package com.helios.testforge.support;

import com.helios.testforge.domain.schema.ColumnMeta;
import com.helios.testforge.domain.schema.DataClass;
import com.helios.testforge.domain.schema.ForeignKey;
import com.helios.testforge.domain.schema.PrimaryKey;
import com.helios.testforge.domain.schema.SchemaSnapshot;
import com.helios.testforge.domain.schema.TableMeta;
import com.helios.testforge.domain.schema.TableRef;
import com.helios.testforge.domain.schema.UniqueConstraint;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds schema snapshots in memory, so graph and generation logic can be
 * tested against precise shapes — a cycle with exactly one nullable edge, say —
 * without standing up a database to hold them.
 */
public final class SchemaFixtures {

    public static final String SCHEMA = "public";

    private SchemaFixtures() {
    }

    public static TableRef ref(String name) {
        return TableRef.of(SCHEMA, name);
    }

    public static Builder table(String name) {
        return new Builder(name);
    }

    public static SchemaSnapshot snapshot(Builder... builders) {
        List<TableMeta> tables = new ArrayList<>();
        for (Builder builder : builders) {
            tables.add(builder.build());
        }
        return new SchemaSnapshot("testdb", SCHEMA, Instant.parse("2026-01-01T00:00:00Z"), tables, "fixture");
    }

    /** A column that is a plain non-null integer surrogate primary key. */
    public static ColumnMeta idColumn(int position) {
        return new ColumnMeta("id", position, "integer", "int4", "integer", false,
                null, 32, 0, "nextval('seq'::regclass)", false, null, false, null, true,
                List.of(), null, null, DataClass.SURROGATE_KEY);
    }

    public static ColumnMeta column(String name, int position, String udtName, boolean nullable) {
        return new ColumnMeta(name, position, udtName, udtName, udtName, nullable,
                null, null, null, null, false, null, false, null, false,
                List.of(), null, null, DataClass.UNKNOWN);
    }

    public static final class Builder {
        private final String name;
        private final List<ColumnMeta> columns = new ArrayList<>();
        private final List<ForeignKey> foreignKeys = new ArrayList<>();
        private final List<UniqueConstraint> uniques = new ArrayList<>();
        private PrimaryKey primaryKey = new PrimaryKey("pk_" + "id", List.of("id"));

        private Builder(String name) {
            this.name = name;
            this.columns.add(idColumn(1));
            this.primaryKey = new PrimaryKey("pk_" + name, List.of("id"));
        }

        public Builder column(String columnName, String udtName, boolean nullable) {
            columns.add(SchemaFixtures.column(columnName, columns.size() + 1, udtName, nullable));
            return this;
        }

        public Builder classified(String columnName, String udtName, boolean nullable, DataClass dataClass) {
            columns.add(new ColumnMeta(columnName, columns.size() + 1, udtName, udtName, udtName, nullable,
                    null, null, null, null, false, null, false, null, false,
                    List.of(), null, null, dataClass));
            return this;
        }

        public Builder enumColumn(String columnName, boolean nullable, String... labels) {
            columns.add(new ColumnMeta(columnName, columns.size() + 1, "USER-DEFINED", "status_enum",
                    "status_enum", nullable, null, null, null, null, false, null, false, null, false,
                    List.of(labels), null, null, DataClass.ENUM_LABEL));
            return this;
        }

        public Builder unique(String columnName) {
            uniques.add(new UniqueConstraint("uq_" + name + "_" + columnName, List.of(columnName), false));
            return this;
        }

        /** Adds a foreign key and the column backing it in one step. */
        public Builder references(String columnName, String parentTable, boolean nullable) {
            columns.add(new ColumnMeta(columnName, columns.size() + 1, "integer", "int4", "integer", nullable,
                    null, 32, 0, null, false, null, false, null, false,
                    List.of(), null, null, DataClass.FOREIGN_KEY));
            foreignKeys.add(new ForeignKey(
                    "fk_" + name + "_" + columnName,
                    ref(name), List.of(columnName),
                    ref(parentTable), List.of("id"),
                    "NO ACTION", "NO ACTION", false));
            return this;
        }

        public Builder noPrimaryKey() {
            primaryKey = null;
            return this;
        }

        public TableMeta build() {
            return new TableMeta(ref(name), columns, primaryKey, foreignKeys, uniques, List.of(), -1, null);
        }
    }
}
