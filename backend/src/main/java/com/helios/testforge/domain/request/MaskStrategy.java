package com.helios.testforge.domain.request;

/**
 * How a column's value is transformed on its way into the seeded database.
 *
 * <p>Every strategy other than {@link #PRESERVE} and {@link #NULLIFY} is
 * <em>deterministic</em>: the same input, dataset salt and platform key always
 * produce the same output. That property is what keeps a masked dataset usable
 * — a customer email masked in {@code users} lands on the identical masked
 * value in {@code audit_log}, so joins and manual spot-checks still line up.
 */
public enum MaskStrategy {

    /** Leave the value untouched. Only legal for columns not classified sensitive. */
    PRESERVE,

    /** Replace with a fixed redaction token. */
    REDACT,

    /** Replace with a keyed HMAC digest, rendered as lowercase hex. */
    HASH,

    /** Keep a prefix and/or suffix, replace the middle. */
    PARTIAL,

    /** Replace the local part, keep or remap the domain, keep it a valid address. */
    EMAIL,

    /** Replace the digits, keep the punctuation and length. */
    PHONE,

    /** Substitute a different name drawn deterministically from a name corpus. */
    NAME,

    /** Replace with a syntactically valid but non-issuable SSN. */
    SSN,

    /** Replace with a Luhn-valid test card number in the same brand range. */
    CREDIT_CARD,

    /** Replace with a checksum-valid IBAN in the same country. */
    IBAN,

    /** Shift a date or timestamp by a deterministic offset, preserving intervals within a row. */
    DATE_SHIFT,

    /** Perturb a number by a bounded deterministic factor, preserving sign and magnitude. */
    NUMERIC_JITTER,

    /** Replace with a stable opaque token, so the value can still be used as a join key. */
    TOKENIZE,

    /** Replace with NULL. Rejected at plan time for NOT NULL columns. */
    NULLIFY
}
