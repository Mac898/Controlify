package dev.isxander.controlify.mixins.feature.dualsense.shears;

import dev.isxander.controlify.api.ControlifyApi;
import dev.isxander.controlify.bindings.ControlifyBindings;
import dev.isxander.controlify.config.settings.profile.HDHapticSettings;
import dev.isxander.controlify.controller.ControllerEntity;
import dev.isxander.controlify.controller.FeedbackCategory;
import dev.isxander.controlify.controller.dualsense.AdaptiveTriggerDispatcher;
import dev.isxander.controlify.controller.dualsense.AdaptiveTriggerHelper;
import dev.isxander.controlify.controller.dualsense.ControlifyTriggerReasons;
import dev.isxander.controlify.controller.dualsense.TriggerSide;
import dev.isxander.controlify.controller.haptic.HDHapticComponent;
import dev.isxander.controlify.controller.haptic.HapticWaveform;
import dev.isxander.controlify.driver.dualsense.DualsenseTriggerEffect;
import dev.isxander.controlify.utils.CUtil;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ShearsItem;
import net.minecraft.world.phys.EntityHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Sharp Weapon click on the use trigger when the player interacts with an entity holding shears
 * (sheep shearing, mooshroom de-shrooming, pumpkin/jack-o'lantern carving on entity, etc.).
 * Plus an HD haptic snip waveform — synth cuts through even when the trigger isn't bound to L2/R2.
 */
@Mixin(MultiPlayerGameMode.class)
public class MultiPlayerGameModeMixin {
    @Unique private static boolean controlify$shearsLogged = false;

    @Inject(
            method = "interact(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/EntityHitResult;Lnet/minecraft/world/InteractionHand;)Lnet/minecraft/world/InteractionResult;",
            at = @At("RETURN")
    )
    private void controlify$onShearEntity(Player player, Entity entity, EntityHitResult hitResult, InteractionHand hand,
                                          CallbackInfoReturnable<InteractionResult> cir) {
        ItemStack stack = player.getItemInHand(hand);
        if (!(stack.getItem() instanceof ShearsItem)) return;
        if (!cir.getReturnValue().consumesAction()) return;

        if (!controlify$shearsLogged) {
            controlify$shearsLogged = true;
            CUtil.LOGGER.log("[AdaptiveTrigger] Shears entity-interact fired: entity={}, hand={}, result={}",
                    entity.getType(), hand, cir.getReturnValue());
        }

        // HD haptic snip — fires regardless of trigger binding, so the user feels the cut even
        // if USE isn't on a trigger.
        controlify$fireSnipHaptic();

        // Adaptive trigger snip: try USE first, fall back to ATTACK.
        TriggerSide side = AdaptiveTriggerHelper.triggerSideForBinding(ControlifyBindings.USE);
        if (side == null) side = AdaptiveTriggerHelper.triggerSideForBinding(ControlifyBindings.ATTACK);
        AdaptiveTriggerDispatcher dispatcher = AdaptiveTriggerHelper.dispatcherFor(ControlifyTriggerReasons.SHEARS_ENTITY_CLIP);
        if (side == null || dispatcher == null) return;

        dispatcher.setLayer(
                side,
                ControlifyTriggerReasons.SHEARS_ENTITY_CLIP,
                ControlifyTriggerReasons.PRIORITY_ACTION,
                new DualsenseTriggerEffect.Weapon((byte) 4, (byte) 6, AdaptiveTriggerHelper.scaleStrength(7)),
                2
        );
    }

    @Unique
    private static void controlify$fireSnipHaptic() {
        HDHapticComponent hd = ControlifyApi.get().getCurrentController()
                .flatMap(ControllerEntity::hdHaptics)
                .orElse(null);
        if (hd == null) return;
        HDHapticSettings settings = hd.settings();
        if (!settings.enabled) return;
        if (!settings.isCategoryEnabled(FeedbackCategory.ENVIRONMENT)) return;

        float intensity = settings.synthesisIntensity * settings.masterIntensity;
        if (intensity <= 0.001f) return;

        // Two pink-noise transients separated by a tiny gap = scissor snip.
        HapticWaveform snip1 = HapticWaveform.withASR(HapticWaveform.pinkNoise(40, 0.55f * intensity), 1, 25, 14);
        HapticWaveform gap = HapticWaveform.sine(40f, 25, 0f);
        HapticWaveform snip2 = HapticWaveform.withASR(HapticWaveform.pinkNoise(35, 0.65f * intensity), 1, 22, 12);
        hd.playHaptic(HapticWaveform.concat(snip1, gap, snip2));
    }
}
