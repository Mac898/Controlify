/*
 * Copyright (C) 2026 isXander
 * This file is part of Controlify.
 *
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.isxander.controlify.controller.haptic;

import dev.isxander.controlify.controller.FeedbackCategory;

/**
 * Maps a sound to a haptic playback recipe.
 *
 * @param intensity            Base gain applied to the haptic (0..1+ allowed; clamped on the actuator).
 * @param maxDistance          Spatial attenuation distance in blocks. 0 disables proximity attenuation.
 * @param requiresLocalSource  When true, the sound only fires haptics if its source position is the local player.
 * @param category             Category gate — toggleable in settings.
 */
public record HapticProfile(
		float intensity,
		float maxDistance,
		boolean requiresLocalSource,
		FeedbackCategory category
) {
	public static HapticProfile of(FeedbackCategory category) {
		return new HapticProfile(1.0f, 0f, false, category);
	}

	public static HapticProfile localOnly(FeedbackCategory category, float intensity) {
		return new HapticProfile(intensity, 0f, true, category);
	}

	public static HapticProfile spatial(FeedbackCategory category, float intensity, float maxDistance) {
		return new HapticProfile(intensity, maxDistance, false, category);
	}

	public HapticProfile withIntensity(float newIntensity) {
		return new HapticProfile(newIntensity, this.maxDistance, this.requiresLocalSource, this.category);
	}
}
