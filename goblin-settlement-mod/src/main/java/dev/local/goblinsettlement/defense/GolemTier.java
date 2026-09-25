package dev.local.goblinsettlement.defense;

import java.util.List;
import java.util.Optional;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/** One entity type carries one of six durable tiers. Costs are paid from real public storage. */
public enum GolemTier {
    WOOD(20, 4, 0.25, Items.OAK_PLANKS, List.of(new GolemMaterialCost(Items.OAK_PLANKS, 8))),
    STONE(30, 5, 0.23, Items.COBBLESTONE, List.of(new GolemMaterialCost(Items.COBBLESTONE, 8))),
    IRON(50, 7, 0.22, Items.IRON_INGOT, List.of(new GolemMaterialCost(Items.IRON_INGOT, 4))),
    GOLD(60, 9, 0.25, Items.GOLD_INGOT, List.of(
            new GolemMaterialCost(Items.GOLD_INGOT, 4), new GolemMaterialCost(Items.REDSTONE, 1))),
    DIAMOND(75, 11, 0.30, Items.DIAMOND, List.of(new GolemMaterialCost(Items.DIAMOND, 2))),
    OBSIDIAN(100, 14, 0.21, Items.OBSIDIAN, List.of(
            new GolemMaterialCost(Items.OBSIDIAN, 8), new GolemMaterialCost(Items.DIAMOND, 2)));

    private final double maxHealth;
    private final double attackDamage;
    private final double movementSpeed;
    private final Item repairItem;
    private final List<GolemMaterialCost> entryCost;

    GolemTier(double maxHealth, double attackDamage, double movementSpeed,
              Item repairItem, List<GolemMaterialCost> entryCost) {
        this.maxHealth = maxHealth;
        this.attackDamage = attackDamage;
        this.movementSpeed = movementSpeed;
        this.repairItem = repairItem;
        this.entryCost = entryCost;
    }

    public double maxHealth() {
        return maxHealth;
    }

    public double attackDamage() {
        return attackDamage;
    }

    public double movementSpeed() {
        return movementSpeed;
    }

    public Item repairItem() {
        return repairItem;
    }

    /** WOOD's cost creates a golem; later costs upgrade from the preceding tier. */
    public List<GolemMaterialCost> entryCost() {
        return entryCost;
    }

    public Optional<GolemTier> next() {
        return ordinal() + 1 < values().length ? Optional.of(values()[ordinal() + 1]) : Optional.empty();
    }
}
