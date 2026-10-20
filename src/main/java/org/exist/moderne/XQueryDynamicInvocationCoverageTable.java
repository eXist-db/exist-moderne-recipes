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

import lombok.Value;
import org.openrewrite.Column;
import org.openrewrite.DataTable;
import org.openrewrite.Recipe;

public class XQueryDynamicInvocationCoverageTable extends DataTable<XQueryDynamicInvocationCoverageTable.Row> {

    public XQueryDynamicInvocationCoverageTable(final Recipe recipe) {
        super(recipe,
                "XQuery dynamic-invocation coverage",
                "Corrects the static test-gap analysis for XQuery Function#eval() overrides: " +
                        "a builtin XQuery function is dispatched by name through the query engine, " +
                        "not called directly from Java, so a static call-graph analysis can never see " +
                        "the edge from a test to eval(). This table cross-references each function's " +
                        "QName against the XQuery calls in the code test suites execute (XQSuite modules, " +
                        "XML TestSets, Java test string literals, and the test runner libraries), to report " +
                        "whether the function is actually exercised by a test despite that blind spot.");
    }

    @Value
    public static class Row {

        @Column(displayName = "Source path",
                description = "Path to the Java source file implementing the XQuery function.")
        String sourcePath;

        @Column(displayName = "Class name",
                description = "Fully qualified name of the org.exist.xquery.Function subclass.")
        String className;

        @Column(displayName = "QName namespace URI",
                description = "The XQuery function's namespace URI, or empty if it could not be resolved, " +
                        "in which case tests are matched on the local name alone.")
        String namespaceUri;

        @Column(displayName = "QName local name",
                description = "The XQuery function's local name, as registered by its `FunctionSignature` " +
                        "(directly, via `new QName(...)`, through a String/QName constant, or via a module's " +
                        "`functionSignature(...)` helper).")
        String localName;

        @Column(displayName = "Dynamically invoked in tests",
                description = "True if code a test suite executes calls `prefix:<localName>(`, references " +
                        "`prefix:<localName>#arity`, or looks it up via `xs:QName('prefix:<localName>')`, " +
                        "with the prefix bound to this function's namespace, indicating the function is " +
                        "likely exercised via XQuery string dispatch even though no direct Java call exists.")
        boolean dynamicallyInvokedInTests;

        @Column(displayName = "Matching test paths",
                description = "Semicolon-separated paths of the test sources/resources whose text " +
                        "matched this function, or empty if none matched.")
        String matchingTestPaths;
    }
}
