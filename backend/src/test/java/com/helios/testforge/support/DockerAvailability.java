package com.helios.testforge.support;

import org.testcontainers.DockerClientFactory;

/**
 * Whether a container runtime is reachable.
 *
 * <p>Probed once and cached: {@code isDockerAvailable} attempts a connection,
 * and on a machine without Docker each call waits out a timeout. Asking once per
 * JVM keeps a full skip from costing more than the tests would have.
 */
public final class DockerAvailability {

    private static final boolean PRESENT = probe();

    private DockerAvailability() {
    }

    public static boolean isPresent() {
        return PRESENT;
    }

    private static boolean probe() {
        if (Boolean.parseBoolean(System.getenv().getOrDefault("TESTFORGE_SKIP_CONTAINER_TESTS", "false"))) {
            return false;
        }
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }
}
