package com.helios.testforge.introspect;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TypeModTest {

    @Test
    void decodesCharacterLengthsWhichPostgresStoresWithAFourByteHeader() {
        assertThat(TypeMod.characterMaxLength("varchar", 124)).isEqualTo(120);
        assertThat(TypeMod.characterMaxLength("bpchar", 14)).isEqualTo(10);
        assertThat(TypeMod.characterMaxLength("varchar", TypeMod.NONE)).isNull();
        assertThat(TypeMod.characterMaxLength("text", 100)).isNull();
    }

    @Test
    void decodesNumericPrecisionAndScaleFromThePackedModifier() {
        // numeric(12, 2) packs as ((12 << 16) | 2) + 4
        int typmod = ((12 << 16) | 2) + 4;
        assertThat(TypeMod.numericPrecision("numeric", typmod)).isEqualTo(12);
        assertThat(TypeMod.numericScale("numeric", typmod)).isEqualTo(2);
    }

    @Test
    void reportsImplicitPrecisionForFixedWidthIntegerTypes() {
        assertThat(TypeMod.numericPrecision("int4", TypeMod.NONE)).isEqualTo(32);
        assertThat(TypeMod.numericScale("int8", TypeMod.NONE)).isZero();
    }

    @Test
    void stripsTheArrayPrefixPostgresUsesForElementTypes() {
        assertThat(TypeMod.base("_text")).isEqualTo("text");
        assertThat(TypeMod.base("VARCHAR")).isEqualTo("varchar");
        assertThat(TypeMod.base(null)).isEmpty();
    }

    @Test
    void decodesTemporalPrecision() {
        assertThat(TypeMod.temporalPrecision("timestamptz", 3)).isEqualTo(3);
        assertThat(TypeMod.temporalPrecision("timestamptz", TypeMod.NONE)).isNull();
        assertThat(TypeMod.temporalPrecision("int4", 3)).isNull();
    }
}
