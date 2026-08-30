package com.helios.testforge.util;

import java.util.regex.Pattern;

/** PostgreSQL identifier and literal quoting. */
public final class Pg {

    /** Unquoted identifiers PostgreSQL folds to lower case and accepts verbatim. */
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[a-z_][a-z0-9_$]*");

    private Pg() {
    }

    /**
     * Quotes an identifier for interpolation into SQL. Always quotes rather than
     * only quoting when necessary: identifiers reaching here come from a target
     * database's catalog, which may legitimately contain mixed case, spaces or
     * reserved words, and an always-quoted identifier is correct in every case.
     */
    public static String quoteIdentifier(String identifier) {
        if (identifier == null) {
            throw new IllegalArgumentException("identifier must not be null");
        }
        if (identifier.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("identifier must not contain a null byte: " + identifier);
        }
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }

    /** True when the identifier would round-trip unquoted, used only for prettier generated DDL comments. */
    public static boolean isSimpleIdentifier(String identifier) {
        return identifier != null && SAFE_IDENTIFIER.matcher(identifier).matches();
    }

    /**
     * Quotes a string literal. The seeder binds every value as a JDBC parameter,
     * so this exists only for the paths that must emit standalone SQL text —
     * generated DDL defaults and snapshot bundles.
     */
    public static String quoteLiteral(String value) {
        if (value == null) {
            return "NULL";
        }
        String escaped = value.replace("'", "''");
        // E'' syntax is required to escape backslashes when standard_conforming_strings is off.
        return escaped.indexOf('\\') >= 0
                ? "E'" + escaped.replace("\\", "\\\\") + "'"
                : "'" + escaped + "'";
    }
}
