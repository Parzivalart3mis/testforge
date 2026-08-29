package com.helios.testforge.introspect;

import com.helios.testforge.domain.schema.DataClass;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Infers what a column <em>means</em> from its name and type.
 *
 * <p>Schemas do not declare that {@code contact_email} holds an email address,
 * but every downstream decision depends on knowing it: whether to generate a
 * realistic address, and whether the value is regulated and must be masked.
 * This classifier is the one place that guess is made, so generation and
 * masking can never diverge on it.
 *
 * <p>Matching is name-first and most-specific-first — {@code date_of_birth}
 * must beat the generic {@code *_date} rule — then falls back to the SQL type.
 * A wrong guess is not silent: the plan lists every classified column and the
 * console lets a requester override any of them before seeding.
 */
public final class DataClassifier {

    /** Name patterns in priority order. The first whose predicate matches wins. */
    private static final List<NameRule> NAME_RULES = List.of(
            // Identity and contact
            rule(DataClass.EMAIL, "email", "e_mail", "email_address", "mail"),
            rule(DataClass.PHONE, "phone", "phone_number", "mobile", "telephone", "fax", "msisdn"),
            rule(DataClass.SSN, "ssn", "social_security", "social_security_number"),
            rule(DataClass.NATIONAL_ID, "national_id", "tax_id", "tin", "nino", "passport", "passport_number"),
            rule(DataClass.CREDIT_CARD, "credit_card", "card_number", "cc_number", "pan"),
            rule(DataClass.IBAN, "iban", "bank_account", "account_number_iban"),
            rule(DataClass.DATE_OF_BIRTH, "date_of_birth", "birth_date", "birthdate", "dob", "born_on"),

            // People
            rule(DataClass.GIVEN_NAME, "first_name", "firstname", "given_name", "forename"),
            rule(DataClass.FAMILY_NAME, "last_name", "lastname", "surname", "family_name"),
            rule(DataClass.FULL_NAME, "full_name", "fullname", "display_name", "contact_name",
                    "customer_name", "person_name", "recipient_name"),
            rule(DataClass.USERNAME, "username", "user_name", "login", "handle", "screen_name"),
            rule(DataClass.PASSWORD_HASH, "password", "password_hash", "passwd", "pwd", "secret", "salt"),
            rule(DataClass.API_TOKEN, "api_key", "api_token", "access_token", "refresh_token",
                    "token", "session_token", "auth_token"),

            // Places
            rule(DataClass.STREET_ADDRESS, "address", "street", "address_line1", "address_line_1",
                    "address1", "street_address", "line1"),
            rule(DataClass.CITY, "city", "town", "locality"),
            rule(DataClass.REGION, "state", "province", "region", "county"),
            rule(DataClass.POSTAL_CODE, "zip", "zipcode", "zip_code", "postal_code", "postcode"),
            rule(DataClass.COUNTRY, "country", "country_code", "nation"),
            rule(DataClass.LATITUDE, "latitude", "lat"),
            rule(DataClass.LONGITUDE, "longitude", "lng", "lon"),

            // Network
            rule(DataClass.IP_ADDRESS, "ip", "ip_address", "client_ip", "remote_addr", "ipv4", "ipv6"),
            rule(DataClass.MAC_ADDRESS, "mac", "mac_address"),
            rule(DataClass.USER_AGENT, "user_agent", "useragent"),
            rule(DataClass.URL, "url", "uri", "website", "link", "href", "callback_url", "webhook_url"),

            // Commerce and org
            rule(DataClass.COMPANY, "company", "company_name", "organization", "organisation", "employer", "vendor"),
            rule(DataClass.JOB_TITLE, "job_title", "title_role", "position", "role_title"),
            rule(DataClass.DEPARTMENT, "department", "dept", "division", "team"),
            rule(DataClass.PRODUCT_NAME, "product_name", "sku_name", "item_name"),
            rule(DataClass.CURRENCY_CODE, "currency", "currency_code", "ccy"),
            rule(DataClass.MONETARY_AMOUNT, "price", "amount", "total", "subtotal", "cost", "balance",
                    "fee", "revenue", "salary", "unit_price", "grand_total", "tax_amount", "discount"),
            rule(DataClass.QUANTITY, "quantity", "qty", "count", "stock", "units", "inventory"),
            rule(DataClass.PERCENTAGE, "percent", "percentage", "rate", "ratio", "discount_pct"),

            // Time
            rule(DataClass.CREATED_TIMESTAMP, "created_at", "created_on", "created", "inserted_at", "date_created"),
            rule(DataClass.UPDATED_TIMESTAMP, "updated_at", "updated_on", "modified_at", "last_modified", "changed_at"),
            rule(DataClass.DURATION, "duration", "duration_ms", "elapsed", "elapsed_ms", "timeout", "ttl"),

            // Text
            rule(DataClass.SLUG, "slug", "permalink", "url_key"),
            rule(DataClass.TITLE, "title", "subject", "headline", "label", "caption"),
            rule(DataClass.FREE_TEXT, "description", "notes", "note", "comment", "comments",
                    "body", "content", "message", "summary", "bio", "remarks"),
            rule(DataClass.STATUS_FLAG, "status", "state", "stage", "phase", "kind", "type", "category"));

    private DataClassifier() {
    }

    /**
     * Classifies a column.
     *
     * @param columnName    the column's name
     * @param udtName       the underlying PostgreSQL type name, e.g. {@code varchar}, {@code int4}
     * @param isEnum        whether the column's type is an enum
     * @param isPrimaryKey  whether the column is (part of) the primary key
     * @param isForeignKey  whether the column is (part of) a foreign key
     */
    public static DataClass classify(String columnName, String udtName, boolean isEnum,
                                     boolean isPrimaryKey, boolean isForeignKey) {
        String name = normalise(columnName);

        // Keys are structural, never semantic — a column named `owner_email_id`
        // that is a foreign key holds a surrogate reference, not an address.
        if (isForeignKey) {
            return DataClass.FOREIGN_KEY;
        }
        if (isPrimaryKey && isSurrogateKeyType(udtName)) {
            return DataClass.SURROGATE_KEY;
        }
        if (isEnum) {
            return DataClass.ENUM_LABEL;
        }

        for (NameRule rule : NAME_RULES) {
            if (rule.matches(name) && typeIsCompatible(rule.dataClass(), udtName)) {
                return rule.dataClass();
            }
        }
        return fromType(udtName, name);
    }

    /**
     * Whether a name-derived guess is plausible for the column's actual type.
     * Without this a {@code boolean is_email_verified} column would classify as
     * an email address and be masked into a value the column cannot even hold.
     */
    private static boolean typeIsCompatible(DataClass dataClass, String udtName) {
        String type = udtName == null ? "" : udtName.toLowerCase(Locale.ROOT);
        boolean textual = type.startsWith("varchar") || type.equals("text") || type.equals("bpchar")
                || type.equals("char") || type.equals("citext") || type.equals("name")
                || type.equals("inet") || type.equals("cidr") || type.equals("macaddr")
                || type.equals("macaddr8") || type.equals("uuid");
        boolean numeric = type.equals("int2") || type.equals("int4") || type.equals("int8")
                || type.equals("numeric") || type.equals("float4") || type.equals("float8")
                || type.equals("money");
        boolean temporal = type.startsWith("timestamp") || type.equals("date") || type.startsWith("time")
                || type.equals("interval");

        return switch (dataClass) {
            case EMAIL, PHONE, SSN, NATIONAL_ID, CREDIT_CARD, IBAN, GIVEN_NAME, FAMILY_NAME,
                 FULL_NAME, USERNAME, PASSWORD_HASH, API_TOKEN, STREET_ADDRESS, CITY, REGION,
                 POSTAL_CODE, COUNTRY, IP_ADDRESS, MAC_ADDRESS, USER_AGENT, URL, COMPANY,
                 JOB_TITLE, DEPARTMENT, PRODUCT_NAME, CURRENCY_CODE, SLUG, TITLE, FREE_TEXT,
                 STATUS_FLAG -> textual;
            case MONETARY_AMOUNT, QUANTITY, PERCENTAGE, LATITUDE, LONGITUDE -> numeric;
            case DATE_OF_BIRTH, CREATED_TIMESTAMP, UPDATED_TIMESTAMP -> temporal;
            case DURATION -> numeric || temporal;
            default -> true;
        };
    }

    /** Fallback classification driven purely by the SQL type. */
    private static DataClass fromType(String udtName, String normalisedName) {
        String type = udtName == null ? "" : udtName.toLowerCase(Locale.ROOT);
        return switch (type) {
            case "bool" -> DataClass.BOOLEAN_FLAG;
            case "timestamp", "timestamptz" -> DataClass.TIMESTAMP;
            case "date" -> DataClass.DATE;
            case "time", "timetz", "interval" -> DataClass.DURATION;
            case "json", "jsonb" -> DataClass.JSON_DOCUMENT;
            case "bytea" -> DataClass.BINARY_BLOB;
            case "uuid" -> DataClass.SURROGATE_KEY;
            case "inet", "cidr" -> DataClass.IP_ADDRESS;
            case "macaddr", "macaddr8" -> DataClass.MAC_ADDRESS;
            case "int2", "int4", "int8" -> normalisedName.endsWith("_id") || normalisedName.equals("id")
                    ? DataClass.SURROGATE_KEY
                    : DataClass.QUANTITY;
            case "numeric", "float4", "float8", "money" -> DataClass.MONETARY_AMOUNT;
            case "text" -> DataClass.FREE_TEXT;
            default -> type.startsWith("varchar") || type.equals("bpchar") || type.equals("char")
                    ? DataClass.TITLE
                    : DataClass.UNKNOWN;
        };
    }

    private static boolean isSurrogateKeyType(String udtName) {
        String type = udtName == null ? "" : udtName.toLowerCase(Locale.ROOT);
        return type.equals("int2") || type.equals("int4") || type.equals("int8") || type.equals("uuid");
    }

    /** Lower-cases and collapses separators so {@code FirstName} and {@code first-name} both match. */
    static String normalise(String columnName) {
        String lower = columnName.toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        StringBuilder sb = new StringBuilder(lower.length());
        boolean lastUnderscore = false;
        for (char c : lower.toCharArray()) {
            if (c == '_') {
                if (!lastUnderscore && !sb.isEmpty()) {
                    sb.append('_');
                }
                lastUnderscore = true;
            } else {
                sb.append(c);
                lastUnderscore = false;
            }
        }
        int end = sb.length();
        while (end > 0 && sb.charAt(end - 1) == '_') {
            end--;
        }
        return sb.substring(0, end);
    }

    /** Every rule, exposed so the console can explain why a column was classified as it was. */
    public static Map<DataClass, List<String>> ruleCatalogue() {
        Map<DataClass, List<String>> catalogue = new LinkedHashMap<>();
        for (NameRule rule : NAME_RULES) {
            catalogue.put(rule.dataClass(), rule.tokens());
        }
        return catalogue;
    }

    private static NameRule rule(DataClass dataClass, String... tokens) {
        return new NameRule(dataClass, List.of(tokens));
    }

    /**
     * A name-matching rule. A column matches when its normalised name equals a
     * token, or ends with {@code _token} — so {@code billing_email} matches
     * {@code email} but {@code emailer_config} does not.
     */
    private record NameRule(DataClass dataClass, List<String> tokens) {

        boolean matches(String normalisedName) {
            for (String token : tokens) {
                if (normalisedName.equals(token) || normalisedName.endsWith("_" + token)) {
                    return true;
                }
            }
            return false;
        }
    }
}
