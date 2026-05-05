package dev.isxander.controlify.mixins.feature.hdhaptics;

import dev.isxander.controlify.api.ControlifyApi;
import dev.isxander.controlify.controller.ControllerEntity;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Forwards every sound MC plays to the active controller's haptic dispatcher.
 * The dispatcher does its own gating on registry mappings, settings, cooldown, and source filtering.
 */
@Mixin(SoundEngine.class)
public class SoundEngineSoundInterceptMixin {
    @Inject(method = "play(Lnet/minecraft/client/resources/sounds/SoundInstance;)Lnet/minecraft/client/sounds/SoundEngine$PlayResult;", at = @At("HEAD"))
    private void controlify$dispatchHaptic(SoundInstance instance, CallbackInfoReturnable<net.minecraft.client.sounds.SoundEngine.PlayResult> cir) {
        ControlifyApi.get().getCurrentController()
                .flatMap(ControllerEntity::hdHaptics)
                .ifPresent(component -> component.dispatcher().dispatchSound(instance));
    }
}
