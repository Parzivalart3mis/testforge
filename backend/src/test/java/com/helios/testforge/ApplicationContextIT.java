package com.helios.testforge;

import com.helios.testforge.ddl.DdlWriter;
import com.helios.testforge.generate.DatasetGenerator;
import com.helios.testforge.generate.GenerationPlanner;
import com.helios.testforge.introspect.PostgresSchemaIntrospector;
import com.helios.testforge.job.JobStore;
import com.helios.testforge.lease.CredentialCipher;
import com.helios.testforge.lease.LeaseService;
import com.helios.testforge.mask.MaskingEngine;
import com.helios.testforge.persistence.DatasetRepository;
import com.helios.testforge.persistence.LeaseRepository;
import com.helios.testforge.pipeline.DatasetService;
import com.helios.testforge.pipeline.ProvisioningPipeline;
import com.helios.testforge.seed.Seeder;
import com.helios.testforge.snapshot.SnapshotStore;
import com.helios.testforge.support.PostgresIntegrationTest;
import com.helios.testforge.support.TestContainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The whole application starts against a real PostgreSQL instance.
 *
 * <p>This is the test that catches everything a unit test structurally cannot:
 * a migration that does not apply, a bean that cannot be constructed, a
 * conditional that leaves a required collaborator missing. It is cheap and it
 * fails loudly, which is the right shape for a smoke test.
 */
@PostgresIntegrationTest
@Import(TestContainersConfig.class)
class ApplicationContextIT {

    @Autowired
    JdbcClient jdbc;

    @Autowired
    List<Object> allBeans;

    @Autowired
    DatasetService datasetService;

    @Autowired
    ProvisioningPipeline pipeline;

    @Autowired
    JobStore jobStore;

    @Autowired
    SnapshotStore snapshotStore;

    @Autowired
    LeaseService leaseService;

    @Autowired
    MaskingEngine maskingEngine;

    @Autowired
    CredentialCipher credentialCipher;

    @Autowired
    PostgresSchemaIntrospector introspector;

    @Autowired
    GenerationPlanner planner;

    @Autowired
    DatasetGenerator generator;

    @Autowired
    Seeder seeder;

    @Autowired
    DdlWriter ddlWriter;

    @Autowired
    DatasetRepository datasetRepository;

    @Autowired
    LeaseRepository leaseRepository;

    @Test
    void everyCollaboratorInThePipelineIsWired() {
        assertThat(datasetService).isNotNull();
        assertThat(pipeline).isNotNull();
        assertThat(introspector).isNotNull();
        assertThat(planner).isNotNull();
        assertThat(generator).isNotNull();
        assertThat(seeder).isNotNull();
        assertThat(ddlWriter).isNotNull();
        assertThat(leaseService).isNotNull();
        assertThat(maskingEngine).isNotNull();
        assertThat(credentialCipher).isNotNull();
    }

    @Test
    void theLocalBackendsAreSelectedUnderTheTestProfile() {
        assertThat(jobStore.backendName()).isEqualTo("memory");
        assertThat(snapshotStore.backendName()).startsWith("filesystem:");
    }

    @Test
    void everyFlywayMigrationApplied() {
        List<String> applied = jdbc.sql(
                        "SELECT version FROM flyway_schema_history WHERE success = true ORDER BY installed_rank")
                .query(String.class)
                .list();

        assertThat(applied)
                .as("a migration that does not apply is only ever caught here")
                .contains("1", "2", "3");
    }

    @Test
    void theControlPlaneTablesExist() {
        List<String> tables = jdbc.sql("""
                        SELECT table_name FROM information_schema.tables
                        WHERE table_schema = 'public' AND table_type = 'BASE TABLE'
                        ORDER BY table_name
                        """)
                .query(String.class)
                .list();

        assertThat(tables).contains(
                "audit_event", "dataset", "lease", "masking_record", "schema_snapshot", "snapshot_export");
    }

    @Test
    void theReaperIndexExistsSoExpirySweepsStayIndexed() {
        List<String> indexes = jdbc.sql(
                        "SELECT indexname FROM pg_indexes WHERE tablename = 'lease' ORDER BY indexname")
                .query(String.class)
                .list();

        assertThat(indexes)
                .as("the reaper polls this every minute; a sequential scan would be a permanent cost")
                .contains("ix_lease_reaper");
    }

    @Test
    void theCredentialColumnsFromTheThirdMigrationExist() {
        List<String> columns = jdbc.sql("""
                        SELECT column_name FROM information_schema.columns
                        WHERE table_name = 'lease' ORDER BY column_name
                        """)
                .query(String.class)
                .list();

        assertThat(columns).contains("credential_ciphertext", "credential_claimed_at");
    }

    @Test
    void theDatasetStatsQueryRunsAgainstAnEmptyTable() {
        // Aggregates over an empty table are a classic source of nulls where the
        // caller expects zeroes.
        var stats = datasetRepository.stats();

        assertThat(stats.total()).isZero();
        assertThat(stats.rowsGenerated()).isZero();
        assertThat(stats.avgDurationMs()).isZero();
    }

    @Test
    void theLeaseRepositoryReportsNoActiveLeasesOnAFreshDatabase() {
        assertThat(leaseRepository.countActive()).isZero();
        assertThat(leaseRepository.activeDatabaseNames()).isEmpty();
    }
}
