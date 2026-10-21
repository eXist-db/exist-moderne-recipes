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

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The methods of the per-module JaCoCo reports ({@code <module>/target/site/jacoco/jacoco.xml}), indexed by
 * module, class and method name. A module without a report has no entry at all: that is how "no coverage data"
 * differs from "covered by no test".
 */
final class JacocoReports {

    private static final String REPORT_SUFFIX = "target/site/jacoco/jacoco.xml";

    /** A JaCoCo counter: missed and covered units. */
    static final class Counter {
        static final Counter NONE = new Counter(0, 0);

        private final int missed;
        private final int covered;

        Counter(final int missed, final int covered) {
            this.missed = missed;
            this.covered = covered;
        }

        int missed() {
            return missed;
        }

        int covered() {
            return covered;
        }
    }

    static final class Method {
        private final List<String> parameters;
        private final Counter instructions;
        private final Counter branches;

        Method(final List<String> parameters, final Counter instructions, final Counter branches) {
            this.parameters = parameters;
            this.instructions = instructions;
            this.branches = branches;
        }

        List<String> parameters() {
            return parameters;
        }

        Counter instructions() {
            return instructions;
        }

        /** @return null if the method has no branches */
        Counter branches() {
            return branches;
        }
    }

    private final Map<String, Map<String, List<Method>>> modules = new HashMap<>();

    boolean isEmpty() {
        return modules.isEmpty();
    }

    boolean hasModule(final String module) {
        return modules.containsKey(module);
    }

    /** Methods of {@code className} named {@code name} in the report of {@code module}; empty if there are none. */
    List<Method> methods(final String module, final String className, final String name) {
        return modules.getOrDefault(module, Collections.<String, List<Method>>emptyMap())
                .getOrDefault(className + '#' + name, Collections.<Method>emptyList());
    }

    /**
     * The methods of {@code className} named {@code name}, one list per report that has any. A class normally is in
     * the report of its own module only; with reports built from merged execution data it may be in several.
     */
    List<List<Method>> methodsInAnyReport(final String className, final String name) {
        final List<List<Method>> found = new ArrayList<>();
        for (final Map<String, List<Method>> report : modules.values()) {
            final List<Method> methods = report.get(className + '#' + name);
            if (methods != null) {
                found.add(methods);
            }
        }
        return found;
    }

    /** Reads every {@code jacoco.xml} below {@code dir}; the module is the path above {@code target/site/jacoco}. */
    static JacocoReports read(final Path dir) {
        final JacocoReports reports = new JacocoReports();
        if (!Files.isDirectory(dir)) {
            return reports;
        }
        final List<Path> files;
        try (Stream<Path> walk = Files.walk(dir)) {
            files = walk.filter(p -> {
                final String path = p.toString().replace('\\', '/');
                return path.equals(REPORT_SUFFIX) || path.endsWith('/' + REPORT_SUFFIX);
            }).sorted().collect(Collectors.toList());
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
        for (final Path file : files) {
            final String relative = dir.relativize(file).toString().replace('\\', '/');
            final String module = relative.length() == REPORT_SUFFIX.length() ? ""
                    : relative.substring(0, relative.length() - REPORT_SUFFIX.length() - 1);
            reports.modules.put(module, readReport(file));
        }
        return reports;
    }

    private static Map<String, List<Method>> readReport(final Path file) {
        final XMLInputFactory factory = XMLInputFactory.newInstance();
        // The report declares a DOCTYPE with report.dtd, which must never be fetched.
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        final Map<String, List<Method>> methods = new HashMap<>();
        try (InputStream in = Files.newInputStream(file)) {
            final XMLStreamReader xml = factory.createXMLStreamReader(in);
            String className = null;
            String name = null;
            List<String> parameters = null;
            Counter instructions = Counter.NONE;
            Counter branches = null;
            while (xml.hasNext()) {
                final int event = xml.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    final String element = xml.getLocalName();
                    if (element.equals("class")) {
                        className = xml.getAttributeValue(null, "name").replace('/', '.');
                    } else if (element.equals("method")) {
                        name = xml.getAttributeValue(null, "name");
                        parameters = MethodSignatures.descriptorParameters(xml.getAttributeValue(null, "desc"));
                        instructions = Counter.NONE;
                        branches = null;
                    } else if (element.equals("counter") && name != null) {
                        final Counter c = new Counter(
                                Integer.parseInt(xml.getAttributeValue(null, "missed")),
                                Integer.parseInt(xml.getAttributeValue(null, "covered")));
                        final String type = xml.getAttributeValue(null, "type");
                        if (type.equals("INSTRUCTION")) {
                            instructions = c;
                        } else if (type.equals("BRANCH")) {
                            branches = c;
                        }
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    if (xml.getLocalName().equals("method") && name != null) {
                        methods.computeIfAbsent(className + '#' + name, k -> new ArrayList<>())
                                .add(new Method(parameters, instructions, branches));
                        name = null;
                    }
                }
            }
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        } catch (final XMLStreamException e) {
            throw new IllegalStateException("Cannot read JaCoCo report " + file, e);
        }
        return methods;
    }
}
