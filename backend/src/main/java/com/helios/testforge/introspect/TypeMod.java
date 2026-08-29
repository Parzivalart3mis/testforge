package com.helios.testforge.introspect;

import java.util.Locale;

/**
 * Decodes PostgreSQL's packed {@code atttypmod} into the length, precision and
 * scale the generator needs.
 *
 * <p>Reading it directly from {@code pg_attribute} rather than going through
 * {@code information_schema} keeps introspection to one catalog query per
 * concern and avoids the information_schema views' privilege filtering, which
 * silently hides columns rather than failing.
 */
public final class TypeMod {

    /** {@code atttypmod} is -1 when the type carries no modifier. */
    public static final int NONE = -1;

    private TypeMod() {
    }

    /**
     * Declared character length for length-bounded string types, or null.
     * PostgreSQL stores {@code varchar(n)} as {@code n + VARHDRSZ}.
     */
    public static Integer characterMaxLength(String udtName, int typmod) {
        if (typmod < 0) {
            return null;
        }
        return switch (base(udtName)) {
            case "varchar", "bpchar", "char" -> typmod - 4;
            case "bit", "varbit" -> typmod;
            default -> null;
        };
    }

    /** Total significant digits for {@code numeric(p, s)}, or null. */
    public static Integer numericPrecision(String udtName, int typmod) {
        String base = base(udtName);
        if (base.equals("numeric") || base.equals("decimal")) {
            return typmod < 0 ? null : ((typmod - 4) >> 16) & 0xFFFF;
        }
        return switch (base) {
            case "int2" -> 16;
            case "int4" -> 32;
            case "int8" -> 64;
            case "float4" -> 24;
            case "float8" -> 53;
            default -> null;
        };
    }

    /** Digits after the decimal point for {@code numeric(p, s)}, or null. */
    public static Integer numericScale(String udtName, int typmod) {
        String base = base(udtName);
        if (base.equals("numeric") || base.equals("decimal")) {
            return typmod < 0 ? null : (typmod - 4) & 0xFFFF;
        }
        return switch (base) {
            case "int2", "int4", "int8" -> 0;
            case "money" -> 2;
            default -> null;
        };
    }

    /** Fractional-second precision for temporal types, or null. */
    public static Integer temporalPrecision(String udtName, int typmod) {
        return switch (base(udtName)) {
            case "timestamp", "timestamptz", "time", "timetz", "interval" -> typmod < 0 ? null : typmod & 0xFFFF;
            default -> null;
        };
    }

    /** Strips a leading underscore, which is how PostgreSQL names array types. */
    static String base(String udtName) {
        if (udtName == null) {
            return "";
        }
        String lower = udtName.toLowerCase(Locale.ROOT);
        return lower.startsWith("_") ? lower.substring(1) : lower;
    }
}
