package dev.local.goblinsettlement.planning.transport;

/**
 * Pure rules for widening a road once it has carried enough traffic. GAME_DESIGN section 7 gives the
 * widths but no traffic numbers, so this round only judges and reports; the widening that follows the
 * judgement is a later round.
 */
public final class RoadUpgradeRules {
    /** Net walking widths from GAME_DESIGN section 7, narrowest first. */
    private static final int[] LADDER = {2, 3, 5};

    /** The ladder itself, as a copy: geometry that has to agree with the rungs pins itself to this. */
    public static int[] ladder() {
        return LADDER.clone();
    }

    /** The width every road is built at, and the width every plan written before widening is assumed. */
    public static int baseLanes() {
        return LADDER[0];
    }

    /**
     * Sample hits a road needs before a widening is worth it, per lane it already has. INVENTED: the
     * design document names widths but no numbers. This has no source and must be re-chosen once this
     * round has produced real counts -- and it must be re-chosen together with
     * TrafficSampler.SAMPLE_INTERVAL_TICKS, because hits are counted per sample and the interval is
     * therefore half of this judgement (TRAFFIC_UPGRADE_DESIGN section 2).
     */
    public static final int TRAFFIC_PER_LANE = 200;

    private RoadUpgradeRules() {
    }

    /** The next width up the ladder, or the widest width when there is nothing above. */
    public static int nextLanes(int lanes) {
        for (int candidate : LADDER) {
            if (candidate > lanes) {
                return candidate;
            }
        }
        return LADDER[LADDER.length - 1];
    }

    /**
     * Whether a road this wide, with this many traffic samples, has earned a widening. The widest
     * street never upgrades again, a road needs at least one full share, and a wider road always asks
     * for at least as much as a narrower one.
     */
    public static boolean shouldUpgrade(int lanes, int traffic) {
        if (lanes <= 0 || lanes >= LADDER[LADDER.length - 1]) {
            return false;
        }
        return traffic >= TRAFFIC_PER_LANE * Math.max(1, lanes - 1);
    }
}
