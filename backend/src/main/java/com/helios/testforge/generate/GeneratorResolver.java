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
        ValueGenerator generator = resolveCore(column, unique);

        // Unique columns never get NULLs injected: a second NULL would collide
        // under a UNIQUE constraint in most engines' interpretation, and it also
        // wastes one of the distinct values the column was asked to provide.
        if (column.nullable() && !unique) {
            return generator.nullableAt(NULL_RATE_PERCENT);
        }
        return generator;
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
                    unique ? Generators.slug(true, length) : Generators.genericText(length);
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
