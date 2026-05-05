/*
 * Copyright (C) 2026 isXander
 * This file is part of Controlify.
 *
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.isxander.controlify.config.dto.profile;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.isxander.controlify.controller.FeedbackCategory;

import java.util.Map;

public record DualsenseConfig(
	boolean triggerEffects,
	float triggerStrengthMultiplier,
	Map<FeedbackCategory, Boolean> triggerCategoryToggles,
	boolean playerIndicatorsEnabled
) {
	public static final Codec<DualsenseConfig> CODEC = RecordCodecBuilder.create(instance -> instance.group(
		Codec.BOOL.fieldOf("trigger_effects").forGetter(DualsenseConfig::triggerEffects),
		Codec.FLOAT.optionalFieldOf("trigger_strength_multiplier", 1.0f).forGetter(DualsenseConfig::triggerStrengthMultiplier),
		Codec.unboundedMap(FeedbackCategory.CODEC, Codec.BOOL).optionalFieldOf("trigger_categories", Map.of()).forGetter(DualsenseConfig::triggerCategoryToggles),
		Codec.BOOL.optionalFieldOf("player_indicators_enabled", true).forGetter(DualsenseConfig::playerIndicatorsEnabled)
	).apply(instance, DualsenseConfig::new));
}
