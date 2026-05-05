/*
 * Copyright (C) 2026 isXander
 * This file is part of Controlify.
 *
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.isxander.controlify.mixins.feature.rumble.blockbreak;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import dev.isxander.controlify.api.ControlifyApi;
import dev.isxander.controlify.bindings.ControlifyBindings;
import dev.isxander.controlify.controller.ControllerEntity;
import dev.isxander.controlify.controller.dualsense.AdaptiveTriggerDispatcher;
import dev.isxander.controlify.controller.dualsense.AdaptiveTriggerHelper;
import dev.isxander.controlify.controller.dualsense.ControlifyTriggerReasons;
import dev.isxander.controlify.controller.dualsense.TriggerHandle;
import dev.isxander.controlify.controller.dualsense.TriggerSide;
import dev.isxander.controlify.driver.dualsense.DualsenseTriggerEffect;
import dev.isxander.controlify.rumble.ContinuousRumbleEffect;
import dev.isxander.controlify.rumble.RumbleSource;
import dev.isxander.controlify.rumble.RumbleState;
import dev.isxander.controlify.utils.Easings;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.Packet;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MultiPlayerGameMode.class)
public class MultiPlayerGameModeMixin {
	@Shadow private float destroyProgress;

	@Unique private ContinuousRumbleEffect blockBreakRumble = null;
	@Unique private TriggerHandle blockBreakTrigger = null;
	/** Last destroy stage (0-9) we fired a pulse for; -1 means no break active. */
	@Unique private int lastBreakStage = -1;
	@Unique private TriggerSide breakSide = null;
	/** Per-pulse decay tracking: ticks since the last stage transition's pulse, 0..2. -1 = inactive. */
	@Unique private int pulseDecayTick = -1;
	@Unique private int lastPulseStartAmp = 0;
	@Unique private byte lastPulseFreq = 0;

	@Inject(
			method = "lambda$startDestroyBlock$1",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientLevel;destroyBlockProgress(ILnet/minecraft/core/BlockPos;I)V")
	)
	private void onStartBreakingBlock(
		CallbackInfoReturnable<Packet<?>> cir,
		@Local(argsOnly = true, name = "state") BlockState state
	) {
		startRumble(state);
		startBreakTrigger(state);
	}

	@Inject(
			method = "lambda$startDestroyBlock$1",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/MultiPlayerGameMode;destroyBlock(Lnet/minecraft/core/BlockPos;)Z")
	)
	private void onInstabreakBlockSurvival(
		CallbackInfoReturnable<Packet<?>> cir,
		@Local(argsOnly = true, name = "state") BlockState state
	) {
		startRumble(state);
		// won't stop until 1 tick
		stopRumble();
		fireInstabreakClick(state);
	}

	@Inject(method = "stopDestroyBlock", at = @At("RETURN"))
	private void onStopBreakingBlock(CallbackInfo ci) {
		stopRumble();
		releaseBreakTrigger();
	}

	@Inject(method = "continueDestroyBlock", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/MultiPlayerGameMode;startPrediction(Lnet/minecraft/client/multiplayer/ClientLevel;Lnet/minecraft/client/multiplayer/prediction/PredictiveAction;)V", ordinal = 1))
	private void onFinishBreakingBlock(BlockPos pos, Direction direction, CallbackInfoReturnable<Boolean> cir) {
		stopRumble();
		releaseBreakTrigger();
	}

	@ModifyExpressionValue(method = "continueDestroyBlock", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/state/BlockState;isAir()Z"))
	private boolean onAbortBreakingBlock(boolean original) {
		if (original) {
			stopRumble();
			releaseBreakTrigger();
		}

		return original;
	}

	// Discrete-pulse mining: fire a brief Vibration burst on each destroy-stage transition (0..9)
	// so the trigger feel matches the on-screen crack-texture stages instead of a continuous buzz.
	// Each pulse expires after a few ticks, giving a natural gap between stages.
	@Inject(method = "continueDestroyBlock", at = @At("RETURN"))
	private void controlify$heartbeatBlockBreakTrigger(BlockPos pos, Direction direction, CallbackInfoReturnable<Boolean> cir) {
		Boolean destroyed = cir.getReturnValue();
		if (destroyed != null && destroyed) {
			releaseBreakTrigger();
			return;
		}
		if (breakSide == null) return;

		// Decay the in-flight pulse: each crack starts hard and falls a level the next tick,
		// giving a "hard-then-fall, hard-then-fall" rhythm rather than a flat 3-tick pulse.
		if (pulseDecayTick >= 0 && pulseDecayTick < 2) {
			pulseDecayTick++;
			int decayedAmp = Math.max(1, lastPulseStartAmp - pulseDecayTick * 3); // start, start-3, start-6
			AdaptiveTriggerDispatcher dispatcher = AdaptiveTriggerHelper.dispatcherFor(ControlifyTriggerReasons.BLOCK_BREAK);
			if (dispatcher != null) {
				blockBreakTrigger = dispatcher.setLayer(
						breakSide,
						ControlifyTriggerReasons.BLOCK_BREAK,
						ControlifyTriggerReasons.PRIORITY_ACTION,
						new DualsenseTriggerEffect.Vibration((byte) 0, AdaptiveTriggerHelper.scaleStrength(decayedAmp), lastPulseFreq),
						1
				);
			}
		}

		int currentStage = Math.max(0, Math.min(9, (int) (destroyProgress * 10f)));
		if (currentStage == lastBreakStage) return;
		lastBreakStage = currentStage;
		firePulseForStage(currentStage);
	}

	@Unique
	private void firePulseForStage(int stage) {
		AdaptiveTriggerDispatcher dispatcher = AdaptiveTriggerHelper.dispatcherFor(ControlifyTriggerReasons.BLOCK_BREAK);
		if (dispatcher == null || breakSide == null) return;
		// Each crack starts at max amp for the stage; decays via the per-tick handler above.
		int startAmp = Math.min(8, 5 + (stage * 3) / 9);   // 5 → 8 (always feels "hard" at the start)
		// Tool-type frequency bias: pickaxe high-pitched ringing, axe chunky low, shovel scrapy mid-high.
		// Stage offset within the tool's band keeps the "frequency rises with progress" gestalt.
		byte freq = controlify$pickToolFreq(stage);
		blockBreakTrigger = dispatcher.setLayer(
				breakSide,
				ControlifyTriggerReasons.BLOCK_BREAK,
				ControlifyTriggerReasons.PRIORITY_ACTION,
				new DualsenseTriggerEffect.Vibration((byte) 0, AdaptiveTriggerHelper.scaleStrength(startAmp), freq),
				1
		);
		lastPulseStartAmp = startAmp;
		lastPulseFreq = freq;
		pulseDecayTick = 0;

		// "Almost done" warning at stages 8 / 9 — sharp high-frequency overlay so the player
		// feels the crack closing on a kill. Higher-priority MINING_TOOL_SIGNATURE band; expires
		// alongside the per-stage pulse.
		if (stage >= 8) {
			AdaptiveTriggerDispatcher overlay = AdaptiveTriggerHelper.dispatcherFor(ControlifyTriggerReasons.MINING_TOOL_SIGNATURE);
			if (overlay != null) {
				overlay.setLayer(
						breakSide,
						ControlifyTriggerReasons.MINING_TOOL_SIGNATURE,
						ControlifyTriggerReasons.PRIORITY_COMBAT_BURST,
						new DualsenseTriggerEffect.Vibration((byte) 4, AdaptiveTriggerHelper.scaleStrength(6), (byte) 80),
						2
				);
			}
		}
	}

	@Unique
	private void startRumble(BlockState state) {
		stopRumble();

		var effect = ContinuousRumbleEffect.builder()
				.byTick(tick -> new RumbleState(
						0.02f + (float)Easings.easeInQuad(Math.min(1, state.getBlock().defaultDestroyTime() / 20f)) * 0.25f,
						0.01f
				))
				.minTime(1)
				.build();

		blockBreakRumble = effect;
		ControlifyApi.get().playRumbleEffect(RumbleSource.INTERACTION, effect);
	}

	@Unique private void stopRumble() {
		if (blockBreakRumble != null) {
			blockBreakRumble.stop();
			blockBreakRumble = null;
		}
		// Don't release the trigger here — startRumble flows through here when transitioning blocks
		// and we want startBreakTrigger to detect the still-active layer to insert the discrete gap.
	}

	@Unique
	private void releaseBreakTrigger() {
		if (blockBreakTrigger != null) {
			blockBreakTrigger.release();
			blockBreakTrigger = null;
		}
		breakSide = null;
		lastBreakStage = -1;
		pulseDecayTick = -1;
	}

	@Unique
	private void startBreakTrigger(BlockState state) {
		if (!isEffectiveTool(state)) {
			releaseBreakTrigger();
			// Wrong-tool warning — short low pink-noise burst on the haptic actuator so the user
			// feels the friction of swinging at something they can't actually drop. Fires once
			// per startDestroyBlock so spamming the swing doesn't spam haptics.
			firePinkNoiseWarning();
			return;
		}
		TriggerSide side = AdaptiveTriggerHelper.triggerSideForBinding(ControlifyBindings.ATTACK);
		if (side == null) return;
		breakSide = side;
		lastBreakStage = -1;
		pulseDecayTick = -1;
	}

	@Unique
	private static byte controlify$pickToolFreq(int stage) {
		net.minecraft.client.player.LocalPlayer player = net.minecraft.client.Minecraft.getInstance().player;
		if (player == null) return (byte) (30 + stage * 2);
		net.minecraft.world.item.ItemStack stack = player.getMainHandItem();
		var tool = stack.getItem();
		// Per-tool base frequency + per-stage rise (2 Hz / stage). Picks chosen by feel:
		//   axe     → chunky / chopping (24..42)
		//   shovel  → mid scrape (40..58)
		//   hoe     → soft till (18..36)
		//   shears  → very high snip (75..93)
		//   mace    → very low boom (22..40)
		//   pickaxe → metallic / sharp (60..78). Classes were removed in 1.21+; detect via the
		//             can-mine-stone signal which is what defines a pickaxe semantically.
		//   default → mid generic (30..48) — fist or weapon facing a stone-with-a-pickaxe-only path
		int base;
		if (tool instanceof net.minecraft.world.item.AxeItem) base = 24;
		else if (tool instanceof net.minecraft.world.item.ShovelItem) base = 40;
		else if (tool instanceof net.minecraft.world.item.HoeItem) base = 18;
		else if (tool instanceof net.minecraft.world.item.ShearsItem) base = 75;
		else if (tool instanceof net.minecraft.world.item.MaceItem) base = 22;
		else if (stack.has(net.minecraft.core.component.DataComponents.TOOL)
				&& stack.isCorrectToolForDrops(net.minecraft.world.level.block.Blocks.STONE.defaultBlockState())) base = 60;
		else base = 30;
		return (byte) Math.min(127, base + stage * 2);
	}

	@Unique
	private static void firePinkNoiseWarning() {
		ControllerEntity controller = ControlifyApi.get().getCurrentController().orElse(null);
		if (controller == null) return;
		var hd = controller.hdHaptics().orElse(null);
		if (hd == null) return;
		var settings = hd.settings();
		if (!settings.enabled) return;
		if (!settings.isCategoryEnabled(dev.isxander.controlify.controller.FeedbackCategory.ENVIRONMENT)) return;
		float amp = 0.5f * settings.synthesisIntensity * settings.masterIntensity;
		if (amp <= 0.001f) return;
		hd.playHaptic(dev.isxander.controlify.controller.haptic.HapticWaveform.withASR(
				dev.isxander.controlify.controller.haptic.HapticWaveform.pinkNoise(120, amp), 5, 60, 55));
	}

	@Unique
	private void fireInstabreakClick(BlockState state) {
		if (!isEffectiveTool(state)) return;
		TriggerSide side = AdaptiveTriggerHelper.triggerSideForBinding(ControlifyBindings.ATTACK);
		AdaptiveTriggerDispatcher dispatcher = AdaptiveTriggerHelper.dispatcherFor(ControlifyTriggerReasons.BLOCK_BREAK);
		if (side == null || dispatcher == null) return;

		// Sharp sub-audible Vibration burst for instabreak (creative / wood / dirt).
		dispatcher.setLayer(
				side,
				ControlifyTriggerReasons.BLOCK_BREAK,
				ControlifyTriggerReasons.PRIORITY_ACTION,
				new DualsenseTriggerEffect.Vibration((byte) 0, AdaptiveTriggerHelper.scaleStrength(8), (byte) 35),
				3
		);
	}

	/**
	 * Only fire the trigger effect when the player is using a tool that can actually drop the block.
	 * Mining stone with a fist or wood with a feather feels weak and slow precisely because it is —
	 * surfacing that as trigger feedback is misleading noise.
	 */
	@Unique
	private static boolean isEffectiveTool(BlockState state) {
		if (!state.requiresCorrectToolForDrops()) return true;
		net.minecraft.client.player.LocalPlayer player = net.minecraft.client.Minecraft.getInstance().player;
		return player != null && player.hasCorrectToolForDrops(state);
	}

}
