package com.helios.testforge.snapshot;

import com.helios.testforge.ddl.DdlWriter;
import com.helios.testforge.domain.plan.GenerationPlan;
import com.helios.testforge.domain.plan.TablePlan;
import com.helios.testforge.domain.schema.ColumnMeta;
import com.helios.testforge.domain.schema.SchemaSnapshot;
import com.helios.testforge.domain.schema.TableMeta;
import com.helios.testforge.util.Pg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Packages a seeded dataset into a portable bundle.
 *
 * <p>A ZIP rather than a {@code pg_dump}: the bundle has to be readable without
 * a PostgreSQL client, since the people who want it are as likely to load it
 * into a test fixture, a spreadsheet or another engine as to restore it. It
 * contains the DDL needed to recreate the schema, one CSV per table, and a
 * manifest recording the seed, the schema fingerprint and every masking
 * decision — which together are enough to reproduce the dataset exactly, or to
 * answer a compliance question about it months later.
 */
@Component
public class SnapshotExporter {

    private static final Logger log = LoggerFactory.getLogger(SnapshotExporter.class);

    /** Rows read from the database at a time, so a large table does not have to fit in memory at once. */
    private static final int FETCH_SIZE = 5_000;

    /** Rows exported per table. A snapshot is a portable sample, not a second copy of the database. */
    private static final int MAX_ROWS_PER_TABLE = 100_000;

    private final DdlWriter ddlWriter;
    private final SnapshotStore store;

    public SnapshotExporter(DdlWriter ddlWriter, SnapshotStore store) {
        this.ddlWriter = ddlWriter;
        this.store = store;
    }

    /**
     * Builds and stores the bundle.
     *
     * @param connection an open connection to the seeded database
     * @param snapshot   the schema
     * @param plan       the plan the dataset was built from
     * @param datasetId  the dataset
     * @param datasetName a human label, used in the file name
     */
    public SnapshotRef export(Connection connection,
                              SchemaSnapshot snapshot,
                              GenerationPlan plan,
                              UUID datasetId,
                              String datasetName) {
        long started = System.nanoTime();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        long totalRows = 0;

        try (ZipOutputStream zip = new ZipOutputStream(buffer, StandardCharsets.UTF_8)) {
            writeEntry(zip, "schema.sql", ddlWriter.writeScript(snapshot).getBytes(StandardCharsets.UTF_8));

            for (TablePlan tablePlan : plan.tables()) {
                TableMeta table = snapshot.requireTable(tablePlan.table());
                byte[] csv = exportTable(connection, table);
                writeEntry(zip, "data/" + table.name() + ".csv", csv);
                totalRows += countLines(csv) - 1;
            }

            writeEntry(zip, "manifest.json",
                    manifest(snapshot, plan, datasetId, datasetName, totalRows)
                            .getBytes(StandardCharsets.UTF_8));
            writeEntry(zip, "README.txt", readme().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("failed to build the snapshot bundle", e);
        }

        byte[] bundle = buffer.toByteArray();
        String filename = safeName(datasetName) + "-" + plan.snapshotFingerprint() + ".zip";
        SnapshotRef stored = store.write(datasetId, filename, bundle);

        log.info("Exported {} rows across {} tables to {} ({} KB) in {} ms",
                totalRows, plan.tableCount(), stored.uri(), bundle.length / 1024,
                (System.nanoTime() - started) / 1_000_000);

        return new SnapshotRef(datasetId, stored.uri(), stored.byteSize(), stored.checksum(),
                totalRows, plan.tableCount(), Instant.now());
    }

    // ---------------------------------------------------------------- table

    private byte[] exportTable(Connection connection, TableMeta table) {
        StringBuilder csv = new StringBuilder();
        List<String> columnNames = table.columns().stream().map(ColumnMeta::name).toList();
        csv.append(columnNames.stream().map(SnapshotExporter::csvCell)
                .reduce((a, b) -> a + "," + b).orElse("")).append('\n');

        String sql = "SELECT " + columnNames.stream().map(Pg::quoteIdentifier)
                .reduce((a, b) -> a + ", " + b).orElse("*")
                + " FROM " + table.ref().quoted() + " LIMIT " + MAX_ROWS_PER_TABLE;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setFetchSize(FETCH_SIZE);
            try (ResultSet rs = statement.executeQuery()) {
                ResultSetMetaData meta = rs.getMetaData();
                int columns = meta.getColumnCount();
                while (rs.next()) {
                    List<String> cells = new ArrayList<>(columns);
                    for (int i = 1; i <= columns; i++) {
                        Object value = rs.getObject(i);
                        cells.add(csvCell(value == null ? null : String.valueOf(value)));
                    }
                    csv.append(String.join(",", cells)).append('\n');
                }
            }
        } catch (SQLException e) {
            throw new SnapshotException("failed to export " + table.qualified(), e);
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * RFC 4180 quoting. A NULL is written as an empty unquoted field, which is
     * distinguishable from an empty string — written as {@code ""} — so a
     * round trip does not silently turn one into the other.
     */
    static String csvCell(String value) {
        if (value == null) {
            return "";
        }
        String escaped = value.replace("\"", "\"\"");
        return '"' + escaped + '"';
    }

    // ------------------------------------------------------------- manifest

    private String manifest(SchemaSnapshot snapshot, GenerationPlan plan,
                            UUID datasetId, String datasetName, long totalRows) {
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"datasetId\": ").append(jsonString(datasetId.toString())).append(",\n");
        json.append("  \"datasetName\": ").append(jsonString(datasetName)).append(",\n");
        json.append("  \"exportedAt\": ").append(jsonString(Instant.now().toString())).append(",\n");
        json.append("  \"generator\": \"TestForge\",\n");
        json.append("  \"reproducibility\": {\n");
        json.append("    \"seed\": ").append(plan.seed()).append(",\n");
        json.append("    \"schemaFingerprint\": ").append(jsonString(plan.snapshotFingerprint())).append(",\n");
        json.append("    \"note\": \"Re-running this seed against a schema with the same fingerprint ")
                .append("reproduces this dataset exactly, masked values included.\"\n");
        json.append("  },\n");
        json.append("  \"source\": {\n");
        json.append("    \"database\": ").append(jsonString(snapshot.database())).append(",\n");
        json.append("    \"schema\": ").append(jsonString(snapshot.schema())).append(",\n");
        json.append("    \"capturedAt\": ").append(jsonString(snapshot.capturedAt().toString())).append("\n");
        json.append("  },\n");
        json.append("  \"totals\": { \"tables\": ").append(plan.tableCount())
                .append(", \"rows\": ").append(totalRows)
                .append(", \"maskedColumns\": ").append(plan.maskedColumns()).append(" },\n");

        json.append("  \"tables\": [\n");
        List<String> tables = plan.tables().stream()
                .map(table -> "    { \"name\": " + jsonString(table.table().qualified())
                        + ", \"seedOrder\": " + table.order()
                        + ", \"depth\": " + table.depth()
                        + ", \"rows\": " + table.rowCount() + " }")
                .toList();
        json.append(String.join(",\n", tables)).append("\n  ],\n");

        json.append("  \"masking\": [\n");
        List<String> masking = plan.maskingSummary().stream().map(SnapshotExporter::jsonString)
                .map(entry -> "    " + entry).toList();
        json.append(String.join(",\n", masking)).append("\n  ],\n");

        json.append("  \"warnings\": [\n");
        List<String> warnings = plan.warnings().stream().map(SnapshotExporter::jsonString)
                .map(entry -> "    " + entry).toList();
        json.append(String.join(",\n", warnings)).append("\n  ]\n");
        json.append("}\n");
        return json.toString();
    }

    private String readme() {
        return """
                TestForge snapshot bundle
                =========================

                schema.sql        DDL recreating the schema this dataset was generated against.
                data/<table>.csv  One file per table, in dependency order, RFC 4180 quoted.
                                  An empty unquoted field is NULL; "" is an empty string.
                manifest.json     Provenance: the seed, the schema fingerprint, per-table row
                                  counts, and every masking decision applied.

                To restore into a local PostgreSQL database:

                    createdb restored
                    psql restored -f schema.sql
                    # then, per table, in the order listed in manifest.json:
                    psql restored -c "\\copy <table> FROM 'data/<table>.csv' WITH (FORMAT csv, HEADER true)"

                Load the tables in the manifest's seedOrder. The foreign keys in schema.sql
                are real, so loading a child before its parent will be rejected.

                Every value here is synthetic. No production row was read to produce it.
                """;
    }

    // -------------------------------------------------------------- helpers

    private void writeEntry(ZipOutputStream zip, String name, byte[] content) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(0L);  // A fixed timestamp keeps identical datasets byte-identical.
        zip.putNextEntry(entry);
        zip.write(content);
        zip.closeEntry();
    }

    private static long countLines(byte[] content) {
        long lines = 0;
        for (byte b : content) {
            if (b == '\n') {
                lines++;
            }
        }
        return lines;
    }

    static String jsonString(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder escaped = new StringBuilder(value.length() + 2).append('"');
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (c < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) c));
                    } else {
                        escaped.append(c);
                    }
                }
            }
        }
        return escaped.append('"').toString();
    }

    static String safeName(String name) {
        if (name == null || name.isBlank()) {
            return "dataset";
        }
        String safe = name.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        return safe.isEmpty() ? "dataset" : (safe.length() > 40 ? safe.substring(0, 40) : safe);
    }

    /** Raised when a dataset cannot be read back for export. */
    public static class SnapshotException extends RuntimeException {

        public SnapshotException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
