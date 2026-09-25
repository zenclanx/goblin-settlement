package dev.local.goblinsettlement.citizen;

import dev.local.goblinsettlement.GoblinSettlement;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;

public final class ModEntities {
    public static final EntityType<GoblinCitizenEntity> GOBLIN = register();

    private ModEntities() {
    }

    private static EntityType<GoblinCitizenEntity> register() {
        ResourceKey<EntityType<?>> key = ResourceKey.create(Registries.ENTITY_TYPE,
                Identifier.fromNamespaceAndPath(GoblinSettlement.MOD_ID, "goblin"));
        return Registry.register(BuiltInRegistries.ENTITY_TYPE, key,
                EntityType.Builder.<GoblinCitizenEntity>of(GoblinCitizenEntity::new, MobCategory.CREATURE)
                        .sized(0.6F, 1.45F).build(key));
    }

    public static void initialize() {
        FabricDefaultAttributeRegistry.register(GOBLIN, GoblinCitizenEntity.createAttributes());
    }
}
