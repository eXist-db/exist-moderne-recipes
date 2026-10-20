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

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
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
    // (`declare function local:count(`), which is not an invocation.
    private static final Pattern INVOCATION = Pattern.compile(
            "(?<![\\p{L}\\p{N}_.\\-:$%])(function\\s+)?(?:(" + NCNAME + "):)?(" + NCNAME + ")\\s*(?:\\(|#\\d)");

    // `xs:QName('p:name')`, as used with `function-lookup(...)`.
    private static final Pattern QNAME_LOOKUP = Pattern.compile(
            "QName\\(\\s*[\"'](?:(" + NCNAME + "):)?(" + NCNAME + ")[\"']");

    private static final Pattern NAMESPACE_DECLARATION = Pattern.compile(
            "\\b(?:declare\\s+namespace|import\\s+module\\s+namespace|module\\s+namespace|import\\s+schema\\s+namespace)"
                    + "\\s+(" + NCNAME + ")\\s*=\\s*([\"'])(.*?)\\2");

    private static final Pattern DEFAULT_FUNCTION_NAMESPACE = Pattern.compile(
            "\\bdeclare\\s+default\\s+function\\s+namespace\\s+([\"'])(.*?)\\1");

    // The start of a prolog declaration; group 1 is set when it declares a function.
    private static final Pattern PROLOG_DECLARATION = Pattern.compile(
            "\\bdeclare\\s+(?:((?:%|updating\\b|function\\b))|(?:variable|namespace|option|context|default|"
                    + "boundary-space|base-uri|construction|copy-namespaces|decimal-format|ordering)\\b)");

    // An XQSuite `%test:pending` annotation (under any prefix bound to the XQSuite namespace).
    private static final Pattern PENDING = Pattern.compile("%(?:" + NCNAME + ":)?pending\\b");

    private XQueryCalls() {
    }

    /**
     * @param text XQuery text, possibly a whole module
     * @param skipPendingTests whether to ignore the bodies of {@code %test:pending} functions,
     *         which XQSuite never runs
     * @return the invocation keys, see the class description
     */
    static Set<String> invocations(final String text, final boolean skipPendingTests) {
        String code = stripComments(text);
        if (skipPendingTests) {
            code = stripPendingFunctions(code);
        }

        final Map<String, String> declared = new HashMap<>();
        final Matcher declaration = NAMESPACE_DECLARATION.matcher(code);
        while (declaration.find()) {
            declared.put(declaration.group(1), declaration.group(3));
        }
        final Matcher defaultNs = DEFAULT_FUNCTION_NAMESPACE.matcher(code);
        final String defaultFunctionNs = defaultNs.find() ? defaultNs.group(2) : FN_NS;

        final Set<String> keys = new LinkedHashSet<>();
        final Matcher invocation = INVOCATION.matcher(code);
        while (invocation.find()) {
            if (invocation.group(1) == null) {
                keys.add(key(invocation.group(2), invocation.group(3), declared, defaultFunctionNs));
            }
        }
        final Matcher lookup = QNAME_LOOKUP.matcher(code);
        while (lookup.find()) {
            keys.add(key(lookup.group(1), lookup.group(2), declared, defaultFunctionNs));
        }
        return keys;
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
     * up to the next prolog declaration. A {@code declare} inside a string in the body (e.g. a
     * query passed to {@code util:eval}) ends the removal early, keeping the rest of that body.
     */
    static String stripPendingFunctions(final String code) {
        if (!code.contains("pending")) {
            return code;
        }
        final StringBuilder out = new StringBuilder(code.length());
        final Matcher declaration = PROLOG_DECLARATION.matcher(code);
        int copiedTo = 0;
        int pendingStart = -1;
        while (declaration.find()) {
            if (pendingStart >= 0) {
                copiedTo = declaration.start();
                pendingStart = -1;
            }
            if (declaration.group(1) != null && isPending(code, declaration.start())) {
                out.append(code, copiedTo, declaration.start());
                pendingStart = declaration.start();
            }
        }
        if (pendingStart < 0) {
            out.append(code, copiedTo, code.length());
        }
        return out.toString();
    }

    private static boolean isPending(final String code, final int declarationStart) {
        // The annotations precede the parameter list, and so the body.
        final int body = code.indexOf('{', declarationStart);
        final String header = code.substring(declarationStart, body < 0 ? code.length() : body);
        return PENDING.matcher(header).find();
    }
}
