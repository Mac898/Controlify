/*
 * Copyright (C) 2026 isXander
 * This file is part of Controlify.
 *
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.isxander.controlify.mixins.feature.hdhaptics.damage;

import dev.isxander.controlify.api.ControlifyApi;
import dev.isxander.controlify.config.settings.profile.HDHapticSettings;
import dev.isxander.controlify.controller.ControllerEntity;
import dev.isxander.controlify.controller.FeedbackCategory;
import dev.isxander.controlify.controller.haptic.HDHapticComponent;
import dev.isxander.controlify.controller.haptic.HapticWaveform;
import net.minecraft.client.Minecraft;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.LivingEntity;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Source-aware damage haptic overlay for the local player. Fires alongside vanilla's PLAYER_HURT
 * sound passthrough so common damage types feel distinct from one another (fall thud, fire crackle,
 * drowning chest pulse, wither AM drone, etc.).
 */
@Mixin(LivingEntity.class)
public class LivingEntityMixin {
	@Inject(method = "handleDamageEvent", at = @At("HEAD"))
	private void controlify$onLocalPlayerDamage(DamageSource source, CallbackInfo ci) {
		if ((Object) this != Minecraft.getInstance().player) return;

		HDHapticComponent component = ControlifyApi.get().getCurrentController()
				.flatMap(ControllerEntity::hdHaptics)
				.orElse(null);
		if (component == null) return;

		HDHapticSettings settings = component.settings();
		if (!settings.enabled) return;
		if (!settings.isCategoryEnabled(FeedbackCategory.COMBAT)) return;

		HapticWaveform overlay = buildOverlay(source, settings.synthesisIntensity * settings.masterIntensity);
		if (overlay != null) component.playHaptic(overlay);

		// Adaptive-trigger pulse for arrow / projectile hits — felt on both triggers since the
		// user wasn't necessarily holding either when shot.
		if (source.is(DamageTypes.ARROW) || source.is(DamageTypes.MOB_PROJECTILE)
				|| source.is(DamageTypes.TRIDENT) || source.is(DamageTypes.WIND_CHARGE)) {
			var atDispatcher = dev.isxander.controlify.controller.dualsense.AdaptiveTriggerHelper.dispatcherFor(
					dev.isxander.controlify.controller.dualsense.ControlifyTriggerReasons.ARROW_HIT);
			if (atDispatcher != null) {
				var effect = new dev.isxander.controlify.driver.dualsense.DualsenseTriggerEffect.Vibration(
						(byte) 0,
						dev.isxander.controlify.controller.dualsense.AdaptiveTriggerHelper.scaleStrength(7),
						(byte) 50);
				atDispatcher.setLayer(dev.isxander.controlify.controller.dualsense.TriggerSide.LEFT,
						dev.isxander.controlify.controller.dualsense.ControlifyTriggerReasons.ARROW_HIT,
						dev.isxander.controlify.controller.dualsense.ControlifyTriggerReasons.PRIORITY_COMBAT_BURST,
						effect, 3);
				atDispatcher.setLayer(dev.isxander.controlify.controller.dualsense.TriggerSide.RIGHT,
						dev.isxander.controlify.controller.dualsense.ControlifyTriggerReasons.ARROW_HIT,
						dev.isxander.controlify.controller.dualsense.ControlifyTriggerReasons.PRIORITY_COMBAT_BURST,
						effect, 3);
			}
		}
	}

	private static @Nullable HapticWaveform buildOverlay(DamageSource source, float intensity) {
		if (source.is(DamageTypes.FALL)) {
			// Heavy thud — low-frequency sine with quick attack and long decay.
			return HapticWaveform.withASR(HapticWaveform.sine(30f, 200, 0.9f * intensity), 5, 50, 150);
		}
		if (source.is(DamageTypes.LAVA) || source.is(DamageTypes.HOT_FLOOR)
				|| source.is(DamageTypes.IN_FIRE) || source.is(DamageTypes.ON_FIRE)
				|| source.is(DamageTypes.CAMPFIRE)) {
			// Crackle — short pink-noise burst (the actuator vibrates with hiss-like high-freq content).
			return HapticWaveform.pinkNoise(180, 0.7f * intensity);
		}
		if (source.is(DamageTypes.DROWN)) {
			// Constricting chest pulse — 40Hz carrier × 2Hz mod.
			HapticWaveform carrier = HapticWaveform.sine(40f, 300, 0.7f * intensity);
			HapticWaveform mod = HapticWaveform.sine(2f, 300, 0.5f);
			return HapticWaveform.am(carrier, mod);
		}
		if (source.is(DamageTypes.WITHER) || source.is(DamageTypes.MAGIC)) {
			// Wither AM drone — 50Hz × 4Hz mod for the magical-curse feel.
			HapticWaveform carrier = HapticWaveform.sine(50f, 400, 0.6f * intensity);
			HapticWaveform mod = HapticWaveform.sine(4f, 400, 0.5f);
			return HapticWaveform.am(carrier, mod);
		}
		if (source.is(DamageTypes.LIGHTNING_BOLT)) {
			// Lightning strike — sharp brown-noise burst with steep envelope.
			return HapticWaveform.withASR(HapticWaveform.brownNoise(250, 1.0f * intensity), 2, 30, 220);
		}
		// Generic damage — let the vanilla PLAYER_HURT sound passthrough handle it.
		return null;
	}
}
