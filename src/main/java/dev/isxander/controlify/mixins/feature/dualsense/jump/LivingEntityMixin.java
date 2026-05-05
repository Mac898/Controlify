package dev.isxander.controlify.mixins.feature.dualsense.jump;

import dev.isxander.controlify.controller.dualsense.AdaptiveTriggerDispatcher;
import dev.isxander.controlify.controller.dualsense.AdaptiveTriggerHelper;
import dev.isxander.controlify.controller.dualsense.ControlifyTriggerReasons;
import dev.isxander.controlify.controller.dualsense.TriggerSide;
import dev.isxander.controlify.driver.dualsense.DualsenseTriggerEffect;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Light Vibration on both triggers when the local player jumps from the ground on foot.
 * Sprint-jumps land harder than walking jumps but the ground-leave moment is the same — keep the
 * effect tiny so it doesn't muddy the swing/break trigger feel.
 */
@Mixin(LivingEntity.class)
public class LivingEntityMixin {
    @Inject(method = "jumpFromGround", at = @At("HEAD"))
    private void controlify$onLocalPlayerJump(CallbackInfo ci) {
        if ((Object) this != Minecraft.getInstance().player) return;
        // Skip when riding (horse jump charge handles that case separately).
        if (Minecraft.getInstance().player.getVehicle() != null) return;
        AdaptiveTriggerDispatcher dispatcher = AdaptiveTriggerHelper.dispatcherFor(ControlifyTriggerReasons.JUMP_ON_FOOT);
        if (dispatcher == null) return;

        DualsenseTriggerEffect kick = new DualsenseTriggerEffect.Vibration(
                (byte) 0, AdaptiveTriggerHelper.scaleStrength(3), (byte) 35);
        dispatcher.setLayer(TriggerSide.LEFT, ControlifyTriggerReasons.JUMP_ON_FOOT,
                ControlifyTriggerReasons.PRIORITY_ACTION, kick, 2);
        dispatcher.setLayer(TriggerSide.RIGHT, ControlifyTriggerReasons.JUMP_ON_FOOT,
                ControlifyTriggerReasons.PRIORITY_ACTION, kick, 2);
    }
}
