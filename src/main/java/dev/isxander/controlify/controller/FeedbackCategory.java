package dev.isxander.controlify.controller;

import com.mojang.serialization.Codec;
import net.minecraft.util.StringRepresentable;

/**
 * A semantic grouping for player-facing controller feedback. Each category can be toggled
 * independently per-system in the settings UI.
 *
 * <p>Two distinct hardware systems share this categorisation — both are surfaced under
 * Controlify's "Adaptive Triggers" and "HD Haptics" settings groups respectively, and the
 * categories enable the user to (for example) keep COMBAT haptics on but turn ENVIRONMENT
 * haptics off, independently of the trigger-side toggles for the same category:
 *
 * <ul>
 *   <li><b>Adaptive Triggers</b> — the two motors inside L2 / R2 that provide dynamic
 *       resistance and trigger-local vibration. Effects are produced via DS5 effects-state
 *       HID reports, never via audio.</li>
 *   <li><b>HD Haptics</b> — the two voice-coil actuators in the controller grips that render
 *       arbitrary PCM as wide-band tactile feedback. Effects are produced by streaming audio
 *       to the controller's USB-audio-class device, separately from the speaker channels.</li>
 * </ul>
 *
 * <p>This enum lives in the neutral {@code controller} package because it's used by both
 * systems; their hardware is unrelated even though the categorisation is shared.
 */
public enum FeedbackCategory implements StringRepresentable {
    MOVEMENT("movement"),
    COMBAT("combat"),
    AMBIENT("ambient"),
    UI("ui"),
    ITEMS("items"),
    ENVIRONMENT("environment"),
    MISC("misc");

    public static final Codec<FeedbackCategory> CODEC = StringRepresentable.fromEnum(FeedbackCategory::values);

    private final String name;
    FeedbackCategory(String name) { this.name = name; }

    @Override public String getSerializedName() { return name; }
}
