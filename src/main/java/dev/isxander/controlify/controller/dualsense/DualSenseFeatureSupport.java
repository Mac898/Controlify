package dev.isxander.controlify.controller.dualsense;

/**
 * Per-controller capability flags computed at connection. Feature code calls
 * {@code component.support().hapticAudio()} etc. to graceful-degrade.
 * Critical for macOS, where audio device detection is unreliable and gated in {@code SDLCommonDriver}.
 */
public record DualSenseFeatureSupport(
        boolean triggerEffects,
        boolean hapticAudio,
        boolean playerIndicators,
        boolean speakerOutput,
        boolean isBluetooth
) {
    public static DualSenseFeatureSupport unsupported() {
        return new DualSenseFeatureSupport(false, false, false, false, false);
    }
}
