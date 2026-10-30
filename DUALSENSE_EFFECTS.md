# DualSense effects reference — adaptive triggers · HD haptics · speaker

A complete catalogue of every DualSense rich-feedback effect Controlify produces:
the adaptive-trigger effects and where they fire, the HD-haptic waveforms and how
they're synthesised, and the current state of speaker output. This is a *reference*
(what exists, with exact parameters); `plan.md` is the status/roadmap.

> **Transport.** Triggers go through `SDL_SendControllerEffect` and HD haptics render to a
> 4-channel SDL audio device, which SDL only exposes over **USB**. Over Bluetooth the
> HD-haptics and speaker paths are unavailable.

Three independent systems, often layered together for one event:

| System | Hardware | What it is |
|---|---|---|
| **Adaptive triggers** | L2/R2 trigger motors | Programmable resistance/force curves along the trigger travel (zones 0–9). |
| **HD haptics** | Two voice-coil actuators (grips) | Wide-band tactile waveforms (48 kHz PCM): synthesised patterns + passed-through game sounds. |
| **Rumble** | Two ERM motors | Classic low/high-frequency rumble (legacy; can be suppressed in favour of HD haptics). |
| **Speaker** | Built-in mono speaker | Volume/mute plumbing only today — game-audio routing is a TODO (see §4). |

---

## 1. Adaptive triggers

### 1.1 Effect types (mode bytes)
`driver/sdl/dualsense/DualsenseTriggerEffectTypes.java`. Only `OFF/FEEDBACK/WEAPON/VIBRATION`
report a trigger-status nibble back; the rest are firmware leftovers or debug modes.

| Type | Byte | Use |
|---|---|---|
| `OFF` | `0x05` | Reset trigger to neutral |
| `FEEDBACK` | `0x21` | Constant resistance from a position |
| `WEAPON` | `0x25` | Resistance between start/stop with a snap-back ("gun trigger") |
| `VIBRATION` | `0x26` | Vibrate the trigger at a frequency past a position |
| `BOW` | `0x22` | Resistance that builds then snaps (unofficial) |
| `GALLOPING` | `0x23` | Rhythmic two-pulse pattern (unofficial) |
| `MACHINE` | `0x27` | Alternating two-amplitude vibration (unofficial) |
| `SIMPLE_*`, `LIMITED_*` | `0x01–0x12` | "Do not use" firmware leftovers |
| `DEBUG_FC/FD/FE` | `0xFC–0xFE` | Calibration/debug — corrupt state |

### 1.2 Effect records & encoding
`driver/sdl/dualsense/DualsenseTriggerEffect.java`. Each record → `DS5EffectsState.TriggerEffect(mode, byte[10])`.
Records with arrays implement content `equals`/`hashCode` so the dispatcher can dedup.
Zones are 0–9 across trigger travel; strength/amplitude 0 collapses to `Off`.

| Record | Parameters | Notes |
|---|---|---|
| `Off()` | — | Neutral |
| `Feedback(position 0–9, strength 0–8)` | constant force from `position` | 16-bit active-zone map + 3-bit/zone force |
| `Weapon(start 2–7, end start+1–8, strength 0–8)` | gun-trigger | start/stop bitmap + force |
| `Vibration(position 0–9, amplitude 0–8, frequency Hz)` | buzz past position | per-zone amplitude + frequency byte |
| `FeedbackMultiplePosition(byte[10] strengths)` | per-zone force profile | arbitrary resistance curve |
| `FeedbackSlope(start, end, startStrength, endStrength)` | linear ramp | computed → `FeedbackMultiplePosition` |
| `VibrationMultiplePosition(frequency, byte[10] amplitudes)` | per-zone buzz | one frequency, per-zone amplitude |
| `Bow(start 1–8, end start+1–8, strength 0–8, snapForce 0–8)` | tension then snap | `snap\|(strength<<4)` packed |
| `Galloping(start, end, firstFoot 0–15, secondFoot 0–15, frequency)` | hoofbeats | two stomp phases |
| `Machine(start, end, ampA 0–7, ampB 0–7, frequency, period)` | engine | alternating amplitudes |

`Galloping`/`Machine` are defined but not yet used by gameplay.

### 1.3 In-game trigger effects (what fires, and where)
All strengths pass through `AdaptiveTriggerHelper.scaleStrength()` (applies the user
`triggerStrengthMultiplier`, clamps 0–8). "Side" = which trigger (USE/ATTACK ≈ R2/L2,
binding-aware). Priorities: `PASSIVE=50`, `ACTION=100`, `COMBAT_BURST=200`.

**Held-item & environment baselines** (`AdaptiveTriggerTickHandler.java`, PASSIVE unless noted)

| Reason | Effect | Category |
|---|---|---|
| Sticky ground (soul sand/mud/slime/honey/cobweb) | `Feedback(0, 1–4)` ∝ slowdown | AMBIENT |
| In portal block | `Vibration(0, 7, 12 Hz)` | AMBIENT |
| Horse jump charge | `Bow` ramp (ACTION) | MOVEMENT |
| Minecart/boat ride | `Vibration(0, 2–6, 22/14 Hz)` | MOVEMENT |
| Underwater | `Feedback(1, 2)` | AMBIENT |
| Ice (friction > 0.7) | `Vibration(0, 2, 30 Hz)` | AMBIENT |
| Standing on magma | `Vibration(0, 6, 10 Hz)` | AMBIENT |
| Low hunger + sprint | `Vibration(2, 2, 10 Hz)` | AMBIENT |
| Bow held | `Bow(7, 8, 2, 8)` | COMBAT |
| Crossbow charged / charging | `Feedback(4, 8)` / `Feedback(4, 2)` | COMBAT |
| Trident held | `Feedback(3, 4)` | COMBAT |
| Spyglass held | `Feedback(5, 5)` | ITEMS |
| Weapon held | `Feedback(3, 4)` | COMBAT |
| Mace airborne | `Feedback(0, 3–8)` ∝ fall | COMBAT |
| Facing placeable block | `Feedback(4, 3)` | ENVIRONMENT |
| Tool facing block | `Feedback(2, 1–8)` ∝ hardness | ENVIRONMENT |

**Use-item actions** (`mixins/.../rumble/useitem/LocalPlayerMixin.java`)

| Reason | Effect |
|---|---|
| Bow draw | `Bow` tension building with draw power; release → `Vibration(2, 7, 40 Hz)` |
| Crossbow load → loaded | `FeedbackSlope` ramp → `Weapon(7, 8, 8)` snap |
| Shield raise | `Feedback(6, 8)` |
| Eating | `FeedbackSlope(0, 9, 2, 6)` + periodic chew `Vibration(4, 3, 8 Hz)` |
| Trident charge → release | `Bow` ramp → `Weapon(2, 8, 8)` |
| Drink finish | `Vibration(0, 4, 80 Hz)` fizz |
| Goat horn | `Vibration(2, 5, 18 Hz)` |

**Combat & world events** (various mixins)

| Reason | Effect |
|---|---|
| Sword swing | 3-stage swoop: `Vibration` 35 Hz → 50 Hz → 22 Hz |
| Predicted crit | `Vibration(0, 8, 50 Hz)` |
| Sword hit (by damage kind) | strong/crit/knockback/sweep/weak/no-damage `Vibration` variants 18–70 Hz |
| Shield block impact | `Vibration(6, 8, 100 Hz)` |
| Arrow/projectile hit (taken) | `Vibration(0, 7, 50 Hz)` |
| Crossbow fire | `Vibration(2, 8, 60 Hz)` |
| Block place / break | `Weapon(3, 4, 3)` / per-stage `Vibration(0, 5–8, tool-freq)` |
| Mining signature (stage 8–9) | `Vibration(4, 6, 80 Hz)` |
| Bucket use / lava bucket | `Feedback(4, 5)` / `Vibration(4, 4, 25 Hz)` |
| Hoe till | `Vibration(3, 5, 18 Hz)` |
| Spawn egg | `Weapon(3, 4, 2)` |
| Tool near-break warning | `Vibration(4, 3, 6 Hz)` |
| Shears entity | `Weapon(4, 6, 7)` |
| Fishing cast / bite / reel | `Weapon(3, 5, 4)` / `Vibration(2, 6, 25 Hz)` held / `Feedback(3, 7)` held |
| Elytra firework boost | `Vibration(0, 8, 60 Hz)` pulse |
| Ground jump | `Vibration(0, 3, 35 Hz)` |
| Mace slam landing | `Vibration(0, 4–8, 22 Hz)` |

*Registered but no current producer:* `SHEARS_CLIP`, `FOOTSTEP_SURFACE`, `HORSE_JUMP_LIFT`.

### 1.4 Layering, priority & dedup
`AdaptiveTriggerDispatcher.java` keeps one `LinkedHashMap<reason → Layer>` per side.

- **Heartbeat layers** (continuous states) auto-expire after `HEARTBEAT_TIMEOUT_TICKS = 2`
  unless re-pushed each tick; **one-shots** carry an explicit `durationTicks`.
- Each tick: age layers, reap expired, then the **highest-priority layer visible under
  the current screen scope** wins (scopes: `IN_WORLD` / `GUI_ONLY` / `ALWAYS`).
- The resolved effect is pushed only when it differs from the last flushed effect
  (record content-equality), so the wire stays quiet when nothing changes.
- Gating: a live DualSense with `triggerEffects` support, `adaptiveTriggersEnabled`, and
  the reason's `FeedbackCategory` enabled.

---

## 2. HD haptics

### 2.1 Waveform primitives
`controller/haptic/HapticWaveform.java` — 48 kHz S16 mono PCM. Generators: `sine`,
`square`, `saw`, `pinkNoise`, `brownNoise`. Combinators: `am` (amplitude modulation),
`withASR` (attack/sustain/release envelope), `mix` (additive, clipped), `concat`,
`scaleSamples` (gain). Deterministic generators are LRU-cached (256 entries).

`intensity = synthesisIntensity × masterIntensity`.

### 2.2 Synthesised continuous patterns (per tick)
`controller/haptic/HDHapticTickHandler.java` — looped/heartbeat textures keyed to state.

| Pattern | Trigger | Synthesis | Category |
|---|---|---|---|
| Heartbeat | HP ≤ 6 | two 50 Hz `sine` thumps; period 1000 ms → 400 ms as HP drops | COMBAT |
| Compass align | holding compass | 30–110 Hz `sine` pulses ∝ alignment² | ITEMS |
| Clock tick | clock crosses noon/midnight | 200/90 Hz `sine` click | ITEMS |
| Elytra wind | fall-flying fast | per-tick `brownNoise`, amp ∝ speed + gusts | MOVEMENT |
| Underwater | submerged | `am(sine 35 Hz, sine 0.3 Hz)` | AMBIENT |
| Rain | raining + sky visible | `pinkNoise`, amp ∝ rain level | AMBIENT |
| Conduit power | effect active | 100 Hz `sine` every ~2 s | AMBIENT |
| Magma | standing on magma | `am(sine 90 Hz, sine 4 Hz)` | AMBIENT |
| Honey | standing on honey | 25 Hz `sine` | AMBIENT |
| Altitude wind | Y > 200 | `brownNoise`, scales with height | AMBIENT |
| Beacon proximity | beacon ≤ 16 blocks | 70 Hz `sine`, amp ∝ 1/distance | AMBIENT |
| Minecart / boat / horse | riding | `brownNoise` / `am` textures, amp ∝ speed; horse adds gallop + landing thumps | MOVEMENT |
| Bee buzz | bee ≤ 4 blocks | `am(saw 220 Hz, sine 35 Hz)`, amp ∝ 1/distance | AMBIENT |
| Portal pre-transport | in portal | `am(sine 40 Hz, sine 2.5 Hz)` fading in over 80 ticks | AMBIENT |
| Dimension sweep | dimension change | one-shot `am(sine 45 Hz, sine 2 Hz)` ASR | AMBIENT |

### 2.3 Synthesised event patterns (mixins)

| Pattern | Trigger | Synthesis | Category |
|---|---|---|---|
| Footsteps | local step | per-material `sine`+`pinkNoise` body, gait sine (sprint/walk) | MOVEMENT |
| Landing thump | fall ≥ 2 | 45 Hz `sine` ASR ∝ fall² | MOVEMENT |
| Mace slam | fall ≥ 3 + mace | `mix(sine 28 Hz spike, brownNoise crackle)` | MOVEMENT |
| Damage overlays | hurt | fall/fire/drown/wither/lightning-specific textures | COMBAT |
| Shears snip | shear entity | double `pinkNoise` snip | ENVIRONMENT |
| Wrong-tool warn | ineffective swing | `pinkNoise` rasp | ENVIRONMENT |
| Fishing cues | approach/nibble/bob | soft `sine` ASR pulses | ITEMS |

### 2.4 Sound passthrough + overlays
- **Intercept:** a mixin on `SoundEngine.play(SoundInstance)` forwards every sound to
  `HDHapticDispatcher.dispatchSound`.
- **Opt-in registry:** `HapticSoundRegistry` maps sound IDs (exact, then suffix/predicate
  rules like `.step`/`.break`/`.place`) to a `HapticProfile` (category, intensity,
  `requiresLocalSource`, cooldown). Unregistered sounds are ignored.
- **Overlays:** registered synth bursts that fire *alongside* passthrough
  (`HapticSoundDefaults.java`) — e.g. note-block instruments (16 synths), bells, level-up
  ping, arrow thud, enchanting arpeggio.
- **OGG → haptics:** `HDHapticComponent.playHaptic` pulls the sound's `SoundBuffer`
  (accessor mixins), caches `CompleteSoundData`, applies gain, hands PCM to the driver.
- **Falloff:** `gain = profile.intensity × linearFalloff(distance, near, far) × master`;
  silent beyond `far`. Footsteps/swim are synthesised (not passed through) because OGG
  passthrough leaked onto the speaker / was unreliable.

### 2.5 Settings (`HDHapticSettings`)
`enabled`, `masterIntensity`, `synthesisIntensity`, per-category toggles
(MOVEMENT/COMBAT/AMBIENT/UI/ITEMS/ENVIRONMENT/MISC), `lowPassFilter`, `suppressRumble`
(drops classic rumble while HD haptics are active so the voice coils aren't double-driven),
`falloffNearBlocks` (default 0), `falloffFarBlocks` (default 64). YACL UI in
`ControllerConfigScreenFactory` with a "Test HD Haptics" button.

---

## 3. Rumble & categories
`FeedbackCategory` (MOVEMENT, COMBAT, AMBIENT, UI, ITEMS, ENVIRONMENT, MISC) is shared by
triggers and haptics but toggled independently per system. Classic rumble is the legacy
ERM path; `suppressRumble` lets HD haptics take over the voice coils cleanly.

---

## 4. Speaker (current status)
Only **volume/mute plumbing** exists — no game audio is routed to the speaker yet.

- `DualSenseComponent` holds `speakerVolume`/`headphoneVolume`/`speakerMuted`/`micMuted`;
  the driver maps them to `DS5EffectsState` (`ucSpeakerVolume` 0x3D–0x64, `ucHeadphoneVolume`
  ≤ 0x7F, mute bits). **No gameplay code sets these.**
- The 4-channel USB audio device is `[speaker, speaker, haptic_L, haptic_R]`; Controlify
  *forces channels 0/1 to silence* every buffer so haptic PCM never spills to the speaker.
- `DualSenseFeatureSupport.speakerOutput` is true only for the USB SDL audio device.

See `DUALSENSE_UPGRADES.md` for proposed speaker work.

---

## Source map
- Trigger types/encoding: `driver/dualsense/DualsenseTriggerEffect{,Types}.java`
- Trigger reasons/dispatch: `controller/dualsense/{AdaptiveTriggerDispatcher,AdaptiveTriggerHelper,AdaptiveTriggerTickHandler,ControlifyTriggerReasons,TriggerReason,TriggerHandle,TriggerSide}.java` + `mixins/feature/{rumble,dualsense,hdhaptics}/**`
- Haptic synthesis: `controller/haptic/{HapticWaveform,HDHapticTickHandler,HDHapticDispatcher,HDHapticComponent,HapticSoundRegistry,HapticSoundDefaults}.java`
- Settings/UI: `config/settings/profile/{DualsenseSettings,HDHapticSettings}.java`, `gui/screen/ControllerConfigScreenFactory.java`, `assets/controlify/lang/en_us.json`
- Transport: `driver/sdl/SDLCommonDriver.java`
