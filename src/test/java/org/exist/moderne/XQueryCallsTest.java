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
        assertThat(XQueryCalls.invocations(code, true).keySet())
                .containsExactlyInAnyOrder("?x:a", "?x:c");
        assertThat(XQueryCalls.invocations(code, false).keySet())
                .containsExactlyInAnyOrder("?x:a", "?x:b", "?x:c", "?x:d");
    }

    @Test
    void resolvesDeclaredAndDefaultFunctionNamespaces() {
        assertThat(XQueryCalls.invocations("""
                import module namespace m = "urn:m" at "m.xqm";
                declare default function namespace "urn:default";
                m:one(), two(), u:three(), $f(1), local:four#0
                """, false).keySet())
                .containsExactlyInAnyOrder("{urn:m}one", "{urn:default}two", "?u:three", "?local:four");
    }

    @Test
    void localNameOfKey() {
        assertThat(XQueryCalls.localName("{urn:m}one")).isEqualTo("one");
        assertThat(XQueryCalls.localName("?u:three")).isEqualTo("three");
    }

    @Test
    void countsArguments() {
        assertThat(XQueryCalls.invocations("""
                x:none(), x:one( 'a, b' ), x:two((1, 2), [3, 4]), x:quoted('it''s, here', "x"),
                x:nested(x:inner(1, 2), map { 'k': 1, 'l': 2 }), x:partial(?, 1), x:unclosed(
                """, false))
                .containsEntry("?x:none", java.util.Set.of(0))
                .containsEntry("?x:one", java.util.Set.of(1))
                .containsEntry("?x:two", java.util.Set.of(2))
                .containsEntry("?x:quoted", java.util.Set.of(2))
                .containsEntry("?x:nested", java.util.Set.of(2))
                .containsEntry("?x:inner", java.util.Set.of(2))
                .containsEntry("?x:partial", java.util.Set.of(2))
                .containsEntry("?x:unclosed", java.util.Set.of(XQueryCalls.UNKNOWN_ARITY));
    }

    @Test
    void addsArrowOperandAndReadsReferenceArities() {
        assertThat(XQueryCalls.invocations("""
                $s => x:arrow(1), $s =!> x:mapping-arrow(), x:ref#3,
                function-lookup(xs:QName("x:lookup"), 2), function-lookup(xs:QName("x:lookup-any"), $n)
                """, false))
                .containsEntry("?x:arrow", java.util.Set.of(2))
                .containsEntry("?x:mapping-arrow", java.util.Set.of(1))
                .containsEntry("?x:ref", java.util.Set.of(3))
                .containsEntry("?x:lookup", java.util.Set.of(2))
                .containsEntry("?x:lookup-any", java.util.Set.of(XQueryCalls.UNKNOWN_ARITY));
    }

    @Test
    void declarationsInsideStringsOrBodiesDoNotSplitFunctions() {
        final String code = """
                module namespace t = "urn:t";
                declare namespace test = "http://exist-db.org/xquery/xqsuite";
                declare %test:assertEquals(70) function t:eval() {
                    util:eval('declare function local:add($a, $b) { $a + $b };
                        let $f := util:function(xs:QName("local:add"), 2) return util:call($f, 20, 50)')
                };
                declare %test:pending function t:skipped() {
                    util:eval("declare function local:x() { 1 }; x:after-inner-declare()")
                };
                """;
        assertThat(XQueryCalls.invocations(code, true).keySet())
                .contains("?util:eval", "?util:function", "?util:call")
                .doesNotContain("?x:after-inner-declare");
    }
}
