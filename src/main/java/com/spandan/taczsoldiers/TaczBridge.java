package com.spandan.taczsoldiers;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Talks to TaCZ through reflection, so this mod needs no TaCZ jar to compile and
 * keeps working across TaCZ versions. Every call is guarded: a failure is logged
 * once and never crashes the game.
 */
public final class TaczBridge {
    private static Boolean ready;
    private static boolean warned;

    private static Method getAllGuns, getGunIndex, idxType, idxData, dataAmmo, dataModes;
    private static Method bCreate, bId, bAmmo, bBarrel, bFire, bBuild;
    private static Method opInit, opDraw, opAim, opShoot, opBolt;
    private static Method gGetAmmo, gSetAmmo, gSetBarrel, gGetId;
    private static Class<?> iGunClass;
    private static Object fireAuto, fireSemi;

    private TaczBridge() {
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static synchronized boolean init() {
        if (ready != null) {
            return ready;
        }
        try {
            Class<?> timeless = Class.forName("com.tacz.guns.api.TimelessAPI");
            getAllGuns = timeless.getMethod("getAllCommonGunIndex");
            getGunIndex = timeless.getMethod("getCommonGunIndex", ResourceLocation.class);

            Class<?> index = Class.forName("com.tacz.guns.resource.index.CommonGunIndex");
            idxType = index.getMethod("getType");
            idxData = index.getMethod("getGunData");

            Class<?> data = Class.forName("com.tacz.guns.resource.pojo.data.gun.GunData");
            dataAmmo = data.getMethod("getAmmoAmount");
            dataModes = data.getMethod("getFireModeSet");

            Class<?> fireModeClass = Class.forName("com.tacz.guns.api.item.gun.FireMode");
            fireAuto = Enum.valueOf((Class) fireModeClass, "AUTO");
            fireSemi = Enum.valueOf((Class) fireModeClass, "SEMI");

            Class<?> builder = Class.forName("com.tacz.guns.api.item.builder.GunItemBuilder");
            bCreate = builder.getMethod("create");
            bId = builder.getMethod("setId", ResourceLocation.class);
            bAmmo = builder.getMethod("setAmmoCount", int.class);
            bBarrel = builder.getMethod("setAmmoInBarrel", boolean.class);
            bFire = builder.getMethod("setFireMode", fireModeClass);
            bBuild = builder.getMethod("build");

            iGunClass = Class.forName("com.tacz.guns.api.item.IGun");
            gGetId = iGunClass.getMethod("getGunId", ItemStack.class);
            gGetAmmo = iGunClass.getMethod("getCurrentAmmoCount", ItemStack.class);
            gSetAmmo = iGunClass.getMethod("setCurrentAmmoCount", ItemStack.class, int.class);
            gSetBarrel = iGunClass.getMethod("setBulletInBarrel", ItemStack.class, boolean.class);

            Class<?> operator = Class.forName("com.tacz.guns.api.entity.IGunOperator");
            opInit = operator.getMethod("initialData");
            opDraw = operator.getMethod("draw", Supplier.class);
            opAim = operator.getMethod("aim", boolean.class);
            opShoot = operator.getMethod("shoot", Supplier.class, Supplier.class);
            opBolt = operator.getMethod("bolt");

            ready = true;
        } catch (Throwable t) {
            TaczSoldiers.LOGGER.error("[TaCZ Soldiers] Could not hook into TaCZ's API - soldiers will have no guns.", t);
            ready = false;
        }
        return ready;
    }

    private static void warn(String what, Throwable t) {
        if (!warned) {
            warned = true;
            TaczSoldiers.LOGGER.error("[TaCZ Soldiers] TaCZ call failed (" + what + "). Further failures are silent.", t);
        }
    }

    public static boolean isGun(ItemStack stack) {
        return !stack.isEmpty() && init() && iGunClass.isInstance(stack.getItem());
    }

    /** All installed guns grouped by class ("rifle", "smg", ...). */
    private static Map<String, List<ResourceLocation>> scanGuns() throws Exception {
        Map<String, List<ResourceLocation>> byType = new HashMap<>();
        for (Object o : (Iterable<?>) getAllGuns.invoke(null)) {
            Map.Entry<?, ?> entry = (Map.Entry<?, ?>) o;
            ResourceLocation id = (ResourceLocation) entry.getKey();
            String type = (String) idxType.invoke(entry.getValue());
            byType.computeIfAbsent(type, k -> new ArrayList<>()).add(id);
        }
        return byType;
    }

    /** Builds a loaded gun for the role, or EMPTY if TaCZ has no suitable gun. */
    public static ItemStack createGun(SoldierRole role, RandomSource rnd) {
        if (!init()) {
            return ItemStack.EMPTY;
        }
        try {
            Map<String, List<ResourceLocation>> byType = scanGuns();
            ResourceLocation chosen = null;

            List<? extends String> overrides = SoldierConfig.GUN_OVERRIDES.get(role).get();
            if (!overrides.isEmpty()) {
                ResourceLocation id = ResourceLocation.tryParse(overrides.get(rnd.nextInt(overrides.size())));
                if (id != null && indexOf(id) != null) {
                    chosen = id;
                } else {
                    TaczSoldiers.LOGGER.warn("[TaCZ Soldiers] Config gun override for {} not found in TaCZ: {}", role.id, id);
                }
            }

            if (chosen == null) {
                for (String type : role.gunTypes) {
                    List<ResourceLocation> list = byType.get(type);
                    if (list != null && !list.isEmpty()) {
                        chosen = list.get(rnd.nextInt(list.size()));
                        break;
                    }
                }
            }

            if (chosen == null) {
                List<ResourceLocation> all = new ArrayList<>();
                byType.forEach((type, list) -> {
                    if (!type.equals("rpg")) {
                        all.addAll(list);
                    }
                });
                if (!all.isEmpty()) {
                    chosen = all.get(rnd.nextInt(all.size()));
                }
            }

            return chosen == null ? ItemStack.EMPTY : buildGun(chosen);
        } catch (Throwable t) {
            warn("createGun", t);
            return ItemStack.EMPTY;
        }
    }

    private static Object indexOf(ResourceLocation id) throws Exception {
        Optional<?> opt = (Optional<?>) getGunIndex.invoke(null, id);
        return opt.orElse(null);
    }

    private static ItemStack buildGun(ResourceLocation id) throws Exception {
        Object index = indexOf(id);
        if (index == null) {
            return ItemStack.EMPTY;
        }
        Object data = idxData.invoke(index);
        int mag = (int) dataAmmo.invoke(data);
        List<?> modes = (List<?>) dataModes.invoke(data);
        Object mode = modes.contains(fireAuto) ? fireAuto : modes.contains(fireSemi) ? fireSemi : modes.get(0);

        Object builder = bCreate.invoke(null);
        bId.invoke(builder, id);
        bAmmo.invoke(builder, mag);
        bBarrel.invoke(builder, true);
        bFire.invoke(builder, mode);
        return (ItemStack) bBuild.invoke(builder);
    }

    public static int magSize(ItemStack stack) {
        try {
            if (!isGun(stack)) {
                return 0;
            }
            ResourceLocation id = (ResourceLocation) gGetId.invoke(stack.getItem(), stack);
            Object index = indexOf(id);
            return index == null ? 0 : (int) dataAmmo.invoke(idxData.invoke(index));
        } catch (Throwable t) {
            warn("magSize", t);
            return 0;
        }
    }

    public static int ammo(ItemStack stack) {
        try {
            return isGun(stack) ? (int) gGetAmmo.invoke(stack.getItem(), stack) : 0;
        } catch (Throwable t) {
            warn("ammo", t);
            return 0;
        }
    }

    /** Instantly refills the magazine (soldiers have unlimited reserve ammo). */
    public static void refill(ItemStack stack) {
        try {
            int mag = magSize(stack);
            if (mag > 0) {
                gSetAmmo.invoke(stack.getItem(), stack, mag);
                gSetBarrel.invoke(stack.getItem(), stack, true);
            }
        } catch (Throwable t) {
            warn("refill", t);
        }
    }

    /** Registers the held gun with TaCZ's shooter logic. Call once the gun is in the main hand. */
    public static void prepare(LivingEntity entity) {
        try {
            opInit.invoke(entity);
            Supplier<ItemStack> hand = entity::getMainHandItem;
            opDraw.invoke(entity, hand);
        } catch (Throwable t) {
            warn("prepare", t);
        }
    }

    public static void aim(LivingEntity entity, boolean aiming) {
        try {
            opAim.invoke(entity, aiming);
        } catch (Throwable t) {
            warn("aim", t);
        }
    }

    public static void bolt(LivingEntity entity) {
        try {
            opBolt.invoke(entity);
        } catch (Throwable t) {
            warn("bolt", t);
        }
    }

    /** Fires one shot toward the given angles. Returns TaCZ's ShootResult name (SUCCESS, COOL_DOWN, NO_AMMO...). */
    public static String shoot(LivingEntity entity, float pitch, float yaw) {
        try {
            Supplier<Float> p = () -> pitch;
            Supplier<Float> y = () -> yaw;
            Object result = opShoot.invoke(entity, p, y);
            return result == null ? "UNKNOWN" : ((Enum<?>) result).name();
        } catch (Throwable t) {
            warn("shoot", t);
            return "ERROR";
        }
    }
}
