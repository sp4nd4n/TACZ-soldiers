package com.spandan.taczsoldiers;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/** /soldier spawn <role|random> [count]   and   /soldier clear */
public final class SoldierCommand {
    private SoldierCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("soldier")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("spawn")
                        .then(Commands.argument("role", StringArgumentType.word())
                                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                                        Stream.concat(Stream.of("random"),
                                                Stream.of(SoldierRole.values()).map(r -> r.id)), builder))
                                .executes(ctx -> spawn(ctx.getSource(), StringArgumentType.getString(ctx, "role"), 1))
                                .then(Commands.argument("count", IntegerArgumentType.integer(1, 50))
                                        .executes(ctx -> spawn(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "role"),
                                                IntegerArgumentType.getInteger(ctx, "count"))))))
                .then(Commands.literal("clear")
                        .executes(ctx -> clear(ctx.getSource()))));
    }

    private static int spawn(CommandSourceStack source, String roleId, int count) {
        boolean random = roleId.equalsIgnoreCase("random");
        SoldierRole fixed = random ? null : SoldierRole.byId(roleId);
        if (!random && fixed == null) {
            source.sendFailure(Component.literal("Unknown role '" + roleId + "'. Use: random, rifleman, sniper, gunner, breacher, operator, officer, medic"));
            return 0;
        }

        ServerLevel level = source.getLevel();
        Vec3 base = source.getPosition();
        RandomSource rnd = level.getRandom();
        SoldierRole[] all = SoldierRole.values();

        int spawned = 0;
        for (int i = 0; i < count; i++) {
            SoldierRole role = fixed != null ? fixed : all[rnd.nextInt(all.length)];
            double x = base.x;
            double z = base.z;
            for (int attempt = 0; attempt < 8; attempt++) {
                double angle = rnd.nextDouble() * Math.PI * 2;
                double radius = 2 + rnd.nextDouble() * 4;
                double cx = base.x + Math.cos(angle) * radius;
                double cz = base.z + Math.sin(angle) * radius;
                if (level.noCollision(TaczSoldiers.SOLDIER.get().getAABB(cx, base.y, cz))) {
                    x = cx;
                    z = cz;
                    break;
                }
            }
            if (SoldierEntity.spawn(level, role, x, base.y, z, rnd.nextFloat() * 360.0F) != null) {
                spawned++;
            }
        }

        final int total = spawned;
        source.sendSuccess(() -> Component.literal("Spawned " + total + " soldier(s)."), true);
        return spawned;
    }

    private static int clear(CommandSourceStack source) {
        List<Entity> found = new ArrayList<>();
        for (Entity entity : source.getLevel().getAllEntities()) {
            if (entity instanceof SoldierEntity) {
                found.add(entity);
            }
        }
        found.forEach(Entity::discard);
        final int total = found.size();
        source.sendSuccess(() -> Component.literal("Removed " + total + " soldier(s)."), true);
        return total;
    }
}
