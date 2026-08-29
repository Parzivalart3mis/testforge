package com.helios.testforge.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PgTest {

    @Test
    void quotesEveryIdentifier() {
        assertThat(Pg.quoteIdentifier("users")).isEqualTo("\"users\"");
        assertThat(Pg.quoteIdentifier("Mixed Case")).isEqualTo("\"Mixed Case\"");
    }

    @Test
    void doublesEmbeddedQuotesSoInjectionCannotEscapeTheIdentifier() {
        assertThat(Pg.quoteIdentifier("we\"ird")).isEqualTo("\"we\"\"ird\"");
        assertThat(Pg.quoteIdentifier("a\"; DROP TABLE users; --"))
                .isEqualTo("\"a\"\"; DROP TABLE users; --\"");
    }

    @Test
    void rejectsNullBytesRatherThanTruncatingSilently() {
        assertThatThrownBy(() -> Pg.quoteIdentifier("bad\0name"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("null byte");
    }

    @Test
    void escapesLiteralsAndSwitchesToEStringsForBackslashes() {
        assertThat(Pg.quoteLiteral("plain")).isEqualTo("'plain'");
        assertThat(Pg.quoteLiteral("it's")).isEqualTo("'it''s'");
        assertThat(Pg.quoteLiteral("back\\slash")).isEqualTo("E'back\\\\slash'");
        assertThat(Pg.quoteLiteral(null)).isEqualTo("NULL");
    }

    @Test
    void recognisesIdentifiersThatWouldRoundTripUnquoted() {
        assertThat(Pg.isSimpleIdentifier("order_items")).isTrue();
        assertThat(Pg.isSimpleIdentifier("Orders")).isFalse();
        assertThat(Pg.isSimpleIdentifier("2fast")).isFalse();
    }
}
