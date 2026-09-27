package dev.local.goblinsettlement.planning.transport;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Pure facility selection: the nearest unserved facility beyond a minimum distance. */
public final class TrafficTargetRules {
    /** A facility position with no Minecraft dependency; y participates only in identity. */
    public record Facility(int x, int y, int z) {
    }

    private TrafficTargetRules() {
    }

    /** Nearest unserved facility with horizontal squared distance strictly above the floor.
     *  Ties break by x, then z, then y. Empty when no candidate qualifies. */
    public static Optional<Facility> nearestBeyond(Facility anchor, List<Facility> facilities,
                                                   List<Facility> served, long minDistanceSquared) {
        Objects.requireNonNull(anchor, "anchor");
        Objects.requireNonNull(facilities, "facilities");
        Objects.requireNonNull(served, "served");
        if (minDistanceSquared < 0) {
            throw new IllegalArgumentException("Minimum distance cannot be negative");
        }
        Facility best = null;
        long bestDistance = Long.MAX_VALUE;
        for (Facility facility : facilities) {
            if (served.contains(facility)) {
                continue;
            }
            long dx = (long) facility.x() - anchor.x();
            long dz = (long) facility.z() - anchor.z();
            long distance = dx * dx + dz * dz;
            if (distance <= minDistanceSquared) {
                continue;
            }
            if (best == null || distance < bestDistance
                    || (distance == bestDistance && precedes(facility, best))) {
                best = facility;
                bestDistance = distance;
            }
        }
        return Optional.ofNullable(best);
    }

    private static boolean precedes(Facility left, Facility right) {
        return left.x() != right.x() ? left.x() < right.x()
                : left.z() != right.z() ? left.z() < right.z()
                : left.y() < right.y();
    }
}
