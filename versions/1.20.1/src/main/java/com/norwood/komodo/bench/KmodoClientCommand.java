package com.norwood.komodo.bench;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import com.norwood.komodo.Komodo;
import com.norwood.komodo.client.render.kmodo.KmodoConfig;
import com.norwood.komodo.client.render.kmodo.KmodoDebug;
import com.norwood.komodo.client.render.kmodo.KmodoGarage;
import com.norwood.komodo.client.render.kmodo.KmodoProfiler;

@Mod.EventBusSubscriber(modid = Komodo.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class KmodoClientCommand {

    private KmodoClientCommand() {}

    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("kmodoc");

        root.then(Commands.literal("profile")
                .then(Commands.literal("on").executes(ctx -> profile(ctx.getSource(), true)))
                .then(Commands.literal("off").executes(ctx -> profile(ctx.getSource(), false))));

        root.then(Commands.literal("report")
                .executes(ctx -> report(ctx.getSource())));

        root.then(Commands.literal("run")
                .then(Commands.argument("label", StringArgumentType.word())
                        .then(Commands.argument("seconds", IntegerArgumentType.integer(1, 3600))
                                .executes(ctx -> run(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "label"),
                                        IntegerArgumentType.getInteger(ctx, "seconds"))))));

        root.then(Commands.literal("ab")
                .then(Commands.argument("seconds", IntegerArgumentType.integer(1, 3600))
                        .executes(ctx -> ab(ctx.getSource(),
                                IntegerArgumentType.getInteger(ctx, "seconds")))));

        root.then(Commands.literal("dormancy")
                .then(Commands.literal("on").executes(ctx -> dormancy(ctx.getSource(), true)))
                .then(Commands.literal("off").executes(ctx -> dormancy(ctx.getSource(), false))));

        root.then(Commands.literal("flywheel")
                .then(Commands.literal("on").executes(ctx -> flywheel(ctx.getSource(), true)))
                .then(Commands.literal("off").executes(ctx -> flywheel(ctx.getSource(), false))));

        root.then(Commands.literal("garage")
                .then(Commands.literal("on").executes(ctx -> garage(ctx.getSource(), true)))
                .then(Commands.literal("off").executes(ctx -> garage(ctx.getSource(), false))));

        event.getDispatcher().register(root);
    }

    private static int profile(CommandSourceStack source, boolean on) {
        KmodoProfiler.setEnabled(on);
        if (on) {
            KmodoDebug.setEnabled(true);
        }
        ok(source, "profiler " + (on ? "ON (KmodoDebug tracking forced on)" : "OFF"));
        return 1;
    }

    private static int report(CommandSourceStack source) {
        if (!KmodoProfiler.enabled()) {
            fail(source, "profiler is OFF — run /kmodoc profile on first.");
            return 0;
        }
        KmodoProfiler.Snapshot s = KmodoProfiler.snapshot();
        line(source, ChatFormatting.LIGHT_PURPLE, String.format(
                "=== profiler snapshot (n=%d frames) ===", s.frames));
        line(source, ChatFormatting.WHITE, String.format(
                "fps %.0f (mc %.0f)  frame %.2fms", s.windowFps, s.mcFps, s.avgFrameMs));
        line(source, ChatFormatting.GOLD, String.format(
                "veh CPU (aggregate across workers) %.2f ms/frame  p95 %.2f  ~%.1f%% frame (wall est)",
                s.aggCpuMsPerFrameAvg, s.aggCpuMsPerFrameP95, s.pctOfFrame));
        line(source, ChatFormatting.GOLD, String.format(
                "per-vehicle %.1f us  updated/frame %.1f  processed/frame %.1f  skipped/frame %.1f",
                s.perVehicleCpuUs, s.updatedPerFrame, s.processedPerFrame, s.skippedPerFrame));
        line(source, ChatFormatting.AQUA, String.format(
                "state active %.1f settling %.1f dormant %.1f  wake/s %.1f",
                s.stateActiveAvg, s.stateSettlingAvg, s.stateDormantAvg, s.wakePerSec));
        line(source, ChatFormatting.GREEN, String.format(
                "megabuffer: instances/frame %.0f  live %d  meshes %d  verts %d  gpu %dkB",
                s.instancesPerFrame, s.liveInstances, s.meshCount, s.totalVertices, s.gpuBytes / 1024));
        line(source, ChatFormatting.DARK_GREEN, String.format(
                "garage: %s  pools %d  live %d  holes %d  gpu %dkB",
                KmodoConfig.garageEnabled() ? "ON" : "OFF",
                KmodoGarage.poolCount(), KmodoGarage.liveSlices(), KmodoGarage.holes(),
                KmodoGarage.gpuBytes() / 1024));
        if (s.updatedPerFrame < 1.0) {
            line(source, ChatFormatting.YELLOW,
                    "warning: ~0 vehicles updated — point the camera at the fleet (frustum culling).");
        }
        return 1;
    }

    private static int run(CommandSourceStack source, String label, int seconds) {
        if (KmodoBench.startRun(label, seconds)) {
            ok(source, "run '" + label + "' capturing for " + seconds + "s...");
            return 1;
        }
        fail(source, "a run/ab capture is already active.");
        return 0;
    }

    private static int ab(CommandSourceStack source, int seconds) {
        if (KmodoBench.startAb(seconds)) {
            ok(source, "A/B started: " + seconds + "s per arm (OFF then ON), plus settle time. "
                    + "Keep the fleet on-screen.");
            return 1;
        }
        fail(source, "a run/ab capture is already active.");
        return 0;
    }

    private static int dormancy(CommandSourceStack source, boolean on) {
        KmodoConfig.setDormancy(on);
        ok(source, "dormancy " + (on ? "ON" : "OFF"));
        return 1;
    }

    private static int flywheel(CommandSourceStack source, boolean on) {
        KmodoConfig.setFlywheel(on);
        ok(source, "flywheel " + (on ? "ON" : "OFF"));
        return 1;
    }

    private static int garage(CommandSourceStack source, boolean on) {
        KmodoConfig.setGarage(on);
        ok(source, "garage " + (on ? "ON" : "OFF"));
        return 1;
    }

    private static void ok(CommandSourceStack source, String text) {
        source.sendSuccess(() -> Component.literal("[kmodo] ").withStyle(ChatFormatting.LIGHT_PURPLE)
                .append(Component.literal(text).withStyle(ChatFormatting.GREEN)), false);
    }

    private static void fail(CommandSourceStack source, String text) {
        source.sendFailure(Component.literal("[kmodo] " + text));
    }

    private static void line(CommandSourceStack source, ChatFormatting color, String text) {
        source.sendSuccess(() -> Component.literal(text).withStyle(color), false);
    }
}
