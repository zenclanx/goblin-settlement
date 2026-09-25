package dev.local.goblinsettlement.defense;

import dev.local.goblinsettlement.GoblinSettlement;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;

/** Call initialize() once from the mod initializer; all six tiers share GOLEM. */
public final class GolemEntities {
    public static final EntityType<GoblinGolemEntity> GOLEM = register();

    private GolemEntities() {
    }

    private static EntityType<GoblinGolemEntity> register() {
        ResourceKey<EntityType<?>> key = ResourceKey.create(Registries.ENTITY_TYPE,
                Identifier.fromNamespaceAndPath(GoblinSettlement.MOD_ID, "goblin_golem"));
        return Registry.register(BuiltInRegistries.ENTITY_TYPE, key,
                EntityType.Builder.<GoblinGolemEntity>of(GoblinGolemEntity::new, MobCategory.CREATURE)
                        .sized(1.1F, 2.7F).build(key));
    }

    public static void initialize() {
        FabricDefaultAttributeRegistry.register(GOLEM, GoblinGolemEntity.createAttributes());
    }
}
