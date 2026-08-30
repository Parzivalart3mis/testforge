package com.helios.testforge.introspect;

import com.helios.testforge.domain.schema.ColumnMeta;
import com.helios.testforge.domain.schema.DataClass;
import com.helios.testforge.domain.schema.ForeignKey;
import com.helios.testforge.domain.schema.SchemaSnapshot;
import com.helios.testforge.domain.schema.TableMeta;
import com.helios.testforge.support.DemoSchema;
import com.helios.testforge.support.DockerAvailability;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Introspection against a real 22-table schema.
 *
 * <p>Every assertion here is about something a hand-written fixture cannot
 * prove: that the catalog queries actually return what the code expects from
 * PostgreSQL, for the awkward cases — identity versus serial, stored generated
 * columns, composite and self-referencing foreign keys, enum labels in catalog
 * order, and typmod-encoded precision.
 */
@EnabledIf(value = "com.helios.testforge.support.DockerAvailability#isPresent",
        disabledReason = "no container runtime is available")
class PostgresSchemaIntrospectorIT {

    private static SchemaSnapshot snapshot;

    @BeforeAll
    static void introspectOnce() {
        if (!DockerAvailability.isPresent()) {
            return;
        }
        snapshot = new PostgresSchemaIntrospector()
                .introspect(DemoSchema.dataSource(), DemoSchema.SCHEMA);
    }

    @Nested
    class Structure {

        @Test
        void findsEveryTableInTheSchema() {
            assertThat(snapshot.tables().stream().map(TableMeta::name).sorted().toList())
                    .isEqualTo(DemoSchema.TABLES);
            assertThat(snapshot.tableCount()).isEqualTo(22);
        }

        @Test
        void findsEveryForeignKeyIncludingTheOneAddedByAlterTable() {
            List<String> constraints = snapshot.allForeignKeys().stream()
                    .map(ForeignKey::name)
                    .toList();

            assertThat(constraints)
                    .as("the cycle-closing key is added after both tables exist")
                    .contains("fk_customer_primary_order");
            assertThat(snapshot.foreignKeyCount()).isGreaterThanOrEqualTo(25);
        }

        @Test
        void identifiesSelfReferencingForeignKeys() {
            List<String> selfReferencing = snapshot.allForeignKeys().stream()
                    .filter(ForeignKey::isSelfReference)
                    .map(fk -> fk.child().name() + "." + fk.childColumns().getFirst())
                    .sorted()
                    .toList();

            assertThat(selfReferencing).containsExactly(
                    "category.parent_id", "customer.referred_by_id", "employee.manager_id");
        }

        @Test
        void readsACompositeForeignKeyWithItsColumnsInConstraintOrder() {
            ForeignKey composite = snapshot.requireTable(
                            com.helios.testforge.domain.schema.TableRef.of("public", "shipment_item"))
                    .foreignKeys().stream()
                    .filter(ForeignKey::isComposite)
                    .findFirst()
                    .orElseThrow();

            assertThat(composite.childColumns()).containsExactly("order_id", "line_number");
            assertThat(composite.parentColumns()).containsExactly("order_id", "line_number");
            assertThat(composite.parent().name()).isEqualTo("order_line");
        }

        @Test
        void readsCompositePrimaryKeysInDeclaredOrder() {
            var inventory = snapshot.requireTable(
                    com.helios.testforge.domain.schema.TableRef.of("public", "inventory"));

            assertThat(inventory.primaryKeyOpt()).isPresent();
            assertThat(inventory.primaryKey().columns())
                    .containsExactly("warehouse_id", "product_variant_id");
        }

        @Test
        void picksUpUniqueConstraintsAndCheckConstraints() {
            var orderLine = snapshot.requireTable(
                    com.helios.testforge.domain.schema.TableRef.of("public", "order_line"));

            assertThat(orderLine.uniques())
                    .anyMatch(u -> u.columns().equals(List.of("order_id", "line_number")));
            assertThat(orderLine.checks())
                    .anyMatch(c -> c.name().equals("ck_order_line_quantity"));
        }
    }

    @Nested
    class ColumnDetail {

        private ColumnMeta column(String table, String name) {
            return snapshot.requireTable(
                            com.helios.testforge.domain.schema.TableRef.of("public", table))
                    .requireColumn(name);
        }

        @Test
        void distinguishesSerialFromIdentity() {
            assertThat(column("customer", "id").serial())
                    .as("SERIAL is a nextval default, not an identity column")
                    .isTrue();
            assertThat(column("customer", "id").identity()).isFalse();

            assertThat(column("address", "id").identity()).isTrue();
            assertThat(column("address", "id").identityGeneration()).isEqualTo("ALWAYS");
            assertThat(column("address", "id").identityAlways()).isTrue();
        }

        @Test
        void recognisesAStoredGeneratedColumnTheSeederMustSkip() {
            ColumnMeta fullName = column("employee", "full_name");

            assertThat(fullName.generated()).isTrue();
            assertThat(fullName.databaseSupplied())
                    .as("a STORED generated column cannot appear in an INSERT")
                    .isTrue();
        }

        @Test
        void anIdentityColumnIsStillSuppliedByTestForge() {
            assertThat(column("address", "id").databaseSupplied())
                    .as("keys are assigned by the platform so children can reference them")
                    .isFalse();
        }

        @Test
        void decodesLengthPrecisionAndScaleFromTypmod() {
            assertThat(column("customer", "email").maxLength()).isEqualTo(255);
            assertThat(column("country", "iso_code").maxLength()).isEqualTo(2);

            ColumnMeta lifetimeValue = column("customer", "lifetime_value");
            assertThat(lifetimeValue.numericPrecision()).isEqualTo(12);
            assertThat(lifetimeValue.numericScale()).isEqualTo(2);
        }

        @Test
        void readsEnumLabelsInTheirCatalogOrder() {
            ColumnMeta status = column("order_header", "status");

            assertThat(status.isEnum()).isTrue();
            assertThat(status.enumLabels())
                    .as("an enum's order is part of its semantics; ORDER BY depends on it")
                    .containsExactly("DRAFT", "PLACED", "PAID", "FULFILLED", "CANCELLED", "REFUNDED");
        }

        @Test
        void detectsArrayColumnsAndTheirElementType() {
            ColumnMeta tags = column("customer", "tags");

            assertThat(tags.isArray()).isTrue();
            assertThat(tags.arrayElementType()).isEqualTo("text");
        }

        @Test
        void capturesNullabilityAndDefaults() {
            assertThat(column("customer", "email").nullable()).isFalse();
            assertThat(column("customer", "phone").nullable()).isTrue();
            assertThat(column("customer", "marketing_opt_in").defaultExpression()).isEqualTo("false");
        }

        @Test
        void carriesColumnAndTableComments() {
            assertThat(column("customer", "primary_order_id").comment())
                    .contains("cycle-closing edge");
            assertThat(snapshot.requireTable(
                    com.helios.testforge.domain.schema.TableRef.of("public", "inventory")).comment())
                    .contains("primary key is foreign keys");
        }
    }

    @Nested
    class Classification {

        private DataClass classOf(String table, String column) {
            return snapshot.requireTable(
                            com.helios.testforge.domain.schema.TableRef.of("public", table))
                    .requireColumn(column).dataClass();
        }

        @Test
        void classifiesPiiColumnsFromTheirNames() {
            assertThat(classOf("customer", "email")).isEqualTo(DataClass.EMAIL);
            assertThat(classOf("customer", "first_name")).isEqualTo(DataClass.GIVEN_NAME);
            assertThat(classOf("customer", "last_name")).isEqualTo(DataClass.FAMILY_NAME);
            assertThat(classOf("customer", "phone")).isEqualTo(DataClass.PHONE);
            assertThat(classOf("customer", "date_of_birth")).isEqualTo(DataClass.DATE_OF_BIRTH);
            assertThat(classOf("customer", "national_id")).isEqualTo(DataClass.NATIONAL_ID);
            assertThat(classOf("payment", "card_number")).isEqualTo(DataClass.CREDIT_CARD);
            assertThat(classOf("supplier", "iban")).isEqualTo(DataClass.IBAN);
            assertThat(classOf("address", "street_address")).isEqualTo(DataClass.STREET_ADDRESS);
            assertThat(classOf("audit_log", "client_ip")).isEqualTo(DataClass.IP_ADDRESS);
        }

        @Test
        void treatsKeysAsStructuralRatherThanSemantic() {
            assertThat(classOf("order_header", "customer_id")).isEqualTo(DataClass.FOREIGN_KEY);
            assertThat(classOf("customer", "id")).isEqualTo(DataClass.SURROGATE_KEY);
        }

        @Test
        void flagsTheSensitiveColumnsMaskingWillActOn() {
            List<String> sensitive = snapshot.sensitiveColumns();

            assertThat(sensitive)
                    .contains("public.customer.email", "public.customer.national_id",
                            "public.payment.card_number", "public.supplier.iban")
                    .doesNotContain("public.order_header.status", "public.order_header.currency");
        }
    }

    @Nested
    class Fingerprinting {

        @Test
        void anUnchangedSchemaProducesTheSameFingerprint() {
            SchemaSnapshot again = new PostgresSchemaIntrospector()
                    .introspect(DemoSchema.dataSource(), DemoSchema.SCHEMA);

            assertThat(again.fingerprint())
                    .as("a stable fingerprint is what lets an unchanged schema reuse its snapshot row")
                    .isEqualTo(snapshot.fingerprint());
        }

        @Test
        void theFingerprintIsShortEnoughToStoreAndCompare() {
            assertThat(snapshot.fingerprint()).hasSize(32).matches("[0-9a-f]+");
        }
    }
}
