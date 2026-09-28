package dev.local.goblinsettlement.housing;

import dev.local.goblinsettlement.colony.ResidentRecord;
import dev.local.goblinsettlement.colony.SettlementSavedData;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * Keeps the roster's home field in step with the homes that exist, one bounded batch at a time.
 *
 * The decision itself is pure (HousingAssignment); this class only reads the world into its inputs and
 * writes the edits back, so nothing here decides who lives where.
 */
public final class HousingAssignmentCoordinator {
    // Invented: nothing in the design gives a cadence for this. Re-choose it together with BUDGET once
    // real settlements have been observed, the way TRAFFIC_PER_LANE and SAMPLE_INTERVAL_TICKS still need.
    private static final int INTERVAL_TICKS = 40;
    // Invented too. The roster is at most 64 long, so a whole pass would fit in one tick; the budget
    // exists to bound the edits per tick and to make the order they land in a decided thing rather than
    // an accident. Re-choose alongside INTERVAL_TICKS.
    private static final int BUDGET = 8;

    private HousingAssignmentCoordinator() {
    }

    public static void tick(ServerLevel level) {
        if (level.getGameTime() % INTERVAL_TICKS != 0) {
            return;
        }
        var data = SettlementSavedData.get(level);
        var settlement = data.settlement();
        if (settlement.isEmpty()) {
            return;
        }
        String id = settlement.get().id();
        var view = view(level, id);
        int applied = 0;
        for (var edit : HousingAssignment.plan(view.judged(), view.unjudged(), roster(data))) {
            if (applied >= BUDGET) {
                break;
            }
            if (data.assignHome(edit.residentId(), edit.home().map(HousingAssignmentCoordinator::pos))) {
                applied++;
            }
        }
    }

    /** How many living residents call this bed their home. Counted from the roster, never stored. */
    public static int occupancy(SettlementSavedData data, BlockPos bed) {
        int count = 0;
        for (var record : data.residents()) {
            if (record.stage() == ResidentRecord.LifeStage.DECEASED) {
                continue;
            }
            if (record.home().filter(bed::equals).isPresent()) {
                count++;
            }
        }
        return count;
    }

    /**
     * Living residents the roster gives no home at all. A resident whose home merely cannot be judged
     * right now is not counted: we do not know that it is homeless, only that we cannot look.
     */
    public static int homeless(ServerLevel level, String id, SettlementSavedData data) {
        Set<HousingAssignment.BedKey> known = new HashSet<>();
        for (var home : HousingSavedData.get(level).homes(id)) {
            // Judged or not, the bed is registered -- either way this resident is not homeless.
            known.add(key(home.bed()));
        }
        int count = 0;
        for (var record : data.residents()) {
            if (record.stage() == ResidentRecord.LifeStage.DECEASED) {
                continue;
            }
            if (record.home().map(HousingAssignmentCoordinator::key).filter(known::contains).isEmpty()) {
                count++;
            }
        }
        return count;
    }

    /** The homes we can read right now, and the beds of those we cannot. */
    public record View(List<HousingAssignment.HomeSlot> judged, Set<HousingAssignment.BedKey> unjudged) { }

    /**
     * Whether this home's capacity can be read right now. The one gate: the planner, the birth test and
     * the status line all ask here rather than repeating the chunk test.
     */
    public static boolean canJudge(ServerLevel level, BlockPos bed) {
        return level.shouldTickBlocksAt(bed);
    }

    /** Every registered home, split into the ones whose capacity we can read and the ones we cannot. */
    public static View view(ServerLevel level, String id) {
        var judged = new ArrayList<HousingAssignment.HomeSlot>();
        var unjudged = new HashSet<HousingAssignment.BedKey>();
        for (var home : HousingSavedData.get(level).homes(id)) {
            var bed = key(home.bed());
            if (canJudge(level, home.bed())) {
                judged.add(new HousingAssignment.HomeSlot(bed, HousingCoordinator.homeCapacity(level, home)));
            } else {
                // Its capacity would read as the air of an unloaded chunk, so it takes no part at all.
                unjudged.add(bed);
            }
        }
        return new View(List.copyOf(judged), Set.copyOf(unjudged));
    }

    /**
     * Whether the settlement has anywhere to put another resident, or empty when nothing can be judged:
     * there are no registered homes at all, or every one of them sits in a chunk we cannot read. Callers
     * fall back to their own rule on empty rather than reading it as "full" -- the opening camp registers
     * no homes, and a hard "no" there would stop it breeding for good.
     */
    public static Optional<Boolean> hasRoomAnywhere(ServerLevel level, String id, SettlementSavedData data) {
        var view = view(level, id);
        if (view.judged().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(HousingAssignment.hasRoom(view.judged(), roster(data)));
    }

    private static List<HousingAssignment.ResidentSlot> roster(SettlementSavedData data) {
        var slots = new ArrayList<HousingAssignment.ResidentSlot>();
        for (var record : data.residents()) {
            if (record.stage() == ResidentRecord.LifeStage.DECEASED) {
                continue;
            }
            slots.add(new HousingAssignment.ResidentSlot(record.id(),
                    record.home().map(HousingAssignmentCoordinator::key)));
        }
        return slots;
    }

    private static HousingAssignment.BedKey key(BlockPos bed) {
        return new HousingAssignment.BedKey(bed.getX(), bed.getY(), bed.getZ());
    }

    private static BlockPos pos(HousingAssignment.BedKey bed) {
        return new BlockPos(bed.x(), bed.y(), bed.z());
    }
}
