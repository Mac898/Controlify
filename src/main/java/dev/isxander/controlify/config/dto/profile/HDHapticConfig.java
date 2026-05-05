package dev.isxander.controlify.config.dto.profile;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.isxander.controlify.controller.FeedbackCategory;

import java.util.Map;

public record HDHapticConfig(
        boolean enabled,
        float masterIntensity,
        float synthesisIntensity,
        Map<FeedbackCategory, Boolean> categoryToggles,
        boolean lowPassFilter,
        boolean suppressRumble,
        float falloffNearBlocks,
        float falloffFarBlocks
) {
    public static final Codec<HDHapticConfig> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.BOOL.fieldOf("enabled").forGetter(HDHapticConfig::enabled),
            Codec.FLOAT.optionalFieldOf("master_intensity", 1.0f).forGetter(HDHapticConfig::masterIntensity),
            Codec.FLOAT.optionalFieldOf("synthesis_intensity", 1.0f).forGetter(HDHapticConfig::synthesisIntensity),
            Codec.unboundedMap(FeedbackCategory.CODEC, Codec.BOOL).optionalFieldOf("categories", Map.of()).forGetter(HDHapticConfig::categoryToggles),
            Codec.BOOL.optionalFieldOf("low_pass_filter", true).forGetter(HDHapticConfig::lowPassFilter),
            Codec.BOOL.optionalFieldOf("suppress_rumble", true).forGetter(HDHapticConfig::suppressRumble),
            Codec.FLOAT.optionalFieldOf("falloff_near_blocks", 0.0f).forGetter(HDHapticConfig::falloffNearBlocks),
            Codec.FLOAT.optionalFieldOf("falloff_far_blocks", 64.0f).forGetter(HDHapticConfig::falloffFarBlocks)
    ).apply(instance, HDHapticConfig::new));
}
