package com.helios.testforge.domain.schema;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TableRefTest {

    @Test
    void parsesQualifiedAndBareNames() {
        assertThat(TableRef.parse("sales.orders", "public")).isEqualTo(TableRef.of("sales", "orders"));
        assertThat(TableRef.parse("orders", "public")).isEqualTo(TableRef.of("public", "orders"));
    }

    @Test
    void rendersQualifiedAndQuotedForms() {
        TableRef ref = TableRef.of("public", "order_items");
        assertThat(ref.qualified()).isEqualTo("public.order_items");
        assertThat(ref.quoted()).isEqualTo("\"public\".\"order_items\"");
    }

    @Test
    void ordersBySchemaThenName() {
        assertThat(TableRef.of("a", "z")).isLessThan(TableRef.of("b", "a"));
        assertThat(TableRef.of("a", "a")).isLessThan(TableRef.of("a", "b"));
    }

    @Test
    void rejectsBlankComponents() {
        assertThatThrownBy(() -> TableRef.of("", "orders")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TableRef.of("public", " ")).isInstanceOf(IllegalArgumentException.class);
    }
}
