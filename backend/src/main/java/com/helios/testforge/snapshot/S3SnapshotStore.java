package com.helios.testforge.snapshot;

import com.helios.testforge.config.TestForgeProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Snapshot bundles in S3.
 *
 * <p>Objects are written with SSE-KMS and a checksum, and keyed under the
 * dataset id so a bucket lifecycle rule can expire whole datasets by prefix
 * rather than needing to understand the contents.
 *
 * <p>A snapshot holds synthetic rows, but the schema it captures is a
 * production schema, so the bucket is treated as sensitive: encryption is
 * required at write time rather than left to a bucket default that could be
 * changed without this code noticing.
 */
@Component
@ConditionalOnProperty(name = "testforge.snapshots.backend", havingValue = "s3")
public class S3SnapshotStore implements SnapshotStore {

    private static final Logger log = LoggerFactory.getLogger(S3SnapshotStore.class);

    private final S3Client s3;
    private final String bucket;
    private final String prefix;

    public S3SnapshotStore(S3Client s3, TestForgeProperties properties) {
        this.s3 = s3;
        this.bucket = properties.snapshots().bucket();
        this.prefix = normalisePrefix(properties.snapshots().prefix());
        if (bucket == null || bucket.isBlank()) {
            throw new IllegalStateException(
                    "testforge.snapshots.backend is s3 but testforge.snapshots.bucket is not set");
        }
        log.info("Snapshots stored in s3://{}/{}", bucket, prefix);
    }

    @Override
    public SnapshotRef write(UUID datasetId, String filename, byte[] content) {
        String key = prefix + datasetId + "/" + FilesystemSnapshotStore.sanitise(filename);
        String checksum = FilesystemSnapshotStore.checksum(content);

        s3.putObject(PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .contentType("application/zip")
                        .serverSideEncryption(ServerSideEncryption.AWS_KMS)
                        .metadata(Map.of(
                                "dataset-id", datasetId.toString(),
                                "sha256", checksum))
                        .build(),
                RequestBody.fromBytes(content));

        log.info("Wrote snapshot s3://{}/{} ({} bytes)", bucket, key, content.length);
        return new SnapshotRef(datasetId, "s3://" + bucket + "/" + key, content.length,
                checksum, 0, 0, Instant.now());
    }

    @Override
    public Optional<byte[]> read(String uri) {
        String key = keyFrom(uri);
        if (key == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(s3.getObjectAsBytes(GetObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .build()).asByteArray());
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        }
    }

    @Override
    public boolean delete(String uri) {
        String key = keyFrom(uri);
        if (key == null) {
            return false;
        }
        s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
        return true;
    }

    @Override
    public String backendName() {
        return "s3://" + bucket + "/" + prefix;
    }

    /**
     * Extracts the object key, refusing a URI that names a different bucket.
     * Snapshot URIs are read back from the control plane, and a row naming
     * another bucket must not turn into a request against it.
     */
    String keyFrom(String uri) {
        if (uri == null || !uri.startsWith("s3://")) {
            return null;
        }
        String withoutScheme = uri.substring("s3://".length());
        int slash = withoutScheme.indexOf('/');
        if (slash < 0) {
            return null;
        }
        String uriBucket = withoutScheme.substring(0, slash);
        return uriBucket.equals(bucket) ? withoutScheme.substring(slash + 1) : null;
    }

    private static String normalisePrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return "";
        }
        return prefix.endsWith("/") ? prefix : prefix + "/";
    }
}
