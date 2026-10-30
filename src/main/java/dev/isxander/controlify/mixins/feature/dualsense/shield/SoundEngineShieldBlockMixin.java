/*
 * Copyright (C) 2026 isXander
 * This file is part of Controlify.
 *
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.isxander.controlify.mixins.feature.dualsense.shield;

import dev.isxander.controlify.bindings.ControlifyBindings;
import dev.isxander.controlify.controller.dualsense.AdaptiveTriggerDispatcher;
import dev.isxander.controlify.controller.dualsense.AdaptiveTriggerHelper;
import dev.isxander.controlify.controller.dualsense.ControlifyTriggerReasons;
import dev.isxander.controlify.controller.dualsense.TriggerSide;
import dev.isxander.controlify.driver.dualsense.DualsenseTriggerEffect;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Fires the L2 "block-hit" Vibration pulse when the {@code item.shield.block} sound plays
 * while the local player is actively blocking — the most reliable client-side signal
 * for a shield-blocked attack since vanilla doesn't dispatch a damage-event for blocked hits.
 */
@Mixin(SoundEngine.class)
public class SoundEngineShieldBlockMixin {
	@Inject(method = "play(Lnet/minecraft/client/resources/sounds/SoundInstance;)Lnet/minecraft/client/sounds/SoundEngine$PlayResult;", at = @At("HEAD"))
	private void controlify$onShieldBlockSound(SoundInstance instance, CallbackInfoReturnable<SoundEngine.PlayResult> cir) {
		Identifier id = instance.getIdentifier();
		if (!"minecraft".equals(id.getNamespace()) || !"item.shield.block".equals(id.getPath())) return;

		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || !mc.player.isBlocking()) return;

		TriggerSide side = AdaptiveTriggerHelper.triggerSideForBinding(ControlifyBindings.USE);
		AdaptiveTriggerDispatcher dispatcher = AdaptiveTriggerHelper.dispatcherFor(ControlifyTriggerReasons.SHIELD_PULSE);
		if (side == null || dispatcher == null) return;

		dispatcher.setLayer(
				side,
				ControlifyTriggerReasons.SHIELD_PULSE,
				ControlifyTriggerReasons.PRIORITY_COMBAT_BURST,
				new DualsenseTriggerEffect.Vibration((byte) 6, AdaptiveTriggerHelper.scaleStrength(8), (byte) 100),
				2
		);
	}
}
