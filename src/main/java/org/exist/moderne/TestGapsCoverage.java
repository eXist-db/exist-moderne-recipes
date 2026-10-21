/*
 * eXist-db Open Source Native XML Database
 * Copyright (C) 2001 The eXist-db Authors
 *
 * info@exist-db.org
 * http://www.exist-db.org
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 */
package org.exist.moderne;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Value;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Option;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.text.PlainText;
import org.openrewrite.text.PlainTextVisitor;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Logger;

/**
 * Appends JaCoCo coverage to Moderne Prethink's {@code test-gaps.csv}.
 * <p>
 * Prethink's static call graph cannot see the runtime dispatch of an interpreter ({@code eval()} of every
 * builtin function and expression is only ever called through a framework), so it lists many covered methods
 * as untested. This recipe joins each row to the method of the per-module JaCoCo reports that CI already
 * produces, by (class, name, erased parameter types), and appends the columns below. The Prethink columns,
 * rows and their order are never touched, so a row that is covered still looks like a gap to a consumer that
 * ignores the new columns.
 * <p>
 * {@code Coverage state} is {@code covered}, {@code partial} or {@code uncovered} for a matched method,
 * {@code no-coverage-data} if neither the module of the row nor any other report holds its class (the tests
 * did not run), and {@code unmatched} if there is a report for the module or the class but no method matched. {@code Adjusted risk score} scales Prethink's
 * {@code Risk score} by the share of the method's instructions that no test reaches.
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class TestGapsCoverage extends Recipe {

    private static final Logger LOG = Logger.getLogger(TestGapsCoverage.class.getName());

    static final List<String> COVERAGE_COLUMNS = Arrays.asList("Coverage state", "Instructions covered",
            "Instructions missed", "Branches covered", "Branches missed", "Match tier", "Adjusted risk score");

    @Getter
    final String displayName = "Add JaCoCo coverage to Prethink's test gaps";

    @Getter
    final String description = "Appends the coverage of JaCoCo reports (`<module>/target/site/jacoco/jacoco.xml`) " +
            "to the rows of Prethink's `test-gaps.csv`: coverage state, instructions and branches covered and " +
            "missed, and how the method was matched. Rows and Prethink's own columns are left as they are. If " +
            "there are no reports the file is left unchanged.";

    @Option(displayName = "JaCoCo reports directory",
            description = "Directory holding the per-module reports, e.g. `jacoco-reports`, relative to the " +
                    "working directory of the run or absolute.",
            example = "jacoco-reports")
    String jacocoReportsDir;

    @Option(displayName = "Test gaps path",
            description = "Path of Prethink's test gaps CSV in the repository. Defaults to " +
                    "`.moderne/context/test-gaps.csv`.",
            example = ".moderne/context/test-gaps.csv",
            required = false)
    String testGapsPath;

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        final String path = testGapsPath == null ? ".moderne/context/test-gaps.csv" : testGapsPath;
        return new PlainTextVisitor<ExecutionContext>() {
            private JacocoReports reports;

            @Override
            public PlainText visitText(final PlainText text, final ExecutionContext ctx) {
                if (!text.getSourcePath().toString().replace('\\', '/').equals(path)) {
                    return text;
                }
                // read on first use: the reports of a large build are only worth parsing if the CSV is there
                if (reports == null) {
                    reports = JacocoReports.read(Paths.get(jacocoReportsDir));
                    if (reports.isEmpty()) {
                        LOG.warning("No JaCoCo reports found in " + Paths.get(jacocoReportsDir).toAbsolutePath()
                                + "; no coverage applied to " + path);
                    }
                }
                if (reports.isEmpty()) {
                    return text;
                }
                final String patched = patch(text.getText(), reports);
                return patched.equals(text.getText()) ? text : text.withText(patched);
            }
        };
    }

    /** Rewrites the CSV with the coverage columns appended (replacing them if a previous run added them). */
    static String patch(final String csv, final JacocoReports reports) {
        final List<Record> records = Record.split(csv);
        if (records.isEmpty()) {
            return csv;
        }
        final Record header = records.get(0);
        final List<String> names = new ArrayList<>(header.values());
        if (!names.isEmpty() && names.get(0).startsWith("\uFEFF")) {
            names.set(0, names.get(0).substring(1));
        }
        final int pathColumn = names.indexOf("Source path");
        final int signatureColumn = names.indexOf("Method signature");
        final int riskColumn = names.indexOf("Risk score");
        if (pathColumn < 0 || signatureColumn < 0) {
            LOG.warning("No coverage applied: the CSV has no 'Source path' and 'Method signature' columns");
            return csv;
        }
        final int needed = Math.max(Math.max(pathColumn, signatureColumn), riskColumn) + 1;
        int original = names.size();
        while (original > 0 && COVERAGE_COLUMNS.contains(names.get(original - 1))) {
            original--;
        }
        final StringBuilder out = new StringBuilder();
        for (int i = 0; i < records.size(); i++) {
            final Record r = records.get(i);
            if (i > 0 && r.values().size() < needed) {
                // a blank or short row has nothing to look up
                out.append(r.text()).append(r.terminator());
                continue;
            }
            out.append(r.text(), 0, r.endOfColumn(original));
            final List<String> added = i == 0 ? COVERAGE_COLUMNS
                    : coverage(r.values().get(pathColumn), r.values().get(signatureColumn),
                    riskColumn < 0 ? "" : r.values().get(riskColumn), reports);
            for (final String value : added) {
                out.append(',').append(quote(value));
            }
            out.append(r.terminator());
        }
        return out.toString();
    }

    private static List<String> coverage(final String sourcePath, final String signature, final String risk,
                                         final JacocoReports reports) {
        final boolean moduleHasReport = reports.hasModule(module(sourcePath));
        final MethodSignatures.Erased method = MethodSignatures.parse(signature);
        final List<List<JacocoReports.Method>> inReports = method == null ? new ArrayList<>()
                : reports.methodsInAnyReport(method.className(), method.name());
        Match best = null;
        for (final List<JacocoReports.Method> candidates : inReports) {
            final Match match = match(candidates, method);
            // with merged reports a method is in several: the best coverage wins, an exact match over a guess
            if (match != null && (best == null || match.isBetterThan(best))) {
                best = match;
            }
        }
        if (best == null) {
            return Arrays.asList(moduleHasReport || !inReports.isEmpty() ? "unmatched" : "no-coverage-data",
                    "", "", "", "", "none", risk);
        }
        final JacocoReports.Counter i = best.method.instructions();
        final String state = i.covered() == 0 ? "uncovered" : i.missed() == 0 ? "covered" : "partial";
        final JacocoReports.Counter b = best.method.branches();
        return Arrays.asList(state, String.valueOf(i.covered()), String.valueOf(i.missed()),
                b == null ? "" : String.valueOf(b.covered()), b == null ? "" : String.valueOf(b.missed()), best.tier,
                adjustedRisk(risk, i));
    }

    /** The method of one report that a Prethink method is, or null. */
    private static Match match(final List<JacocoReports.Method> candidates, final MethodSignatures.Erased method) {
        final List<String> parameters = method.parameters();
        final boolean constructor = method.name().equals("<init>");
        // k leading descriptor parameters are hidden: the outer instance of an inner class, (String, int) of an enum
        for (int k = 0; k <= (constructor ? 2 : 0); k++) {
            JacocoReports.Method best = null;
            for (final JacocoReports.Method c : candidates) {
                if (c.parameters().size() >= k && c.parameters().subList(k, c.parameters().size()).equals(parameters)
                        && (best == null || total(c) > total(best))) {
                    best = c;   // bridge and synthetic duplicates: the real body has the most instructions
                }
            }
            if (best != null) {
                return new Match(best, "exact");
            }
        }
        if (!parameters.contains("{undefined}")) {
            return null;   // only a type Prethink could not resolve justifies guessing by arity
        }
        final List<JacocoReports.Method> byArity = new ArrayList<>();
        for (final JacocoReports.Method c : candidates) {
            final int size = c.parameters().size();
            if (size >= parameters.size() && size <= parameters.size() + (constructor ? 2 : 0)) {
                byArity.add(c);
            }
        }
        return byArity.size() == 1 ? new Match(byArity.get(0), "arity-unique") : null;
    }

    /** The risk score scaled by the share of the method's instructions that no test reaches. */
    static String adjustedRisk(final String risk, final JacocoReports.Counter instructions) {
        final int total = instructions.missed() + instructions.covered();
        if (total == 0) {
            return risk;
        }
        try {
            return new BigDecimal(risk.trim()).multiply(BigDecimal.valueOf(instructions.missed()))
                    .divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
        } catch (final NumberFormatException e) {
            return "";
        }
    }

    private static final class Match {
        private final JacocoReports.Method method;
        private final String tier;

        Match(final JacocoReports.Method method, final String tier) {
            this.method = method;
            this.tier = tier;
        }

        boolean isBetterThan(final Match other) {
            if (tier.equals(other.tier)) {
                return method.instructions().covered() > other.method.instructions().covered();
            }
            return tier.equals("exact");
        }
    }

    private static int total(final JacocoReports.Method m) {
        return m.instructions().missed() + m.instructions().covered();
    }

    /** The module of a source path: the part before {@code /src/main/}, empty for a single-module build. */
    static String module(final String sourcePath) {
        final String path = sourcePath.replace('\\', '/');
        if (path.startsWith("src/main/")) {
            return "";
        }
        final int i = path.indexOf("/src/main/");
        return i < 0 ? path : path.substring(0, i);
    }

    private static String quote(final String value) {
        return value.contains(",") || value.contains("\"") ? '"' + value.replace("\"", "\"\"") + '"' : value;
    }

    /** One CSV record: its raw text (without terminator), the end offset of every column, and the terminator. */
    private static final class Record {
        private final String text;
        private final List<Integer> ends;
        private final List<String> values;
        private final String terminator;

        Record(final String text, final List<Integer> ends, final List<String> values, final String terminator) {
            this.text = text;
            this.ends = ends;
            this.values = values;
            this.terminator = terminator;
        }

        String text() {
            return text;
        }

        List<String> values() {
            return values;
        }

        String terminator() {
            return terminator;
        }

        int endOfColumn(final int count) {
            return count == 0 ? 0 : ends.get(Math.min(count, ends.size()) - 1);
        }

        static List<Record> split(final String csv) {
            final List<Record> records = new ArrayList<>();
            int start = 0;
            while (start < csv.length()) {
                final List<Integer> ends = new ArrayList<>();
                final List<String> values = new ArrayList<>();
                final StringBuilder value = new StringBuilder();
                boolean quoted = false;
                int i = start;
                for (; i < csv.length(); i++) {
                    final char c = csv.charAt(i);
                    if (quoted) {
                        if (c == '"' && i + 1 < csv.length() && csv.charAt(i + 1) == '"') {
                            value.append('"');
                            i++;
                        } else if (c == '"') {
                            quoted = false;
                        } else {
                            value.append(c);
                        }
                    } else if (c == '"') {
                        quoted = true;
                    } else if (c == ',') {
                        ends.add(i - start);
                        values.add(value.toString());
                        value.setLength(0);
                    } else if (c == '\n' || c == '\r') {
                        break;
                    } else {
                        value.append(c);
                    }
                }
                ends.add(i - start);
                values.add(value.toString());
                final String terminator = csv.startsWith("\r\n", i) ? "\r\n" : i < csv.length() ? csv.substring(i, i + 1) : "";
                records.add(new Record(csv.substring(start, i), ends, values, terminator));
                start = i + terminator.length();
            }
            return records;
        }
    }
}
