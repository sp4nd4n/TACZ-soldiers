package com.spandan.taczsoldiers;

import net.minecraftforge.common.ForgeConfigSpec;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public final class SoldierConfig {
    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.BooleanValue HOSTILE;
    public static final ForgeConfigSpec.DoubleValue GUN_DROP_CHANCE;
    public static final ForgeConfigSpec.DoubleValue HEALTH_MULTIPLIER;
    public static final Map<SoldierRole, ForgeConfigSpec.ConfigValue<List<? extends String>>> GUN_OVERRIDES =
            new EnumMap<>(SoldierRole.class);

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();

        b.push("general");
        HOSTILE = b.comment("Soldiers attack players on sight. Set false to make them passive until hurt.")
                .define("hostileToPlayers", true);
        GUN_DROP_CHANCE = b.comment("Chance (0-1) that a soldier drops its gun when killed.")
                .defineInRange("gunDropChance", 0.2, 0.0, 1.0);
        HEALTH_MULTIPLIER = b.comment("Multiplies every role's health.")
                .defineInRange("healthMultiplier", 1.0, 0.1, 20.0);
        b.pop();

        b.comment("Force specific TaCZ gun ids per role, e.g. [\"tacz:m4a1\"]. Empty = pick a random installed gun of the right class.")
                .push("gunOverrides");
        for (SoldierRole role : SoldierRole.values()) {
            GUN_OVERRIDES.put(role, b.defineListAllowEmpty(role.id, List.of(), o -> o instanceof String));
        }
        b.pop();

        SPEC = b.build();
    }

    private SoldierConfig() {
    }
}
