/*
 * Copyright (C) 2026 isXander
 * This file is part of Controlify.
 *
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.isxander.controlify.config.settings.profile;

import dev.isxander.controlify.config.dto.profile.DualsenseConfig;
import dev.isxander.controlify.controller.FeedbackCategory;

import java.util.HashMap;
import java.util.Map;

public class DualsenseSettings {
	public boolean triggerEffects;
	public float triggerStrengthMultiplier;
	public Map<FeedbackCategory, Boolean> triggerCategoryToggles;
	public boolean playerIndicatorsEnabled;

	public DualsenseSettings(
			boolean triggerEffects,
			float triggerStrengthMultiplier,
			Map<FeedbackCategory, Boolean> triggerCategoryToggles,
			boolean playerIndicatorsEnabled
	) {
		this.triggerEffects = triggerEffects;
		this.triggerStrengthMultiplier = triggerStrengthMultiplier;
		this.triggerCategoryToggles = new HashMap<>(triggerCategoryToggles);
		this.playerIndicatorsEnabled = playerIndicatorsEnabled;
	}

	public static DualsenseSettings fromDTO(DualsenseConfig dto) {
		return new DualsenseSettings(
				dto.triggerEffects(),
				dto.triggerStrengthMultiplier(),
				dto.triggerCategoryToggles(),
				dto.playerIndicatorsEnabled()
		);
	}

	public DualsenseConfig toDTO() {
		return new DualsenseConfig(
				triggerEffects,
				triggerStrengthMultiplier,
				Map.copyOf(triggerCategoryToggles),
				playerIndicatorsEnabled
		);
	}

	public boolean isCategoryEnabled(FeedbackCategory category) {
		return triggerCategoryToggles.getOrDefault(category, true);
	}
}
