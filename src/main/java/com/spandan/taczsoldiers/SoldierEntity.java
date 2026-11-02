package com.spandan.taczsoldiers;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;

import javax.annotation.Nullable;

public class SoldierEntity extends PathfinderMob {
    private static final EntityDataAccessor<Integer> DATA_ROLE =
            SynchedEntityData.defineId(SoldierEntity.class, EntityDataSerializers.INT);

    private boolean loadoutApplied;
    private boolean gunReady;

    public SoldierEntity(EntityType<? extends SoldierEntity> type, Level level) {
        super(type, level);
        this.xpReward = 10;
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0)
                .add(Attributes.MOVEMENT_SPEED, 0.27)
                .add(Attributes.FOLLOW_RANGE, 64.0)
                .add(Attributes.KNOCKBACK_RESISTANCE, 0.2)
                .add(Attributes.ATTACK_DAMAGE, 2.0);
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(DATA_ROLE, -1);
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(1, new GunAttackGoal(this));
        this.goalSelector.addGoal(2, new MeleeAttackGoal(this, 1.2, false)); // only if it has no gun
        this.goalSelector.addGoal(5, new WaterAvoidingRandomStrollGoal(this, 0.8));
        this.goalSelector.addGoal(6, new LookAtPlayerGoal(this, Player.class, 12.0F));
        this.goalSelector.addGoal(7, new RandomLookAroundGoal(this));

        this.targetSelector.addGoal(1, new HurtByTargetGoal(this, SoldierEntity.class).setAlertOthers());
        this.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Player.class, 10, true, false,
                target -> SoldierConfig.HOSTILE.get()));
    }

    // ---- role & loadout ----------------------------------------------------------------

    public SoldierRole getRole() {
        int i = this.entityData.get(DATA_ROLE);
        SoldierRole[] roles = SoldierRole.values();
        return i >= 0 && i < roles.length ? roles[i] : SoldierRole.RIFLEMAN;
    }

    public void setRole(SoldierRole role) {
        this.entityData.set(DATA_ROLE, role.ordinal());
    }

    public void applyLoadout() {
        SoldierRole role = getRole();

        this.getAttribute(Attributes.MAX_HEALTH).setBaseValue(role.health * SoldierConfig.HEALTH_MULTIPLIER.get());
        this.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(role.speed);
        this.setHealth(this.getMaxHealth());

        role.equipArmor(this);
        ItemStack gun = TaczBridge.createGun(role, this.random);
        this.setItemSlot(EquipmentSlot.MAINHAND, gun);

        this.setDropChance(EquipmentSlot.MAINHAND, SoldierConfig.GUN_DROP_CHANCE.get().floatValue());
        this.setDropChance(EquipmentSlot.HEAD, 0.05F);
        this.setDropChance(EquipmentSlot.CHEST, 0.05F);
        this.setDropChance(EquipmentSlot.LEGS, 0.05F);
        this.setDropChance(EquipmentSlot.FEET, 0.05F);

        this.setCustomName(Component.literal(role.displayName));
        this.setCustomNameVisible(false);
        this.setPersistenceRequired();
        this.loadoutApplied = true;
        this.gunReady = false;
    }

    @Nullable
    public static SoldierEntity spawn(ServerLevel level, SoldierRole role, double x, double y, double z, float yaw) {
        SoldierEntity soldier = TaczSoldiers.SOLDIER.get().create(level);
        if (soldier == null) {
            return null;
        }
        soldier.moveTo(x, y, z, yaw, 0.0F);
        soldier.setRole(role);
        soldier.finalizeSpawn(level, level.getCurrentDifficultyAt(soldier.blockPosition()),
                MobSpawnType.COMMAND, null, null);
        level.addFreshEntity(soldier);
        return soldier;
    }

    @Nullable
    @Override
    public SpawnGroupData finalizeSpawn(ServerLevelAccessor level, DifficultyInstance difficulty,
                                        MobSpawnType reason, @Nullable SpawnGroupData data,
                                        @Nullable CompoundTag tag) {
        data = super.finalizeSpawn(level, difficulty, reason, data, tag);
        if (!this.loadoutApplied) {
            if (this.entityData.get(DATA_ROLE) < 0) {
                SoldierRole[] roles = SoldierRole.values();
                setRole(roles[this.random.nextInt(roles.length)]); // e.g. /summon taczsoldiers:soldier
            }
            applyLoadout();
        }
        return data;
    }

    // ---- gun handling ------------------------------------------------------------------

    public boolean hasGun() {
        return TaczBridge.isGun(this.getMainHandItem());
    }

    public int ammoLeft() {
        return TaczBridge.ammo(this.getMainHandItem());
    }

    public void refillMagazine() {
        TaczBridge.refill(this.getMainHandItem());
    }

    @Override
    public void tick() {
        super.tick();
        if (this.level().isClientSide) {
            return;
        }
        if (!this.gunReady && this.hasGun()) {
            TaczBridge.prepare(this);
            this.gunReady = true;
        }
        if (getRole() == SoldierRole.MEDIC && this.tickCount % 60 == 0) {
            healNearbyAllies();
        }
    }

    private void healNearbyAllies() {
        for (SoldierEntity ally : this.level().getEntitiesOfClass(SoldierEntity.class,
                this.getBoundingBox().inflate(10.0),
                e -> e != this && e.isAlive() && e.getHealth() < e.getMaxHealth())) {
            ally.heal(3.0F);
            if (this.level() instanceof ServerLevel server) {
                server.sendParticles(ParticleTypes.HEART, ally.getX(), ally.getY(1.0), ally.getZ(),
                        3, 0.3, 0.3, 0.3, 0.0);
            }
        }
    }

    // ---- combat rules ------------------------------------------------------------------

    @Override
    public boolean canAttack(LivingEntity target) {
        return !(target instanceof SoldierEntity) && super.canAttack(target);
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (source.getEntity() instanceof SoldierEntity) {
            return false; // no friendly fire
        }
        return super.hurt(source, amount);
    }

    @Override
    public boolean removeWhenFarAway(double distanceToClosestPlayer) {
        return false;
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return SoundEvents.PLAYER_HURT;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.PLAYER_DEATH;
    }

    // ---- save / load -------------------------------------------------------------------

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putInt("SoldierRole", this.entityData.get(DATA_ROLE));
        tag.putBoolean("LoadoutApplied", this.loadoutApplied);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains("SoldierRole")) {
            this.entityData.set(DATA_ROLE, tag.getInt("SoldierRole"));
        }
        this.loadoutApplied = tag.getBoolean("LoadoutApplied");
    }
}
