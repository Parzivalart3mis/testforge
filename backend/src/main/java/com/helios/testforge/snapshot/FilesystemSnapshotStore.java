package com.helios.testforge.snapshot;

import com.helios.testforge.config.TestForgeProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/**
 * Snapshot bundles on local disk.
 *
 * <p>Addressed by URI rather than by path, so a stored reference stays
 * meaningful if bundles ever move to object storage behind the same interface.
 */
@Component
public class FilesystemSnapshotStore implements SnapshotStore {

    private static final Logger log = LoggerFactory.getLogger(FilesystemSnapshotStore.class);

    private final Path root;

    public FilesystemSnapshotStore(TestForgeProperties properties) {
        this.root = Path.of(properties.snapshots().directory()).toAbsolutePath().normalize();
    }

    @Override
    public SnapshotRef write(UUID datasetId, String filename, byte[] content) {
        Path directory = root.resolve(datasetId.toString());
        Path target = directory.resolve(sanitise(filename));
        try {
            Files.createDirectories(directory);
            Files.write(target, content);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to write snapshot to " + target, e);
        }
        log.info("Wrote snapshot {} ({} bytes)", target, content.length);
        return new SnapshotRef(datasetId, target.toUri().toString(), content.length,
                checksum(content), 0, 0, Instant.now());
    }

    @Override
    public Optional<byte[]> read(String uri) {
        Path path = toPath(uri);
        if (path == null || !Files.isRegularFile(path)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readAllBytes(path));
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read snapshot at " + uri, e);
        }
    }

    @Override
    public boolean delete(String uri) {
        Path path = toPath(uri);
        if (path == null) {
            return false;
        }
        try {
            return Files.deleteIfExists(path);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to delete snapshot at " + uri, e);
        }
    }

    @Override
    public String backendName() {
        return "filesystem:" + root;
    }

    /**
     * Resolves a URI to a path under the configured root, refusing anything that
     * escapes it. Snapshot URIs come back from the control-plane database and a
     * traversal in one would otherwise let a read reach any file the service can
     * open.
     */
    private Path toPath(String uri) {
        Path path;
        try {
            path = uri.startsWith("file:")
                    ? Path.of(java.net.URI.create(uri))
                    : Path.of(uri);
        } catch (RuntimeException e) {
            return null;
        }
        Path normalised = path.toAbsolutePath().normalize();
        return normalised.startsWith(root) ? normalised : null;
    }

    /** Strips any path structure, so a filename can only ever name a file in its own directory. */
    static String sanitise(String filename) {
        String base = filename.replace('\\', '/');
        int slash = base.lastIndexOf('/');
        String name = slash < 0 ? base : base.substring(slash + 1);
        return name.isBlank() ? "snapshot.zip" : name;
    }

    static String checksum(byte[] content) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha256.digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every JVM", e);
        }
    }

    /** The bytes a text entry contributes, in the encoding bundles are written in. */
    static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
