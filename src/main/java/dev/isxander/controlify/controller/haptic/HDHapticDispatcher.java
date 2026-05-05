package dev.isxander.controlify.controller.haptic;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Dispatches <b>HD haptic</b> effects on behalf of a {@link HDHapticComponent} — i.e. PCM streams
 * sent to the two voice-coil actuators in the DualSense grips. These actuators render arbitrary
 * audio waveforms as wide-band tactile feedback. This does not control the trigger-motor adaptive
 * triggers (see {@code AdaptiveTriggerDispatcher}); the two systems use entirely separate hardware
 * and entirely separate transports (USB-audio-class endpoint here vs DS5 HID effects state there).
 *
 * <p>Three entry points:
 * <ul>
 *     <li>{@link #dispatchSound(SoundInstance)} — called from the sound-intercept mixin for every sound played; routes through {@link HapticSoundRegistry} for opt-in mappings.</li>
 *     <li>{@link #playOneShot(HapticWaveform)} — fire-and-forget synthesized waveform.</li>
 *     <li>{@link #startLoop(Identifier, HapticLoop)} / {@link #stopLoop(Identifier)} — sustained drones; chunks are re-queued by {@link #tick()} only when the previous chunk has been consumed downstream.</li>
 * </ul>
 * The dispatcher is owned by the component and shares its lifecycle (one per controller).
 */
public final class HDHapticDispatcher {
    /** Per-sound minimum spacing between dispatches; prevents rapid-fire dupes. */
    public static final int COOLDOWN_TICKS = 3;

    private final HDHapticComponent component;
    private final Map<Identifier, Integer> soundCooldowns = new HashMap<>();
    private final Map<Identifier, HapticLoop> activeLoops = new HashMap<>();

    public HDHapticDispatcher(HDHapticComponent component) {
        this.component = component;
    }

    public void registerSoundMapping(Identifier soundLocation, HapticProfile profile) {
        HapticSoundRegistry.register(soundLocation, profile);
    }

    public void registerSoundMapping(SoundEvent event, HapticProfile profile) {
        HapticSoundRegistry.register(event, profile);
    }

    public void dispatchSound(SoundInstance instance) {
        Identifier id = instance.getIdentifier();
        var settings = component.settings();
        if (!settings.enabled) return;
        LocalPlayer localPlayer = Minecraft.getInstance().player;
        float master = settings.masterIntensity;
        // User-configured absolute falloff bounds in blocks. Near-field = full amplitude inside
        // this radius; far-field = global silence-point cap (per-feature range still acts as the
        // hard ceiling, so a noteblock can't be heard from 100 blocks even if userFar is 200).
        float nearBlocks = settings.falloffNearBlocks;
        float farBlocks = settings.falloffFarBlocks;

        // Synth overlay (if registered) — fires alongside the passthrough so e.g. levelup
        // gets a triple-pulse on top of the vanilla sound. Independent cooldown from the
        // passthrough; gated by the overlay's category, optional local-source flag, and
        // (when set) linear-falloff distance attenuation.
        HapticSoundRegistry.OverlayEntry overlay = HapticSoundRegistry.getOverlay(id);
        if (overlay != null && settings.isCategoryEnabled(overlay.category())) {
            boolean localOk = !overlay.requiresLocalSource() || isLocalSource(instance, localPlayer);
            if (localOk) {
                float overlayAtt = computeLinearFalloff(overlay.maxDistance(), nearBlocks, farBlocks, instance, localPlayer);
                float overlayGain = overlayAtt * master;
                if (overlayGain > 0.001f) {
                    HapticWaveform wave = overlay.waveform().get();
                    if (wave != null) component.playHaptic(wave, overlayGain);
                }
            }
        }

        HapticProfile profile = HapticSoundRegistry.get(id);
        if (profile == null) return;

        if (!settings.isCategoryEnabled(profile.category())) return;

        if (soundCooldowns.getOrDefault(id, 0) > 0) return;

        float attenuation = computeLinearFalloff(profile.maxDistance(), nearBlocks, farBlocks, instance, localPlayer);
        if (attenuation <= 0.001f) return;

        if (profile.requiresLocalSource() && !isLocalSource(instance, localPlayer)) return;

        float gain = profile.intensity() * attenuation * master;
        if (gain <= 0.001f) return;

        soundCooldowns.put(id, COOLDOWN_TICKS);
        component.playHaptic(SoundEvent.createVariableRangeEvent(id), gain);
    }

    public void playOneShot(HapticWaveform waveform) {
        if (!component.settings().enabled) return;
        component.playHaptic(waveform);
    }

    public void startLoop(Identifier loopId, HapticLoop loop) {
        activeLoops.put(loopId, loop);
    }

    public void stopLoop(Identifier loopId) {
        activeLoops.remove(loopId);
    }

    public boolean isLoopActive(Identifier loopId) {
        return activeLoops.containsKey(loopId);
    }

    public @Nullable HapticLoop getLoop(Identifier loopId) {
        return activeLoops.get(loopId);
    }

    public void clearAll() {
        soundCooldowns.clear();
        activeLoops.clear();
    }

    public void tick() {
        Iterator<Map.Entry<Identifier, Integer>> it = soundCooldowns.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Identifier, Integer> e = it.next();
            int v = e.getValue() - 1;
            if (v <= 0) {
                it.remove();
            } else {
                e.setValue(v);
            }
        }

        if (!component.settings().enabled) return;
        for (HapticLoop loop : activeLoops.values()) {
            // Re-queue only when the previously-queued chunk has been consumed downstream.
            // Without this, chunks accumulate (every tick we'd queue 200ms of audio while only
            // 50ms was consumed), producing seconds of residual audio after the loop is stopped.
            loop.onTickConsumed();
            if (loop.shouldQueue()) {
                byte[] chunk = loop.nextChunk();
                component.playHaptic(chunk, loop.base().format());
                loop.onQueued();
            }
        }
    }

    /** Linear-falloff attenuation with user-configured absolute near/far block distances.
     *  The per-feature {@code featureRange} acts as a hard ceiling (a sound's silence point can
     *  never exceed its registered range). Within {@code nearBlocks}: full amplitude. Beyond
     *  {@code min(featureRange, farBlocks)}: silent. Linear in between.
     *
     *  <p>Returns 1 when {@code featureRange ≤ 0} (no spatial gate) or the player is null. */
    private static float computeLinearFalloff(float featureRange, float nearBlocks, float farBlocks,
                                              SoundInstance instance, @Nullable LocalPlayer player) {
        if (featureRange <= 0f) return 1f;
        if (player == null) return 1f;

        // Effective silence point: feature range capped by user's far cap. Floor at 0.5 blocks
        // so a misconfigured very-low far doesn't reduce to a step-function.
        float effectiveFar = Math.min(featureRange, Math.max(0.5f, farBlocks));
        // Effective near floor: user's near, capped just below far so the curve has room to fall.
        float effectiveNear = Math.min(Math.max(0f, nearBlocks), Math.max(0f, effectiveFar - 0.5f));

        double dx = instance.getX() - player.getX();
        double dy = instance.getY() - player.getY();
        double dz = instance.getZ() - player.getZ();
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);

        if (dist <= effectiveNear) return 1f;
        if (dist >= effectiveFar) return 0f;
        return (float) (1.0 - (dist - effectiveNear) / (effectiveFar - effectiveNear));
    }

    private static boolean isLocalSource(SoundInstance instance, @Nullable LocalPlayer player) {
        if (player == null) return false;
        double dx = instance.getX() - player.getX();
        double dy = instance.getY() - player.getY();
        double dz = instance.getZ() - player.getZ();
        return dx * dx + dy * dy + dz * dz < 0.25;
    }
}
