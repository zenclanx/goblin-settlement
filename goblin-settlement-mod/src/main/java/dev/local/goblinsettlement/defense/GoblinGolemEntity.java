package dev.local.goblinsettlement.defense;

import dev.local.goblinsettlement.citizen.GoblinCitizenEntity;
import dev.local.goblinsettlement.colony.SettlementSavedData;
import dev.local.goblinsettlement.construction.transport.TransportSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * One persistent entity type for all six tiers. It acquires a target only from an explicit
 * resident-damage event; proximity, equipment and warehouse visits never create hostility.
 */
public final class GoblinGolemEntity extends PathfinderMob {
    public static final double DEFENSE_RADIUS = 24.0;
    private static final int ALERT_TICKS = 20 * 15;

    private GolemTier tier = GolemTier.WOOD;
    private String settlementId = "";
    private BlockPos home = BlockPos.ZERO;
    private int alertTicks;

    public GoblinGolemEntity(EntityType<? extends GoblinGolemEntity> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return PathfinderMob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, GolemTier.WOOD.maxHealth())
                .add(Attributes.ATTACK_DAMAGE, GolemTier.WOOD.attackDamage())
                .add(Attributes.MOVEMENT_SPEED, GolemTier.WOOD.movementSpeed());
    }

    @Override
    protected void registerGoals() {
        goalSelector.addGoal(1, new MeleeAttackGoal(this, 1.0, true));
        goalSelector.addGoal(2, new LookAtPlayerGoal(this, Player.class, 6.0F));
        goalSelector.addGoal(3, new RandomLookAroundGoal(this));
    }

    public GolemTier tier() {
        return tier;
    }

    public String settlementId() {
        return settlementId;
    }

    public BlockPos home() {
        return home;
    }

    /** Called once by the creation workflow after ownership and location checks. */
    public boolean bindToSettlement(String id, BlockPos homePos) {
        if (!(level() instanceof ServerLevel) || !settlementId.isBlank()
                || id == null || id.isBlank() || homePos == null) {
            return false;
        }
        settlementId = id;
        home = homePos.immutable();
        applyTierAttributes();
        setHealth(getMaxHealth());
        return true;
    }

    /**
     * Call from a real damage callback for a registered settlement resident. The attacker
     * must be the causal entity of that damage, and is forgotten after a short bounded alert.
     */
    public boolean alertToResidentAttack(GoblinCitizenEntity victim, DamageSource source) {
        return source != null && alertToResidentAttack(victim, source.getEntity());
    }

    public boolean alertToResidentAttack(GoblinCitizenEntity victim, Entity cause) {
        if (!(level() instanceof ServerLevel level) || settlementId.isBlank()
                || victim == null || victim.level() != level) {
            return false;
        }
        if (!(cause instanceof LivingEntity attacker) || !isPermittedAttacker(attacker)
                || !attacker.isAlive() || attacker.level() != level
                || distanceToSqr(attacker) > DEFENSE_RADIUS * DEFENSE_RADIUS
                || attacker.distanceToSqr(home.getX() + 0.5, home.getY() + 0.5, home.getZ() + 0.5)
                        > DEFENSE_RADIUS * DEFENSE_RADIUS
                || distanceToSqr(victim) > DEFENSE_RADIUS * DEFENSE_RADIUS) {
            return false;
        }
        SettlementSavedData data = SettlementSavedData.get(level);
        if (data.settlement().map(value -> !value.id().equals(settlementId)).orElse(true)
                || data.resident(victim.getUUID().toString()).isEmpty()) {
            return false;
        }
        setTarget(attacker);
        alertTicks = ALERT_TICKS;
        return true;
    }

    private static boolean isPermittedAttacker(LivingEntity entity) {
        return entity instanceof Monster || entity instanceof ServerPlayer player
                && !player.isCreative() && !player.isSpectator();
    }

    @Override
    public void die(DamageSource source) {
        if (level() instanceof ServerLevel level && !settlementId.isBlank()) {
            DefenseSavedData.get(level).markDead(getUUID().toString(), settlementId);
        }
        super.die(source);
    }

    @Override
    protected void customServerAiStep(ServerLevel level) {
        stopAtClosedBridge(level);
        LivingEntity target = getTarget();
        if (target != null) {
            boolean expired = alertTicks <= 0 || !target.isAlive() || target.level() != level
                    || !isPermittedAttacker(target)
                    || distanceToSqr(target) > DEFENSE_RADIUS * DEFENSE_RADIUS
                    || target.distanceToSqr(home.getX() + 0.5, home.getY() + 0.5, home.getZ() + 0.5)
                            > DEFENSE_RADIUS * DEFENSE_RADIUS;
            if (expired) {
                setTarget(null);
                getNavigation().stop();
                alertTicks = 0;
            } else {
                alertTicks--;
            }
        }
        super.customServerAiStep(level);
        stopAtClosedBridge(level);
        if (getTarget() == null && !settlementId.isBlank()
                && distanceToSqr(home.getX() + 0.5, home.getY(), home.getZ() + 0.5) > 16.0) {
            getNavigation().moveTo(home.getX() + 0.5, home.getY(), home.getZ() + 0.5, 1.0);
            stopAtClosedBridge(level);
        }
    }

    private void stopAtClosedBridge(ServerLevel level) {
        var path = getNavigation().getPath();
        if (path == null) {
            return;
        }
        var transport = TransportSavedData.get(level);
        for (int index = path.getNextNodeIndex(); index < path.getNodeCount(); index++) {
            if (transport.isBridgeClosedAt(path.getNodePos(index))) {
                getNavigation().stop();
                return;
            }
        }
    }

    /** Package-private: only GolemWorkshop may apply a paid upgrade. */
    void applyPaidUpgrade(GolemTier next) {
        if (next == null || tier.next().orElse(null) != next) {
            throw new IllegalArgumentException("Golem upgrades must advance one tier");
        }
        float missingHealth = getMaxHealth() - getHealth();
        tier = next;
        applyTierAttributes();
        setHealth(Math.max(1.0F, getMaxHealth() - missingHealth));
    }

    /** Package-private: only GolemWorkshop may apply a paid repair. */
    void applyPaidRepair() {
        heal((float) Math.max(4.0, tier.maxHealth() / 4.0));
    }

    private void applyTierAttributes() {
        var health = getAttribute(Attributes.MAX_HEALTH);
        var damage = getAttribute(Attributes.ATTACK_DAMAGE);
        var speed = getAttribute(Attributes.MOVEMENT_SPEED);
        if (health != null) {
            health.setBaseValue(tier.maxHealth());
        }
        if (damage != null) {
            damage.setBaseValue(tier.attackDamage());
        }
        if (speed != null) {
            speed.setBaseValue(tier.movementSpeed());
        }
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output);
        output.putString("GoblinGolemTier", tier.name());
        output.putString("GoblinGolemSettlement", settlementId);
        output.store("GoblinGolemHome", BlockPos.CODEC, home);
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        try {
            tier = GolemTier.valueOf(input.getStringOr("GoblinGolemTier", "WOOD"));
        } catch (IllegalArgumentException exception) {
            tier = GolemTier.WOOD;
        }
        settlementId = input.getStringOr("GoblinGolemSettlement", "");
        home = input.read("GoblinGolemHome", BlockPos.CODEC).orElse(blockPosition()).immutable();
        alertTicks = 0;
        setTarget(null);
        applyTierAttributes();
        setHealth(Math.min(getHealth(), getMaxHealth()));
    }

    @Override
    public boolean removeWhenFarAway(double distanceToClosestPlayer) {
        return false;
    }
}
