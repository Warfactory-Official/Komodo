# Komodo

Komodo is a client-only Minecraft mod that speeds up the rendering of Superb Warfare (SBW)
vehicles. It replaces the default per-frame GeckoLib draw of every vehicle with a GPU-instanced
Flywheel path, so large fleets of tanks, trucks, helicopters and other vehicles cost far less on
the CPU and render thread.  Provides 5-30x performance boost.

It ships for two versions from one source tree:

- Minecraft 1.20.1 (Forge)
- Minecraft 1.21.1 (NeoForge)

## What it does

For each SBW vehicle, Komodo bakes the GeckoLib model once and then draws it through Flywheel's
instancer instead of re-walking and re-emitting every bone every frame. 

- Static body bones are merged into a single mesh and drawn as one instanced body.
- Moving bones (wheels, tracks, turret, barrel, rotor, etc.) become separate dynamic instances
  that follow the animation.
- Identical bone meshes (for example the many track links) share one mesh and one instancer.
- On lower detail (LOD) models the track segments are baked into the static body, since track
  scroll is not visible at range.

On top of the instanced path it adds three optimizations:

- Dormancy: a parked vehicle that is empty, still, and not animating is frozen and stops updating
  until any tracked state changes (driver, movement, rotation, turret or gun aim, recoil, AI
  target, or fire). This is interrupt driven, so a frozen vehicle costs almost nothing.
- Garage pool: dormant, stationary vehicles are baked once into a shared GPU buffer and drawn
  with a single call, with periodic relighting and background compaction. (Currently buggy)
- Retained fallback: when a shader pack is active (Iris or Oculus), the raw instanced draw is
  skipped and a retained vertex-buffer path is used instead so the vehicle still renders correctly. However, I do not
  recommend it.

It also includes a small gameplay fix, the cache mixin (VehicleGunDataCacheMixin), which caches
SBW's gun-data map instead of rebuilding it many times per tick per vehicle. Decreases GC stutter.

## What it configures

Komodo writes one config file, `config/komodo.toml`, under a `[dormancy]` section.

- `probeVehicles` (list of strings, default empty): entity ids of vehicles that keep animating on
  their own while parked, empty, and untouched. These are the only vehicles the interrupt driven
  wake path cannot observe, so they get a cheap periodic re-check instead of staying frozen.
  Example: `["superbwarfare:some_idle_animated_vehicle"]`
- `probeAllVehicles` (boolean, default false): when true, every vehicle is periodically re-checked
  regardless of the list above. Use this only for diagnosis if a parked vehicle looks stuck mid
  animation and you are not sure which id to add.

Most setups need neither option, because standard vehicles freeze cleanly and wake on any real
state change.

There are also runtime toggles and a benchmark harness for tuning and profiling:

- `/kmodoc flywheel on|off`, `/kmodoc dormancy on|off`, `/kmodoc garage on|off`
- `/kmodoc profile on|off`, `/kmodoc report`, `/kmodoc run <label> <seconds>`, `/kmodoc ab <seconds>`
- `/kmodo garage <count> [type|mix] [spacing]` and `/kmodo clear` to spawn and remove a test fleet

## Origin

The renderer is a straight port of the "Kmodo" (typo) system from the Warfactory Modern Core mod, lifted
out into its own standalone mod.


