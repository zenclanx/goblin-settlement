package dev.local.goblinsettlement.planning.transport;

import java.util.List;
import java.util.Objects;

/**
 * Pure bridge-or-road rule over straight-line column facts. The first water run
 * decides: roads cannot cross any water, so a run that is not bridgeable means
 * the direct corridor is unusable and RoadPlanner's A* owns whatever detour it
 * can find.
 */
public final class TrafficDecision {
    public enum ColumnKind { LAND, WATER, BLOCKED }
    public enum Kind { NONE, ROAD, BRIDGE }

    /** For BRIDGE the gap occupies columns [gapStartInclusive, gapEndInclusive];
     *  the near bank is gapStart-1 and the far bank is gapEnd+1 (both LAND). */
    public record Decision(Kind kind, int gapStartInclusive, int gapEndInclusive) {
        public Decision {
            Objects.requireNonNull(kind, "kind");
        }
    }

    private TrafficDecision() {
    }

    public static Decision decide(List<ColumnKind> columns, int targetIndex, int minSpan, int maxSpan) {
        Objects.requireNonNull(columns, "columns");
        if (columns.isEmpty() || targetIndex < 0 || targetIndex >= columns.size()
                || minSpan < 1 || maxSpan < minSpan) {
            return new Decision(Kind.NONE, -1, -1);
        }
        if (columns.get(targetIndex) != ColumnKind.LAND) {
            // The probe cannot confirm the target stands on land at line height;
            // RoadPlanner owns target accessibility and fails cleanly on its own.
            return new Decision(Kind.ROAD, -1, -1);
        }
        int runStart = -1;
        for (int index = 0; index <= targetIndex; index++) {
            if (columns.get(index) == ColumnKind.WATER) {
                runStart = index;
                break;
            }
        }
        if (runStart < 0) {
            return new Decision(Kind.ROAD, -1, -1);
        }
        if (runStart == 0 || columns.get(runStart - 1) != ColumnKind.LAND) {
            return new Decision(Kind.ROAD, -1, -1);   // no buildable near bank
        }
        int runEnd = runStart;
        while (runEnd + 1 < targetIndex && columns.get(runEnd + 1) == ColumnKind.WATER) {
            runEnd++;
        }
        if (columns.get(runEnd + 1) != ColumnKind.LAND) {
            return new Decision(Kind.ROAD, -1, -1);   // no buildable far bank
        }
        int width = runEnd - runStart + 1;
        if (width < minSpan || width > maxSpan) {
            return new Decision(Kind.ROAD, -1, -1);
        }
        return new Decision(Kind.BRIDGE, runStart, runEnd);
    }
}
