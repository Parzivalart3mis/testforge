package com.helios.testforge.domain.schema;

/**
 * Semantic classification of a column, inferred from its name and type during
 * introspection.
 *
 * <p>This drives two different subsystems and is deliberately shared between
 * them: the generator picks a realistic value corpus from it, and the masking
 * policy decides from {@link #sensitive()} whether a real value flowing through
 * the platform has to be replaced. Keeping one vocabulary means a column
 * classified as {@code EMAIL} is generated as an email <em>and</em> masked as
 * one, rather than the two subsystems disagreeing.
 */
public enum DataClass {

    GIVEN_NAME(true),
    FAMILY_NAME(true),
    FULL_NAME(true),
    USERNAME(true),
    EMAIL(true),
    PHONE(true),
    SSN(true),
    NATIONAL_ID(true),
    CREDIT_CARD(true),
    IBAN(true),
    DATE_OF_BIRTH(true),
    STREET_ADDRESS(true),
    CITY(true),
    REGION(true),
    POSTAL_CODE(true),
    COUNTRY(false),
    LATITUDE(true),
    LONGITUDE(true),
    IP_ADDRESS(true),
    MAC_ADDRESS(true),
    USER_AGENT(false),
    URL(false),
    PASSWORD_HASH(true),
    API_TOKEN(true),
    COMPANY(false),
    JOB_TITLE(false),
    DEPARTMENT(false),
    PRODUCT_NAME(false),
    CURRENCY_CODE(false),
    MONETARY_AMOUNT(false),
    QUANTITY(false),
    PERCENTAGE(false),
    ENUM_LABEL(false),
    STATUS_FLAG(false),
    BOOLEAN_FLAG(false),
    CREATED_TIMESTAMP(false),
    UPDATED_TIMESTAMP(false),
    TIMESTAMP(false),
    DATE(false),
    DURATION(false),
    SLUG(false),
    TITLE(false),
    FREE_TEXT(false),
    JSON_DOCUMENT(false),
    BINARY_BLOB(false),
    SURROGATE_KEY(false),
    FOREIGN_KEY(false),
    UNKNOWN(false);

    private final boolean sensitive;

    DataClass(boolean sensitive) {
        this.sensitive = sensitive;
    }

    /**
     * Whether a value of this class is personally identifying or otherwise
     * regulated, and therefore must never leave a production system unmasked.
     */
    public boolean sensitive() {
        return sensitive;
    }
}
