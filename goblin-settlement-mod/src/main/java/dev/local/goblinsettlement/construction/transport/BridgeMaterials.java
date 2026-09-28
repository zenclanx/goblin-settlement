package dev.local.goblinsettlement.construction.transport;

import dev.local.goblinsettlement.construction.BuildMaterial;
import dev.local.goblinsettlement.planning.bridge.BridgePlanner;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What each kind of bridge is built from, and how much of it must be on hand to start one. One
 * authority: the builder's every step, the proposal's material gate and the command all ask here, so a
 * new kind cannot be half-taught.
 *
 * <p>The temporary construction barriers and the railings stay wooden on both kinds. The barriers are
 * not part of the finished crossing, and a stone railing would need the economy to produce one first.
 */
public final class BridgeMaterials {
    /** A coarse "there is enough to begin" floor per material; the real cost is the structure itself. */
    private static final int PLANK_FLOOR = 8;
    private static final int LOG_FLOOR = 4;
    private static final int FENCE_FLOOR = 4;
    private static final int TORCH_FLOOR = 4;
    /**
     * INVENTED: nothing in the design document gives a stone floor. It is the same shape as the wooden
     * plank floor -- enough to lay a few columns -- and must be re-chosen once a stone bridge has been
     * built and watched.
     */
    private static final int COBBLESTONE_FLOOR = 8;

    private BridgeMaterials() {
    }

    public static BuildMaterial deck(TransportPlan.Kind kind) {
        return kind == TransportPlan.Kind.STONE_BRIDGE
                ? BuildMaterial.COBBLESTONE : BuildMaterial.OAK_PLANKS;
    }

    public static BuildMaterial support(TransportPlan.Kind kind) {
        return kind == TransportPlan.Kind.STONE_BRIDGE
                ? BuildMaterial.COBBLESTONE : BuildMaterial.OAK_LOG;
    }

    public static BuildMaterial railing(TransportPlan.Kind kind) {
        return BuildMaterial.OAK_FENCE;
    }

    public static BuildMaterial lighting(TransportPlan.Kind kind) {
        return BuildMaterial.TORCH;
    }

    public static BuildMaterial barrier(TransportPlan.Kind kind) {
        return BuildMaterial.OAK_FENCE;
    }

    /** The span this kind is surveyed for; the numbers live in {@link BridgePlanner}. */
    public static int minSpan(TransportPlan.Kind kind) {
        return kind == TransportPlan.Kind.STONE_BRIDGE
                ? BridgePlanner.MIN_STONE_SPAN : BridgePlanner.MIN_WOOD_SPAN;
    }

    public static int maxSpan(TransportPlan.Kind kind) {
        return kind == TransportPlan.Kind.STONE_BRIDGE
                ? BridgePlanner.MAX_STONE_SPAN : BridgePlanner.MAX_WOOD_SPAN;
    }

    /** The materials this kind needs before it can start, each with the floor it is checked against. */
    public static Map<BuildMaterial, Integer> required(TransportPlan.Kind kind) {
        var required = new LinkedHashMap<BuildMaterial, Integer>();
        required.put(deck(kind), kind == TransportPlan.Kind.STONE_BRIDGE
                ? COBBLESTONE_FLOOR : PLANK_FLOOR);
        required.put(support(kind), kind == TransportPlan.Kind.STONE_BRIDGE
                ? COBBLESTONE_FLOOR : LOG_FLOOR);
        required.put(railing(kind), FENCE_FLOOR);
        required.put(lighting(kind), TORCH_FLOOR);
        return Map.copyOf(required);
    }
}
