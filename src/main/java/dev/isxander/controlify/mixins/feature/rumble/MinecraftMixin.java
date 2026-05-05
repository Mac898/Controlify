package dev.isxander.controlify.mixins.feature.rumble;

import dev.isxander.controlify.api.ControlifyApi;
import dev.isxander.controlify.controller.ControllerEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class MinecraftMixin {
    @Inject(method = "disconnect(Lnet/minecraft/client/gui/screens/Screen;Z)V", at = @At("HEAD"))
    private void clearRumbleEffects(CallbackInfo ci) {
        ControlifyApi.get().getCurrentController().ifPresent(controller -> {
            controller.rumble().ifPresent(rumble -> rumble.rumbleManager().clearEffects());
            controller.dualSense().ifPresent(ds -> ds.adaptiveTriggers().clearAll());
            controller.hdHaptics().ifPresent(haptics -> haptics.dispatcher().clearAll());
        });
    }
}
