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

import java.util.Arrays;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

class MethodSignaturesTest {

    private static MethodSignatures.Erased parse(final String name, final String parameters) {
        return MethodSignatures.parse("a.Foo{name=" + name + ",return=void,parameters=[" + parameters + "]}");
    }

    @Test
    void parsesNoParameters() {
        final MethodSignatures.Erased m = parse("run", "");
        assertThat(m.className()).isEqualTo("a.Foo");
        assertThat(m.name()).isEqualTo("run");
        assertThat(m.parameters()).isEmpty();
    }

    @Test
    void returnsNullForAnythingElse() {
        assertThat(MethodSignatures.parse("not a signature")).isNull();
    }

    @Test
    void namesConstructorsLikeJaCoCo() {
        assertThat(parse("<constructor>", "int").name()).isEqualTo("<init>");
    }

    @Test
    void keepsNestedClassDollar() {
        assertThat(MethodSignatures.parse("a.Foo$Inner{name=run,return=void,parameters=[]}").className())
                .isEqualTo("a.Foo$Inner");
    }

    @Test
    void dropsTypeArgumentsAndSplitsOnTopLevelCommasOnly() {
        assertThat(parse("run", "java.util.Map<java.lang.String, java.lang.Object>,int[]").parameters())
                .containsExactly("java.util.Map", "int[]");
    }

    @Test
    void dropsNestedTypeArguments() {
        assertThat(MethodSignatures.erase("java.util.List<java.util.Map<java.lang.String, java.lang.Object>>"))
                .isEqualTo("java.util.List");
    }

    @Test
    void keepsTheInnerTypeOfAParameterizedOuterType() {
        assertThat(MethodSignatures.erase("java.util.Map<K, V>.Entry<X>")).isEqualTo("java.util.Map.Entry");
        assertThat(MethodSignatures.erase("a.Outer<java.util.List<T>>.Inner[]")).isEqualTo("a.Outer.Inner[]");
    }

    @Test
    void erasesUnboundedTypeVariableToObject() {
        assertThat(MethodSignatures.erase("Generic{R}")).isEqualTo("java.lang.Object");
    }

    @Test
    void erasesBoundedTypeVariableToItsFirstBound() {
        assertThat(MethodSignatures.erase("Generic{T extends org.x.Y}")).isEqualTo("org.x.Y");
    }

    @Test
    void keepsArraySuffixOfTypeVariables() {
        assertThat(MethodSignatures.erase("Generic{T extends org.x.Y}[][]")).isEqualTo("org.x.Y[][]");
        assertThat(MethodSignatures.erase("Generic{T}[]")).isEqualTo("java.lang.Object[]");
    }

    @Test
    void erasesRecursiveBoundWithoutItsTypeArguments() {
        assertThat(parse("run", "Generic{C extends Comparable<Generic{? super Generic{C}}>}[],int").parameters())
                .containsExactly("Comparable[]", "int");
    }

    @Test
    void treatsVarargsAsArray() {
        assertThat(MethodSignatures.erase("java.lang.String...")).isEqualTo("java.lang.String[]");
        assertThat(MethodSignatures.erase("java.util.List<java.lang.String>[]")).isEqualTo("java.util.List[]");
    }

    @Test
    void keepsUnresolvableTypeAsIs() {
        assertThat(parse("run", "{undefined}").parameters()).containsExactly("{undefined}");
    }

    @Test
    void convertsDescriptorToJavaTypes() {
        assertThat(MethodSignatures.descriptorParameters("(Ljava/lang/String;[IZ[[Ljava/util/Map;)V"))
                .containsExactly("java.lang.String", "int[]", "boolean", "java.util.Map[][]");
        assertThat(MethodSignatures.descriptorParameters("()V")).isEqualTo(Collections.emptyList());
    }

    @Test
    void convertsEveryPrimitive() {
        assertThat(MethodSignatures.descriptorParameters("(ZBCSIJFD)V"))
                .isEqualTo(Arrays.asList("boolean", "byte", "char", "short", "int", "long", "float", "double"));
    }

    @Test
    void convertsNestedClassDescriptor() {
        assertThat(MethodSignatures.descriptorParameters("(La/Foo$Inner;)V")).containsExactly("a.Foo$Inner");
    }
}
