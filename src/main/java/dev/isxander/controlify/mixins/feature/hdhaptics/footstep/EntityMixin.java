/*
 * Copyright (C) 2026 isXander
 * This file is part of Controlify.
 *
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.isxander.controlify.mixins.feature.hdhaptics.footstep;

import dev.isxander.controlify.api.ControlifyApi;
import dev.isxander.controlify.config.settings.profile.HDHapticSettings;
import dev.isxander.controlify.controller.ControllerEntity;
import dev.isxander.controlify.controller.FeedbackCategory;
import dev.isxander.controlify.controller.haptic.HDHapticComponent;
import dev.isxander.controlify.controller.haptic.HapticWaveform;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Per-step HD haptic for the local player. Each footstep produces a single composite waveform =
 * a {@code ground} component (material-specific timbre) mixed with a {@code gait} component
 * (walk light tap or sprint thump). Material differentiation comes from the synthesised body —
 * the OGG passthrough was unreliable on this firmware so we render the surface ourselves at a
 * known frequency and noise color.
 *
 * <p>Walking and sprinting share the same material body but the gait component is much louder
 * (and slightly lower-frequency) for sprint. Walking gait is intentionally subtle — barely
 * felt unless layered with the body.
 */
@Mixin(Entity.class)
public class EntityMixin {
	@Inject(method = "playStepSound", at = @At("HEAD"))
	private void controlify$onPlayStepSoundHaptic(BlockPos pos, BlockState state, CallbackInfo ci) {
		Entity self = (Entity) (Object) this;
		if (self != Minecraft.getInstance().player) return;
		if (!(self instanceof LivingEntity le)) return;
		if (le.getVehicle() != null) return; // riding — skip step haptics entirely

		HDHapticComponent component = ControlifyApi.get().getCurrentController()
				.flatMap(ControllerEntity::hdHaptics)
				.orElse(null);
		if (component == null) return;
		HDHapticSettings settings = component.settings();
		if (!settings.enabled) return;
		if (!settings.isCategoryEnabled(FeedbackCategory.MOVEMENT)) return;

		float intensity = settings.synthesisIntensity * settings.masterIntensity;
		if (intensity <= 0.001f) return;

		boolean sprinting = le.isSprinting();
		HapticWaveform body = controlify$buildGroundBody(state, intensity);
		HapticWaveform gait = controlify$buildGaitComponent(sprinting, intensity);
		component.playHaptic(HapticWaveform.mix(body, gait));
	}

	/** Build a per-material "ground feel" waveform — sine body + a short noise transient. */
	private static HapticWaveform controlify$buildGroundBody(BlockState state, float intensity) {
		SoundType st = state.getSoundType();
		// Per-material parameters: (centre frequency Hz, body amp, noise amp, body ms).
		// Amplitudes are pre-perceptual-correction — each row tuned so peaks feel similar across
		// surfaces at default intensity.
		float freq, bodyAmp, noiseAmp;
		int bodyMs = 70;
		if (st == SoundType.STONE || st == SoundType.METAL || st == SoundType.NETHERITE_BLOCK
				|| st == SoundType.ANCIENT_DEBRIS || st == SoundType.AMETHYST || st == SoundType.AMETHYST_CLUSTER
				|| st == SoundType.COPPER || st == SoundType.DEEPSLATE || st == SoundType.DEEPSLATE_BRICKS
				|| st == SoundType.TUFF || st == SoundType.CALCITE) {
			// Hard surfaces — sharp, ringing tap. High freq, brief noise crackle.
			freq = 90; bodyAmp = 0.35f; noiseAmp = 0.30f; bodyMs = 50;
		} else if (st == SoundType.WOOD || st == SoundType.BAMBOO || st == SoundType.BAMBOO_WOOD
				|| st == SoundType.HANGING_SIGN) {
			// Wood — warm, hollow tone. Less noise.
			freq = 55; bodyAmp = 0.40f; noiseAmp = 0.12f; bodyMs = 80;
		} else if (st == SoundType.WOOL) {
			// Wool — muted, low body, no crackle.
			freq = 35; bodyAmp = 0.30f; noiseAmp = 0.05f; bodyMs = 60;
		} else if (st == SoundType.GRAVEL || st == SoundType.SAND) {
			// Granular — strong noise, low body.
			freq = 28; bodyAmp = 0.18f; noiseAmp = 0.45f; bodyMs = 60;
		} else if (st == SoundType.GRASS) {
			// Soft turf — light body, soft noise.
			freq = 32; bodyAmp = 0.22f; noiseAmp = 0.20f; bodyMs = 70;
		} else if (st == SoundType.SNOW || st == SoundType.POWDER_SNOW) {
			// Snow — soft crunch. Low body, mid noise.
			freq = 24; bodyAmp = 0.18f; noiseAmp = 0.30f; bodyMs = 80;
		} else if (st == SoundType.MUD || st == SoundType.MUDDY_MANGROVE_ROOTS) {
			// Mud — deep squelch. Low body strong, wet noise tail.
			freq = 22; bodyAmp = 0.45f; noiseAmp = 0.25f; bodyMs = 100;
		} else if (st == SoundType.HONEY_BLOCK || st == SoundType.SLIME_BLOCK) {
			// Sticky — slow body wave, no high noise.
			freq = 25; bodyAmp = 0.40f; noiseAmp = 0.05f; bodyMs = 110;
		} else if (state.is(BlockTags.ICE)) {
			// Ice — bright sharp body + slip noise.
			freq = 70; bodyAmp = 0.30f; noiseAmp = 0.35f; bodyMs = 60;
		} else {
			// Default mid.
			freq = 40; bodyAmp = 0.30f; noiseAmp = 0.18f; bodyMs = 70;
		}

		HapticWaveform body = HapticWaveform.withASR(
				HapticWaveform.sine(freq, bodyMs, bodyAmp * intensity),
				3, Math.max(1, bodyMs - 20), 17);
		HapticWaveform noise = HapticWaveform.withASR(
				HapticWaveform.pinkNoise(45, noiseAmp * intensity),
				2, 30, 13);
		return HapticWaveform.mix(body, noise);
	}

	/** Walk = subtle, sprint = pronounced. The gait body sits just below the surface body's
	 *  frequency so the two read as related. Walking is intentionally barely-perceptible alone;
	 *  the listener hears the surface body and feels the walk only as a subtle bottom. */
	private static HapticWaveform controlify$buildGaitComponent(boolean sprinting, float intensity) {
		if (sprinting) {
			// Sprint thump — short low pulse. Reduced from previous 0.4 → 0.18 per user feedback.
			return HapticWaveform.withASR(
					HapticWaveform.sine(50f, 65, 0.18f * intensity), 3, 30, 32);
		}
		// Walk: very faint. ~5x quieter than sprint.
		return HapticWaveform.withASR(
				HapticWaveform.sine(45f, 50, 0.05f * intensity), 2, 25, 23);
	}
}
