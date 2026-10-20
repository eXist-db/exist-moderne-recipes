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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lightweight, regex-based extraction of the XQuery functions a piece of XQuery text invokes.
 * <p>
 * Each invocation is reported as a key: {@code {namespace-uri}local-name} when its prefix is bound
 * by a declaration in the text itself (or it is unprefixed, and so in the default function
 * namespace), or {@code ?prefix:local-name} when the prefix is not declared in the text, as is
 * usual for eXist-db's builtin modules, whose default prefixes ({@code util}, {@code xmldb}, ...)
 * are bound statically. The caller resolves the latter against the modules' known prefixes.
 */
final class XQueryCalls {

    static final String FN_NS = "http://www.w3.org/2005/xpath-functions";
    static final String XQSUITE_NS = "http://exist-db.org/xquery/xqsuite";

    /** The arity of an invocation whose argument count could not be determined. */
    static final int UNKNOWN_ARITY = -1;

    /** Prefixes bound in every XQuery static context, whatever modules exist. */
    static final Map<String, String> PREDECLARED_PREFIXES;

    static {
        final Map<String, String> prefixes = new HashMap<>();
        prefixes.put("fn", FN_NS);
        prefixes.put("map", FN_NS + "/map");
        prefixes.put("array", FN_NS + "/array");
        prefixes.put("math", "http://www.w3.org/2005/xpath-functions/math");
        prefixes.put("xs", "http://www.w3.org/2001/XMLSchema");
        PREDECLARED_PREFIXES = Collections.unmodifiableMap(prefixes);
    }

    private static final String NAME_START = "[\\p{L}_]";
    private static final String NAME_CHAR = "[\\p{L}\\p{N}_.\\-\\p{M}]";
    private static final String NCNAME = NAME_START + NAME_CHAR + "*";

    // A function call `p:name(` or function reference `p:name#1`. The lookbehind stops a match
    // starting mid-name (so `local-name(` never yields `name`), on a variable (`$f(`), or on an
    // annotation (`%test:args(`); group 1 flags a function declaration
    // (`declare function local:count(`), which is not an invocation; group 4 is a reference's arity.
    private static final Pattern INVOCATION = Pattern.compile(
            "(?<![\\p{L}\\p{N}_.\\-:$%])(function\\s+)?(?:(" + NCNAME + "):)?(" + NCNAME + ")\\s*(?:\\(|#(\\d+))");

    // `xs:QName('p:name')`, as used with `function-lookup(xs:QName('p:name'), 2)`; group 3 is the arity.
    private static final Pattern QNAME_LOOKUP = Pattern.compile(
            "QName\\(\\s*[\"'](?:(" + NCNAME + "):)?(" + NCNAME + ")[\"']\\s*\\)(?:\\s*,\\s*(\\d+))?");

    // An arrow operator (`=>`, or XQuery 4's mapping arrow `=!>`) before a call supplies its first argument.
    private static final Pattern ARROW_BEFORE = Pattern.compile("=!?>\\s*$");

    private static final Pattern NAMESPACE_DECLARATION = Pattern.compile(
            "\\b(?:declare\\s+namespace|import\\s+module\\s+namespace|module\\s+namespace|import\\s+schema\\s+namespace)"
                    + "\\s+(" + NCNAME + ")\\s*=\\s*([\"'])(.*?)\\2");

    private static final Pattern DEFAULT_FUNCTION_NAMESPACE = Pattern.compile(
            "\\bdeclare\\s+default\\s+function\\s+namespace\\s+([\"'])(.*?)\\1");

    // The start of a prolog declaration; group 1 is set when it may declare a function.
    private static final Pattern PROLOG_DECLARATION = Pattern.compile(
            "\\bdeclare\\s+(?:((?:%|updating\\b|function\\b))|(?:variable|namespace|option|context|default|"
                    + "boundary-space|base-uri|construction|copy-namespaces|decimal-format|ordering)\\b)");

    private static final Pattern FUNCTION_NAME = Pattern.compile(
            "\\bfunction\\s+((?:" + NCNAME + ":)?" + NCNAME + ")\\s*\\(");

    private static final Pattern ANNOTATION = Pattern.compile("%(?:(" + NCNAME + "):)?(" + NCNAME + ")");

    // An XQSuite `%test:pending` annotation (under any prefix bound to the XQSuite namespace).
    private static final Pattern PENDING = Pattern.compile("%(?:" + NCNAME + ":)?pending\\b");

    /** How far to scan for the closing parenthesis of a call before giving up on its arity. */
    private static final int MAX_ARGUMENT_SCAN = 20_000;

    private XQueryCalls() {
    }

    /**
     * @param text XQuery text, possibly a whole module
     * @param xqsuiteModule whether the text is an XQSuite module, whose {@code %test:pending}
     *         functions never run and whose other functions only run when reached from a test
     * @return the arities of each invocation key, see the class description; an arity is
     *         {@link #UNKNOWN_ARITY} if it could not be determined
     */
    static Map<String, Set<Integer>> invocations(final String text, final boolean xqsuiteModule) {
        String code = stripComments(text);
        if (xqsuiteModule) {
            code = reachableCode(stripPendingFunctions(code));
        }

        final Map<String, String> declared = new HashMap<>();
        final Matcher declaration = NAMESPACE_DECLARATION.matcher(code);
        while (declaration.find()) {
            declared.put(declaration.group(1), declaration.group(3));
        }
        final Matcher defaultNs = DEFAULT_FUNCTION_NAMESPACE.matcher(code);
        final String defaultFunctionNs = defaultNs.find() ? defaultNs.group(2) : FN_NS;

        final Map<String, Set<Integer>> invocations = new LinkedHashMap<>();
        final Matcher invocation = INVOCATION.matcher(code);
        while (invocation.find()) {
            if (invocation.group(1) == null) {
                final int arity;
                if (invocation.group(4) != null) {
                    arity = Integer.parseInt(invocation.group(4));
                } else {
                    final int args = argumentCount(code, invocation.end());
                    final boolean arrow = ARROW_BEFORE.matcher(
                            code.substring(Math.max(0, invocation.start() - 8), invocation.start())).find();
                    arity = args == UNKNOWN_ARITY ? UNKNOWN_ARITY : args + (arrow ? 1 : 0);
                }
                invocations.computeIfAbsent(key(invocation.group(2), invocation.group(3), declared, defaultFunctionNs),
                        k -> new LinkedHashSet<>()).add(arity);
            }
        }
        final Matcher lookup = QNAME_LOOKUP.matcher(code);
        while (lookup.find()) {
            invocations.computeIfAbsent(key(lookup.group(1), lookup.group(2), declared, defaultFunctionNs),
                    k -> new LinkedHashSet<>()).add(lookup.group(3) != null ? Integer.parseInt(lookup.group(3)) : UNKNOWN_ARITY);
        }
        return invocations;
    }

    static String qualifiedKey(final String namespaceUri, final String localName) {
        return "{" + namespaceUri + "}" + localName;
    }

    static String undeclaredPrefixKey(final String prefix, final String localName) {
        return "?" + prefix + ":" + localName;
    }

    /** @return the local name of an invocation key */
    static String localName(final String key) {
        return key.substring(key.startsWith("{") ? key.indexOf('}') + 1 : key.indexOf(':') + 1);
    }

    private static String key(final String prefix, final String localName, final Map<String, String> declared,
            final String defaultFunctionNs) {
        if (prefix == null) {
            return qualifiedKey(defaultFunctionNs, localName);
        }
        final String namespaceUri = declared.get(prefix);
        return namespaceUri != null ? qualifiedKey(namespaceUri, localName) : undeclaredPrefixKey(prefix, localName);
    }

    /**
     * Counts the top-level arguments of a call whose opening parenthesis ends just before
     * {@code start}, skipping string literals and nested brackets.
     *
     * @return the argument count, or {@link #UNKNOWN_ARITY} if the closing parenthesis is not found
     */
    static int argumentCount(final String code, final int start) {
        final int end = Math.min(code.length(), start + MAX_ARGUMENT_SCAN);
        int depth = 0;
        int commas = 0;
        boolean anyArgument = false;
        for (int i = start; i < end; i++) {
            final char c = code.charAt(i);
            if (c == '"' || c == '\'') {
                // XQuery escapes a quote inside a string literal by doubling it.
                int close = code.indexOf(c, i + 1);
                while (close >= 0 && close + 1 < end && code.charAt(close + 1) == c) {
                    close = code.indexOf(c, close + 2);
                }
                if (close < 0) {
                    return UNKNOWN_ARITY;
                }
                anyArgument = true;
                i = close;
            } else if (c == '(' || c == '[' || c == '{') {
                depth++;
                anyArgument = true;
            } else if (c == ')' || c == ']' || c == '}') {
                if (depth == 0) {
                    return c == ')' ? (anyArgument ? commas + 1 : 0) : UNKNOWN_ARITY;
                }
                depth--;
            } else if (c == ',' && depth == 0) {
                commas++;
            } else if (!Character.isWhitespace(c)) {
                anyArgument = true;
            }
        }
        return UNKNOWN_ARITY;
    }

    /**
     * Removes (possibly nested) XQuery {@code (: ... :)} comments. An unterminated comment is left
     * in place rather than swallowing the rest of the text.
     */
    static String stripComments(final String text) {
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

    /**
     * Removes each function declaration annotated {@code %test:pending}, from its {@code declare}
     * up to the next prolog declaration (a {@code declare} inside a string, such as a query passed
     * to {@code util:eval}, or inside a body does not count as one).
     */
    static String stripPendingFunctions(final String code) {
        if (!code.contains("pending")) {
            return code;
        }
        final StringBuilder out = new StringBuilder(code.length());
        for (final Declaration declaration : declarations(code)) {
            if (!(declaration.isFunction() && PENDING.matcher(declaration.header(code)).find())) {
                out.append(code, declaration.start, declaration.end);
            }
        }
        return out.toString();
    }

    /**
     * Keeps only the code of an XQSuite module that runs: its prolog, its test functions (any
     * function with an XQSuite annotation, such as {@code %test:assertEquals} or
     * {@code %test:setUp}), functions with any other prefixed annotation (e.g. RESTXQ's
     * {@code %rest:GET}, invoked by another engine), and the functions those transitively call.
     * A module without XQSuite annotations is returned unchanged.
     */
    static String reachableCode(final String code) {
        final Set<String> xqsuitePrefixes = new HashSet<>();
        final Matcher ns = NAMESPACE_DECLARATION.matcher(code);
        while (ns.find()) {
            if (XQSUITE_NS.equals(ns.group(3))) {
                xqsuitePrefixes.add(ns.group(1));
            }
        }
        if (xqsuitePrefixes.isEmpty()) {
            return code;
        }

        final List<Declaration> declarations = declarations(code);
        final Map<String, Declaration> functions = new HashMap<>();
        final Deque<String> pending = new ArrayDeque<>();
        boolean anyTest = false;
        for (final Declaration declaration : declarations) {
            final String name = declaration.isFunction() ? declaration.functionName(code) : null;
            if (name == null) {
                // The module/import preamble, variables, options...: always evaluated.
                pending.addAll(calledNames(declaration.text(code)));
                continue;
            }
            functions.put(name, declaration);
            boolean root = false;
            final Matcher annotation = ANNOTATION.matcher(declaration.header(code));
            while (annotation.find()) {
                final String prefix = annotation.group(1);
                // Unprefixed annotations (%private, %public, ...) don't make a function an entry point.
                if (prefix != null) {
                    root = true;
                    anyTest |= xqsuitePrefixes.contains(prefix);
                }
            }
            if (root) {
                pending.add(name);
            }
        }
        if (!anyTest) {
            return code;
        }

        final Set<String> reachable = new HashSet<>();
        while (!pending.isEmpty()) {
            final String name = pending.pop();
            final Declaration function = functions.get(name);
            if (function != null && reachable.add(name)) {
                pending.addAll(calledNames(function.text(code)));
            }
        }

        final StringBuilder out = new StringBuilder(code.length());
        for (final Declaration declaration : declarations) {
            final String name = declaration.isFunction() ? declaration.functionName(code) : null;
            if (name == null || reachable.contains(name)) {
                out.append(code, declaration.start, declaration.end);
            } else {
                out.append('\n');
            }
        }
        return out.toString();
    }

    private static Set<String> calledNames(final String text) {
        final Set<String> names = new LinkedHashSet<>();
        final Matcher invocation = INVOCATION.matcher(text);
        while (invocation.find()) {
            if (invocation.group(1) == null) {
                names.add(lexicalName(invocation.group(2), invocation.group(3)));
            }
        }
        final Matcher lookup = QNAME_LOOKUP.matcher(text);
        while (lookup.find()) {
            names.add(lexicalName(lookup.group(1), lookup.group(2)));
        }
        return names;
    }

    private static String lexicalName(final String prefix, final String localName) {
        return prefix == null ? localName : prefix + ":" + localName;
    }

    /**
     * Splits code into the text before the first prolog declaration and one region per
     * declaration, each up to the next one. The regions cover the code exactly.
     */
    private static List<Declaration> declarations(final String code) {
        final List<Declaration> declarations = new ArrayList<>();
        final BitSet topLevel = topLevelPositions(code);
        final Matcher matcher = PROLOG_DECLARATION.matcher(code);
        int start = 0;
        boolean function = false;
        while (matcher.find()) {
            // A `declare` in a string (a query passed to util:eval) or in a function body is not a
            // prolog declaration of this module.
            if (!topLevel.get(matcher.start())) {
                continue;
            }
            if (matcher.start() > start) {
                declarations.add(new Declaration(start, matcher.start(), function));
            }
            start = matcher.start();
            function = matcher.group(1) != null;
        }
        declarations.add(new Declaration(start, code.length(), function));
        return declarations;
    }

    /**
     * @return the positions outside string literals and outside any {@code {...}} block, where a
     *         prolog declaration can start
     */
    private static BitSet topLevelPositions(final String code) {
        final BitSet topLevel = new BitSet(code.length());
        int depth = 0;
        for (int i = 0; i < code.length(); i++) {
            final char c = code.charAt(i);
            if (c == '"' || c == '\'') {
                // XQuery escapes a quote inside a string literal by doubling it.
                int close = code.indexOf(c, i + 1);
                while (close >= 0 && close + 1 < code.length() && code.charAt(close + 1) == c) {
                    close = code.indexOf(c, close + 2);
                }
                if (close < 0) {
                    break;
                }
                i = close;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth = Math.max(0, depth - 1);
            } else if (depth == 0) {
                topLevel.set(i);
            }
        }
        return topLevel;
    }

    private static final class Declaration {
        final int start;
        final int end;
        final boolean function;

        Declaration(final int start, final int end, final boolean function) {
            this.start = start;
            this.end = end;
            this.function = function;
        }

        boolean isFunction() {
            return function;
        }

        String text(final String code) {
            return code.substring(start, end);
        }

        /** The text before the function body: annotations, name and parameters. */
        String header(final String code) {
            final int body = code.indexOf('{', start);
            return code.substring(start, body < 0 || body > end ? end : body);
        }

        /** @return the declared function's lexical QName, or null if this declares something else */
        String functionName(final String code) {
            final Matcher name = FUNCTION_NAME.matcher(header(code));
            return name.find() ? name.group(1) : null;
        }
    }
}
