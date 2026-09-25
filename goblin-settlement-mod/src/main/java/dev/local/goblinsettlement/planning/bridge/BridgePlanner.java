package dev.local.goblinsettlement.planning.bridge;

import dev.local.goblinsettlement.colony.SettlementSavedData;
import dev.local.goblinsettlement.interaction.WorldModificationPermission;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Bounded, read-only survey for a straight wooden bridge. It proposes geometry
 * and a construction order; it does not make a bridge safe to enter or place any
 * blocks. Call on the server thread and recheck the entire footprint when work
 * is scheduled and immediately before each block modification.
 */
public final class BridgePlanner {
    public static final int MIN_WOOD_SPAN = 4;
    public static final int MAX_WOOD_SPAN = 12;
    private static final int MAX_WATER_BASE_DEPTH = 4;
    private static final int MAX_RAVINE_BASE_DEPTH = 8;

    public enum Status {
        FOUND,
        NO_SETTLEMENT,
        WRONG_SETTLEMENT,
        INVALID_DIRECTION,
        PROTECTED_PLAYER_AREA,
        CHUNK_INACTIVE,
        UNCLAIMED_LAND,
        NEAR_LANDING_UNSAFE,
        NEAR_APPROACH_UNSAFE,
        FAR_APPROACH_UNSAFE,
        IRREGULAR_BANK,
        SPAN_TOO_SHORT,
        SPAN_TOO_LONG,
        MIXED_GAP,
        OBSTRUCTED_CROSSING,
        WATER_TOO_DEEP,
        RAVINE_TOO_DEEP,
        UNSAFE_FOUNDATION
    }

    public enum BridgeKind {
        SHALLOW_WATER, SMALL_RAVINE
    }

    /** Order only. The construction coordinator owns placement and inspection. */
    public enum Stage {
        BANK_APPROACHES,
        ABUTMENTS_AND_SUPPORTS,
        DECK,
        RAILINGS,
        LIGHTING,
        CONNECTIVITY_INSPECTION
    }

    /**
     * Deck sites include four columns: outer rail, two clear walking lanes,
     * outer rail. Rail sites are at walking-foot height; light sites are above
     * the first and last rail pairs. Surveyed bases show possible support
     * foundations, not a finished structural blueprint.
     */
    public record Candidate(
            BridgeKind kind, Direction direction, int span,
            List<BlockPos> nearApproachFeet, List<BlockPos> nearLandingFeet,
            List<BlockPos> farLandingFeet, List<BlockPos> farApproachFeet,
            List<BlockPos> deckSites, List<BlockPos> railSites,
            List<BlockPos> lightSites, List<BlockPos> surveyedBases,
            List<Stage> stages) {
        public Candidate {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(direction, "direction");
            nearApproachFeet = immutablePositions(nearApproachFeet);
            nearLandingFeet = immutablePositions(nearLandingFeet);
            farLandingFeet = immutablePositions(farLandingFeet);
            farApproachFeet = immutablePositions(farApproachFeet);
            deckSites = immutablePositions(deckSites);
            railSites = immutablePositions(railSites);
            lightSites = immutablePositions(lightSites);
            surveyedBases = immutablePositions(surveyedBases);
            stages = List.copyOf(stages);
        }
    }

    public record Result(Status status, Optional<Candidate> candidate) {
        public Result {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(candidate, "candidate");
        }
    }

    private record Survey(Status status, BridgeKind kind, BlockPos base) {
        private static Survey rejected(Status status) {
            return new Survey(status, null, null);
        }

        private static Survey found(BridgeKind kind, BlockPos base) {
            return new Survey(Status.FOUND, kind, base);
        }
    }

    private BridgePlanner() {
    }

    /**
     * The near-bank foot is the first of two walking lanes at ground level.
     * The direction points across the gap. The second lane is clockwise from
     * that direction. Both banks and their outward approach cells must offer
     * two clear, same-height walking lanes; the gap occupies 4-12 columns.
     */
    public static Result planWoodBridge(
            ServerLevel level, String settlementId, BlockPos nearBankFoot,
            Direction direction) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(settlementId, "settlementId");
        Objects.requireNonNull(nearBankFoot, "nearBankFoot");
        Objects.requireNonNull(direction, "direction");

        var settlement = SettlementSavedData.get(level).settlement();
        if (settlement.isEmpty()) {
            return failure(Status.NO_SETTLEMENT);
        }
        if (!settlement.get().id().equals(settlementId)) {
            return failure(Status.WRONG_SETTLEMENT);
        }
        if (direction == Direction.UP || direction == Direction.DOWN) {
            return failure(Status.INVALID_DIRECTION);
        }

        Direction right = direction.getClockWise();
        Direction left = direction.getCounterClockWise();
        BlockPos near = nearBankFoot.immutable();
        Status nearCheck = landingPair(level, settlementId, near, right);
        if (nearCheck != Status.FOUND) {
            return failure(asLandingFailure(nearCheck, Status.NEAR_LANDING_UNSAFE));
        }

        BlockPos nearApproach = near.relative(direction.getOpposite());
        Status nearApproachCheck = landingPair(level, settlementId, nearApproach, right);
        if (nearApproachCheck != Status.FOUND) {
            return failure(asLandingFailure(nearApproachCheck, Status.NEAR_APPROACH_UNSAFE));
        }

        List<BlockPos> deck = new ArrayList<>(MAX_WOOD_SPAN * 4);
        List<BlockPos> rails = new ArrayList<>(MAX_WOOD_SPAN * 2);
        List<BlockPos> bases = new ArrayList<>(MAX_WOOD_SPAN * 4);
        BridgeKind crossingKind = null;

        for (int step = 1; step <= MAX_WOOD_SPAN + 1; step++) {
            BlockPos lane = near.relative(direction, step);
            Status firstLanding = landing(level, settlementId, lane);
            Status secondLanding = landing(level, settlementId, lane.relative(right));
            if (isPermissionFailure(firstLanding)) {
                return failure(firstLanding);
            }
            if (isPermissionFailure(secondLanding)) {
                return failure(secondLanding);
            }
            boolean firstOnBank = firstLanding == Status.FOUND;
            boolean secondOnBank = secondLanding == Status.FOUND;
            if (firstOnBank && secondOnBank) {
                int span = step - 1;
                if (span < MIN_WOOD_SPAN) {
                    return failure(Status.SPAN_TOO_SHORT);
                }
                BlockPos farApproach = lane.relative(direction);
                Status farApproachCheck = landingPair(level, settlementId, farApproach, right);
                if (farApproachCheck != Status.FOUND) {
                    return failure(asLandingFailure(farApproachCheck, Status.FAR_APPROACH_UNSAFE));
                }
                List<BlockPos> lights = List.of(
                        rails.get(0).above(), rails.get(1).above(),
                        rails.get(rails.size() - 2).above(),
                        rails.get(rails.size() - 1).above());
                Candidate candidate = new Candidate(
                        crossingKind, direction, span,
                        lanePair(nearApproach, right), lanePair(near, right),
                        lanePair(lane, right), lanePair(farApproach, right),
                        deck, rails, lights, bases, List.of(Stage.values()));
                return new Result(Status.FOUND, Optional.of(candidate));
            }
            if (firstOnBank != secondOnBank) {
                return failure(Status.IRREGULAR_BANK);
            }
            if (step > MAX_WOOD_SPAN) {
                return failure(Status.SPAN_TOO_LONG);
            }

            BlockPos[] width = {
                    lane.relative(left), lane, lane.relative(right),
                    lane.relative(right, 2)
            };
            BridgeKind stepKind = null;
            for (int offset = 0; offset < width.length; offset++) {
                Survey survey = surveyGapColumn(level, settlementId, width[offset]);
                if (survey.status() != Status.FOUND) {
                    return failure(survey.status());
                }
                if (stepKind != null && stepKind != survey.kind()) {
                    return failure(Status.MIXED_GAP);
                }
                stepKind = survey.kind();
                deck.add(width[offset].below().immutable());
                bases.add(survey.base().immutable());
                if (offset == 0 || offset == width.length - 1) {
                    rails.add(width[offset].immutable());
                }
            }
            if (crossingKind != null && crossingKind != stepKind) {
                return failure(Status.MIXED_GAP);
            }
            crossingKind = stepKind;
        }
        return failure(Status.SPAN_TOO_LONG);
    }

    private static List<BlockPos> immutablePositions(List<BlockPos> positions) {
        return positions.stream().map(pos -> Objects.requireNonNull(pos, "position").immutable()).toList();
    }

    private static List<BlockPos> lanePair(BlockPos first, Direction right) {
        return List.of(first.immutable(), first.relative(right).immutable());
    }

    private static Result failure(Status status) {
        return new Result(status, Optional.empty());
    }

    private static Status asLandingFailure(Status actual, Status terrainFailure) {
        return isPermissionFailure(actual) ? actual : terrainFailure;
    }

    private static boolean isPermissionFailure(Status status) {
        return status == Status.PROTECTED_PLAYER_AREA
                || status == Status.CHUNK_INACTIVE || status == Status.UNCLAIMED_LAND;
    }

    private static Status permission(ServerLevel level, String settlementId, BlockPos pos) {
        return switch (WorldModificationPermission.check(level, settlementId, pos)) {
            case ALLOWED -> Status.FOUND;
            case NO_SETTLEMENT -> Status.NO_SETTLEMENT;
            case WRONG_SETTLEMENT -> Status.WRONG_SETTLEMENT;
            case PROTECTED_PLAYER_AREA -> Status.PROTECTED_PLAYER_AREA;
            case CHUNK_INACTIVE -> Status.CHUNK_INACTIVE;
            case UNCLAIMED_LAND -> Status.UNCLAIMED_LAND;
        };
    }

    private static Status landingPair(
            ServerLevel level, String settlementId, BlockPos first, Direction right) {
        Status firstStatus = landing(level, settlementId, first);
        if (firstStatus != Status.FOUND) {
            return firstStatus;
        }
        return landing(level, settlementId, first.relative(right));
    }

    private static Status landing(ServerLevel level, String settlementId, BlockPos foot) {
        BlockPos head = foot.above();
        BlockPos ground = foot.below();
        for (BlockPos pos : List.of(foot, head, ground)) {
            Status access = permission(level, settlementId, pos);
            if (access != Status.FOUND) {
                return access;
            }
        }
        BlockState footState = level.getBlockState(foot);
        BlockState headState = level.getBlockState(head);
        BlockState groundState = level.getBlockState(ground);
        if (!footState.isAir() || !headState.isAir()
                || !groundState.getFluidState().isEmpty()
                || (!groundState.is(Blocks.DIRT_PATH)
                    && !groundState.isFaceSturdy(level, ground, Direction.UP))
                || unsafeBase(groundState)) {
            return Status.NEAR_LANDING_UNSAFE;
        }
        return Status.FOUND;
    }

    private static Survey surveyGapColumn(
            ServerLevel level, String settlementId, BlockPos foot) {
        BlockPos head = foot.above();
        BlockPos deck = foot.below();
        for (BlockPos pos : List.of(foot, head)) {
            Status access = permission(level, settlementId, pos);
            if (access != Status.FOUND) {
                return Survey.rejected(access);
            }
        }
        if (!level.getBlockState(foot).isAir() || !level.getBlockState(head).isAir()) {
            return Survey.rejected(Status.OBSTRUCTED_CROSSING);
        }

        boolean water = false;
        for (int depth = 0; depth <= MAX_RAVINE_BASE_DEPTH; depth++) {
            BlockPos probe = deck.below(depth);
            Status access = permission(level, settlementId, probe);
            if (access != Status.FOUND) {
                return Survey.rejected(access);
            }
            BlockState state = level.getBlockState(probe);
            if (state.getFluidState().is(FluidTags.LAVA)) {
                return Survey.rejected(Status.OBSTRUCTED_CROSSING);
            }
            if (state.getFluidState().is(FluidTags.WATER)) {
                water = true;
                continue;
            }
            if (state.isAir()) {
                continue;
            }
            if (depth == 0 || !state.getFluidState().isEmpty()
                    || !state.isFaceSturdy(level, probe, Direction.UP)
                    || unsafeBase(state)) {
                return Survey.rejected(Status.UNSAFE_FOUNDATION);
            }
            if (water && depth > MAX_WATER_BASE_DEPTH) {
                return Survey.rejected(Status.WATER_TOO_DEEP);
            }
            BridgeKind kind = water ? BridgeKind.SHALLOW_WATER : BridgeKind.SMALL_RAVINE;
            return Survey.found(kind, probe.immutable());
        }
        return Survey.rejected(water ? Status.WATER_TOO_DEEP : Status.RAVINE_TOO_DEEP);
    }

    private static boolean unsafeBase(BlockState state) {
        return state.is(Blocks.FARMLAND) || state.is(Blocks.MAGMA_BLOCK)
                || state.is(Blocks.CACTUS) || state.is(Blocks.CAMPFIRE)
                || state.is(Blocks.SOUL_CAMPFIRE) || state.is(Blocks.POWDER_SNOW);
    }
}
