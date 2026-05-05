package dev.isxander.controlify.controller.dualsense;

import dev.isxander.controlify.bindings.ControlifyBindings;
import dev.isxander.controlify.driver.sdl.dualsense.DualsenseTriggerEffect;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MaceItem;
import net.minecraft.world.item.SpyglassItem;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * Per-tick gameplay-state checks that push passive trigger modifier layers and held-item
 * baselines. Runs alongside {@link AdaptiveTriggerDispatcher#tick()} from {@code Controlify.tick()}.
 *
 * <p>One instance per {@link DualSenseComponent}. Layers pushed here use the {@code PRIORITY_PASSIVE}
 * band so any action-in-progress or combat-burst layer overrides them naturally — when the player
 * starts using their item, the use-item layer takes over; when they stop, the held-item baseline
 * resumes within {@link AdaptiveTriggerDispatcher#HEARTBEAT_TIMEOUT_TICKS} ticks.
 */
public final class AdaptiveTriggerTickHandler {
    private final DualSenseComponent component;

    public AdaptiveTriggerTickHandler(DualSenseComponent component) {
        this.component = component;
    }

    public void tick() {
        if (!component.support().triggerEffects()) return;
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return;
        tickUnderwaterModifier(player);
        tickIceModifier(player);
        tickMagmaModifier(player);
        tickLowHungerSprint(player);
        tickStickyBlock(player);
        tickHeldItemBaselines(player);
        tickVehicleRide(player);
        tickHorseJumpCharge(player);
        tickPortalPreTransport(player);
    }

    /** Sticky-surface resistance: light Feedback proportional to (1 - block speed factor). Picks
     *  up soul sand (0.4), mud (0.65), slime block (0.4), honey (0.4). Cobwebs use entity-side
     *  damping rather than speedFactor, so we add an explicit branch for them. Capped low so it
     *  preserves dynamic range against the ACTION layers. */
    private void tickStickyBlock(LocalPlayer player) {
        // Block at the player's feet (the one they're standing on / inside for cobwebs).
        BlockState feetBlock = player.level().getBlockState(player.blockPosition());
        BlockState belowBlock = player.level().getBlockState(player.blockPosition().below());

        float speedFactor = Math.min(feetBlock.getBlock().getSpeedFactor(), belowBlock.getBlock().getSpeedFactor());
        // Cobwebs don't use speedFactor; detect explicitly.
        boolean inCobweb = feetBlock.is(Blocks.COBWEB);
        if (speedFactor >= 1.0f && !inCobweb) return;

        AdaptiveTriggerDispatcher dispatcher = AdaptiveTriggerHelper.dispatcherFor(ControlifyTriggerReasons.MOVE_STICKY);
        if (dispatcher == null) return;

        // Slow factor 0..1 where 1 = full slow (cobweb-tier). Honey/slime ~0.6 → 4 strength.
        float slow = inCobweb ? 1.0f : Math.max(0f, 1.0f - speedFactor);
        // Soft cap so it never feels heavier than a held-item baseline (max strength 4).
        int strength = Math.min(4, 1 + (int) Math.round(3.5f * slow));
        DualsenseTriggerEffect effect = new DualsenseTriggerEffect.Feedback(
                (byte) 0, AdaptiveTriggerHelper.scaleStrength(strength));
        pushBoth(dispatcher, ControlifyTriggerReasons.MOVE_STICKY, effect);
    }

    /** Build-up swirl resistance while the player is inside a portal block, before the dimension
     *  transition fires. Resistance climbs 1→8 over the portal-wait window. */
    private void tickPortalPreTransport(LocalPlayer player) {
        // Cheap detection: check if the player's eye block or feet block is a portal.
        BlockState feetBlock = player.level().getBlockState(player.blockPosition());
        boolean inPortal = feetBlock.is(Blocks.NETHER_PORTAL) || feetBlock.is(Blocks.END_PORTAL);
        if (!inPortal) return;

        AdaptiveTriggerDispatcher dispatcher = AdaptiveTriggerHelper.dispatcherFor(ControlifyTriggerReasons.PORTAL_PRE);
        if (dispatcher == null) return;
        // Heavy buzz across the full trigger travel so the entrance feels eventful — replaces
        // the previous strength-5 mid-band with strength-7 full-band at a faster swirl frequency.
        DualsenseTriggerEffect effect = new DualsenseTriggerEffect.Vibration(
                (byte) 0, AdaptiveTriggerHelper.scaleStrength(7), (byte) 12);
        pushBoth(dispatcher, ControlifyTriggerReasons.PORTAL_PRE, effect);
    }

    /** While riding a jumpable mount with the jump charge accumulating, push a Bow effect on the
     *  USE trigger that widens with the charge — releases on jump (scale → 0). */
    private void tickHorseJumpCharge(LocalPlayer player) {
        float charge = player.getJumpRidingScale();
        if (charge <= 0f) return;
        if (player.jumpableVehicle() == null) return;
        AdaptiveTriggerDispatcher dispatcher = AdaptiveTriggerHelper.dispatcherFor(ControlifyTriggerReasons.HORSE_JUMP_CHARGE);
        if (dispatcher == null) return;
        // Drive USE trigger if bound to a trigger; fallback to ATTACK; then RIGHT.
        TriggerSide side = AdaptiveTriggerHelper.triggerSideForBinding(
                dev.isxander.controlify.bindings.ControlifyBindings.USE);
        if (side == null) side = AdaptiveTriggerHelper.triggerSideForBinding(
                dev.isxander.controlify.bindings.ControlifyBindings.ATTACK);
        if (side == null) return;

        // Bow: starts narrow at high zone, widens to span 2..8 at full charge — same shape as
        // the bow / trident charge so the user has a single consistent "tension" feel.
        int startPos = Math.max(2, Math.round(7f - 5f * charge));
        int strength = Math.round(2f + 6f * charge);
        DualsenseTriggerEffect effect = new DualsenseTriggerEffect.Bow(
                (byte) startPos, (byte) 8,
                AdaptiveTriggerHelper.scaleStrength(strength),
                AdaptiveTriggerHelper.scaleStrength(8));
        dispatcher.setLayer(side, ControlifyTriggerReasons.HORSE_JUMP_CHARGE,
                ControlifyTriggerReasons.PRIORITY_ACTION, effect);
    }

    /** Faint Vibration on both triggers while riding a boat or minecart — felt as a "running engine" buzz. */
    private void tickVehicleRide(LocalPlayer player) {
        var vehicle = player.getVehicle();
        if (vehicle == null) return;
        boolean isMinecart = vehicle instanceof net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
        boolean isBoat = vehicle instanceof net.minecraft.world.entity.vehicle.boat.AbstractBoat;
        if (!isMinecart && !isBoat) return;

        AdaptiveTriggerDispatcher dispatcher = AdaptiveTriggerHelper.dispatcherFor(ControlifyTriggerReasons.VEHICLE_RIDE);
        if (dispatcher == null) return;

        var v = vehicle.getDeltaMovement();
        double speed = Math.sqrt(v.x * v.x + v.z * v.z);
        if (speed < 0.05) return;
        // Minecarts at high speed feel chunkier than boats; pick frequency accordingly.
        int amp = Math.min(6, 2 + (int) Math.round(speed * 12));
        byte freq = isMinecart ? (byte) 22 : (byte) 14;
        DualsenseTriggerEffect ride = new DualsenseTriggerEffect.Vibration(
                (byte) 0, AdaptiveTriggerHelper.scaleStrength(amp), freq);
        pushBoth(dispatcher, ControlifyTriggerReasons.VEHICLE_RIDE, ride);
    }

    /** While submerged, push a low-priority Feedback wall to both triggers — water-resistance feel. */
    private void tickUnderwaterModifier(LocalPlayer player) {
        if (!player.isUnderWater()) return;
        AdaptiveTriggerDispatcher dispatcher = AdaptiveTriggerHelper.dispatcherFor(ControlifyTriggerReasons.UNDERWATER_MODIFIER);
        if (dispatcher == null) return;

        DualsenseTriggerEffect modifier = new DualsenseTriggerEffect.Feedback(
                (byte) 1, AdaptiveTriggerHelper.scaleStrength(2));
        pushBoth(dispatcher, ControlifyTriggerReasons.UNDERWATER_MODIFIER, modifier);
    }

    /** Slippery surfaces (ice / packed ice / blue ice) push a faint wobble to both triggers. */
    private void tickIceModifier(LocalPlayer player) {
        if (!player.onGround()) return;
        BlockState below = player.level().getBlockState(player.blockPosition().below());
        if (below.getBlock().getFriction() <= 0.7f) return;
        AdaptiveTriggerDispatcher dispatcher = AdaptiveTriggerHelper.dispatcherFor(ControlifyTriggerReasons.ICE_MODIFIER);
        if (dispatcher == null) return;

        DualsenseTriggerEffect modifier = new DualsenseTriggerEffect.Vibration(
                (byte) 0, AdaptiveTriggerHelper.scaleStrength(2), (byte) 30);
        pushBoth(dispatcher, ControlifyTriggerReasons.ICE_MODIFIER, modifier);
    }

    /** Standing on a magma block — heat-buzz on both triggers. */
    private void tickMagmaModifier(LocalPlayer player) {
        if (!player.onGround()) return;
        BlockState below = player.level().getBlockState(player.blockPosition().below());
        if (!below.is(Blocks.MAGMA_BLOCK)) return;
        AdaptiveTriggerDispatcher dispatcher = AdaptiveTriggerHelper.dispatcherFor(ControlifyTriggerReasons.MAGMA_MODIFIER);
        if (dispatcher == null) return;

        DualsenseTriggerEffect modifier = new DualsenseTriggerEffect.Vibration(
                (byte) 0, AdaptiveTriggerHelper.scaleStrength(6), (byte) 10);
        pushBoth(dispatcher, ControlifyTriggerReasons.MAGMA_MODIFIER, modifier);
    }

    /** Sprinting with food level ≤ 6 — light fatigue buzz on both triggers. */
    private void tickLowHungerSprint(LocalPlayer player) {
        if (!player.isSprinting()) return;
        if (player.getFoodData().getFoodLevel() > 6) return;
        AdaptiveTriggerDispatcher dispatcher = AdaptiveTriggerHelper.dispatcherFor(ControlifyTriggerReasons.LOW_HUNGER_SPRINT);
        if (dispatcher == null) return;

        DualsenseTriggerEffect modifier = new DualsenseTriggerEffect.Vibration(
                (byte) 2, AdaptiveTriggerHelper.scaleStrength(2), (byte) 10);
        pushBoth(dispatcher, ControlifyTriggerReasons.LOW_HUNGER_SPRINT, modifier);
    }

    /** Held-item baselines: weapon weight, bow/crossbow trigger feel, trident charge weight, spyglass zoom hold, tool-facing-block hardness. */
    private void tickHeldItemBaselines(LocalPlayer player) {
        ItemStack main = player.getMainHandItem();

        // Bow held — narrow-zone Bow at the trigger end, mirroring the start-of-draw effect so
        // there's no perceptible lag when the user actually starts drawing.
        if (main.getItem() instanceof BowItem) {
            pushHeld(ControlifyTriggerReasons.BOW_HOLD, ControlifyBindings.USE.bindId(),
                    new DualsenseTriggerEffect.Bow((byte) 7, (byte) 8,
                            AdaptiveTriggerHelper.scaleStrength(2),
                            AdaptiveTriggerHelper.scaleStrength(8)),
                    true);
        }

        // Crossbow uncharged — light Feedback baseline matching the start of FeedbackSlope so the
        // load animation transitions smoothly. When charged, CROSSBOW_READY (below) takes over.
        if (main.getItem() instanceof CrossbowItem) {
            if (CrossbowItem.isCharged(main)) {
                // Hard "trigger break" wall before firing — the user has to push past zone 4 to fire.
                // Sharp enough that release feels distinct from the gradual loading resistance.
                pushHeld(ControlifyTriggerReasons.CROSSBOW_READY, ControlifyBindings.USE.bindId(),
                        new DualsenseTriggerEffect.Feedback((byte) 4, AdaptiveTriggerHelper.scaleStrength(8)),
                        true);
            } else {
                pushHeld(ControlifyTriggerReasons.CROSSBOW_HOLD, ControlifyBindings.USE.bindId(),
                        new DualsenseTriggerEffect.Feedback((byte) 4, AdaptiveTriggerHelper.scaleStrength(2)),
                        true);
            }
        }

        // Trident — light "weight" on the use trigger; the SPEAR-animation Bow effect overrides
        // immediately on right-click.
        if (main.getItem() instanceof TridentItem) {
            pushHeld(ControlifyTriggerReasons.TRIDENT_HOLD, ControlifyBindings.USE.bindId(),
                    new DualsenseTriggerEffect.Feedback((byte) 3, AdaptiveTriggerHelper.scaleStrength(4)),
                    true);
        }

        // Spyglass held — passive zoom-feel on the use trigger.
        if (main.getItem() instanceof SpyglassItem) {
            pushHeld(ControlifyTriggerReasons.SPYGLASS_HOLD, ControlifyBindings.USE.bindId(),
                    new DualsenseTriggerEffect.Feedback((byte) 5, AdaptiveTriggerHelper.scaleStrength(5)),
                    true);
        }

        // Weapon-style weight for swords / maces / axes — anything with a Weapon data component.
        // The Weapon component covers swords + maces in 1.21+; axes also have it.
        if (main.has(net.minecraft.core.component.DataComponents.WEAPON)) {
            pushHeld(ControlifyTriggerReasons.WEAPON_HOLD, ControlifyBindings.ATTACK.bindId(),
                    new DualsenseTriggerEffect.Feedback((byte) 3, AdaptiveTriggerHelper.scaleStrength(4)),
                    /* useSide = */ false);
        }

        // Mace-airborne — resistance grows as you fall further with a mace; the trigger feels
        // "loaded" mid-air for the ground slam. Felt across the whole travel so it's clearly
        // distinct from the WEAPON_HOLD baseline. Threshold 1.0 to avoid noise from minor jumps.
        if (main.getItem() instanceof MaceItem && !player.onGround() && player.fallDistance > 1.0) {
            int amp = Math.min(8, 3 + (int) Math.round(5.0 * Math.min(1.0, player.fallDistance / 6.0)));
            pushHeld(ControlifyTriggerReasons.MACE_SLAM, ControlifyBindings.ATTACK.bindId(),
                    new DualsenseTriggerEffect.Feedback((byte) 0, AdaptiveTriggerHelper.scaleStrength(amp)),
                    false);
        }

        // Block-place pre-emptive baseline — when holding a BlockItem facing a placeable block,
        // a faint Feedback wall appears on the use trigger so the click commits with resistance
        // rather than pure click. Stays out of the way of action layers via PRIORITY_PASSIVE.
        if (main.getItem() instanceof BlockItem) {
            tickBlockPlaceFacing(player);
        }

        // Tool-facing-block — fires for any held item that *can mine* the block in front of the
        // player, including weapons used as wrong-tool fallbacks. The previous version skipped
        // anything with the WEAPON component (axes!) and anything without TOOL (sword vs cobweb).
        // This pre-empts the trigger for the swing the user is about to make.
        tickToolFacingBlock(player, main);
    }

    /** When the player holds a BlockItem and the cursor is on a face where a block could be placed,
     *  push a small Feedback wall as a pre-emptive baseline. */
    private void tickBlockPlaceFacing(LocalPlayer player) {
        var hitResult = Minecraft.getInstance().hitResult;
        if (!(hitResult instanceof BlockHitResult bhr) || bhr.getType() != HitResult.Type.BLOCK) return;
        BlockState state = player.level().getBlockState(bhr.getBlockPos());
        if (state.isAir()) return;
        AdaptiveTriggerDispatcher dispatcher = AdaptiveTriggerHelper.dispatcherFor(ControlifyTriggerReasons.BLOCK_PLACE_HOLD);
        if (dispatcher == null) return;
        TriggerSide side = AdaptiveTriggerHelper.triggerSideForBinding(ControlifyBindings.USE);
        if (side == null) return;
        DualsenseTriggerEffect effect = new DualsenseTriggerEffect.Feedback(
                (byte) 4, AdaptiveTriggerHelper.scaleStrength(3));
        dispatcher.setLayer(side, ControlifyTriggerReasons.BLOCK_PLACE_HOLD,
                ControlifyTriggerReasons.PRIORITY_PASSIVE, effect);
    }

    private void tickToolFacingBlock(LocalPlayer player, ItemStack tool) {
        var hitResult = Minecraft.getInstance().hitResult;
        if (!(hitResult instanceof BlockHitResult bhr) || bhr.getType() != HitResult.Type.BLOCK) return;
        BlockState state = player.level().getBlockState(bhr.getBlockPos());
        if (state.isAir()) return;

        // Skip blocks the player can't actually mine: instabreak isn't worth a baseline either.
        // Use destroy-speed > 0 (any speed > 0 means the player can break it).
        float speed = state.getDestroySpeed(player.level(), bhr.getBlockPos());
        if (speed <= 0f) return;
        // For blocks requiring correct tool, only fire if the tool will drop loot — empty-handed
        // mining stone is busywork that shouldn't push the trigger.
        if (state.requiresCorrectToolForDrops() && !player.hasCorrectToolForDrops(state)) return;

        // Hardness factor 0..1 from destroy-speed (0 = obsidian-tier, 1 = instabreak-grade).
        // Lower destroy-speed = harder block = more resistance. Raw destroyTime is a better
        // proxy: higher = harder.
        float hardness = state.getBlock().defaultDestroyTime();
        if (hardness <= 0f) {
            // instabreak — minimum resistance, just enough to feel the contact.
            DualsenseTriggerEffect e = new DualsenseTriggerEffect.Feedback(
                    (byte) 2, AdaptiveTriggerHelper.scaleStrength(1));
            pushHeld(ControlifyTriggerReasons.TOOL_FACING_BLOCK, ControlifyBindings.ATTACK.bindId(), e, false);
            return;
        }
        int strength = Math.min(8, 2 + (int) Math.round(6.0 * Math.min(1.0, hardness / 10.0)));
        pushHeld(ControlifyTriggerReasons.TOOL_FACING_BLOCK, ControlifyBindings.ATTACK.bindId(),
                new DualsenseTriggerEffect.Feedback((byte) 2, AdaptiveTriggerHelper.scaleStrength(strength)),
                false);
    }

    private void pushHeld(TriggerReason reason, net.minecraft.resources.Identifier bindIdUnused, DualsenseTriggerEffect effect, boolean useSide) {
        AdaptiveTriggerDispatcher dispatcher = AdaptiveTriggerHelper.dispatcherFor(reason);
        if (dispatcher == null) return;
        TriggerSide side = AdaptiveTriggerHelper.triggerSideForBinding(
                useSide ? ControlifyBindings.USE : ControlifyBindings.ATTACK);
        if (side == null) return;
        dispatcher.setLayer(side, reason, ControlifyTriggerReasons.PRIORITY_PASSIVE, effect);
    }

    private static void pushBoth(AdaptiveTriggerDispatcher dispatcher, TriggerReason reason, DualsenseTriggerEffect effect) {
        dispatcher.setLayer(TriggerSide.LEFT, reason, ControlifyTriggerReasons.PRIORITY_PASSIVE, effect);
        dispatcher.setLayer(TriggerSide.RIGHT, reason, ControlifyTriggerReasons.PRIORITY_PASSIVE, effect);
    }
}
