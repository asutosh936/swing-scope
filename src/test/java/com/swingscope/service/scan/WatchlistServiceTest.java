package com.swingscope.service.scan;

import com.swingscope.domain.scan.WatchlistEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class WatchlistServiceTest {

    @Autowired
    private WatchlistService watchlist;

    @Test
    void addsAndNormalisesTheTicker() {
        WatchlistEntry entry = watchlist.add("  vz  ", "dividend payer");

        assertThat(entry.getId()).isNotNull();
        assertThat(entry.getTicker()).isEqualTo("VZ");
        assertThat(entry.getNote()).isEqualTo("dividend payer");
        assertThat(entry.getDateAdded()).isEqualTo(LocalDate.now());
    }

    @Test
    @DisplayName("adding a ticker twice is a no-op, not an error")
    void addingATickerTwiceIsIdempotent() {
        WatchlistEntry first = watchlist.add("VZ", "note");
        WatchlistEntry second = watchlist.add("vz", "different note");

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(watchlist.findAll()).hasSize(1);
        assertThat(second.getNote()).isEqualTo("note");   // the original note survives
    }

    @Test
    void listsAlphabetically() {
        watchlist.add("VZ", null);
        watchlist.add("AAPL", null);
        watchlist.add("MSFT", null);

        assertThat(watchlist.tickers()).containsExactly("AAPL", "MSFT", "VZ");
    }

    @Test
    void bulkAddSkipsDuplicates() {
        watchlist.add("VZ", null);
        watchlist.addAll(List.of("VZ", "CARR", "AAPL"));

        assertThat(watchlist.tickers()).containsExactly("AAPL", "CARR", "VZ");
    }

    @Test
    void updatesTheNote() {
        WatchlistEntry entry = watchlist.add("VZ", null);

        assertThat(watchlist.updateNote(entry.getId(), "slow mover").getNote()).isEqualTo("slow mover");
    }

    @Test
    void removesById() {
        WatchlistEntry entry = watchlist.add("VZ", null);
        watchlist.remove(entry.getId());

        assertThat(watchlist.findAll()).isEmpty();
    }

    @Test
    void removesByTicker() {
        watchlist.add("VZ", null);
        watchlist.removeByTicker("vz");

        assertThat(watchlist.findAll()).isEmpty();
    }

    @Test
    void unknownEntriesFailClearly() {
        assertThatThrownBy(() -> watchlist.findById(9999L))
                .isInstanceOf(WatchlistEntryNotFoundException.class)
                .hasMessageContaining("9999");

        assertThatThrownBy(() -> watchlist.removeByTicker("NOPE"))
                .isInstanceOf(WatchlistEntryNotFoundException.class)
                .hasMessageContaining("not on the watchlist");
    }

    @Test
    void aBlankTickerIsRejected() {
        assertThatThrownBy(() -> watchlist.add("   ", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ticker is required");

        assertThatThrownBy(() -> watchlist.add(null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
