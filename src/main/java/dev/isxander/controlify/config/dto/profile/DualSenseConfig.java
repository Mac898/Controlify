package dev.isxander.controlify.config.dto.profile;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.isxander.controlify.controller.FeedbackCategory;

import java.util.Map;

public record DualSenseConfig(
        boolean adaptiveTriggersEnabled,
        float triggerStrengthMultiplier,
        Map<FeedbackCategory, Boolean> triggerCategoryToggles,
        boolean playerIndicatorsEnabled
) {
    public static final Codec<DualSenseConfig> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.BOOL.optionalFieldOf("adaptive_triggers_enabled", true).forGetter(DualSenseConfig::adaptiveTriggersEnabled),
            Codec.FLOAT.optionalFieldOf("trigger_strength_multiplier", 1.0f).forGetter(DualSenseConfig::triggerStrengthMultiplier),
            Codec.unboundedMap(FeedbackCategory.CODEC, Codec.BOOL).optionalFieldOf("trigger_categories", Map.of()).forGetter(DualSenseConfig::triggerCategoryToggles),
            Codec.BOOL.optionalFieldOf("player_indicators_enabled", true).forGetter(DualSenseConfig::playerIndicatorsEnabled)
    ).apply(instance, DualSenseConfig::new));
}
