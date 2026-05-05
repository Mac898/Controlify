package dev.isxander.controlify.mixins.feature.dualsense.useitem;

import dev.isxander.controlify.bindings.ControlifyBindings;
import dev.isxander.controlify.controller.dualsense.AdaptiveTriggerDispatcher;
import dev.isxander.controlify.controller.dualsense.AdaptiveTriggerHelper;
import dev.isxander.controlify.controller.dualsense.ControlifyTriggerReasons;
import dev.isxander.controlify.controller.dualsense.TriggerSide;
import dev.isxander.controlify.driver.sdl.dualsense.DualsenseTriggerEffect;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Fires the "trigger break" Weapon click on R2 the moment a charged crossbow is fired
 * (i.e. {@code use()} is called and the stack is already charged).
 */
@Mixin(CrossbowItem.class)
public class CrossbowItemMixin {
    @Inject(method = "use(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/InteractionHand;)Lnet/minecraft/world/InteractionResult;", at = @At("HEAD"))
    private void controlify$onCrossbowUse(Level level, Player player, InteractionHand hand, CallbackInfoReturnable<InteractionResult> cir) {
        if (!level.isClientSide()) return;
        if (!(player instanceof LocalPlayer)) return;

        ItemStack stack = player.getItemInHand(hand);
        if (!CrossbowItem.isCharged(stack)) return;

        TriggerSide side = AdaptiveTriggerHelper.triggerSideForBinding(ControlifyBindings.USE);
        AdaptiveTriggerDispatcher dispatcher = AdaptiveTriggerHelper.dispatcherFor(ControlifyTriggerReasons.CROSSBOW_FIRE);
        if (side == null || dispatcher == null) return;

        // Vibration burst is more felt than a thin Weapon click for the fire moment — gives a
        // distinct "shot" kick on top of any residual loaded-state resistance.
        dispatcher.setLayer(
                side,
                ControlifyTriggerReasons.CROSSBOW_FIRE,
                ControlifyTriggerReasons.PRIORITY_COMBAT_BURST,
                new DualsenseTriggerEffect.Vibration((byte) 2, AdaptiveTriggerHelper.scaleStrength(8), (byte) 60),
                4
        );
    }
}
