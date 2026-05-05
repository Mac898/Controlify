package dev.isxander.controlify.controller.haptic;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * A continuous haptic drone built from a short PCM buffer. The dispatcher re-queues {@link #nextChunk()}
 * back-to-back; the crossfade ramp at each end prevents clicks at the seam.
 */
public final class HapticLoop {
    private final HapticWaveform base;
    private final int crossfadeMs;
    private final int chunkTicks;
    private float amplitude = 1f;
    /** Ticks of audio currently buffered downstream. Decremented each {@link #onTickConsumed()} call. */
    private int ticksRemaining = 0;

    public HapticLoop(HapticWaveform base) {
        this(base, 40);
    }

    public HapticLoop(HapticWaveform base, int crossfadeMs) {
        this.base = base;
        this.crossfadeMs = Math.max(0, crossfadeMs);
        // ms / 50 = ticks (Minecraft is 20Hz). Floor of 1 — even tiny chunks count as one tick.
        this.chunkTicks = Math.max(1, base.durationMs() / 50);
    }

    /** True if the previous chunk has been consumed and the next chunk should be queued. */
    public boolean shouldQueue() {
        return ticksRemaining <= 0;
    }

    /** Call once per tick. Does not produce a chunk; just advances the buffer-consumed counter. */
    public void onTickConsumed() {
        if (ticksRemaining > 0) ticksRemaining--;
    }

    /** Mark a chunk as queued — adds the chunk's tick duration to the remaining counter. */
    public void onQueued() {
        ticksRemaining += chunkTicks;
    }

    public void setAmplitude(float amp) {
        this.amplitude = Math.max(0f, Math.min(1f, amp));
    }

    public float amplitude() {
        return amplitude;
    }

    public HapticWaveform base() {
        return base;
    }

    public int crossfadeMs() {
        return crossfadeMs;
    }

    public byte[] nextChunk() {
        short[] samples = HapticWaveform.readSamples(base.pcm());
        int crossfadeSamples = (int) (crossfadeMs * base.format().getSampleRate() / 1000.0);
        crossfadeSamples = Math.min(crossfadeSamples, samples.length / 2);

        short[] out = new short[samples.length];
        for (int i = 0; i < samples.length; i++) {
            float gain = amplitude;
            if (i < crossfadeSamples) {
                gain *= (float) i / crossfadeSamples;
            } else if (i >= samples.length - crossfadeSamples) {
                int rel = samples.length - i;
                gain *= (float) rel / crossfadeSamples;
            }
            int v = Math.round(samples[i] * gain);
            out[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, v));
        }

        byte[] bytes = new byte[out.length * 2];
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(out);
        return bytes;
    }
}
