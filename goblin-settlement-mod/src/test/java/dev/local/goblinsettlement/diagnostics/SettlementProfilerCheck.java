package dev.local.goblinsettlement.diagnostics;

/**
 * Deterministic checks for the settlement profiler. Durations are injected through record(...) rather
 * than measured, so the totals and ordering do not depend on wall-clock timing.
 */
public final class SettlementProfilerCheck {
    public static void main(String[] args) {
        checkDisabledRunExecutesButRecordsNothing();
        checkEnabledAccumulatesCountTotalMax();
        checkEnabledRunRecordsItsCount();
        checkOnClearsPreviousSamples();
        checkOffStopsRecordingForRun();
        checkSamplesSurviveOff();
        checkReportOrdersByTotalThenName();
        checkUnrecordedNameAbsent();
        checkEmptyReportSaysSo();
        System.out.println("SettlementProfilerCheck passed");
    }

    private static void checkDisabledRunExecutesButRecordsNothing() {
        SettlementProfiler.off();
        boolean[] ran = {false};
        SettlementProfiler.run("disabledName", () -> ran[0] = true);
        check(ran[0], "a disabled run still executes the task");
        check(!SettlementProfiler.report().contains("disabledName"),
                "a disabled run records nothing");
    }

    private static void checkEnabledAccumulatesCountTotalMax() {
        SettlementProfiler.on();
        SettlementProfiler.record("alpha", 1_000_000L);
        SettlementProfiler.record("alpha", 3_000_000L);
        SettlementProfiler.record("alpha", 2_000_000L);
        String report = SettlementProfiler.report();
        check(report.contains("alpha: n=3 total=6.000 avg=2.000 max=3.000"),
                "a name accumulates count, total and max across calls");
    }

    private static void checkEnabledRunRecordsItsCount() {
        SettlementProfiler.on();
        SettlementProfiler.run("runName", () -> {
        });
        check(SettlementProfiler.report().contains("runName: n=1"),
                "an enabled run records one sample");
    }

    private static void checkOnClearsPreviousSamples() {
        SettlementProfiler.on();
        SettlementProfiler.record("before", 1_000_000L);
        check(SettlementProfiler.report().contains("before"), "the sample was recorded");
        SettlementProfiler.on();
        SettlementProfiler.record("after", 1_000_000L);
        String report = SettlementProfiler.report();
        check(!report.contains("before"), "on() clears earlier samples");
        check(report.contains("after"), "on() still records afterwards");
    }

    private static void checkOffStopsRecordingForRun() {
        SettlementProfiler.on();
        SettlementProfiler.off();
        boolean[] ran = {false};
        SettlementProfiler.run("afterOff", () -> ran[0] = true);
        check(ran[0], "off() still runs the task");
        check(!SettlementProfiler.report().contains("afterOff"),
                "off() stops run from recording");
    }

    private static void checkSamplesSurviveOff() {
        // The real workflow: collect, stop, then read. off() must freeze, not discard.
        SettlementProfiler.on();
        SettlementProfiler.record("kept", 1_000_000L);
        SettlementProfiler.record("kept", 3_000_000L);
        SettlementProfiler.off();
        String report = SettlementProfiler.report();
        check(report.contains("kept: n=2 total=4.000 avg=2.000 max=3.000"),
                "samples survive off() and stay readable");
    }

    private static void checkReportOrdersByTotalThenName() {
        SettlementProfiler.on();
        SettlementProfiler.record("bravo", 1_000_000L);
        SettlementProfiler.record("alpha", 1_000_000L);
        SettlementProfiler.record("charlie", 2_000_000L);
        String report = SettlementProfiler.report();
        int charlie = report.indexOf("charlie: n=");
        int alpha = report.indexOf("alpha: n=");
        int bravo = report.indexOf("bravo: n=");
        check(charlie >= 0 && alpha >= 0 && bravo >= 0, "all three names appear");
        check(charlie < alpha, "the largest total comes first");
        check(alpha < bravo, "a tie breaks by name ascending");
    }

    private static void checkUnrecordedNameAbsent() {
        SettlementProfiler.on();
        SettlementProfiler.record("present", 1_000_000L);
        check(!SettlementProfiler.report().contains("zeta: n="),
                "a name never recorded is not in the report");
    }

    private static void checkEmptyReportSaysSo() {
        SettlementProfiler.on();
        SettlementProfiler.off();
        check(SettlementProfiler.report().equals("Profiler: no samples recorded"),
                "an empty report says there are no samples");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
