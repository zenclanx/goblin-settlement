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
        // Patrol gave the sentry a trade of its own, so "no work kind of its own means generalist" no
        // longer applies to any profession: pulled onto farm work the sentry is a specialist away from
        // its trade, and ranks behind an unassigned generalist.
        check(ProfessionRules.matchRank(WorkKind.FARMING, Profession.SENTRY)
                > ProfessionRules.matchRank(WorkKind.FARMING, Profession.UNASSIGNED),
                "a sentry pulled onto farm work ranks behind a generalist");
        check(ProfessionRules.matchRank(WorkKind.PATROL, Profession.SENTRY) == 0,
                "the sentry matches patrol work");
        check(ProfessionRules.workIntervalTicks(WorkKind.FARMING, Profession.FARMER) == 10,
                "a matching specialist works at the baseline pace");
        check(ProfessionRules.workIntervalTicks(WorkKind.FARMING, Profession.UNASSIGNED)
                > ProfessionRules.workIntervalTicks(WorkKind.FARMING, Profession.FARMER),
                "a generalist works slower than a specialist");
        check(ProfessionRules.workIntervalTicks(WorkKind.FARMING, Profession.MINER)
                > ProfessionRules.workIntervalTicks(WorkKind.FARMING, Profession.UNASSIGNED),
                "a mismatched specialist works slowest");
        check(WorkKind.employs(Profession.SENTRY), "the sentry now has patrol work");
        check(WorkKind.employs(Profession.FARMER), "farmer is employed by farming work");
        check(ProfessionRules.scarcest(List.of(), 8).orElseThrow() == Profession.FARMER,
                "an empty roster starts from the first profession");
        check(ProfessionRules.scarcest(List.of(Profession.FARMER, Profession.FARMER), 8).orElseThrow()
                == Profession.FORESTER, "the fewest-staffed profession wins");
        check(ProfessionRules.scarcest(List.of(Profession.FORESTER, Profession.MINER), 8).orElseThrow()
                == Profession.FARMER, "ties fall back to enum order");
        checkSentryCeiling();
        checkScarcestSkipsSentryAtItsCeiling();
        checkScarcestPicksSentryBelowItsCeiling();
        System.out.println("ProfessionRulesCheck passed");
    }

    /**
     * GAME_DESIGN asks for roughly one sentry per twelve adults, at most four. Every other trade has
     * no written ceiling, so it stays uncapped rather than getting an invented number.
     */
    private static void checkSentryCeiling() {
        check(ProfessionRules.ceiling(Profession.SENTRY, 0) == 0, "no adults, no sentry");
        check(ProfessionRules.ceiling(Profession.SENTRY, 11) == 0, "eleven adults still allow none");
        check(ProfessionRules.ceiling(Profession.SENTRY, 12) == 1, "twelve adults allow the first");
        check(ProfessionRules.ceiling(Profession.SENTRY, 23) == 1, "twenty-three allow one");
        check(ProfessionRules.ceiling(Profession.SENTRY, 24) == 2, "twenty-four allow two");
        check(ProfessionRules.ceiling(Profession.SENTRY, 48) == 4, "forty-eight allow four");
        check(ProfessionRules.ceiling(Profession.SENTRY, 120) == 4, "the ceiling stops at four");
        check(ProfessionRules.ceiling(Profession.FARMER, 120) == Integer.MAX_VALUE,
                "a trade with no written ceiling stays uncapped");
    }

    private static void checkScarcestSkipsSentryAtItsCeiling() {
        List<Profession> roster = SENTRY_LIGHT_ROSTER;
        check(ProfessionRules.ceiling(Profession.SENTRY, roster.size()) == 1,
                "this roster is at the sentry ceiling");
        check(ProfessionRules.scarcest(roster, roster.size()).orElseThrow() == Profession.FARMER,
                "a sentry at its ceiling is skipped in favour of the next fewest");
    }

    /**
     * The same roster at a larger population: twenty-four adults allow two sentries, so the single
     * sentry is below its ceiling and wins on the fewest holders. Pairing this with the check above
     * pins the ceiling itself as what decides the outcome -- the roster does not change between them.
     */
    private static void checkScarcestPicksSentryBelowItsCeiling() {
        check(ProfessionRules.ceiling(Profession.SENTRY, 24) == 2,
                "twenty-four adults allow a second sentry");
        check(ProfessionRules.scarcest(SENTRY_LIGHT_ROSTER, 24).orElseThrow() == Profession.SENTRY,
                "a sentry below its ceiling wins on the fewest holders");
    }

    /**
     * One sentry and two of every other trade. The sentry holds the fewest, so it only loses when a
     * ceiling rules it out.
     */
    private static final List<Profession> SENTRY_LIGHT_ROSTER = List.of(Profession.SENTRY,
            Profession.FARMER, Profession.FARMER,
            Profession.FORESTER, Profession.FORESTER,
            Profession.MINER, Profession.MINER,
            Profession.BUILDER, Profession.BUILDER,
            Profession.HAULER, Profession.HAULER,
            Profession.ARTISAN, Profession.ARTISAN);

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
