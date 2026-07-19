package com.norwood.komodo.client.debug;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;

import com.norwood.komodo.Komodo;
import com.norwood.komodo.bench.KmodoBench;
import com.norwood.komodo.client.render.kmodo.KmodoDebug;
import com.norwood.komodo.client.render.kmodo.KmodoDebug.ModelStats;
import com.norwood.komodo.client.render.kmodo.KmodoProfiler;

@EventBusSubscriber(modid = Komodo.MOD_ID, value = Dist.CLIENT)
public final class KmodoDebugHandler {

    private KmodoDebugHandler() {}

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        KmodoBench.tick();

        if (KmodoDebugKeyMappings.TOGGLE.consumeClick()) {
            boolean nowOn = KmodoDebug.toggle();
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                if (nowOn) {
                    mc.player.displayClientMessage(
                            Component.literal("[Komodo] ")
                                    .withStyle(ChatFormatting.AQUA)
                                    .append(Component.literal("Kmodo debug ON — dumping stats...")
                                            .withStyle(ChatFormatting.GREEN)),
                            false);

                    dumpToChat(mc);
                } else {
                    mc.player.displayClientMessage(
                            Component.literal("[Komodo] ")
                                    .withStyle(ChatFormatting.AQUA)
                                    .append(Component.literal("Kmodo debug OFF")
                                            .withStyle(ChatFormatting.GRAY)),
                            false);
                }
            } else if (nowOn) {

                KmodoDebug.dump();
            }
        }

        if (KmodoDebug.enabled() && KmodoDebugKeyMappings.DUMP.consumeClick()) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                dumpToChat(mc);
            } else {
                KmodoDebug.dump();
            }
        }
    }

    private static void dumpToChat(Minecraft mc) {
        String full = KmodoDebug.dump();
        for (String line : full.split("\n")) {
            if (line.isBlank()) continue;
            ChatFormatting color;
            if (line.startsWith("===")) {
                color = ChatFormatting.AQUA;
            } else if (line.contains("FLYWHEEL")) {
                color = ChatFormatting.GREEN;
            } else if (line.contains("RETAINED")) {
                color = ChatFormatting.YELLOW;
            } else if (line.contains("VANILLA")) {
                color = ChatFormatting.GRAY;
            } else {
                color = ChatFormatting.WHITE;
            }
            mc.player.displayClientMessage(
                    Component.literal(line).withStyle(color), false);
        }
    }

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {

        if (KmodoProfiler.enabled()) {
            KmodoProfiler.rollFrame();
        }
        if (KmodoDebug.enabled()) {
            KmodoDebug.beginFrame();
        }

        if (!KmodoDebug.enabled() && !KmodoProfiler.enabled()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        GuiGraphics gg = event.getGuiGraphics();
        int x = 6;
        int y = 6;
        final int lineH = 10;

        if (KmodoDebug.enabled()) {

        gg.drawString(mc.font,
                "§b[KmodoDebug]§r (TOGGLE=off / DUMP=refresh)", x, y, 0xFFFFFF);
        y += lineH;

        if (KmodoDebug.allStats().isEmpty()) {
            gg.drawString(mc.font,
                    "§7  (no models tracked — drive a vehicle)", x, y, 0xAAAAAA);
            y += lineH;
        } else {

        for (ModelStats s : KmodoDebug.allStats()) {
            KmodoDebug.Mode mode = s.lastMode;
            int modeColor;
            String modeTag;
            if (mode == KmodoDebug.Mode.FLYWHEEL) {
                modeColor = 0x55FF55;
                modeTag = "FLY";
            } else if (mode == KmodoDebug.Mode.RETAINED) {
                modeColor = 0xFFFF55;
                modeTag = "RET";
            } else if (mode == KmodoDebug.Mode.VANILLA) {
                modeColor = 0xAAAAAA;
                modeTag = "VAN";
            } else {
                modeColor = 0x888888;
                modeTag = "???";
            }

            String label = s.res.getPath();
            int slash = label.lastIndexOf('/');
            if (slash >= 0) label = label.substring(slash + 1);
            if (label.endsWith(".geo.json")) label = label.substring(0, label.length() - 9);

            String detail;
            if (mode == KmodoDebug.Mode.FLYWHEEL) {
                detail = String.format(" b=%dv d=%d(%dv) %dkB live=%d drm=%d act=%d",
                        s.flywheelBodyVertices, s.flywheelDynamicBoneCount, s.flywheelDynamicVertices,
                        s.flywheelGpuBytes / 1024,
                        s.flywheelLiveInstances.get(),
                        s.dormantLastFrame, s.activeLastFrame);
            } else if (mode == KmodoDebug.Mode.RETAINED) {
                detail = String.format(" vbos=%d verts=%d frm=%d",
                        s.retainedVboCount, s.retainedTotalVertices,
                        s.retainedFrameLast);
            } else {
                detail = "";
            }

            gg.drawString(mc.font,
                    "[" + modeTag + "] " + label + detail,
                    x, y, modeColor);
            y += lineH;
        }
        }
        y += lineH;
        }

        if (KmodoProfiler.enabled()) {
            drawProfiler(mc, gg, x, y, lineH);
        }
    }

    private static void drawProfiler(Minecraft mc, GuiGraphics gg, int x, int y, int lineH) {
        KmodoProfiler.Snapshot s = KmodoProfiler.snapshot();

        gg.drawString(mc.font, "§d[KmodoProfiler]§r "
                + (KmodoProfiler.isRunActive() ? "§e(capturing)" : "") + " n=" + s.frames, x, y, 0xFFFFFF);
        y += lineH;

        gg.drawString(mc.font, String.format(
                "§7fps §f%.0f§7 (mc %.0f)  frame §f%.2f§7ms", s.windowFps, s.mcFps, s.avgFrameMs),
                x, y, 0xFFFFFF);
        y += lineH;

        gg.drawString(mc.font, String.format(
                "§7rt-cpu §f%.2f§7ms  other §f%.2f§7ms  gpu-garage §f%.2f§7ms  wrk-agg §f%.2f§7ms",
                s.renderThreadMsPerFrame, s.unaccountedMsPerFrame, s.gpuMsPerFrame, s.aggCpuMsPerFrameAvg),
                x, y, 0xFFDD55);
        y += lineH;

        gg.drawString(mc.font, String.format(
                "§c▲ TOP §f%s §c%.2f§7ms  §7per-veh §f%.1f§7us  upd/frame §f%.1f",
                s.topPhase, s.topPhaseMs, s.perVehicleCpuUs, s.updatedPerFrame),
                x, y, 0xFF6666);
        y += lineH;

        gg.drawString(mc.font, String.format(
                "§7ph(us) anim §f%.0f§7 walk §f%.0f§7 dorm §f%.0f§7 | bake §f%.0f§7 relit §f%.0f",
                ns(s, KmodoProfiler.Phase.ANIMATE), ns(s, KmodoProfiler.Phase.WALK),
                ns(s, KmodoProfiler.Phase.DORMANCY), ns(s, KmodoProfiler.Phase.BAKE),
                ns(s, KmodoProfiler.Phase.RELIGHT)),
                x, y, 0xAAAAAA);
        y += lineH;

        gg.drawString(mc.font, String.format(
                "§7garage(us) gbake §f%.0f§7 gdraw §f%.0f§7 gcomp §f%.0f",
                ns(s, KmodoProfiler.Phase.GARAGE_BAKE), ns(s, KmodoProfiler.Phase.GARAGE_DRAW),
                ns(s, KmodoProfiler.Phase.GARAGE_COMPACT)),
                x, y, 0x55FF55);
        y += lineH;

        gg.drawString(mc.font, String.format(
                "§7state act §f%.1f§7 set §f%.1f§7 drm §f%.1f§7  wake/s §f%.1f",
                s.stateActiveAvg, s.stateSettlingAvg, s.stateDormantAvg, s.wakePerSec),
                x, y, 0x55FFFF);
        y += lineH;

        gg.drawString(mc.font, String.format(
                "§7garage: pools §f%d§7 slices §f%d§7 holes §f%d§7 gpu §f%d§7kB  §8| fly inst/f §f%.0f",
                s.garagePools, s.garageSlices, s.garageHoles, s.garageGpuBytes / 1024, s.instancesPerFrame),
                x, y, 0x55FF55);
    }

    private static double ns(KmodoProfiler.Snapshot s, KmodoProfiler.Phase p) {
        return s.phaseAvgNanos[p.ordinal()] / 1000.0;
    }
}
