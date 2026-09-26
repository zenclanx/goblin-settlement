package dev.local.goblinsettlement.camp;

import dev.local.goblinsettlement.citizen.GoblinCitizenEntity;
import dev.local.goblinsettlement.citizen.ModEntities;
import dev.local.goblinsettlement.colony.ResidentRecord;
import dev.local.goblinsettlement.colony.SettlementSavedData;
import dev.local.goblinsettlement.interaction.WorldModificationPermission;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.levelgen.Heightmap;

/** Conservative natural camp creation for the existing one-settlement-per-dimension save format. */
public final class CampGenerationCoordinator {
    private static final int PLOT_SIZE = 8;
    private static final int PROBE_INTERVAL = 200;

    private CampGenerationCoordinator() {
    }

    /** Call once per server-level tick. Only an active, fully surveyed plot can be claimed. */
    public static void tick(ServerLevel level) {
        if (!level.dimension().equals(Level.OVERWORLD)) return;
        if (level.getGameTime() % PROBE_INTERVAL != 0) return;
        SettlementSavedData settlements = SettlementSavedData.get(level);
        CampGenerationSavedData camp = CampGenerationSavedData.get(level);
        if (camp.site().isEmpty()) {
            if (settlements.settlement().isPresent()) return; // Includes administrator-founded settlements.
            BlockPos candidate = findSite(level, settlements);
            if (candidate == null) return;
            camp.claim(candidate);
        }
        BlockPos anchor = camp.site().orElseThrow();
        if (!level.shouldTickBlocksAt(anchor)) return;
        if (settlements.settlement().isEmpty()) {
            if (settlements.found(anchor) != SettlementSavedData.FoundResult.FOUNDED) {
                camp.abandonUnbuiltSite();
                return;
            }
        }
        var founded = settlements.settlement();
        if (founded.isEmpty() || !founded.get().anchor().equals(anchor)) return;
        String id = founded.get().id();
        BlockPos corner = corner(anchor);
        if (!camp.layoutComplete()) {
            placeCamp(level, id, corner);
            if (layoutComplete(level, corner)) camp.markLayoutComplete();
        }
        if (!camp.layoutComplete()) return;
        BlockPos chestPos = corner.offset(1, 0, 3);
        if (!camp.starterGrantStarted() && level.getBlockState(chestPos).is(Blocks.CHEST)
                && level.getBlockEntity(chestPos) instanceof Container chest) {
            camp.markStarterGrantStarted();
            grantSupplies(chest);
        }
        if (level.getBlockState(chestPos).is(Blocks.CHEST)
                && level.getBlockEntity(chestPos) instanceof Container
                && !settlements.warehouses().contains(chestPos)) settlements.registerWarehouse(chestPos);
        if (!camp.populationStarted()) {
            spawnAdults(level, settlements, id, corner);
            if (initialAdultCount(settlements, id) == 8) camp.markPopulationStarted();
        }
    }

    private static BlockPos findSite(ServerLevel level, SettlementSavedData data) {
        BlockPos best = null;
        int bestScore = Integer.MIN_VALUE;
        for (var player : level.players()) {
            BlockPos origin = player.blockPosition();
            for (int[] offset : new int[][] {{64, 0}, {-64, 0}, {0, 64}, {0, -64},
                    {64, 64}, {-64, 64}, {64, -64}, {-64, -64},
                    {80, 0}, {-80, 0}, {0, 80}, {0, -80},
                    {80, 80}, {-80, 80}, {80, -80}, {-80, -80},
                    {96, 0}, {-96, 0}, {0, 96}, {0, -96}}) {
                int x = Math.floorDiv(origin.getX() + offset[0], PLOT_SIZE) * PLOT_SIZE;
                int z = Math.floorDiv(origin.getZ() + offset[1], PLOT_SIZE) * PLOT_SIZE;
                BlockPos probe = new BlockPos(x + 3, origin.getY(), z + 3);
                if (!level.shouldTickBlocksAt(probe)) continue;
                int y = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, probe).getY();
                BlockPos corner = new BlockPos(x, y, z);
                if (!safePlot(level, data, corner)) continue;
                int score = nearbyResources(level, corner);
                if (score > bestScore) {
                    bestScore = score;
                    best = corner.offset(3, 0, 3);
                }
            }
        }
        return best;
    }

    private static boolean safePlot(ServerLevel level, SettlementSavedData data, BlockPos corner) {
        for (int dx = 0; dx < PLOT_SIZE; dx++) {
            for (int dz = 0; dz < PLOT_SIZE; dz++) {
                BlockPos feet = corner.offset(dx, 0, dz);
                if (!level.shouldTickBlocksAt(feet) || !level.getWorldBorder().isWithinBounds(feet)
                        || data.isProtected(feet)) return false;
                var ground = level.getBlockState(feet.below());
                if (!(ground.is(Blocks.GRASS_BLOCK) || ground.is(Blocks.DIRT)
                        || ground.is(Blocks.PODZOL) || ground.is(Blocks.COARSE_DIRT))
                        || !ground.getFluidState().isEmpty()
                        || !ground.isFaceSturdy(level, feet.below(), Direction.UP)) return false;
                for (int dy = 0; dy <= 3; dy++) {
                    BlockPos space = feet.above(dy);
                    if (!level.getBlockState(space).isAir() || !level.getFluidState(space).isEmpty()) return false;
                }
            }
        }
        return true;
    }

    /** Trees and water rank safe sites; missing either does not block a viable plain. */
    private static int nearbyResources(ServerLevel level, BlockPos corner) {
        boolean tree = false;
        boolean water = false;
        boolean cultivable = false;
        for (int dx = -12; dx <= 19; dx += 4) {
            for (int dz = -12; dz <= 19; dz += 4) {
                BlockPos sample = corner.offset(dx, 0, dz);
                if (!level.shouldTickBlocksAt(sample)) continue;
                for (int dy = -2; dy <= 4; dy++) {
                    var state = level.getBlockState(sample.above(dy));
                    tree |= state.is(BlockTags.LOGS);
                    water |= state.is(Blocks.WATER);
                    cultivable |= state.is(Blocks.FARMLAND) || state.is(Blocks.GRASS_BLOCK);
                }
            }
        }
        return (tree ? 4 : 0) + (water ? 2 : 0) + (cultivable ? 1 : 0);
    }

    private static BlockPos corner(BlockPos anchor) {
        return new BlockPos(anchor.getX() - Math.floorMod(anchor.getX(), PLOT_SIZE), anchor.getY(),
                anchor.getZ() - Math.floorMod(anchor.getZ(), PLOT_SIZE));
    }

    private static void placeCamp(ServerLevel level, String id, BlockPos corner) {
        // Every placement rechecks current land ownership and air; a changed player block is never overwritten.
        placeAir(level, id, corner.offset(1, 0, 3), Blocks.CHEST.defaultBlockState());
        placeAir(level, id, corner.offset(3, 0, 3), Blocks.CAMPFIRE.defaultBlockState());
        for (int x : new int[] {0, 2, 4, 6}) {
            placeBed(level, id, corner.offset(x, 0, 1));
            placeBed(level, id, corner.offset(x, 0, 5));
        }
        for (int x : new int[] {0, 7}) {
            for (int z : new int[] {0, 7}) {
                for (int y = 0; y <= 2; y++) {
                    placeAir(level, id, corner.offset(x, y, z), Blocks.OAK_FENCE.defaultBlockState());
                }
            }
        }
        for (int x = 0; x < PLOT_SIZE; x++) {
            for (int z : new int[] {1, 2, 5, 6}) {
                placeAir(level, id, corner.offset(x, 3, z), Blocks.OAK_SLAB.defaultBlockState());
            }
        }
    }

    private static void placeBed(ServerLevel level, String id, BlockPos foot) {
        BlockPos head = foot.south();
        var footState = Blocks.WHITE_BED.defaultBlockState()
                .setValue(BedBlock.FACING, Direction.SOUTH).setValue(BedBlock.PART, BedPart.FOOT);
        var headState = footState.setValue(BedBlock.PART, BedPart.HEAD);
        boolean footOkay = bedPartMatches(level, foot, BedPart.FOOT);
        boolean headOkay = bedPartMatches(level, head, BedPart.HEAD);
        if (!footOkay && !canPlace(level, id, foot)) return;
        if (!headOkay && !canPlace(level, id, head)) return;
        if (!footOkay) level.setBlock(foot, footState, 3);
        if (!headOkay) level.setBlock(head, headState, 3);
    }

    private static boolean bedPartMatches(ServerLevel level, BlockPos pos, BedPart part) {
        var state = level.getBlockState(pos);
        return state.is(Blocks.WHITE_BED) && state.getValue(BedBlock.PART) == part
                && state.getValue(BedBlock.FACING) == Direction.SOUTH;
    }

    private static boolean layoutComplete(ServerLevel level, BlockPos corner) {
        if (!level.getBlockState(corner.offset(1, 0, 3)).is(Blocks.CHEST)
                || !level.getBlockState(corner.offset(3, 0, 3)).is(Blocks.CAMPFIRE)) return false;
        for (int x : new int[] {0, 2, 4, 6}) {
            for (int z : new int[] {1, 5}) {
                BlockPos foot = corner.offset(x, 0, z);
                if (!bedPartMatches(level, foot, BedPart.FOOT)
                        || !bedPartMatches(level, foot.south(), BedPart.HEAD)) return false;
            }
        }
        for (int x : new int[] {0, 7}) {
            for (int z : new int[] {0, 7}) {
                for (int y = 0; y <= 2; y++) {
                    if (!level.getBlockState(corner.offset(x, y, z)).is(Blocks.OAK_FENCE)) return false;
                }
            }
        }
        for (int x = 0; x < PLOT_SIZE; x++) {
            for (int z : new int[] {1, 2, 5, 6}) {
                if (!level.getBlockState(corner.offset(x, 3, z)).is(Blocks.OAK_SLAB)) return false;
            }
        }
        return true;
    }

    private static void placeAir(ServerLevel level, String id, BlockPos pos,
                                 net.minecraft.world.level.block.state.BlockState state) {
        if (canPlace(level, id, pos)) level.setBlock(pos, state, 3);
    }

    private static boolean canPlace(ServerLevel level, String id, BlockPos pos) {
        return WorldModificationPermission.check(level, id, pos) == WorldModificationPermission.Decision.ALLOWED
                && level.getBlockState(pos).isAir() && level.getFluidState(pos).isEmpty();
    }

    private static void grantSupplies(Container chest) {
        List<ItemStack> supplies = List.of(new ItemStack(Items.BREAD, 24),
                new ItemStack(Items.WHEAT_SEEDS, 32), new ItemStack(Items.OAK_SAPLING, 12),
                new ItemStack(Items.WOODEN_AXE, 2), new ItemStack(Items.WOODEN_HOE, 2),
                new ItemStack(Items.WOODEN_PICKAXE, 2), new ItemStack(Items.WHITE_BED, 2),
                new ItemStack(Items.OAK_PLANKS, 32));
        for (int slot = 0; slot < supplies.size() && slot < chest.getContainerSize(); slot++) {
            if (chest.getItem(slot).isEmpty()) chest.setItem(slot, supplies.get(slot));
        }
        chest.setChanged();
    }

    private static void spawnAdults(ServerLevel level, SettlementSavedData data, String id, BlockPos corner) {
        BlockPos[] spaces = {corner.offset(0, 0, 3), corner.offset(2, 0, 3),
                corner.offset(4, 0, 3), corner.offset(6, 0, 3), corner.offset(0, 0, 4),
                corner.offset(2, 0, 4), corner.offset(4, 0, 4), corner.offset(6, 0, 4)};
        for (int i = 0; i < spaces.length; i++) {
            String residentId = UUID.nameUUIDFromBytes((id + ":initial-adult:" + i)
                    .getBytes(StandardCharsets.UTF_8)).toString();
            if (data.resident(residentId).isPresent()) continue;
            if (level.getEntity(UUID.fromString(residentId)) instanceof GoblinCitizenEntity) {
                data.registerAdult(residentId, i < 4
                        ? ResidentRecord.ReproductiveRole.MOTHER : ResidentRecord.ReproductiveRole.FATHER);
                continue;
            }
            BlockPos spawn = spaces[i];
            if (!level.getBlockState(spawn).isAir() || !level.getBlockState(spawn.above()).isAir()) continue;
            GoblinCitizenEntity adult = ModEntities.GOBLIN.create(level, EntitySpawnReason.NATURAL);
            if (adult == null) continue;
            adult.setUUID(UUID.fromString(residentId));
            adult.snapTo(spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5);
            if (!level.noCollision(adult) || !level.getEntitiesOfClass(LivingEntity.class,
                    adult.getBoundingBox(), LivingEntity::isAlive).isEmpty()) continue;
            if (level.addFreshEntity(adult)) {
                data.registerAdult(residentId, i < 4
                        ? ResidentRecord.ReproductiveRole.MOTHER : ResidentRecord.ReproductiveRole.FATHER);
            }
        }
    }

    private static int initialAdultCount(SettlementSavedData data, String id) {
        int count = 0;
        for (int i = 0; i < 8; i++) {
            String residentId = UUID.nameUUIDFromBytes((id + ":initial-adult:" + i)
                    .getBytes(StandardCharsets.UTF_8)).toString();
            if (data.resident(residentId).isPresent()) count++;
        }
        return count;
    }
}
