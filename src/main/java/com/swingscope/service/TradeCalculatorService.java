package com.swingscope.service;

import com.swingscope.config.TradingRules;
import com.swingscope.domain.TradeAnalysis;
import com.swingscope.domain.TradeSetup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Pure, deterministic sizing math. No external calls, no opinions — the tool may refuse to size a
 * trade that fails the rules, but it never says "buy this".
 */
@Service
public class TradeCalculatorService {

    private static final Logger log = LoggerFactory.getLogger(TradeCalculatorService.class);

    private static final int MONEY_SCALE = 2;
    private static final int SHARE_SCALE = 4;

    private final TradingRules rules;

    public TradeCalculatorService(TradingRules rules) {
        this.rules = rules;
        log.info("TradeCalculatorService initialised with rules: minRiskReward={}, defaultAccountSize={}, "
                + "defaultRiskAmount={}", rules.minRiskReward(), rules.defaultAccountSize(),
                rules.defaultRiskAmount());
    }

    public TradeAnalysis analyze(TradeSetup setup) {
        log.info("Analyzing setup: ticker={} entry={} stop={} target={} account={} riskAmount={}",
                setup.ticker(), setup.entry(), setup.stop(), setup.target(),
                setup.accountSize(), setup.riskAmount());

        if (setup.stop().compareTo(setup.entry()) >= 0) {
            log.warn("REJECTED {}: stop {} is not below entry {} — long-only tool, no shorting logic",
                    setup.ticker(), setup.stop(), setup.entry());
            return TradeAnalysis.rejected(setup.ticker(), "stop must be below entry");
        }
        if (setup.target().compareTo(setup.entry()) <= 0) {
            log.warn("REJECTED {}: target {} is not above entry {}",
                    setup.ticker(), setup.target(), setup.entry());
            return TradeAnalysis.rejected(setup.ticker(), "target must be above entry");
        }

        BigDecimal riskPerShare = money(setup.entry().subtract(setup.stop()));
        BigDecimal rewardPerShare = money(setup.target().subtract(setup.entry()));
        BigDecimal ratio = rewardPerShare.divide(riskPerShare, MONEY_SCALE, RoundingMode.HALF_UP);
        log.debug("{}: riskPerShare={} rewardPerShare={} ratio={} (minimum {})",
                setup.ticker(), riskPerShare, rewardPerShare, ratio, rules.minRiskReward());

        // Risk budget is stated directly in dollars, e.g. $5 on this trade.
        BigDecimal maxRisk = money(setup.riskAmount());
        if (maxRisk.compareTo(setup.accountSize()) > 0) {
            log.warn("{}: risk budget {} exceeds the whole account balance {} — sizing will be capped by cash",
                    setup.ticker(), maxRisk, setup.accountSize());
        }

        BigDecimal idealShares = maxRisk.divide(riskPerShare, SHARE_SCALE, RoundingMode.HALF_UP);
        int riskCappedShares = idealShares.setScale(0, RoundingMode.FLOOR).intValueExact();

        // Cap by cash: the position must fit inside the account.
        int cashCappedShares = setup.accountSize()
                .divide(setup.entry(), 0, RoundingMode.FLOOR)
                .intValueExact();
        int wholeShares = Math.min(riskCappedShares, cashCappedShares);
        log.debug("{}: maxRisk={} idealShares={} riskCapped={} cashCapped={} -> wholeShares={}",
                setup.ticker(), maxRisk, idealShares, riskCappedShares, cashCappedShares, wholeShares);

        if (cashCappedShares < riskCappedShares) {
            log.info("{}: size limited by available cash ({} shares) rather than the risk budget ({} shares)",
                    setup.ticker(), cashCappedShares, riskCappedShares);
        }

        BigDecimal shares = BigDecimal.valueOf(wholeShares);
        BigDecimal totalRisk = money(shares.multiply(riskPerShare));
        BigDecimal positionCost = money(shares.multiply(setup.entry()));
        BigDecimal cashLeft = money(setup.accountSize().subtract(positionCost));

        boolean ratioOk = ratio.compareTo(rules.minRiskReward()) >= 0;
        boolean sizeOk = wholeShares >= 1;
        boolean pass = ratioOk && sizeOk;
        String reason = reason(ratioOk, sizeOk, ratio, cashCappedShares, riskCappedShares);

        log.info("Analysis complete for {}: verdict={} ratio={} shares={} totalRisk={} positionCost={} "
                        + "cashLeft={} reason=\"{}\"",
                setup.ticker(), pass ? "PASS" : "FAIL", ratio, wholeShares,
                totalRisk, positionCost, cashLeft, reason);

        return new TradeAnalysis(
                setup.ticker(), riskPerShare, rewardPerShare, ratio,
                idealShares, wholeShares, totalRisk, positionCost, cashLeft,
                pass, reason);
    }

    private String reason(boolean ratioOk, boolean sizeOk, BigDecimal ratio,
                          int cashCappedShares, int riskCappedShares) {
        if (!ratioOk && !sizeOk) {
            return "ratio %s < %s, and position size is 0 shares".formatted(ratio, rules.minRiskReward());
        }
        if (!ratioOk) {
            return "ratio %s < %s".formatted(ratio, rules.minRiskReward());
        }
        if (!sizeOk) {
            return cashCappedShares == 0
                    ? "position size is 0 shares — entry price exceeds the account balance"
                    : "position size is 0 shares — risk per share exceeds the risk budget";
        }
        if (cashCappedShares < riskCappedShares) {
            return "PASS (size capped by available cash, not by the risk budget)";
        }
        return "PASS";
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }
}
