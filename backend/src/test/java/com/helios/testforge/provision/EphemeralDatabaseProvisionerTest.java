package com.helios.testforge.provision;

import com.helios.testforge.config.TestForgeProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EphemeralDatabaseProvisionerTest {

    private static EphemeralDatabaseProvisioner provisioner() {
        TestForgeProperties.Ephemeral ephemeral = new TestForgeProperties.Ephemeral(
                "jdbc:postgresql://localhost:5432/postgres", "postgres", "postgres",
                "db.internal", 5432, "tf_", 50);
        return new EphemeralDatabaseProvisioner(new TestForgeProperties(
                null, null, ephemeral, null, null, null, List.of()));
    }

    @ParameterizedTest
    @CsvSource({
            "Checkout Service,checkout_service",
            "orders-api,orders_api",
            "  Trailing  ,trailing",
            "!!!,ds",
            "'',ds"
    })
    void reducesLabelsToLegalIdentifierFragments(String label, String expected) {
        assertThat(EphemeralDatabaseProvisioner.slugify(label)).isEqualTo(expected);
    }

    @Test
    void slugsAreTruncatedToLeaveRoomInsideThe63ByteIdentifierLimit() {
        String slug = EphemeralDatabaseProvisioner.slugify("a".repeat(200));

        assertThat(slug).hasSize(24);
        // prefix + slug + underscore + 10-char suffix + "_r" must still fit.
        assertThat("tf_".length() + slug.length() + 1 + 10 + 2).isLessThan(63);
    }

    @Test
    void nullLabelsAreTolerated() {
        assertThat(EphemeralDatabaseProvisioner.slugify(null)).isEqualTo("ds");
    }

    @Test
    void refusesToDropAnythingWithoutTheEphemeralPrefix() {
        // This guard runs before any connection is opened, which is what makes
        // it a safety property rather than a database-level check.
        assertThatThrownBy(() -> provisioner().drop("production_orders", "app_user"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("refusing to drop")
                .hasMessageContaining("was not created by TestForge");
    }

    @Test
    void refusesToDropANullDatabaseName() {
        assertThatThrownBy(() -> provisioner().drop(null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("refusing to drop");
    }

    @Test
    void refusesToDropWhenOnlyTheRoleNameIsForeign() {
        assertThatThrownBy(() -> provisioner().drop("tf_orders_abc123", "postgres"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("refusing to drop");
    }

    @Test
    void aProvisionedDatabaseBuildsAConnectionStringAndCanRedactIt() {
        ProvisionedDatabase database = new ProvisionedDatabase(
                java.util.UUID.randomUUID(), "tf_demo_abc", "tf_demo_abc_r", "s3cret",
                "jdbc:postgresql://db.internal:5432/tf_demo_abc", java.time.Instant.now());

        assertThat(database.connectionString())
                .isEqualTo("jdbc:postgresql://db.internal:5432/tf_demo_abc?user=tf_demo_abc_r&password=s3cret");
        assertThat(database.redacted().password()).isNull();
        assertThat(database.redacted().connectionString()).isNull();
    }

    @Test
    void connectingWithARedactedRecordFailsLoudlyRatherThanWithANullPassword() {
        ProvisionedDatabase redacted = new ProvisionedDatabase(
                java.util.UUID.randomUUID(), "tf_demo_abc", "tf_demo_abc_r", null,
                "jdbc:postgresql://db.internal:5432/tf_demo_abc", java.time.Instant.now()).redacted();

        assertThatThrownBy(() -> provisioner().connectAsOwner(redacted))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("password has been redacted");
    }

    @Test
    void provisioningFailsClearlyWhenNoClusterIsConfigured() {
        EphemeralDatabaseProvisioner unconfigured = new EphemeralDatabaseProvisioner(
                new TestForgeProperties(null, null,
                        new TestForgeProperties.Ephemeral(null, null, null, "localhost", 5432, "tf_", 50),
                        null, null, null, List.of()));

        assertThatThrownBy(() -> unconfigured.listOwnedDatabases())
                .isInstanceOf(EphemeralDatabaseProvisioner.ProvisioningException.class)
                .hasMessageContaining("admin-jdbc-url is not configured");
    }
}
