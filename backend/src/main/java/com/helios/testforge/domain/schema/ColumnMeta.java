package com.helios.testforge.domain.schema;

import java.util.List;
import java.util.Optional;

/**
 * One column as reported by PostgreSQL's catalogs, carrying everything needed
 * to both re-emit the column as DDL and generate a plausible value for it.
 *
 * @param name                 column name, exactly as stored in the catalog
 * @param position             1-based ordinal position within the table
 * @param dataType             {@code information_schema.columns.data_type}, e.g. {@code character varying}
 * @param udtName              underlying type name, e.g. {@code varchar}, {@code int4}, {@code _text} for arrays
 * @param formattedType        {@code format_type()} output, e.g. {@code character varying(120)} — used verbatim in DDL
 * @param nullable             whether the column accepts NULL
 * @param maxLength            character maximum length, when the type is length-bounded
 * @param numericPrecision     total digits, for numeric/decimal types
 * @param numericScale         digits after the decimal point, for numeric/decimal types
 * @param defaultExpression    column DEFAULT expression, verbatim, when present
 * @param identity             true when declared GENERATED ... AS IDENTITY
 * @param identityGeneration   {@code ALWAYS} or {@code BY DEFAULT}, when {@code identity}
 * @param generated            true when this is a GENERATED ALWAYS AS (...) STORED column
 * @param generationExpression the STORED generation expression, when {@code generated}
 * @param serial               true when backed by a sequence via a {@code nextval(...)} default
 * @param enumLabels           labels of the backing enum type, when the column is an enum
 * @param arrayElementType     element type name, when the column is an array
 * @param comment              column comment, when one is set
 * @param dataClass            semantic classification driving generation and masking
 */
public record ColumnMeta(
        String name,
        int position,
        String dataType,
        String udtName,
        String formattedType,
        boolean nullable,
        Integer maxLength,
        Integer numericPrecision,
        Integer numericScale,
        String defaultExpression,
        boolean identity,
        String identityGeneration,
        boolean generated,
        String generationExpression,
        boolean serial,
        List<String> enumLabels,
        String arrayElementType,
        String comment,
        DataClass dataClass) {

    public ColumnMeta {
        enumLabels = enumLabels == null ? List.of() : List.copyOf(enumLabels);
        dataClass = dataClass == null ? DataClass.UNKNOWN : dataClass;
    }

    /**
     * Whether the database supplies this column's value on its own, meaning the
     * seeder must leave it out of the INSERT column list entirely.
     */
    public boolean databaseSupplied() {
        return generated || (identity && "ALWAYS".equalsIgnoreCase(identityGeneration));
    }

    /** Whether a value may be omitted, letting a default or NULL take over. */
    public boolean omissible() {
        return databaseSupplied() || serial || (nullable && defaultExpression == null);
    }

    public boolean isEnum() {
        return !enumLabels.isEmpty();
    }

    public boolean isArray() {
        return arrayElementType != null;
    }

    public Optional<Integer> maxLengthOpt() {
        return Optional.ofNullable(maxLength);
    }

    /** A short human-readable rendering used in the console's schema browser. */
    public String describe() {
        StringBuilder sb = new StringBuilder(name).append(' ').append(formattedType);
        if (!nullable) {
            sb.append(" NOT NULL");
        }
        if (identity) {
            sb.append(" IDENTITY");
        } else if (serial) {
            sb.append(" SERIAL");
        }
        return sb.toString();
    }
}
