package com.swingscope.service.candidate;

import com.swingscope.config.AnalysisProperties;
import com.swingscope.config.TradingRules;
import com.swingscope.domain.TradeAnalysis;
import com.swingscope.domain.TradeSetup;
import com.swingscope.domain.candidate.AnalysisConfidence;
import com.swingscope.domain.candidate.CandidateAnalysis;
import com.swingscope.domain.candidate.CandidateRow;
import com.swingscope.domain.candidate.CandidateVerdict;
import com.swingscope.domain.levels.LevelAnalysis;
import com.swingscope.domain.levels.PriceZone;
import com.swingscope.domain.scan.ScanResult;
import com.swingscope.domain.scan.TieredStock;
import com.swingscope.service.TradeCalculatorService;
import com.swingscope.service.levels.LevelSuggestionService;
import com.swingscope.service.marketdata.MarketDataException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Runs the whole plan-and-analyse chain for one scanned candidate (Phase 8).
 *
 * <p>Composes three services that already exist and costs <strong>no extra provider call</strong> —
 * the candles were fetched during tiering and are cached.
 *
 * <p>Nothing here decides whether to trade. A PASS means "this clears the rules you set"; a tier
 * means "worth your chart time". Both are statements about arithmetic, not about the company.
 */
@Service
public class CandidateAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(CandidateAnalysisService.class);

    /** A ratio this far past the minimum is comfortable rather than borderline. */
    private static final BigDecimal COMFORTABLE_RATIO_MULTIPLE = new BigDecimal("1.25");
    private static final int COMFORTABLE_SHARES = 3;
    private static final int RECENT_BARS = 60;
    private static final int DEEP_HISTORY_BARS = 200;

    private final LevelSuggestionService levels;
    private final TradeCalculatorService calculator;
    private final AnalysisProperties properties;
    private final TradingRules rules;

    public CandidateAnalysisService(LevelSuggestionService levels, TradeCalculatorService calculator,
                                    AnalysisProperties properties, TradingRules rules) {
        this.levels = levels;
        this.calculator = calculator;
        this.properties = properties;
        this.rules = rules;
    }

    /**
     * Analyses every tradeable candidate in a scan, best-founded first.
     *
     * <p>Costs no provider call — the candles were fetched during tiering and are cached. Tier 3 and
     * Skip are left alone: they are not candidates, and analysing them would imply otherwise.
     *
     * <p>Lives here rather than in the scan job so the JSON API and the UI cannot drift apart. They
     * did briefly: the page showed sized candidates while {@code /api/scan} returned an empty list,
     * which reads as "nothing qualified" rather than "this path never asked".
     */
    public ScanResult analyseAll(ScanResult result) {
        if (!properties.autoAnalyse()) {
            return result;
        }
        List<CandidateRow> analysed = result.tradeable().stream()
                .map(this::analyse)
                .map(CandidateRow::from)
                .sorted(Comparator.comparingInt(CandidateRow::confidenceMet).reversed()
                        .thenComparing(c -> c.verdict().ordinal()))
                .toList();

        log.info("Auto-analysed {} tradeable candidate(s): {} pass, {} fail, {} need levels",
                analysed.size(),
                analysed.stream().filter(c -> c.verdict() == CandidateVerdict.PASS).count(),
                analysed.stream().filter(c -> c.verdict() == CandidateVerdict.FAIL).count(),
                analysed.stream().filter(c -> c.verdict() == CandidateVerdict.NEEDS_LEVELS).count());
        return result.withCandidates(analysed);
    }

    /** Analyses one candidate. A data failure degrades to NEEDS_LEVELS rather than killing the scan. */
    public CandidateAnalysis analyse(TieredStock stock) {
        try {
            return doAnalyse(stock);
        } catch (MarketDataException e) {
            log.warn("Could not auto-analyse {} — {}", stock.symbol(), e.getMessage());
            return needsLevels(stock, null,
                    List.of("market data unavailable: " + e.getMessage(),
                            "set entry, stop and target yourself"));
        }
    }

    private CandidateAnalysis doAnalyse(TieredStock stock) {
        LevelAnalysis levelAnalysis = levels.suggest(stock.symbol());

        if (!levelAnalysis.isComplete() || stock.price() == null) {
            return needsLevels(stock, levelAnalysis, missingInputs(levelAnalysis, stock));
        }

        TradeSetup setup = new TradeSetup(stock.symbol(), stock.price(),
                levelAnalysis.stop().value(), levelAnalysis.target().value(),
                properties.accountSize(), properties.riskAmount());
        TradeAnalysis analysis = calculator.analyze(setup);

        AnalysisConfidence confidence = confidenceFor(stock, levelAnalysis, analysis);
        CandidateVerdict verdict = analysis.pass() ? CandidateVerdict.PASS : CandidateVerdict.FAIL;

        log.debug("{}: {} ratio={} shares={} confidence={} ({} of {})",
                stock.symbol(), verdict, analysis.ratio(), analysis.wholeShares(),
                confidence.grade(), confidence.met(), confidence.total());

        return new CandidateAnalysis(stock, levelAnalysis, analysis, confidence, verdict, List.of());
    }

    /** The row is kept, saying exactly why and what the human must supply. */
    private CandidateAnalysis needsLevels(TieredStock stock, LevelAnalysis levelAnalysis,
                                          List<String> needed) {
        AnalysisConfidence confidence = levelAnalysis == null
                ? AnalysisConfidence.of(List.of())
                : confidenceFor(stock, levelAnalysis, null);
        return new CandidateAnalysis(stock, levelAnalysis, null, confidence,
                CandidateVerdict.NEEDS_LEVELS, needed);
    }

    /** The refusal reason verbatim, plus the specific field the human has to fill. */
    private static List<String> missingInputs(LevelAnalysis levels, TieredStock stock) {
        List<String> needed = new ArrayList<>();
        if (stock.price() == null) {
            needed.add("no current price — set the entry yourself");
        }
        if (levels != null && !levels.stop().isPresent()) {
            needed.add("stop: " + levels.stop().rationale());
        }
        if (levels != null && !levels.target().isPresent()) {
            needed.add("target: " + levels.target().rationale());
        }
        if (needed.isEmpty()) {
            needed.add("levels unavailable — set stop and target yourself");
        }
        return needed;
    }

    // ------------------------------------------------------------------------------- confidence

    /**
     * Six facts about the derivation. Explicitly not a probability — see {@link AnalysisConfidence}.
     */
    AnalysisConfidence confidenceFor(TieredStock stock, LevelAnalysis levels, TradeAnalysis analysis) {
        List<AnalysisConfidence.Factor> factors = new ArrayList<>();

        int bars = levels == null ? 0 : levels.barsAnalyzed();
        boolean deepHistory = bars >= DEEP_HISTORY_BARS && stock.inUptrend() != null;
        factors.add(new AnalysisConfidence.Factor("Data depth", deepHistory,
                bars + " bars" + (stock.inUptrend() == null ? ", EMA200 unavailable" : "")));

        boolean structural = levels != null
                && levels.stop().isPresent() && !levels.stop().isFallback()
                && levels.target().isPresent() && !levels.target().isFallback();
        factors.add(new AnalysisConfidence.Factor("Level derivation", structural,
                describeDerivation(levels)));

        PriceZone stopZone = levels == null || levels.stop().zone() == null ? null : levels.stop().zone();
        boolean strongZone = stopZone != null && stopZone.touches() >= 3
                && stopZone.barsSinceLastTouch() <= RECENT_BARS;
        factors.add(new AnalysisConfidence.Factor("Zone strength", strongZone,
                stopZone == null ? "no structural zone"
                        : "%d touches, last tested %d bars ago"
                        .formatted(stopZone.touches(), stopZone.barsSinceLastTouch())));

        boolean complete = stock.marketCapMillions() != null
                && (levels == null || levels.warnings().stream()
                .noneMatch(w -> w.contains("unavailable")));
        factors.add(new AnalysisConfidence.Factor("Data completeness", complete,
                complete ? "market cap and earnings known" : "provider gaps present"));

        boolean comfortableRatio = analysis != null && analysis.ratio()
                .compareTo(rules.minRiskReward().multiply(COMFORTABLE_RATIO_MULTIPLE)) >= 0;
        factors.add(new AnalysisConfidence.Factor("Ratio margin", comfortableRatio,
                analysis == null ? "not computed"
                        : "%s against a %s minimum".formatted(analysis.ratio(), rules.minRiskReward())));

        boolean headroom = analysis != null && analysis.wholeShares() >= COMFORTABLE_SHARES;
        factors.add(new AnalysisConfidence.Factor("Sizing headroom", headroom,
                analysis == null ? "not computed" : analysis.wholeShares() + " shares"));

        return AnalysisConfidence.of(factors);
    }

    private static String describeDerivation(LevelAnalysis levels) {
        if (levels == null) {
            return "no levels";
        }
        if (!levels.stop().isPresent() || !levels.target().isPresent()) {
            return "at least one level refused";
        }
        boolean anyFallback = levels.stop().isFallback() || levels.target().isFallback();
        return anyFallback ? "volatility fallback in use" : "both from price structure";
    }
}
