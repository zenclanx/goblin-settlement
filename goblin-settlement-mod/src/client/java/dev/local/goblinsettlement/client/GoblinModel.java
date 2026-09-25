package dev.local.goblinsettlement.client;

import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.util.Mth;

/** Temporary small humanoid model; art can be replaced without changing entity logic. */
public final class GoblinModel extends EntityModel<GoblinRenderState> {
    private final ModelPart head;
    private final ModelPart leftArm;
    private final ModelPart rightArm;
    private final ModelPart leftLeg;
    private final ModelPart rightLeg;

    public GoblinModel(ModelPart root) {
        super(root);
        head = root.getChild("head");
        leftArm = root.getChild("left_arm");
        rightArm = root.getChild("right_arm");
        leftLeg = root.getChild("left_leg");
        rightLeg = root.getChild("right_leg");
    }

    public static LayerDefinition createLayer() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();
        root.addOrReplaceChild("head", CubeListBuilder.create().texOffs(0, 0)
                .addBox(-4, -8, -4, 8, 8, 8), PartPose.offset(0, 6, 0));
        root.addOrReplaceChild("body", CubeListBuilder.create().texOffs(16, 16)
                .addBox(-3, 0, -2, 6, 8, 4), PartPose.offset(0, 7, 0));
        root.addOrReplaceChild("left_arm", CubeListBuilder.create().texOffs(40, 16)
                .addBox(-1.5F, 0, -1.5F, 3, 8, 3), PartPose.offset(4.5F, 8, 0));
        root.addOrReplaceChild("right_arm", CubeListBuilder.create().texOffs(40, 16)
                .addBox(-1.5F, 0, -1.5F, 3, 8, 3), PartPose.offset(-4.5F, 8, 0));
        root.addOrReplaceChild("left_leg", CubeListBuilder.create().texOffs(0, 16)
                .addBox(-1.5F, 0, -1.5F, 3, 8, 3), PartPose.offset(1.5F, 16, 0));
        root.addOrReplaceChild("right_leg", CubeListBuilder.create().texOffs(0, 16)
                .addBox(-1.5F, 0, -1.5F, 3, 8, 3), PartPose.offset(-1.5F, 16, 0));
        return LayerDefinition.create(mesh, 64, 64);
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
