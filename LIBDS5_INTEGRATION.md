# libds5 integration — DualSense HD haptics + adaptive triggers over Bluetooth

## What this adds
Controlify already supports DualSense **HD haptics** (via an SDL 4-channel audio device) and
**adaptive triggers** (via `SDL_SendGamepadEffect`) — but **only over USB**. Over Bluetooth, SDL
exposes no DualSense haptic audio device, so both features were gated off.

This change wires in **[libds5](file:///home/mac/Software/libds5)** — a small native library that owns
the DualSense Bluetooth HID output stream — as the Bluetooth transport for those exact same effects.

The elegant part: **Controlify keeps authoring effects exactly as it does now.** Its
`DualsenseTriggerEffect` → `DS5EffectsState.TriggerEffect` byte encoding is already correct, and the
trigger mode bytes in `DualsenseTriggerEffectTypes` are byte-for-byte identical to libds5's
`DS5_TRIGGER_*` constants. Over Bluetooth we simply **forward those bytes + the haptic PCM to libds5**
instead of relying on the (absent) SDL audio device. libds5 is the single coalesced output owner.

## BT-only design (USB path is byte-for-byte unchanged)
Every libds5 call is guarded behind `isBluetooth()` and a non-null, active `LibDs5DualSense`. Over USB
nothing changed: HD haptics still render to the SDL audio device, triggers still go through
`SDL_SendControllerEffect`. The branch is a clean `if (isBluetooth()) { libds5 } else { existing SDL }`
in the driver constructor.

### Ownership split over Bluetooth (benign coexistence)
Over BT, libds5 owns **only** the adaptive triggers + HD haptics; **SDL still owns everything else**
(lightbar/LED, player-indicator LEDs, mute light, classic rumble, speaker/headphone volume, audio-mute,
haptic low-pass, light reset). This is intentional and safe: libds5's trigger/haptic reports and SDL's
LED/rumble/state reports set **disjoint DualSense valid-flag fields**, and the controller tolerates the
two writers — the same benign coexistence the in-kernel `hid-playstation` driver and `dualsensectl`
already rely on. Concretely, `updateDualSense()` does **not** early-return on the BT path: it forwards
the resolved trigger bytes to libds5 and **suppresses only the SDL trigger-effect fields** of the
`DS5EffectsState` it sends, while all other state fields are populated and sent over SDL exactly as
before the integration.

### Bus guard
`ds5_open_auto()` opens the *first* DualSense regardless of bus, so with a USB pad present it could grab
a controller SDL is already driving over USB. `LibDs5DualSense.tryCreate()` therefore calls
`ds5_get_bus()` after opening and, if the bus is **not** Bluetooth (`DS5_BUS_BT == 0`), calls
`ds5_close()` and returns `null` — leaving the USB device entirely to SDL.

### Thread safety
`ds5_close()` frees native memory. `setTriggerEffect`, `pushHaptics`, `isActive` and `close` are all
`synchronized(this)` on the `LibDs5DualSense` owner so a disconnect (`close`) racing an update can never
read the cached `device` pointer after it's been freed (use-after-free / SIGSEGV).

## Files added
- `src/main/java/dev/isxander/controlify/driver/libds5/LibDs5.java`
  — JNA interface binding for the libds5 C ABI (`ds5.h`). Maps `ds5_open_auto`, `ds5_close`,
    `ds5_get_bus`, `ds5_has_opus`, `ds5_set_trigger` (+ a `Structure` `Ds5TriggerEffect{mode; param[10]}`
    modelling the C `ds5_trigger_effect`), `ds5_set_trigger_off`, `ds5_set_rumble`, `ds5_set_lightbar`,
    `ds5_push_haptics`, `ds5_start_stream`, `ds5_stop_stream`. Loads the native lib by base name `ds5`
    via `Native.load`, with a lazy singleton that captures (never throws) the load error.
- `src/main/java/dev/isxander/controlify/driver/libds5/LibDs5DualSense.java`
  — per-controller owner of a BT `ds5_device`. `tryCreate()` opens + starts the stream (returns `null`
    + logs once on any failure). Exposes `setTriggerEffect(side, TriggerEffect)`,
    `pushHaptics(CompleteSoundData)` (decodes 8/16/32-bit PCM or float, mono→duplicated, to
    interleaved-stereo float in [-1,1]), and `close()`. Every method is a defensive no-op once closed
    or if the device never opened.

## Files changed
- `src/main/java/dev/isxander/controlify/driver/sdl/SDLCommonDriver.java`
  - New `@Nullable LibDs5DualSense libds5DualSense` field.
  - Constructor: for a DualSense, `if (isBluetooth())` → `LibDs5DualSense.tryCreate(logger)`; the
    existing SDL audio-device probe now runs only on the `else` (non-BT) branch.
  - New `hasHdHaptics()` = SDL audio device (USB) **or** active libds5 (BT).
  - `addComponents`: `DualSenseFeatureSupport.hapticAudio` and the `HDHapticComponent` attachment now
    key off `hasHdHaptics()` (so HD haptics attach over BT even though `dualsenseAudioDev == null`).
    `speakerOutput` still keys off the SDL audio device only.
  - `updateDualSense`: over BT (libds5 active) it forwards the resolved left/right trigger effect bytes
    to libds5 via the new `updateDualSenseTriggersLibDs5()` and **suppresses only the SDL trigger-effect
    fields** of the `DS5EffectsState` (no `ALLOW_*_TRIGGER_FFB` / trigger bytes set) while still sending
    all other state (LEDs/mute/volumes/etc.) via `SDL_SendControllerEffect`, exactly as before. It does
    **not** early-return on the BT path.
  - `playHaptic`: over BT (libds5 active) it calls `libds5DualSense.pushHaptics(sound)` and returns,
    instead of the SDL audio-stream path.
  - `close()`: closes `libds5DualSense` (stop stream + close device).

## How effects flow over Bluetooth now
- **Adaptive triggers:** `AdaptiveTriggerDispatcher.tick()` → `DualSenseComponent.set{Left,Right}TriggerEffect`
  (unchanged) → `updateDualSense()` sees `isBluetooth` + active libds5 → `effect.createState()` (the
  existing, correct encoder) → `LibDs5DualSense.setTriggerEffect(side, fx)` → `ds5_set_trigger(device,
  side, {mode=fx.effectType, param=fx.p0..p9})`. libds5 flushes it on the BT stream.
- **HD haptics:** `HDHapticComponent` → `playHaptic(CompleteSoundData)` → `LibDs5DualSense.pushHaptics`
  decodes the clip's PCM to interleaved-stereo float and calls `ds5_push_haptics(device, pcm, frames,
  rate)`. libds5 resamples to the controller's native 3 kHz haptic rate and coalesces it.

## Speaker/mic virtual audio devices
When the Bluetooth owner starts (`LibDs5DualSense.tryCreate` → after `ds5_start_stream`), it calls
`ds5_audio_bridge_start(device)`. If libds5 was built with its PipeWire bridge, libds5 registers the
controller's audio endpoints as **OS virtual audio devices** — `DualSense (BT) Speaker` (a sink) and
`DualSense (BT) Mic` (a source) — so any app can route to them through the OS audio graph, exactly like
the **wired** controller's USB-audio devices. libds5 owns both directions internally: PCM written to the
speaker sink is Opus-encoded and streamed to the controller, and mic audio from the controller is decoded
and published to the source. **Controlify pushes no speaker PCM and pulls no mic itself.**

This is **best-effort and independent of HD haptics**: HD haptics remain a separate push path
(`ds5_push_haptics`), and if the bridge fails to start (e.g. libds5 built without PipeWire → `DS5_ENOTSUP`,
or no running PipeWire server) the owner stays alive with triggers + haptics fully working — the outcome is
just logged once (`DS5_OK` → info, `DS5_ENOTSUP` → debug, else → warn). `close()` calls
`ds5_audio_bridge_stop(device)` **before** `ds5_close(device)`. Note that audio actually flows only while
the controller is connected; the virtual devices are torn down when the BT owner closes.

## Loading the native lib
The native lib is loaded by base name `ds5` (`libds5.so`) via `Native.load("ds5")`.

**Bundled in the jar (default, sandbox-proof).** `build.gradle.kts` has a `prepareLibds5Natives` task
that copies the locally-built `libds5.so` into the jar at JNA's resource path
`linux-x86-64/libds5.so`. JNA auto-extracts and loads it from the classpath — so it works inside
**sandboxed launchers (PrismLauncher/Flatpak)** with **no `-Djna.library.path` and no system install**.
The source dir defaults to `~/Software/libds5/build`; override with `-Plibds5BuildDir=/path` or the
`LIBDS5_BUILD_DIR` env var. **Rebuild libds5 (`ninja -C build`) before building the mod jar** so the
latest `.so` is bundled.

- **Linux x86-64 only for now** — there is no published libds5 artifact yet, so only the local-build
  native is bundled. Other platforms (and CI without a local build) ship without it: the feature stays
  off at runtime, the mod still runs. (Future: publish per-platform libds5 natives like the SDL3 ones
  and add them to `nativeTargets`.)
- **Runtime deps:** `libds5.so` needs `libopus.so.0` + `libpipewire-0.3.so.0` present in the runtime
  (standard in the Flatpak freedesktop runtime). If loading fails with an `UnsatisfiedLinkError` naming
  one of those, that dep is missing from the sandbox/runtime.
- **Alternatives** if you're not using the bundled native: install libds5 system-wide
  (`sudo meson install` → `/usr/local/lib/<triplet>`, on the loader path) or, for an unsandboxed dev run,
  `-Djna.library.path=/home/mac/Software/libds5/build`.

If the library can't be loaded by any route, `LibDs5.getOrNull()` returns `null`, `LibDs5DualSense.tryCreate`
logs a single warning and returns `null`, and BT rich features simply stay off — the mod never crashes.
**This is exactly what the "HD Haptics not supported" UI reflects when the native is absent** — the
detection (`hasHdHaptics()` → `HDHapticComponent` presence → the GUI label) is working correctly; it just
needs the native to load.

## Compile status
- **The two new files (`LibDs5.java`, `LibDs5DualSense.java`) compile cleanly** — re-verified with a
  targeted `javac` against the resolved classpath (compiled Controlify `main` classes + JNA 5.17 + JetBrains
  annotations): 0 errors, all 5 classes emitted. The modified `SDLCommonDriver.java` is a small, reviewed
  diff (BT branch + helpers); its only new symbols are the two libds5 classes just verified.
- A full `./gradlew :1.21.11-fabric:compileJava --offline` currently fails, but **only for pre-existing,
  unrelated reasons**: remapped-Minecraft render/screenop symbols (`net.minecraft.client.renderer.state.gui`,
  etc.) and optional Sodium/Iris mod-compat mixins are unresolved in offline mode. Every error is in
  `utils/render`, `screenop`, `virtualmouse`, `gui`, or `mixins` — **none reference `driver/sdl`,
  `driver/libds5`, `LibDs5`, or `SDLCommonDriver`**. A normal (online) build with the remapped MC + mod-compat
  deps present is needed for a full end-to-end compile.

## Hardware validation (libds5 + kernel side)
The full BT audio path this integration depends on is **validated on hardware**: with libds5 in audio mode
(PipeWire bridge up, mic streaming) and the `hid-playstation-ds5` C-lite kernel module loaded, the gamepad
input device stayed completely clean (0 spurious events) while audio/mic streamed — the scenario that
previously flung the mouse. libds5 also now tears down audio mode cleanly on close (final mic-off `0x36`),
so the controller reverts to gamepad-only reporting rather than streaming audio until the link drops.
See `DS5Dongle/8-dual-control.md` §8.6.

## TODOs / risks
- **Native packaging (DONE for Linux x86-64):** `libds5.so` is now bundled into the jar at
  `linux-x86-64/libds5.so` and auto-extracted by JNA (see "Loading the native lib"). Remaining: publish
  per-platform libds5 natives (aarch64/macOS/Windows) and add them to `nativeTargets`, like SDL3.
- **Single-owner-for-ALL-output (follow-up TODO):** today over BT, libds5 owns triggers + haptics while
  SDL still owns LED/rumble/volume/other state — a deliberate, benign two-writer split (disjoint
  valid-flags). A future refactor could make libds5 the single owner of *all* DualSense output over BT,
  routing lightbar/player-LEDs/mute/rumble through `ds5_set_lightbar` / `ds5_set_player_leds` /
  `ds5_set_mic_mute_led` / `ds5_set_rumble` (all bound/available) too. **Blocked:** libds5 currently has
  **no speaker/headphone-volume or audio-mute setters**, so volume/mute would regress unless those are
  added to libds5 first. Do **not** attempt the single-owner refactor until those setters exist; the
  current split is correct and complete in the meantime.
- **Mic / speaker:** delivered via the libds5 **PipeWire audio bridge** (`ds5_audio_bridge_start/stop`),
  which exposes them as OS virtual audio devices (see "Speaker/mic virtual audio devices" above). The
  manual `ds5_push_speaker` / `ds5_pull_mic` / `ds5_has_opus` entry points stay intentionally unwired —
  the bridge owns both directions, so Controlify never pumps PCM itself.
- **Haptic rate:** `pushHaptics` passes the clip's source sample rate to libds5 (which resamples).
  Synthesized haptics are 48 kHz mono; OGG-sourced clips vary. If a clip reports a zero/invalid rate we
  fall back to 3000 Hz.
