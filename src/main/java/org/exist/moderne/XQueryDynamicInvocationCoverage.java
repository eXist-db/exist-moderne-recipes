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
import org.openrewrite.Tree;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaSourceFile;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.Statement;
import org.openrewrite.java.tree.TypeUtils;
import org.openrewrite.text.PlainText;
import org.openrewrite.xml.tree.Xml;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Corrects a systematic blind spot in static test-coverage analysis for interpreter/DSL-dispatch
 * codebases: a static call-graph analyzer can never see the edge from a test to an
 * {@code eval()} override of an {@code org.exist.xquery.Function} subclass (including
 * {@code BasicFunction}), because the test invokes it by XQuery function name through the query
 * engine (a string, parsed and dispatched at runtime), not via a direct Java method call.
 * <p>
 * Scans every non-test Java source file for classes assignable to {@code org.exist.xquery.Function}
 * (excluding {@code UserDefinedFunction}), extracting the QName(s) each one registers: the first
 * argument to {@code new FunctionSignature(...)} or to a {@code functionSignature(...)}/
 * {@code functionSignatures(...)} helper, whether a {@code new QName(local, namespace, prefix)}, a
 * {@code String}/{@code QName} constant declared anywhere in the codebase, or a bare local name
 * passed to a module's helper, whose own {@code new QName(name, NAMESPACE_URI, PREFIX)} supplies the
 * namespace.
 * <p>
 * Then scans the code the test suites actually execute, as eXist-db's runners do:
 * <ul>
 * <li>XQuery files in test source sets (XQSuite modules), skipping {@code %test:pending} functions;</li>
 * <li>XML {@code <TestSet>} files in test source sets, taking only the {@code <code>} and
 * {@code <xpath>} of tests that are not {@code @ignore}d, and the set's {@code <imports>},
 * {@code <functions>} and {@code <variable>} prolog;</li>
 * <li>string literals in Java test classes;</li>
 * <li>BATS integration tests ({@code .bats}) and REST API {@code <query>} request bodies, which run
 * queries against a container;</li>
 * <li>the XQSuite and XML test runner libraries themselves, which run with every suite.</li>
 * </ul>
 * In each, it finds function calls ({@code prefix:name(}), function references
 * ({@code prefix:name#1}) and {@code xs:QName('prefix:name')} lookups, ignoring XQuery comments,
 * and resolves their prefixes to a namespace from the text's own declarations or, failing that, the
 * modules' default prefixes. A function is matched on namespace and local name, or on local name
 * alone if its namespace could not be resolved. Emits one row per (implementing class, QName) pair
 * reporting whether a match was found and where.
 * <p>
 * This is a best-effort heuristic, not a proof: it does not check arity, and it cannot tell
 * whether a call it finds is actually reached when the test runs.
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class XQueryDynamicInvocationCoverage extends ScanningRecipe<XQueryDynamicInvocationCoverage.Acc> {

    private static final String FUNCTION = "org.exist.xquery.Function";
    private static final String BASIC_FUNCTION = "org.exist.xquery.BasicFunction";
    private static final String USER_DEFINED_FUNCTION = "org.exist.xquery.UserDefinedFunction";
    private static final String FUNCTION_SIGNATURE = "org.exist.xquery.FunctionSignature";
    private static final String QNAME = "org.exist.dom.QName";
    private static final String EXIST_NS = "http://exist.sourceforge.net/NS/exist";

    /** File extensions eXist-db's {@code XQueryFilenameFilter} treats as XQuery. */
    private static final String[] XQUERY_EXTENSIONS = {".xq", ".xql", ".xqm", ".xquery", ".xqy", ".xqws"};

    /** The test runner libraries, under {@code src/main}, executed by every XQSuite or XML test file. */
    private static final String[] TEST_RUNNER_LIBRARIES = {
            "org/exist/xquery/lib/xqsuite/xqsuite.xql",
            "org/exist/xquery/lib/test.xq"
    };

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
                        acc.index(path, XQueryCalls.invocations(String.join("\n", collectStringLiterals(cu, ctx)), true));
                    } else {
                        collectFunctionNames(cu, ctx, acc);
                    }
                } else if (tree instanceof PlainText) {
                    final PlainText pt = (PlainText) tree;
                    final String path = pt.getSourcePath().toString();
                    if (isXQueryFile(path) && (isTestPath(path) || isTestRunnerLibrary(path))) {
                        acc.index(path, XQueryCalls.invocations(pt.getText(), true));
                    } else if (path.endsWith(".bats") && isTestPath(path)) {
                        // BATS integration tests run queries against a container via the client or REST API.
                        acc.index(path, XQueryCalls.invocations(pt.getText(), false));
                    }
                } else if (tree instanceof Xml.Document) {
                    final Xml.Document doc = (Xml.Document) tree;
                    final String path = doc.getSourcePath().toString();
                    if (isTestPath(path)) {
                        final String code = executedCode(doc);
                        if (code != null) {
                            acc.index(path, XQueryCalls.invocations(code, false));
                        }
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
                            for (final ResolvedName name : acc.resolveNames(info)) {
                                final Set<String> matches = acc.matchingTestPaths(name);
                                table.insertRow(ctx, new XQueryDynamicInvocationCoverageTable.Row(
                                        path,
                                        info.className,
                                        name.namespaceUri != null ? name.namespaceUri : "",
                                        name.localName,
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

    private static boolean isXQueryFile(final String path) {
        for (final String extension : XQUERY_EXTENSIONS) {
            if (path.endsWith(extension)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isTestRunnerLibrary(final String path) {
        final String normalized = path.replace('\\', '/');
        for (final String library : TEST_RUNNER_LIBRARIES) {
            if (normalized.endsWith("/" + library)) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return the XQuery a test XML document executes: a {@code <TestSet>}'s tests, or the
     *         {@code <text>} of a REST API {@code <query>} request body; null for any other
     *         document, which is test data rather than code
     */
    static @Nullable String executedCode(final Xml.Document doc) {
        final Xml.Tag root = doc.getRoot();
        if (root != null && "query".equals(localName(root)) && declaresExistNamespace(root)) {
            final StringBuilder code = new StringBuilder();
            for (final Xml.Tag child : root.getChildren()) {
                if ("text".equals(localName(child))) {
                    code.append(textContent(child)).append('\n');
                }
            }
            return code.toString();
        }
        return testSetCode(doc);
    }

    /**
     * Extracts the XQuery an XML {@code <TestSet>} executes, mirroring eXist-db's {@code test.xq}:
     * each test's {@code <code>} (and {@code <xpath>} assertions) is run with the set's
     * {@code <imports>}, {@code <variable>}s and {@code <functions>} as its prolog, skipping tests
     * whose {@code @ignore} is anything but {@code "no"}. Other children ({@code <description>},
     * {@code <task>}, {@code <expected>}, ...) are not code and are left out.
     *
     * @return the code, or null if the document is not a {@code <TestSet>}
     */
    static @Nullable String testSetCode(final Xml.Document doc) {
        final Xml.Tag root = doc.getRoot();
        if (root == null || !"TestSet".equals(root.getName())) {
            return null;
        }
        final StringBuilder code = new StringBuilder();
        for (final Xml.Tag child : root.getChildren()) {
            switch (child.getName()) {
                case "imports":
                case "functions":
                case "variable":
                    code.append(textContent(child)).append('\n');
                    break;
                case "test":
                    final String ignore = attribute(child, "ignore");
                    if (ignore == null || "no".equals(ignore)) {
                        for (final Xml.Tag part : child.getChildren()) {
                            if ("code".equals(part.getName()) || "xpath".equals(part.getName())) {
                                code.append(textContent(part)).append('\n');
                            }
                        }
                    }
                    break;
                default:
                    break;
            }
        }
        return code.toString();
    }

    private static String localName(final Xml.Tag tag) {
        final String name = tag.getName();
        return name.substring(name.indexOf(':') + 1);
    }

    private static boolean declaresExistNamespace(final Xml.Tag tag) {
        for (final Xml.Attribute attribute : tag.getAttributes()) {
            if (attribute.getKeyAsString().startsWith("xmlns") && EXIST_NS.equals(attribute.getValueAsString())) {
                return true;
            }
        }
        return false;
    }

    private static @Nullable String attribute(final Xml.Tag tag, final String name) {
        for (final Xml.Attribute attribute : tag.getAttributes()) {
            if (name.equals(attribute.getKeyAsString())) {
                return attribute.getValueAsString();
            }
        }
        return null;
    }

    private static String textContent(final Xml.Tag tag) {
        final StringBuilder text = new StringBuilder();
        if (tag.getContent() != null) {
            for (final Object content : tag.getContent()) {
                if (content instanceof Xml.CharData) {
                    final Xml.CharData charData = (Xml.CharData) content;
                    text.append(charData.isCdata() ? charData.getText() : unescape(charData.getText()));
                } else if (content instanceof Xml.Tag) {
                    text.append(textContent((Xml.Tag) content));
                }
            }
        }
        return text.toString();
    }

    private static String unescape(final String text) {
        if (text.indexOf('&') < 0) {
            return text;
        }
        return text.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&apos;", "'").replace("&amp;", "&");
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

    private static void collectFunctionNames(final JavaSourceFile cu, final ExecutionContext ctx, final Acc acc) {
        final String path = cu.getSourcePath().toString();
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
                    final Ref ref = ref(initializer);
                    if (ref != null) {
                        acc.constants.put(memberKey(currentClass(), result.getSimpleName()), ref);
                    }
                }
                return result;
            }

            @Override
            public J.NewClass visitNewClass(final J.NewClass newClass, final ExecutionContext c) {
                final J.NewClass result = super.visitNewClass(newClass, c);
                if (TypeUtils.isOfClassType(result.getType(), FUNCTION_SIGNATURE)) {
                    recordFirstArg(result.getArguments(), null);
                } else if (TypeUtils.isOfClassType(result.getType(), QNAME)) {
                    recordSignatureHelper(result);
                }
                return result;
            }

            @Override
            public J.MethodInvocation visitMethodInvocation(final J.MethodInvocation method,
                    final ExecutionContext c) {
                final J.MethodInvocation result = super.visitMethodInvocation(method, c);
                if (isSignatureHelperName(result.getSimpleName())) {
                    final JavaType.Method type = result.getMethodType();
                    recordFirstArg(result.getArguments(), type != null
                            ? memberKey(type.getDeclaringType().getFullyQualifiedName(), result.getSimpleName())
                            : null);
                }
                return result;
            }

            /**
             * Records a module's {@code functionSignature(String name, ...)} helper, e.g.
             * {@code return FunctionDSL.functionSignature(new QName(name, NAMESPACE_URI, PREFIX), ...)},
             * so that a function registering itself through it with a bare local name gets the
             * helper's namespace.
             */
            private void recordSignatureHelper(final J.NewClass qname) {
                final List<Expression> args = qname.getArguments();
                if (args.size() < 2 || !(args.get(0) instanceof J.Identifier) || classStack.isEmpty()) {
                    return;
                }
                final J.MethodDeclaration method = getCursor().firstEnclosing(J.MethodDeclaration.class);
                if (method == null || !isSignatureHelperName(method.getSimpleName())
                        || method.getParameters().isEmpty()) {
                    return;
                }
                final Statement firstParam = method.getParameters().get(0);
                if (firstParam instanceof J.VariableDeclarations
                        && !((J.VariableDeclarations) firstParam).getVariables().isEmpty()
                        && ((J.VariableDeclarations) firstParam).getVariables().get(0).getSimpleName()
                                .equals(((J.Identifier) args.get(0)).getSimpleName())) {
                    acc.signatureHelpers.put(memberKey(currentClass(), method.getSimpleName()),
                            new QNameRef(new Literal(""), ref(args.get(1)), args.size() > 2 ? ref(args.get(2)) : null));
                }
            }

            private void recordFirstArg(final List<Expression> args, final @Nullable String helperKey) {
                final String className = currentFunctionClass();
                if (className == null || args.isEmpty()) {
                    return;
                }
                final Ref ref = ref(args.get(0));
                if (ref != null) {
                    acc.implementationsByPath
                            .computeIfAbsent(path, p -> new LinkedHashMap<>())
                            .computeIfAbsent(className, ClassInfo::new)
                            .names.add(new SignatureName(ref, helperKey));
                }
            }

            private String currentClass() {
                return classStack.get(classStack.size() - 1);
            }

            private @Nullable String currentFunctionClass() {
                // The innermost enclosing function class decides attribution; a non-function class
                // nested in a function class (e.g. a helper) still attributes to the function class.
                for (int i = functionClassStack.size() - 1; i >= 0; i--) {
                    if (functionClassStack.get(i) != null) {
                        return functionClassStack.get(i);
                    }
                }
                return null;
            }

            /**
             * A reference to a {@code String} or {@code QName} value, to be resolved once every
             * file has been scanned.
             */
            private @Nullable Ref ref(final Expression expression) {
                if (expression instanceof J.Literal) {
                    final Object value = ((J.Literal) expression).getValue();
                    return value instanceof String ? new Literal((String) value) : null;
                }
                if (expression instanceof J.NewClass
                        && TypeUtils.isOfClassType(((J.NewClass) expression).getType(), QNAME)) {
                    final List<Expression> args = ((J.NewClass) expression).getArguments();
                    final Ref localName = args.isEmpty() ? null : ref(args.get(0));
                    if (localName == null) {
                        return null;
                    }
                    return new QNameRef(localName,
                            args.size() > 1 ? ref(args.get(1)) : null,
                            args.size() > 2 ? ref(args.get(2)) : null);
                }
                if (expression instanceof J.Identifier) {
                    return constantRef((J.Identifier) expression);
                }
                if (expression instanceof J.FieldAccess) {
                    return constantRef(((J.FieldAccess) expression).getName());
                }
                if (expression instanceof J.MethodInvocation
                        && "getLocalPart".equals(((J.MethodInvocation) expression).getSimpleName())
                        && ((J.MethodInvocation) expression).getSelect() != null) {
                    // `SOME_QNAME.getLocalPart()`: the local name of a QName constant.
                    return ref(((J.MethodInvocation) expression).getSelect());
                }
                return null;
            }

            private @Nullable Ref constantRef(final J.Identifier identifier) {
                final JavaType.Variable field = identifier.getFieldType();
                final JavaType.FullyQualified owner = field != null ? TypeUtils.asFullyQualified(field.getOwner()) : null;
                if (owner != null) {
                    return new Constant(memberKey(owner.getFullyQualifiedName(), identifier.getSimpleName()));
                }
                // Missing type attribution: assume a constant in the enclosing class.
                return classStack.isEmpty() ? null : new Constant(memberKey(currentClass(), identifier.getSimpleName()));
            }
        }.visit(cu, ctx);
    }

    private static boolean isSignatureHelperName(final String name) {
        return "functionSignature".equals(name) || "functionSignatures".equals(name);
    }

    private static boolean isFunctionImplementation(final JavaType.FullyQualified type) {
        return (TypeUtils.isAssignableTo(FUNCTION, type) || TypeUtils.isAssignableTo(BASIC_FUNCTION, type))
                && !TypeUtils.isAssignableTo(USER_DEFINED_FUNCTION, type);
    }

    private static String memberKey(final String ownerFqn, final String name) {
        return ownerFqn + "#" + name;
    }

    public static class Acc {
        final Map<String, Map<String, ClassInfo>> implementationsByPath = new LinkedHashMap<>();
        final Map<String, Ref> constants = new HashMap<>();
        final Map<String, QNameRef> signatureHelpers = new HashMap<>();
        /** Test paths by invocation key, see {@link XQueryCalls}. */
        final Map<String, Set<String>> testPathsByInvocation = new HashMap<>();
        /** Test paths by invoked local name, for functions whose namespace is unknown. */
        final Map<String, Set<String>> testPathsByLocalName = new HashMap<>();
        private @Nullable Map<String, Set<String>> prefixesByNamespace;

        void index(final String path, final Set<String> invocations) {
            for (final String key : invocations) {
                testPathsByInvocation.computeIfAbsent(key, k -> new LinkedHashSet<>()).add(path);
                testPathsByLocalName.computeIfAbsent(XQueryCalls.localName(key), k -> new LinkedHashSet<>()).add(path);
            }
        }

        Set<ResolvedName> resolveNames(final ClassInfo info) {
            final Set<ResolvedName> resolved = new LinkedHashSet<>();
            for (final SignatureName name : info.names) {
                final ResolvedName r = resolve(name);
                if (r != null) {
                    resolved.add(r);
                }
            }
            return resolved;
        }

        @Nullable ResolvedName resolve(final SignatureName name) {
            final ResolvedName r = name.ref.resolve(this, 0);
            if (r == null || r.namespaceUri != null || name.helperKey == null) {
                return r;
            }
            // A bare local name passed to a module's helper takes the helper's namespace.
            final QNameRef helper = signatureHelpers.get(name.helperKey);
            final ResolvedName template = helper != null ? helper.resolve(this, 0) : null;
            return template != null ? new ResolvedName(r.localName, template.namespaceUri, template.prefix) : r;
        }

        Set<String> matchingTestPaths(final ResolvedName name) {
            if (name.namespaceUri == null) {
                return testPathsByLocalName.getOrDefault(name.localName, Collections.emptySet());
            }
            final Set<String> paths = new LinkedHashSet<>(testPathsByInvocation.getOrDefault(
                    XQueryCalls.qualifiedKey(name.namespaceUri, name.localName), Collections.emptySet()));
            for (final String prefix : prefixesByNamespace().getOrDefault(name.namespaceUri, Collections.emptySet())) {
                paths.addAll(testPathsByInvocation.getOrDefault(
                        XQueryCalls.undeclaredPrefixKey(prefix, name.localName), Collections.emptySet()));
            }
            return paths;
        }

        /** The statically bound prefixes: the XQuery predeclared ones plus each module's default prefix. */
        private Map<String, Set<String>> prefixesByNamespace() {
            if (prefixesByNamespace == null) {
                final Map<String, Set<String>> prefixes = new HashMap<>();
                for (final Map.Entry<String, String> predeclared : XQueryCalls.PREDECLARED_PREFIXES.entrySet()) {
                    prefixes.computeIfAbsent(predeclared.getValue(), ns -> new LinkedHashSet<>()).add(predeclared.getKey());
                }
                for (final Map<String, ClassInfo> classes : implementationsByPath.values()) {
                    for (final ClassInfo info : classes.values()) {
                        for (final ResolvedName name : resolveNames(info)) {
                            if (name.namespaceUri != null && name.prefix != null && !name.prefix.isEmpty()) {
                                prefixes.computeIfAbsent(name.namespaceUri, ns -> new LinkedHashSet<>()).add(name.prefix);
                            }
                        }
                    }
                }
                prefixesByNamespace = prefixes;
            }
            return prefixesByNamespace;
        }
    }

    private static class ClassInfo {
        final String className;
        final List<SignatureName> names = new ArrayList<>();

        ClassInfo(final String className) {
            this.className = className;
        }
    }

    /** The name a function registers, and the helper it registered it through, if any. */
    private static class SignatureName {
        final Ref ref;
        final @Nullable String helperKey;

        SignatureName(final Ref ref, final @Nullable String helperKey) {
            this.ref = ref;
            this.helperKey = helperKey;
        }
    }

    @Value
    static class ResolvedName {
        String localName;
        @Nullable String namespaceUri;
        @Nullable String prefix;
    }

    /** An unresolved {@code String} or {@code QName} value. */
    private abstract static class Ref {
        // Bounds constant chains, guarding against a cycle.
        static final int MAX_DEPTH = 16;

        abstract @Nullable ResolvedName resolve(Acc acc, int depth);

        static @Nullable String resolveString(final @Nullable Ref ref, final Acc acc, final int depth) {
            final ResolvedName r = ref != null ? ref.resolve(acc, depth + 1) : null;
            return r != null ? r.localName : null;
        }
    }

    private static class Literal extends Ref {
        final String value;

        Literal(final String value) {
            this.value = value;
        }

        @Override
        @Nullable ResolvedName resolve(final Acc acc, final int depth) {
            return new ResolvedName(value, null, null);
        }
    }

    private static class Constant extends Ref {
        final String key;

        Constant(final String key) {
            this.key = key;
        }

        @Override
        @Nullable ResolvedName resolve(final Acc acc, final int depth) {
            final Ref target = acc.constants.get(key);
            return target != null && depth < MAX_DEPTH ? target.resolve(acc, depth + 1) : null;
        }
    }

    private static class QNameRef extends Ref {
        final Ref localName;
        final @Nullable Ref namespaceUri;
        final @Nullable Ref prefix;

        QNameRef(final Ref localName, final @Nullable Ref namespaceUri, final @Nullable Ref prefix) {
            this.localName = localName;
            this.namespaceUri = namespaceUri;
            this.prefix = prefix;
        }

        @Override
        @Nullable ResolvedName resolve(final Acc acc, final int depth) {
            if (depth >= MAX_DEPTH) {
                return null;
            }
            final String local = resolveString(localName, acc, depth);
            if (local == null) {
                return null;
            }
            String ns = resolveString(namespaceUri, acc, depth);
            if (ns != null && ns.isEmpty()) {
                ns = null;
            }
            return new ResolvedName(local, ns, resolveString(prefix, acc, depth));
        }
    }
}
