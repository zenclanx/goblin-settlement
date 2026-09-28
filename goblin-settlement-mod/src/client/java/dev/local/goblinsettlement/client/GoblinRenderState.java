package dev.local.goblinsettlement.client;

import dev.local.goblinsettlement.colony.Profession;
import dev.local.goblinsettlement.defense.GolemTier;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;

public final class GoblinRenderState extends LivingEntityRenderState {
    public Profession profession = Profession.UNASSIGNED;
    public boolean female;
    public boolean child;
    /** The golem tier being drawn. The goblin renderer never reads it; a golem has no trade or sex. */
    public GolemTier tier = GolemTier.WOOD;
}
