package dev.isxander.controlify.controller.haptic;

import javax.sound.sampled.AudioFormat;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Synthesized PCM waveform for the DualSense haptic actuator. 16-bit signed mono at 48000 Hz LE
 * unless otherwise specified — that matches the DualSense audio output spec.
 */
public record HapticWaveform(byte[] pcm, AudioFormat format) {
    private static final float SAMPLE_RATE = 48000f;
    private static final int CACHE_CAPACITY = 256;

    private static final Map<String, byte[]> CACHE = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
                    return size() > CACHE_CAPACITY;
                }
            }
    );

    public int durationMs() {
        return (int) Math.round(pcm.length / 2.0 / format.getSampleRate() * 1000.0);
    }

    public static AudioFormat defaultFormat() {
        return new AudioFormat(SAMPLE_RATE, 16, 1, true, false);
    }

    public static HapticWaveform sine(float freqHz, int durationMs, float amplitude) {
        String key = "sine|" + freqHz + "|" + durationMs + "|" + amplitude;
        AudioFormat fmt = defaultFormat();
        byte[] cached = CACHE.get(key);
        if (cached != null) return new HapticWaveform(cached, fmt);

        float amp = clampAmp(amplitude);
        int n = samplesForMs(durationMs, fmt);
        short[] samples = new short[n];
        double phaseStep = 2.0 * Math.PI * freqHz / SAMPLE_RATE;
        for (int i = 0; i < n; i++) {
            samples[i] = (short) Math.round(Short.MAX_VALUE * amp * Math.sin(phaseStep * i));
        }
        byte[] data = writeSamples(samples);
        CACHE.put(key, data);
        return new HapticWaveform(data, fmt);
    }

    public static HapticWaveform square(float freqHz, int durationMs, float amplitude) {
        String key = "square|" + freqHz + "|" + durationMs + "|" + amplitude;
        AudioFormat fmt = defaultFormat();
        byte[] cached = CACHE.get(key);
        if (cached != null) return new HapticWaveform(cached, fmt);

        float amp = clampAmp(amplitude);
        int n = samplesForMs(durationMs, fmt);
        short[] samples = new short[n];
        double samplesPerCycle = SAMPLE_RATE / Math.max(freqHz, 0.0001);
        short hi = (short) Math.round(Short.MAX_VALUE * amp);
        short lo = (short) -hi;
        for (int i = 0; i < n; i++) {
            double pos = (i % samplesPerCycle) / samplesPerCycle;
            samples[i] = pos < 0.5 ? hi : lo;
        }
        byte[] data = writeSamples(samples);
        CACHE.put(key, data);
        return new HapticWaveform(data, fmt);
    }

    public static HapticWaveform saw(float freqHz, int durationMs, float amplitude) {
        String key = "saw|" + freqHz + "|" + durationMs + "|" + amplitude;
        AudioFormat fmt = defaultFormat();
        byte[] cached = CACHE.get(key);
        if (cached != null) return new HapticWaveform(cached, fmt);

        float amp = clampAmp(amplitude);
        int n = samplesForMs(durationMs, fmt);
        short[] samples = new short[n];
        double samplesPerCycle = SAMPLE_RATE / Math.max(freqHz, 0.0001);
        for (int i = 0; i < n; i++) {
            double pos = (i % samplesPerCycle) / samplesPerCycle;
            samples[i] = (short) Math.round(Short.MAX_VALUE * amp * (2.0 * pos - 1.0));
        }
        byte[] data = writeSamples(samples);
        CACHE.put(key, data);
        return new HapticWaveform(data, fmt);
    }

    /** Paul Kellet's filter — a simple, well-known approximation of pink noise. Not cached: rolls fresh each call. */
    public static HapticWaveform pinkNoise(int durationMs, float amplitude) {
        AudioFormat fmt = defaultFormat();
        float amp = clampAmp(amplitude);
        int n = samplesForMs(durationMs, fmt);
        short[] samples = new short[n];
        double b0 = 0, b1 = 0, b2 = 0, b3 = 0, b4 = 0, b5 = 0, b6 = 0;
        var rng = ThreadLocalRandom.current();
        for (int i = 0; i < n; i++) {
            double white = rng.nextDouble() * 2.0 - 1.0;
            b0 = 0.99886 * b0 + white * 0.0555179;
            b1 = 0.99332 * b1 + white * 0.0750759;
            b2 = 0.96900 * b2 + white * 0.1538520;
            b3 = 0.86650 * b3 + white * 0.3104856;
            b4 = 0.55000 * b4 + white * 0.5329522;
            b5 = -0.7616 * b5 - white * 0.0168980;
            double pink = (b0 + b1 + b2 + b3 + b4 + b5 + b6 + white * 0.5362) * 0.11;
            b6 = white * 0.115926;
            samples[i] = (short) Math.round(Short.MAX_VALUE * amp * Math.max(-1.0, Math.min(1.0, pink)));
        }
        return new HapticWaveform(writeSamples(samples), fmt);
    }

    /** Brown noise via leaky integration of white noise. Not cached: rolls fresh each call. */
    public static HapticWaveform brownNoise(int durationMs, float amplitude) {
        AudioFormat fmt = defaultFormat();
        float amp = clampAmp(amplitude);
        int n = samplesForMs(durationMs, fmt);
        short[] samples = new short[n];
        double sum = 0;
        var rng = ThreadLocalRandom.current();
        for (int i = 0; i < n; i++) {
            sum = sum * 0.99 + (rng.nextDouble() * 2.0 - 1.0) * 0.1;
            double clamped = Math.max(-1.0, Math.min(1.0, sum));
            samples[i] = (short) Math.round(Short.MAX_VALUE * amp * clamped);
        }
        return new HapticWaveform(writeSamples(samples), fmt);
    }

    public static HapticWaveform am(HapticWaveform carrier, HapticWaveform modulator) {
        if (!formatsCompatible(carrier.format, modulator.format)) {
            throw new IllegalArgumentException("AM carrier and modulator must share format");
        }
        short[] c = readSamples(carrier.pcm);
        short[] m = readSamples(modulator.pcm);
        int n = Math.min(c.length, m.length);
        short[] out = new short[n];
        for (int i = 0; i < n; i++) {
            float envelope = ((m[i] / (float) Short.MAX_VALUE) + 1f) * 0.5f;
            out[i] = (short) Math.round(c[i] * envelope);
        }
        return new HapticWaveform(writeSamples(out), carrier.format);
    }

    public static HapticWaveform withASR(HapticWaveform source, int attackMs, int sustainMs, int releaseMs) {
        AudioFormat fmt = source.format;
        short[] in = readSamples(source.pcm);
        int attack = samplesForMs(attackMs, fmt);
        int sustain = samplesForMs(sustainMs, fmt);
        int release = samplesForMs(releaseMs, fmt);
        int target = attack + sustain + release;
        int n = Math.min(in.length, target);
        short[] out = new short[n];

        for (int i = 0; i < n; i++) {
            float gain;
            if (i < attack) {
                gain = attack == 0 ? 1f : (float) i / attack;
            } else if (i < attack + sustain) {
                gain = 1f;
            } else {
                int relIdx = i - attack - sustain;
                gain = release == 0 ? 0f : 1f - ((float) relIdx / release);
            }
            out[i] = (short) Math.round(in[i] * gain);
        }
        return new HapticWaveform(writeSamples(out), fmt);
    }

    /**
     * Sample-by-sample additive mix of multiple waveforms — for parallel composition
     * (e.g. material strike + gait thump played at the same time, not back-to-back).
     * Output length = max input length. Shorter inputs are zero-padded. Samples are
     * summed and clipped to int16 range.
     */
    public static HapticWaveform mix(HapticWaveform... parts) {
        if (parts.length == 0) return new HapticWaveform(new byte[0], defaultFormat());
        AudioFormat fmt = parts[0].format;
        int maxN = 0;
        short[][] all = new short[parts.length][];
        for (int i = 0; i < parts.length; i++) {
            if (!formatsCompatible(fmt, parts[i].format)) {
                throw new IllegalArgumentException("mix parts must share format");
            }
            all[i] = readSamples(parts[i].pcm);
            if (all[i].length > maxN) maxN = all[i].length;
        }
        short[] out = new short[maxN];
        for (int i = 0; i < maxN; i++) {
            int sum = 0;
            for (short[] s : all) {
                if (i < s.length) sum += s[i];
            }
            out[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, sum));
        }
        return new HapticWaveform(writeSamples(out), fmt);
    }

    /**
     * Scale signed-int16 PCM bytes by {@code gain} in place and return a new array. Used by the
     * dispatcher to apply linear-falloff distance attenuation to both synth waveforms and OGG-
     * derived passthrough buffers. Out-of-range gains are clamped at 0..1 (no amplification —
     * the actuator's headroom is the source amplitude). Bytes outside int16-LE format pass through
     * unchanged so non-standard buffers don't get garbled.
     */
    public static byte[] scaleSamples(byte[] pcm, float gain) {
        if (gain >= 0.999f) return pcm;
        if (gain <= 0f) return new byte[pcm.length]; // silence (zero-PCM)
        if ((pcm.length & 1) != 0) return pcm;       // odd length: not int16, leave alone
        byte[] out = new byte[pcm.length];
        ByteBuffer in = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer outBuf = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < pcm.length; i += 2) {
            int sample = in.getShort(i);
            int scaled = Math.round(sample * gain);
            if (scaled > Short.MAX_VALUE) scaled = Short.MAX_VALUE;
            else if (scaled < Short.MIN_VALUE) scaled = Short.MIN_VALUE;
            outBuf.putShort(i, (short) scaled);
        }
        return out;
    }

    public static HapticWaveform concat(HapticWaveform... parts) {
        if (parts.length == 0) return new HapticWaveform(new byte[0], defaultFormat());
        AudioFormat fmt = parts[0].format;
        int total = 0;
        for (HapticWaveform p : parts) {
            if (!formatsCompatible(fmt, p.format)) {
                throw new IllegalArgumentException("concat parts must share format");
            }
            total += p.pcm.length;
        }
        byte[] out = new byte[total];
        int offset = 0;
        for (HapticWaveform p : parts) {
            System.arraycopy(p.pcm, 0, out, offset, p.pcm.length);
            offset += p.pcm.length;
        }
        return new HapticWaveform(out, fmt);
    }

    private static float clampAmp(float a) {
        return Math.max(0f, Math.min(1f, a));
    }

    private static int samplesForMs(int ms, AudioFormat fmt) {
        return Math.max(0, (int) (ms * fmt.getSampleRate() / 1000.0));
    }

    private static byte[] writeSamples(short[] samples) {
        byte[] out = new byte[samples.length * 2];
        ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(samples);
        return out;
    }

    static short[] readSamples(byte[] pcm) {
        ShortBuffer buf = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
        short[] out = new short[buf.remaining()];
        buf.get(out);
        return out;
    }

    private static boolean formatsCompatible(AudioFormat a, AudioFormat b) {
        return a.getSampleRate() == b.getSampleRate()
                && a.getChannels() == b.getChannels()
                && a.getSampleSizeInBits() == b.getSampleSizeInBits()
                && a.isBigEndian() == b.isBigEndian()
                && a.getEncoding().equals(b.getEncoding());
    }
}
