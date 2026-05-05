package dev.isxander.controlify.config.settings.profile;

import dev.isxander.controlify.config.dto.profile.DualSenseConfig;
import dev.isxander.controlify.controller.FeedbackCategory;

import java.util.HashMap;
import java.util.Map;

public class DualSenseSettings {
    public boolean adaptiveTriggersEnabled;
    public float triggerStrengthMultiplier;
    public Map<FeedbackCategory, Boolean> triggerCategoryToggles;
    public boolean playerIndicatorsEnabled;

    public DualSenseSettings(
            boolean adaptiveTriggersEnabled,
            float triggerStrengthMultiplier,
            Map<FeedbackCategory, Boolean> triggerCategoryToggles,
            boolean playerIndicatorsEnabled
    ) {
        this.adaptiveTriggersEnabled = adaptiveTriggersEnabled;
        this.triggerStrengthMultiplier = triggerStrengthMultiplier;
        this.triggerCategoryToggles = new HashMap<>(triggerCategoryToggles);
        this.playerIndicatorsEnabled = playerIndicatorsEnabled;
    }

    public static DualSenseSettings fromDTO(DualSenseConfig dto) {
        return new DualSenseSettings(
                dto.adaptiveTriggersEnabled(),
                dto.triggerStrengthMultiplier(),
                dto.triggerCategoryToggles(),
                dto.playerIndicatorsEnabled()
        );
    }

    public DualSenseConfig toDTO() {
        return new DualSenseConfig(
                adaptiveTriggersEnabled,
                triggerStrengthMultiplier,
                Map.copyOf(triggerCategoryToggles),
                playerIndicatorsEnabled
        );
    }

    public static DualSenseSettings createDefault() {
        return new DualSenseSettings(true, 1.0f, Map.of(), true);
    }

    public boolean isCategoryEnabled(FeedbackCategory category) {
        return triggerCategoryToggles.getOrDefault(category, true);
    }
}
