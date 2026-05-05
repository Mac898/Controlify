package dev.isxander.controlify.mixins.feature.dualsense.elytra;

import dev.isxander.controlify.bindings.ControlifyBindings;
import dev.isxander.controlify.controller.dualsense.AdaptiveTriggerDispatcher;
import dev.isxander.controlify.controller.dualsense.AdaptiveTriggerHelper;
import dev.isxander.controlify.controller.dualsense.ControlifyTriggerReasons;
import dev.isxander.controlify.controller.dualsense.TriggerSide;
import dev.isxander.controlify.driver.sdl.dualsense.DualsenseTriggerEffect;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.OptionalInt;

/**
 * While a firework rocket is attached to the local fall-flying player, push a Machine effect
 * on R2 to emulate the rocket revving through the right grip. Heartbeats every tick the
 * rocket is alive; releases naturally when the rocket is removed (heartbeat-or-die).
 */
@Mixin(FireworkRocketEntity.class)
public class FireworkRocketEntityMixin {
    @Shadow
    @Final
    private static EntityDataAccessor<OptionalInt> DATA_ATTACHED_TO_TARGET;

    @Inject(method = "tick", at = @At("RETURN"))
    private void controlify$onFireworkTick(CallbackInfo ci) {
        FireworkRocketEntity self = (FireworkRocketEntity) (Object) this;
        OptionalInt attachedId = self.getEntityData().get(DATA_ATTACHED_TO_TARGET);
        if (attachedId.isEmpty()) return;

        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return;
        if (attachedId.getAsInt() != player.getId()) return;
        if (!player.isFallFlying()) return;

        // The firework was activated via the USE binding; route the rev to that trigger so a remap travels with it.
        TriggerSide side = AdaptiveTriggerHelper.triggerSideForBinding(ControlifyBindings.USE);
        AdaptiveTriggerDispatcher dispatcher = AdaptiveTriggerHelper.dispatcherFor(ControlifyTriggerReasons.ELYTRA_FIREWORK);
        if (side == null || dispatcher == null) return;

        // Pure Vibration (no resistance) so the user can keep firing rockets without fighting the
        // trigger. Felt across the whole travel range — the boost rev is the goal, not a wall.
        dispatcher.setLayer(
                side,
                ControlifyTriggerReasons.ELYTRA_FIREWORK,
                ControlifyTriggerReasons.PRIORITY_ACTION,
                new DualsenseTriggerEffect.Vibration(
                        (byte) 0,
                        AdaptiveTriggerHelper.scaleStrength(8),
                        (byte) 60
                )
        );
    }
}
