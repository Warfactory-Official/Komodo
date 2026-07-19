package com.norwood.komodo.bench;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;

import com.atsuishio.superbwarfare.entity.vehicle.base.GeoVehicleEntity;
import com.norwood.komodo.Komodo;

import java.util.ArrayList;
import java.util.List;

@EventBusSubscriber(modid = Komodo.MOD_ID, bus = EventBusSubscriber.Bus.GAME)
public final class KmodoGarageCommand {

    private KmodoGarageCommand() {}

    public static final String BENCH_TAG = "komodo_bench";

    private static final int MAX_COUNT = 1000;
    private static final int DEFAULT_SPACING = 6;

    private static final ResourceLocation[] PREFERRED_DEFAULTS = {
            ResourceLocation.fromNamespaceAndPath("superbwarfare", "bmp_2"),
            ResourceLocation.fromNamespaceAndPath("superbwarfare", "yx_100"),
            ResourceLocation.fromNamespaceAndPath("superbwarfare", "bradley"),
    };

    private static volatile List<ResourceLocation> cachedVehicleTypes;

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("kmodo")
                .requires(source -> source.hasPermission(2));

        root.then(Commands.literal("garage")
                .then(Commands.argument("count", IntegerArgumentType.integer(1, MAX_COUNT))
                        .executes(ctx -> garage(ctx.getSource(),
                                IntegerArgumentType.getInteger(ctx, "count"), null, DEFAULT_SPACING))
                        .then(Commands.literal("mix")
                                .executes(ctx -> garage(ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "count"), "mix", DEFAULT_SPACING))
                                .then(Commands.argument("spacing", IntegerArgumentType.integer(1, 64))
                                        .executes(ctx -> garage(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "count"), "mix",
                                                IntegerArgumentType.getInteger(ctx, "spacing")))))
                        .then(Commands.argument("entityId", ResourceLocationArgument.id())
                                .executes(ctx -> garage(ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "count"),
                                        ResourceLocationArgument.getId(ctx, "entityId").toString(), DEFAULT_SPACING))
                                .then(Commands.argument("spacing", IntegerArgumentType.integer(1, 64))
                                        .executes(ctx -> garage(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "count"),
                                                ResourceLocationArgument.getId(ctx, "entityId").toString(),
                                                IntegerArgumentType.getInteger(ctx, "spacing")))))));

        root.then(Commands.literal("clear")
                .executes(ctx -> clear(ctx.getSource())));

        event.getDispatcher().register(root);
    }

    private static int garage(CommandSourceStack source, int count, String entityArg, int spacing) {
        ServerLevel level = source.getLevel();
        if (level == null) {
            source.sendFailure(Component.literal("[kmodo] no server level available."));
            return 0;
        }

        boolean mix = "mix".equalsIgnoreCase(entityArg);
        List<ResourceLocation> types = discoverVehicleTypes(level);
        if (types.isEmpty()) {
            source.sendFailure(Component.literal("[kmodo] no GeoVehicleEntity types are registered."));
            return 0;
        }

        List<EntityType<?>> spawnTypes = new ArrayList<>();
        if (mix) {
            for (ResourceLocation id : types) {
                spawnTypes.add(BuiltInRegistries.ENTITY_TYPE.get(id));
            }
        } else {
            ResourceLocation chosen;
            if (entityArg != null) {
                chosen = ResourceLocation.tryParse(entityArg);
                if (chosen == null || !types.contains(chosen)) {
                    source.sendFailure(Component.literal(
                            "[kmodo] '" + entityArg + "' is not a registered vehicle. Use 'mix' or one of: "
                                    + summarize(types)));
                    return 0;
                }
            } else {
                chosen = pickDefault(types);
            }
            spawnTypes.add(BuiltInRegistries.ENTITY_TYPE.get(chosen));
        }

        Vec3 center = source.getPosition();
        int side = (int) Math.ceil(Math.sqrt(count));
        double half = (side - 1) * spacing / 2.0;

        int spawned = 0;
        int failed = 0;
        for (int i = 0; i < count; i++) {
            int gx = i % side;
            int gz = i / side;
            double px = center.x - half + gx * spacing;
            double pz = center.z - half + gz * spacing;
            double py = center.y + 0.5;

            EntityType<?> type = spawnTypes.get(i % spawnTypes.size());
            if (type == null) {
                failed++;
                continue;
            }
            Entity e;
            try {
                e = type.create(level);
            } catch (Throwable t) {
                failed++;
                continue;
            }
            if (e == null) {
                failed++;
                continue;
            }
            e.moveTo(px, py, pz, source.getRotation().y, 0.0F);
            e.addTag(BENCH_TAG);
            if (level.addFreshEntity(e)) {
                spawned++;
            } else {
                e.discard();
                failed++;
            }
        }

        int finalSpawned = spawned;
        int finalFailed = failed;
        String fleet = mix ? (spawnTypes.size() + " mixed types") : summarize(List.of(
                BuiltInRegistries.ENTITY_TYPE.getKey(spawnTypes.get(0))));
        source.sendSuccess(() -> Component.literal(
                "[kmodo] garage: spawned " + finalSpawned + " of " + count + " (" + finalFailed
                        + " failed) [" + fleet + "] spacing=" + spacing
                        + ". Face the fleet before running /kmodoc ab."), true);
        return finalSpawned;
    }

    private static int clear(CommandSourceStack source) {
        int removed = 0;
        if (source.getServer() != null) {
            for (ServerLevel level : source.getServer().getAllLevels()) {
                List<Entity> doomed = new ArrayList<>();
                for (Entity e : level.getAllEntities()) {
                    if (e.getTags().contains(BENCH_TAG)) {
                        doomed.add(e);
                    }
                }
                for (Entity e : doomed) {
                    e.discard();
                    removed++;
                }
            }
        }
        int finalRemoved = removed;
        source.sendSuccess(() -> Component.literal(
                "[kmodo] clear: removed " + finalRemoved + " benchmark vehicle(s)."), true);
        return removed;
    }

    private static ResourceLocation pickDefault(List<ResourceLocation> available) {
        for (ResourceLocation preferred : PREFERRED_DEFAULTS) {
            if (available.contains(preferred)) {
                return preferred;
            }
        }
        return available.get(0);
    }

    private static String summarize(List<ResourceLocation> ids) {
        StringBuilder sb = new StringBuilder();
        int shown = Math.min(ids.size(), 8);
        for (int i = 0; i < shown; i++) {
            if (i > 0) sb.append(", ");
            sb.append(ids.get(i));
        }
        if (ids.size() > shown) {
            sb.append(", ... (").append(ids.size()).append(" total)");
        }
        return sb.toString();
    }

    private static List<ResourceLocation> discoverVehicleTypes(ServerLevel level) {
        List<ResourceLocation> cached = cachedVehicleTypes;
        if (cached != null) {
            return cached;
        }
        synchronized (KmodoGarageCommand.class) {
            if (cachedVehicleTypes != null) {
                return cachedVehicleTypes;
            }
            List<ResourceLocation> found = new ArrayList<>();
            for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
                Entity probe;
                try {
                    probe = type.create(level);
                } catch (Throwable t) {
                    continue;
                }
                if (probe instanceof GeoVehicleEntity) {
                    ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
                    if (id != null) {
                        found.add(id);
                    }
                }
                if (probe != null) {
                    probe.discard();
                }
            }
            found.sort((a, b) -> a.toString().compareTo(b.toString()));
            cachedVehicleTypes = found;
            Komodo.LOGGER.info("[kmodo] discovered {} GeoVehicleEntity types for the garage harness.", found.size());
            return found;
        }
    }
}
