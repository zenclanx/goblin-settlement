package dev.local.goblinsettlement.defense;

import dev.local.goblinsettlement.citizen.GoblinCitizenEntity;
import dev.local.goblinsettlement.colony.SettlementSavedData;
import dev.local.goblinsettlement.construction.transport.TransportSavedData;
import dev.local.goblinsettlement.sound.ModSounds;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
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
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * Persistent custom golem for five tiers. Legacy iron entities migrate to the vanilla type.
 * It acquires a target only from an explicit
 * resident-damage event; proximity, equipment and warehouse visits never create hostility.
 */
public final class GoblinGolemEntity extends PathfinderMob {
    public static final double DEFENSE_RADIUS = 24.0;
    static final int ALERT_TICKS = 20 * 15;
    /**
     * The tier, mirrored to the client so it can draw the tier's art. A string, like the citizen's
     * synced trade: the enum is ours, so its name is the wire format. Iron is a value of the enum but
     * never one this entity is meant to draw -- see {@link #tierForRender()}.
     */
    private static final EntityDataAccessor<String> DATA_TIER =
            SynchedEntityData.defineId(GoblinGolemEntity.class, EntityDataSerializers.STRING);

    /** The tier the server owns. Its mirror on the wire is DATA_TIER, and setTier is the only writer. */
    private GolemTier tier = GolemTier.WOOD;
    private String settlementId = "";
    private BlockPos home = BlockPos.ZERO;
    private int alertTicks;

    public GoblinGolemEntity(EntityType<? extends GoblinGolemEntity> type, Level level) {
        super(type, level);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_TIER, GolemTier.WOOD.name());
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

    /**
     * The tier's own footfall instead of the generic one. Iron has no custom sound, so it falls through
     * to the vanilla step sound rather than going silent.
     */
    @Override
    protected void playStepSound(BlockPos pos, BlockState state) {
        Optional<SoundEvent> move = tierForRender().flatMap(ModSounds::golemMove);
        if (move.isEmpty()) {
            super.playStepSound(pos, state);
            return;
        }
        playSound(move.orElseThrow(), 0.6F, 1.0F);
    }

    public GolemTier tier() {
        return tier;
    }

    /**
     * The tier the client should draw, or empty when it must draw nothing at all.
     *
     * <p>Two synced values have no drawable art and both read as empty rather than as a tier: a value with
     * no custom art of its own -- iron, the vanilla entity a custom golem is migrated onto -- and a name
     * this build does not know, which only a corrupt or hand-edited save can carry. Neither may reach the
     * renderer as a tier, because the renderer has no art for them and by design throws rather than wear
     * another tier's skin. Drawing nothing for the few seconds until the server's next migration pass
     * corrects the entity is the honest failure, and the lesser evil next to a crash.
     *
     * <p>Iron arrives here only from a save: nothing this build does leaves a world entity sitting on it
     * (a paid upgrade to iron converts straight to the vanilla entity, and the one upgrade path that steps
     * through the iron constant does so on an entity not yet added to the world). So this is a migration
     * and corrupt-data guard, not a path live code reaches.
     */
    public Optional<GolemTier> tierForRender() {
        try {
            GolemTier synced = GolemTier.valueOf(getEntityData().get(DATA_TIER));
            return synced.hasCustomArt() ? Optional.of(synced) : Optional.empty();
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    /**
     * The tier's only writer. A paid upgrade and loading a save are the two ways the tier can change,
     * and both go through here, so the synced copy the client renders from cannot be left behind.
     */
    private void setTier(GolemTier next) {
        tier = next;
        getEntityData().set(DATA_TIER, next.name());
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
        if (!(cause instanceof LivingEntity attacker) || !DefenseCoordinator.isPermittedAttacker(attacker)
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

    /**
     * Call when a settlement sentry reports a hostile it can see. Paired with alertToResidentAttack:
     * the guards below are the same ones, minus the three that need a victim -- a sighting has none.
     * Changing one of the two means checking the other.
     */
    public boolean alertToSighting(LivingEntity threat) {
        if (threat == null || !(level() instanceof ServerLevel level) || settlementId.isBlank()
                || !threat.isAlive() || threat.level() != level
                || !DefenseCoordinator.isPermittedAttacker(threat)
                || distanceToSqr(threat) > DEFENSE_RADIUS * DEFENSE_RADIUS
                || threat.distanceToSqr(home.getX() + 0.5, home.getY() + 0.5, home.getZ() + 0.5)
                        > DEFENSE_RADIUS * DEFENSE_RADIUS) {
            return false;
        }
        SettlementSavedData data = SettlementSavedData.get(level);
        if (data.settlement().map(value -> !value.id().equals(settlementId)).orElse(true)) {
            return false;
        }
        setTarget(threat);
        alertTicks = ALERT_TICKS;
        return true;
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
                    || !DefenseCoordinator.isPermittedAttacker(target)
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
        setTier(next);
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
        GolemTier loaded;
        try {
            loaded = GolemTier.valueOf(input.getStringOr("GoblinGolemTier", "WOOD"));
        } catch (IllegalArgumentException exception) {
            loaded = GolemTier.WOOD;
        }
        setTier(loaded);
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
