package dev.isxander.controlify.controller.haptic;

import dev.isxander.controlify.controller.FeedbackCategory;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Global registry mapping a sound's location to a {@link HapticProfile}.
 * Mappings are opt-in — sounds without an entry produce no haptics. The registry is shared across
 * all {@link HDHapticDispatcher} instances; this matches Minecraft's convention that any sound a
 * given mod registers is the same sound regardless of which controller is active.
 *
 * <p>Two lookup tiers: exact-Identifier matches (highest priority), then ordered rules
 * (predicates over Identifier). Rules let one entry cover all per-material variants of a sound
 * (e.g. every {@code .step} sound) without enumerating each one.
 */
public final class HapticSoundRegistry {
    private static final Map<Identifier, HapticProfile> EXACT = new HashMap<>();
    private static final List<RuleEntry> RULES = new ArrayList<>();
    private static final Map<Identifier, OverlayEntry> OVERLAYS = new HashMap<>();

    private record RuleEntry(Predicate<Identifier> matcher, HapticProfile profile) {}

    /**
     * A synthesized overlay fired alongside the passthrough — e.g. an arpeggio on top of
     * UI_TOAST_CHALLENGE_COMPLETE, a synth click on ITEM_PICKUP, a sub-bass under ENDER_DRAGON_GROWL.
     * The supplier is called each dispatch so the waveform may include random / time-varying content.
     *
     * @param requiresLocalSource when true, the overlay is suppressed unless the sound instance
     *                            originates within ~0.5 blocks of the local player. Used by
     *                            self-only events like swim strokes / splashes so other players'
     *                            sounds don't trigger our actuator.
     * @param maxDistance         when {@code > 0}, the overlay is gated by linear-falloff distance
     *                            attenuation: amplitude scales {@code (1 − dist/maxDistance)} and
     *                            is silent at or beyond max. {@code 0} disables proximity gating
     *                            (UI / world-fixed events that vanilla only plays for the local
     *                            listener anyway, like {@code PLAYER_LEVELUP}).
     */
    public record OverlayEntry(Supplier<HapticWaveform> waveform, FeedbackCategory category,
                               boolean requiresLocalSource, float maxDistance) {}

    private HapticSoundRegistry() {}

    public static void register(Identifier soundLocation, HapticProfile profile) {
        EXACT.put(soundLocation, profile);
    }

    public static void register(SoundEvent event, HapticProfile profile) {
        register(event.location(), profile);
    }

    /**
     * Register a rule that matches any sound whose path ends with the given suffix
     * (within the {@code minecraft} namespace by default). For example, {@code ".step"}
     * matches every {@code block.<material>.step} sound.
     */
    public static void registerSuffix(String pathSuffix, HapticProfile profile) {
        RULES.add(new RuleEntry(
                id -> "minecraft".equals(id.getNamespace()) && id.getPath().endsWith(pathSuffix),
                profile
        ));
    }

    public static void registerRule(Predicate<Identifier> matcher, HapticProfile profile) {
        RULES.add(new RuleEntry(matcher, profile));
    }

    /** Register a synthesized overlay attached to a specific sound id. No spatial gate. */
    public static void registerOverlay(Identifier soundLocation, FeedbackCategory category, Supplier<HapticWaveform> overlay) {
        OVERLAYS.put(soundLocation, new OverlayEntry(overlay, category, false, 0f));
    }

    public static void registerOverlay(SoundEvent event, FeedbackCategory category, Supplier<HapticWaveform> overlay) {
        registerOverlay(event.location(), category, overlay);
    }

    /** Register a synthesized overlay that only fires when the sound originates at the local player. */
    public static void registerLocalOverlay(SoundEvent event, FeedbackCategory category, Supplier<HapticWaveform> overlay) {
        OVERLAYS.put(event.location(), new OverlayEntry(overlay, category, true, 0f));
    }

    /** Register a spatially-gated synthesized overlay — fires with linear-falloff attenuation
     *  scaled by source distance to the local player, silent beyond {@code maxDistance}. Use this
     *  for overlays attached to events that originate at non-local positions in MP (note blocks,
     *  bells, item pickups, the dragon's growl, etc.). */
    public static void registerSpatialOverlay(SoundEvent event, FeedbackCategory category, float maxDistance,
                                              Supplier<HapticWaveform> overlay) {
        OVERLAYS.put(event.location(), new OverlayEntry(overlay, category, false, maxDistance));
    }

    public static @Nullable OverlayEntry getOverlay(Identifier soundLocation) {
        return OVERLAYS.get(soundLocation);
    }

    public static @Nullable HapticProfile get(Identifier soundLocation) {
        HapticProfile exact = EXACT.get(soundLocation);
        if (exact != null) return exact;
        for (RuleEntry rule : RULES) {
            if (rule.matcher.test(soundLocation)) return rule.profile;
        }
        return null;
    }

    public static Map<Identifier, HapticProfile> view() {
        return Collections.unmodifiableMap(EXACT);
    }
}
