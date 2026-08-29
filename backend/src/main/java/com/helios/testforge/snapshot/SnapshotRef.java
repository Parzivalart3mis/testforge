package com.helios.testforge.snapshot;

import java.time.Instant;
import java.util.UUID;

/**
 * A written snapshot bundle.
 *
 * @param datasetId  the dataset it captures
 * @param uri        where it lives, e.g. an s3:// or file:// URI
 * @param byteSize   the bundle's size on disk
 * @param checksum   SHA-256 of the bundle, so a download can be verified
 * @param rowCount   rows captured across every table
 * @param tableCount tables captured
 * @param createdAt  when it was written
 */
public record SnapshotRef(
        UUID datasetId,
        String uri,
        long byteSize,
        String checksum,
        long rowCount,
        int tableCount,
        Instant createdAt) {
}
