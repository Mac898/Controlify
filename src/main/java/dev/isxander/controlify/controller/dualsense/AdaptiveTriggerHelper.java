/*
 * Copyright (C) 2026 isXander
 * This file is part of Controlify.
 *
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.isxander.controlify.controller.dualsense;

import dev.isxander.controlify.api.ControlifyApi;
import dev.isxander.controlify.api.bind.InputBinding;
import dev.isxander.controlify.api.bind.InputBindingSupplier;
import dev.isxander.controlify.bindings.input.AxisInput;
import dev.isxander.controlify.bindings.input.Input;
import dev.isxander.controlify.controller.ControllerEntity;
import dev.isxander.controlify.controller.FeedbackCategory;
import dev.isxander.controlify.controller.input.GamepadInputs;
import dev.isxander.controlify.utils.CUtil;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;

/**
 * Centralised gating for game-event mixins that want to push trigger effects.
 * Returns the active controller's {@link AdaptiveTriggerDispatcher} only when:
 * a controller is current, it's a DualSense, the firmware reports trigger-effect support,
 * adaptive triggers are enabled in settings, and the supplied category is enabled.
 */
public final class AdaptiveTriggerHelper {
	// Debug: only log each rejection cause once per category to avoid spam.
	private static final EnumSet<FeedbackCategory> WARNED_NO_CONTROLLER = EnumSet.noneOf(FeedbackCategory.class);
	private static final EnumSet<FeedbackCategory> WARNED_NO_DUALSENSE = EnumSet.noneOf(FeedbackCategory.class);
	private static final EnumSet<FeedbackCategory> WARNED_NO_TRIGGER_FX = EnumSet.noneOf(FeedbackCategory.class);
	private static final EnumSet<FeedbackCategory> WARNED_DISABLED = EnumSet.noneOf(FeedbackCategory.class);
	private static final EnumSet<FeedbackCategory> WARNED_CATEGORY_OFF = EnumSet.noneOf(FeedbackCategory.class);

	private AdaptiveTriggerHelper() {}

	public static @Nullable AdaptiveTriggerDispatcher dispatcherFor(FeedbackCategory category) {
		ControllerEntity controller = ControlifyApi.get().getCurrentController().orElse(null);
		if (controller == null) {
			if (WARNED_NO_CONTROLLER.add(category))
				CUtil.LOGGER.log("[AdaptiveTrigger] Gate failed for {}: no current controller", category);
			return null;
		}

		DualsenseComponent ds = controller.dualSense().orElse(null);
		if (ds == null) {
			if (WARNED_NO_DUALSENSE.add(category))
				CUtil.LOGGER.log("[AdaptiveTrigger] Gate failed for {}: current controller has no DualSense component", category);
			return null;
		}
		if (!ds.support().triggerEffects()) {
			if (WARNED_NO_TRIGGER_FX.add(category))
				CUtil.LOGGER.log("[AdaptiveTrigger] Gate failed for {}: feature support flag triggerEffects=false", category);
			return null;
		}

		var settings = controller.settings().dualsense;
		if (!settings.triggerEffects) {
			if (WARNED_DISABLED.add(category))
				CUtil.LOGGER.log("[AdaptiveTrigger] Gate failed for {}: triggerEffects=false in settings", category);
			return null;
		}
		if (!settings.isCategoryEnabled(category)) {
			if (WARNED_CATEGORY_OFF.add(category))
				CUtil.LOGGER.log("[AdaptiveTrigger] Gate failed for {}: category disabled (toggles map={})", category, settings.triggerCategoryToggles);
			return null;
		}

		return ds.adaptiveTriggers();
	}

	public static @Nullable AdaptiveTriggerDispatcher dispatcherFor(TriggerReason reason) {
		return dispatcherFor(ControlifyTriggerReasons.categoryOf(reason));
	}

	/** Scale a 0..8 strength byte by the user's trigger strength multiplier. Result clamped to 0..8. */
	public static byte scaleStrength(int base) {
		ControllerEntity controller = ControlifyApi.get().getCurrentController().orElse(null);
		float multiplier = controller == null ? 1f : controller.settings().dualsense.triggerStrengthMultiplier;
		int scaled = Math.round(base * multiplier);
		return (byte) Math.max(0, Math.min(8, scaled));
	}

	/**
	 * Resolve which physical trigger side the given binding is currently mapped to.
	 * Returns LEFT / RIGHT for an L2 / R2 axis binding, or null if the binding is not bound
	 * to a trigger (e.g. mapped to a button) — in which case callers should skip the trigger
	 * effect entirely. Honours user remappings.
	 */
	public static @Nullable TriggerSide triggerSideForBinding(InputBindingSupplier supplier) {
		ControllerEntity controller = ControlifyApi.get().getCurrentController().orElse(null);
		if (controller == null) return null;
		InputBinding binding = supplier.onOrNull(controller);
		if (binding == null) return null;
		Input input = binding.boundInput();
		if (input instanceof AxisInput axis) {
			if (axis.axis().equals(GamepadInputs.LEFT_TRIGGER_AXIS)) return TriggerSide.LEFT;
			if (axis.axis().equals(GamepadInputs.RIGHT_TRIGGER_AXIS)) return TriggerSide.RIGHT;
		}
		return null;
	}
}
