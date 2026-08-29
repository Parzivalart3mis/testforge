package com.helios.testforge.snapshot;

import java.util.Optional;
import java.util.UUID;

/**
 * Where snapshot bundles are kept.
 *
 * <p>S3 in deployed environments, the local filesystem otherwise. Both are
 * addressed by URI so a stored reference means the same thing whichever backend
 * wrote it, and a dataset exported locally can be described the same way as one
 * exported to a bucket.
 */
public interface SnapshotStore {

    /**
     * Writes a bundle.
     *
     * @param datasetId the dataset being captured
     * @param filename  the bundle's file name within the dataset's prefix
     * @param content   the bundle bytes
     * @return a reference including the resulting URI and checksum
     */
    SnapshotRef write(UUID datasetId, String filename, byte[] content);

    /** Reads a bundle back, for the console's download endpoint. */
    Optional<byte[]> read(String uri);

    /** Removes a bundle. */
    boolean delete(String uri);

    /** Which backend is in use, surfaced on the health endpoint. */
    String backendName();
}
