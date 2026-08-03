# Large Moving Entities (Carrier-Scale) in Superb Warfare — Design Notes

> Design exploration: how to make 3-chunk-long / 2-chunk-wide vehicles ("aircraft carrier"
> scale) work without straining a server, and how a Jolt physics engine (via Velthoric or
> jolt-jni directly) would fit into the Superb Warfare / Komodo / AshVehicle stack.
>
> Drafted 2026-07-21. Research synthesized from three parallel investigations: (1) the current
> Komodo/AshVehicle/Warfctory codebase, (2) the Velthoric library + Jolt, (3) prior art
> (Valkyrien Skies 2, Create, Movecraft, Archimedes/DaVinci, DynamX).

---

## TL;DR

- **Size is not the problem; representation is.** Everything painful about large MC entities
  comes from the vanilla pipeline seeing the object as one giant axis-aligned box.
- **SBW is already the right paradigm** ("rigid body + rendered model, no real blocks"), the
  pattern proven to win at carrier scale. We are *not* building player-block ships, so we do
  **not** need Valkyrien Skies' shipyard machinery.
- **Make the carrier a KINEMATIC Jolt body driven by existing movement code** — not a
  fully-dynamic rigid body, not a VS2 sub-world. Jolt provides collision surface + contacts +
  buoyancy only.
- **Reuse the crown jewel**: `EntityCollisionMixin` OBB platform-walking already solves
  "stand/move on a moving rotating deck." Back it with Jolt contacts; don't rip it out.
- **Velthoric = best reference to study, not yet a stable dependency.** Early PoC, API churn,
  open bugs in exactly the subsystems we'd lean on. Prefer a thin custom layer on `jolt-jni`,
  borrowing Velthoric's voxel→collision mesher (LGPL).
- **Structural limit to design around:** Jolt parallelizes across *independent* bodies, but a
  single constrained assembly = one simulation island = **one core**. Few capital ships is fine;
  fully-dynamic fleets are not. Kinematic bodies sidestep this.

---

## 1. The core reframe

"How do I make a 3×2-chunk entity work without straining a server?" has a counterintuitive
answer: **the size of the object is not the problem. The representation is.** All the pain of
large vanilla entities comes from forcing the vanilla pipeline to treat the object as *one giant
axis-aligned bounding box (AABB)*:

- **AABBs can't rotate.** The collision box maintains a static orientation (per Minecraft Wiki).
  A 100-block hull at 45° must expand to its axis-aligned enclosure — a ~141×141 ghost footprint
  (`new_half = |cosθ|·half_x + |sinθ|·half_z`), ~5× the real area, all of it costing full
  computation. Oriented boxes (OBBs) need SAT over 15 axes (15–30× more expensive/pair) and
  vanilla has no such pipeline.
- **Collision is O(swept volume)** with no broadphase index. Mojang bug MC-260743: a fast TNT
  iterates ~100M block positions in one tick when only ~8,400 can collide — "no mechanisms to
  discard redundant collision checks."
- **Entity tracking floods packets.** `EntityTracker` sends move packets every `updateFrequency`
  to every watcher. Our vehicles use `setTrackingRange(1028)` + `setUpdateInterval(1)`, so a
  carrier visible to 20 players ≈ 133+ position packets/sec from one entity.
- **Chunk churn.** A fast entity crosses a chunk boundary many times/sec, forcing entity-ticking
  status repeatedly and leaving a ~300-tick tail of loaded chunks.
- **Multi-part hitboxes (Ender Dragon) don't scale.** The dragon is 9 entities; parts are full
  entities with their own IDs/spawn/tracking, they **don't rotate**, and aren't saved. A
  1,000-feature ship as parts ≈ 133k packets/sec just from parts.

**The one insight that makes carrier-scale tractable** (stolen from Valkyrien Skies): *decouple
the physics/collision representation from anything the vanilla entity-AABB pipeline can see, and
never let that pipeline treat the object as one giant AABB.* Once you do, a carrier's **server**
cost becomes roughly constant regardless of visual size.

---

## 2. Where our stack already sits (the good news)

Superb Warfare is **already** the "rigid body + rendered model, no real blocks" paradigm — the
exact pattern (column B in §4) that prior art proves wins decisively at carrier scale. We are not
building player-block ships. Our vehicles are GeckoLib model entities with:

- **Tiny real AABB** (e.g. aircraft are `2.0 × 2.0` regardless of visual size) — vanilla never
  sees a giant box. ✅
- **Parallel OBB list** — F-22 defines **44 oriented boxes** (wings/fuselage/fins) via JSON,
  world-updated each tick by `updateOBB()`. This is effectively a hand-rolled compound collision
  shape, currently used for bullet/melee hit detection only. ✅
- **`EntityCollisionMixin` — OBB platform-walking.** Already lets players and vehicles stand and
  walk on a moving, rotating deck, with yaw-delta compensation and vertical push resolution.
  **This is the crown jewel** — the single hardest part of "walk around on a moving carrier," and
  a version of it already works. ✅
- **Mavic `DroneChunkStreamer`** — a working pattern for force-ticking a multi-chunk footprint
  (ticket + explicit `ClientboundLevelChunkWithLightPacket` streaming). ✅

### Key integration touch-points already in the code

| Concept | What exists | Location |
|---|---|---|
| Physics-step / position-commit hook | `VehicleEntity.move(MoverType, Vec3)` (SBW calls it; Warfctory mixins HEAD) | `Warfctory-Modern-Core/.../VehicleFoliageBreakerMixin.java:39` |
| Velocity integration | `RemoteDroneEntity.travel()` sets `deltaMovement` directly | `AshVehicle/.../base/RemoteDroneEntity.java:245` |
| Engine dispatch | `EngineInfo.work(vehicle)` / `VehicleEngineUtils.helicopterEngine(...)` | `AshVehicle/.../data/AshEngineInfo.java:22` |
| OBB collision volumes | `vehicle.getOBBs()` / `List<OBB>`, updated each tick | `AshVehicle/.../mixin/VehicleEntityMixin.java:19` |
| OBB rotation extension | `MixinOBBInfo` adds `RotationAngles` (Euler) | `AshVehicle/.../mixin/MixinOBBInfo.java:10` |
| Entity-on-vehicle (deck walk) | OBB platform detection on `Entity.collide()` | `AshVehicle/.../mixin/EntityCollisionMixin.java:46` |
| Terrain probing (tanks) | `TerrainCompat` JSON local probe points | vehicle JSON (e.g. `m1a1abrams.json`) |
| Passenger positioning | `positionRider(Entity, MoveFunction)` | `AshVehicle/.../base/RemoteDroneEntity.java:377` |
| Synced state | `SynchedEntityData` (power, roll, gear, laser, …) | SBW `VehicleEntity` |
| Render pose | `VehicleRenderer.vehicleAxis(entity, pose, yaw, partialTick)` | Komodo `KmodoFlywheelVehicleVisual.java:419` |
| Client interpolation | `Mth.rotLerp(partialTick, yRotO, getYRot())`; garage dormancy | Komodo `KmodoFlywheelVehicleVisual` |
| Multi-chunk keepalive | Mavic ticket + chunk streaming | `Mavic/.../DroneChunkStreamer.java` |

> Note: Komodo is a **client-only** Flywheel-instancing renderer for any `VehicleRenderer`
> entity; it owns no entity classes. Entities live in **AshVehicle** (open) on top of SBW's
> closed `VehicleEntity` → `GeoVehicleEntity`. Server overrides live in **Warfctory-Modern-Core**.

### What's missing for a physics engine

1. No rigid-body abstraction (`IPhysicsVehicle` / body handle) on `GeoVehicleEntity`.
2. No voxel→collision-geometry pipeline (SBW uses vanilla AABB sweep via `Entity.move()`).
3. OBBs are parallel to, not coupled with, any integration step.
4. No interpolation buffer if physics runs at a different rate than the game tick.
5. No multi-chunk physics keepalive for large *stationary* vehicles (only Mavic's drone path).

---

## 3. What Jolt / Velthoric actually buys us

Not "physics for its own sake" — our movement is already hand-integrated and works. Jolt earns
its place in exactly four places:

1. **Oriented collision queries** — replace hand-rolled `TerrainCompat` contouring and the
   per-tick 20-block OBB scan with a real compound shape + contact callbacks.
2. **Buoyancy** — a carrier floating/listing on water is a Jolt strength.
3. **Terrain collision** — running aground / ramming. *The* hard part, and the one thing
   Velthoric genuinely solves: its native, greedy-meshed `TerrainVoxelShape`
   (internal-edge-removal, handles non-solid blocks) is the voxel→collision-mesh pipeline we'd
   otherwise spend months building.
4. **Optional emergent physics** for small vehicles (crash dynamics on planes/tanks/the drone).

### The structural limit to design around

Jolt parallelizes across **independent** bodies (a job system + island builder, ~4.9× at 8
threads), but **a single rigidly-constrained assembly is one island and runs on one core.** A
thousand crates scale beautifully; ten fully-dynamic capital ships do not. This is *the* reason
to keep capital ships **kinematic** (see §5). Jolt memory is pre-allocated at `Init()` and **not
resizable** — size the body pool up front. Double-precision build needed for large-world coords;
broad phase stays float so resolution degrades past ~10 km from origin (origin rebasing).

---

## 4. Prior-art pattern synthesis

| | (A) Sub-world + transform (VS2) | **(B) Rigid body + model (us / DynamX)** | (C) Block-teleport (Movecraft) |
|---|---|---|---|
| World collision | Continuous rigid body (Krunch); terrain baked to mesh on bg threads | Rigid body vs static-world shape | Discrete pre-move grid check |
| Player riding | Physics-based; movement patched to ship-space | Passenger entity + custom input | Teleport by delta each tick (fragile) |
| Networking | 1 transform/ship/tick; lags ~50 ships | **Lowest: 1 entity packet regardless of size** | O(block count) packets/move |
| Server perf | Physics offloaded to threads; **~3.5× idle MSPT overhead** | **Single entity tick; constant vs size** | Main thread; ~10k moving-block limit |
| Block interactivity | Full | **None (designed assets)** | Full (real blocks) |
| Proven at carrier scale? | **Yes** | **Yes (if not player-built)** | **No** |

- **VS2** is the only path if carriers must be *player-built from real blocks*. It's the one
  architecture that solves rotation + physics collision + large scale together — but at real
  cost: ~3.5× idle MSPT overhead (Spark #473), native memory leaks on world reload
  (#487/#525/#891), and it calls `Thread.sleep()` on the **main server thread** when its physics
  queue backs up (#456/#323). Forge/Fabric only (no Paper). **We don't need any of this** because
  our carriers are designed content, not player builds.
- **Create contraptions** — best block-based collision in any mod (continuous OBB + SAT + TOI),
  Flywheel-rendered, real passenger seats. But rotation is bearing/piston-axis only (no free
  pitch/roll) and NBT packet size is the hard ceiling.
- **Movecraft** — no entities; rewrites blocks via NMS each move. Zero idle cost but O(blocks)
  per tick; ~10k combined moving blocks is the community wall. A ~15k-block carrier is already
  over it. Wrong asymptotic for us.
- **Archimedes' / DaVinci's / MovingWorld** — lift-into-entity, **single AABB envelope, players
  can't walk on a moving ship**. Abandoned. Confirms the entity approach needs the OBB
  platform-walk layer we already have.
- **DynamX / Immersive Vehicles** — pure entity, OBJ models + Libbulletjme (Bullet JNI). Single
  entity tick regardless of visual size; the lightest at carrier scale. This is the closest
  analog to where SBW already is.

---

## 5. Recommended architecture — kinematic capital ships

**Do not** make the carrier a fully-dynamic rigid body Jolt drives, and **do not** build a VS2
shipyard. Make it a **KINEMATIC Jolt body driven from existing engine code**; Jolt provides only
the collision surface, contact events, and buoyancy.

Why kinematic:

- Sidesteps the one-island-one-core wall (kinematic bodies don't solve constraint islands).
- Doesn't fight scripted flight/drive models or SBW's `EngineInfo.work()` / `travel()`.
- Smooth, predictable motion → cheap networking (§6).
- Matches what Eureka actually does: it applies *stabilizing torque* so ships don't realistically
  tip. Nobody wants an aircraft carrier that emergently capsizes mid-battle.

### The per-tick loop (server, off main thread where possible)

1. Run existing movement/engine code to compute the scripted pose.
2. Push pose into the kinematic Jolt body (`setPositionRotationAndVelocity`) **before** the step.
3. Step Jolt (off the tick thread; free the temp allocator each `Update()`).
4. Read contacts **after** the step; reconcile at the `VehicleEntity.move()` interception point
   (the `VehicleFoliageBreakerMixin` HEAD pattern shows how to intercept safely) instead of the
   vanilla AABB sweep.
5. Drive `updateOBB()` from the Jolt pose (OBBs become a *consumer* of the authoritative pose,
   not a parallel Euler system) — keep OBBs for hit detection.

### Wiring to existing systems

- **Deck-walking**: keep `EntityCollisionMixin`, but back it with Jolt's **contact listener**
  instead of `level.getEntities(20-block AABB)` + N×OBB scan every tick. That scan (44 OBBs × 20
  players/tick) is a real cost center; contacts make it event-driven.
- **Passengers**: `positionRider()` transforms seat offsets through the Jolt body pose.
- **Rendering**: Komodo already lerps from `yRotO/xRotO`. Feed Jolt's previous-tick pose into
  those (or add interpolation fields) — the Flywheel path barely changes. **Never run Jolt
  client-side**; the client stays a pure interpolator (this is where Velthoric's #66
  interpolation/clock-sync bugs live — avoid inheriting them).

---

## 6. Killing server strain, specifically

1. **Networking**: a slow, smoothly-moving carrier does *not* need `updateInterval(1)`. Drop its
   transform sync to ~5–10 Hz and interpolate client-side (VS2 runs ships at 20 Hz and wants
   *less*). Biggest cheap win given the 1028-block tracking range.
2. **Chunk keepalive**: generalize Mavic's `DroneChunkStreamer` to force-tick the ~6-chunk
   footprint of any large kinematic vehicle. Bounded and cheap.
3. **Off-thread stepping**: run the Jolt step off the main tick thread; pre-size the body pool.
4. **Contacts over scans**: replace the per-tick OBB platform scan with event-driven contacts.

---

## 7. Velthoric verdict (as a dependency)

**Best reference implementation to study, not yet a stable dependency to ship on.**

- ~9-month-old single-dev project, self-described "Proof of Concept."
- Real architecture: Jolt via `stephengold/jolt-jni`, server-authoritative, off-thread stepping,
  per-dimension physics worlds, double-precision, native greedy-meshed terrain, built-in vehicle
  controllers, Zstd networking. Forge/Fabric/NeoForge/Quilt via Architectury; MC 1.20.1 + 1.21.1.
- **Against**: no published benchmarks; breaking API churn nearly every minor release; **open
  bugs in exactly the subsystems we'd lean on** — terrain-load sequencing (#65: bodies fall
  through not-yet-loaded chunks — deadly for a carrier crossing fresh chunks) and client
  interpolation/clock-sync (#66); zero third-party adopters; thin docs; native-only
  (no 32-bit/Android — propagates to us).

### The choice

- **Depend on Velthoric** — fastest to prototype; inherit its churn + bugs; pin an exact version.
- **Thin layer on `stephengold/jolt-jni` directly** (the mature binding Velthoric itself uses) —
  more upfront work, but tailored and owned; skip the ~90% of Velthoric's surface we'll never use
  (soft bodies, ragdolls, physics guns). **Borrow the one genuinely hard piece — the
  voxel→`TerrainVoxelShape` greedy mesher — from Velthoric's source (LGPL-3.0).**

Recommendation: **lean toward the thin custom layer** for a mixin-heavy port with narrow needs
(a few kinematic capital ships + optional dynamic small vehicles), but **prototype against
Velthoric first** to validate the approach cheaply before committing.

---

## 8. Suggested phasing

- **Phase 0** — Keep OBB + `EntityCollisionMixin` untouched. It's the asset, not the liability.
- **Phase 1** — `jolt-jni` in; one **kinematic** body per capital ship driven by existing
  movement. Jolt used purely for terrain contact + buoyancy; retire `TerrainCompat` probing.
  Validate TPS with a couple of carriers.
- **Phase 2** — Route deck-walking through Jolt contacts; drop carrier sync rate + interpolate.
- **Phase 3** (optional) — Small vehicles become dynamic bodies for crash physics (these *do*
  parallelize across cores).
- **Phase 4** (optional) — Fully-dynamic capital ships, only if gameplay wants it, knowing each
  eats a core.

**One-liner:** the carrier should be a **kinematic Jolt body wearing our existing
OBB/GeckoLib clothing** — not a VS2 shipyard, and not a fully-dynamic rigid body. Server cost
stays near-constant in size, we reuse the platform-walking already nailed, and Jolt is confined
to the two things it's uniquely good at (oriented terrain collision + buoyancy).

---

## Appendix A — Key references

- **Velthoric**: repo `github.com/velthoric/Velthoric` (mirror `xI-Mx-Ix/Velthoric`),
  `modrinth.com/mod/velthoric`, docs `velthoric.github.io/velthoric-docs`. Issues #65 (terrain
  load timing), #66 (interpolation/clock), #60 (rigid-body/ground collision).
- **jolt-jni**: `github.com/stephengold/jolt-jni`, `stephengold.github.io/jolt-jni-docs`
  (Java 11+ runtime, JDK 21 to build; Sp/Dp native classifiers; no 32-bit/Android).
- **Jolt**: `github.com/jrouwe/JoltPhysics`, `Docs/Architecture.md`,
  multicore scaling PDF, voxel-world discussion #446 (one static body per chunk-slice via
  `StaticCompoundShape`).
- **Valkyrien Skies 2**: `github.com/ValkyrienSkies/Valkyrien-Skies-2`,
  `wiki.valkyrienskies.org`. Issues #473 (Spark idle overhead), #456/#323 (main-thread sleep),
  #487/#525/#891 (native leaks), #522/#610 (directional-block disassembly), #29 (sync/UDP).
  VS2 2.4.10 added optional Jolt backend; default engine is Krunch.
- **Others**: Create (`Creators-of-Create/Create`, #1893 NBT ceiling), Movecraft
  (`APDevTeam/Movecraft`, #86 rider teleport), DynamX (Libbulletjme), MC-260743 (collision
  broadphase bug), Minecraft Wiki Hitbox / Ender Dragon pages.

## Appendix B — Current-code file index (for when I come back)

- Entities: `AshVehicle/src/main/java/Aru/Aru/ashvehicle/entity/vehicle/` (base: `BaseAircraftEntity`,
  `RemoteDroneEntity`; leaves: `ZumwaltEntity`, `F22Entity`, tanks, …); registry `init/ModEntities.java`
  (`setTrackingRange(1028)`, `setUpdateInterval(1)`).
- OBB / collision mixins: `AshVehicle/.../mixin/VehicleEntityMixin.java`, `MixinOBBInfo.java`,
  `EntityCollisionMixin.java`.
- Engine data: `AshVehicle/.../data/AshEngineInfo.java`; vehicle JSON with `EngineType`/`EngineInfo`/
  `OBB`/`TerrainCompat`/`Mass`/seat definitions.
- Server overrides: `Warfctory-Modern-Core/.../mixin/VehicleFoliageBreakerMixin.java`,
  `SyncedEntityFuelStorage.java`.
- Rendering (Komodo, client-only): `Komodo/versions/1.21.1/.../kmodo/KmodoFlywheelVehicleVisual.java`,
  `KmodoFlywheelRegistrar.java`, dormancy/garage pool.
- Chunk streaming: `Mavic/versions/1.21.1/.../DroneChunkStreamer.java`.
