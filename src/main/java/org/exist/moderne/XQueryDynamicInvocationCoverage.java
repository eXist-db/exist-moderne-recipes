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
import org.openrewrite.ScanningRecipe;
import org.openrewrite.Tree;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaSourceFile;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.TypeUtils;
import org.openrewrite.text.PlainText;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Corrects a systematic blind spot in static test-coverage analysis for interpreter/DSL-dispatch
 * codebases: a static call-graph analyzer can never see the edge from a test to a
 * {@code BasicFunction#eval()} override, because the test invokes it by XQuery function name
 * through the query engine (a string, parsed and dispatched at runtime), not via a direct Java
 * method call.
 * <p>
 * Scans every non-test Java source file for classes assignable to
 * {@code org.exist.xquery.BasicFunction}, extracting the QName local name(s) each one registers
 * (the first String argument to every {@code new QName(...)} call inside the class). Then scans
 * every source file under a {@code /test/} path — Java test classes (string literal contents) and
 * plain-text test resources (XQSuite {@code .xql}/{@code .xqm} modules, XML fixtures) alike — for
 * text containing {@code "<localName>("}. Emits one row per (implementing class, local name) pair
 * reporting whether a match was found and where.
 * <p>
 * This is a best-effort heuristic, not a proof: it matches on local name only (not the full
 * qualified QName with namespace prefix), so it can be fooled by two different modules
 * coincidentally sharing a local name. In this codebase's convention of long, hyphenated,
 * function-specific names, that risk is low.
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class XQueryDynamicInvocationCoverage extends ScanningRecipe<XQueryDynamicInvocationCoverage.Acc> {

    private static final String BASIC_FUNCTION = "org.exist.xquery.BasicFunction";
    private static final String QNAME = "org.exist.dom.QName";

    @Getter
    final String displayName = "XQuery dynamic-invocation test coverage";

    @Getter
    final String description = "Finds BasicFunction#eval() overrides that static test-gap analysis " +
            "reports as untested, but which a test actually exercises by invoking the XQuery " +
            "function by name (a string dispatched through the query engine, invisible to a " +
            "static Java call graph). Emits a data table correcting the coverage signal.";

    @Override
    public Acc getInitialValue(final ExecutionContext ctx) {
        return new Acc();
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getScanner(final Acc acc) {
        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public Tree visit(final Tree tree, final ExecutionContext ctx) {
                if (tree instanceof JavaSourceFile) {
                    final JavaSourceFile cu = (JavaSourceFile) tree;
                    final String path = cu.getSourcePath().toString();
                    if (isTestPath(path)) {
                        collectStringLiterals(cu, ctx, acc, path);
                    } else {
                        collectBasicFunctionQNames(cu, ctx, acc, path);
                    }
                } else if (tree instanceof PlainText) {
                    final PlainText pt = (PlainText) tree;
                    final String path = pt.getSourcePath().toString();
                    if (isTestPath(path) || path.endsWith(".xql") || path.endsWith(".xqm")) {
                        acc.testBlobs.add(new TestBlob(path, pt.getText()));
                    }
                }
                return tree;
            }
        };
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(final Acc acc) {
        final XQueryDynamicInvocationCoverageTable table = new XQueryDynamicInvocationCoverageTable(this);
        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public Tree visit(final Tree tree, final ExecutionContext ctx) {
                if (tree instanceof JavaSourceFile) {
                    final String path = ((JavaSourceFile) tree).getSourcePath().toString();
                    final ClassInfo info = acc.implementationsByPath.get(path);
                    if (info != null) {
                        for (final String localName : info.localNames) {
                            final String needle = localName + "(";
                            final List<String> matches = new ArrayList<>();
                            for (final TestBlob blob : acc.testBlobs) {
                                if (blob.text.contains(needle)) {
                                    matches.add(blob.sourcePath);
                                }
                            }
                            table.insertRow(ctx, new XQueryDynamicInvocationCoverageTable.Row(
                                    path,
                                    info.className,
                                    localName,
                                    !matches.isEmpty(),
                                    String.join("; ", matches)
                            ));
                        }
                    }
                }
                return tree;
            }
        };
    }

    private static boolean isTestPath(final String path) {
        return path.contains("/test/") || path.contains("\\test\\");
    }

    private static void collectStringLiterals(final JavaSourceFile cu, final ExecutionContext ctx,
            final Acc acc, final String path) {
        final List<String> literals = new ArrayList<>();
        new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public J.Literal visitLiteral(final J.Literal literal, final ExecutionContext c) {
                if (literal.getValue() instanceof String) {
                    literals.add((String) literal.getValue());
                }
                return literal;
            }
        }.visit(cu, ctx);
        if (!literals.isEmpty()) {
            acc.testBlobs.add(new TestBlob(path, String.join("\n", literals)));
        }
    }

    private static void collectBasicFunctionQNames(final JavaSourceFile cu, final ExecutionContext ctx,
            final Acc acc, final String path) {
        new JavaIsoVisitor<ExecutionContext>() {
            String currentClassName;
            // static final String constants declared in the file, e.g.
            // `private final static String FS_FOO_NAME = "foo";` - resolves the common eXist
            // pattern of registering a FunctionSignature via a named constant rather than an
            // inline literal.
            final Map<String, String> stringConstants = new HashMap<>();

            @Override
            public J.ClassDeclaration visitClassDeclaration(final J.ClassDeclaration classDecl,
                    final ExecutionContext c) {
                final JavaType.FullyQualified type = classDecl.getType();
                final String previous = currentClassName;
                if (type != null && TypeUtils.isAssignableTo(BASIC_FUNCTION, type)) {
                    currentClassName = type.getFullyQualifiedName();
                }
                final J.ClassDeclaration result = super.visitClassDeclaration(classDecl, c);
                currentClassName = previous;
                return result;
            }

            @Override
            public J.VariableDeclarations visitVariableDeclarations(final J.VariableDeclarations multiVariable,
                    final ExecutionContext c) {
                final J.VariableDeclarations result = super.visitVariableDeclarations(multiVariable, c);
                // No type-expression check here: J LST nodes don't render clean source text via
                // toString(), so a String-literal initializer is itself sufficient evidence this
                // is a String constant - the declared type doesn't need separate confirmation.
                for (final J.VariableDeclarations.NamedVariable v : result.getVariables()) {
                    if (v.getInitializer() instanceof J.Literal) {
                        final Object value = ((J.Literal) v.getInitializer()).getValue();
                        if (value instanceof String) {
                            stringConstants.put(v.getSimpleName(), (String) value);
                        }
                    }
                }
                return result;
            }

            @Override
            public J.NewClass visitNewClass(final J.NewClass newClass, final ExecutionContext c) {
                final J.NewClass result = super.visitNewClass(newClass, c);
                final JavaType.FullyQualified type = TypeUtils.asFullyQualified(result.getType());
                if (type != null && QNAME.equals(type.getFullyQualifiedName())) {
                    recordFirstArgAsLocalName(result.getArguments());
                }
                return result;
            }

            @Override
            public J.MethodInvocation visitMethodInvocation(final J.MethodInvocation method,
                    final ExecutionContext c) {
                final J.MethodInvocation result = super.visitMethodInvocation(method, c);
                final String name = result.getSimpleName();
                if ("functionSignature".equals(name) || "functionSignatures".equals(name)) {
                    recordFirstArgAsLocalName(result.getArguments());
                }
                return result;
            }

            private void recordFirstArgAsLocalName(final List<Expression> args) {
                if (currentClassName == null || args.isEmpty()) {
                    return;
                }
                final Expression first = args.get(0);
                String localName = null;
                if (first instanceof J.Literal && ((J.Literal) first).getValue() instanceof String) {
                    localName = (String) ((J.Literal) first).getValue();
                } else if (first instanceof J.Identifier) {
                    localName = stringConstants.get(((J.Identifier) first).getSimpleName());
                }
                if (localName != null) {
                    acc.implementationsByPath
                            .computeIfAbsent(path, p -> new ClassInfo(currentClassName))
                            .localNames.add(localName);
                }
            }
        }.visit(cu, ctx);
    }

    public static class Acc {
        final Map<String, ClassInfo> implementationsByPath = new LinkedHashMap<>();
        final List<TestBlob> testBlobs = new ArrayList<>();
    }

    private static class ClassInfo {
        final String className;
        final Set<String> localNames = new LinkedHashSet<>();

        ClassInfo(final String className) {
            this.className = className;
        }
    }

    private static class TestBlob {
        final String sourcePath;
        final String text;

        TestBlob(final String sourcePath, final String text) {
            this.sourcePath = sourcePath;
            this.text = text;
        }
    }
}
