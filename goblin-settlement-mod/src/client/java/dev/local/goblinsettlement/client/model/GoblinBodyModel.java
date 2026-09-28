package dev.local.goblinsettlement.client.model;

import dev.local.goblinsettlement.client.GoblinRenderState;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.util.Mth;

/**
 * The six-part humanoid body every generated art variant shares -- the goblin bodies and the custom
 * golems alike. The generated meshes differ only in geometry, so the walk and look animation lives
 * here exactly once.
 */
public abstract class GoblinBodyModel extends EntityModel<GoblinRenderState> {
    private final ModelPart head;
    private final ModelPart leftArm;
    private final ModelPart rightArm;
    private final ModelPart leftLeg;
    private final ModelPart rightLeg;

    protected GoblinBodyModel(ModelPart root) {
        super(root);
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
