package com.helios.testforge.introspect;

import com.helios.testforge.domain.schema.DataClass;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class DataClassifierTest {

    private static DataClass classify(String column, String type) {
        return DataClassifier.classify(column, type, false, false, false);
    }

    @ParameterizedTest
    @CsvSource({
            "email,varchar,EMAIL",
            "contact_email,varchar,EMAIL",
            "billing_email_address,varchar,EMAIL",
            "phone_number,varchar,PHONE",
            "mobile,varchar,PHONE",
            "ssn,varchar,SSN",
            "first_name,varchar,GIVEN_NAME",
            "last_name,varchar,FAMILY_NAME",
            "customer_name,varchar,FULL_NAME",
            "street_address,text,STREET_ADDRESS",
            "postal_code,varchar,POSTAL_CODE",
            "client_ip,inet,IP_ADDRESS",
            "password_hash,varchar,PASSWORD_HASH",
            "unit_price,numeric,MONETARY_AMOUNT",
            "quantity,int4,QUANTITY",
            "created_at,timestamptz,CREATED_TIMESTAMP",
            "updated_at,timestamptz,UPDATED_TIMESTAMP",
            "description,text,FREE_TEXT",
            "status,varchar,STATUS_FLAG"
    })
    void classifiesColumnsFromTheirNames(String column, String type, DataClass expected) {
        assertThat(classify(column, type)).isEqualTo(expected);
    }

    @Test
    void aNameGuessIsRejectedWhenTheTypeCannotHoldIt() {
        // `is_email_verified` is a boolean flag, not an address — masking it as
        // an email would produce a value the column cannot store.
        assertThat(classify("is_email_verified", "bool")).isEqualTo(DataClass.BOOLEAN_FLAG);
        assertThat(classify("phone_verified", "bool")).isEqualTo(DataClass.BOOLEAN_FLAG);
        assertThat(classify("address_id", "int4")).isEqualTo(DataClass.SURROGATE_KEY);
    }

    @Test
    void moreSpecificNamesWinOverGenericOnes() {
        assertThat(classify("date_of_birth", "date")).isEqualTo(DataClass.DATE_OF_BIRTH);
        assertThat(classify("shipped_date", "date")).isEqualTo(DataClass.DATE);
    }

    @Test
    void keysAreStructuralRatherThanSemantic() {
        assertThat(DataClassifier.classify("owner_email_id", "int4", false, false, true))
                .isEqualTo(DataClass.FOREIGN_KEY);
        assertThat(DataClassifier.classify("id", "int8", false, true, false))
                .isEqualTo(DataClass.SURROGATE_KEY);
    }

    @Test
    void enumColumnsAreClassifiedByTheirType() {
        assertThat(DataClassifier.classify("whatever", "order_status", true, false, false))
                .isEqualTo(DataClass.ENUM_LABEL);
    }

    @Test
    void everyPiiClassIsMarkedSensitive() {
        assertThat(DataClass.EMAIL.sensitive()).isTrue();
        assertThat(DataClass.SSN.sensitive()).isTrue();
        assertThat(DataClass.CREDIT_CARD.sensitive()).isTrue();
        assertThat(DataClass.FULL_NAME.sensitive()).isTrue();
        assertThat(DataClass.STATUS_FLAG.sensitive()).isFalse();
        assertThat(DataClass.SURROGATE_KEY.sensitive()).isFalse();
    }

    @Test
    void namesAreNormalisedBeforeMatching() {
        assertThat(DataClassifier.normalise("FirstName")).isEqualTo("firstname");
        assertThat(DataClassifier.normalise("first-name")).isEqualTo("first_name");
        assertThat(DataClassifier.normalise("first__name_")).isEqualTo("first_name");
    }

    @Test
    void unknownNamesFallBackToTheSqlType() {
        assertThat(classify("xyzzy", "bool")).isEqualTo(DataClass.BOOLEAN_FLAG);
        assertThat(classify("xyzzy", "jsonb")).isEqualTo(DataClass.JSON_DOCUMENT);
        assertThat(classify("xyzzy", "bytea")).isEqualTo(DataClass.BINARY_BLOB);
        assertThat(classify("xyzzy", "text")).isEqualTo(DataClass.FREE_TEXT);
    }
}
