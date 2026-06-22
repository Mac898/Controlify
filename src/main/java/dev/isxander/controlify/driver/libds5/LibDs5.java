package dev.isxander.controlify.driver.libds5;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;

/**
 * JNA binding for <b>libds5</b> — a small native library that drives a DualSense's rich features
 * (adaptive triggers + HD haptics) over its Bluetooth HID transport. Controlify already supports
 * those features over USB through SDL (an SDL audio device for HD haptics + {@code SDL_SendGamepadEffect}
 * for triggers); over Bluetooth SDL exposes no haptic audio device, so we forward the already-authored
 * effect bytes and haptic PCM to libds5 instead. libds5 owns the single coalesced output stream.
 *
 * <p>This maps the public C ABI declared in {@code ds5/ds5.h}. The {@code DS5_TRIGGER_*} mode bytes in
 * {@code ds5/ds5_protocol.h} are identical to Controlify's
 * {@link dev.isxander.controlify.driver.sdl.dualsense.DualsenseTriggerEffectTypes}, so the
 * {@link dev.isxander.controlify.driver.sdl.dualsense.DS5EffectsState.TriggerEffect} bytes Controlify
 * already produces can be forwarded verbatim.
 *
 * <h2>Native loading (dev)</h2>
 * The library is loaded by its base name {@code "ds5"} (i.e. {@code libds5.so} on Linux). For
 * development you must point JNA at the build output, e.g. add the JVM flag
 * <pre>-Djna.library.path=/home/mac/Software/libds5/build</pre>
 * TODO: proper packaging should extract a bundled {@code libds5} from the jar at runtime, mirroring
 * {@link dev.isxander.controlify.driver.sdl.SDLNativesLoader} / libsdl4j's natives handling, rather
 * than relying on {@code jna.library.path}.
 */
public interface LibDs5 extends Library {
    /** Lazily-loaded singleton. {@code null} if the native library could not be loaded. */
    final class Holder {
        private Holder() {}

        static final LibDs5 INSTANCE;
        static final Throwable LOAD_ERROR;

        static {
            LibDs5 instance = null;
            Throwable error = null;
            try {
                instance = Native.load("ds5", LibDs5.class);
            } catch (Throwable t) {
                error = t;
            }
            INSTANCE = instance;
            LOAD_ERROR = error;
        }
    }

    /**
     * @return the loaded binding, or {@code null} if {@code libds5} is unavailable on this system.
     *         Callers must null-check and degrade gracefully — Bluetooth rich features simply stay off.
     */
    static LibDs5 getOrNull() {
        return Holder.INSTANCE;
    }

    /** @return the {@link Throwable} from the load attempt, or {@code null} if loading succeeded. */
    static Throwable loadError() {
        return Holder.LOAD_ERROR;
    }

    // ---- Return codes (ds5.h enum; plain ints over the ABI) -----------------
    int DS5_OK = 0;
    /** Feature compiled out (e.g. no libopus / no PipeWire bridge). */
    int DS5_ENOTSUP = -3;

    // ---- ds5_side / ds5_bus (plain ints over the ABI) -----------------------
    int DS5_BUS_BT = 0;
    int DS5_BUS_USB = 1;
    int DS5_SIDE_LEFT = 0;
    int DS5_SIDE_RIGHT = 1;

    // ---- Lifecycle ----------------------------------------------------------
    /** Find + open the first DualSense via libds5's built-in backend. Returns {@code null} if none found. */
    Pointer ds5_open_auto();
    void ds5_close(Pointer device);

    /** @return {@link #DS5_BUS_BT} or {@link #DS5_BUS_USB}. */
    int ds5_get_bus(Pointer device);
    /** @return 1 if libds5 was built with libopus (speaker/mic), else 0. */
    int ds5_has_opus();

    // ---- Adaptive triggers --------------------------------------------------
    /**
     * Queue an adaptive-trigger effect. Maps the C signature
     * {@code ds5_set_trigger(ds5_device*, ds5_side, const ds5_trigger_effect*)}.
     */
    void ds5_set_trigger(Pointer device, int side, Ds5TriggerEffect.ByReference fx);
    void ds5_set_trigger_off(Pointer device, int side);

    // ---- LEDs / rumble ------------------------------------------------------
    void ds5_set_rumble(Pointer device, byte left, byte right);
    void ds5_set_lightbar(Pointer device, byte r, byte g, byte b);

    // ---- Audio out ----------------------------------------------------------
    /**
     * Push HD haptics: interleaved-stereo float in [-1,1] at {@code rate} Hz. libds5 resamples to the
     * controller's native haptic rate and quantizes. {@code frames} is the per-channel frame count
     * (so {@code stereo.length == frames * 2}).
     *
     * @return {@code DS5_OK} (0) or a negative error code.
     */
    int ds5_push_haptics(Pointer device, float[] stereo, int frames, int rate);

    // ---- Speaker/mic as OS virtual audio devices (PipeWire) -----------------
    /**
     * Expose the controller speaker as an OS output device and its mic as an OS input device, bridged
     * through libds5 (PipeWire) so any app routes to them just like the wired DualSense's USB-audio
     * devices. libds5 owns the speaker-PCM push and mic pull internally — Controlify does not.
     *
     * @return {@code DS5_OK} (0), {@code DS5_ENOTSUP} (-3) if libds5 was built without the PipeWire
     *         bridge, or another negative error code. Idempotent.
     */
    int ds5_audio_bridge_start(Pointer device);
    void ds5_audio_bridge_stop(Pointer device);
    /** @return 1 if libds5 was built with the PipeWire audio bridge, else 0. */
    int ds5_has_audio_bridge();

    // ---- Pump ---------------------------------------------------------------
    /** Start libds5's internal thread that pumps poll()+flush() at the audio cadence. @return DS5_OK or negative. */
    int ds5_start_stream(Pointer device);
    void ds5_stop_stream(Pointer device);

    /**
     * Mirror of C {@code struct ds5_trigger_effect { uint8_t mode; uint8_t param[10]; }} — 11 bytes,
     * which is exactly Controlify's {@code TriggerEffect} (effectType + p0..p9).
     */
    @Structure.FieldOrder({"mode", "param"})
    class Ds5TriggerEffect extends Structure {
        public byte mode;
        public byte[] param = new byte[10];

        public Ds5TriggerEffect() {
            super();
        }

        public Ds5TriggerEffect(byte mode, byte[] param) {
            this.mode = mode;
            System.arraycopy(param, 0, this.param, 0, Math.min(param.length, this.param.length));
        }

        public static class ByReference extends Ds5TriggerEffect implements Structure.ByReference {
            public ByReference() {
                super();
            }

            public ByReference(byte mode, byte[] param) {
                super(mode, param);
            }
        }
    }
}
