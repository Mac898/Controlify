package dev.isxander.controlify.mixins.feature.hdhaptics.landing;

import dev.isxander.controlify.api.ControlifyApi;
import dev.isxander.controlify.config.settings.profile.HDHapticSettings;
import dev.isxander.controlify.controller.ControllerEntity;
import dev.isxander.controlify.controller.FeedbackCategory;
import dev.isxander.controlify.controller.haptic.HDHapticComponent;
import dev.isxander.controlify.controller.haptic.HapticWaveform;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Synthesised landing thump for the local player. Fires a 60 Hz half-cycle whose amplitude scales
 * with fall distance, on top of the existing footstep passthrough. Threshold raised to 2 blocks
 * so 1-block hops and short jumps are silent — the user wanted the thump to read as a "real fall"
 * rather than firing on every step.
 *
 * <p>Composes well with the heartbeat/hurt waveforms playing concurrently for the falling-damage
 * hit: the landing thud is shorter (90 ms) and lower frequency than the heartbeat double-thump
 * (~200 ms 50 Hz), so the user feels the thud first, then the heartbeat spike on top.
 */
@Mixin(Entity.class)
public class EntityMixin {
    @Inject(method = "checkFallDamage", at = @At("HEAD"))
    private void controlify$onLand(double y, boolean onGround, BlockState state, BlockPos pos, CallbackInfo ci) {
        if ((Object) this != Minecraft.getInstance().player) return;
        if (!onGround) return;

        Entity self = (Entity) (Object) this;
        double fallDist = self.fallDistance;
        if (fallDist < 2.0) return; // 1-block hops are silent

        HDHapticComponent component = ControlifyApi.get().getCurrentController()
                .flatMap(ControllerEntity::hdHaptics)
                .orElse(null);
        if (component == null) return;

        HDHapticSettings settings = component.settings();
        if (!settings.enabled) return;
        if (!settings.isCategoryEnabled(FeedbackCategory.MOVEMENT)) return;

        // Map fall distance 2 → 18 blocks to amp 0 → 1, then square so short falls feel light and
        // long falls saturate. The 2-block floor matches the start of fall damage in vanilla.
        double normalised = Math.min(1.0, Math.max(0.0, (fallDist - 2.0) / 16.0));
        float amp = (float) (normalised * normalised);
        float intensity = settings.synthesisIntensity * settings.masterIntensity;
        amp *= intensity;
        if (amp <= 0.001f) return;

        // Lower freq + slightly shorter duration so the heartbeat (50 Hz, ~200 ms) on damage
        // reads as a discrete beat rather than blending with the thud.
        HapticWaveform thump = HapticWaveform.withASR(
                HapticWaveform.sine(45f, 90, 0.95f * amp), 5, 25, 60);
        component.playHaptic(thump);

        // Mace slam landing: if we're holding a mace and the fall is significant, layer a bright
        // impact spike on top — vanilla deals smash damage so the haptic should feel weighty.
        net.minecraft.world.item.ItemStack main = ((net.minecraft.world.entity.LivingEntity) self).getMainHandItem();
        if (fallDist >= 3.0 && main.getItem() instanceof net.minecraft.world.item.MaceItem) {
            float maceAmp = (float) Math.min(1.0, fallDist / 5.0) * intensity;
            HapticWaveform spike = HapticWaveform.withASR(
                    HapticWaveform.sine(28f, 200, 0.9f * maceAmp), 3, 110, 87);
            HapticWaveform crackle = HapticWaveform.withASR(
                    HapticWaveform.brownNoise(220, 0.5f * maceAmp), 5, 90, 125);
            component.playHaptic(HapticWaveform.mix(spike, crackle));

            // Adaptive trigger spike on attack trigger — mace impact registers on both triggers
            // since the player isn't pulling triggers when landing.
            var atDispatcher = dev.isxander.controlify.controller.dualsense.AdaptiveTriggerHelper.dispatcherFor(
                    dev.isxander.controlify.controller.dualsense.ControlifyTriggerReasons.MACE_SLAM_LAND);
            if (atDispatcher != null) {
                int strength = Math.min(8, 4 + (int) Math.round(4.0 * Math.min(1.0, fallDist / 5.0)));
                var effect = new dev.isxander.controlify.driver.sdl.dualsense.DualsenseTriggerEffect.Vibration(
                        (byte) 0,
                        dev.isxander.controlify.controller.dualsense.AdaptiveTriggerHelper.scaleStrength(strength),
                        (byte) 22);
                atDispatcher.setLayer(dev.isxander.controlify.controller.dualsense.TriggerSide.LEFT,
                        dev.isxander.controlify.controller.dualsense.ControlifyTriggerReasons.MACE_SLAM_LAND,
                        dev.isxander.controlify.controller.dualsense.ControlifyTriggerReasons.PRIORITY_COMBAT_BURST,
                        effect, 4);
                atDispatcher.setLayer(dev.isxander.controlify.controller.dualsense.TriggerSide.RIGHT,
                        dev.isxander.controlify.controller.dualsense.ControlifyTriggerReasons.MACE_SLAM_LAND,
                        dev.isxander.controlify.controller.dualsense.ControlifyTriggerReasons.PRIORITY_COMBAT_BURST,
                        effect, 4);
            }
        }
    }
}
