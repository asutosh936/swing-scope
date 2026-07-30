package com.swingscope.domain.marketdata;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MarketSnapshotTest {

    private static MarketSnapshot withWarnings(List<String> warnings) {
        return new MarketSnapshot("AAPL", new BigDecimal("214.25"), new BigDecimal("3.00"),
                null, null, null, 1L, 1L, null, null, null, false, false, 0, warnings);
    }

    @Test
    void nullWarningsBecomeAnEmptyListRatherThanAnNpeLaterOn() {
        assertThat(withWarnings(null).warnings()).isEmpty();
    }

    @Test
    void warningsAreDefensivelyCopiedAndImmutable() {
        List<String> mutable = new ArrayList<>(List.of("only 60 daily bars available"));
        MarketSnapshot snapshot = withWarnings(mutable);

        mutable.add("added after construction");

        assertThat(snapshot.warnings()).containsExactly("only 60 daily bars available");
        assertThatThrownBy(() -> snapshot.warnings().add("nope"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
