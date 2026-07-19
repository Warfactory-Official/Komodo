package com.norwood.komodo.bench;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import com.norwood.komodo.Komodo;
import com.norwood.komodo.client.render.kmodo.KmodoConfig;
import com.norwood.komodo.client.render.kmodo.KmodoDebug;
import com.norwood.komodo.client.render.kmodo.KmodoProfiler;

public final class KmodoBench {

    private KmodoBench() {}

    private enum Stage { IDLE, RUN_CAPTURE, AB_SETTLE_OFF, AB_CAPTURE_OFF, AB_SETTLE_ON, AB_CAPTURE_ON }

    private static final long SETTLE_NANOS = 1_500_000_000L;

    private static volatile Stage stage = Stage.IDLE;
    private static long stageDeadline = 0L;
    private static long captureNanos = 0L;
    private static String label = "";
    private static boolean priorDormancy = true;

    private static KmodoProfiler.Run runOff = null;

    public static boolean isBusy() {
        return stage != Stage.IDLE;
    }

    public static synchronized boolean startRun(String runLabel, int seconds) {
        if (isBusy() || KmodoProfiler.isRunActive()) {
            return false;
        }
        label = runLabel;
        captureNanos = seconds * 1_000_000_000L;
        KmodoDebug.setEnabled(true);
        KmodoProfiler.setEnabled(true);
        KmodoProfiler.startRun(runLabel);
        stage = Stage.RUN_CAPTURE;
        stageDeadline = System.nanoTime() + captureNanos;
        return true;
    }

    public static synchronized boolean startAb(int seconds) {
        if (isBusy() || KmodoProfiler.isRunActive()) {
            return false;
        }
        captureNanos = seconds * 1_000_000_000L;
        priorDormancy = KmodoConfig.dormancyEnabled();
        runOff = null;
        KmodoDebug.setEnabled(true);
        KmodoProfiler.setEnabled(true);

        KmodoConfig.setDormancy(false);
        stage = Stage.AB_SETTLE_OFF;
        stageDeadline = System.nanoTime() + SETTLE_NANOS;
        message(ChatFormatting.AQUA, "A/B started: settling (dormancy OFF)...");
        return true;
    }

    public static synchronized void tick() {
        if (stage == Stage.IDLE) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            abort();
            return;
        }

        long now = System.nanoTime();
        if (now < stageDeadline) {
            return;
        }

        switch (stage) {
            case RUN_CAPTURE -> {
                KmodoProfiler.Run run = KmodoProfiler.stopRun();
                stage = Stage.IDLE;
                if (run != null) {
                    message(ChatFormatting.GREEN, "run '" + label + "' done: " + run.summaryLine());
                }
            }
            case AB_SETTLE_OFF -> {
                KmodoProfiler.startRun("ab-dormancy-off");
                stage = Stage.AB_CAPTURE_OFF;
                stageDeadline = now + captureNanos;
                message(ChatFormatting.AQUA, "capturing arm A (dormancy OFF)...");
            }
            case AB_CAPTURE_OFF -> {
                runOff = KmodoProfiler.stopRun();
                KmodoConfig.setDormancy(true);
                stage = Stage.AB_SETTLE_ON;
                stageDeadline = now + SETTLE_NANOS;
                message(ChatFormatting.AQUA, "settling (dormancy ON)...");
            }
            case AB_SETTLE_ON -> {
                KmodoProfiler.startRun("ab-dormancy-on");
                stage = Stage.AB_CAPTURE_ON;
                stageDeadline = now + captureNanos;
                message(ChatFormatting.AQUA, "capturing arm B (dormancy ON)...");
            }
            case AB_CAPTURE_ON -> {
                KmodoProfiler.Run runOn = KmodoProfiler.stopRun();
                KmodoConfig.setDormancy(priorDormancy);
                stage = Stage.IDLE;
                finishAb(runOff, runOn);
            }
            default -> stage = Stage.IDLE;
        }
    }

    private static void finishAb(KmodoProfiler.Run off, KmodoProfiler.Run on) {
        if (off == null || on == null) {
            message(ChatFormatting.RED, "A/B aborted: missing capture data.");
            return;
        }
        String block = KmodoProfiler.compareRuns(off, on);
        Komodo.LOGGER.info("[kmodo] A/B result:\n{}", block);
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            for (String line : block.split("\n")) {
                if (line.isBlank()) continue;
                ChatFormatting color = line.startsWith("===") ? ChatFormatting.AQUA : ChatFormatting.WHITE;
                mc.player.displayClientMessage(Component.literal(line).withStyle(color), false);
            }
        }
        message(ChatFormatting.GREEN, "A/B complete (dormancy restored to " + priorDormancy + "). CSVs written.");
    }

    private static void abort() {
        boolean wasAb = stage == Stage.AB_SETTLE_OFF || stage == Stage.AB_CAPTURE_OFF
                || stage == Stage.AB_SETTLE_ON || stage == Stage.AB_CAPTURE_ON;
        if (KmodoProfiler.isRunActive()) {
            KmodoProfiler.stopRun();
        }
        if (wasAb) {
            KmodoConfig.setDormancy(priorDormancy);
        }
        stage = Stage.IDLE;
        runOff = null;
        Komodo.LOGGER.warn("[kmodo] bench aborted (level/player unavailable); dormancy restored.");
    }

    private static void message(ChatFormatting color, String text) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(
                    Component.literal("[kmodo] ").withStyle(ChatFormatting.LIGHT_PURPLE)
                            .append(Component.literal(text).withStyle(color)),
                    false);
        }
    }
}
