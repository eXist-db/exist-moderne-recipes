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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import java.io.IOException;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.openrewrite.test.SourceSpecs.text;

class TestGapsCoverageTest implements RewriteTest {

    private static final String HEADER = "Source path,Class name,Method name,Method signature,"
            + "Cyclomatic complexity,Risk score,Gap reason,Suggested test class";

    private static final String NEW_COLUMNS = ",Coverage state,Instructions covered,Instructions missed,"
            + "Branches covered,Branches missed,Match tier,Adjusted risk score";

    private static final String OLD_COLUMNS = ",Coverage state,Instructions covered,Instructions missed,"
            + "Branches covered,Branches missed,Match tier";

    /** One class per situation a Prethink row can be in; all in module mod-a, whose tests ran. */
    private static final String MOD_A = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <!DOCTYPE report PUBLIC "-//JACOCO//DTD Report 1.1//EN" "report.dtd">
            <report name="mod-a">
              <package name="a">
                <class name="a/Foo" sourcefilename="Foo.java">
                  <method name="covered" desc="()V" line="1">
                    <counter type="INSTRUCTION" missed="0" covered="5"/>
                    <counter type="BRANCH" missed="0" covered="2"/>
                  </method>
                  <method name="partial" desc="(I)V" line="2">
                    <counter type="INSTRUCTION" missed="3" covered="5"/>
                    <counter type="BRANCH" missed="1" covered="1"/>
                  </method>
                  <method name="uncovered" desc="()V" line="3">
                    <counter type="INSTRUCTION" missed="7" covered="0"/>
                  </method>
                  <method name="over" desc="(Ljava/lang/String;)V" line="4">
                    <counter type="INSTRUCTION" missed="0" covered="2"/>
                  </method>
                  <method name="over" desc="(I)V" line="5">
                    <counter type="INSTRUCTION" missed="4" covered="0"/>
                  </method>
                  <method name="get" desc="()Ljava/lang/Object;" line="6">
                    <counter type="INSTRUCTION" missed="0" covered="3"/>
                  </method>
                  <method name="get" desc="()Ljava/lang/String;" line="6">
                    <counter type="INSTRUCTION" missed="0" covered="9"/>
                  </method>
                  <method name="weird" desc="(Ljava/lang/String;)V" line="7">
                    <counter type="INSTRUCTION" missed="1" covered="1"/>
                  </method>
                  <method name="generic" desc="(Ljava/util/Map;Ljava/lang/Object;[Lorg/x/Y;)V" line="8">
                    <counter type="INSTRUCTION" missed="0" covered="1"/>
                  </method>
                </class>
                <class name="a/Foo$Inner" sourcefilename="Foo.java">
                  <method name="&lt;init&gt;" desc="(La/Foo;I)V" line="10">
                    <counter type="INSTRUCTION" missed="0" covered="6"/>
                  </method>
                </class>
                <class name="a/Color" sourcefilename="Color.java">
                  <method name="&lt;init&gt;" desc="(Ljava/lang/String;II)V" line="11">
                    <counter type="INSTRUCTION" missed="2" covered="0"/>
                  </method>
                </class>
              </package>
            </report>
            """;

    @TempDir
    Path reports;

    @BeforeEach
    void writeReports() throws IOException {
        JacocoReportsTest.write(reports, "mod-a", MOD_A);
    }

    @Override
    public void defaults(final RecipeSpec spec) {
        spec.recipe(new TestGapsCoverage(reports.toString(), null));
    }

    private static String row(final String module, final String cls, final String name, final String parameters) {
        return module + "/src/main/java/" + cls.replace('.', '/') + ".java," + cls + "," + name + ",\""
                + cls + "{name=" + name + ",return=void,parameters=[" + parameters + "]}\",1,2,\"x\",T";
    }

    @Test
    void appendsCoverageToEveryRowAndKeepsTheRestByteForByte() {
        final String before = HEADER + "\n"
                + row("mod-a", "a.Foo", "covered", "") + "\n"
                + row("mod-a", "a.Foo", "partial", "int") + "\n"
                + row("mod-a", "a.Foo", "uncovered", "") + "\n"
                + row("mod-b", "b.Bar", "start", "") + "\n"
                + row("mod-a", "a.Missing", "run", "") + "\n";
        final String after = HEADER + NEW_COLUMNS + "\n"
                + row("mod-a", "a.Foo", "covered", "") + ",covered,5,0,2,0,exact,0\n"
                + row("mod-a", "a.Foo", "partial", "int") + ",partial,5,3,1,1,exact,0.75\n"
                + row("mod-a", "a.Foo", "uncovered", "") + ",uncovered,0,7,,,exact,2\n"
                + row("mod-b", "b.Bar", "start", "") + ",no-coverage-data,,,,,none,2\n"
                + row("mod-a", "a.Missing", "run", "") + ",unmatched,,,,,none,2\n";
        rewriteRun(text(before, after, s -> s.path(".moderne/context/test-gaps.csv")));
    }

    @Test
    void matchesOverloadsByErasedParameterTypes() {
        final String before = HEADER + "\n"
                + row("mod-a", "a.Foo", "over", "java.lang.String") + "\n"
                + row("mod-a", "a.Foo", "over", "int") + "\n";
        final String after = HEADER + NEW_COLUMNS + "\n"
                + row("mod-a", "a.Foo", "over", "java.lang.String") + ",covered,2,0,,,exact,0\n"
                + row("mod-a", "a.Foo", "over", "int") + ",uncovered,0,4,,,exact,2\n";
        rewriteRun(text(before, after, s -> s.path(".moderne/context/test-gaps.csv")));
    }

    @Test
    void takesTheBodyOfTwoMethodsThatDifferOnlyInReturnType() {
        final String before = HEADER + "\n" + row("mod-a", "a.Foo", "get", "") + "\n";
        final String after = HEADER + NEW_COLUMNS + "\n" + row("mod-a", "a.Foo", "get", "") + ",covered,9,0,,,exact,0\n";
        rewriteRun(text(before, after, s -> s.path(".moderne/context/test-gaps.csv")));
    }

    @Test
    void matchesGenericParametersAfterErasure() {
        final String before = HEADER + "\n"
                + row("mod-a", "a.Foo", "generic", "java.util.Map<java.lang.String, java.lang.Object>,Generic{T},"
                + "Generic{T extends org.x.Y}[]") + "\n";
        final String after = HEADER + NEW_COLUMNS + "\n"
                + row("mod-a", "a.Foo", "generic", "java.util.Map<java.lang.String, java.lang.Object>,Generic{T},"
                + "Generic{T extends org.x.Y}[]") + ",covered,1,0,,,exact,0\n";
        rewriteRun(text(before, after, s -> s.path(".moderne/context/test-gaps.csv")));
    }

    @Test
    void matchesInnerClassAndEnumConstructorsWithHiddenLeadingParameters() {
        final String before = HEADER + "\n"
                + row("mod-a", "a.Foo$Inner", "<constructor>", "int") + "\n"
                + row("mod-a", "a.Color", "<constructor>", "int") + "\n";
        final String after = HEADER + NEW_COLUMNS + "\n"
                + row("mod-a", "a.Foo$Inner", "<constructor>", "int") + ",covered,6,0,,,exact,0\n"
                + row("mod-a", "a.Color", "<constructor>", "int") + ",uncovered,0,2,,,exact,2\n";
        rewriteRun(text(before, after, s -> s.path(".moderne/context/test-gaps.csv")));
    }

    @Test
    void fallsBackToTheOnlyMethodOfCompatibleArity() {
        final String before = HEADER + "\n" + row("mod-a", "a.Foo", "weird", "{undefined}") + "\n";
        final String after = HEADER + NEW_COLUMNS + "\n"
                + row("mod-a", "a.Foo", "weird", "{undefined}") + ",partial,1,1,,,arity-unique,1\n";
        rewriteRun(text(before, after, s -> s.path(".moderne/context/test-gaps.csv")));
    }

    @Test
    void doesNotGuessWhenSeveralMethodsFitTheArity() {
        // over(String) and over(int) both have arity 1
        final String before = HEADER + "\n" + row("mod-a", "a.Foo", "over", "{undefined}") + "\n";
        final String after = HEADER + NEW_COLUMNS + "\n"
                + row("mod-a", "a.Foo", "over", "{undefined}") + ",unmatched,,,,,none,2\n";
        rewriteRun(text(before, after, s -> s.path(".moderne/context/test-gaps.csv")));
    }

    @Test
    void replacesColumnsAddedByAnEarlierRun() {
        final String once = HEADER + OLD_COLUMNS + "\n" + row("mod-a", "a.Foo", "covered", "") + ",uncovered,0,9,,,exact\n";
        final String again = HEADER + NEW_COLUMNS + "\n" + row("mod-a", "a.Foo", "covered", "") + ",covered,5,0,2,0,exact,0\n";
        rewriteRun(text(once, again, s -> s.path(".moderne/context/test-gaps.csv")));
    }

    @Test
    void keepsQuotedFieldsWithCommasQuotesAndLineBreaksAndCrLf() {
        final String reason = "\"complexity 5, \"\"hot\"\"\r\nsecond line\"";
        final String line = "mod-a/src/main/java/a/Foo.java,a.Foo,covered,\"a.Foo{name=covered,return=void,parameters=[]}\",1,2,"
                + reason + ",T";
        rewriteRun(text(HEADER + "\r\n" + line + "\r\n", HEADER + NEW_COLUMNS + "\r\n" + line + ",covered,5,0,2,0,exact,0\r\n",
                s -> s.path(".moderne/context/test-gaps.csv")));
    }

    @Test
    void keepsRowWithoutTrailingNewline() {
        final String row = row("mod-a", "a.Foo", "covered", "");
        rewriteRun(text(HEADER + "\n" + row, HEADER + NEW_COLUMNS + "\n" + row + ",covered,5,0,2,0,exact,0",
                s -> s.path(".moderne/context/test-gaps.csv")));
    }

    @Test
    void ignoresOtherFiles() {
        rewriteRun(text(HEADER + "\n" + row("mod-a", "a.Foo", "covered", "") + "\n", s -> s.path("other.csv")));
    }

    @Test
    void usesTheConfiguredPath() {
        rewriteRun(
                spec -> spec.recipe(new TestGapsCoverage(reports.toString(), "gaps/t.csv")),
                text(HEADER + "\n" + row("mod-a", "a.Foo", "covered", "") + "\n",
                        HEADER + NEW_COLUMNS + "\n" + row("mod-a", "a.Foo", "covered", "") + ",covered,5,0,2,0,exact,0\n",
                        s -> s.path("gaps/t.csv")),
                text(HEADER + "\n", s -> s.path(".moderne/context/test-gaps.csv")));
    }

    @Test
    void leavesTheCsvUnchangedWithoutReports() {
        rewriteRun(
                spec -> spec.recipe(new TestGapsCoverage(reports.resolve("missing").toString(), null)),
                text(HEADER + "\n" + row("mod-a", "a.Foo", "covered", "") + "\n",
                        s -> s.path(".moderne/context/test-gaps.csv")));
    }

    @Test
    void leavesTheCsvUnchangedForAnEmptyReportsDirectory(@TempDir final Path empty) {
        rewriteRun(
                spec -> spec.recipe(new TestGapsCoverage(empty.toString(), null)),
                text(HEADER + "\n" + row("mod-a", "a.Foo", "covered", "") + "\n",
                        s -> s.path(".moderne/context/test-gaps.csv")));
    }

    /** Reports built from merged execution data hold the same class in several modules. */
    private static final String MOD_B = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <!DOCTYPE report PUBLIC "-//JACOCO//DTD Report 1.1//EN" "report.dtd">
            <report name="mod-b">
              <package name="a">
                <class name="a/Foo" sourcefilename="Foo.java">
                  <method name="partial" desc="(I)V" line="2">
                    <counter type="INSTRUCTION" missed="1" covered="7"/>
                  </method>
                  <method name="over" desc="(Ljava/lang/String;)V" line="4">
                    <counter type="INSTRUCTION" missed="9" covered="0"/>
                  </method>
                </class>
                <class name="a/Other" sourcefilename="Other.java">
                  <method name="run" desc="()V" line="1">
                    <counter type="INSTRUCTION" missed="0" covered="3"/>
                  </method>
                </class>
              </package>
            </report>
            """;

    @Test
    void takesTheBestCoverageOfAMethodThatSeveralReportsHold() throws IOException {
        JacocoReportsTest.write(reports, "mod-b", MOD_B);
        final String before = HEADER + "\n"
                + row("mod-a", "a.Foo", "partial", "int") + "\n"
                + row("mod-a", "a.Foo", "over", "java.lang.String") + "\n";
        final String after = HEADER + NEW_COLUMNS + "\n"
                + row("mod-a", "a.Foo", "partial", "int") + ",partial,7,1,,,exact,0.25\n"
                + row("mod-a", "a.Foo", "over", "java.lang.String") + ",covered,2,0,,,exact,0\n";
        rewriteRun(text(before, after, s -> s.path(".moderne/context/test-gaps.csv")));
    }

    @Test
    void usesAReportOfAnotherModuleForAClassOfAModuleWithoutReport() throws IOException {
        JacocoReportsTest.write(reports, "mod-b", MOD_B);
        final String before = HEADER + "\n" + row("mod-c", "a.Other", "run", "") + "\n";
        final String after = HEADER + NEW_COLUMNS + "\n" + row("mod-c", "a.Other", "run", "") + ",covered,3,0,,,exact,0\n";
        rewriteRun(text(before, after, s -> s.path(".moderne/context/test-gaps.csv")));
    }

    @Test
    void roundsTheAdjustedRiskToTwoDecimals() {
        assertThat(TestGapsCoverage.adjustedRisk("10", new JacocoReports.Counter(1, 2))).isEqualTo("3.33");
        assertThat(TestGapsCoverage.adjustedRisk("2", new JacocoReports.Counter(1, 2))).isEqualTo("0.67");
        assertThat(TestGapsCoverage.adjustedRisk("12", new JacocoReports.Counter(3, 1))).isEqualTo("9");
        assertThat(TestGapsCoverage.adjustedRisk("5", new JacocoReports.Counter(0, 4))).isEqualTo("0");
    }

    @Test
    void keepsTheRiskScoreOfMethodsWithoutInstructionsAndLeavesUnparsableOnesEmpty() {
        assertThat(TestGapsCoverage.adjustedRisk("5", new JacocoReports.Counter(0, 0))).isEqualTo("5");
        assertThat(TestGapsCoverage.adjustedRisk("high", new JacocoReports.Counter(1, 1))).isEmpty();
    }

    @Test
    void leavesBlankAndShortRowsAlone() {
        final String before = HEADER + "\n\n" + row("mod-a", "a.Foo", "covered", "") + "\nshort,row\n\n";
        final String after = HEADER + NEW_COLUMNS + "\n\n"
                + row("mod-a", "a.Foo", "covered", "") + ",covered,5,0,2,0,exact,0\nshort,row\n\n";
        rewriteRun(text(before, after, s -> s.path(".moderne/context/test-gaps.csv")));
    }

    @Test
    void readsAHeaderWithAByteOrderMark() {
        final String row = row("mod-a", "a.Foo", "covered", "");
        rewriteRun(text("\uFEFF" + HEADER + "\n" + row + "\n", "\uFEFF" + HEADER + NEW_COLUMNS + "\n" + row
                + ",covered,5,0,2,0,exact,0\n", s -> s.path(".moderne/context/test-gaps.csv")));
    }

    @Test
    void doesNotShiftParametersOfPlainMethods() {
        // only a constructor has hidden leading parameters; generic(Map, Object, Y[]) is not generic(Object, Y[])
        final String row = row("mod-a", "a.Foo", "generic", "java.lang.Object,org.x.Y[]");
        rewriteRun(text(HEADER + "\n" + row + "\n", HEADER + NEW_COLUMNS + "\n" + row + ",unmatched,,,,,none,2\n",
                s -> s.path(".moderne/context/test-gaps.csv")));
    }

    @Test
    void doesNotGuessByArityForResolvedTypes() {
        final String row = row("mod-a", "a.Foo", "weird", "java.lang.Integer");
        rewriteRun(text(HEADER + "\n" + row + "\n", HEADER + NEW_COLUMNS + "\n" + row + ",unmatched,,,,,none,2\n",
                s -> s.path(".moderne/context/test-gaps.csv")));
    }

    @Test
    void derivesTheModuleFromTheSourcePath() {
        assertThat(TestGapsCoverage.module("exist-core/src/main/java/a/Foo.java")).isEqualTo("exist-core");
        assertThat(TestGapsCoverage.module("extensions/indexes/lucene/src/main/java/a/Foo.java"))
                .isEqualTo("extensions/indexes/lucene");
        assertThat(TestGapsCoverage.module("src/main/java/a/Foo.java")).isEmpty();
    }
}
