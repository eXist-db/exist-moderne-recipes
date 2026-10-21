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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns Prethink's {@code JavaType.Method#toString} signatures and JaCoCo's JVM descriptors into the same
 * erased Java parameter types, so a row of {@code test-gaps.csv} can be joined to a JaCoCo method.
 */
final class MethodSignatures {

    private static final Map<Character, String> PRIMITIVES = new HashMap<>();

    static {
        PRIMITIVES.put('Z', "boolean");
        PRIMITIVES.put('B', "byte");
        PRIMITIVES.put('C', "char");
        PRIMITIVES.put('S', "short");
        PRIMITIVES.put('I', "int");
        PRIMITIVES.put('J', "long");
        PRIMITIVES.put('F', "float");
        PRIMITIVES.put('D', "double");
        PRIMITIVES.put('V', "void");
    }

    private static final Pattern SIGNATURE = Pattern.compile(
            "^(?<cls>[^{]+)\\{name=(?<name>[^,]+),return=(?<ret>.*?),parameters=\\[(?<params>.*)]}$");
    private static final Pattern GENERIC = Pattern.compile(
            "^Generic\\{(\\w+)(?: extends ([^&}<]+))?.*}((?:\\[])*)$");

    private MethodSignatures() {
    }

    /** A Prethink method with its parameter types erased; the name is JaCoCo's ({@code <init>} for constructors). */
    static final class Erased {
        private final String className;
        private final String name;
        private final List<String> parameters;

        Erased(final String className, final String name, final List<String> parameters) {
            this.className = className;
            this.name = name;
            this.parameters = parameters;
        }

        String className() {
            return className;
        }

        String name() {
            return name;
        }

        List<String> parameters() {
            return parameters;
        }
    }

    /** @return the erased method, or null if the signature is not in Prethink's format */
    static Erased parse(final String signature) {
        final Matcher m = SIGNATURE.matcher(signature);
        if (!m.matches()) {
            return null;
        }
        final String name = "<constructor>".equals(m.group("name")) ? "<init>" : m.group("name");
        final List<String> parameters = new ArrayList<>();
        for (final String p : splitTopLevel(m.group("params"))) {
            parameters.add(erase(p));
        }
        return new Erased(m.group("cls"), name, parameters);
    }

    /** Prethink parameter type to erased Java type: a type variable becomes its first bound or Object. */
    static String erase(final String type) {
        final Matcher m = GENERIC.matcher(type);
        if (m.matches()) {
            return (m.group(2) == null ? "java.lang.Object" : m.group(2).trim()) + m.group(3);
        }
        String t = type;
        final StringBuilder arrays = new StringBuilder();
        while (t.endsWith("[]")) {
            arrays.append("[]");
            t = t.substring(0, t.length() - 2);
        }
        return dropTypeArguments(t).replace("...", "[]") + arrays;
    }

    /** {@code Map<K, V>.Entry<X>} to {@code Map.Entry}: removes every balanced {@code <...>} group. */
    private static String dropTypeArguments(final String type) {
        final StringBuilder out = new StringBuilder();
        int depth = 0;
        for (final char c : type.toCharArray()) {
            if (c == '<') {
                depth++;
            } else if (c == '>' && depth > 0) {
                depth--;
            } else if (depth == 0) {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** JVM descriptor {@code (Ljava/lang/String;[I)V} to {@code [java.lang.String, int[]]}. */
    static List<String> descriptorParameters(final String descriptor) {
        final String args = descriptor.substring(1, descriptor.indexOf(')'));
        final List<String> out = new ArrayList<>();
        int i = 0;
        while (i < args.length()) {
            int dims = 0;
            while (args.charAt(i) == '[') {
                dims++;
                i++;
            }
            final String type;
            if (args.charAt(i) == 'L') {
                final int end = args.indexOf(';', i);
                type = args.substring(i + 1, end).replace('/', '.');
                i = end + 1;
            } else {
                type = PRIMITIVES.get(args.charAt(i));
                i++;
            }
            final StringBuilder name = new StringBuilder(type);
            for (int d = 0; d < dims; d++) {
                name.append("[]");
            }
            out.add(name.toString());
        }
        return out;
    }

    private static List<String> splitTopLevel(final String s) {
        final List<String> out = new ArrayList<>();
        final StringBuilder cur = new StringBuilder();
        int depth = 0;
        for (final char ch : s.toCharArray()) {
            if (ch == '<' || ch == '{') {
                depth++;
            } else if (ch == '>' || ch == '}') {
                depth--;
            }
            if (ch == ',' && depth == 0) {
                out.add(cur.toString().trim());
                cur.setLength(0);
            } else {
                cur.append(ch);
            }
        }
        if (!cur.toString().trim().isEmpty()) {
            out.add(cur.toString().trim());
        }
        return out;
    }
}
