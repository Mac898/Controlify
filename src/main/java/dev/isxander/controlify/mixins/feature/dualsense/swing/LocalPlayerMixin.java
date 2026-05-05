package dev.isxander.controlify.mixins.feature.dualsense.swing;

import dev.isxander.controlify.bindings.ControlifyBindings;
import dev.isxander.controlify.controller.dualsense.AdaptiveTriggerDispatcher;
import dev.isxander.controlify.controller.dualsense.AdaptiveTriggerHelper;
import dev.isxander.controlify.controller.dualsense.ControlifyTriggerReasons;
import dev.isxander.controlify.controller.dualsense.TriggerSide;
import dev.isxander.controlify.driver.sdl.dualsense.DualsenseTriggerEffect;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Pushes a one-shot Weapon click on R2 for every confirmed swing animation — entity attack,
 * block dig, or empty miss. Layered with a Vibration burst for critical hits, predicted from
 * the same conditions vanilla uses for crit particles (airborne + falling, not in water /
 * climbing / passenger). The off-hand swing (e.g. shield bash visual) is ignored.
 */
@Mixin(LocalPlayer.class)
public class LocalPlayerMixin {
    private static void runSwordSwoop(AdaptiveTriggerDispatcher dispatcher, TriggerSide side) {
        // 3-stage swing curve: tough start → soft middle → hard stop.
        //   Stage 1 (0–80ms):    heavy build-up — high amp, low freq thump.
        //   Stage 2 (80–180ms):  soft middle    — low amp, light buzz (the actual swing-through).
        //   Stage 3 (180–250ms): hard stop      — high amp, very low freq impact (end of swing).
        dispatcher.setLayer(
                side,
                ControlifyTriggerReasons.SWORD_SWING,
                ControlifyTriggerReasons.PRIORITY_ACTION,
                new DualsenseTriggerEffect.Vibration((byte) 0, AdaptiveTriggerHelper.scaleStrength(8), (byte) 35),
                2
        );
        Minecraft mc = Minecraft.getInstance();
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(80);
                mc.execute(() -> dispatcher.setLayer(
                        side,
                        ControlifyTriggerReasons.SWORD_SWING,
                        ControlifyTriggerReasons.PRIORITY_ACTION,
                        new DualsenseTriggerEffect.Vibration((byte) 0, AdaptiveTriggerHelper.scaleStrength(2), (byte) 50),
                        2
                ));
                Thread.sleep(100);
                mc.execute(() -> dispatcher.setLayer(
                        side,
                        ControlifyTriggerReasons.SWORD_SWING,
                        ControlifyTriggerReasons.PRIORITY_ACTION,
                        new DualsenseTriggerEffect.Vibration((byte) 0, AdaptiveTriggerHelper.scaleStrength(8), (byte) 22),
                        2
                ));
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }, "ControlifySwordSwoopTail");
        t.setDaemon(true);
        t.start();
    }

    @Inject(method = "swing(Lnet/minecraft/world/InteractionHand;)V", at = @At("HEAD"))
    private void controlify$onSwing(InteractionHand hand, CallbackInfo ci) {
        if (hand != InteractionHand.MAIN_HAND) return;

        TriggerSide side = AdaptiveTriggerHelper.triggerSideForBinding(ControlifyBindings.ATTACK);
        AdaptiveTriggerDispatcher dispatcher = AdaptiveTriggerHelper.dispatcherFor(ControlifyTriggerReasons.SWORD_SWING);
        if (side == null || dispatcher == null) return;

        // Two-stage "swoop": sharp low-frequency thump (impact), then a quieter sub-bass tail
        // 100ms later. The high→low frequency sweep approximates a sword swing's whoosh.
        LocalPlayer self = (LocalPlayer) (Object) this;
        runSwordSwoop(dispatcher, side);

        boolean isCrit = self.fallDistance > 0f
                && !self.onGround()
                && !self.onClimbable()
                && !self.isInWater()
                && !self.isPassenger();
        if (isCrit) {
            // Crit overlay: higher-priority Vibration burst on top of the swoop.
            dispatcher.setLayer(
                    side,
                    ControlifyTriggerReasons.SWORD_CRIT,
                    ControlifyTriggerReasons.PRIORITY_COMBAT_BURST,
                    new DualsenseTriggerEffect.Vibration((byte) 0, AdaptiveTriggerHelper.scaleStrength(8), (byte) 50),
                    4
            );
        }

        // Tool-about-to-break warning — fires alongside the swing if the held tool is over 90% damaged.
        ItemStack tool = self.getMainHandItem();
        if (tool.isDamageableItem() && tool.getMaxDamage() > 0
                && tool.getDamageValue() > 0.9f * tool.getMaxDamage()) {
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
    }
}
