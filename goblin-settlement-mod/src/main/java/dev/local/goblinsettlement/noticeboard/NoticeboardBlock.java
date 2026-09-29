package dev.local.goblinsettlement.noticeboard;

import com.mojang.serialization.MapCodec;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A board on two posts, standing in the middle of one block. Both shapes are the union of the art
 * team's nine elements, measured off the delivered model: fifteen units wide and three deep, so the
 * two axes are not interchangeable and each needs its own box.
 */
public final class NoticeboardBlock extends HorizontalDirectionalBlock {
    public static final MapCodec<NoticeboardBlock> CODEC = simpleCodec(NoticeboardBlock::new);
    // FACING is inherited from HorizontalDirectionalBlock -- do not redeclare it here, or the field
    // would shadow the parent's and the two could drift apart.

    /** Wide in x, thin in z: the board faces north or south. */
    private static final VoxelShape SHAPE_NS =
            Shapes.box(0.5 / 16.0, 0.0, 6.5 / 16.0, 15.5 / 16.0, 1.0, 9.5 / 16.0);
    /** The same board turned a quarter turn: wide in z, thin in x. */
    private static final VoxelShape SHAPE_EW =
            Shapes.box(6.5 / 16.0, 0.0, 0.5 / 16.0, 9.5 / 16.0, 1.0, 15.5 / 16.0);

    public NoticeboardBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected MapCodec<? extends NoticeboardBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos,
                                  CollisionContext context) {
        return shape(state);
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos,
                                           CollisionContext context) {
        return shape(state);
    }

    private static VoxelShape shape(BlockState state) {
        return state.getValue(FACING).getAxis() == Direction.Axis.Z ? SHAPE_NS : SHAPE_EW;
    }

    /**
     * The server computes the snapshot and pushes it; the client only opens the screen when it arrives.
     * There is no client-to-server request, because the click already happened on the server and there
     * is nothing to ask for.
     */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hit) {
        if (!level.isClientSide() && player instanceof ServerPlayer server) {
            var report = SettlementReport.snapshot((ServerLevel) level);
            ServerPlayNetworking.send(server, new NoticeboardPayload(report));
        }
        return InteractionResult.SUCCESS;
    }
}
