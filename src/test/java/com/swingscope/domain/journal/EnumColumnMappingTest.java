package com.swingscope.domain.journal;

import jakarta.persistence.Column;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards a schema-drift bug that the normal test suite structurally cannot catch.
 *
 * <p>Left to itself, Hibernate maps a Java enum to H2's <em>native ENUM</em> column type, which fixes
 * the permitted values at table-creation time. {@code ddl-auto: update} never widens it, so adding a
 * new constant — {@code REJECTED} was the one that bit — fails at INSERT on any database created
 * before the change:
 *
 * <pre>Value not permitted for column "('CLOSED_LOSS', … 'SCRATCH')": "REJECTED"</pre>
 *
 * <p>The suite missed it because tests run {@code ddl-auto: create-drop} against an in-memory
 * database: the schema is always rebuilt from the current enum, so every constant is permitted.
 * Only a pre-existing file database reproduces it.
 *
 * <p>Pinning {@code columnDefinition = "varchar(...)"} keeps the column a plain string, so new enum
 * constants need no migration. These assertions fail if anyone removes that.
 */
class EnumColumnMappingTest {

    private static Column columnOf(Class<?> entity, String fieldName) throws NoSuchFieldException {
        Field field = entity.getDeclaredField(fieldName);
        Column column = field.getAnnotation(Column.class);
        assertThat(column)
                .as("%s.%s must carry @Column", entity.getSimpleName(), fieldName)
                .isNotNull();
        return column;
    }

    @Test
    @DisplayName("status is a varchar column, so new TradeStatus values need no migration")
    void statusColumnIsVarchar() throws NoSuchFieldException {
        assertThat(columnOf(TradeJournalEntry.class, "status").columnDefinition())
                .as("without this, adding a TradeStatus constant breaks INSERT on existing databases")
                .isEqualTo("varchar(20)");
    }

    @Test
    @DisplayName("setupType is a varchar column too, for the same reason")
    void setupTypeColumnIsVarchar() throws NoSuchFieldException {
        assertThat(columnOf(TradeJournalEntry.class, "setupType").columnDefinition())
                .isEqualTo("varchar(20)");
    }

    @Test
    @DisplayName("the varchar length still fits the longest constant name")
    void theColumnIsWideEnoughForEveryConstant() {
        int longestStatus = java.util.Arrays.stream(TradeStatus.values())
                .mapToInt(s -> s.name().length()).max().orElseThrow();
        int longestSetup = java.util.Arrays.stream(SetupType.values())
                .mapToInt(s -> s.name().length()).max().orElseThrow();

        assertThat(longestStatus)
                .as("longest TradeStatus name must fit in varchar(20)")
                .isLessThanOrEqualTo(20);
        assertThat(longestSetup)
                .as("longest SetupType name must fit in varchar(20)")
                .isLessThanOrEqualTo(20);
    }
}
