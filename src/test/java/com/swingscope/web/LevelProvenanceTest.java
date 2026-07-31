package com.swingscope.web;

import com.swingscope.domain.journal.LevelSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 6.6 — the field that makes the whole level feature evaluable. If provenance is recorded
 * wrongly, the eventual "are suggested levels better than mine?" comparison is worthless.
 */
class LevelProvenanceTest {

    private static BigDecimal d(String value) {
        return new BigDecimal(value);
    }

    @Test
    @DisplayName("no suggestion offered → HUMAN")
    void noSuggestionIsHuman() {
        assertThat(CalculatorController.provenanceOf(d("39.00"), d("43.60"), null, null))
                .isEqualTo(LevelSource.HUMAN);
    }

    @Test
    @DisplayName("both taken exactly as proposed → SUGGESTED")
    void untouchedIsSuggested() {
        assertThat(CalculatorController.provenanceOf(d("39.00"), d("43.60"), d("39.00"), d("43.60")))
                .isEqualTo(LevelSource.SUGGESTED);
    }

    @Test
    @DisplayName("a one-cent tweak is EDITED — adjusting a level is a decision")
    void anyChangeIsEdited() {
        assertThat(CalculatorController.provenanceOf(d("39.01"), d("43.60"), d("39.00"), d("43.60")))
                .isEqualTo(LevelSource.EDITED);
        assertThat(CalculatorController.provenanceOf(d("39.00"), d("43.59"), d("39.00"), d("43.60")))
                .isEqualTo(LevelSource.EDITED);
    }

    @Test
    @DisplayName("trailing zeros are the same number, not an edit")
    void scaleDifferencesAreNotEdits() {
        assertThat(CalculatorController.provenanceOf(d("39.0"), d("43.600"), d("39.00"), d("43.60")))
                .isEqualTo(LevelSource.SUGGESTED);
    }

    @Test
    @DisplayName("one level suggested, the other typed → still SUGGESTED if the suggested one stands")
    void aPartialSuggestionCountsOnWhatWasActuallyProposed() {
        // Stop was proposed and kept; the target was refused, so the human's target is not an edit.
        assertThat(CalculatorController.provenanceOf(d("39.00"), d("50.00"), d("39.00"), null))
                .isEqualTo(LevelSource.SUGGESTED);

        // Stop was proposed and overridden.
        assertThat(CalculatorController.provenanceOf(d("38.00"), d("50.00"), d("39.00"), null))
                .isEqualTo(LevelSource.EDITED);
    }

    @Test
    @DisplayName("clearing a suggested level counts as an edit, not as never having had one")
    void clearingASuggestionIsAnEdit() {
        assertThat(CalculatorController.provenanceOf(null, d("43.60"), d("39.00"), d("43.60")))
                .isEqualTo(LevelSource.EDITED);
    }
}
