package com.swingscope.web.journal;

import com.swingscope.domain.journal.SetupType;
import com.swingscope.domain.journal.TradeJournalEntry;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;

/** Request bodies for {@code /api/journal}. */
public final class JournalRequests {

    private JournalRequests() {
    }

    /** Create or replace the plan side of an entry. */
    public record CreateEntry(
            @NotBlank String ticker,
            SetupType setupType,
            @NotNull @Positive BigDecimal entry,
            @NotNull @Positive BigDecimal stop,
            @NotNull @Positive BigDecimal target,
            BigDecimal ratio,
            @PositiveOrZero int shares,
            BigDecimal riskAmount,
            String lessonText,
            Boolean rulesFollowed
    ) {
        public TradeJournalEntry toEntity() {
            TradeJournalEntry created = new TradeJournalEntry(
                    ticker.trim().toUpperCase(), setupType, entry, stop, target, ratio, shares, riskAmount);
            created.setLessonText(lessonText);
            created.setRulesFollowed(rulesFollowed);
            return created;
        }
    }

    /** A setup the rules refused. {@code reason} is stored as the lesson. */
    public record RejectEntry(
            @NotBlank String ticker,
            SetupType setupType,
            @NotNull @Positive BigDecimal entry,
            @NotNull @Positive BigDecimal stop,
            @NotNull @Positive BigDecimal target,
            BigDecimal ratio,
            @PositiveOrZero int shares,
            BigDecimal riskAmount,
            String reason
    ) {
        public TradeJournalEntry toEntity() {
            return TradeJournalEntry.rejected(ticker.trim().toUpperCase(), setupType,
                    entry, stop, target, ratio, shares, riskAmount, reason);
        }
    }

    public record FillRequest(@NotNull @Positive BigDecimal fillPrice, Integer actualShares) {
    }

    /** Closing requires the lesson and the rules answer — that is the point of the journal. */
    public record CloseRequest(
            @NotNull @Positive BigDecimal exitPrice,
            @NotBlank String lessonText,
            @NotNull Boolean rulesFollowed
    ) {
    }
}
