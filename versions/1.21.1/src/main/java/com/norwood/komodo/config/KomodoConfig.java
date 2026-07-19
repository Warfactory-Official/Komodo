package com.norwood.komodo.config;

import net.neoforged.neoforge.common.ModConfigSpec;

import com.norwood.komodo.Komodo;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class KomodoConfig {

    private KomodoConfig() {}

    private static final boolean DEFAULT_PROBE_ALL_VEHICLES = false;

    private static volatile boolean probeAllVehicles = DEFAULT_PROBE_ALL_VEHICLES;
    private static volatile Set<String> probeVehicleIds = Set.of();
    private static volatile int probeVehiclesGen;

    public static final ModConfigSpec SPEC;
    private static final ModConfigSpec.ConfigValue<List<? extends String>> PROBE_VEHICLES;
    private static final ModConfigSpec.BooleanValue PROBE_ALL_VEHICLES;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        builder.push("dormancy");

        PROBE_VEHICLES = builder
                .comment(
                        "Vehicle ids that keep animating on their own while fully idle and so must be periodically re-checked.",
                        "Example: [\"superbwarfare:some_idle_animated_vehicle\"]")
                .defineListAllowEmpty("probeVehicles", List.of(), o -> o instanceof String);

        PROBE_ALL_VEHICLES = builder
                .comment("When true, EVERY vehicle is probed regardless of the list above.")
                .define("probeAllVehicles", DEFAULT_PROBE_ALL_VEHICLES);

        builder.pop();
        SPEC = builder.build();
    }

    public static boolean shouldProbeVehicle(String entityId) {
        return probeAllVehicles || probeVehicleIds.contains(entityId);
    }

    public static int probeVehiclesGeneration() {
        return probeVehiclesGen;
    }

    public static void bake() {
        probeAllVehicles = PROBE_ALL_VEHICLES.get();
        probeVehicleIds = parseProbeVehicleIds(PROBE_VEHICLES.get());
        probeVehiclesGen++;
        Komodo.LOGGER.info("Komodo dormancy probe: {} ({} listed)",
                probeAllVehicles ? "ALL" : "listed-only", probeVehicleIds.size());
    }

    private static Set<String> parseProbeVehicleIds(List<? extends String> lines) {
        if (lines == null || lines.isEmpty()) {
            return Set.of();
        }
        Set<String> ids = new HashSet<>();
        for (String raw : lines) {
            if (raw == null) continue;
            String id = raw.trim();
            if (!id.isEmpty()) {
                ids.add(id);
            }
        }
        return ids.isEmpty() ? Set.of() : ids;
    }
}
