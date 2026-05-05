/*
 * Copyright (C) 2026 isXander
 * This file is part of Controlify.
 *
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.isxander.controlify.mixins.feature.rumble.explosion;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.isxander.controlify.api.ControlifyApi;
import dev.isxander.controlify.config.settings.profile.HDHapticSettings;
import dev.isxander.controlify.controller.ControllerEntity;
import dev.isxander.controlify.controller.FeedbackCategory;
import dev.isxander.controlify.controller.haptic.HDHapticComponent;
import dev.isxander.controlify.controller.haptic.HapticWaveform;
import dev.isxander.controlify.rumble.BasicRumbleEffect;
import dev.isxander.controlify.rumble.RumbleSource;
import dev.isxander.controlify.rumble.RumbleState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.LightningBolt;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(LightningBolt.class)
public class LightningBoltMixin {
	@ModifyExpressionValue(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;isClientSide()Z"))
	private boolean onLightningStrike(boolean client) {
		if (client) {
			ControlifyApi.get().playRumbleEffect(
					RumbleSource.WORLD,
					BasicRumbleEffect.seq(
							BasicRumbleEffect.constant(1f, 0.2f, 6), // initial boom
							BasicRumbleEffect.byTime(t -> new RumbleState(0f, 1 - t*0.2f), 10) // explosion
					)
			);

			// Nearby-strike high-energy haptic — fires once per strike entity, layered on top of
			// the LIGHTNING_BOLT_THUNDER passthrough at distance. Within 16 blocks it's a sharp
			// brown-noise crack + sub-bass rumble; tapers linearly to silent at 16.
			controlify$nearbyStrikeHaptic((LightningBolt) (Object) this);
		}
		return client;
	}

	private static void controlify$nearbyStrikeHaptic(LightningBolt bolt) {
		LocalPlayer player = Minecraft.getInstance().player;
		if (player == null) return;
		double dx = bolt.getX() - player.getX();
		double dy = bolt.getY() - player.getY();
		double dz = bolt.getZ() - player.getZ();
		double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
		if (dist > 16.0) return;

		HDHapticComponent hd = ControlifyApi.get().getCurrentController()
				.flatMap(ControllerEntity::hdHaptics)
				.orElse(null);
		if (hd == null) return;
		HDHapticSettings settings = hd.settings();
		if (!settings.enabled) return;
		if (!settings.isCategoryEnabled(FeedbackCategory.AMBIENT)) return;

		float proximity = (float) Math.max(0.0, 1.0 - dist / 16.0);
		float intensity = settings.synthesisIntensity * settings.masterIntensity * proximity;
		if (intensity <= 0.001f) return;

		// Sharp crack first, then a low rumble tail. The crack reads as the discharge,
		// the tail as the thunder pressure-wave hitting the ground.
		HapticWaveform crack = HapticWaveform.withASR(
				HapticWaveform.brownNoise(150, 1.0f * intensity), 1, 60, 89);
		HapticWaveform rumble = HapticWaveform.withASR(
				HapticWaveform.sine(35f, 600, 0.85f * intensity), 30, 250, 320);
		hd.playHaptic(HapticWaveform.concat(crack, rumble));
	}
}
