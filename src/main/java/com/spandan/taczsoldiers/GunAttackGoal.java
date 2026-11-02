package com.spandan.taczsoldiers;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/** Moves, aims and fires the soldier's TaCZ gun at its target. */
public class GunAttackGoal extends Goal {
    private final SoldierEntity mob;

    private int seeTime;
    private int burstLeft;
    private int pause;
    private int reload;
    private int repath;
    private int strafeTime;
    private boolean strafeRight = true;
    private boolean aiming;

    public GunAttackGoal(SoldierEntity mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        LivingEntity target = mob.getTarget();
        return target != null && target.isAlive() && mob.hasGun();
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        mob.setAggressive(true);
        seeTime = 0;
        burstLeft = 0;
        pause = 0;
        reload = 0;
    }

    @Override
    public void stop() {
        mob.setAggressive(false);
        setAiming(false);
        mob.getNavigation().stop();
        seeTime = 0;
    }

    private void setAiming(boolean value) {
        if (aiming != value) {
            aiming = value;
            TaczBridge.aim(mob, value);
        }
    }

    @Override
    public void tick() {
        LivingEntity target = mob.getTarget();
        if (target == null) {
            return;
        }
        SoldierRole role = mob.getRole();
        double dist = mob.distanceTo(target);
        boolean sees = mob.getSensing().hasLineOfSight(target);
        seeTime = sees ? seeTime + 1 : 0;

        mob.getLookControl().setLookAt(target, 40.0F, 40.0F);

        // ---- movement ----
        if (sees && dist < role.retreatRange) {
            if (--repath <= 0) {
                repath = 10;
                Vec3 away = DefaultRandomPos.getPosAway(mob, 12, 6, target.position());
                if (away != null) {
                    mob.getNavigation().moveTo(away.x, away.y, away.z, 1.1);
                }
            }
        } else if (!sees || dist > role.preferredRange) {
            if (--repath <= 0) {
                repath = 10;
                mob.getNavigation().moveTo(target, 1.0);
            }
        } else {
            mob.getNavigation().stop();
            if (role.strafes) {
                if (--strafeTime <= 0) {
                    strafeTime = 20 + mob.getRandom().nextInt(25);
                    strafeRight = !strafeRight;
                }
                mob.getMoveControl().strafe(0.0F, strafeRight ? 0.5F : -0.5F);
                mob.lookAt(target, 30.0F, 30.0F);
            }
        }

        // ---- shooting ----
        if (reload > 0) {
            reload--;
            if (reload == 0) {
                mob.refillMagazine();
            }
            return;
        }

        if (!sees || dist > role.engageRange || seeTime < role.reactionTicks) {
            setAiming(false);
            return;
        }
        setAiming(true);

        if (pause > 0) {
            pause--;
            return;
        }

        if (mob.ammoLeft() <= 0) {
            startReload(role);
            return;
        }

        if (burstLeft <= 0) {
            burstLeft = Mth.nextInt(mob.getRandom(), role.burstMin, role.burstMax);
        }

        Vec3 from = mob.getEyePosition();
        double tx = target.getX();
        double ty = target.getY() + target.getBbHeight() * 0.6;
        double tz = target.getZ();
        double dx = tx - from.x;
        double dy = ty - from.y;
        double dz = tz - from.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);

        float yaw = (float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0F;
        float pitch = (float) (-(Mth.atan2(dy, horizontal) * (180.0 / Math.PI)));
        yaw += (mob.getRandom().nextFloat() - 0.5F) * 2.0F * role.inaccuracy;
        pitch += (mob.getRandom().nextFloat() - 0.5F) * 2.0F * role.inaccuracy;

        String result = TaczBridge.shoot(mob, pitch, yaw);
        switch (result) {
            case "SUCCESS" -> {
                burstLeft--;
                if (burstLeft <= 0) {
                    pause = Mth.nextInt(mob.getRandom(), role.pauseMin, role.pauseMax);
                }
            }
            case "NEED_BOLT" -> TaczBridge.bolt(mob);
            case "NO_AMMO" -> startReload(role);
            default -> {
                // COOL_DOWN, IS_DRAWING, etc. - just try again next tick
            }
        }
    }

    private void startReload(SoldierRole role) {
        reload = role.reloadTicks;
        burstLeft = 0;
        setAiming(false);
    }
}
