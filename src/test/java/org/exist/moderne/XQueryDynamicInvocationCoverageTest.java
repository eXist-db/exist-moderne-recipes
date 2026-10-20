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

import org.junit.jupiter.api.Test;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;
import org.openrewrite.test.SourceSpecs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.openrewrite.java.Assertions.java;
import static org.openrewrite.test.SourceSpecs.text;
import static org.openrewrite.xml.Assertions.xml;

class XQueryDynamicInvocationCoverageTest implements RewriteTest {

    @Override
    public void defaults(final RecipeSpec spec) {
        spec.recipe(new XQueryDynamicInvocationCoverage());
    }

    private static final String FUNCTION_STUB = """
            package org.exist.xquery;
            public abstract class Function {
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

    private static final String QNAME_STUB = """
            package org.exist.dom;
            public class QName {
                public QName(String localName, String namespaceURI) {}
            }
            """;

    private static SourceSpecs[] stubs() {
        return new SourceSpecs[]{
                java(FUNCTION_STUB),
                java(BASIC_FUNCTION_STUB),
                java(USER_DEFINED_FUNCTION_STUB),
                java(FUNCTION_SIGNATURE_STUB),
                java(QNAME_STUB)
        };
    }

    private static SourceSpecs[] with(final SourceSpecs... specs) {
        final SourceSpecs[] stubs = stubs();
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
                import org.exist.xquery.FunctionSignature;
                import org.exist.dom.QName;
                public class F extends BasicFunction {
                %s
                }
                """.formatted(body));
    }

    private static SourceSpecs signatureFor(final String localName) {
        return function("public static final FunctionSignature signature = "
                + "new FunctionSignature(new QName(\"" + localName + "\", \"ns\"), \"desc\");");
    }

    private static XQueryDynamicInvocationCoverageTable.Row row(final String localName, final boolean hit,
            final String matchingPaths) {
        return new XQueryDynamicInvocationCoverageTable.Row("p/F.java", "p.F", localName, hit, matchingPaths);
    }

    private static void expect(final RecipeSpec spec, final XQueryDynamicInvocationCoverageTable.Row... rows) {
        spec.dataTable(XQueryDynamicInvocationCoverageTable.Row.class, actual ->
                assertThat(actual).containsExactly(rows));
    }

    @Test
    void reportsFunctionAsDynamicallyInvokedWhenTestStringLiteralMatches() {
        rewriteRun(
                spec -> expect(spec, row("environment-variable", true, "src/test/java/p/FTest.java")),
                with(
                        signatureFor("environment-variable"),
                        java(
                                """
                                package p;
                                public class FTest {
                                    void test() {
                                        String query = "fn:environment-variable('PATH')";
                                    }
                                }
                                """,
                                s -> s.path("src/test/java/p/FTest.java")
                        )
                )
        );
    }

    @Test
    void reportsFunctionAsNotDynamicallyInvokedWhenNoTestMatches() {
        rewriteRun(
                spec -> expect(spec, row("environment-variable", false, "")),
                with(
                        signatureFor("environment-variable"),
                        java(
                                """
                                package p;
                                public class FTest {
                                    void test() {
                                        String query = "util:system-property('vendor')";
                                    }
                                }
                                """,
                                s -> s.path("src/test/java/p/FTest.java")
                        )
                )
        );
    }

    @Test
    void findsInvocationInPlainTextXqSuiteModule() {
        rewriteRun(
                spec -> expect(spec, row("system-property", true, "src/test/xquery/system-property-test.xql")),
                with(
                        signatureFor("system-property"),
                        text(
                                "(: %test:assertTrue :)\ndeclare function local:t() { util:system-property('vendor') };",
                                s -> s.path("src/test/xquery/system-property-test.xql")
                        )
                )
        );
    }

    @Test
    void findsInvocationInXmlTestFixture() {
        rewriteRun(
                spec -> expect(spec, row("foo-bar", true, "src/test/resources/t.xml")),
                with(
                        signatureFor("foo-bar"),
                        xml("<test><code>x:foo-bar(1)</code></test>", s -> s.path("src/test/resources/t.xml"))
                )
        );
    }

    @Test
    void findsLocalNameRegisteredViaFunctionSignatureHelperWithConstant() {
        rewriteRun(
                spec -> spec.dataTable(XQueryDynamicInvocationCoverageTable.Row.class, rows ->
                        assertThat(rows).containsExactly(
                                new XQueryDynamicInvocationCoverageTable.Row(
                                        "org/exist/xquery/functions/util/AvailableSystemProperties.java",
                                        "org.exist.xquery.functions.util.AvailableSystemProperties",
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
                                public class UtilModule {
                                    static Object functionSignature(String name, String description, Object returnType, Object... paramTypes) {
                                        return null;
                                    }
                                }
                                """
                        ),
                        java(
                                """
                                package org.exist.xquery.functions.util;
                                import org.exist.xquery.BasicFunction;
                                import static org.exist.xquery.functions.util.UtilModule.functionSignature;
                                public class AvailableSystemProperties extends BasicFunction {
                                    private final static String FS_NAME = "available-system-properties";
                                    public static final Object SIGNATURE = functionSignature(FS_NAME, "desc", null);
                                }
                                """
                        ),
                        java(
                                """
                                package org.exist.xquery.functions.util;
                                public class SystemPropertyTest {
                                    void test() {
                                        String query = "util:available-system-properties()";
                                    }
                                }
                                """,
                                s -> s.path("src/test/java/org/exist/xquery/functions/util/SystemPropertyTest.java")
                        )
                )
        );
    }

    @Test
    void doesNotMatchLocalNameAsSuffixOfLongerName() {
        rewriteRun(
                spec -> expect(spec, row("name", false, "")),
                with(
                        signatureFor("name"),
                        text("fn:local-name(.), fn:node-name(.)", s -> s.path("src/test/xquery/t.xql"))
                )
        );
    }

    @Test
    void ignoresXQueryOutsideTestSourceSets() {
        rewriteRun(
                spec -> expect(spec, row("foo-bar", false, "")),
                with(
                        signatureFor("foo-bar"),
                        text("x:foo-bar(1)", s -> s.path("src/main/resources/app/lib.xqm")),
                        java(
                                """
                                package org.exist.test;
                                public class Helper {
                                    String q = "x:foo-bar(1)";
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
                        signatureFor("foo-bar"),
                        text("x:foo-bar(1)", s -> s.path("test/src/t.xql"))
                )
        );
    }

    @Test
    void resolvesConstantDeclaredAfterUse() {
        rewriteRun(
                spec -> expect(spec, row("late-name", false, "")),
                with(
                        function("""
                                public static final FunctionSignature signature = new FunctionSignature(new QName(FS_NAME, "ns"), "desc");
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
                                public class Names {
                                    public static final String LOCAL = "shared-name";
                                    public static final QName QN = new QName(LOCAL, "ns");
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
                                public static final FunctionSignature signature = new FunctionSignature(new QName("real-fn", "ns"), "desc");
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
                                .containsExactly(
                                        org.assertj.core.groups.Tuple.tuple("p.M$A", "aa"),
                                        org.assertj.core.groups.Tuple.tuple("p.M$B", "bb"))),
                with(
                        java(
                                """
                                package p;
                                import org.exist.xquery.BasicFunction;
                                import org.exist.xquery.FunctionSignature;
                                import org.exist.dom.QName;
                                public class M {
                                    static class A extends BasicFunction {
                                        static final FunctionSignature S = new FunctionSignature(new QName("aa", ""), "");
                                    }
                                    static class B extends BasicFunction {
                                        static final FunctionSignature S = new FunctionSignature(new QName("bb", ""), "");
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
                                    static final FunctionSignature S = new FunctionSignature(new QName("count", "ns"), "");
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
                                    static final FunctionSignature S = new FunctionSignature(new QName("udf", "ns"), "");
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
                                    new FunctionSignature(new QName("spaced", "ns"), "desc"),
                                    new FunctionSignature(new QName("referenced", "ns"), "desc"),
                                    new FunctionSignature(new QName("looked-up", "ns"), "desc")
                                };
                                """),
                        text("""
                                x:spaced ( 1 ),
                                for-each((1, 2), x:referenced#1),
                                function-lookup(xs:QName('x:looked-up'), 0)
                                """, s -> s.path("src/test/xquery/t.xql"))
                )
        );
    }

    @Test
    void ignoresCommentsAndFunctionDeclarations() {
        rewriteRun(
                spec -> expect(spec, row("count", false, "")),
                with(
                        signatureFor("count"),
                        text("""
                                (: TODO test fn:count() (: nested :) :)
                                declare function local:count($x) { 1 };
                                """, s -> s.path("src/test/xquery/t.xql"))
                )
        );
    }

    @Test
    void matchesCallSplitAcrossConcatenatedLiterals() {
        rewriteRun(
                spec -> expect(spec, row("split-name", true, "src/test/java/p/FTest.java")),
                with(
                        signatureFor("split-name"),
                        java(
                                """
                                package p;
                                public class FTest {
                                    String q = "x:split-" + "name" + "(1)";
                                }
                                """,
                                s -> s.path("src/test/java/p/FTest.java")
                        )
                )
        );
    }

    @Test
    void stripXQueryCommentsLeavesUnterminatedCommentInPlace() {
        assertThat(XQueryDynamicInvocationCoverage.stripXQueryComments("a (: b (: c :) d :) e"))
                .isEqualTo("a   e");
        assertThat(XQueryDynamicInvocationCoverage.stripXQueryComments("a (: b"))
                .isEqualTo("a (: b");
    }
}
