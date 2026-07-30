package com.swingscope.web.journal;

import com.swingscope.domain.journal.SetupType;
import com.swingscope.domain.journal.TradeJournalEntry;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;

/** Mutable backing bean for the journal create/edit form, so a failed submit redisplays intact. */
public class JournalEntryForm {

    @NotBlank(message = "ticker is required")
    private String ticker;

    private SetupType setupType = SetupType.OTHER;

    @NotNull(message = "entry is required")
    @Positive(message = "entry must be greater than 0")
    private BigDecimal entry;

    @NotNull(message = "stop is required")
    @Positive(message = "stop must be greater than 0")
    private BigDecimal stop;

    @NotNull(message = "target is required")
    @Positive(message = "target must be greater than 0")
    private BigDecimal target;

    private BigDecimal ratio;

    @PositiveOrZero(message = "shares cannot be negative")
    private int shares;

    private BigDecimal riskAmount;

    private String lessonText;

    private Boolean rulesFollowed;

    public static JournalEntryForm of(TradeJournalEntry entry) {
        JournalEntryForm form = new JournalEntryForm();
        form.ticker = entry.getTicker();
        form.setupType = entry.getSetupType();
        form.entry = entry.getEntry();
        form.stop = entry.getStop();
        form.target = entry.getTarget();
        form.ratio = entry.getRatio();
        form.shares = entry.getShares();
        form.riskAmount = entry.getRiskAmount();
        form.lessonText = entry.getLessonText();
        form.rulesFollowed = entry.getRulesFollowed();
        return form;
    }

    public TradeJournalEntry toEntity() {
        TradeJournalEntry created = new TradeJournalEntry(
                ticker.trim().toUpperCase(), setupType, entry, stop, target, ratio, shares, riskAmount);
        created.setLessonText(lessonText);
        created.setRulesFollowed(rulesFollowed);
        return created;
    }

    public String getTicker() {
        return ticker;
    }

    public void setTicker(String ticker) {
        this.ticker = ticker;
    }

    public SetupType getSetupType() {
        return setupType;
    }

    public void setSetupType(SetupType setupType) {
        this.setupType = setupType;
    }

    public BigDecimal getEntry() {
        return entry;
    }

    public void setEntry(BigDecimal entry) {
        this.entry = entry;
    }

    public BigDecimal getStop() {
        return stop;
    }

    public void setStop(BigDecimal stop) {
        this.stop = stop;
    }

    public BigDecimal getTarget() {
        return target;
    }

    public void setTarget(BigDecimal target) {
        this.target = target;
    }

    public BigDecimal getRatio() {
        return ratio;
    }

    public void setRatio(BigDecimal ratio) {
        this.ratio = ratio;
    }

    public int getShares() {
        return shares;
    }

    public void setShares(int shares) {
        this.shares = shares;
    }

    public BigDecimal getRiskAmount() {
        return riskAmount;
    }

    public void setRiskAmount(BigDecimal riskAmount) {
        this.riskAmount = riskAmount;
    }

    public String getLessonText() {
        return lessonText;
    }

    public void setLessonText(String lessonText) {
        this.lessonText = lessonText;
    }

    public Boolean getRulesFollowed() {
        return rulesFollowed;
    }

    public void setRulesFollowed(Boolean rulesFollowed) {
        this.rulesFollowed = rulesFollowed;
    }
}
