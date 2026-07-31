package com.swingscope.web;

import com.swingscope.domain.TradeSetup;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/**
 * Mutable form backing bean for the Thymeleaf calculator.
 *
 * <p>{@link TradeSetup} is an immutable record, which Spring cannot re-populate when a submission
 * fails validation and the form has to be redisplayed with the user's own input still in it. This
 * bean holds the raw form state; {@link #toSetup()} converts it once it is known to be valid.
 */
public class TradeSetupForm {

    @NotBlank(message = "ticker is required")
    private String ticker;

    @NotNull(message = "entry is required")
    @Positive(message = "entry must be greater than 0")
    private BigDecimal entry;

    @NotNull(message = "stop is required")
    @Positive(message = "stop must be greater than 0")
    private BigDecimal stop;

    @NotNull(message = "target is required")
    @Positive(message = "target must be greater than 0")
    private BigDecimal target;

    @NotNull(message = "account size is required")
    @Positive(message = "account size must be greater than 0")
    private BigDecimal accountSize;

    /**
     * What the level engine proposed, carried through the round-trip so the journal can record
     * whether the human accepted it, changed it, or never had a suggestion at all (Phase 6.6).
     * Never used in the sizing math.
     */
    private BigDecimal suggestedStop;

    private BigDecimal suggestedTarget;

    @NotNull(message = "risk $ is required")
    @Positive(message = "risk $ must be greater than 0")
    private BigDecimal riskAmount;

    public TradeSetup toSetup() {
        return new TradeSetup(ticker.trim().toUpperCase(), entry, stop, target, accountSize, riskAmount);
    }

    public String getTicker() {
        return ticker;
    }

    public void setTicker(String ticker) {
        this.ticker = ticker;
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

    public BigDecimal getAccountSize() {
        return accountSize;
    }

    public void setAccountSize(BigDecimal accountSize) {
        this.accountSize = accountSize;
    }

    public BigDecimal getSuggestedStop() {
        return suggestedStop;
    }

    public void setSuggestedStop(BigDecimal suggestedStop) {
        this.suggestedStop = suggestedStop;
    }

    public BigDecimal getSuggestedTarget() {
        return suggestedTarget;
    }

    public void setSuggestedTarget(BigDecimal suggestedTarget) {
        this.suggestedTarget = suggestedTarget;
    }

    public BigDecimal getRiskAmount() {
        return riskAmount;
    }

    public void setRiskAmount(BigDecimal riskAmount) {
        this.riskAmount = riskAmount;
    }
}
