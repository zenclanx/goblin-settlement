package dev.local.goblinsettlement.construction.transport;

import dev.local.goblinsettlement.colony.SettlementSavedData;
import dev.local.goblinsettlement.interaction.WorldModificationPermission;
import dev.local.goblinsettlement.planning.bridge.BridgePlanner;
import dev.local.goblinsettlement.planning.road.RoadPlanner;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.Container;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * First server-side traffic work loop. One block is built each second from a
 * physical registered warehouse. Plans and progress persist in TransportSavedData.
 * This does not assign a resident carrier; the entity transport handoff is a
 * separate integration point.
 */
public final class TransportCoordinator {
    private enum FootprintAccess {
        ALLOWED, INACTIVE, REVOKED
    }

    public record StartResult(boolean accepted, String reason, Optional<TransportPlan> plan) {
    }

    private TransportCoordinator() {
    }

    /** Enqueue a two-lane wooden road toward the target facility block. */
    public static StartResult startRoad(
            ServerLevel level, String settlementId, BlockPos targetFacility) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(settlementId, "settlementId");
        Objects.requireNonNull(targetFacility, "targetFacility");
        TransportSavedData traffic = TransportSavedData.get(level);
        if (hasActivePlan(traffic)) {
            return rejected("TRAFFIC_WORK_ACTIVE");
        }
        RoadPlanner.Result result = RoadPlanner.planFromRegisteredFacilities(
                level, settlementId, targetFacility);
        if (result.candidate().isEmpty()) {
            return rejected(result.status().name());
        }
        List<BlockPos> route = result.candidate().orElseThrow().centerline();
        Map<BlockPos, TransportPlan.Step> sites = new LinkedHashMap<>();
        for (int index = 0; index < route.size(); index++) {
            BlockPos foot = route.get(index);
            Direction forward = roadDirection(route, index);
            Direction side = forward.getClockWise();
            for (BlockPos lane : List.of(foot, foot.relative(side))) {
                if (!safeRoadFoot(level, settlementId, lane)) {
                    return rejected("UNSAFE_TWO_LANE_FOOTPRINT");
                }
                BlockPos ground = lane.below().immutable();
                sites.putIfAbsent(ground, new TransportPlan.Step(
                        TransportPlan.Phase.SURFACE, ground,
                        TransportPlan.Material.OAK_PLANKS, TransportPlan.Rule.ROAD_GROUND));
            }
        }
        if (sites.isEmpty() || conflictsWithExistingWork(
                SettlementSavedData.get(level), sites.keySet())) {
            return rejected("NO_SITES_OR_WORK_CONFLICT");
        }
        TransportPlan plan = new TransportPlan(
                UUID.randomUUID().toString(), settlementId, TransportPlan.Kind.ROAD,
                List.copyOf(sites.values()), 0, false, List.of(), List.of(), List.of());
        if (!traffic.add(plan)) {
            return rejected("TRAFFIC_WORK_ACTIVE_OR_LIMIT");
        }
        return new StartResult(true, "PLANNED", Optional.of(plan));
    }

    /** Enqueue a four-column wooden bridge with two walking lanes and outer rails. */
    public static StartResult startWoodBridge(
            ServerLevel level, String settlementId, BlockPos nearBankFoot,
            Direction direction) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(settlementId, "settlementId");
        Objects.requireNonNull(nearBankFoot, "nearBankFoot");
        Objects.requireNonNull(direction, "direction");
        TransportSavedData traffic = TransportSavedData.get(level);
        if (hasActivePlan(traffic)) {
            return rejected("TRAFFIC_WORK_ACTIVE");
        }
        BridgePlanner.Result result = BridgePlanner.planWoodBridge(
                level, settlementId, nearBankFoot, direction);
        if (result.candidate().isEmpty()) {
            return rejected(result.status().name());
        }
        BridgePlanner.Candidate candidate = result.candidate().orElseThrow();
        List<BlockPos> barriers = new ArrayList<>(4);
        barriers.addAll(candidate.nearLandingFeet());
        barriers.addAll(candidate.farLandingFeet());

        List<TransportPlan.Step> steps = new ArrayList<>();
        for (BlockPos foot : barriers) {
            steps.add(new TransportPlan.Step(TransportPlan.Phase.BARRIERS, foot,
                    TransportPlan.Material.OAK_FENCE, TransportPlan.Rule.AIR));
        }
        Set<BlockPos> approachGround = new LinkedHashSet<>();
        for (BlockPos foot : candidate.nearApproachFeet()) approachGround.add(foot.below());
        for (BlockPos foot : candidate.nearLandingFeet()) approachGround.add(foot.below());
        for (BlockPos foot : candidate.farLandingFeet()) approachGround.add(foot.below());
        for (BlockPos foot : candidate.farApproachFeet()) approachGround.add(foot.below());
        for (BlockPos site : approachGround) {
            if (!safeRoadGround(level, settlementId, site)) {
                return rejected("UNSAFE_BRIDGE_APPROACH");
            }
            steps.add(new TransportPlan.Step(TransportPlan.Phase.APPROACHES, site,
                    TransportPlan.Material.OAK_PLANKS, TransportPlan.Rule.APPROACH_GROUND));
        }

        for (int frame = 0; frame < candidate.span(); frame++) {
            if (frame % 3 != 0 && frame != candidate.span() - 1) {
                continue;
            }
            for (int lane : new int[] {0, 3}) {
                int index = frame * 4 + lane;
                BlockPos base = candidate.surveyedBases().get(index);
                BlockPos deck = candidate.deckSites().get(index);
                for (int y = base.getY() + 1; y < deck.getY(); y++) {
                    BlockPos post = new BlockPos(deck.getX(), y, deck.getZ());
                    steps.add(new TransportPlan.Step(TransportPlan.Phase.SUPPORTS, post,
                            TransportPlan.Material.OAK_LOG, TransportPlan.Rule.SUPPORT));
                }
            }
        }
        for (BlockPos deck : candidate.deckSites()) {
            steps.add(new TransportPlan.Step(TransportPlan.Phase.SURFACE, deck,
                    TransportPlan.Material.OAK_PLANKS, TransportPlan.Rule.AIR_OR_WATER));
        }
        for (BlockPos rail : candidate.railSites()) {
            steps.add(new TransportPlan.Step(TransportPlan.Phase.RAILINGS, rail,
                    TransportPlan.Material.OAK_FENCE, TransportPlan.Rule.AIR));
        }
        for (BlockPos light : candidate.lightSites()) {
            steps.add(new TransportPlan.Step(TransportPlan.Phase.LIGHTING, light,
                    TransportPlan.Material.TORCH, TransportPlan.Rule.AIR));
        }

        Set<BlockPos> closure = new LinkedHashSet<>();
        closure.addAll(barriers);
        candidate.deckSites().forEach(deck -> closure.add(deck.above().immutable()));
        if (conflictsWithExistingWork(SettlementSavedData.get(level),
                steps.stream().map(TransportPlan.Step::site).collect(
                        java.util.stream.Collectors.toSet()))) {
            return rejected("WORK_CONFLICT");
        }
        TransportPlan plan = new TransportPlan(
                UUID.randomUUID().toString(), settlementId, TransportPlan.Kind.WOOD_BRIDGE,
                steps, 0, false, barriers, List.copyOf(closure), candidate.surveyedBases());
        if (!traffic.add(plan)) {
            return rejected("TRAFFIC_WORK_ACTIVE_OR_LIMIT");
        }
        return new StartResult(true, "PLANNED", Optional.of(plan));
    }

    /** Register on END_WORLD_TICK. Never loads a chunk or advances inactive work. */
    public static void tick(ServerLevel level) {
        if (level.getGameTime() % 20 != 0) {
            return;
        }
        TransportSavedData traffic = TransportSavedData.get(level);
        SettlementSavedData settlement = SettlementSavedData.get(level);
        // A fixed inspection budget prevents a growing bridge network from
        // making one world tick scan every completed structure.
        for (TransportPlan plan : traffic.nextOpenBridgeInspections(4)) {
            FootprintAccess access = footprintAccess(level, plan);
            if (access == FootprintAccess.REVOKED) {
                traffic.replace(plan.rewind(0));
            } else if (access == FootprintAccess.ALLOWED
                    && (!foundationsRemainSafe(level, plan)
                        || firstMissingStructuralStep(level, plan) >= 0)) {
                traffic.replace(plan.rewind(0));
            }
        }
        traffic.nextActivePlan().ifPresent(plan ->
                tickPlan(level, settlement, traffic, plan));
    }

    private static void tickPlan(ServerLevel level, SettlementSavedData settlement,
                                 TransportSavedData traffic, TransportPlan plan) {
        if (settlement.settlement().isEmpty()
                || !settlement.settlement().orElseThrow().id().equals(plan.settlementId())
                || !allPermitted(level, plan)
                || !foundationsRemainSafe(level, plan)) {
            return;
        }
        if (plan.kind() == TransportPlan.Kind.WOOD_BRIDGE
                && plan.completedSteps() >= plan.barrierFeet().size()
                && plan.completedSteps() < plan.steps().size()) {
            for (int index = 0; index < plan.barrierFeet().size(); index++) {
                if (!level.getBlockState(plan.barrierFeet().get(index)).is(Blocks.OAK_FENCE)) {
                    traffic.replace(plan.rewind(index));
                    return;
                }
            }
        }
        if (plan.completedSteps() == plan.steps().size()) {
            int missing = firstMissingStructuralStep(level, plan);
            if (missing >= 0) {
                traffic.replace(plan.rewind(missing));
                return;
            }
            if (plan.kind() == TransportPlan.Kind.WOOD_BRIDGE) {
                // Keep the saved closure until every temporary fence is gone.
                // A failed removal can be retried after a reload without opening early.
                if (clearFinishedBarriers(level, plan)) {
                    traffic.replace(plan.withOpen(true));
                }
            }
            return;
        }

        if (!level.getGameRules().get(GameRules.MOB_GRIEFING)) {
            return;
        }
        TransportPlan.Step step = plan.steps().get(plan.completedSteps());
        Block desired = blockFor(step.material());
        if (level.getBlockState(step.site()).is(desired)) {
            traffic.replace(plan.advance());
            return; // Reload or another builder already completed this cell.
        }
        if (!siteReady(level, plan.settlementId(), step)) {
            return;
        }

        for (BlockPos warehouse : settlement.warehouses()) {
            if (WorldModificationPermission.check(level, plan.settlementId(), warehouse)
                    != WorldModificationPermission.Decision.ALLOWED
                    || !(level.getBlockEntity(warehouse) instanceof Container container)) {
                continue;
            }
            int slot = firstItemSlot(container, itemFor(step.material()));
            if (slot < 0) {
                continue;
            }
            ItemStack before = container.getItem(slot).copy();
            ItemStack removed = container.removeItem(slot, 1);
            if (!removed.is(itemFor(step.material())) || removed.getCount() != 1) {
                container.setItem(slot, before);
                container.setChanged();
                continue;
            }
            BlockState desiredState = desired.defaultBlockState();
            if (!level.setBlock(step.site(), desiredState, 3)) {
                container.setItem(slot, before);
                container.setChanged();
                return;
            }
            container.setChanged();
            if (level.getBlockState(step.site()).is(desired)) {
                traffic.replace(plan.advance());
            }
            return;
        }
        // No accessible warehouse currently contains this step's actual item.
    }

    private static boolean hasActivePlan(TransportSavedData traffic) {
        return traffic.plans().stream().anyMatch(plan -> !plan.isComplete());
    }

    private static StartResult rejected(String reason) {
        return new StartResult(false, reason, Optional.empty());
    }

    private static Direction roadDirection(List<BlockPos> route, int index) {
        BlockPos from = route.get(index == route.size() - 1 && index > 0 ? index - 1 : index);
        BlockPos to = route.get(index == route.size() - 1 && index > 0 ? index : Math.min(index + 1, route.size() - 1));
        int dx = Integer.compare(to.getX(), from.getX());
        int dz = Integer.compare(to.getZ(), from.getZ());
        if (dx > 0) return Direction.EAST;
        if (dx < 0) return Direction.WEST;
        if (dz > 0) return Direction.SOUTH;
        return Direction.NORTH;
    }

    private static boolean safeRoadFoot(ServerLevel level, String id, BlockPos foot) {
        if (!permitted(level, id, foot) || !permitted(level, id, foot.above())) {
            return false;
        }
        if (!level.getBlockState(foot).isAir() || !level.getBlockState(foot.above()).isAir()) {
            return false;
        }
        return safeRoadGround(level, id, foot.below());
    }

    private static boolean safeRoadGround(ServerLevel level, String id, BlockPos ground) {
        if (!permitted(level, id, ground)) {
            return false;
        }
        BlockState state = level.getBlockState(ground);
        return state.getFluidState().isEmpty()
                && (state.is(Blocks.DIRT_PATH)
                    || state.isFaceSturdy(level, ground, Direction.UP))
                && buildableGround(state);
    }

    private static boolean buildableGround(BlockState state) {
        return state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.DIRT)
                || state.is(Blocks.COARSE_DIRT) || state.is(Blocks.PODZOL)
                || state.is(Blocks.STONE) || state.is(Blocks.COBBLESTONE)
                || state.is(Blocks.GRAVEL) || state.is(Blocks.SAND)
                || state.is(Blocks.RED_SAND) || state.is(Blocks.DIRT_PATH)
                || state.is(Blocks.OAK_PLANKS);
    }

    private static boolean conflictsWithExistingWork(
            SettlementSavedData settlement, Set<BlockPos> sites) {
        for (var plan : settlement.plans()) {
            if (!plan.isComplete() && (sameColumn(sites, plan.start())
                    || sameColumn(sites, plan.start().east()))) {
                return true;
            }
        }
        return settlement.farmSites().stream().anyMatch(site ->
                sites.stream().anyMatch(pos -> pos.getX() == site.cropPos().getX()
                        && pos.getZ() == site.cropPos().getZ()));
    }

    private static boolean sameColumn(Set<BlockPos> sites, BlockPos other) {
        return sites.stream().anyMatch(site -> site.getX() == other.getX()
                && site.getZ() == other.getZ());
    }

    private static boolean permitted(ServerLevel level, String id, BlockPos pos) {
        return WorldModificationPermission.check(level, id, pos)
                == WorldModificationPermission.Decision.ALLOWED;
    }

    private static boolean allPermitted(ServerLevel level, TransportPlan plan) {
        return footprintAccess(level, plan) == FootprintAccess.ALLOWED;
    }

    private static FootprintAccess footprintAccess(ServerLevel level, TransportPlan plan) {
        boolean inactive = false;
        for (TransportPlan.Step step : plan.steps()) {
            WorldModificationPermission.Decision decision = WorldModificationPermission.check(
                    level, plan.settlementId(), step.site());
            if (decision == WorldModificationPermission.Decision.CHUNK_INACTIVE) {
                inactive = true;
            } else if (decision != WorldModificationPermission.Decision.ALLOWED) {
                return FootprintAccess.REVOKED;
            }
        }
        for (BlockPos base : plan.foundationBases()) {
            WorldModificationPermission.Decision decision = WorldModificationPermission.check(
                    level, plan.settlementId(), base);
            if (decision == WorldModificationPermission.Decision.CHUNK_INACTIVE) {
                inactive = true;
            } else if (decision != WorldModificationPermission.Decision.ALLOWED) {
                return FootprintAccess.REVOKED;
            }
        }
        for (BlockPos foot : plan.closedFootprint()) {
            WorldModificationPermission.Decision decision = WorldModificationPermission.check(
                    level, plan.settlementId(), foot);
            if (decision == WorldModificationPermission.Decision.CHUNK_INACTIVE) {
                inactive = true;
            } else if (decision != WorldModificationPermission.Decision.ALLOWED) {
                return FootprintAccess.REVOKED;
            }
        }
        return inactive ? FootprintAccess.INACTIVE : FootprintAccess.ALLOWED;
    }

    private static boolean foundationsRemainSafe(ServerLevel level, TransportPlan plan) {
        for (BlockPos base : plan.foundationBases()) {
            BlockState state = level.getBlockState(base);
            if (!state.getFluidState().isEmpty()
                    || !state.isFaceSturdy(level, base, Direction.UP)) {
                return false;
            }
        }
        return true;
    }

    private static int firstMissingStructuralStep(ServerLevel level, TransportPlan plan) {
        for (int index = 0; index < plan.steps().size(); index++) {
            TransportPlan.Step step = plan.steps().get(index);
            if (step.phase() != TransportPlan.Phase.BARRIERS
                    && !level.getBlockState(step.site()).is(blockFor(step.material()))) {
                return index;
            }
        }
        return -1;
    }

    private static boolean clearFinishedBarriers(ServerLevel level, TransportPlan plan) {
        if (!level.getGameRules().get(GameRules.MOB_GRIEFING)) {
            return false;
        }
        for (BlockPos pos : plan.barrierFeet()) {
            if (!permitted(level, plan.settlementId(), pos)) {
                return false;
            }
            BlockState state = level.getBlockState(pos);
            if (!state.isAir() && !state.is(Blocks.OAK_FENCE)) {
                return false;
            }
        }
        for (BlockPos pos : plan.barrierFeet()) {
            if (level.getBlockState(pos).is(Blocks.OAK_FENCE)
                    && (!level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3)
                        || !level.getBlockState(pos).isAir())) {
                return false;
            }
        }
        return true;
    }

    private static boolean siteReady(
            ServerLevel level, String settlementId, TransportPlan.Step step) {
        BlockPos site = step.site();
        BlockPos below = site.below();
        if (!permitted(level, settlementId, site)
                || !permitted(level, settlementId, below)
                || !permitted(level, settlementId, site.above())
                || !permitted(level, settlementId, site.above(2))) {
            return false;
        }
        BlockState current = level.getBlockState(site);
        BlockState belowState = level.getBlockState(below);
        BlockState desired = blockFor(step.material()).defaultBlockState();
        if (!desired.canSurvive(level, site)) {
            return false;
        }
        return switch (step.rule()) {
            case ROAD_GROUND -> buildableGround(current)
                    && level.getBlockState(site.above()).isAir()
                    && level.getBlockState(site.above(2)).isAir();
            case APPROACH_GROUND -> buildableGround(current)
                    && (level.getBlockState(site.above()).isAir()
                        || level.getBlockState(site.above()).is(Blocks.OAK_FENCE))
                    && level.getBlockState(site.above(2)).isAir();
            case AIR_OR_WATER -> current.isAir()
                    || current.getFluidState().is(FluidTags.WATER);
            case SUPPORT -> (current.isAir()
                    || current.getFluidState().is(FluidTags.WATER))
                    && belowState.isFaceSturdy(level, below, Direction.UP);
            case AIR -> current.isAir()
                    && (step.material() == TransportPlan.Material.TORCH
                        || belowState.isFaceSturdy(level, below, Direction.UP));
        };
    }

    private static int firstItemSlot(Container container, Item item) {
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            if (container.getItem(slot).is(item)) {
                return slot;
            }
        }
        return -1;
    }

    private static Item itemFor(TransportPlan.Material material) {
        return switch (material) {
            case OAK_PLANKS -> Items.OAK_PLANKS;
            case OAK_LOG -> Items.OAK_LOG;
            case OAK_FENCE -> Items.OAK_FENCE;
            case TORCH -> Items.TORCH;
        };
    }

    private static Block blockFor(TransportPlan.Material material) {
        return switch (material) {
            case OAK_PLANKS -> Blocks.OAK_PLANKS;
            case OAK_LOG -> Blocks.OAK_LOG;
            case OAK_FENCE -> Blocks.OAK_FENCE;
            case TORCH -> Blocks.TORCH;
        };
    }
}
