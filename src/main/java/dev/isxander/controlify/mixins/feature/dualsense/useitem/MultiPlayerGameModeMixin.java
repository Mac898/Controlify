package dev.isxander.controlify.mixins.feature.dualsense.useitem;

import dev.isxander.controlify.bindings.ControlifyBindings;
import dev.isxander.controlify.controller.dualsense.AdaptiveTriggerDispatcher;
import dev.isxander.controlify.controller.dualsense.AdaptiveTriggerHelper;
import dev.isxander.controlify.controller.dualsense.ControlifyTriggerReasons;
import dev.isxander.controlify.controller.dualsense.TriggerSide;
import dev.isxander.controlify.driver.dualsense.DualsenseTriggerEffect;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.HoeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Trigger feedback for click-on-block / use-in-air actions:
 * <ul>
 *     <li>Block place — Weapon click on L2</li>
 *     <li>Bucket fill / empty — Feedback hold on L2; lava adds a Vibration burst</li>
 * </ul>
 */
@Mixin(MultiPlayerGameMode.class)
public class MultiPlayerGameModeMixin {
    @Inject(method = "useItemOn(Lnet/minecraft/client/player/LocalPlayer;Lnet/minecraft/world/InteractionHand;Lnet/minecraft/world/phys/BlockHitResult;)Lnet/minecraft/world/InteractionResult;", at = @At("RETURN"))
    private void controlify$onUseItemOn(LocalPlayer player, InteractionHand hand, BlockHitResult hitResult, CallbackInfoReturnable<InteractionResult> cir) {
        if (!cir.getReturnValue().consumesAction()) return;
        ItemStack stack = player.getItemInHand(hand);
        dispatchUseTrigger(stack);
    }

    @Inject(method = "useItem(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/InteractionHand;)Lnet/minecraft/world/InteractionResult;", at = @At("RETURN"))
    private void controlify$onUseItem(Player player, InteractionHand hand, CallbackInfoReturnable<InteractionResult> cir) {
        if (!(player instanceof LocalPlayer)) return;
        if (!cir.getReturnValue().consumesAction()) return;
        ItemStack stack = player.getItemInHand(hand);
        // Only buckets need this path — block place always goes through useItemOn.
        if (stack.getItem() instanceof BucketItem) {
            dispatchUseTrigger(stack);
        }
    }

    private static void dispatchUseTrigger(ItemStack stack) {
        TriggerSide side = AdaptiveTriggerHelper.triggerSideForBinding(ControlifyBindings.USE);
        if (side == null) return;

        // Tool-break warning fires for any successful use action when the tool is over 90% damaged.
        if (stack.isDamageableItem() && stack.getMaxDamage() > 0
                && stack.getDamageValue() > 0.9f * stack.getMaxDamage()) {
            AdaptiveTriggerDispatcher warnDispatcher = AdaptiveTriggerHelper.dispatcherFor(ControlifyTriggerReasons.TOOL_BREAK_WARNING);
            if (warnDispatcher != null) {
                warnDispatcher.setLayer(
                        side,
                        ControlifyTriggerReasons.TOOL_BREAK_WARNING,
                        ControlifyTriggerReasons.PRIORITY_COMBAT_BURST,
                        new DualsenseTriggerEffect.Vibration((byte) 4, AdaptiveTriggerHelper.scaleStrength(3), (byte) 6),
                        2
                );
            }
        }

        if (stack.getItem() instanceof BucketItem) {
            AdaptiveTriggerDispatcher dispatcher = AdaptiveTriggerHelper.dispatcherFor(ControlifyTriggerReasons.BUCKET_USE);
            if (dispatcher == null) return;
            dispatcher.setLayer(
                    side,
                    ControlifyTriggerReasons.BUCKET_USE,
                    ControlifyTriggerReasons.PRIORITY_ACTION,
                    new DualsenseTriggerEffect.Feedback((byte) 4, AdaptiveTriggerHelper.scaleStrength(5)),
                    4 // ~200ms
            );
            if (stack.is(Items.LAVA_BUCKET)) {
                dispatcher.setLayer(
                        side,
                        ControlifyTriggerReasons.BUCKET_LAVA,
                        ControlifyTriggerReasons.PRIORITY_COMBAT_BURST,
                        new DualsenseTriggerEffect.Vibration((byte) 4, AdaptiveTriggerHelper.scaleStrength(4), (byte) 25),
                        4
                );
            }
            return;
        }

        if (stack.getItem() instanceof HoeItem) {
            AdaptiveTriggerDispatcher dispatcher = AdaptiveTriggerHelper.dispatcherFor(ControlifyTriggerReasons.HOE_TILL);
            if (dispatcher == null) return;
            dispatcher.setLayer(
                    side,
                    ControlifyTriggerReasons.HOE_TILL,
                    ControlifyTriggerReasons.PRIORITY_ACTION,
                    new DualsenseTriggerEffect.Vibration((byte) 3, AdaptiveTriggerHelper.scaleStrength(5), (byte) 18),
                    5 // ~250ms tilling buzz
            );
            return;
        }

        if (stack.getItem() instanceof SpawnEggItem) {
            AdaptiveTriggerDispatcher dispatcher = AdaptiveTriggerHelper.dispatcherFor(ControlifyTriggerReasons.SPAWN_EGG_USE);
            if (dispatcher == null) return;
            dispatcher.setLayer(
                    side,
                    ControlifyTriggerReasons.SPAWN_EGG_USE,
                    ControlifyTriggerReasons.PRIORITY_ACTION,
                    new DualsenseTriggerEffect.Weapon((byte) 3, (byte) 4, AdaptiveTriggerHelper.scaleStrength(2)),
                    1
            );
            return;
        }

        if (stack.getItem() instanceof BlockItem) {
            AdaptiveTriggerDispatcher dispatcher = AdaptiveTriggerHelper.dispatcherFor(ControlifyTriggerReasons.BLOCK_PLACE);
            if (dispatcher == null) return;
            dispatcher.setLayer(
                    side,
                    ControlifyTriggerReasons.BLOCK_PLACE,
                    ControlifyTriggerReasons.PRIORITY_ACTION,
                    new DualsenseTriggerEffect.Weapon((byte) 3, (byte) 4, AdaptiveTriggerHelper.scaleStrength(3)),
                    1 // ~50ms one-shot
            );
        }
    }
}
