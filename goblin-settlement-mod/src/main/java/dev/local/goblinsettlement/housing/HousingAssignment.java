package dev.local.goblinsettlement.housing;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Who lives where. Pure: it reads coordinates and ids, decides, and never touches the world.
 *
 * A home keeps no list of its residents -- the roster is the one place a home is written down -- so a
 * home's occupancy is counted from the residents that point at it. Everything here derives from that.
 */
public final class HousingAssignment {

    /** A home is identified by its bed: every resident of it points at this coordinate. */
    public record BedKey(int x, int y, int z) { }

    /** A home whose capacity could be read. */
    public record HomeSlot(BedKey bed, int capacity) { }

    /** A living resident, and where it lives now if anywhere. */
    public record ResidentSlot(String id, Optional<BedKey> home) { }

    /** One edit to apply: this resident's new home, or empty to unbind it. */
    public record Change(String residentId, Optional<BedKey> home) { }

    /** Homes are walked in bed order: x, then z, then y -- the order the home scan itself uses. */
    private static final Comparator<BedKey> BED_ORDER = Comparator.comparingInt(BedKey::x)
            .thenComparingInt(BedKey::z).thenComparingInt(BedKey::y);

    private HousingAssignment() {
    }

    /** How many of these residents call this bed their home. */
    public static int occupancy(List<ResidentSlot> residents, BedKey bed) {
        int count = 0;
        for (ResidentSlot resident : residents) {
            if (resident.home().filter(bed::equals).isPresent()) {
                count++;
            }
        }
        return count;
    }

    /**
     * Where everyone should live, as the edits that get them there. Empty when nothing needs to change.
     *
     * A home we cannot judge (its chunk is not ticking, so its capacity is unknowable) takes no part in
     * the assignment, and the residents living in it are left exactly as they are: reading an unloaded
     * home as full would evict people from a house that is perfectly fine.
     */
    public static List<Change> plan(List<HomeSlot> homes, Set<BedKey> unjudged,
                                    List<ResidentSlot> residents) {
        List<HomeSlot> ordered = new ArrayList<>(homes);
        ordered.sort(Comparator.comparing(HomeSlot::bed, BED_ORDER));
        List<ResidentSlot> roster = new ArrayList<>(residents);
        roster.sort(Comparator.comparing(ResidentSlot::id));

        Map<String, BedKey> wanted = new LinkedHashMap<>();
        // Residents of a home we cannot judge keep it; no edit is emitted for them.
        for (ResidentSlot resident : roster) {
            if (resident.home().filter(unjudged::contains).isPresent()) {
                wanted.put(resident.id(), resident.home().orElseThrow());
            }
        }
        // Each judged home keeps its lowest ids, up to its capacity; the rest fall through.
        for (HomeSlot home : ordered) {
            int left = home.capacity();
            for (ResidentSlot resident : roster) {
                if (left <= 0) {
                    break;
                }
                if (resident.home().filter(home.bed()::equals).isPresent()
                        && !wanted.containsKey(resident.id())) {
                    wanted.put(resident.id(), home.bed());
                    left--;
                }
            }
        }
        // Whoever is left has no home we can honour: a vanished one, an overfull one, or none at all.
        for (ResidentSlot resident : roster) {
            if (wanted.containsKey(resident.id())) {
                continue;
            }
            for (HomeSlot home : ordered) {
                if (occupancyOf(wanted, home.bed()) < home.capacity()) {
                    wanted.put(resident.id(), home.bed());
                    break;
                }
            }
        }

        Map<String, Optional<BedKey>> current = new HashMap<>();
        for (ResidentSlot resident : roster) {
            current.put(resident.id(), resident.home());
        }
        List<Change> edits = new ArrayList<>();
        for (ResidentSlot resident : roster) {
            Optional<BedKey> target = Optional.ofNullable(wanted.get(resident.id()));
            if (!target.equals(resident.home())) {
                edits.add(new Change(resident.id(), target));
            }
        }
        // A decided order, so a budgeted pass always lands the same edits first. An edit always has a bed
        // to sort by: either the one it moves to, or the one it leaves.
        edits.sort((left, right) -> {
            int byBed = BED_ORDER.compare(bedFor(left, current), bedFor(right, current));
            return byBed != 0 ? byBed : left.residentId().compareTo(right.residentId());
        });
        return edits;
    }

    private static BedKey bedFor(Change edit, Map<String, Optional<BedKey>> current) {
        return edit.home().or(() -> current.get(edit.residentId())).orElseThrow();
    }

    private static int occupancyOf(Map<String, BedKey> wanted, BedKey bed) {
        int count = 0;
        for (BedKey taken : wanted.values()) {
            if (taken.equals(bed)) {
                count++;
            }
        }
        return count;
    }
}
