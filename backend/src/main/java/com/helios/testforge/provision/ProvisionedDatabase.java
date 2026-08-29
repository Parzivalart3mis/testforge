package com.helios.testforge.provision;

import java.time.Instant;
import java.util.UUID;

/**
 * A freshly created ephemeral database and the credentials for it.
 *
 * <p>The password lives in this record and nowhere else durable: the control
 * plane stores only a digest, and the plaintext reaches the requester exactly
 * once, in the response that issues the lease.
 *
 * @param datasetId    the dataset this database was created for
 * @param databaseName the physical database name on the cluster
 * @param roleName     the login role created alongside it, owner of the database
 * @param password     the role's generated password
 * @param jdbcUrl      the URL a client connects with, using the cluster's client-facing host
 * @param createdAt    when it was created
 */
public record ProvisionedDatabase(
        UUID datasetId,
        String databaseName,
        String roleName,
        String password,
        String jdbcUrl,
        Instant createdAt) {

    /**
     * The connection string handed to the requester, credentials included, or
     * null once the record has been redacted. Returning null rather than a
     * string containing the literal "null" matters: a redacted record must not
     * produce something that looks like a usable connection string.
     */
    public String connectionString() {
        if (password == null) {
            return null;
        }
        String separator = jdbcUrl.contains("?") ? "&" : "?";
        return jdbcUrl + separator + "user=" + roleName + "&password=" + password;
    }

    /** A copy with the password cleared, for anything that is logged or persisted. */
    public ProvisionedDatabase redacted() {
        return new ProvisionedDatabase(datasetId, databaseName, roleName, null, jdbcUrl, createdAt);
    }
}
