package dev.local.goblinsettlement.client;

import dev.local.goblinsettlement.colony.Profession;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;

public final class GoblinRenderState extends LivingEntityRenderState {
    public Profession profession = Profession.UNASSIGNED;
}
