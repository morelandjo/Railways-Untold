package com.vodmordia.railwaysuntold.worldgen.placement.decision;

import com.vodmordia.railwaysuntold.worldgen.placement.PlacementDecision;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Characterizes {@link PlacementPipeline}'s pure orchestration: rules are consulted in ascending priority
 * order until one returns a decision (first-present wins, the rest are skipped), and an exhausted pipeline
 * defers. DEFER/INVALID decisions pass straight through without log-context enrichment - the load-bearing
 * guard that lets the pipeline run without touching the (here null) world context for those types.
 *
 * The real rules are deeply world-bound; the pipeline's ordering/short-circuit logic is not, so it is
 * pinned here with fake rules that return canned decisions and record whether they were consulted.
 */
class PlacementPipelineTest {

    /** A fake rule that records each consultation into a shared log and returns a fixed result. */
    private static final class RecordingRule implements PlacementRule {
        private final String name;
        private final int priority;
        private final Optional<PlacementDecision> result;
        private final List<String> consultLog;

        RecordingRule(String name, int priority, Optional<PlacementDecision> result, List<String> consultLog) {
            this.name = name;
            this.priority = priority;
            this.result = result;
            this.consultLog = consultLog;
        }

        @Override
        public Optional<PlacementDecision> decide(DeciderContext context) {
            consultLog.add(name);
            return result;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public int getPriority() {
            return priority;
        }
    }

    @Test
    void consultsRulesInAscendingPriorityOrderRegardlessOfInsertionOrder() {
        List<String> consulted = new ArrayList<>();
        // Both abstain so every rule is consulted; add them out of priority order.
        PlacementPipeline pipeline = PlacementPipeline.builder()
                .add(new RecordingRule("high", 90, Optional.empty(), consulted))
                .add(new RecordingRule("low", 10, Optional.empty(), consulted))
                .build();

        pipeline.execute(null);

        assertEquals(List.of("low", "high"), consulted, "rules must run in ascending priority order");
    }

    @Test
    void returnsTheFirstPresentDecisionAndShortCircuitsTheRest() {
        List<String> consulted = new ArrayList<>();
        PlacementDecision handled = PlacementDecision.invalid();
        PlacementPipeline pipeline = PlacementPipeline.builder()
                .add(new RecordingRule("first", 10, Optional.of(handled), consulted))
                .add(new RecordingRule("second", 20, Optional.empty(), consulted))
                .build();

        PlacementDecision result = pipeline.execute(null);

        // INVALID passes through attachLogContext untouched, so it is the very same instance.
        assertSame(handled, result, "the first present decision must be returned");
        assertEquals(List.of("first"), consulted, "later rules must not be consulted once one handles");
        assertFalse(consulted.contains("second"));
    }

    @Test
    void defersWhenEveryRuleAbstains() {
        PlacementPipeline pipeline = PlacementPipeline.builder()
                .add(new RecordingRule("a", 10, Optional.empty(), new ArrayList<>()))
                .add(new RecordingRule("b", 20, Optional.empty(), new ArrayList<>()))
                .build();

        PlacementDecision result = pipeline.execute(null);

        assertEquals(PlacementDecision.Type.DEFER, result.getType(),
                "an exhausted pipeline defers rather than returning null");
    }

    @Test
    void deferAndInvalidDecisionsPassThroughWithoutTouchingTheWorldContext() {
        // The log-context guard skips enrichment for DEFER/INVALID; with a null context that is the only
        // reason execute() does not NPE here - this pins that guard.
        PlacementDecision invalid = PlacementDecision.invalid();
        PlacementDecision defer = PlacementDecision.defer();

        assertSame(invalid, PlacementPipeline.builder()
                .add(new RecordingRule("inv", 10, Optional.of(invalid), new ArrayList<>()))
                .build()
                .execute(null));
        assertSame(defer, PlacementPipeline.builder()
                .add(new RecordingRule("def", 10, Optional.of(defer), new ArrayList<>()))
                .build()
                .execute(null));
        assertTrue(true);
    }
}
