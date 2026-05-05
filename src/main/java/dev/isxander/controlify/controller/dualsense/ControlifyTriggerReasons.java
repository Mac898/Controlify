package dev.isxander.controlify.controller.dualsense;

import dev.isxander.controlify.controller.FeedbackCategory;

/**
 * Centralized registry of {@link TriggerReason} values used by Controlify gameplay hooks.
 * Priority bands documented on {@link TriggerReason}:
 * <ul>
 *     <li>0–99: fallback / passive (water resistance, ice slip)</li>
 *     <li>100–199: action-in-progress (drawing a bow, mining, eating)</li>
 *     <li>200–299: combat-imminent (critical hit, crossbow fire, shield block-hit)</li>
 *     <li>1000+: system / debug</li>
 * </ul>
 * Each reason carries a category that the user can toggle in settings;
 * use {@link #categoryOf(TriggerReason)} to recover it.
 */
public final class ControlifyTriggerReasons {
    public static final int PRIORITY_PASSIVE = 50;
    public static final int PRIORITY_ACTION = 100;
    public static final int PRIORITY_COMBAT_BURST = 200;

    public static final TriggerReason BOW_DRAW = TriggerReason.register("bow_draw", "Bow draw", TriggerReason.ReasonScope.IN_WORLD);
    public static final TriggerReason CROSSBOW_LOAD = TriggerReason.register("crossbow_load", "Crossbow loading", TriggerReason.ReasonScope.IN_WORLD);
    public static final TriggerReason CROSSBOW_LOADED = TriggerReason.register("crossbow_loaded", "Crossbow ready", TriggerReason.ReasonScope.IN_WORLD);
    public static final TriggerReason CROSSBOW_FIRE = TriggerReason.register("crossbow_fire", "Crossbow firing", TriggerReason.ReasonScope.IN_WORLD);
    public static final TriggerReason SWORD_SWING = TriggerReason.register("sword_swing", "Sword swing", TriggerReason.ReasonScope.IN_WORLD);
    public static final TriggerReason SWORD_CRIT = TriggerReason.register("sword_crit", "Critical hit", TriggerReason.ReasonScope.IN_WORLD);
    public static final TriggerReason SHIELD_HOLD = TriggerReason.register("shield_hold", "Shield raised", TriggerReason.ReasonScope.IN_WORLD);
    public static final TriggerReason SHIELD_PULSE = TriggerReason.register("shield_pulse", "Shield blocked attack", TriggerReason.ReasonScope.IN_WORLD);
    public static final TriggerReason EATING = TriggerReason.register("eating", "Eating / drinking", TriggerReason.ReasonScope.IN_WORLD);
    public static final TriggerReason EATING_CHEW = TriggerReason.register("eating_chew", "Eating chew pulse", TriggerReason.ReasonScope.IN_WORLD);
    public static final TriggerReason BOW_RELEASE = TriggerReason.register("bow_release", "Bow release snap", TriggerReason.ReasonScope.IN_WORLD);
    public static final TriggerReason BLOCK_BREAK = TriggerReason.register("block_break", "Block break progress", TriggerReason.ReasonScope.IN_WORLD);
    public static final TriggerReason BLOCK_PLACE = TriggerReason.register("block_place", "Block place click", TriggerReason.ReasonScope.IN_WORLD);
    public static final TriggerReason BUCKET_USE = TriggerReason.register("bucket_use", "Bucket fill or empty", TriggerReason.ReasonScope.IN_WORLD);
    public static final TriggerReason BUCKET_LAVA = TriggerReason.register("bucket_lava", "Lava bucket pulse overlay", TriggerReason.ReasonScope.IN_WORLD);
    public static final TriggerReason ELYTRA_FIREWORK = TriggerReason.register("elytra_firework", "Elytra firework boost", TriggerReason.ReasonScope.IN_WORLD);
    public static final TriggerReason TRIDENT_CHARGE = TriggerReason.register("trident_charge", "Trident throw charge", TriggerReason.ReasonScope.IN_WORLD);
    public static final TriggerReason TRIDENT_RELEASE = TriggerReason.register("trident_release", "Trident throw release snap", TriggerReason.ReasonScope.IN_WORLD);
    public static final TriggerReason UNDERWATER_MODIFIER = TriggerReason.register("underwater_modifier", "Underwater resistance modifier", TriggerReason.ReasonScope.IN_WORLD);
    public static final TriggerReason TOOL_BREAK_WARNING = TriggerReason.register("tool_break_warning", "Tool about-to-break warning", TriggerReason.ReasonScope.IN_WORLD);
    // Held-item passive baselines — applied while the relevant item is in the main hand so the
    // trigger feels appropriate before any explicit action begins.
    public static final TriggerReason WEAPON_HOLD = TriggerReason.register("weapon_hold", "Weapon held baseline", TriggerReason.ReasonScope.IN_WORLD);
    public static final TriggerReason CROSSBOW_READY = TriggerReason.register("crossbow_ready", "Crossbow charged-and-ready baseline", TriggerReason.ReasonScope.IN_WORLD);
    public static final TriggerReason TRIDENT_HOLD = TriggerReason.register("trident_hold", "Trident held baseline", TriggerReason.ReasonScope.IN_WORLD);
    public static final TriggerReason SPYGLASS_HOLD = TriggerReason.register("spyglass_hold", "Spyglass zoom hold", TriggerReason.ReasonScope.IN_WORLD);
    // Passive environmental modifiers — same priority band as UNDERWATER_MODIFIER, naturally
    // overridden by any action / combat-burst layer.
    public static final TriggerReason ICE_MODIFIER = TriggerReason.register("ice_modifier", "Slippery surface vibration", TriggerReason.ReasonScope.IN_WORLD);
    public static final TriggerReason MAGMA_MODIFIER = TriggerReason.register("magma_modifier", "Magma block vibration", TriggerReason.ReasonScope.IN_WORLD);
    public static final TriggerReason LOW_HUNGER_SPRINT = TriggerReason.register("low_hunger_sprint", "Low-hunger sprint vibration", TriggerReason.ReasonScope.IN_WORLD);
    public static final TriggerReason MACE_SLAM = TriggerReason.register("mace_slam", "Mace ground-slam wind-up", TriggerReason.ReasonScope.IN_WORLD);
    public static final TriggerReason HOE_TILL = TriggerReason.register("hoe_till", "Hoe tilling", TriggerReason.ReasonScope.IN_WORLD);
    public static final TriggerReason SHEARS_CLIP = TriggerReason.register("shears_clip", "Shears clip", TriggerReason.ReasonScope.IN_WORLD);
    public static final TriggerReason SPAWN_EGG_USE = TriggerReason.register("spawn_egg_use", "Spawn egg use", TriggerReason.ReasonScope.IN_WORLD);
    public static final TriggerReason BOW_HOLD = TriggerReason.register("bow_hold", "Bow held baseline", TriggerReason.ReasonScope.IN_WORLD);
    public static final TriggerReason CROSSBOW_HOLD = TriggerReason.register("crossbow_hold", "Crossbow uncharged held baseline", TriggerReason.ReasonScope.IN_WORLD);
    public static final TriggerReason TOOL_FACING_BLOCK = TriggerReason.register("tool_facing_block", "Mining tool facing block hardness", TriggerReason.ReasonScope.IN_WORLD);
    public static final TriggerReason SWORD_HIT_IMPACT = TriggerReason.register("sword_hit_impact", "Sword hit impact (sound-driven)", TriggerReason.ReasonScope.IN_WORLD);
    public static final TriggerReason GOAT_HORN_TOOT = TriggerReason.register("goat_horn_toot", "Goat horn / instrument tooting", TriggerReason.ReasonScope.IN_WORLD);
    public static final TriggerReason DRINK_FIZZ = TriggerReason.register("drink_fizz", "Drink-potion fizz tail", TriggerReason.ReasonScope.IN_WORLD);
    public static final TriggerReason JUMP_ON_FOOT = TriggerReason.register("jump_on_foot", "Jump on foot", TriggerReason.ReasonScope.IN_WORLD);
    public static final TriggerReason FISHING_CAST = TriggerReason.register("fishing_cast", "Fishing rod cast", TriggerReason.ReasonScope.IN_WORLD);
    public static final TriggerReason FISHING_BITE = TriggerReason.register("fishing_bite", "Fishing rod bite", TriggerReason.ReasonScope.IN_WORLD);
    public static final TriggerReason FISHING_REEL = TriggerReason.register("fishing_reel", "Fishing rod reel-in resistance", TriggerReason.ReasonScope.IN_WORLD);
    public static final TriggerReason VEHICLE_RIDE = TriggerReason.register("vehicle_ride", "Boat / minecart ambient", TriggerReason.ReasonScope.IN_WORLD);
    public static final TriggerReason FOOTSTEP_SURFACE = TriggerReason.register("footstep_surface", "Footstep surface profile", TriggerReason.ReasonScope.IN_WORLD);
    public static final TriggerReason HORSE_JUMP_CHARGE = TriggerReason.register("horse_jump_charge", "Horse jump charge", TriggerReason.ReasonScope.IN_WORLD);
    public static final TriggerReason SHEARS_ENTITY_CLIP = TriggerReason.register("shears_clip_entity", "Shears clip on entity", TriggerReason.ReasonScope.IN_WORLD);
    public static final TriggerReason MINING_TOOL_SIGNATURE = TriggerReason.register("mining_tool_signature", "Mining tool signature overlay", TriggerReason.ReasonScope.IN_WORLD);
    public static final TriggerReason MOVE_STICKY = TriggerReason.register("move_sticky", "Sticky surface (slow-movement block)", TriggerReason.ReasonScope.IN_WORLD);
    public static final TriggerReason BLOCK_PLACE_HOLD = TriggerReason.register("block_place_hold", "Block place pre-emptive baseline", TriggerReason.ReasonScope.IN_WORLD);
    public static final TriggerReason MACE_SLAM_LAND = TriggerReason.register("mace_slam_land", "Mace slam landing impact", TriggerReason.ReasonScope.IN_WORLD);
    public static final TriggerReason ARROW_HIT = TriggerReason.register("arrow_hit_player", "Arrow hit on local player", TriggerReason.ReasonScope.IN_WORLD);
    public static final TriggerReason HORSE_JUMP_LIFT = TriggerReason.register("horse_jump_lift", "Horse jump lift-off", TriggerReason.ReasonScope.IN_WORLD);
    public static final TriggerReason PORTAL_PRE = TriggerReason.register("portal_pre", "Pre-portal swirl wind-up", TriggerReason.ReasonScope.IN_WORLD);

    private ControlifyTriggerReasons() {}

    public static FeedbackCategory categoryOf(TriggerReason reason) {
        if (reason == BOW_DRAW || reason == CROSSBOW_LOAD || reason == CROSSBOW_LOADED
                || reason == CROSSBOW_FIRE || reason == SWORD_SWING || reason == SWORD_CRIT
                || reason == SHIELD_HOLD || reason == SHIELD_PULSE || reason == BOW_RELEASE
                || reason == TRIDENT_CHARGE || reason == TRIDENT_RELEASE
                || reason == TOOL_BREAK_WARNING
                || reason == WEAPON_HOLD || reason == CROSSBOW_READY || reason == TRIDENT_HOLD
                || reason == MACE_SLAM || reason == MACE_SLAM_LAND
                || reason == BOW_HOLD || reason == CROSSBOW_HOLD || reason == SWORD_HIT_IMPACT
                || reason == ARROW_HIT) {
            return FeedbackCategory.COMBAT;
        }
        if (reason == TOOL_FACING_BLOCK || reason == MINING_TOOL_SIGNATURE) {
            return FeedbackCategory.ENVIRONMENT;
        }
        if (reason == UNDERWATER_MODIFIER || reason == ICE_MODIFIER
                || reason == MAGMA_MODIFIER || reason == LOW_HUNGER_SPRINT
                || reason == MOVE_STICKY || reason == PORTAL_PRE) {
            return FeedbackCategory.AMBIENT;
        }
        if (reason == SPYGLASS_HOLD || reason == GOAT_HORN_TOOT
                || reason == FISHING_CAST || reason == FISHING_BITE || reason == FISHING_REEL) {
            return FeedbackCategory.ITEMS;
        }
        if (reason == EATING || reason == EATING_CHEW || reason == DRINK_FIZZ) {
            return FeedbackCategory.ITEMS;
        }
        if (reason == ELYTRA_FIREWORK || reason == JUMP_ON_FOOT
                || reason == VEHICLE_RIDE || reason == FOOTSTEP_SURFACE
                || reason == HORSE_JUMP_CHARGE || reason == HORSE_JUMP_LIFT) {
            return FeedbackCategory.MOVEMENT;
        }
        if (reason == BLOCK_BREAK || reason == BLOCK_PLACE || reason == BLOCK_PLACE_HOLD
                || reason == BUCKET_USE || reason == BUCKET_LAVA
                || reason == HOE_TILL || reason == SHEARS_CLIP || reason == SHEARS_ENTITY_CLIP || reason == SPAWN_EGG_USE) {
            return FeedbackCategory.ENVIRONMENT;
        }
        return FeedbackCategory.MISC;
    }
}
