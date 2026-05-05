# Controlify — DualSense Adaptive Triggers & HD Haptics

This is the live plan + status doc for Controlify's DualSense feedback systems. The original design doc that kicked off this work has been distilled into the four sections below.

---

## 1. Foundation / Core

### Terminology

The DualSense has **three** independent feedback hardware systems. Always use the precise term.

| Term | Hardware | Transport | What it produces |
|---|---|---|---|
| **Adaptive Triggers** | Two motors, one inside L2 and one inside R2 | DS5 effects-state HID report (`rgucRightTriggerEffect` / `rgucLeftTriggerEffect`) | Dynamic resistance and trigger-local vibration. Felt only by the finger on the trigger. Effect types: `Off`, `Feedback`, `Weapon`, `Vibration`, `FeedbackMultiplePosition`, `FeedbackSlope`, `VibrationMultiplePosition`, `Bow`, `Galloping`, `Machine`. |
| **HD Haptics** | Two voice-coil actuators, one in each grip | USB-audio-class endpoint with custom 4-channel layout (channels 0/1 = controller speaker, channels 2/3 = grip actuators) | High-resolution wide-band tactile feedback. Renders arbitrary PCM (passed-through .ogg sounds, synthesized waveforms). Felt across the whole grip. Bandwidth-starved over Bluetooth (no public protocol — see Agent Notes). |
| **Rumble** | Same voice-coil actuators as HD Haptics, addressed via `SDL_RumbleController`'s coarse-amplitude API | DS5 effects-state `ucRumbleLeft` / `ucRumbleRight` fields | Backwards-compatible coarse vibration for events that don't warrant full PCM. Existing Controlify rumble system. Independent toggle from HD Haptics in settings. |

### Package layout

```
controller/
├── FeedbackCategory.java                    — neutral enum shared by both feedback systems
├── dualsense/                               — Adaptive Triggers
│   ├── AdaptiveTriggerDispatcher.java       — push-based priority-layered effects
│   ├── AdaptiveTriggerHelper.java           — gating helper + scaleStrength + binding-side lookup
│   ├── AdaptiveTriggerTickHandler.java      — passive baselines (held-item, environment)
│   ├── ControlifyTriggerReasons.java        — registered TriggerReason constants + categoryOf
│   ├── DualSenseComponent.java              — the ECS component for DS5 features
│   ├── DualSenseFeatureSupport.java         — capability flags
│   ├── TriggerHandle.java                   — heartbeat / update / release
│   ├── TriggerReason.java                   — record (Identifier, description, scope)
│   └── TriggerSide.java                     — LEFT / RIGHT
├── haptic/                                  — HD Haptics
│   ├── HDHapticComponent.java
│   ├── HDHapticDispatcher.java              — sound dispatch + loops + one-shots
│   ├── HDHapticTickHandler.java             — per-tick gameplay haptics (ambient loops, heartbeat, etc.)
│   ├── HapticLoop.java                      — looped chunk with crossfade and consume tracking
│   ├── HapticProfile.java                   — sound→haptic recipe
│   ├── HapticSoundDefaults.java             — registers default sound mappings on init
│   ├── HapticSoundRegistry.java             — exact + suffix-rule + predicate lookups
│   └── HapticWaveform.java                  — synthesized PCM (sine/saw/pink/brown/AM/ASR/concat with cache)
└── rumble/                                  — existing Controlify rumble system
driver/sdl/dualsense/
├── DS5EffectsState.java                     — JNA struct for DS5 effects-state HID report
├── DualsenseTriggerEffect.java              — sealed interface + records for each effect type
└── DualsenseTriggerEffectTypes.java         — type bytes
mixins/feature/
├── dualsense/                               — Adaptive Trigger gameplay hooks
│   ├── elytra/FireworkRocketEntityMixin.java
│   ├── shield/SoundEngineShieldBlockMixin.java
│   ├── swing/LocalPlayerMixin.java          — every-swing 3-stage swoop + crit overlay
│   ├── swing/SoundEngineSwordImpactMixin.java — sound-driven hit/crit/sweep/no-hit differentiation
│   └── useitem/                             — CrossbowItemMixin, MultiPlayerGameModeMixin
├── hdhaptics/                               — HD Haptics gameplay hooks
│   ├── damage/LivingEntityMixin.java        — source-aware damage overlays
│   ├── landing/EntityMixin.java             — fall thump
│   └── SoundEngineSoundInterceptMixin.java  — registry-driven sound passthrough
└── rumble/                                  — existing rumble hooks; some extended with trigger calls
```

### Key abstractions

- **`AdaptiveTriggerDispatcher`** — push-based priority-layered effects per trigger side. Layers expire after `HEARTBEAT_TIMEOUT_TICKS` (2) ticks of inactivity, or after their explicit `durationTicks`. Reserved priority bands:
  - **0–99 PASSIVE** — held-item baselines, environmental modifiers
  - **100–199 ACTION** — action-in-progress (bow draw, mining, eating)
  - **200–299 COMBAT_BURST** — combat-imminent (critical hit, sword impact, shield block-hit)
  - **1000+ DEBUG** — settings-screen test buttons
- **Scope filter**: each `TriggerReason` has a scope (`IN_WORLD` / `GUI_ONLY` / `ALWAYS`). The dispatcher resolves the highest-priority *visible* layer per side based on `Minecraft.getInstance().screen` state. IN_WORLD layers are silent while a screen is open.
- **Binding-aware sides**: every gameplay effect routes through `AdaptiveTriggerHelper.triggerSideForBinding(USE / ATTACK)`. Returns `LEFT`/`RIGHT`/`null`. If null (binding mapped to a button), the effect is skipped.
- **Held-item baselines**: applied at `PRIORITY_PASSIVE` so any action layer overrides naturally; baseline resumes within 2 ticks of action layer expiry (heartbeat-or-die).
- **`HDHapticDispatcher`** — sound-event dispatch with category gating, requiresLocalSource / spatial attenuation, per-sound cooldown (3 ticks). Plus `playOneShot(HapticWaveform)` and `startLoop`/`stopLoop` for synthesized content.
- **`HapticSoundRegistry`** — three lookup tiers: exact `Identifier`, then `registerSuffix(".step", profile)` for path suffixes, then `registerRule(predicate, profile)` for arbitrary matchers. Pattern rules let one entry cover all per-material variants of a sound.
- **`HapticLoop`** — tracks chunk-tick duration so the dispatcher only re-queues a chunk when the previous one has been consumed downstream (prevents seconds of residual audio after the loop is stopped).
- **`HapticWaveform`** — synthesized PCM at 48 kHz signed-mono LE; LRU cache for deterministic generators (sine / square / saw); fresh generation for noise generators (pink / brown).
- **Sound-intercept mixin** — `SoundEngineSoundInterceptMixin` (HEAD inject on `play(SoundInstance)PlayResult`) routes every sound to the registry, providing zero-friction passthrough for any registered mapping. A second mixin, `SoundEngineSwordImpactMixin`, listens for `PLAYER_ATTACK_*` sounds at the local player and fires distinct trigger overlays for each impact type.
- **`AdaptiveTriggerTickHandler` / `HDHapticTickHandler`** — per-tick handlers ticked from `Controlify.tick()` alongside the dispatchers. Together with the dispatcher's heartbeat-or-die layers, this gives passive effects (held-item baselines, ambient drones) without needing per-event mixins.

### Settings model

- `DualSenseSettings` — `adaptiveTriggersEnabled`, `triggerStrengthMultiplier`, `triggerCategoryToggles : Map<FeedbackCategory, Boolean>`, `playerIndicatorsEnabled`
- `HDHapticSettings` — `enabled`, `masterIntensity`, `synthesisIntensity`, `categoryToggles`, `lowPassFilter`
- All fields use `Codec.optionalFieldOf(default)` — old `controlify.json` files load cleanly without a DataFixer bump
- Settings UI: three sibling YACL groups (Vibration / HD Haptics / Adaptive Triggers); per-group master toggle, intensity sliders, per-category toggles, Test buttons

---

## 2. Adaptive Trigger Features

Implemented and remaining features. Implemented = ✅; remaining = ⬜.

### Combat & weapons

- ✅ **Bow held baseline** — narrow `Bow(7,8,2,8)` at trigger end (matches start-of-draw, no lag transition)
- ✅ **Bow draw** — progressive `Bow` widening 7→2 zone with strength 2→8 over the draw
- ✅ **Bow release snap** — `Vibration(2, 7, 40 Hz)` 100 ms one-shot
- ✅ **Crossbow held (uncharged) baseline** — `Feedback(4, 2)` light hold
- ✅ **Crossbow charging** — `FeedbackSlope(2, 8, 2, 7)` while loading
- ✅ **Crossbow loaded click** — `Weapon(7, 8, 8)` 150 ms when charge completes
- ✅ **Crossbow charged-and-ready baseline** — `Feedback(4, 8)` hard wall before fire
- ✅ **Crossbow fire** — `Vibration(2, 8, 60 Hz)` 200 ms burst on `CrossbowItem.use` while charged
- ✅ **Sword swing (every swing)** — 3-stage swoop via thread+execute: heavy thump 35 Hz → soft 50 Hz → impact 22 Hz
- ✅ **Sword hit differentiation** — sound-driven (`SoundEngineSwordImpactMixin`):
  - `PLAYER_ATTACK_STRONG` → `Vibration(0, 8, 25 Hz)` 200 ms heavy thump
  - `PLAYER_ATTACK_CRIT` → `Vibration(0, 8, 70 Hz)` 200 ms bright spike
  - `PLAYER_ATTACK_KNOCKBACK` → `Vibration(0, 8, 18 Hz)` 250 ms deepest
  - `PLAYER_ATTACK_SWEEP` → `Vibration(0, 6, 50 Hz)` 150 ms wider buzz
  - `PLAYER_ATTACK_WEAK` → `Vibration(0, 4, 35 Hz)` 100 ms light flick
  - `PLAYER_ATTACK_NODAMAGE` → `Vibration(0, 2, 40 Hz)` 50 ms faint pat
- ✅ **Sword crit overlay** — predicted client-side (airborne + falling, not in liquid/climbing/passenger), at `PRIORITY_COMBAT_BURST`
- ✅ **Trident held baseline** — `Feedback(3, 4)` weight
- ✅ **Trident throw charge** — progressive `Bow` like the bow effect, scaling 0→1 over `THROW_THRESHOLD_TIME` (10 ticks)
- ✅ **Trident throw release snap** — `Weapon(2, 8, 8)` 100 ms
- ✅ **Mace airborne (slam wind-up)** — `Feedback(2, 2..8)` resistance grows with `fallDistance / 8`
- ✅ **Weapon held baseline** — `Feedback(3, 4)` for any item with the `DataComponents.WEAPON` data component (swords, axes, maces)
- ✅ **Shield held** — `Feedback(6, 8)` constant wall while raised (filtered to `ShieldItem` from BLOCK animation)
- ✅ **Shield block-hit pulse** — `Vibration(6, 8, 100 Hz)` 100 ms on `item.shield.block` sound when player is blocking
- ✅ **Goat horn / instruments trigger feel** — `Vibration(2, 5, 18 Hz)` while the toot animation runs, on the use trigger

### Mining & tools

- ✅ **Block break — discrete cracks** — per-stage Vibration pulses: hard at start (amp 5–8), decays over 2 ticks (amp − 3 each tick). Frequency 30→48 Hz over the 10 stages.
- ✅ **Effective-tool gate** — no trigger effect when the block requires a correct tool the player doesn't have
- ✅ **Block-break transition release** — clean release on block destroyed (force-release via `cir.getReturnValue()`)
- ✅ **Block instabreak** — `Vibration(0, 8, 35 Hz)` 150 ms one-shot for creative / wood / dirt
- ✅ **Tool facing block (hardness baseline)** — items with `DataComponents.TOOL` (and not WEAPON) facing a block they can effectively drop → `Feedback(2, 2..8)` scaled by hardness
- ✅ **Block place** — `Weapon(3, 4, 3)` 50 ms one-shot
- ✅ **Bucket fill / empty** — `Feedback(4, 5)` 200 ms hold; lava bucket overlays `Vibration(4, 4, 25 Hz)` at COMBAT_BURST
- ✅ **Hoe tilling** — `Vibration(3, 5, 18 Hz)` 250 ms
- ✅ **Spawn egg use** — `Weapon(3, 4, 2)` 50 ms one-shot
- ✅ **Spyglass zoom hold** — `Feedback(5, 5)` while held
- ✅ **Tool-about-to-break warning** — `Vibration(4, 3, 6 Hz)` overlay on swing or useItemOn when tool damage > 90% of max
- ✅ **"Almost done" overlay (mining tool signature)** — at destroy stages 8/9, layer a sharp `Vibration(4, 6, 80 Hz)` overlay at COMBAT_BURST so the kill-stage feels distinct from the per-stage cracks
- ✅ **Per-tool-type frequency bias for mining** — pickaxe 60..78 Hz (metallic), axe 24..42 Hz (chunky chop), shovel 40..58 Hz (mid scrape), hoe 18..36 Hz (soft till), shears 75..93 Hz (snip), mace 22..40 Hz (boom). Pickaxe detected via `stack.isCorrectToolForDrops(STONE)` since `PickaxeItem` no longer exists.
- ✅ **Shears clip on entities** — `Weapon(4, 6, 7)` 100 ms one-shot on the use trigger when a shears interaction with an entity consumes (sheep / mooshroom / pumpkin entity, etc.). New mixin: `feature.dualsense.shears.MultiPlayerGameModeMixin`.

### Items & use

- ✅ **Eat / drink** — `FeedbackSlope(0, 9, 2, 6)` baseline + chew-cadence `Vibration(4, 3, 8 Hz)` overlay every 4 ticks (matching MC's chew sound interval)
- ✅ **Drink-potion fizz tail** — `Vibration(0, 4, 80 Hz)` 200 ms one-shot fired at the end of any DRINK animation (food's EAT path is unchanged)

### Mobility

- ✅ **Elytra firework boost** — `Vibration(0, 8, 60 Hz)` (no resistance — doesn't fight rapid rocket usage)
- ✅ **Sprint with low hunger** — `Vibration(2, 2, 10 Hz)` fatigue buzz on both triggers (food ≤ 6)
- ✅ **Horse jump charge** — `Bow` widening shape (zone 7→2, strength 2→8) on the use trigger while `getJumpRidingScale()` > 0 and on a `PlayerRideableJumping` mount. Released naturally on jump (scale → 0).
- ✅ **Boat / minecart ambient** — `Vibration(0, 2..6, 14/22 Hz)` per-tick on both triggers while riding (frequency picked by vehicle type, amp scales with horizontal speed)
- ✅ **Jump on foot** — `Vibration(0, 3, 35 Hz)` 100 ms kick on both triggers when the local player jumps from the ground (skipped while mounted)

### Environmental modifiers (passive, both triggers)

- ✅ **Underwater** — `Feedback(1, 2)` water-resistance feel on both triggers
- ✅ **Slippery surfaces** — `Vibration(0, 2, 30 Hz)` wobble (block friction > 0.7)
- ✅ **Magma block standing** — `Vibration(0, 6, 10 Hz)` heat-buzz
- ✅ **Footstep surface profiles per material** — per-step Vibration on both triggers, frequency picked by `BlockState.getSoundType()`: stone/metal/amethyst/copper/deepslate 65 Hz, wood/wool/bamboo 40 Hz, grass/sand/gravel 26 Hz, snow/mud/honey/slime 20 Hz, ICE tag 50 Hz, default 35 Hz. Sprint amp 4 vs walk amp 2. New mixin: `feature.dualsense.footstep.EntityMixin`.

### Fishing

- ✅ **Fishing cast / bite / reel** — Cast: `Weapon(3, 5, 4)` 100 ms on the use trigger when the bobber spawns. Bite: continuous `Vibration(2, 6, 25 Hz)` on the use trigger while biting. Reel: `Feedback(3, 7)` wall on the attack trigger, released on bite-end / hook removal. Layered into the existing rumble FishingHookMixin so it shares the bite state.

### Test infrastructure

- ✅ **Settings-screen "Test Adaptive Triggers" button** — cycles `Feedback → Weapon → Bow` on the user's USE-bound trigger (falls back to ATTACK then RIGHT)

---

## 3. HD Haptics Features

Implemented and remaining features. Implemented = ✅; remaining = ⬜. Sound-mapping rows = passthrough; everything else is per-tick or event-driven synthesized.

### Movement / surface

- ✅ **Footsteps per surface** — `.step` suffix rule, `localOnly`, intensity 0.4× (suppresses other entities' steps)
- ✅ **Jump landing** — synth 60 Hz half-cycle with ASR; amplitude scaled by `(fallDistance − 0.5) / 17.5` squared, so 1-block hops are silent and 18+ blocks saturate
- ✅ **Underwater ambient** — 35 Hz × 0.3 Hz AM `HapticLoop` while submerged
- ✅ **Elytra wind drone** — per-tick fresh brown-noise chunks (no `HapticLoop`); amplitude over speed band [1.0, 4.5] m/tick × random 0.7–1.05 gust factor
- ✅ **Elytra firework boost** — passthrough firework rocket sounds
- ✅ **Sprint footstep boost** — additional 55 Hz 60 ms ASR thump on every sprint stride, layered under the .step passthrough. New mixin: `feature.hdhaptics.footstep.EntityMixin`.
- ✅ **Swimming strokes** — synth-only overlay on `PLAYER_SWIM` / `GENERIC_SWIM` (35 Hz sine + brown-noise tail with ASR). Local-source-gated so other players' swims don't fire. Pure sub-bass content stays in the actuator's native band — no speaker bleed.
- ✅ **Water splash entry** — synth-only overlay on `PLAYER_SPLASH` (25 Hz thud + brown-noise scatter), `PLAYER_SPLASH_HIGH_SPEED` (22 Hz bigger thud, longer scatter), and `GENERIC_SPLASH`. Local-only.
- ✅ **Walking on ice** — pink-noise scrape (80 ms ASR) every third ice step, on top of the regular surface .step. Layered with the AT 50 Hz ice-step Vibration above.
- ✅ **Boat paddle** — `BOAT_PADDLE_LAND` / `BOAT_PADDLE_WATER` passthrough at spatial 6 blocks; per-tick AM-modulated drone (`HapticLoop`, 35 Hz × 0.8 Hz) while riding any boat
- ✅ **Minecart on rails** — `MINECART_RIDING` / `MINECART_INSIDE` passthrough; per-tick brown-noise rumble (`HapticLoop`, 50 ms chunks) while riding, amp scales with horizontal speed
- ✅ **Horse gallop** — `HORSE_GALLOP` / `HORSE_JUMP` / `HORSE_LAND` / `HORSE_STEP` / `HORSE_STEP_WOOD` passthrough at spatial 4 blocks

### Mining / building

- ✅ **Block break** — `.break` suffix rule, all materials, spatial attenuation 6 blocks
- ✅ **Block place** — `.place` suffix rule, intensity 0.5×
- ✅ **Bucket fill** — `item.bucket.fill*` covered via the suffix rules and BREWING/anvil mappings
- ✅ **"Almost done" pulse** — see Adaptive Triggers/Mining (the dual implementation also fires the AT signature)
- ✅ **Wrong-tool synth pulse** — short pink-noise burst (~120 ms with ASR) on the actuator when `startBreakTrigger` runs but the tool isn't effective
- ✅ **Bone meal application** — `BONE_MEAL_USE` passthrough at spatial 4 blocks

### Combat

- ✅ **Sword hit on entity** — `PLAYER_ATTACK_STRONG / WEAK` passthrough, spatial 5 blocks
- ✅ **Critical hit** — `PLAYER_ATTACK_CRIT` passthrough
- ✅ **Sweep attack** — `PLAYER_ATTACK_SWEEP` passthrough
- ✅ **Knockback hit** — `PLAYER_ATTACK_KNOCKBACK` passthrough
- ✅ **No-damage swing** — `PLAYER_ATTACK_NODAMAGE` passthrough
- ✅ **Arrow hit on you** — `ARROW_HIT_PLAYER` localOnly
- ✅ **Heartbeat at low health** — synth double-thump (50 Hz sine × ASR), period 1000 ms @ HP 6 → 400 ms @ HP 0
- ✅ **Damage taken (source-aware overlays)**:
  - `FALL` → 30 Hz sine with long decay (heavy thud)
  - `LAVA / IN_FIRE / ON_FIRE / HOT_FLOOR / CAMPFIRE` → pink-noise crackle burst
  - `DROWN` → 40 Hz × 2 Hz AM (chest pulse)
  - `WITHER / MAGIC` → 50 Hz × 4 Hz AM (curse drone)
  - `LIGHTNING_BOLT` → brown-noise burst with sharp envelope
  - Other → falls through to vanilla `PLAYER_HURT` passthrough
- ✅ **Bow release / crossbow fire / trident throw** — `ARROW_SHOOT` / `CROSSBOW_SHOOT` / `TRIDENT_THROW` passthrough, all `localOnly`

### Items / use

- ✅ **Eat / drink** — `GENERIC_EAT.value()` / `GENERIC_DRINK.value()` localOnly
- ✅ **Goat horn** — all 8 entries via `SoundEvents.GOAT_HORN_SOUND_VARIANTS`
- ✅ **Bell** — `BELL_BLOCK` strike + `BELL_RESONATE` tail
- ✅ **Note block** — prefix rule on `block.note_block.*`
- ✅ **Anvil** — `ANVIL_USE` + `ANVIL_LAND`
- ✅ **Brewing bubble** — `BREWING_STAND_BREW`
- ✅ **Blast furnace crackle** — `BLASTFURNACE_FIRE_CRACKLE`
- ⬜ Drink-potion fizz tail (HD haptic-only synth overlay still pending; adaptive trigger version covers most of the feel)
- ✅ **Enchanting** — `ENCHANTMENT_TABLE_USE` passthrough plus a 4-note rising arpeggio overlay (80→110→150→190 Hz) layered on top, fired every enchant click. Vanilla doesn't ship a separate "success" event, so the during-sound carries it.
- ✅ **Furnace fire crackle** — faint passthrough; vanilla doesn't ship an explicit "furnace done" sound to attach to
- ✅ **Fishing cast / bite / retrieve** — `FISHING_BOBBER_THROW` / `FISHING_BOBBER_SPLASH` / `FISHING_BOBBER_RETRIEVE` passthrough

### Environmental / ambient

- ✅ **Lightning thunder + impact** — `LIGHTNING_BOLT_THUNDER` + `LIGHTNING_BOLT_IMPACT`, range 30 blocks
- ✅ **Rain on player** — `HapticLoop` of pink noise while raining + `canSeeSky`, amp scales with rain level
- ✅ **Conduit pulse** — synth 100 Hz tick every ~2 s while CONDUIT_POWER effect is active
- ✅ **Conduit ambient** — `CONDUIT_ACTIVATE` / `CONDUIT_DEACTIVATE`
- ✅ **Bee buzzing nearby** — 220 Hz saw × 35 Hz AM scaled by inverse-distance to nearest `Bee` within 4 blocks (entity scan refreshed every 4 ticks)
- ✅ **Magma block standing** — 90 Hz × 4 Hz AM `HapticLoop`
- ✅ **Honey block** — 25 Hz constant low `HapticLoop`
- ✅ **Slime block fall** — `SLIME_BLOCK_FALL`
- ✅ **Squid ink puff** — `SQUID_SQUIRT` + `GLOW_SQUID_SQUIRT`
- ✅ **Phantom swoop** — `PHANTOM_SWOOP`
- ✅ **Enderman teleport** — `ENDERMAN_TELEPORT`
- ✅ **End portal frame fill / spawn** — `END_PORTAL_FRAME_FILL` + `END_PORTAL_SPAWN`
- ✅ **High-altitude wind** — brown-noise `HapticLoop` above Y=200, amplitude scales linearly to ~Y=320 (build limit)
- ✅ **Beacon proximity** — per-2s 16-block scan for `Blocks.BEACON`; pulses a 70 Hz sine softly with inverse-distance amplitude
- ✅ **Portal entry/exit sweep** — `PORTAL_TRAVEL` / `PORTAL_TRIGGER` / `PORTAL_AMBIENT` passthroughs, plus a per-tick dimension-change watcher that fires a 1.2 s 45 Hz × 2 Hz AM swept waveform on dimension-key change.
- ✅ **Soul sand step intensity boost** — explicit `SOUL_SAND_STEP` entry at 0.8 intensity overrides the generic `.step` rule

### Triggers / events / boss

- ✅ **Levelup** — `PLAYER_LEVELUP` localOnly
- ✅ **XP orb pickup** — `EXPERIENCE_ORB_PICKUP` localOnly
- ✅ **Advancement unlock** — `UI_TOAST_CHALLENGE_COMPLETE`
- ✅ **Ender dragon roar** — `ENDER_DRAGON_GROWL`, range 64 blocks
- ✅ **Ender dragon death** — `ENDER_DRAGON_DEATH`, range 128 blocks
- ✅ **Wither shoot / death** — `WITHER_SHOOT` + `WITHER_DEATH`
- ✅ **Beacon activate / deactivate** — `BEACON_ACTIVATE` + `BEACON_DEACTIVATE`
- ✅ **Compass / lodestone direction** — continuous per-tick sine; frequency 30 → 140 Hz, amplitude 0.1 → 0.6, both scaling with `pow(alignment, 2)` so opposite-side fades to silence
- ✅ **Levelup synthesized triple-pulse overlay** — registered via the new `HapticSoundRegistry.registerOverlay` API: ascending 80→110→140 Hz pings on top of `PLAYER_LEVELUP`
- ✅ **Advancement arpeggio overlay** — 4-note 90→120→160→200 Hz arpeggio on top of `UI_TOAST_CHALLENGE_COMPLETE`
- ✅ **Dragon roar overlay** — 28 Hz × 2.5 Hz AM throb under `ENDER_DRAGON_GROWL`
- ✅ **Clock midday / midnight click** — per-tick check while holding `Items.CLOCK`; fires bright (200 Hz) at noon, dull (90 Hz) at midnight, exactly once on the boundary crossing

### UI / inventory

- ✅ **Item pickup** — `ITEM_PICKUP`
- ✅ **UI button click** — `UI_BUTTON_CLICK.value()`
- ✅ **Trade complete** — `VILLAGER_YES`
- ✅ **Crafting take-output** — `UI_LOOM_TAKE_RESULT` / `UI_STONECUTTER_TAKE_RESULT` / `UI_CARTOGRAPHY_TABLE_TAKE_RESULT` / `CRAFTER_CRAFT` passthroughs
- ✅ **Item-pickup synth click overlay** — 140 Hz 30 ms ASR sine layered on `ITEM_PICKUP`

### Settings + per-system gates

- ✅ **Master enable** per system, plus master enable separate from rumble
- ✅ **Per-category toggles** — MOVEMENT / COMBAT / AMBIENT / UI / ITEMS / ENVIRONMENT / MISC for both Adaptive Triggers and HD Haptics
- ✅ **Master + synthesis intensity** sliders for HD Haptics; trigger strength multiplier for Adaptive Triggers
- ✅ **Hardware low-pass filter** toggle for HD Haptics (strips audible high-frequency content)
- ✅ **Settings-screen "Test HD Haptics" button** — synthesized double-thump heartbeat

### Other

- ✅ **Note-block instrument-specific tuning** — explicit per-instrument profiles registered for all vanilla instruments; bass/basedrum/didgeridoo at 1.0, snare 0.7, guitar 0.8, banjo/harp 0.6, pling/bit/cow-bell/bell 0.5, flute/chime/xylophone 0.4, hat 0.3. The fallback prefix rule still catches modded variants.

---

## 4. Agent Notes / Build Notes

### Build commands

```bash
./gradlew :26.1-fabric:compileJava :26.1-fabric:processResources    # fabric-only fast iteration
./gradlew :26.1-neoforge:compileJava                                  # verify NF too before commit
./gradlew :26.1-fabric:build                                          # full jar (slow; needs prepareNativeResources etc)
```

The active stonecutter target is `versions/current` which points at `26.1-fabric`. Both fabric and neoforge produce noisy `Note: uses or overrides a deprecated API` lines from the stonecutter-generated copy of `MultiPlayerGameModeMixin` — these are benign.

### JDK + tooling

- **JDK 25 required** to build (modstitch 0.8.4 needs JVM ≥ 25). Gradle itself runs under JDK 25.
- The Minecraft toolchain is JDK 25; Mojang is on Java 25 from MC 26.1.
- Java 21 is **not enough** — the build will fail at `:buildSrc:generateExternalPluginSpecBuilders` with a "Dependency requires at least JVM runtime version 25" error.

### Minecraft 26.1 API gotchas

These bit during implementation; preserved as notes for future code:

- `Level.isClientSide` is now a method, not a public field — call `level.isClientSide()`.
- `SoundInstance.getLocation()` is now `getIdentifier()`.
- `Minecraft.tell()` is gone; use `Minecraft.execute(Runnable)` (the BlockableEventLoop).
- `SoundEngine.play(SoundInstance)` returns `SoundEngine.PlayResult`, not `void` — mixin descriptors must include the return type.
- `SoundEvents.GOAT_HORN_SOUND_*` individual constants are gone; iterate `SoundEvents.GOAT_HORN_SOUND_VARIANTS` (an `ImmutableList<Holder.Reference<SoundEvent>>`) and call `.value()`.
- `SoundEvents.GENERIC_EAT` and `SoundEvents.GENERIC_DRINK` are now `Holder.Reference<SoundEvent>` (not `SoundEvent` directly) — call `.value()`.
- `SwordItem`, `PickaxeItem`, `DiggerItem` classes **no longer exist**; identify weapons/tools by `stack.has(DataComponents.WEAPON)` / `stack.has(DataComponents.TOOL)`. `AxeItem`, `ShovelItem`, `BowItem`, `CrossbowItem`, `TridentItem`, `MaceItem`, `HoeItem`, `ShearsItem`, `SpawnEggItem`, `SpyglassItem`, `BucketItem`, `BlockItem`, `CompassItem` still exist as classes.
- `Entity.fallDistance` is now `double` (was `float`) — cast or use `double` arithmetic.
- World spawn position is at `level.getRespawnData().pos()` now (returns `BlockPos` from a `LevelData.RespawnData` record) — `getSharedSpawnPos()` is gone.
- `DamageTypes.X` are `ResourceKey<DamageType>`; check via `source.is(DamageTypes.FALL)` etc.

### Bluetooth status

HD Haptics is **unavailable on Bluetooth on every Linux setup tested**. The DualSense over BT advertises only the HID and PnP UUIDs — no A2DP or other audio profile. Sony's PS5 uses a proprietary handshake that nothing in BlueZ implements, so the OS never exposes the controller's 4-channel audio sink. SDL3 only sees the PC's regular audio outputs. There is no public open-source path; would require reverse-engineering Sony's BT haptic protocol from scratch.

Adaptive Triggers, Rumble, gyro, and LED **all work fine on BT** since they go through the standard DS5 effects HID report.

### Diagnostic logs in place

Useful for triage when the user reports "feature X isn't working":

| Log line | Source | Fires |
|---|---|---|
| `[AdaptiveTrigger] Settings on connect — adaptiveTriggersEnabled=…` | `SDLControllerManager` | once per controller connect |
| `[AdaptiveTrigger] Gate failed for <CATEGORY>: <reason>` | `AdaptiveTriggerHelper` | once per category-and-reason pair |
| `[AdaptiveTrigger] onStartUsingItem first time seeing anim=X item=Y` | rumble useitem `LocalPlayerMixin` | once per unique `ItemUseAnimation` |
| `[AdaptiveTrigger] SPEAR startTrigger. USE side: …, dispatcher: …` | rumble useitem `LocalPlayerMixin` | every time SPEAR enters startTrigger |
| `DualSense HD Haptics: probing N SDL audio playback device(s).` + per-device line | `SDLCommonDriver` | controller construct |

### Audio plumbing details (channel map + buffer accumulation)

These caused real bugs and are worth understanding:

- The DualSense audio device exposes 4 channels: `[0, 1, 2, 3]` = `[speaker, speaker, haptic_L, haptic_R]`. We use a per-stream output channel map: mono input → `[-1, -1, 0, 0]` (silence speaker, both haptic from input ch 0); stereo → `[-1, -1, 0, 1]`.
- **Channel-map persistence**: SDL3 was observed to drop the channel map after a stream's queue drains. The map is now stored on the `AudioStreamHandle` and re-applied on every `queueAudio` call.
- **`tickLength` precision**: The duration-in-ticks calc must multiply before dividing, with a `Math.ceil` and `Math.max(1, …)` floor — otherwise short audio truncates to 0 ticks, `streamLastPlayed` doesn't decrement, `isInUse()` flips to false right after queueing, and rapid one-shots queue sequentially on the same stream instead of spawning parallel streams (rapid note-block hits → "playing for long after the noteblock is finished").
- **`HapticLoop` chunk-tick tracking**: the dispatcher only re-queues a chunk when the previously-queued chunk has been consumed downstream. Without this, every per-tick re-queue would add another chunk to the SDL queue, producing seconds of residual audio after the loop is stopped.

### Known iteration debts / "if you have time"

- **Tool-break warning** could be more pronounced — currently easy to miss.
- **Held-item baselines** for ranged weapons could differentiate enchanted vs vanilla.
- **Heartbeat at low HP** uses the synthesis-intensity slider but might want a dedicated slider.
- **Settings UI Test buttons** could be smarter about which trigger they fire on (currently fall back to RIGHT if neither USE nor ATTACK is on a trigger).
- **Bee buzz** is per-tick; could be made per-bee for richer multi-source feel.
- **Compass continuous tone** might feel too active — consider a "hold for direction-find" toggle.

### Backwards compatibility

- Every new field in `HDHapticConfig` and `DualSenseConfig` uses `Codec.optionalFieldOf(default)`. Existing `controlify.json` files load cleanly with new fields defaulted; no DataFixer version bump needed.
- The `HDHapticCategory` enum was renamed to `FeedbackCategory` and moved to `controller/` (no longer in `controller/haptic/`). Codec serialization uses the same lowercase names (`movement`, `combat`, etc.) so existing settings files still parse.
