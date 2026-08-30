package com.helios.testforge.generate;

import com.helios.testforge.domain.schema.ColumnMeta;
import com.helios.testforge.domain.schema.TableMeta;
import com.helios.testforge.introspect.TypeMod;
import com.helios.testforge.mask.Corpora;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * Chooses the generator for a column.
 *
 * <p>Resolution runs semantics first, type second. The inferred {@link
 * com.helios.testforge.domain.schema.DataClass} decides what a column
 * <em>means</em> — an email, a price, a created timestamp — and only when that
 * is unknown does the SQL type decide what it can <em>hold</em>. Doing it in the
 * other order produces the classic useless test dataset where every varchar is
 * the same random string.
 *
 * <p>A semantic choice is only taken when the column's type can hold the result;
 * the classifier already applies that check, so anything reaching here with a
 * specific class is safe to generate for.
 */
@Component
public class GeneratorResolver {

    /** Share of rows given NULL in a nullable column, so null paths get exercised. */
    private static final int NULL_RATE_PERCENT = 12;

    /**
     * Resolves the generator for one column.
     *
     * @param table  the owning table, for uniqueness lookups
     * @param column the column
     * @return a generator, never null
     */
    public ValueGenerator resolve(TableMeta table, ColumnMeta column) {
        boolean unique = table.isUniqueColumn(column.name());

        // A CHECK constraint overrides the semantic choice. A `rating smallint`
        // reads as a quantity and would be generated in the thousands; the
        // constraint saying it must be 1 to 5 is the more specific fact, and it
        // is the one the database will enforce.
        ValueGenerator generator = constrained(table, column)
                .orElseGet(() -> resolveCore(column, unique));

        // Unique columns never get NULLs injected: a second NULL would collide
        // under a UNIQUE constraint in most engines' interpretation, and it also
        // wastes one of the distinct values the column was asked to provide.
        if (column.nullable() && !unique) {
            return generator.nullableAt(NULL_RATE_PERCENT);
        }
        return generator;
    }

    /**
     * A generator derived from the column's check constraints, when they say
     * anything a generator can act on.
     */
    private java.util.Optional<ValueGenerator> constrained(TableMeta table, ColumnMeta column) {
        if (table.checks().isEmpty()) {
            return java.util.Optional.empty();
        }
        CheckBounds.Bounds bounds = CheckBounds.interpret(table.checks()).get(column.name());
        if (bounds == null) {
            return java.util.Optional.empty();
        }

        if (bounds.hasAllowedValues() && isTextual(column)) {
            return java.util.Optional.of(Generators.fromCorpus(bounds.allowed(), column.maxLength()));
        }
        if (!bounds.hasRange()) {
            return java.util.Optional.empty();
        }

        return switch (TypeMod.base(column.udtName())) {
            case "int2" -> java.util.Optional.of(Generators.integerInRange(
                    bounds.minLong(1), bounds.maxLong(30_000), column.udtName()));
            case "int4" -> java.util.Optional.of(Generators.integerInRange(
                    bounds.minLong(1), bounds.maxLong(1_000_000), column.udtName()));
            case "int8" -> java.util.Optional.of(Generators.integerInRange(
                    bounds.minLong(1), bounds.maxLong(1_000_000_000L), column.udtName()));
            case "numeric", "decimal", "float4", "float8", "money" -> java.util.Optional.of(
                    Generators.decimalInRange(
                            bounds.minDecimal(java.math.BigDecimal.ZERO),
                            bounds.maxDecimal(java.math.BigDecimal.valueOf(100_000)),
                            column.numericScale()));
            default -> java.util.Optional.empty();
        };
    }

    /** A short human-readable label for the chosen generator, shown in the plan. */
    public String describe(TableMeta table, ColumnMeta column) {
        if (column.isEnum()) {
            return "enum(" + column.enumLabels().size() + " labels)";
        }
        return switch (column.dataClass()) {
            case UNKNOWN -> "type:" + TypeMod.base(column.udtName());
            default -> column.dataClass().name().toLowerCase(Locale.ROOT);
        };
    }

    private ValueGenerator resolveCore(ColumnMeta column, boolean unique) {
        if (column.isEnum()) {
            return Generators.enumLabel(column.enumLabels());
        }
        if (column.isArray()) {
            return arrayGenerator(column);
        }
        // A UNIQUE textual column overrides semantics entirely, except for email,
        // which has a uniqueness-aware generator that still yields a valid
        // address. Every other textual generator draws from a fixed corpus or
        // truncates to fit, and either will eventually collide - which aborts
        // the whole seed. Enumerating the safe classes was tried and was wrong:
        // a char(2) iso_code fell through to the generic title generator and
        // collided on the third row. Distinctness wins over realism here.
        if (unique && isTextual(column)) {
            return column.dataClass() == com.helios.testforge.domain.schema.DataClass.EMAIL
                    ? Generators.email(true, column.maxLength())
                    : Generators.uniqueText(column.maxLength());
        }

        Integer length = column.maxLength();
        Integer precision = column.numericPrecision();
        Integer scale = column.numericScale();

        ValueGenerator semantic = switch (column.dataClass()) {
            case EMAIL -> Generators.email(unique, length);
            case GIVEN_NAME -> Generators.givenName(length);
            case FAMILY_NAME -> Generators.familyName(length);
            case FULL_NAME -> Generators.fullName(length);
            case USERNAME -> Generators.username(unique, length);
            case PHONE -> Generators.phone(length);
            case SSN, NATIONAL_ID -> Generators.ssn();
            case CREDIT_CARD -> Generators.creditCard();
            case IBAN -> Generators.iban();
            case DATE_OF_BIRTH -> Generators.dateOfBirth();
            case STREET_ADDRESS -> Generators.streetAddress(length);
            case CITY -> Generators.fromCorpus(Corpora.CITIES, length);
            case REGION -> Generators.fromCorpus(Corpora.REGIONS, length);
            case POSTAL_CODE -> Generators.postalCode(length);
            case COUNTRY -> Generators.fromCorpus(
                    length != null && length <= 3 ? Corpora.COUNTRY_CODES : Corpora.COUNTRIES, length);
            case LATITUDE -> Generators.latitude();
            case LONGITUDE -> Generators.longitude();
            case IP_ADDRESS -> Generators.ipv4();
            case MAC_ADDRESS -> Generators.macAddress();
            case USER_AGENT -> Generators.genericText(length);
            case URL -> Generators.url(length);
            case PASSWORD_HASH -> Generators.opaqueToken("$2b$12$", 53, length);
            case API_TOKEN -> Generators.opaqueToken("tfk_", 32, length);
            case COMPANY -> Generators.fromCorpus(Corpora.COMPANIES, length);
            case JOB_TITLE -> Generators.fromCorpus(Corpora.JOB_TITLES, length);
            case DEPARTMENT -> Generators.fromCorpus(Corpora.DEPARTMENTS, length);
            case PRODUCT_NAME -> Generators.fromCorpus(Corpora.PRODUCTS, length);
            case CURRENCY_CODE -> Generators.fromCorpus(Corpora.CURRENCIES, length);
            case MONETARY_AMOUNT -> Generators.money(precision, scale);
            case QUANTITY -> quantityFor(column);
            case PERCENTAGE -> Generators.percentage(scale);
            case STATUS_FLAG -> Generators.fromCorpus(Corpora.STATUSES, length);
            case BOOLEAN_FLAG -> Generators.flag(30);
            case CREATED_TIMESTAMP -> temporal(column, 365, 0);
            case UPDATED_TIMESTAMP -> temporal(column, 180, 0);
            case TIMESTAMP -> temporal(column, 365, 90);
            case DATE -> Generators.date(365, 90);
            case DURATION -> durationFor(column);
            case SLUG -> Generators.slug(unique, length);
            case TITLE -> Generators.title(length);
            case FREE_TEXT -> Generators.freeText(length);
            case JSON_DOCUMENT -> Generators.jsonDocument();
            case BINARY_BLOB -> Generators.bytes(64);
            case SURROGATE_KEY, FOREIGN_KEY, ENUM_LABEL, UNKNOWN -> null;
        };

        return semantic != null ? semantic : byType(column, unique);
    }

    /** Whether the column holds text, and so needs a text generator. */
    private static boolean isTextual(ColumnMeta column) {
        return switch (TypeMod.base(column.udtName())) {
            case "text", "varchar", "bpchar", "char", "citext", "name" -> true;
            default -> false;
        };
    }

    /** Falls back to the SQL type when the column's meaning is unknown. */
    private ValueGenerator byType(ColumnMeta column, boolean unique) {
        String type = TypeMod.base(column.udtName());
        Integer length = column.maxLength();

        return switch (type) {
            case "bool" -> Generators.bool();
            case "int2" -> Generators.smallInt(unique);
            case "int4" -> Generators.integer(unique);
            case "int8" -> Generators.bigInt(unique);
            case "numeric", "decimal" -> Generators.decimal(column.numericPrecision(), column.numericScale());
            case "float4" -> Generators.floatValue();
            case "float8" -> Generators.doubleValue();
            case "money" -> Generators.money(19, 2);
            case "uuid" -> Generators.uuid();
            case "date" -> Generators.date(365, 90);
            case "timestamp" -> Generators.timestamp(365, 90);
            case "timestamptz" -> Generators.timestampTz(365, 90);
            case "time", "timetz" -> Generators.time();
            case "interval" -> Generators.interval();
            case "json", "jsonb" -> Generators.jsonDocument();
            case "bytea" -> Generators.bytes(64);
            case "inet", "cidr" -> Generators.ipv4();
            case "macaddr", "macaddr8" -> Generators.macAddress();
            case "text", "varchar", "bpchar", "char", "citext", "name" ->
                    unique ? Generators.uniqueText(length) : Generators.genericText(length);
            default -> unsupported(column);
        };
    }

    /**
     * Types with no generator are only tolerable when the column accepts NULL.
     * A NOT NULL column of an unsupported type is a hard stop, reported by name
     * so the requester can exclude the table or add an override, rather than a
     * seeding failure two minutes into the run.
     */
    private ValueGenerator unsupported(ColumnMeta column) {
        if (column.nullable() || column.defaultExpression() != null) {
            return ValueGenerator.nullValue();
        }
        throw new UnsupportedColumnTypeException(column.name(), column.formattedType());
    }

    private ValueGenerator arrayGenerator(ColumnMeta column) {
        String element = TypeMod.base(column.arrayElementType());
        return switch (element) {
            case "int2", "int4", "int8" -> Generators.integerArray(0, 4);
            default -> Generators.textArray(0, 4);
        };
    }

    /** A quantity's shape depends on whether the column is an integer or a decimal. */
    private ValueGenerator quantityFor(ColumnMeta column) {
        String type = TypeMod.base(column.udtName());
        return switch (type) {
            case "numeric", "decimal" -> Generators.decimal(column.numericPrecision(), column.numericScale());
            case "float4" -> Generators.floatValue();
            case "float8" -> Generators.doubleValue();
            case "int8" -> Generators.bigInt(false);
            case "int2" -> Generators.smallInt(false);
            default -> Generators.quantity();
        };
    }

    /** A duration is an interval when the column is temporal, and milliseconds when it is numeric. */
    private ValueGenerator durationFor(ColumnMeta column) {
        String type = TypeMod.base(column.udtName());
        return switch (type) {
            case "interval" -> Generators.interval();
            case "time", "timetz" -> Generators.time();
            case "numeric", "decimal" -> Generators.decimal(column.numericPrecision(), column.numericScale());
            default -> Generators.durationMillis();
        };
    }

    /** Picks the temporal Java type matching the column, so the driver binds it without a cast. */
    private ValueGenerator temporal(ColumnMeta column, int daysBack, int daysForward) {
        return switch (TypeMod.base(column.udtName())) {
            case "date" -> Generators.date(daysBack, daysForward);
            case "timestamptz" -> Generators.timestampTz(daysBack, daysForward);
            case "time", "timetz" -> Generators.time();
            case "int8" -> Generators.bigInt(false);
            default -> Generators.timestamp(daysBack, daysForward);
        };
    }

    /** Raised when a NOT NULL column has a type TestForge cannot synthesise a value for. */
    public static class UnsupportedColumnTypeException extends RuntimeException {

        private final transient List<String> details;

        public UnsupportedColumnTypeException(String column, String type) {
            super("column '" + column + "' has type " + type
                    + ", which TestForge cannot generate values for, and it is NOT NULL. "
                    + "Exclude the table from the request, or add a masking rule supplying a fixed value.");
            this.details = List.of(column, type);
        }

        public List<String> details() {
            return details;
        }
    }
}
