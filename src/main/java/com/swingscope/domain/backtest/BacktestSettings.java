package com.swingscope.domain.backtest;

/**
 * How a replay is conducted. Separate from {@link com.swingscope.config.LevelProperties}, which
 * governs how levels are computed — these govern how the resulting trade is simulated.
 *
 * @param entryRule    where the fill is assumed to happen
 * @param timeStopBars bars to wait before abandoning the trade. Defaults to 15, matching the
 *                     time-stop already shown in the management-rules panel
 */
public record BacktestSettings(EntryRule entryRule, int timeStopBars) {

    public enum EntryRule {
        /**
         * Fill at the open of the bar <em>after</em> the signal. Honest: you cannot buy the close
         * you are reacting to. Costs some realism to gaps, which is the point.
         */
        NEXT_OPEN,

        /**
         * Fill at the close of the signal bar. Mildly optimistic — it assumes you acted on a price
         * at the instant you observed it — and kept only for comparison against NEXT_OPEN.
         */
        SIGNAL_CLOSE
    }

    public static final int DEFAULT_TIME_STOP_BARS = 15;

    public BacktestSettings {
        if (entryRule == null) {
            entryRule = EntryRule.NEXT_OPEN;
        }
        if (timeStopBars <= 0) {
            timeStopBars = DEFAULT_TIME_STOP_BARS;
        }
    }

    public static BacktestSettings defaults() {
        return new BacktestSettings(EntryRule.NEXT_OPEN, DEFAULT_TIME_STOP_BARS);
    }
}
