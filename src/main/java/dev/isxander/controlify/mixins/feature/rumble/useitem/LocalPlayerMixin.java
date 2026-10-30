/*
 * Copyright (C) 2026 isXander
 * This file is part of Controlify.
 *
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.isxander.controlify.mixins.feature.rumble.useitem;

import dev.isxander.controlify.api.ControlifyApi;
import dev.isxander.controlify.bindings.ControlifyBindings;
import dev.isxander.controlify.controller.dualsense.AdaptiveTriggerDispatcher;
import dev.isxander.controlify.controller.dualsense.AdaptiveTriggerHelper;
import dev.isxander.controlify.controller.dualsense.ControlifyTriggerReasons;
import dev.isxander.controlify.controller.dualsense.TriggerHandle;
import dev.isxander.controlify.controller.dualsense.TriggerSide;
import dev.isxander.controlify.driver.dualsense.DualsenseTriggerEffect;
import dev.isxander.controlify.rumble.*;
import dev.isxander.controlify.rumble.effects.UseItemEffectHolder;
import dev.isxander.controlify.utils.CUtil;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.item.ShieldItem;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LocalPlayer.class)
public abstract class LocalPlayerMixin extends LivingEntityMixin implements UseItemEffectHolder {
	@Unique private ContinuousRumbleEffect useItemRumble;
	@Unique private TriggerHandle useItemTrigger;
	@Unique private ItemUseAnimation useItemAnimation;
	@Unique private boolean crossbowWasLoaded;
	@Unique private static final java.util.Set<ItemUseAnimation> controlify$loggedAnimations = java.util.Collections.synchronizedSet(java.util.EnumSet.noneOf(ItemUseAnimation.class));

	@Override
	protected void onStartUsingItem(InteractionHand hand, CallbackInfo ci, ItemStack stack) {
		ItemUseAnimation anim = stack.getUseAnimation();
		// Diagnostic: log the first time we see each animation type so we can verify the mixin
		// is firing for the expected use-item events.
		if (controlify$loggedAnimations.add(anim)) {
			CUtil.LOGGER.log("[AdaptiveTrigger] onStartUsingItem first time seeing anim={} item={}",
					anim, stack.getItem());
		}
		this.useItemAnimation = anim;
		this.crossbowWasLoaded = false;

		switch (anim) {
			case BOW -> startRumble(ContinuousRumbleEffect.builder()
					.byTick(tick -> new RumbleState(
							tick % 7 <= 3 && tick > BowItem.MAX_DRAW_DURATION ? 0.1f : 0f,
							BowItem.getPowerForTime(tick)
					))
					.build());
			case CROSSBOW -> {
				int chargeDuration = CrossbowItem.getChargeDuration(
						stack,
						(LocalPlayer) (Object) this
				);
				startRumble(ContinuousRumbleEffect.builder()
						.byTick(tick -> new RumbleState(
								0f,
								(float) tick / chargeDuration
						))
						.timeout(chargeDuration)
						.build());
			}
			case BLOCK, SPYGLASS -> startRumble(ContinuousRumbleEffect.builder()
					.byTick(tick -> new RumbleState(
							0f,
							tick % 4 / 4f * 0.12f + 0.05f
					))
					.build());
			case EAT, DRINK -> startRumble(ContinuousRumbleEffect.builder()
					// Chew-cadence pulse: light tap every 4 ticks (~MC's chew sound interval),
					// silent in between. Matches the on-screen bite rhythm.
					.byTick(tick -> tick % 4 == 0
							? new RumbleState(0.04f, 0.12f)
							: new RumbleState(0f, 0f))
					.build());
			case TOOT_HORN -> startRumble(ContinuousRumbleEffect.builder()
					.byTick(tick -> new RumbleState(
							Math.min(1f, tick / 10f),
							0.25f
					))
					.build());
			case SPEAR -> startRumble(ContinuousRumbleEffect.builder()
					.constant(0.3f, 0.3f)
					.build());
		}

		startTrigger(stack, anim);
	}

	@Override
	protected void onUpdateUsingItem(ItemStack stack, CallbackInfo ci) {
		if (useItemTrigger == null || useItemAnimation == null) return;

		LocalPlayer self = (LocalPlayer) (Object) this;
		int useTicks = self.getTicksUsingItem();

		switch (useItemAnimation) {
			case BOW -> {
				float power = BowItem.getPowerForTime(useTicks);
				// Strong resistance from the very start: square-root curve gives a steep early
				// ramp (power 0.25 → strength ~5) so even partial draws feel weighty, peaking
				// at strength 8 across a wide zone. The trigger stays tense at full draw.
				int startPos = Math.max(2, Math.round(7f - 5f * power));
				int strength = Math.min(8, Math.round(4f + 4f * (float) Math.sqrt(power)));
				useItemTrigger.update(new DualsenseTriggerEffect.Bow(
						(byte) startPos, (byte) 8,
						AdaptiveTriggerHelper.scaleStrength(strength),
						AdaptiveTriggerHelper.scaleStrength(8)
				));
			}
			case CROSSBOW -> {
				int chargeDuration = CrossbowItem.getChargeDuration(stack, self);
				boolean isLoaded = useTicks >= chargeDuration;
				if (isLoaded && !crossbowWasLoaded) {
					TriggerSide useSide = AdaptiveTriggerHelper.triggerSideForBinding(ControlifyBindings.USE);
					AdaptiveTriggerDispatcher dispatcher = AdaptiveTriggerHelper.dispatcherFor(ControlifyTriggerReasons.CROSSBOW_LOADED);
					if (useSide != null && dispatcher != null) {
						dispatcher.setLayer(
								useSide,
								ControlifyTriggerReasons.CROSSBOW_LOADED,
								ControlifyTriggerReasons.PRIORITY_COMBAT_BURST,
								new DualsenseTriggerEffect.Weapon((byte) 7, (byte) 8, AdaptiveTriggerHelper.scaleStrength(8)),
								3
						);
					}
					useItemTrigger.update(new DualsenseTriggerEffect.Off());
				} else if (!isLoaded) {
					// Heavy ramp: startStrength climbs 4→7, endStrength saturates at 8 from the
					// start so the release point always feels like a wall. Square-root curve so
					// even early ticks have meaningful tension.
					float charge = chargeDuration <= 0 ? 1f : Math.min(1f, (float) useTicks / chargeDuration);
					float curved = (float) Math.sqrt(charge);
					int startStrength = Math.min(7, 4 + Math.round(3f * curved));
					int endStrength = 8;
					useItemTrigger.update(new DualsenseTriggerEffect.FeedbackSlope(
							(byte) 2, (byte) 8,
							AdaptiveTriggerHelper.scaleStrength(startStrength),
							AdaptiveTriggerHelper.scaleStrength(endStrength)
					));
				}
				crossbowWasLoaded = isLoaded;
			}
			case BLOCK -> useItemTrigger.heartbeat();
			case SPEAR -> {
				// Trident: ramp window covers 20 ticks. Square-root curve so even half-charges
				// feel heavy. Snap force ramps too so release is increasingly punchy.
				float power = Math.min(1f, useTicks / 20f);
				float curved = (float) Math.sqrt(power);
				int strength = Math.min(8, Math.round(4f + 4f * curved));
				int startPos = Math.max(2, Math.round(7f - 5f * power));
				int snapForce = Math.min(8, Math.round(4f + 4f * curved));
				useItemTrigger.update(new DualsenseTriggerEffect.Bow(
						(byte) startPos, (byte) 8,
						AdaptiveTriggerHelper.scaleStrength(strength),
						AdaptiveTriggerHelper.scaleStrength(snapForce)
				));
			}
			case EAT, DRINK -> {
				useItemTrigger.heartbeat();
				// Fire a Vibration pulse on each chew tick so the trigger pulses with the on-screen
				// chew rhythm. Higher priority briefly overrides the FeedbackSlope baseline.
				if (useTicks > 0 && useTicks % 4 == 0) {
					TriggerSide chewSide = AdaptiveTriggerHelper.triggerSideForBinding(ControlifyBindings.USE);
					AdaptiveTriggerDispatcher chewDispatcher = AdaptiveTriggerHelper.dispatcherFor(ControlifyTriggerReasons.EATING_CHEW);
					if (chewSide != null && chewDispatcher != null) {
						chewDispatcher.setLayer(
								chewSide,
								ControlifyTriggerReasons.EATING_CHEW,
								ControlifyTriggerReasons.PRIORITY_COMBAT_BURST,
								new DualsenseTriggerEffect.Vibration((byte) 4, AdaptiveTriggerHelper.scaleStrength(3), (byte) 8),
								2
						);
					}
				}
			}
			default -> useItemTrigger.heartbeat();
		}
	}

	@Override
	protected void onStopUsingItem(CallbackInfo ci) {
		if (useItemRumble != null) {
			useItemRumble.stop();
			useItemRumble = null;
		}

		if (useItemTrigger != null) {
			if (useItemAnimation == ItemUseAnimation.BOW) {
				TriggerSide useSide = AdaptiveTriggerHelper.triggerSideForBinding(ControlifyBindings.USE);
				AdaptiveTriggerDispatcher dispatcher = AdaptiveTriggerHelper.dispatcherFor(ControlifyTriggerReasons.BOW_RELEASE);
				if (useSide != null && dispatcher != null) {
					dispatcher.setLayer(
							useSide,
							ControlifyTriggerReasons.BOW_RELEASE,
							ControlifyTriggerReasons.PRIORITY_COMBAT_BURST,
							new DualsenseTriggerEffect.Vibration((byte) 2, AdaptiveTriggerHelper.scaleStrength(7), (byte) 40),
							2
					);
				}
			}
			if (useItemAnimation == ItemUseAnimation.SPEAR) {
				TriggerSide useSide = AdaptiveTriggerHelper.triggerSideForBinding(ControlifyBindings.USE);
				AdaptiveTriggerDispatcher dispatcher = AdaptiveTriggerHelper.dispatcherFor(ControlifyTriggerReasons.TRIDENT_RELEASE);
				if (useSide != null && dispatcher != null) {
					dispatcher.setLayer(
							useSide,
							ControlifyTriggerReasons.TRIDENT_RELEASE,
							ControlifyTriggerReasons.PRIORITY_COMBAT_BURST,
							new DualsenseTriggerEffect.Weapon((byte) 2, (byte) 8, AdaptiveTriggerHelper.scaleStrength(8)),
							2
					);
				}
			}
			// Drink-potion fizz tail — a brief sub-bass shimmer at the end of any DRINK animation.
			// EAT (food) gets nothing — it's already strongly differentiated by the chew cadence.
			if (useItemAnimation == ItemUseAnimation.DRINK) {
				TriggerSide useSide = AdaptiveTriggerHelper.triggerSideForBinding(ControlifyBindings.USE);
				AdaptiveTriggerDispatcher dispatcher = AdaptiveTriggerHelper.dispatcherFor(ControlifyTriggerReasons.DRINK_FIZZ);
				if (useSide != null && dispatcher != null) {
					dispatcher.setLayer(
							useSide,
							ControlifyTriggerReasons.DRINK_FIZZ,
							ControlifyTriggerReasons.PRIORITY_COMBAT_BURST,
							new DualsenseTriggerEffect.Vibration((byte) 0, AdaptiveTriggerHelper.scaleStrength(4), (byte) 80),
							4
					);
				}
			}
			useItemTrigger.release();
			useItemTrigger = null;
		}
		useItemAnimation = null;
		crossbowWasLoaded = false;
	}

	@Unique private void startTrigger(ItemStack stack, ItemUseAnimation anim) {
		switch (anim) {
			case BOW -> {
				TriggerSide side = AdaptiveTriggerHelper.triggerSideForBinding(ControlifyBindings.USE);
				AdaptiveTriggerDispatcher dispatcher = AdaptiveTriggerHelper.dispatcherFor(ControlifyTriggerReasons.BOW_DRAW);
				if (side != null && dispatcher != null) {
					useItemTrigger = dispatcher.setLayer(
							side,
							ControlifyTriggerReasons.BOW_DRAW,
							ControlifyTriggerReasons.PRIORITY_ACTION,
							new DualsenseTriggerEffect.Bow(
									(byte) 2, (byte) 8,
									AdaptiveTriggerHelper.scaleStrength(2),
									AdaptiveTriggerHelper.scaleStrength(8)
							)
					);
				}
			}
			case CROSSBOW -> {
				TriggerSide side = AdaptiveTriggerHelper.triggerSideForBinding(ControlifyBindings.USE);
				AdaptiveTriggerDispatcher dispatcher = AdaptiveTriggerHelper.dispatcherFor(ControlifyTriggerReasons.CROSSBOW_LOAD);
				if (side != null && dispatcher != null) {
					useItemTrigger = dispatcher.setLayer(
							side,
							ControlifyTriggerReasons.CROSSBOW_LOAD,
							ControlifyTriggerReasons.PRIORITY_ACTION,
							new DualsenseTriggerEffect.FeedbackSlope(
									(byte) 2, (byte) 8,
									AdaptiveTriggerHelper.scaleStrength(2),
									AdaptiveTriggerHelper.scaleStrength(7)
							)
					);
				}
			}
			case BLOCK -> {
				if (stack.getItem() instanceof ShieldItem) {
					TriggerSide side = AdaptiveTriggerHelper.triggerSideForBinding(ControlifyBindings.USE);
					AdaptiveTriggerDispatcher dispatcher = AdaptiveTriggerHelper.dispatcherFor(ControlifyTriggerReasons.SHIELD_HOLD);
					if (side != null && dispatcher != null) {
						useItemTrigger = dispatcher.setLayer(
								side,
								ControlifyTriggerReasons.SHIELD_HOLD,
								ControlifyTriggerReasons.PRIORITY_ACTION,
								new DualsenseTriggerEffect.Feedback((byte) 6, AdaptiveTriggerHelper.scaleStrength(8))
						);
					}
				}
			}
			case EAT, DRINK -> {
				TriggerSide side = AdaptiveTriggerHelper.triggerSideForBinding(ControlifyBindings.USE);
				AdaptiveTriggerDispatcher dispatcher = AdaptiveTriggerHelper.dispatcherFor(ControlifyTriggerReasons.EATING);
				if (side != null && dispatcher != null) {
					useItemTrigger = dispatcher.setLayer(
							side,
							ControlifyTriggerReasons.EATING,
							ControlifyTriggerReasons.PRIORITY_ACTION,
							new DualsenseTriggerEffect.FeedbackSlope(
									(byte) 0, (byte) 9,
									AdaptiveTriggerHelper.scaleStrength(2),
									AdaptiveTriggerHelper.scaleStrength(6)
							)
					);
				}
			}
			case SPEAR -> {
				TriggerSide side = AdaptiveTriggerHelper.triggerSideForBinding(ControlifyBindings.USE);
				AdaptiveTriggerDispatcher dispatcher = AdaptiveTriggerHelper.dispatcherFor(ControlifyTriggerReasons.TRIDENT_CHARGE);
				CUtil.LOGGER.log("[AdaptiveTrigger] SPEAR startTrigger. USE side: {}, TRIDENT_CHARGE dispatcher: {}",
						side, dispatcher == null ? "null" : "ok");
				if (side != null && dispatcher != null) {
					// Bow effect with the same widening-zone trick: low-charge feels light at the
					// trigger end, full-charge resists across most of the travel.
					useItemTrigger = dispatcher.setLayer(
							side,
							ControlifyTriggerReasons.TRIDENT_CHARGE,
							ControlifyTriggerReasons.PRIORITY_ACTION,
							new DualsenseTriggerEffect.Bow(
									(byte) 7, (byte) 8,
									AdaptiveTriggerHelper.scaleStrength(2),
									AdaptiveTriggerHelper.scaleStrength(8)
							)
					);
				}
			}
			case TOOT_HORN -> {
				// Steady deep toot pulse on the use trigger while the horn is sounding.
				TriggerSide side = AdaptiveTriggerHelper.triggerSideForBinding(ControlifyBindings.USE);
				AdaptiveTriggerDispatcher dispatcher = AdaptiveTriggerHelper.dispatcherFor(ControlifyTriggerReasons.GOAT_HORN_TOOT);
				if (side != null && dispatcher != null) {
					useItemTrigger = dispatcher.setLayer(
							side,
							ControlifyTriggerReasons.GOAT_HORN_TOOT,
							ControlifyTriggerReasons.PRIORITY_ACTION,
							new DualsenseTriggerEffect.Vibration((byte) 2, AdaptiveTriggerHelper.scaleStrength(5), (byte) 18)
					);
				}
			}
			default -> {}
		}
	}

	@Unique private void startRumble(ContinuousRumbleEffect effect) {
		ControlifyApi.get().playRumbleEffect(RumbleSource.INTERACTION, effect);
		this.useItemRumble = effect;
	}

	@Override
	public @Nullable ContinuousRumbleEffect controlify$getUseItemEffect() {
		return useItemRumble;
	}
}
