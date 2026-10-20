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

import static org.assertj.core.api.Assertions.assertThat;

class XQueryCallsTest {

    @Test
    void stripCommentsHandlesNestingAndLeavesUnterminatedCommentInPlace() {
        assertThat(XQueryCalls.stripComments("a (: b (: c :) d :) e")).isEqualTo("a   e");
        assertThat(XQueryCalls.stripComments("a (: b")).isEqualTo("a (: b");
    }

    @Test
    void stripPendingFunctionsRemovesOnlyPendingDeclarations() {
        final String code = """
                declare %test:assertTrue function t:a() { x:a() };
                declare %test:pending %test:assertTrue function t:b() { x:b() };
                declare %test:assertTrue function t:c() { x:c() };
                declare %test:pending("last") function t:d() { x:d() };
                """;
        assertThat(XQueryCalls.invocations(code, true))
                .containsExactlyInAnyOrder("?x:a", "?x:c");
        assertThat(XQueryCalls.invocations(code, false))
                .containsExactlyInAnyOrder("?x:a", "?x:b", "?x:c", "?x:d");
    }

    @Test
    void resolvesDeclaredAndDefaultFunctionNamespaces() {
        assertThat(XQueryCalls.invocations("""
                import module namespace m = "urn:m" at "m.xqm";
                declare default function namespace "urn:default";
                m:one(), two(), u:three(), $f(1), local:four#0
                """, false))
                .containsExactlyInAnyOrder("{urn:m}one", "{urn:default}two", "?u:three", "?local:four");
    }

    @Test
    void localNameOfKey() {
        assertThat(XQueryCalls.localName("{urn:m}one")).isEqualTo("one");
        assertThat(XQueryCalls.localName("?u:three")).isEqualTo("three");
    }
}
