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

import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.Test;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;
import org.openrewrite.test.SourceSpecs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.openrewrite.java.Assertions.java;
import static org.openrewrite.test.SourceSpecs.text;
import static org.openrewrite.xml.Assertions.xml;

class XQueryDynamicInvocationCoverageTest implements RewriteTest {

    private static final String UTIL_NS = "http://exist-db.org/xquery/util";
    private static final String FN_NS = "http://www.w3.org/2005/xpath-functions";
    private static final String NGRAM_NS = "http://exist-db.org/xquery/ngram";

    @Override
    public void defaults(final RecipeSpec spec) {
        spec.recipe(new XQueryDynamicInvocationCoverage());
    }

    private static final String FUNCTION_STUB = """
            package org.exist.xquery;
            public abstract class Function {
                public final static String BUILTIN_FUNCTION_NS = "http://www.w3.org/2005/xpath-functions";
            }
            """;

    private static final String BASIC_FUNCTION_STUB = """
            package org.exist.xquery;
            public abstract class BasicFunction extends Function {
            }
            """;

    private static final String USER_DEFINED_FUNCTION_STUB = """
            package org.exist.xquery;
            public class UserDefinedFunction extends Function {
            }
            """;

    private static final String FUNCTION_SIGNATURE_STUB = """
            package org.exist.xquery;
            import org.exist.dom.QName;
            public class FunctionSignature {
                public FunctionSignature(QName name, String description) {}
            }
            """;

    private static final String FUNCTION_DSL_STUB = """
            package org.exist.xquery;
            import org.exist.dom.QName;
            public class FunctionDSL {
                public static FunctionSignature functionSignature(QName name, String description) {
                    return new FunctionSignature(name, description);
                }
            }
            """;

    private static final String QNAME_STUB = """
            package org.exist.dom;
            public class QName {
                public QName(String localName, String namespaceURI) {}
                public QName(String localName, String namespaceURI, String prefix) {}
                public String getLocalPart() { return null; }
            }
            """;

    private static final String UTIL_MODULE_STUB = """
            package org.exist.xquery.functions.util;
            import org.exist.dom.QName;
            import org.exist.xquery.FunctionDSL;
            import org.exist.xquery.FunctionSignature;
            public class UtilModule {
                public final static String NAMESPACE_URI = "http://exist-db.org/xquery/util";
                public final static String PREFIX = "util";
                static FunctionSignature functionSignature(String name, String description) {
                    return FunctionDSL.functionSignature(new QName(name, NAMESPACE_URI, PREFIX), description);
                }
            }
            """;

    private static SourceSpecs[] with(final SourceSpecs... specs) {
        final SourceSpecs[] stubs = {
                java(FUNCTION_STUB),
                java(BASIC_FUNCTION_STUB),
                java(USER_DEFINED_FUNCTION_STUB),
                java(FUNCTION_SIGNATURE_STUB),
                java(FUNCTION_DSL_STUB),
                java(QNAME_STUB),
                java(UTIL_MODULE_STUB)
        };
        final SourceSpecs[] all = new SourceSpecs[stubs.length + specs.length];
        System.arraycopy(stubs, 0, all, 0, stubs.length);
        System.arraycopy(specs, 0, all, stubs.length, specs.length);
        return all;
    }

    /** A BasicFunction {@code p.F} at {@code p/F.java} with the given class body. */
    private static SourceSpecs function(final String body) {
        return java("""
                package p;
                import org.exist.xquery.BasicFunction;
                import org.exist.xquery.Function;
                import org.exist.xquery.FunctionSignature;
                import org.exist.xquery.functions.util.UtilModule;
                import org.exist.dom.QName;
                public class F extends BasicFunction {
                %s
                }
                """.formatted(body));
    }

    /** A util-module function {@code p.F} registering {@code util:<localName>}. */
    private static SourceSpecs utilFunction(final String localName) {
        return function("public static final FunctionSignature signature = new FunctionSignature("
                + "new QName(\"" + localName + "\", UtilModule.NAMESPACE_URI, UtilModule.PREFIX), \"desc\");");
    }

    private static XQueryDynamicInvocationCoverageTable.Row row(final String localName, final boolean hit,
            final String matchingPaths) {
        return new XQueryDynamicInvocationCoverageTable.Row("p/F.java", "p.F", UTIL_NS, localName, hit, matchingPaths);
    }

    private static void expect(final RecipeSpec spec, final XQueryDynamicInvocationCoverageTable.Row... rows) {
        spec.dataTable(XQueryDynamicInvocationCoverageTable.Row.class, actual ->
                assertThat(actual).containsExactly(rows));
    }

    private static SourceSpecs testJava(final String path, final String query) {
        return java("""
                package p;
                public class FTest {
                    String query = "%s";
                }
                """.formatted(query.replace("\"", "\\\"")), s -> s.path(path));
    }

    @Test
    void reportsFunctionAsDynamicallyInvokedWhenTestStringLiteralMatches() {
        rewriteRun(
                spec -> expect(spec, row("system-property", true, "src/test/java/p/FTest.java")),
                with(
                        utilFunction("system-property"),
                        testJava("src/test/java/p/FTest.java", "util:system-property('vendor')")
                )
        );
    }

    @Test
    void reportsFunctionAsNotDynamicallyInvokedWhenNoTestMatches() {
        rewriteRun(
                spec -> expect(spec, row("system-property", false, "")),
                with(
                        utilFunction("system-property"),
                        testJava("src/test/java/p/FTest.java", "util:available-system-properties()")
                )
        );
    }

    @Test
    void findsInvocationInXqSuiteModule() {
        rewriteRun(
                spec -> expect(spec, row("system-property", true, "src/test/xquery/system-property-test.xql")),
                with(
                        utilFunction("system-property"),
                        text(
                                """
                                module namespace t = "http://exist-db.org/xquery/test/system-property";
                                declare namespace test = "http://exist-db.org/xquery/xqsuite";
                                declare %test:assertTrue function t:vendor() { util:system-property('vendor') };
                                """,
                                s -> s.path("src/test/xquery/system-property-test.xql")
                        )
                )
        );
    }

    @Test
    void skipsPendingXqSuiteTests() {
        rewriteRun(
                spec -> expect(spec, row("system-property", false, "")),
                with(
                        utilFunction("system-property"),
                        text(
                                """
                                module namespace t = "http://exist-db.org/xquery/test/system-property";
                                declare namespace test = "http://exist-db.org/xquery/xqsuite";
                                declare
                                    %test:pending("broken")
                                    %test:assertTrue
                                function t:vendor() { util:system-property('vendor') };
                                declare variable $t:x := 1;
                                """,
                                s -> s.path("src/test/xquery/t.xql")
                        )
                )
        );
    }

    @Test
    void findsInvocationInXmlTestSetCodeOnly() {
        rewriteRun(
                spec -> expect(spec, row("foo-bar", true, "src/test/xquery/run.xml")),
                with(
                        utilFunction("foo-bar"),
                        xml(
                                """
                                <TestSet>
                                    <testName>t</testName>
                                    <description>covers util:foo-bar() eventually</description>
                                    <test>
                                        <task>util:foo-bar()</task>
                                        <code><![CDATA[util:foo-bar(1) < 2]]></code>
                                        <expected>true</expected>
                                    </test>
                                </TestSet>
                                """,
                                s -> s.path("src/test/xquery/run.xml")
                        )
                )
        );
    }

    @Test
    void ignoresXmlTestSetDescriptionsExpectationsAndIgnoredTests() {
        rewriteRun(
                spec -> expect(spec, row("foo-bar", false, "")),
                with(
                        utilFunction("foo-bar"),
                        xml(
                                """
                                <TestSet>
                                    <testName>t</testName>
                                    <description>covers util:foo-bar() eventually</description>
                                    <test ignore="yes">
                                        <task>t</task>
                                        <code>util:foo-bar(1)</code>
                                    </test>
                                    <test>
                                        <task>util:foo-bar()</task>
                                        <code>1</code>
                                        <expected>util:foo-bar(1)</expected>
                                    </test>
                                </TestSet>
                                """,
                                s -> s.path("src/test/xquery/ignored.xml")
                        ),
                        xml("<data><q>util:foo-bar(1)</q></data>", s -> s.path("src/test/resources/data.xml"))
                )
        );
    }

    @Test
    void findsInvocationInXmlTestSetPrologAndXPath() {
        rewriteRun(
                spec -> spec.dataTable(XQueryDynamicInvocationCoverageTable.Row.class, rows ->
                        assertThat(rows).extracting(XQueryDynamicInvocationCoverageTable.Row::isDynamicallyInvokedInTests)
                                .containsExactly(true, true)),
                with(
                        function("""
                                static final FunctionSignature[] signatures = {
                                    new FunctionSignature(new QName("in-functions", UtilModule.NAMESPACE_URI, UtilModule.PREFIX), "desc"),
                                    new FunctionSignature(new QName("in-xpath", UtilModule.NAMESPACE_URI, UtilModule.PREFIX), "desc")
                                };
                                """),
                        xml(
                                """
                                <TestSet>
                                    <testName>t</testName>
                                    <functions>declare function local:f() { util:in-functions() };</functions>
                                    <test>
                                        <task>t</task>
                                        <code>local:f()</code>
                                        <xpath>util:in-xpath(.)</xpath>
                                    </test>
                                </TestSet>
                                """,
                                s -> s.path("src/test/xquery/prolog.xml")
                        )
                )
        );
    }

    @Test
    void resolvesNamespaceThroughModuleSignatureHelper() {
        rewriteRun(
                spec -> spec.dataTable(XQueryDynamicInvocationCoverageTable.Row.class, rows ->
                        assertThat(rows).containsExactly(
                                new XQueryDynamicInvocationCoverageTable.Row(
                                        "org/exist/xquery/functions/util/AvailableSystemProperties.java",
                                        "org.exist.xquery.functions.util.AvailableSystemProperties",
                                        UTIL_NS,
                                        "available-system-properties",
                                        true,
                                        "src/test/java/org/exist/xquery/functions/util/SystemPropertyTest.java"
                                )
                        )
                ),
                with(
                        java(
                                """
                                package org.exist.xquery.functions.util;
                                import org.exist.xquery.BasicFunction;
                                import org.exist.xquery.FunctionSignature;
                                import static org.exist.xquery.functions.util.UtilModule.functionSignature;
                                public class AvailableSystemProperties extends BasicFunction {
                                    private final static String FS_NAME = "available-system-properties";
                                    public static final FunctionSignature SIGNATURE = functionSignature(FS_NAME, "desc");
                                }
                                """
                        ),
                        testJava("src/test/java/org/exist/xquery/functions/util/SystemPropertyTest.java",
                                "util:available-system-properties()")
                )
        );
    }

    @Test
    void distinguishesFunctionsSharingALocalNameByNamespace() {
        rewriteRun(
                spec -> spec.dataTable(XQueryDynamicInvocationCoverageTable.Row.class, rows ->
                        assertThat(rows)
                                .extracting(XQueryDynamicInvocationCoverageTable.Row::getNamespaceUri,
                                        XQueryDynamicInvocationCoverageTable.Row::isDynamicallyInvokedInTests)
                                .containsExactly(Tuple.tuple(FN_NS, true), Tuple.tuple(NGRAM_NS, false))),
                with(
                        java(
                                """
                                package p;
                                import org.exist.xquery.Function;
                                import org.exist.xquery.FunctionSignature;
                                import org.exist.dom.QName;
                                public class FunContains extends Function {
                                    static final FunctionSignature S = new FunctionSignature(new QName("contains", Function.BUILTIN_FUNCTION_NS), "");
                                }
                                """
                        ),
                        java(
                                """
                                package p;
                                import org.exist.xquery.BasicFunction;
                                import org.exist.xquery.FunctionSignature;
                                import org.exist.dom.QName;
                                public class NGramSearch extends BasicFunction {
                                    static final FunctionSignature S = new FunctionSignature(new QName("contains", "http://exist-db.org/xquery/ngram", "ngram"), "");
                                }
                                """
                        ),
                        text("contains('abc', 'b'), fn:contains('a', 'a')", s -> s.path("src/test/xquery/t.xql"))
                )
        );
    }

    @Test
    void resolvesPrefixesDeclaredInTheTest() {
        rewriteRun(
                spec -> expect(spec, row("foo-bar", true, "src/test/xquery/t.xql")),
                with(
                        utilFunction("foo-bar"),
                        text("""
                                declare namespace u = "http://exist-db.org/xquery/util";
                                u:foo-bar(1)
                                """, s -> s.path("src/test/xquery/t.xql"))
                )
        );
    }

    @Test
    void doesNotMatchModuleDefaultPrefixRedeclaredToAnotherNamespace() {
        rewriteRun(
                spec -> expect(spec, row("foo-bar", false, "")),
                with(
                        utilFunction("foo-bar"),
                        text("""
                                declare namespace util = "http://example.com/not-util";
                                util:foo-bar(1)
                                """, s -> s.path("src/test/xquery/t.xql"))
                )
        );
    }

    @Test
    void countsTestRunnerLibrariesAsExecutedByTests() {
        rewriteRun(
                spec -> expect(spec, row("eval", true,
                        "exist-core/src/main/resources/org/exist/xquery/lib/xqsuite/xqsuite.xql")),
                with(
                        utilFunction("eval"),
                        text("util:eval($code)",
                                s -> s.path("exist-core/src/main/resources/org/exist/xquery/lib/xqsuite/xqsuite.xql"))
                )
        );
    }

    @Test
    void findsInvocationsInBatsTestsAndRestQueryBodies() {
        rewriteRun(
                spec -> spec.dataTable(XQueryDynamicInvocationCoverageTable.Row.class, rows ->
                        assertThat(rows).extracting(XQueryDynamicInvocationCoverageTable.Row::getMatchingTestPaths)
                                .containsExactly("exist-docker/src/test/bats/03-xquery-spec.bats",
                                        "exist-docker/src/test/bats/fixtures/q.xml")),
                with(
                        function("""
                                static final FunctionSignature[] signatures = {
                                    new FunctionSignature(new QName("in-bats", UtilModule.NAMESPACE_URI, UtilModule.PREFIX), "desc"),
                                    new FunctionSignature(new QName("in-rest-query", UtilModule.NAMESPACE_URI, UtilModule.PREFIX), "desc")
                                };
                                """),
                        text("""
                                @test "query" {
                                  run curl -s "http://127.0.0.1:8080/exist/rest/db?_query=util:in-bats()&_wrap=no"
                                }
                                """, s -> s.path("exist-docker/src/test/bats/03-xquery-spec.bats")),
                        xml("""
                                <query xmlns="http://exist.sourceforge.net/NS/exist" wrap="no">
                                    <text><![CDATA[ util:in-rest-query("admin") ]]></text>
                                </query>
                                """, s -> s.path("exist-docker/src/test/bats/fixtures/q.xml"))
                )
        );
    }

    @Test
    void resolvesLocalPartOfQNameConstant() {
        rewriteRun(
                spec -> expect(spec, row("resource-functions", false, "")),
                with(
                        function("""
                                private final static QName RESOURCE_FUNCTIONS = new QName("resource-functions", UtilModule.NAMESPACE_URI, UtilModule.PREFIX);
                                static final FunctionSignature S = new FunctionSignature(
                                        new QName(RESOURCE_FUNCTIONS.getLocalPart(), UtilModule.NAMESPACE_URI, UtilModule.PREFIX), "");
                                """)
                )
        );
    }

    @Test
    void fallsBackToLocalNameWhenNamespaceIsUnresolved() {
        rewriteRun(
                spec -> spec.dataTable(XQueryDynamicInvocationCoverageTable.Row.class, rows ->
                        assertThat(rows).containsExactly(
                                new XQueryDynamicInvocationCoverageTable.Row("p/F.java", "p.F", "", "mystery",
                                        true, "src/test/xquery/t.xql"))),
                with(
                        function("""
                                static String ns() { return "urn:computed"; }
                                static final FunctionSignature S = new FunctionSignature(new QName("mystery", ns()), "");
                                """),
                        text("x:mystery()", s -> s.path("src/test/xquery/t.xql"))
                )
        );
    }

    @Test
    void doesNotMatchLocalNameAsSuffixOfLongerName() {
        rewriteRun(
                spec -> expect(spec, row("name", false, "")),
                with(
                        utilFunction("name"),
                        text("util:local-name(.), util:node-name(.)", s -> s.path("src/test/xquery/t.xql"))
                )
        );
    }

    @Test
    void ignoresCodeOutsideTestSourceSetsAndNonXQueryTestFiles() {
        rewriteRun(
                spec -> expect(spec, row("foo-bar", false, "")),
                with(
                        utilFunction("foo-bar"),
                        text("util:foo-bar(1)", s -> s.path("src/main/resources/app/lib.xqm")),
                        text("util:foo-bar(1)", s -> s.path("src/test/resources/expected.txt")),
                        java(
                                """
                                package org.exist.test;
                                public class Helper {
                                    String q = "util:foo-bar(1)";
                                }
                                """,
                                s -> s.path("src/main/java/org/exist/test/Helper.java")
                        )
                )
        );
    }

    @Test
    void treatsTopLevelTestDirectoryAsTestPath() {
        rewriteRun(
                spec -> expect(spec, row("foo-bar", true, "test/src/t.xql")),
                with(
                        utilFunction("foo-bar"),
                        text("util:foo-bar(1)", s -> s.path("test/src/t.xql"))
                )
        );
    }

    @Test
    void resolvesConstantDeclaredAfterUse() {
        rewriteRun(
                spec -> expect(spec, row("late-name", false, "")),
                with(
                        function("""
                                public static final FunctionSignature signature = new FunctionSignature(new QName(FS_NAME, UtilModule.NAMESPACE_URI), "desc");
                                private static final String FS_NAME = "late-name";
                                """)
                )
        );
    }

    @Test
    void resolvesQNameConstantDeclaredInAnotherClass() {
        rewriteRun(
                spec -> expect(spec, row("shared-name", false, "")),
                with(
                        java(
                                """
                                package p;
                                import org.exist.dom.QName;
                                import org.exist.xquery.functions.util.UtilModule;
                                public class Names {
                                    public static final String LOCAL = "shared-name";
                                    public static final QName QN = new QName(LOCAL, UtilModule.NAMESPACE_URI, UtilModule.PREFIX);
                                }
                                """
                        ),
                        function("public static final FunctionSignature signature = new FunctionSignature(Names.QN, \"desc\");")
                )
        );
    }

    @Test
    void ignoresQNamesOutsideFunctionSignatures() {
        rewriteRun(
                spec -> expect(spec, row("real-fn", false, "")),
                with(
                        function("""
                                public static final FunctionSignature signature = new FunctionSignature(new QName("real-fn", UtilModule.NAMESPACE_URI), "desc");
                                private static final QName RESULT = new QName("result", "");
                                Object eval() { return new QName("value", ""); }
                                """)
                )
        );
    }

    @Test
    void attributesNamesToTheirOwnNestedClass() {
        rewriteRun(
                spec -> spec.dataTable(XQueryDynamicInvocationCoverageTable.Row.class, rows ->
                        assertThat(rows)
                                .extracting(XQueryDynamicInvocationCoverageTable.Row::getClassName,
                                        XQueryDynamicInvocationCoverageTable.Row::getLocalName)
                                .containsExactly(Tuple.tuple("p.M$A", "aa"), Tuple.tuple("p.M$B", "bb"))),
                with(
                        java(
                                """
                                package p;
                                import org.exist.xquery.BasicFunction;
                                import org.exist.xquery.FunctionSignature;
                                import org.exist.dom.QName;
                                public class M {
                                    static class A extends BasicFunction {
                                        static final FunctionSignature S = new FunctionSignature(new QName("aa", "urn:x"), "");
                                    }
                                    static class B extends BasicFunction {
                                        static final FunctionSignature S = new FunctionSignature(new QName("bb", "urn:x"), "");
                                    }
                                }
                                """
                        )
                )
        );
    }

    @Test
    void includesDirectFunctionSubclassesButNotUserDefinedFunctions() {
        rewriteRun(
                spec -> spec.dataTable(XQueryDynamicInvocationCoverageTable.Row.class, rows ->
                        assertThat(rows).extracting(XQueryDynamicInvocationCoverageTable.Row::getLocalName)
                                .containsExactly("count")),
                with(
                        java(
                                """
                                package p;
                                import org.exist.xquery.Function;
                                import org.exist.xquery.FunctionSignature;
                                import org.exist.dom.QName;
                                public class FunCount extends Function {
                                    static final FunctionSignature S = new FunctionSignature(new QName("count", Function.BUILTIN_FUNCTION_NS), "");
                                }
                                """
                        ),
                        java(
                                """
                                package p;
                                import org.exist.xquery.UserDefinedFunction;
                                import org.exist.xquery.FunctionSignature;
                                import org.exist.dom.QName;
                                public class MyUdf extends UserDefinedFunction {
                                    static final FunctionSignature S = new FunctionSignature(new QName("udf", "urn:x"), "");
                                }
                                """
                        )
                )
        );
    }

    @Test
    void matchesWhitespaceBeforeParenFunctionReferencesAndLookups() {
        rewriteRun(
                spec -> spec.dataTable(XQueryDynamicInvocationCoverageTable.Row.class, rows ->
                        assertThat(rows).extracting(XQueryDynamicInvocationCoverageTable.Row::isDynamicallyInvokedInTests)
                                .containsExactly(true, true, true)),
                with(
                        function("""
                                static final FunctionSignature[] signatures = {
                                    new FunctionSignature(new QName("spaced", UtilModule.NAMESPACE_URI, UtilModule.PREFIX), "desc"),
                                    new FunctionSignature(new QName("referenced", UtilModule.NAMESPACE_URI, UtilModule.PREFIX), "desc"),
                                    new FunctionSignature(new QName("looked-up", UtilModule.NAMESPACE_URI, UtilModule.PREFIX), "desc")
                                };
                                """),
                        text("""
                                util:spaced ( 1 ),
                                for-each((1, 2), util:referenced#1),
                                function-lookup(xs:QName('util:looked-up'), 0)
                                """, s -> s.path("src/test/xquery/t.xql"))
                )
        );
    }

    @Test
    void ignoresCommentsAnnotationsAndFunctionDeclarations() {
        rewriteRun(
                spec -> expect(spec, row("count", false, "")),
                with(
                        utilFunction("count"),
                        text("""
                                module namespace util = "http://exist-db.org/xquery/util";
                                (: TODO test util:count() (: nested :) :)
                                declare %util:count("x") function util:count($x) { 1 };
                                """, s -> s.path("src/test/xquery/t.xql"))
                )
        );
    }

    @Test
    void matchesCallSplitAcrossConcatenatedLiterals() {
        rewriteRun(
                spec -> expect(spec, row("split-name", true, "src/test/java/p/FTest.java")),
                with(
                        utilFunction("split-name"),
                        java(
                                """
                                package p;
                                public class FTest {
                                    String q = "util:split-" + "name" + "(1)";
                                }
                                """,
                                s -> s.path("src/test/java/p/FTest.java")
                        )
                )
        );
    }
}
