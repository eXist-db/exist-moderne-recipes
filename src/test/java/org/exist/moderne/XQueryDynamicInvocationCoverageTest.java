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
import org.openrewrite.test.RewriteTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.openrewrite.java.Assertions.java;
import static org.openrewrite.test.SourceSpecs.text;

class XQueryDynamicInvocationCoverageTest implements RewriteTest {

    @Override
    public void defaults(final org.openrewrite.test.RecipeSpec spec) {
        spec.recipe(new XQueryDynamicInvocationCoverage());
    }

    private static final String BASIC_FUNCTION_STUB = """
            package org.exist.xquery;
            public abstract class BasicFunction {
            }
            """;

    private static final String QNAME_STUB = """
            package org.exist.dom;
            public class QName {
                public QName(String localName, String namespaceURI) {}
            }
            """;

    @Test
    void reportsFunctionAsDynamicallyInvokedWhenTestStringLiteralMatches() {
        rewriteRun(
                spec -> spec.dataTable(XQueryDynamicInvocationCoverageTable.Row.class, rows ->
                        assertThat(rows).containsExactly(
                                new XQueryDynamicInvocationCoverageTable.Row(
                                        "org/exist/xquery/functions/fn/FunEnvironment.java",
                                        "org.exist.xquery.functions.fn.FunEnvironment",
                                        "environment-variable",
                                        true,
                                        "src/test/java/org/exist/xquery/functions/fn/FunEnvironmentTest.java"
                                )
                        )
                ),
                java(BASIC_FUNCTION_STUB),
                java(QNAME_STUB),
                java(
                        """
                        package org.exist.xquery.functions.fn;
                        import org.exist.xquery.BasicFunction;
                        import org.exist.dom.QName;
                        public class FunEnvironment extends BasicFunction {
                            public static final Object signature = new QName("environment-variable", "ns");
                        }
                        """
                ),
                java(
                        """
                        package org.exist.xquery.functions.fn;
                        public class FunEnvironmentTest {
                            void test() {
                                String query = "fn:environment-variable('PATH')";
                            }
                        }
                        """,
                        s -> s.path("src/test/java/org/exist/xquery/functions/fn/FunEnvironmentTest.java")
                )
        );
    }

    @Test
    void reportsFunctionAsNotDynamicallyInvokedWhenNoTestMatches() {
        rewriteRun(
                spec -> spec.dataTable(XQueryDynamicInvocationCoverageTable.Row.class, rows ->
                        assertThat(rows).containsExactly(
                                new XQueryDynamicInvocationCoverageTable.Row(
                                        "org/exist/xquery/functions/fn/FunEnvironment.java",
                                        "org.exist.xquery.functions.fn.FunEnvironment",
                                        "environment-variable",
                                        false,
                                        ""
                                )
                        )
                ),
                java(BASIC_FUNCTION_STUB),
                java(QNAME_STUB),
                java(
                        """
                        package org.exist.xquery.functions.fn;
                        import org.exist.xquery.BasicFunction;
                        import org.exist.dom.QName;
                        public class FunEnvironment extends BasicFunction {
                            public static final Object signature = new QName("environment-variable", "ns");
                        }
                        """
                ),
                java(
                        """
                        package org.exist.xquery.functions.fn;
                        public class FunEnvironmentTest {
                            void test() {
                                String query = "util:system-property('vendor')";
                            }
                        }
                        """,
                        s -> s.path("src/test/java/org/exist/xquery/functions/fn/FunEnvironmentTest.java")
                )
        );
    }

    @Test
    void findsInvocationInPlainTextXqSuiteModule() {
        rewriteRun(
                spec -> spec.dataTable(XQueryDynamicInvocationCoverageTable.Row.class, rows ->
                        assertThat(rows).containsExactly(
                                new XQueryDynamicInvocationCoverageTable.Row(
                                        "org/exist/xquery/functions/util/SystemProperty.java",
                                        "org.exist.xquery.functions.util.SystemProperty",
                                        "system-property",
                                        true,
                                        "src/test/resources/system-property-test.xql"
                                )
                        )
                ),
                java(BASIC_FUNCTION_STUB),
                java(QNAME_STUB),
                java(
                        """
                        package org.exist.xquery.functions.util;
                        import org.exist.xquery.BasicFunction;
                        import org.exist.dom.QName;
                        public class SystemProperty extends BasicFunction {
                            public static final Object signature = new QName("system-property", "ns");
                        }
                        """
                ),
                text(
                        "(: %test:assertTrue :)\ndeclare function local:t() { util:system-property('vendor') };",
                        s -> s.path("src/test/resources/system-property-test.xql")
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
                java(BASIC_FUNCTION_STUB),
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
        );
    }
}
