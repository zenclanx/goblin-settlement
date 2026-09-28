package dev.local.goblinsettlement.housing;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Standalone assertions for the pure home assignment rule. */
public final class HousingAssignmentCheck {

    public static void main(String[] args) {
        checkHomelessFillHomesInBedOrder();
        checkFullHomesLeaveTheHomelessWhereTheyAre();
        checkAVanishedHomeIsDropped();
        checkAnOverfullHomeKeepsItsLowestIds();
        checkInputOrderDoesNotMatter();
        checkUnjudgedHomesAreLeftAlone();
        checkNothingToDoProducesNothing();
        checkApplyingThePlanIsIdempotent();
        checkOccupancyCountsTheResidentsPointingHere();
        checkMixedBatchOrdering();
        checkHasAnyRoom();
        System.out.println("HousingAssignmentCheck passed");
    }

    private static void checkHomelessFillHomesInBedOrder() {
        var homes = List.of(home(20, 0, 1), home(10, 0, 2));
        var roster = List.of(resident("a", null), resident("b", null), resident("c", null));
        var changes = HousingAssignment.plan(homes, Set.of(), roster);
        require(changes.size() == 3, "every homeless resident is placed");
        require(changes.get(0).residentId().equals("a")
                        && changes.get(0).home().equals(Optional.of(bed(10, 0))),
                "the first home by bed order fills first");
        require(changes.get(1).residentId().equals("b")
                        && changes.get(1).home().equals(Optional.of(bed(10, 0))),
                "and takes the second resident too");
        require(changes.get(2).residentId().equals("c")
                        && changes.get(2).home().equals(Optional.of(bed(20, 0))),
                "the next home only starts once the first is full");
    }

    private static void checkFullHomesLeaveTheHomelessWhereTheyAre() {
        var homes = List.of(home(10, 0, 1));
        var roster = List.of(resident("a", bed(10, 0)), resident("b", null));
        require(HousingAssignment.plan(homes, Set.of(), roster).isEmpty(),
                "a homeless resident stays homeless when every home is full");
    }

    private static void checkAVanishedHomeIsDropped() {
        // The vanished resident is treated as homeless, so it is either moved straight into a home with
        // room (one edit, not an unbind followed by an assign) or, when nothing has room, unbound.
        var homes = List.of(home(10, 0, 1));
        var roster = List.of(resident("a", bed(99, 0)), resident("b", bed(10, 0)));
        var changes = HousingAssignment.plan(homes, Set.of(), roster);
        require(changes.size() == 1 && changes.get(0).residentId().equals("a")
                        && changes.get(0).home().isEmpty(),
                "a home that no longer exists unbinds its resident when nothing has room");

        var roomy = HousingAssignment.plan(List.of(home(10, 0, 2)), Set.of(),
                List.of(resident("a", bed(99, 0))));
        require(roomy.size() == 1 && roomy.get(0).residentId().equals("a")
                        && roomy.get(0).home().equals(Optional.of(bed(10, 0))),
                "and moves straight into a home that has room");
    }

    private static void checkAnOverfullHomeKeepsItsLowestIds() {
        var homes = List.of(home(10, 0, 1));
        var roster = List.of(resident("b", bed(10, 0)), resident("a", bed(10, 0)),
                resident("c", bed(10, 0)));
        var changes = HousingAssignment.plan(homes, Set.of(), roster);
        require(changes.size() == 2, "two of the three are unbound");
        require(changes.stream().noneMatch(change -> change.residentId().equals("a")),
                "the lowest id keeps the home");
        require(changes.stream().allMatch(change -> change.home().isEmpty()),
                "and the others are unbound, since the home is full");
    }

    private static void checkInputOrderDoesNotMatter() {
        var one = HousingAssignment.plan(List.of(home(20, 0, 1), home(10, 0, 2)), Set.of(),
                List.of(resident("c", null), resident("a", null), resident("b", null)));
        var other = HousingAssignment.plan(List.of(home(10, 0, 2), home(20, 0, 1)), Set.of(),
                List.of(resident("a", null), resident("b", null), resident("c", null)));
        require(one.equals(other), "the order the inputs arrive in does not change the outcome");
    }

    private static void checkUnjudgedHomesAreLeftAlone() {
        var homes = List.of(home(10, 0, 1));
        var unknown = bed(30, 0);
        var roster = List.of(resident("a", unknown), resident("b", null));
        var changes = HousingAssignment.plan(homes, Set.of(unknown), roster);
        require(changes.stream().noneMatch(change -> change.residentId().equals("a")),
                "a resident of a home we cannot judge is left alone");
        require(changes.size() == 1 && changes.get(0).residentId().equals("b")
                        && changes.get(0).home().equals(Optional.of(bed(10, 0))),
                "and that home is not offered to anyone else");
    }

    private static void checkNothingToDoProducesNothing() {
        require(HousingAssignment.plan(List.of(home(10, 0, 1)), Set.of(),
                        List.of(resident("a", bed(10, 0)))).isEmpty(),
                "an assignment that already holds produces no edits");
        require(HousingAssignment.plan(List.of(), Set.of(), List.of(resident("a", null))).isEmpty(),
                "with no homes at all nobody can be placed");
    }

    private static void checkApplyingThePlanIsIdempotent() {
        var homes = List.of(home(10, 0, 2), home(20, 0, 1));
        var roster = List.of(resident("a", null), resident("b", bed(20, 0)), resident("c", null));
        var first = HousingAssignment.plan(homes, Set.of(), roster);
        var after = new ArrayList<HousingAssignment.ResidentSlot>();
        for (var resident : roster) {
            Optional<HousingAssignment.BedKey> target = resident.home();
            for (var change : first) {
                if (change.residentId().equals(resident.id())) {
                    target = change.home();
                }
            }
            after.add(new HousingAssignment.ResidentSlot(resident.id(), target));
        }
        require(HousingAssignment.plan(homes, Set.of(), after).isEmpty(),
                "applying the plan leaves nothing left to do");
    }

    private static void checkOccupancyCountsTheResidentsPointingHere() {
        var roster = List.of(resident("a", bed(10, 0)), resident("b", bed(10, 0)),
                resident("c", bed(20, 0)));
        require(HousingAssignment.occupancy(roster, bed(10, 0)) == 2,
                "occupancy counts the residents pointing at this bed");
        require(HousingAssignment.occupancy(roster, bed(20, 0)) == 1, "and only those");
        require(HousingAssignment.occupancy(roster, bed(99, 0)) == 0, "an unoccupied bed counts nobody");
    }

    /**
     * A batch holding both an assignment and an unbind. bedFor sorts the unbind by the bed it leaves and
     * the assignment by the bed it joins, so the two must interleave by their reference bed, not by kind.
     */
    private static void checkMixedBatchOrdering() {
        var homes = List.of(home(10, 0, 1), home(20, 0, 1), home(30, 0, 1));
        var roster = List.of(resident("a", bed(10, 0)), resident("b", bed(10, 0)),
                resident("c", bed(20, 0)), resident("d", bed(5, 0)));
        var changes = HousingAssignment.plan(homes, Set.of(), roster);
        // The first home keeps only a (capacity 1); b is evicted into the empty third home; d's home does
        // not exist and nothing is left to hold it; c stays where it is and emits no edit.
        require(changes.size() == 2, "one assignment and one unbind, in that batch");
        require(changes.get(0).residentId().equals("d") && changes.get(0).home().isEmpty(),
                "the unbind sorts by the bed it leaves (x=5), ahead of the assignment");
        require(changes.get(1).residentId().equals("b")
                        && changes.get(1).home().equals(Optional.of(bed(30, 0))),
                "and the assignment sorts by the bed it joins (x=30)");
    }

    private static void checkHasAnyRoom() {
        require(HousingAssignment.hasRoom(List.of(home(10, 0, 2)), List.of(resident("a", bed(10, 0)))),
                "a home with a free slot has room");
        require(!HousingAssignment.hasRoom(List.of(home(10, 0, 1), home(20, 0, 1)),
                        List.of(resident("a", bed(10, 0)), resident("b", bed(20, 0)))),
                "every home exactly full has no room");
        require(!HousingAssignment.hasRoom(List.of(home(10, 0, 1)),
                        List.of(resident("a", bed(10, 0)), resident("b", bed(10, 0)))),
                "a home over capacity has no room");
        require(!HousingAssignment.hasRoom(List.of(), List.of(resident("a", null))),
                "no homes at all has no room");
    }

    private static HousingAssignment.BedKey bed(int x, int z) {
        return new HousingAssignment.BedKey(x, 64, z);
    }

    private static HousingAssignment.HomeSlot home(int x, int z, int capacity) {
        return new HousingAssignment.HomeSlot(bed(x, z), capacity);
    }

    private static HousingAssignment.ResidentSlot resident(String id, HousingAssignment.BedKey at) {
        return new HousingAssignment.ResidentSlot(id, Optional.ofNullable(at));
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException("HousingAssignmentCheck failed: " + message);
        }
    }
}
