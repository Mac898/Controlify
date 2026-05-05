package dev.isxander.controlify.config.settings.profile;

import dev.isxander.controlify.config.dto.profile.HDHapticConfig;
import dev.isxander.controlify.controller.FeedbackCategory;

import java.util.HashMap;
import java.util.Map;

public class HDHapticSettings {
    public boolean enabled;
    public float masterIntensity;
    public float synthesisIntensity;
    public Map<FeedbackCategory, Boolean> categoryToggles;
    public boolean lowPassFilter;
    public boolean suppressRumble;
    /** Within this absolute distance (blocks), spatial haptics play at full amplitude regardless
     *  of feature range. Default 0 = falloff begins immediately at distance 0. */
    public float falloffNearBlocks;
    /** Absolute silence-point distance (blocks). Each feature's registered range is capped at
     *  this value — i.e. a 64-block dragon-roar range with this set to 24 silences the dragon
     *  haptic beyond 24 blocks. Default 64 = wide enough that most features keep their natural
     *  silence point. */
    public float falloffFarBlocks;

    public HDHapticSettings(
            boolean enabled,
            float masterIntensity,
            float synthesisIntensity,
            Map<FeedbackCategory, Boolean> categoryToggles,
            boolean lowPassFilter,
            boolean suppressRumble,
            float falloffNearBlocks,
            float falloffFarBlocks
    ) {
        this.enabled = enabled;
        this.masterIntensity = masterIntensity;
        this.synthesisIntensity = synthesisIntensity;
        this.categoryToggles = new HashMap<>(categoryToggles);
        this.lowPassFilter = lowPassFilter;
        this.suppressRumble = suppressRumble;
        this.falloffNearBlocks = falloffNearBlocks;
        this.falloffFarBlocks = falloffFarBlocks;
    }

    public static HDHapticSettings fromDTO(HDHapticConfig dto) {
        return new HDHapticSettings(
                dto.enabled(),
                dto.masterIntensity(),
                dto.synthesisIntensity(),
                dto.categoryToggles(),
                dto.lowPassFilter(),
                dto.suppressRumble(),
                dto.falloffNearBlocks(),
                dto.falloffFarBlocks()
        );
    }

    public HDHapticConfig toDTO() {
        return new HDHapticConfig(
                enabled,
                masterIntensity,
                synthesisIntensity,
                Map.copyOf(categoryToggles),
                lowPassFilter,
                suppressRumble,
                falloffNearBlocks,
                falloffFarBlocks
        );
    }

    public boolean isCategoryEnabled(FeedbackCategory category) {
        return categoryToggles.getOrDefault(category, true);
    }
}
