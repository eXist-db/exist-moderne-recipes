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
import org.jspecify.annotations.Nullable;
import org.openrewrite.Cursor;
import org.openrewrite.ExecutionContext;
import org.openrewrite.ScanningRecipe;
import org.openrewrite.SourceFile;
import org.openrewrite.Tree;
import org.openrewrite.TreeVisitor;
import org.openrewrite.binary.Binary;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaSourceFile;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.TypeUtils;
import org.openrewrite.quark.Quark;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Corrects a systematic blind spot in static test-coverage analysis for interpreter/DSL-dispatch
 * codebases: a static call-graph analyzer can never see the edge from a test to an
 * {@code eval()} override of an {@code org.exist.xquery.Function} subclass (including
 * {@code BasicFunction}), because the test invokes it by XQuery function name through the query
 * engine (a string, parsed and dispatched at runtime), not via a direct Java method call.
 * <p>
 * Scans every non-test Java source file for classes assignable to {@code org.exist.xquery.Function}
 * (excluding {@code UserDefinedFunction}), extracting the QName local name(s) each one registers:
 * the name passed as the first argument to {@code new FunctionSignature(...)} or to a
 * {@code functionSignature(...)}/{@code functionSignatures(...)} helper, whether given inline, as a
 * {@code new QName("...", ...)}, or through a {@code String}/{@code QName} constant declared
 * anywhere in the codebase. Then scans every source file under a test source set — Java test
 * classes (string literal contents) and any other test resource (XQSuite modules, XML fixtures,
 * ...) alike — for XQuery function calls ({@code prefix:name(}), function references
 * ({@code prefix:name#1}) and {@code xs:QName('prefix:name')} lookups, ignoring XQuery comments.
 * Emits one row per (implementing class, local name) pair reporting whether a match was found and
 * where.
 * <p>
 * This is a best-effort heuristic, not a proof: it matches on local name only (not the full
 * qualified QName with namespace prefix), so it can be fooled by two different modules
 * coincidentally sharing a local name.
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class XQueryDynamicInvocationCoverage extends ScanningRecipe<XQueryDynamicInvocationCoverage.Acc> {

    private static final String FUNCTION = "org.exist.xquery.Function";
    private static final String BASIC_FUNCTION = "org.exist.xquery.BasicFunction";
    private static final String USER_DEFINED_FUNCTION = "org.exist.xquery.UserDefinedFunction";
    private static final String FUNCTION_SIGNATURE = "org.exist.xquery.FunctionSignature";
    private static final String QNAME = "org.exist.dom.QName";

    private static final String NAME_START = "[\\p{L}_]";
    private static final String NAME_CHAR = "[\\p{L}\\p{N}_.\\-\\p{M}]";
    private static final String NCNAME = NAME_START + NAME_CHAR + "*";

    // A function call `name(` or function reference `name#1`, optionally prefixed. The lookbehind
    // stops a match starting mid-name (so `local-name(` never yields `name`) or on a variable
    // (`$f(`); group 1 flags a function declaration (`declare function local:count(`), which is
    // not an invocation.
    private static final Pattern INVOCATION = Pattern.compile(
            "(?<![\\p{L}\\p{N}_.\\-:$])(function\\s+)?(?:" + NCNAME + ":)?(" + NCNAME + ")\\s*(?:\\(|#\\d)");

    // `xs:QName('prefix:name')`, as used with `function-lookup(...)`.
    private static final Pattern QNAME_LOOKUP = Pattern.compile(
            "QName\\(\\s*[\"'](?:" + NCNAME + ":)?(" + NCNAME + ")[\"']");

    @Getter
    final String displayName = "XQuery dynamic-invocation test coverage";

    @Getter
    final String description = "Finds XQuery Function#eval() overrides that static test-gap analysis " +
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
            public Tree visit(final @Nullable Tree tree, final ExecutionContext ctx) {
                if (tree instanceof JavaSourceFile) {
                    final JavaSourceFile cu = (JavaSourceFile) tree;
                    final String path = cu.getSourcePath().toString();
                    if (isTestPath(path)) {
                        indexInvocations(acc, path, String.join("\n", collectStringLiterals(cu, ctx)));
                    } else {
                        collectFunctionNames(cu, ctx, acc, path);
                    }
                } else if (tree instanceof SourceFile && !(tree instanceof Quark) && !(tree instanceof Binary)) {
                    final SourceFile sf = (SourceFile) tree;
                    final String path = sf.getSourcePath().toString();
                    if (isTestPath(path)) {
                        indexInvocations(acc, path, sf.printAll());
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
            public Tree visit(final @Nullable Tree tree, final ExecutionContext ctx) {
                if (tree instanceof JavaSourceFile) {
                    final String path = ((JavaSourceFile) tree).getSourcePath().toString();
                    final Map<String, ClassInfo> classes = acc.implementationsByPath.get(path);
                    if (classes != null) {
                        for (final ClassInfo info : classes.values()) {
                            for (final String localName : info.resolveLocalNames(acc.constants)) {
                                final Set<String> matches = acc.testPathsByLocalName
                                        .getOrDefault(localName, Collections.emptySet());
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
                }
                return tree;
            }
        };
    }

    /**
     * True for a path inside a test source set: {@code src/test/...}, {@code src/testFixtures/...},
     * {@code src/integrationTest/...}, or a top-level {@code test/}/{@code tests/} directory.
     * Deliberately does not match a {@code test} package segment under {@code src/main}.
     */
    static boolean isTestPath(final String path) {
        final String[] segments = path.replace('\\', '/').split("/");
        if (segments.length > 0 && ("test".equals(segments[0]) || "tests".equals(segments[0]))) {
            return true;
        }
        for (int i = 0; i < segments.length - 1; i++) {
            if ("src".equals(segments[i])) {
                final String sourceSet = segments[i + 1];
                if (sourceSet.startsWith("test") || sourceSet.endsWith("Test")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void indexInvocations(final Acc acc, final String path, final String text) {
        final String code = stripXQueryComments(text);
        final Matcher invocation = INVOCATION.matcher(code);
        while (invocation.find()) {
            if (invocation.group(1) == null) {
                acc.testPathsByLocalName.computeIfAbsent(invocation.group(2), n -> new LinkedHashSet<>()).add(path);
            }
        }
        final Matcher lookup = QNAME_LOOKUP.matcher(code);
        while (lookup.find()) {
            acc.testPathsByLocalName.computeIfAbsent(lookup.group(1), n -> new LinkedHashSet<>()).add(path);
        }
    }

    /**
     * Removes (possibly nested) XQuery {@code (: ... :)} comments. An unterminated comment is left
     * in place rather than swallowing the rest of the text.
     */
    static String stripXQueryComments(final String text) {
        if (!text.contains("(:")) {
            return text;
        }
        final StringBuilder out = new StringBuilder(text.length());
        int i = 0;
        while (i < text.length()) {
            if (text.startsWith("(:", i)) {
                final int end = commentEnd(text, i);
                if (end < 0) {
                    out.append(text, i, text.length());
                    break;
                }
                out.append(' ');
                i = end;
            } else {
                out.append(text.charAt(i++));
            }
        }
        return out.toString();
    }

    private static int commentEnd(final String text, final int start) {
        int depth = 0;
        int i = start;
        while (i < text.length() - 1) {
            if (text.startsWith("(:", i)) {
                depth++;
                i += 2;
            } else if (text.startsWith(":)", i)) {
                depth--;
                i += 2;
                if (depth == 0) {
                    return i;
                }
            } else {
                i++;
            }
        }
        return -1;
    }

    private static List<String> collectStringLiterals(final JavaSourceFile cu, final ExecutionContext ctx) {
        final List<String> literals = new ArrayList<>();
        new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public J.Binary visitBinary(final J.Binary binary, final ExecutionContext c) {
                // Fold `"fn:foo" + "(1)"` into one string so a call split across literals still matches.
                final String concatenated = concatenatedLiterals(binary);
                if (concatenated != null) {
                    literals.add(concatenated);
                    return binary;
                }
                return super.visitBinary(binary, c);
            }

            @Override
            public J.Literal visitLiteral(final J.Literal literal, final ExecutionContext c) {
                if (literal.getValue() instanceof String) {
                    literals.add((String) literal.getValue());
                }
                return literal;
            }
        }.visit(cu, ctx);
        return literals;
    }

    private static @Nullable String concatenatedLiterals(final Expression expression) {
        if (expression instanceof J.Literal) {
            final Object value = ((J.Literal) expression).getValue();
            return value instanceof String ? (String) value : null;
        }
        if (expression instanceof J.Parentheses) {
            final J tree = ((J.Parentheses<?>) expression).getTree();
            return tree instanceof Expression ? concatenatedLiterals((Expression) tree) : null;
        }
        if (expression instanceof J.Binary && ((J.Binary) expression).getOperator() == J.Binary.Type.Addition) {
            final String left = concatenatedLiterals(((J.Binary) expression).getLeft());
            final String right = concatenatedLiterals(((J.Binary) expression).getRight());
            return left != null && right != null ? left + right : null;
        }
        return null;
    }

    private static void collectFunctionNames(final JavaSourceFile cu, final ExecutionContext ctx,
            final Acc acc, final String path) {
        new JavaIsoVisitor<ExecutionContext>() {
            final List<String> classStack = new ArrayList<>();
            final List<@Nullable String> functionClassStack = new ArrayList<>();

            @Override
            public J.ClassDeclaration visitClassDeclaration(final J.ClassDeclaration classDecl,
                    final ExecutionContext c) {
                final JavaType.FullyQualified type = classDecl.getType();
                final String fqn = type != null ? type.getFullyQualifiedName() : classDecl.getSimpleName();
                classStack.add(fqn);
                functionClassStack.add(type != null && isFunctionImplementation(type) ? fqn : null);
                try {
                    return super.visitClassDeclaration(classDecl, c);
                } finally {
                    classStack.remove(classStack.size() - 1);
                    functionClassStack.remove(functionClassStack.size() - 1);
                }
            }

            @Override
            public J.VariableDeclarations.NamedVariable visitVariable(final J.VariableDeclarations.NamedVariable variable,
                    final ExecutionContext c) {
                final J.VariableDeclarations.NamedVariable result = super.visitVariable(variable, c);
                // Only fields (declared directly in a class body) can be referenced from elsewhere
                // as constants; locals and parameters are skipped.
                final Cursor block = getCursor().getParentTreeCursor().getParentTreeCursor();
                final boolean isField = block.getValue() instanceof J.Block
                        && block.getParentTreeCursor().getValue() instanceof J.ClassDeclaration;
                final Expression initializer = result.getInitializer();
                if (isField && initializer != null && !classStack.isEmpty()) {
                    final NameRef ref = nameRef(initializer);
                    if (ref != null) {
                        acc.constants.put(constantKey(classStack.get(classStack.size() - 1), result.getSimpleName()), ref);
                    }
                }
                return result;
            }

            @Override
            public J.NewClass visitNewClass(final J.NewClass newClass, final ExecutionContext c) {
                final J.NewClass result = super.visitNewClass(newClass, c);
                if (TypeUtils.isOfClassType(result.getType(), FUNCTION_SIGNATURE)) {
                    recordFirstArg(result.getArguments());
                }
                return result;
            }

            @Override
            public J.MethodInvocation visitMethodInvocation(final J.MethodInvocation method,
                    final ExecutionContext c) {
                final J.MethodInvocation result = super.visitMethodInvocation(method, c);
                final String name = result.getSimpleName();
                if ("functionSignature".equals(name) || "functionSignatures".equals(name)) {
                    recordFirstArg(result.getArguments());
                }
                return result;
            }

            private void recordFirstArg(final List<Expression> args) {
                final String className = currentFunctionClass();
                if (className == null || args.isEmpty()) {
                    return;
                }
                final NameRef ref = nameRef(args.get(0));
                if (ref != null) {
                    acc.implementationsByPath
                            .computeIfAbsent(path, p -> new LinkedHashMap<>())
                            .computeIfAbsent(className, ClassInfo::new)
                            .names.add(ref);
                }
            }

            private @Nullable String currentFunctionClass() {
                // The innermost enclosing class decides attribution; a non-function class nested in
                // a function class (e.g. a helper) still attributes to the enclosing function class.
                for (int i = functionClassStack.size() - 1; i >= 0; i--) {
                    if (functionClassStack.get(i) != null) {
                        return functionClassStack.get(i);
                    }
                }
                return null;
            }

            /**
             * Resolves an expression naming a function to either its literal local name, or a
             * reference to a {@code String}/{@code QName} constant to be resolved once every file
             * has been scanned.
             */
            private @Nullable NameRef nameRef(final Expression expression) {
                if (expression instanceof J.Literal) {
                    final Object value = ((J.Literal) expression).getValue();
                    return value instanceof String ? NameRef.literal((String) value) : null;
                }
                if (expression instanceof J.NewClass
                        && TypeUtils.isOfClassType(((J.NewClass) expression).getType(), QNAME)) {
                    final List<Expression> args = ((J.NewClass) expression).getArguments();
                    return args.isEmpty() ? null : nameRef(args.get(0));
                }
                if (expression instanceof J.Identifier) {
                    return constantRef((J.Identifier) expression);
                }
                if (expression instanceof J.FieldAccess) {
                    return constantRef(((J.FieldAccess) expression).getName());
                }
                return null;
            }

            private @Nullable NameRef constantRef(final J.Identifier identifier) {
                final JavaType.Variable field = identifier.getFieldType();
                final JavaType.FullyQualified owner = field != null ? TypeUtils.asFullyQualified(field.getOwner()) : null;
                if (owner != null) {
                    return NameRef.constant(constantKey(owner.getFullyQualifiedName(), identifier.getSimpleName()));
                }
                // Missing type attribution: assume a constant in the enclosing class.
                return classStack.isEmpty() ? null
                        : NameRef.constant(constantKey(classStack.get(classStack.size() - 1), identifier.getSimpleName()));
            }
        }.visit(cu, ctx);
    }

    private static boolean isFunctionImplementation(final JavaType.FullyQualified type) {
        return (TypeUtils.isAssignableTo(FUNCTION, type) || TypeUtils.isAssignableTo(BASIC_FUNCTION, type))
                && !TypeUtils.isAssignableTo(USER_DEFINED_FUNCTION, type);
    }

    private static String constantKey(final String ownerFqn, final String name) {
        return ownerFqn + "#" + name;
    }

    public static class Acc {
        final Map<String, Map<String, ClassInfo>> implementationsByPath = new LinkedHashMap<>();
        final Map<String, NameRef> constants = new HashMap<>();
        final Map<String, Set<String>> testPathsByLocalName = new HashMap<>();
    }

    private static class ClassInfo {
        final String className;
        final List<NameRef> names = new ArrayList<>();

        ClassInfo(final String className) {
            this.className = className;
        }

        Set<String> resolveLocalNames(final Map<String, NameRef> constants) {
            final Set<String> localNames = new LinkedHashSet<>();
            for (final NameRef ref : names) {
                final String localName = ref.resolve(constants);
                if (localName != null) {
                    localNames.add(localName);
                }
            }
            return localNames;
        }
    }

    /** Either a literal local name or the key of a constant holding one. */
    private static class NameRef {
        final @Nullable String literal;
        final @Nullable String constantKey;

        private NameRef(final @Nullable String literal, final @Nullable String constantKey) {
            this.literal = literal;
            this.constantKey = constantKey;
        }

        static NameRef literal(final String value) {
            return new NameRef(value, null);
        }

        static NameRef constant(final String key) {
            return new NameRef(null, key);
        }

        @Nullable String resolve(final Map<String, NameRef> constants) {
            NameRef ref = this;
            // Bounded to guard against a constant cycle.
            for (int hops = 0; ref != null && hops < 16; hops++) {
                if (ref.literal != null) {
                    return ref.literal;
                }
                ref = constants.get(ref.constantKey);
            }
            return null;
        }
    }
}
