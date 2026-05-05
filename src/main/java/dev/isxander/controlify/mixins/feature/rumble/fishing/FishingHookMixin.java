package dev.isxander.controlify.mixins.feature.rumble.fishing;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.isxander.controlify.api.ControlifyApi;
import dev.isxander.controlify.bindings.ControlifyBindings;
import dev.isxander.controlify.controller.dualsense.AdaptiveTriggerDispatcher;
import dev.isxander.controlify.controller.dualsense.AdaptiveTriggerHelper;
import dev.isxander.controlify.controller.dualsense.ControlifyTriggerReasons;
import dev.isxander.controlify.controller.dualsense.TriggerHandle;
import dev.isxander.controlify.controller.dualsense.TriggerSide;
import dev.isxander.controlify.driver.sdl.dualsense.DualsenseTriggerEffect;
import dev.isxander.controlify.rumble.ContinuousRumbleEffect;
import dev.isxander.controlify.rumble.RumbleSource;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.FishingHook;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FishingHook.class)
public class FishingHookMixin {
    @Shadow private boolean biting;
    @Shadow private int nibble;
    @Shadow private int timeUntilHooked;

    @Unique private boolean isLocalPlayerHook;
    @Unique private ContinuousRumbleEffect bitingRumble;
    @Unique private TriggerHandle controlify$biteTrigger;
    @Unique private TriggerHandle controlify$reelTrigger;
    /** Last seen value of timeUntilHooked — non-zero indicates "fish approaching" (particle trail). */
    @Unique private int controlify$lastTimeUntilHooked = 0;
    /** Soft-cue cooldown so we don't spam haptics on every nibble tick. */
    @Unique private int controlify$nibbleCooldown = 0;
    /** Previous-tick Y delta — used to detect bob direction reversals (each reversal = one "bob"). */
    @Unique private double controlify$prevDy = 0.0;
    /** Cooldown between bob pulses so the actuator doesn't blur into a constant drone. */
    @Unique private int controlify$bobCooldown = 0;

    @ModifyExpressionValue(method = "onSyncedDataUpdated", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/syncher/SynchedEntityData;get(Lnet/minecraft/network/syncher/EntityDataAccessor;)Ljava/lang/Object;", ordinal = 1))
    private Object onBitingStateUpdated(Object bitingObj) {
        var biting = (boolean) bitingObj;
        if (isLocalPlayerHook) {
            if (biting && !this.biting) {
                this.bitingRumble = ContinuousRumbleEffect.builder()
                        .constant(0f, 0.05f)
                        .build();

                ControlifyApi.get().playRumbleEffect(RumbleSource.INTERACTION, this.bitingRumble);
                startBiteTriggers();
            } else if (!biting && this.biting) {
                stopBitingRumble();
                stopBiteTriggers();
            }
        }
        return biting;
    }

    @Inject(method = "onClientRemoval", at = @At("RETURN"))
    private void onClientRemoval(CallbackInfo ci) {
        stopBitingRumble();
        stopBiteTriggers();
    }

    /** Per-tick soft cues that mirror the bobber's visual states before the bite is fully synced.
     *  These are HD-haptic-only, no AT layer, so they sit under the foreground bite haptic.
     *  Triggered states:
     *    - Fish approaching: timeUntilHooked transitioned 0 → >0 (the "particle trail" appears).
     *    - Pre-bite nibbles: nibble > 0 (the bobber dipping slightly each tick before the bite).
     */
    @Inject(method = "tick", at = @At("RETURN"))
    private void controlify$onFishingHookTick(CallbackInfo ci) {
        if (!isLocalPlayerHook) return;
        if (controlify$nibbleCooldown > 0) controlify$nibbleCooldown--;
        if (controlify$bobCooldown > 0) controlify$bobCooldown--;

        // Fish approaching: timeUntilHooked just became non-zero. Single soft pulse.
        if (controlify$lastTimeUntilHooked == 0 && this.timeUntilHooked > 0) {
            controlify$fireSoftPulse(0.18f, 35f, 70);
        }
        controlify$lastTimeUntilHooked = this.timeUntilHooked;

        // Pre-bite nibble: bobber dipping. Fire on first nibble of a session, not every tick.
        if (this.nibble > 0 && !this.biting && controlify$nibbleCooldown == 0) {
            controlify$fireSoftPulse(0.22f, 28f, 60);
            controlify$nibbleCooldown = 8; // ~400ms gap between nibble cues
        }

        // Bobber bobbing on the water surface: detect Y-velocity direction reversals. Each
        // reversal is a single visible bob (the bobber peaked or troughed). Only fires while the
        // bobber is in water and not biting (the bite haptic dominates anyway). Cooldown prevents
        // back-to-back micro-reversals from blurring into a drone.
        FishingHook self = (FishingHook) (Object) this;
        if (self.isInWater() && !this.biting) {
            double dy = self.getDeltaMovement().y;
            // Reversal if previous and current dy have opposite signs and magnitudes are non-trivial.
            boolean reversed = controlify$prevDy * dy < -1.0e-6
                    && (Math.abs(controlify$prevDy) > 0.005 || Math.abs(dy) > 0.005);
            if (reversed && controlify$bobCooldown == 0) {
                // Map magnitude to amp so calm water is barely felt and choppy water is clearly felt.
                double mag = Math.max(Math.abs(controlify$prevDy), Math.abs(dy));
                float amp = (float) Math.min(0.40, 0.10 + mag * 6.0);
                controlify$fireSoftPulse(amp, 50f, 50);
                controlify$bobCooldown = 4; // ~200ms gap
            }
            controlify$prevDy = dy;
        } else {
            controlify$prevDy = 0.0;
        }
    }

    @Unique
    private static void controlify$fireSoftPulse(float amp, float freq, int durMs) {
        var hd = ControlifyApi.get().getCurrentController()
                .flatMap(dev.isxander.controlify.controller.ControllerEntity::hdHaptics)
                .orElse(null);
        if (hd == null) return;
        var settings = hd.settings();
        if (!settings.enabled) return;
        if (!settings.isCategoryEnabled(dev.isxander.controlify.controller.FeedbackCategory.ITEMS)) return;
        float intensity = settings.synthesisIntensity * settings.masterIntensity;
        if (intensity <= 0.001f) return;
        var pulse = dev.isxander.controlify.controller.haptic.HapticWaveform.withASR(
                dev.isxander.controlify.controller.haptic.HapticWaveform.sine(freq, durMs, amp * intensity),
                3, durMs - 30, 27);
        hd.playHaptic(pulse);
    }

    @Unique
    private void stopBitingRumble() {
        if (bitingRumble != null) {
            bitingRumble.stop();
            bitingRumble = null;
        }
    }

    @Inject(method = "setOwner", at = @At("RETURN"))
    private void onOwnerSet(@Nullable Entity entity, CallbackInfo ci) {
        isLocalPlayerHook = entity instanceof LocalPlayer;
        if (isLocalPlayerHook) controlify$fireCastTrigger();
    }

    @Unique
    private static void controlify$fireCastTrigger() {
        TriggerSide useSide = AdaptiveTriggerHelper.triggerSideForBinding(ControlifyBindings.USE);
        AdaptiveTriggerDispatcher dispatcher = AdaptiveTriggerHelper.dispatcherFor(ControlifyTriggerReasons.FISHING_CAST);
        if (useSide == null || dispatcher == null) return;
        // Quick Weapon click as the bobber sails out — only on initial owner-set.
        dispatcher.setLayer(
                useSide,
                ControlifyTriggerReasons.FISHING_CAST,
                ControlifyTriggerReasons.PRIORITY_ACTION,
                new DualsenseTriggerEffect.Weapon((byte) 3, (byte) 5, AdaptiveTriggerHelper.scaleStrength(4)),
                2
        );
    }

    @Unique
    private void startBiteTriggers() {
        AdaptiveTriggerDispatcher dispatcher = AdaptiveTriggerHelper.dispatcherFor(ControlifyTriggerReasons.FISHING_BITE);
        if (dispatcher != null) {
            TriggerSide useSide = AdaptiveTriggerHelper.triggerSideForBinding(ControlifyBindings.USE);
            if (useSide != null) {
                // Held until the bite ends — the handle releases it on stop / removal.
                controlify$biteTrigger = dispatcher.setLayer(
                        useSide,
                        ControlifyTriggerReasons.FISHING_BITE,
                        ControlifyTriggerReasons.PRIORITY_COMBAT_BURST,
                        new DualsenseTriggerEffect.Vibration(
                                (byte) 2, AdaptiveTriggerHelper.scaleStrength(6), (byte) 25),
                        Integer.MAX_VALUE
                );
            }
        }
        // Reel resistance — Feedback wall on the ATTACK trigger so reeling-in feels weighty.
        AdaptiveTriggerDispatcher reelDispatcher = AdaptiveTriggerHelper.dispatcherFor(ControlifyTriggerReasons.FISHING_REEL);
        if (reelDispatcher != null) {
            TriggerSide attackSide = AdaptiveTriggerHelper.triggerSideForBinding(ControlifyBindings.ATTACK);
            if (attackSide != null) {
                controlify$reelTrigger = reelDispatcher.setLayer(
                        attackSide,
                        ControlifyTriggerReasons.FISHING_REEL,
                        ControlifyTriggerReasons.PRIORITY_ACTION,
                        new DualsenseTriggerEffect.Feedback((byte) 3, AdaptiveTriggerHelper.scaleStrength(7)),
                        Integer.MAX_VALUE
                );
            }
        }
    }

    @Unique
    private void stopBiteTriggers() {
        if (controlify$biteTrigger != null) {
            controlify$biteTrigger.release();
            controlify$biteTrigger = null;
        }
        if (controlify$reelTrigger != null) {
            controlify$reelTrigger.release();
            controlify$reelTrigger = null;
        }
    }
}
