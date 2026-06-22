package dev.isxander.controlify.driver.libds5;

import com.sun.jna.Pointer;
import dev.isxander.controlify.controller.haptic.CompleteSoundData;
import dev.isxander.controlify.driver.sdl.dualsense.DS5EffectsState;
import dev.isxander.controlify.utils.log.ControlifyLogger;
import org.jetbrains.annotations.Nullable;

import javax.sound.sampled.AudioFormat;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Owns a single {@code ds5_device} opened over libds5 for a DualSense connected via <b>Bluetooth</b>.
 *
 * <p>Over USB, Controlify keeps using SDL (the SDL audio device + {@code SDL_SendGamepadEffect});
 * this class is only instantiated on the Bluetooth path, where SDL exposes no haptic audio device.
 * Controlify authors effects exactly as before — its {@link dev.isxander.controlify.driver.sdl.dualsense.DualsenseTriggerEffect}
 * produces the correct {@link DS5EffectsState.TriggerEffect} bytes — and we simply forward those bytes
 * plus the haptic PCM to libds5, which is the single owner of the coalesced BT output stream.
 *
 * <p>Construction never throws: if libds5 is unavailable or no device opens, {@link #isActive()}
 * returns {@code false} and every method is a no-op, so Bluetooth rich features stay off and the mod
 * keeps running. Use {@link #tryCreate} to obtain an instance.
 */
public final class LibDs5DualSense {
    /** Native haptic rate the DualSense BT audio path expects (libds5 resamples to this). */
    private static final int HAPTIC_RATE_HZ = 3000;

    private static boolean loggedLoadFailure = false;

    private final ControlifyLogger logger;
    private final LibDs5 lib;
    /**
     * Native {@code ds5_device}, or {@code null} once {@link #close()} has freed it. Guarded by
     * {@code this}: {@link #setTriggerEffect}, {@link #pushHaptics} and {@link #close} all read/clear it
     * under {@code synchronized(this)} so a disconnect ({@code close}) racing an update can never pass a
     * freed pointer to native code ({@code ds5_close} frees native memory → use-after-free / SIGSEGV).
     */
    private @Nullable Pointer device;

    private LibDs5DualSense(ControlifyLogger logger, LibDs5 lib, Pointer device) {
        this.logger = logger;
        this.lib = lib;
        this.device = device;
    }

    /**
     * Attempt to open a DualSense over libds5 and start its output stream. Returns {@code null} on any
     * failure (library missing, no device, wrong bus) after logging once — callers leave the feature off.
     */
    public static @Nullable LibDs5DualSense tryCreate(ControlifyLogger logger) {
        LibDs5 lib = LibDs5.getOrNull();
        if (lib == null) {
            if (!loggedLoadFailure) {
                loggedLoadFailure = true;
                logger.warn(
                        "libds5 native library could not be loaded; DualSense HD haptics + adaptive triggers "
                                + "over Bluetooth are disabled. For dev, set -Djna.library.path to the libds5 build dir.",
                        LibDs5.loadError());
            }
            return null;
        }

        Pointer device;
        try {
            device = lib.ds5_open_auto();
        } catch (Throwable t) {
            logger.warn("libds5 ds5_open_auto() threw; Bluetooth DualSense rich features disabled.", t);
            return null;
        }

        if (device == null) {
            logger.log("libds5 found no DualSense to open over Bluetooth; rich features stay on the SDL path only.");
            return null;
        }

        LibDs5DualSense owner = new LibDs5DualSense(logger, lib, device);

        try {
            int bus = lib.ds5_get_bus(device);
            logger.debugLog("libds5 opened DualSense on bus {} (0=BT, 1=USB).", bus);

            // ds5_open_auto() opens the FIRST DualSense regardless of bus. If it grabbed a USB pad, SDL
            // already owns that controller over USB — close and bail so the two don't both drive it.
            if (bus != LibDs5.DS5_BUS_BT) {
                logger.log("libds5 opened a non-Bluetooth DualSense (bus={}); closing — SDL owns USB.", bus);
                owner.close();
                return null;
            }

            int rc = lib.ds5_start_stream(device);
            if (rc != 0) {
                logger.warn("libds5 ds5_start_stream() failed (rc={}); closing and disabling BT rich features.", rc);
                owner.close();
                return null;
            }

            // Expose the controller speaker/mic as OS virtual audio devices (PipeWire). Best-effort:
            // a failure here must NOT disable triggers/haptics, so we only log and keep the owner alive.
            owner.startAudioBridge();
        } catch (Throwable t) {
            logger.warn("libds5 failed while starting the output stream; disabling BT rich features.", t);
            owner.close();
            return null;
        }

        logger.log("libds5 active: forwarding DualSense adaptive triggers + HD haptics over Bluetooth.");
        return owner;
    }

    public synchronized boolean isActive() {
        return device != null;
    }

    /**
     * Forward an already-rendered trigger effect to libds5. {@code fx.effectType} is the
     * {@code DS5_TRIGGER_*} mode and {@code fx.p0..p9} are the 10 param bytes — identical to the
     * C {@code ds5_trigger_effect} layout.
     *
     * @param sideLibDs5 {@link LibDs5#DS5_SIDE_LEFT} or {@link LibDs5#DS5_SIDE_RIGHT}
     */
    public synchronized void setTriggerEffect(int sideLibDs5, DS5EffectsState.TriggerEffect fx) {
        if (device == null || fx == null) return;
        try {
            byte[] params = { fx.p0, fx.p1, fx.p2, fx.p3, fx.p4, fx.p5, fx.p6, fx.p7, fx.p8, fx.p9 };
            LibDs5.Ds5TriggerEffect.ByReference effect = new LibDs5.Ds5TriggerEffect.ByReference(fx.effectType, params);
            effect.write();
            lib.ds5_set_trigger(device, sideLibDs5, effect);
        } catch (Throwable t) {
            logger.debugWarn("libds5 ds5_set_trigger failed", t);
        }
    }

    /**
     * Convert {@code sound}'s PCM to interleaved-stereo float in [-1,1] and push it to libds5's HD
     * haptic stream. Mono is duplicated to both channels. Supports the 8/16/32-bit signed-PCM and
     * 32-bit float encodings the haptic pipeline produces.
     */
    public synchronized void pushHaptics(CompleteSoundData sound) {
        if (device == null || sound == null) return;
        try {
            float[] stereo = toInterleavedStereoFloat(sound.audio(), sound.format());
            if (stereo.length == 0) return;
            int frames = stereo.length / 2;
            int rate = (int) sound.format().getSampleRate();
            lib.ds5_push_haptics(device, stereo, frames, rate > 0 ? rate : HAPTIC_RATE_HZ);
        } catch (Throwable t) {
            logger.debugWarn("libds5 ds5_push_haptics failed", t);
        }
    }

    /**
     * Ask libds5 to register the controller speaker/mic as OS virtual audio devices (PipeWire), so apps
     * route to "DualSense (BT) Speaker"/"...Mic" exactly like the wired controller's USB-audio devices.
     * libds5 owns the speaker-PCM push + mic pull internally; Controlify pumps nothing. Best-effort — a
     * failure leaves triggers + HD haptics fully working, so we only log the outcome once.
     */
    private synchronized void startAudioBridge() {
        if (device == null) return;
        try {
            int rc = lib.ds5_audio_bridge_start(device);
            if (rc == LibDs5.DS5_OK) {
                logger.log("DualSense speaker/mic exposed as audio devices.");
            } else if (rc == LibDs5.DS5_ENOTSUP) {
                logger.debugLog("libds5 built without PipeWire bridge; no virtual audio devices.");
            } else {
                logger.warn("libds5 ds5_audio_bridge_start() failed (rc={}); no virtual audio devices.", rc);
            }
        } catch (Throwable t) {
            logger.debugWarn("libds5 ds5_audio_bridge_start failed", t);
        }
    }

    public synchronized void close() {
        if (device == null) return;
        Pointer d = device;
        device = null; // null first so concurrent callers no-op even if close throws
        try {
            lib.ds5_audio_bridge_stop(d);
        } catch (Throwable t) {
            logger.debugWarn("libds5 ds5_audio_bridge_stop failed", t);
        }
        try {
            lib.ds5_stop_stream(d);
        } catch (Throwable t) {
            logger.debugWarn("libds5 ds5_stop_stream failed", t);
        }
        try {
            lib.ds5_close(d);
        } catch (Throwable t) {
            logger.debugWarn("libds5 ds5_close failed", t);
        }
    }

    /**
     * Decode raw PCM bytes into interleaved-stereo float in [-1,1]. Mono input is duplicated across
     * L/R; stereo (or more) input keeps its first two channels interleaved.
     */
    private static float[] toInterleavedStereoFloat(byte[] audio, AudioFormat format) {
        int channels = Math.max(1, format.getChannels());
        int bits = format.getSampleSizeInBits();
        int bytesPerSample = bits / 8;
        if (bytesPerSample <= 0) return new float[0];

        AudioFormat.Encoding encoding = format.getEncoding();
        boolean isFloat = encoding == AudioFormat.Encoding.PCM_FLOAT;
        boolean isUnsigned = encoding == AudioFormat.Encoding.PCM_UNSIGNED;
        ByteOrder order = format.isBigEndian() ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN;

        int frameBytes = bytesPerSample * channels;
        if (frameBytes <= 0) return new float[0];
        int frames = audio.length / frameBytes;
        if (frames <= 0) return new float[0];

        ByteBuffer buf = ByteBuffer.wrap(audio).order(order);
        float[] out = new float[frames * 2];

        for (int frame = 0; frame < frames; frame++) {
            float left = 0f;
            float right = 0f;
            for (int ch = 0; ch < channels; ch++) {
                int sampleOffset = frame * frameBytes + ch * bytesPerSample;
                float sample = readSample(buf, sampleOffset, bytesPerSample, isFloat, isUnsigned);
                if (channels == 1) {
                    left = sample;
                    right = sample;
                } else if (ch == 0) {
                    left = sample;
                } else if (ch == 1) {
                    right = sample;
                }
                // channels > 2: ignore extras (DualSense haptics are 2-channel).
            }
            out[frame * 2] = left;
            out[frame * 2 + 1] = right;
        }

        return out;
    }

    /** Read one sample at {@code offset} and normalise it to [-1,1]. */
    private static float readSample(ByteBuffer buf, int offset, int bytesPerSample, boolean isFloat, boolean isUnsigned) {
        if (isFloat) {
            if (bytesPerSample == 4) {
                return clamp(buf.getFloat(offset));
            }
            if (bytesPerSample == 8) {
                return clamp((float) buf.getDouble(offset));
            }
            return 0f;
        }

        switch (bytesPerSample) {
            case 1 -> {
                if (isUnsigned) {
                    int v = buf.get(offset) & 0xFF;
                    return clamp((v - 128) / 128f);
                }
                return clamp(buf.get(offset) / 128f);
            }
            case 2 -> {
                return clamp(buf.getShort(offset) / 32768f);
            }
            case 4 -> {
                return clamp(buf.getInt(offset) / (float) Integer.MAX_VALUE);
            }
            default -> {
                return 0f;
            }
        }
    }

    private static float clamp(float v) {
        if (v > 1f) return 1f;
        if (v < -1f) return -1f;
        return v;
    }
}
