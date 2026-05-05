package dev.isxander.controlify.mixins.feature.dualsense.swing;

import dev.isxander.controlify.bindings.ControlifyBindings;
import dev.isxander.controlify.controller.dualsense.AdaptiveTriggerDispatcher;
import dev.isxander.controlify.controller.dualsense.AdaptiveTriggerHelper;
import dev.isxander.controlify.controller.dualsense.ControlifyTriggerReasons;
import dev.isxander.controlify.controller.dualsense.TriggerSide;
import dev.isxander.controlify.driver.sdl.dualsense.DualsenseTriggerEffect;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Sound-driven sword impact differentiation. Each {@code PLAYER_ATTACK_*} sound type fires a
 * distinct adaptive trigger overlay at {@code PRIORITY_COMBAT_BURST}, briefly overriding the
 * generic 3-stage swoop the swing mixin produces. Only fires for sounds played at the local player.
 */
@Mixin(SoundEngine.class)
public class SoundEngineSwordImpactMixin {
    @Inject(method = "play(Lnet/minecraft/client/resources/sounds/SoundInstance;)Lnet/minecraft/client/sounds/SoundEngine$PlayResult;", at = @At("HEAD"))
    private void controlify$onAttackSound(SoundInstance instance, CallbackInfoReturnable<SoundEngine.PlayResult> cir) {
        Identifier id = instance.getIdentifier();
        if (!"minecraft".equals(id.getNamespace())) return;

        DualsenseTriggerEffect effect;
        int durationTicks;
        if (id.equals(SoundEvents.PLAYER_ATTACK_STRONG.location())) {
            // Heavy hit — full amp at deep sub-bass freq for a thump.
            effect = new DualsenseTriggerEffect.Vibration((byte) 0, AdaptiveTriggerHelper.scaleStrength(8), (byte) 25);
            durationTicks = 4;
        } else if (id.equals(SoundEvents.PLAYER_ATTACK_CRIT.location())) {
            // Critical hit — bright high-freq spike, distinct from STRONG's thump.
            effect = new DualsenseTriggerEffect.Vibration((byte) 0, AdaptiveTriggerHelper.scaleStrength(8), (byte) 70);
            durationTicks = 4;
        } else if (id.equals(SoundEvents.PLAYER_ATTACK_KNOCKBACK.location())) {
            // Knockback — heaviest, slowest, deepest.
            effect = new DualsenseTriggerEffect.Vibration((byte) 0, AdaptiveTriggerHelper.scaleStrength(8), (byte) 18);
            durationTicks = 5;
        } else if (id.equals(SoundEvents.PLAYER_ATTACK_SWEEP.location())) {
            // Sweep — wide medium-freq buzz to convey "multiple things hit".
            effect = new DualsenseTriggerEffect.Vibration((byte) 0, AdaptiveTriggerHelper.scaleStrength(6), (byte) 50);
            durationTicks = 3;
        } else if (id.equals(SoundEvents.PLAYER_ATTACK_WEAK.location())) {
            // Tap-attack on cooldown — light flick.
            effect = new DualsenseTriggerEffect.Vibration((byte) 0, AdaptiveTriggerHelper.scaleStrength(4), (byte) 35);
            durationTicks = 2;
        } else if (id.equals(SoundEvents.PLAYER_ATTACK_NODAMAGE.location())) {
            // Miss — barely felt; a faint pat distinguishes "swung at nothing" from "swung and hit".
            effect = new DualsenseTriggerEffect.Vibration((byte) 0, AdaptiveTriggerHelper.scaleStrength(2), (byte) 40);
            durationTicks = 1;
        } else {
            return;
        }

        // Only react if the sound came from the local player (not a neighbouring PvP fight).
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        double dx = instance.getX() - mc.player.getX();
        double dy = instance.getY() - mc.player.getY();
        double dz = instance.getZ() - mc.player.getZ();
        if (dx * dx + dy * dy + dz * dz > 1.0) return; // ~1-block radius

        AdaptiveTriggerDispatcher dispatcher = AdaptiveTriggerHelper.dispatcherFor(ControlifyTriggerReasons.SWORD_HIT_IMPACT);
        if (dispatcher == null) return;
        TriggerSide side = AdaptiveTriggerHelper.triggerSideForBinding(ControlifyBindings.ATTACK);
        if (side == null) return;

        dispatcher.setLayer(
                side,
                ControlifyTriggerReasons.SWORD_HIT_IMPACT,
                ControlifyTriggerReasons.PRIORITY_COMBAT_BURST,
                effect,
                durationTicks
        );
    }
}
