package com.swingscope.domain.scan;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class WatchlistEntryTest {

    @Test
    void constructorStampsTodayAndKeepsWhatItWasGiven() {
        WatchlistEntry entry = new WatchlistEntry("VZ", "dividend payer");

        assertThat(entry.getTicker()).isEqualTo("VZ");
        assertThat(entry.getNote()).isEqualTo("dividend payer");
        assertThat(entry.getDateAdded()).isEqualTo(LocalDate.now());
        assertThat(entry.getId()).isNull();
    }

    @Test
    void settersRoundTrip() {
        WatchlistEntry entry = new WatchlistEntry("VZ", null);

        entry.setTicker("CARR");
        entry.setNote("watch the gap");
        entry.setDateAdded(LocalDate.of(2026, 1, 15));

        assertThat(entry.getTicker()).isEqualTo("CARR");
        assertThat(entry.getNote()).isEqualTo("watch the gap");
        assertThat(entry.getDateAdded()).isEqualTo(LocalDate.of(2026, 1, 15));
    }
}
