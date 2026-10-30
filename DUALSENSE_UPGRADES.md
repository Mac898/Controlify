# DualSense upgrade brainstorm — adaptive triggers · HD haptics · embedded speaker

60+ concrete upgrade ideas for Controlify's DualSense handling, grouped by system, each
with a one-line rationale and a pointer to where it lands. Effects authoring is
transport-agnostic, so unless noted these apply to any connection.
Cross-reference the current state in `DUALSENSE_EFFECTS.md`.

Priority tags: **[P1]** high value / low effort · **[P2]** solid · **[P3]** ambitious / research.

---

## A. Adaptive triggers — gameplay coverage

1. **[P2] Wire up the registered-but-dead reasons** — `FOOTSTEP_SURFACE`, `SHEARS_CLIP`,
   `HORSE_JUMP_LIFT` are defined with no producer. Implement them (e.g. subtle per-surface
   footstep resistance) so the catalogue isn't lying.
2. **[P2] Galloping/Machine effects in use** — both records exist but nothing emits them.
   Use `Galloping` for horse/strider/camel gaits and `Machine` for minecarts on powered
   rails / piston-adjacent / brewing-stand bubbling.
3. **[P1] Mining hardness → resistance curve** — today tool-vs-block is a flat
   `Feedback(2, 1–8)`. Use `FeedbackSlope` so resistance *ramps* as the block nears break,
   giving a tactile "almost there" cue.
4. **[P2] Fishing tension by fish weight / struggle** — modulate the held `Vibration` on
   bite with the hook's tug timing, and ramp reel `Feedback` strength with line tension.
5. **[P2] Bow over-draw warning** — once fully drawn, add a sharp `Vibration` flutter past
   the snap zone to signal "release now / holding too long shakes aim."
6. **[P2] Per-mob attack signatures** — vary the sword-hit trigger snap by target armor /
   entity size (hitting iron golem ≠ hitting a chicken).
7. **[P3] Recoil/knockback on R2 ranged** — brief trigger kick on bow/crossbow/trident
   release proportional to projectile power, distinct from the muzzle vibration.
8. **[P2] Elytra control surfaces** — map pitch/dive to trigger resistance so steep dives
   feel "heavier," reinforcing the existing firework pulse.
9. **[P2] Vehicle throttle feel** — boats/minecarts: `Feedback` resistance that tracks
   acceleration vs cruising, not just a flat ride buzz.
10. **[P1] Block-place "click"** — current `Weapon(3,4,3)` is fine; add a tiny terminal
    snap (`Bow` snapForce) so placement has a satisfying detent.
11. **[P2] Shield bash / parry window** — distinct trigger pulse during the brief
    perfect-block window vs sustained `Feedback(6,8)` hold.
12. **[P2] Eating/drinking progress slope refinement** — make the chew/gulp cadence track
    the item's actual use duration (cookie ≠ golden apple).
13. **[P3] Redstone/lever/button interactables** — light `Feedback` detent when the
    crosshair is over an interactable that R2/use would toggle.
14. **[P2] Enchanting/anvil/grindstone GUI triggers** — `GUI_ONLY` scope already exists;
    add trigger detents to slider/anvil interactions.
15. **[P2] Boss-fight intensity layer** — a low-priority ambient trigger tension while in
    a boss's bounding region (dragon/wither/warden), scaled by proximity.

## B. Adaptive triggers — fidelity & systems

16. **[P1] Read back trigger telemetry** — the input report carries trigger effect/status
    nibbles (`left/right_trigger_status`). Surface them so effects
    can confirm the controller accepted the mode and self-heal on desync.
17. **[P2] Smooth effect transitions** — interpolate between consecutive `Feedback`/`Bow`
    states over a few ticks instead of hard-switching, to remove "stair-stepping" feel.
18. **[P2] Priority-aware blending** — instead of strict winner-takes-all per side, blend a
    passive baseline (held-item resistance) with a transient burst where the modes are
    compatible (e.g. baseline `Feedback` + brief `Vibration` overlay).
19. **[P2] Hysteresis on flapping reasons** — fast-toggling environment reasons (in/out of
    water, on/off ice) can chatter; add small enter/exit hysteresis.
20. **[P3] Calibration-aware zones** — DualSense units vary; expose a per-user trigger
    travel calibration so zone 0–9 maps to *felt* positions consistently.
21. **[P2] "Trigger profiles" presets** — Off / Subtle / Standard / Strong as a single
    dropdown that scales `triggerStrengthMultiplier` + category set, for quick tuning.
22. **[P3] Per-reason strength overrides in config** — power users tune individual reasons
    (data-driven JSON), not just a global multiplier.
23. **[P1] Effect dedup across sides** — minor: confirm a symmetric both-sides effect isn't
    encoded/sent twice; share the encoded record between sides.

## C. HD haptics — new patterns

24. **[P2] Status-effect textures** — poison (irregular pulse), regen (gentle rising
    swell), nausea (slow `am` wobble), haste/slowness, levitation. Maps cleanly onto the
    existing per-tick synth handler.
25. **[P2] Hunger/saturation low-rumble** — subtle stomach-growl `brownNoise` swell at very
    low food, distinct from the sprint-trigger cue.
26. **[P2] Weather transitions** — thunder *strike* haptic (sharp `brownNoise` crack synced
    to lightning) layered over the existing rain texture.
27. **[P2] Mob proximity / threat radar** — low directional-ish pulse as hostiles approach
    in the dark (gated, AMBIENT), intensity by nearest-hostile distance.
28. **[P2] Block-interaction textures** — opening chests/doors/trapdoors, lever flips,
    button presses as short synth clicks (some already via note overlays; extend).
29. **[P3] Biome ambience beds** — a very low continuous texture per biome family (cave
    drone, ocean swell, desert wind) at low intensity, opt-in.
30. **[P2] Crafting/smelting feedback** — furnace "done" ping, crafting-complete click,
    brewing bubble loop while a stand is active and nearby.
31. **[P2] XP orb pickup shimmer** — light ascending `sine` ping on orb absorb (rate-limited
    so a big orb burst doesn't machine-gun).
32. **[P2] Sculk/Warden tension** — sculk-sensor "listening" pulse and a heartbeat-like
    dread bed near a Warden, distinct from the HP heartbeat.
33. **[P2] Projectile whoosh (incoming)** — brief directional-ish brown-noise swell when an
    arrow/trident passes close, separate from the hit overlay.
34. **[P3] Music-reactive haptics** — when a jukebox/note-block sequence plays nearby,
    derive a rhythmic haptic from the audio envelope (ties into §E speaker work).

## D. HD haptics — fidelity & systems

35. **[P1] Apply the low-pass filter setting end-to-end** — ensure `lowPassFilter` actually
    filters the passthrough PCM (voice coils muddy on HF content); verify it's wired, not
    just a stored flag.
36. **[P2] Per-channel (L/R) haptics** — the controller has two actuators; today most synth
    is mono. Pan footsteps/impacts/proximity by source direction for spatial feel.
37. **[P2] Proper haptic mixer/bus** — multiple simultaneous sources (heartbeat + footsteps
    + rain) currently rely on the controller/driver; add an explicit software mix bus with
    per-source gain + soft clip so layering is predictable.
38. **[P2] Resampler quality** — Upgrade to polyphase/speexdsp resampling for cleaner haptic
    + speaker output.
39. **[P2] Loudness normalisation for passthrough** — game OGGs vary wildly in level;
    normalise to a target RMS before quantising so haptic strength is consistent.
40. **[P1] Cooldown/limit polish** — the 3-tick per-sound cooldown is global; make it
    per-category or per-source so rapid distinct sounds aren't swallowed while spam is
    still tamed.
41. **[P2] Haptic ducking** — when a high-priority transient fires (damage, big impact),
    briefly duck ambient beds so the important cue cuts through.
42. **[P3] Envelope library expansion** — add decay curves (exp), `concat` ramps, and a
    one-pole resonator for "pluck" realism beyond ASR.
43. **[P2] Synthesis determinism + cache hits** — confirm the LRU cache key covers all
    generator params; noise variants are intentionally uncached — consider a small ring of
    pre-rendered noise to cut per-tick allocation.
44. **[P2] Test-bench screen** — extend the "Test HD Haptics" button into a small palette
    that plays each named pattern, for tuning without entering a world.

## E. Embedded speaker — the big opportunity (mostly greenfield)

45. **[P1] Route chosen game audio to the speaker** — the headline TODO. Add a `speakerOutput`
    path that sends selected sounds (or a submix) to the controller speaker via the SDL
    audio device. Gate by category like haptics.
46. **[P1] "Personal sounds" submix** — UI clicks, level-up, advancement toasts, low-health
    warning, item pickup → the controller speaker (close, personal) while world audio stays
    on the TV/headset. High immersion, low bandwidth.
47. **[P2] Speaker volume in settings + per-category routing** — expose
    `setSpeakerVolume`/`setSpeakerMuted` (plumbing already exists) in YACL, plus a
    "which categories go to the speaker" matrix.
48. **[P2] Auto headphone/speaker routing** — follow the controller's headphone-plug bit
    (`headphones` in the input report) to switch between `0x13` speaker and `0x16` headset
    segments automatically.
49. **[P2] Voice/ambient cues** — villager "hmm", totem pop, raid horn, or a configurable
    proximity ping out of the controller speaker.
50. **[P3] Directional white-noise compass** — pan a faint cue across speaker vs haptics to
    hint at an objective bearing (accessibility + exploration).
51. **[P2] Speaker click for trigger detents** — pair a tiny speaker tick with adaptive-
    trigger detents (place/break) for a tactile-plus-audible "snap."
52. **[P3] Mic integration (input)** — expose the DualSense mic to voice-chat mods
    (Simple Voice Chat) as a selectable input, closing the full-duplex loop.
53. **[P2] Speaker test + level meter** — a settings button that plays a test tone and shows
    capture level, mirroring the haptic test, so users can verify routing/volume.
54. **[P3] Sidetone / haptic-from-speaker coherence** — when a sound plays on both speaker
    and haptics, time-align them so the thump and the click coincide.

## F. Cross-cutting — architecture, perf, UX, accessibility

55. **[P1] Data-driven sound→effect mappings** — move `HapticSoundDefaults` /
    `ControlifyTriggerReasons` mappings into a resource JSON so packs/users can add or
    retune mappings without recompiling.
56. **[P2] Per-controller capability cache** — detect edge/firmware variants and disable
    unsupported effects gracefully (some clones lack adaptive triggers).
57. **[P2] Telemetry/diagnostics overlay** — a debug HUD showing active trigger layers per
    side, current haptic sources, and bus/transport — invaluable for tuning and bug reports.
58. **[P2] Battery-aware throttling** — reduce haptic/speaker intensity
    when controller battery is low, to extend playtime.
59. **[P1] Accessibility presets** — "reduced motion," "haptics-only (no triggers)," and a
    global intensity that also tames classic rumble, for users sensitive to strong forces.
60. **[P2] Latency budget + soak test** — measure event→haptic onset and run a 30-min soak
    (audio cadence/drop-frame) to catch the audio jitter/underrun class of bugs (see the
    speaker-buzz investigation note).
61. **[P2] Profile import/export & sharing** — let users export their trigger/haptic tuning
    as a shareable file/string.
62. **[P3] Per-dimension / modpack profiles** — auto-select an effect profile by dimension
    or detected modpack (Nether = more heat textures, etc.).

---

## Suggested first wave
If picking a handful to start: **#45/#46** (speaker submix — the missing pillar), **#3**
(mining ramp — cheap, high feel), **#35** (verify low-pass), **#16** (trigger telemetry
read-back — unlocks self-healing + #58), **#55** (data-driven mappings — multiplies all
future content), and **#57** (cross-platform native packaging — unblocks non-Linux users).
