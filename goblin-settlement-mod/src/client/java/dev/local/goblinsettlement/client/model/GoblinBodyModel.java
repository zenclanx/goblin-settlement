package dev.local.goblinsettlement.client.model;

import dev.local.goblinsettlement.client.GoblinRenderState;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.util.Mth;

/**
 * The six-part humanoid body every generated art variant shares -- the goblin bodies and the custom
 * golems alike. The generated meshes differ only in geometry, so the walk and look animation lives
 * here exactly once.
 */
public abstract class GoblinBodyModel extends EntityModel<GoblinRenderState> {
    /** The model-space ground line the feet stand on. The counterpart of GROUND in the generator. */
    public static final float GROUND = 24.0F;
    /** The art's meshes are built on a 2x grid; the generator emits them at art scale, so this halves
     * them back at render time. Its counterpart is the unscaled geometry in tools/generate_models.py. */
    public static final float RENDER_SCALE = 0.5F;

    private final ModelPart head;
    private final ModelPart leftArm;
    private final ModelPart rightArm;
    private final ModelPart leftLeg;
    private final ModelPart rightLeg;

    protected GoblinBodyModel(ModelPart root) {
        super(root);
        // Counterpart of tools/generate_models.py, which emits art-scale geometry. Scaling the root
        // about the ground line -- its pivot moved to GROUND * (1 - scale) -- halves every mesh while
        // the feet stay planted. Scaling about y = 0 instead would float every model 0.75 blocks.
        PartPose scaled = new PartPose(0.0F, GROUND * (1.0F - RENDER_SCALE), 0.0F,
                0.0F, 0.0F, 0.0F, RENDER_SCALE, RENDER_SCALE, RENDER_SCALE);
        root.setInitialPose(scaled);
        root.loadPose(scaled);
        head = root.getChild("head");
        leftArm = root.getChild("left_arm");
        rightArm = root.getChild("right_arm");
        leftLeg = root.getChild("left_leg");
        rightLeg = root.getChild("right_leg");
    }

    @Override
    public void setupAnim(GoblinRenderState state) {
        super.setupAnim(state);
        head.xRot = state.xRot * Mth.DEG_TO_RAD;
        head.yRot = state.yRot * Mth.DEG_TO_RAD;
        float swing = state.walkAnimationSpeed * 1.2F;
        float phase = state.walkAnimationPos * 0.65F;
        leftLeg.xRot = Mth.cos(phase) * swing;
        rightLeg.xRot = Mth.cos(phase + Mth.PI) * swing;
        leftArm.xRot = rightLeg.xRot * 0.6F;
        rightArm.xRot = leftLeg.xRot * 0.6F;
    }
}
