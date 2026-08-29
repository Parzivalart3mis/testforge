package com.helios.testforge.mask;

/**
 * The scope a masked value is derived within.
 *
 * <p>The three fields decide exactly how far a masked value's stability
 * reaches, which is the whole design question in masking:
 *
 * <ul>
 *   <li>{@code datasetSalt} scopes stability to one dataset. Two datasets from
 *       the same source produce different masked values, so they cannot be
 *       cross-referenced.</li>
 *   <li>{@code column} is normally left null, which is what makes joins survive:
 *       {@code users.email} and {@code audit_log.actor_email} mask to the same
 *       value because neither derivation mentions its column. Setting it
 *       deliberately breaks that linkage for a column that should not be
 *       joinable.</li>
 *   <li>{@code rowKey} groups values that must move together. A date shift
 *       derived from the row key moves every date in that row by the same
 *       offset, so intervals - signup to first order, order to shipment -
 *       survive masking intact.</li>
 * </ul>
 *
 * @param datasetSalt per-dataset salt, normally the dataset id
 * @param column      qualified column name, or null to keep values joinable across columns
 * @param rowKey      identity of the row, so correlated values shift together
 */
public record MaskContext(String datasetSalt, String column, String rowKey) {

    public static MaskContext forDataset(String datasetSalt) {
        return new MaskContext(datasetSalt, null, null);
    }

    public MaskContext withRow(String key) {
        return new MaskContext(datasetSalt, column, key);
    }

    public MaskContext withColumn(String qualifiedColumn) {
        return new MaskContext(datasetSalt, qualifiedColumn, rowKey);
    }

    /** The derivation parts for a value, in a fixed order. */
    String[] parts(String value) {
        return new String[]{datasetSalt, column == null ? "" : column, value};
    }

    /** The derivation parts for a row-scoped quantity, which must not depend on the value. */
    String[] rowParts(String purpose) {
        return new String[]{datasetSalt, purpose, rowKey == null ? "" : rowKey};
    }
}
