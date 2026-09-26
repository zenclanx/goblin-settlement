package dev.local.goblinsettlement.colony;

import java.util.List;

/** Standalone checks runnable with the JDK while Fabric dependencies are unavailable. */
public final class ProfessionRulesCheck {
    public static void main(String[] args) {
        check(ProfessionRules.matchRank(WorkKind.FARMING, Profession.FARMER) == 0,
                "a matching specialist ranks first");
        check(ProfessionRules.matchRank(WorkKind.FARMING, Profession.MINER)
                > ProfessionRules.matchRank(WorkKind.FARMING, Profession.UNASSIGNED),
                "a mismatched specialist ranks behind an unassigned generalist");
        check(ProfessionRules.matchRank(WorkKind.FARMING, Profession.SENTRY)
                == ProfessionRules.matchRank(WorkKind.FARMING, Profession.UNASSIGNED),
                "a profession with no work of its own is a generalist, not a mismatch");
        check(ProfessionRules.workIntervalTicks(WorkKind.FARMING, Profession.FARMER) == 10,
                "a matching specialist works at the baseline pace");
        check(ProfessionRules.workIntervalTicks(WorkKind.FARMING, Profession.UNASSIGNED)
                > ProfessionRules.workIntervalTicks(WorkKind.FARMING, Profession.FARMER),
                "a generalist works slower than a specialist");
        check(ProfessionRules.workIntervalTicks(WorkKind.FARMING, Profession.MINER)
                > ProfessionRules.workIntervalTicks(WorkKind.FARMING, Profession.UNASSIGNED),
                "a mismatched specialist works slowest");
        check(WorkKind.employs(Profession.SENTRY) == false, "sentry has no work kind yet");
        check(WorkKind.employs(Profession.FARMER), "farmer is employed by farming work");
        check(ProfessionRules.scarcest(List.of()) == Profession.FARMER,
                "an empty roster starts from the first profession");
        check(ProfessionRules.scarcest(List.of(Profession.FARMER, Profession.FARMER))
                == Profession.FORESTER, "the fewest-staffed profession wins");
        check(ProfessionRules.scarcest(List.of(Profession.FORESTER, Profession.MINER))
                == Profession.FARMER, "ties fall back to enum order");
        System.out.println("ProfessionRulesCheck passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
