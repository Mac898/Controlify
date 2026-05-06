package dev.isxander.controlify.controller.haptic;

import dev.isxander.controlify.controller.FeedbackCategory;
import dev.isxander.controlify.utils.CUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.CompassItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Per-tick gameplay haptics that aren't sound-driven: heartbeat at low HP, elytra wind drone,
 * compass direction pulse. One instance per {@link HDHapticComponent}; ticked from
 * {@code Controlify.tick()} alongside the dispatcher.
 */
public final class HDHapticTickHandler {
    private static final Identifier ELYTRA_WIND_LOOP = CUtil.rl("elytra_wind");
    private static final Identifier UNDERWATER_LOOP = CUtil.rl("underwater");
    private static final Identifier RAIN_LOOP = CUtil.rl("rain");
    private static final Identifier MAGMA_LOOP = CUtil.rl("magma");
    private static final Identifier HONEY_LOOP = CUtil.rl("honey");

    private final HDHapticComponent component;

    private static final Identifier ALTITUDE_WIND_LOOP = CUtil.rl("altitude_wind");
    private static final Identifier MINECART_RIDE_LOOP = CUtil.rl("minecart_ride");
    private static final Identifier BOAT_RIDE_LOOP = CUtil.rl("boat_ride");
    private static final Identifier PORTAL_PRE_LOOP = CUtil.rl("portal_pre");
    private static final Identifier HORSE_GALLOP_LOOP = CUtil.rl("horse_gallop");

    private int heartbeatCooldown = 0;
    private int compassCooldown = 0;
    private int conduitCooldown = 0;
    private int beeCooldown = 0;
    private int beaconCooldown = 0;
    /** Last world-time slot we fired the clock click at (in 12000-tick increments — noon / midnight). */
    private long lastClockTimeSlot = Long.MIN_VALUE;
    /** Tracks the player's current dimension key as a string so we can detect dimension changes. */
    private @org.jetbrains.annotations.Nullable net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> lastDimension = null;
    private boolean elytraWindActive = false;
    private boolean underwaterActive = false;
    private boolean rainActive = false;
    private boolean magmaActive = false;
    private boolean honeyActive = false;
    private boolean altitudeWindActive = false;
    private boolean minecartRideActive = false;
    private boolean boatRideActive = false;
    private boolean portalPreActive = false;
    /** Ticks the player has been standing in a portal block — drives the swirl crescendo. */
    private int portalPreTicks = 0;
    /** Set when a dimension change fires while inside a portal block; suppresses the pre-portal
     *  loop until the player leaves the destination portal block. Without this the loop keeps
     *  going on the destination side because the player typically spawns inside a portal block. */
    private boolean portalPreSuppressed = false;
    private boolean horseGallopActive = false;
    /** Last tick we saw the horse airborne — triggers a jump landing thump on transition. */
    private boolean horseAirborneLast = false;

    public HDHapticTickHandler(HDHapticComponent component) {
        this.component = component;
    }

    public void tick() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            stopElytraWindIfActive();
            return;
        }

        var settings = component.settings();
        if (!settings.enabled) {
            stopElytraWindIfActive();
            return;
        }

        if (settings.isCategoryEnabled(FeedbackCategory.COMBAT)) {
            tickHeartbeat(player);
        }
        if (settings.isCategoryEnabled(FeedbackCategory.ITEMS)) {
            tickCompass(player);
            tickClock(player);
        }
        if (settings.isCategoryEnabled(FeedbackCategory.MOVEMENT)) {
            tickElytraWind(player);
            tickMinecartRide(player);
            tickBoatRide(player);
            tickHorseRide(player);
        } else {
            stopElytraWindIfActive();
            stopRideLoops();
        }

        if (settings.isCategoryEnabled(FeedbackCategory.AMBIENT)) {
            tickUnderwater(player);
            tickRain(player);
            tickConduit(player);
            tickMagma(player);
            tickHoney(player);
            tickBeeBuzz(player);
            tickAltitudeWind(player);
            tickBeaconProximity(player);
            tickPortalPreTransport(player);
            tickDimensionSweep(player);
        } else {
            stopAmbientLoops();
        }
    }

    /** Builds an AM-modulated swirl loop that fades in slowly while the player stands in a
     *  portal block, crescendoing right at the dimension-change moment (~80 ticks = 4 s, matching
     *  vanilla's nether-portal cooldown). Resets when the player leaves. The dimension-sweep
     *  handler ({@link #tickDimensionSweep}) handles the actual transition and suppresses this
     *  loop until the player leaves the destination portal block. */
    private void tickPortalPreTransport(LocalPlayer player) {
        var feet = player.level().getBlockState(player.blockPosition());
        boolean inPortal = feet.is(net.minecraft.world.level.block.Blocks.NETHER_PORTAL)
                || feet.is(net.minecraft.world.level.block.Blocks.END_PORTAL);

        // Clear suppression as soon as the player leaves the portal block — re-entry should
        // crescendo again from scratch.
        if (!inPortal) {
            portalPreSuppressed = false;
        }

        if (inPortal && !portalPreSuppressed) {
            portalPreTicks = Math.min(80, portalPreTicks + 1); // 4-second cap
            if (!portalPreActive) {
                HapticWaveform carrier = HapticWaveform.sine(40f, 1000, 0.85f);
                HapticWaveform mod = HapticWaveform.sine(2.5f, 1000, 0.7f);
                HapticWaveform am = HapticWaveform.am(carrier, mod);
                component.dispatcher().startLoop(PORTAL_PRE_LOOP, new HapticLoop(am, 80));
                portalPreActive = true;
            }
            HapticLoop loop = component.dispatcher().getLoop(PORTAL_PRE_LOOP);
            if (loop != null) {
                // Slow fade-in over the 4-second portal cooldown. Squared so the back end is
                // dramatic — early seconds are barely felt, the last second peaks, then the
                // dimension-sweep takes over at the transition.
                float ramp = Math.min(1f, (float) portalPreTicks / 80f);
                loop.setAmplitude(ramp * ramp * intensity());
            }
        } else {
            if (portalPreActive) {
                component.dispatcher().stopLoop(PORTAL_PRE_LOOP);
                portalPreActive = false;
            }
            portalPreTicks = 0;
        }
    }

    /** Fire a long sweep waveform when the player crosses a dimension boundary, and suppress
     *  the portal pre-transport loop until the player leaves the destination portal block. */
    private void tickDimensionSweep(LocalPlayer player) {
        var current = player.level().dimension();
        if (lastDimension == null) {
            lastDimension = current;
            return;
        }
        if (current.equals(lastDimension)) return;
        lastDimension = current;

        // Stop the pre-portal loop and prevent it from re-engaging on the destination side
        // (where the player typically spawns still inside a portal block).
        if (portalPreActive) {
            component.dispatcher().stopLoop(PORTAL_PRE_LOOP);
            portalPreActive = false;
        }
        portalPreTicks = 0;
        portalPreSuppressed = true;

        // Sweep: low rumble that AM-modulates faster as it ends, on top of any portal-travel
        // passthrough already firing. Long enough to feel like the dimension transition itself.
        HapticWaveform carrier = HapticWaveform.sine(45f, 1200, 0.8f * intensity());
        HapticWaveform mod = HapticWaveform.sine(2f, 1200, 0.6f);
        component.playHaptic(HapticWaveform.withASR(HapticWaveform.am(carrier, mod), 60, 900, 240));
    }

    /** Synthesized double-thump that accelerates as HP drops below 6. */
    private void tickHeartbeat(LocalPlayer player) {
        float health = player.getHealth();
        if (health > 6f || health <= 0f) {
            heartbeatCooldown = 0;
            return;
        }
        if (heartbeatCooldown > 0) {
            heartbeatCooldown--;
            return;
        }
        // Two short low-frequency thumps separated by a 200ms silence.
        HapticWaveform thump = HapticWaveform.withASR(
                HapticWaveform.sine(50f, 80, 0.85f * intensity()), 10, 50, 60);
        HapticWaveform gap = HapticWaveform.sine(50f, 200, 0f);
        component.playHaptic(HapticWaveform.concat(thump, gap, thump));

        // Period: 1000ms at HP 6 → 400ms at HP 0. (1000 - 100*(6-HP), floor 400.)
        int periodMs = Math.max(400, 1000 - 100 * Math.round(6f - health));
        heartbeatCooldown = Math.max(8, periodMs / 50);
    }

    /** Continuous sine pulse on the actuator — frequency rises with view-to-spawn alignment;
     *  amplitude scales with alignment too, so pointing 90° away is faint and pointing 180° away
     *  is silent. Per-tick fresh chunks let parameters update smoothly. Background-level only
     *  so it doesn't drown out foreground haptics like sword swings.
     *
     *  <p>Tracks the lodestone target if the held compass has a {@code LodestoneTracker} component;
     *  otherwise falls back to the world spawn / respawn point for plain compasses. */
    private void tickCompass(LocalPlayer player) {
        ItemStack main = player.getMainHandItem();
        ItemStack off = player.getOffhandItem();
        boolean mainIsCompass = main.getItem() instanceof CompassItem;
        boolean offIsCompass = off.getItem() instanceof CompassItem;
        if (!mainIsCompass && !offIsCompass) return;
        // Off-hand compass is even quieter than main-hand so it doesn't dominate when the player
        // is doing something else with the main hand (sword swing, mining).
        ItemStack compass = mainIsCompass ? main : off;
        float handMultiplier = mainIsCompass ? 1.0f : 0.5f;

        BlockPos targetPos = controlify$resolveCompassTarget(player, compass);
        if (targetPos == null) return;

        Vec3 toTarget = Vec3.atCenterOf(targetPos).subtract(player.position());
        Vec3 view = player.getViewVector(1f);
        Vec3 toTargetH = horizontalNormalized(toTarget);
        Vec3 viewH = horizontalNormalized(view);
        if (toTargetH == null || viewH == null) return;

        double dot = toTargetH.dot(viewH);            // -1 (opposite) … 1 (aligned)
        double normalised = (dot + 1.0) / 2.0;       // 0 (opposite) … 1 (aligned)
        // Curve fades hard near the back so opposite-side is silent, with a gentle build to facing.
        double alignment = Math.pow(normalised, 2.0);
        if (alignment < 0.05) return;

        // Background-only volume: cap at 0.18 (was 0.6) so it doesn't compete with foreground.
        float freq = (float) (30.0 + 80.0 * alignment);          // 30..110 Hz (was 30..140)
        float amp = (float) (0.04 + 0.14 * alignment) * intensity() * handMultiplier;
        if (amp <= 0.001f) return;

        HapticWaveform pulse = HapticWaveform.sine(freq, 50, amp);
        component.playHaptic(pulse);
    }

    /** Resolve where a compass should point: lodestone target if the stack has a tracker with a
     *  same-dimension target, otherwise the world spawn / respawn point. Returns null if there's
     *  no resolvable target (e.g. lodestone tracker pointing to another dimension). */
    private static @org.jetbrains.annotations.Nullable BlockPos controlify$resolveCompassTarget(LocalPlayer player, ItemStack compass) {
        net.minecraft.world.item.component.LodestoneTracker tracker =
                compass.get(net.minecraft.core.component.DataComponents.LODESTONE_TRACKER);
        if (tracker != null && tracker.target().isPresent()) {
            net.minecraft.core.GlobalPos global = tracker.target().get();
            // Only point if the lodestone is in the same dimension as the player; otherwise the
            // compass spins randomly in vanilla, so we stay silent.
            if (!global.dimension().equals(player.level().dimension())) return null;
            return global.pos();
        }
        var respawn = player.level().getRespawnData();
        if (respawn == null) return null;
        return respawn.pos();
    }

    /** Per-tick brown-noise burst with speed-dependent amplitude and random gust modulation.
     *  Each tick generates a fresh ~50ms chunk so stopping the wind is instant: as soon as the
     *  player lands, no more chunks are queued and the residual is one tick (50ms). The fresh
     *  noise per tick + amplitude jitter gives the "more random" feel.
     */
    private void tickElytraWind(LocalPlayer player) {
        if (!player.isFallFlying()) {
            elytraWindActive = false;
            return;
        }
        Vec3 v = player.getDeltaMovement();
        float speed = (float) v.length();
        // Don't even start until the player is actually moving — gentle glides at <1.0 m/tick are
        // basically silent. Maps speed in [1.0, 4.5] m/tick → amp [0, 1], so high-speed dives
        // saturate while typical glides feel light and proportionate.
        float threshold = 1.0f;
        float ceiling = 4.5f;
        if (speed <= threshold) {
            elytraWindActive = false;
            return;
        }
        float baseAmp = Math.min(1f, (speed - threshold) / (ceiling - threshold));
        // Random gust factor 0.7..1.05 per tick — gives the wind a turbulent feel.
        float gust = 0.7f + 0.35f * (float) Math.random();
        float amp = baseAmp * gust * intensity();
        if (amp <= 0.001f) return;
        elytraWindActive = true;
        HapticWaveform chunk = HapticWaveform.brownNoise(50, amp);
        component.playHaptic(chunk);
    }

    private void stopElytraWindIfActive() {
        // Per-tick chunk approach: nothing to release. Just clear the active flag so the next tick
        // doesn't push a fresh chunk.
        elytraWindActive = false;
    }

    /** Low-frequency AM-modulated hum while submerged. */
    private void tickUnderwater(LocalPlayer player) {
        boolean active = player.isUnderWater();
        if (active && !underwaterActive) {
            HapticWaveform carrier = HapticWaveform.sine(35f, 1000, 0.5f);
            HapticWaveform mod = HapticWaveform.sine(0.3f, 1000, 0.5f);
            HapticWaveform am = HapticWaveform.am(carrier, mod);
            component.dispatcher().startLoop(UNDERWATER_LOOP, new HapticLoop(am, 80));
            underwaterActive = true;
        } else if (!active && underwaterActive) {
            component.dispatcher().stopLoop(UNDERWATER_LOOP);
            underwaterActive = false;
        }
        HapticLoop loop = component.dispatcher().getLoop(UNDERWATER_LOOP);
        if (loop != null) loop.setAmplitude(0.5f * intensity());
    }

    /** Pink-noise loop while it's raining and the player has open sky overhead. */
    private void tickRain(LocalPlayer player) {
        Level level = player.level();
        boolean active = level.isRaining() && level.canSeeSky(player.blockPosition());
        if (active && !rainActive) {
            HapticWaveform rain = HapticWaveform.pinkNoise(500, 0.5f);
            component.dispatcher().startLoop(RAIN_LOOP, new HapticLoop(rain, 40));
            rainActive = true;
        } else if (!active && rainActive) {
            component.dispatcher().stopLoop(RAIN_LOOP);
            rainActive = false;
        }
        HapticLoop loop = component.dispatcher().getLoop(RAIN_LOOP);
        if (loop != null) {
            loop.setAmplitude(level.getRainLevel(1f) * 0.4f * intensity());
        }
    }

    /** A single 100Hz pulse every ~2 seconds while the player has Conduit Power. */
    private void tickConduit(LocalPlayer player) {
        if (!player.hasEffect(MobEffects.CONDUIT_POWER)) {
            conduitCooldown = 0;
            return;
        }
        if (conduitCooldown > 0) {
            conduitCooldown--;
            return;
        }
        HapticWaveform pulse = HapticWaveform.withASR(
                HapticWaveform.sine(100f, 60, 0.5f * intensity()), 8, 30, 22);
        component.playHaptic(pulse);
        conduitCooldown = 40; // ~2 seconds
    }

    /** 90Hz throb at 4Hz while standing on a magma block. */
    private void tickMagma(LocalPlayer player) {
        boolean active = player.onGround() && player.level().getBlockState(player.blockPosition().below()).is(Blocks.MAGMA_BLOCK);
        if (active && !magmaActive) {
            HapticWaveform carrier = HapticWaveform.sine(90f, 1000, 0.6f);
            HapticWaveform mod = HapticWaveform.sine(4f, 1000, 0.5f);
            HapticWaveform am = HapticWaveform.am(carrier, mod);
            component.dispatcher().startLoop(MAGMA_LOOP, new HapticLoop(am, 60));
            magmaActive = true;
        } else if (!active && magmaActive) {
            component.dispatcher().stopLoop(MAGMA_LOOP);
            magmaActive = false;
        }
        HapticLoop loop = component.dispatcher().getLoop(MAGMA_LOOP);
        if (loop != null) loop.setAmplitude(0.7f * intensity());
    }

    /** 25Hz constant low while standing on a honey block. */
    private void tickHoney(LocalPlayer player) {
        boolean active = player.onGround() && player.level().getBlockState(player.blockPosition().below()).is(Blocks.HONEY_BLOCK);
        if (active && !honeyActive) {
            HapticWaveform low = HapticWaveform.sine(25f, 1000, 0.4f);
            component.dispatcher().startLoop(HONEY_LOOP, new HapticLoop(low, 80));
            honeyActive = true;
        } else if (!active && honeyActive) {
            component.dispatcher().stopLoop(HONEY_LOOP);
            honeyActive = false;
        }
        HapticLoop loop = component.dispatcher().getLoop(HONEY_LOOP);
        if (loop != null) loop.setAmplitude(0.4f * intensity());
    }

    private void stopAmbientLoops() {
        if (underwaterActive) { component.dispatcher().stopLoop(UNDERWATER_LOOP); underwaterActive = false; }
        if (rainActive)       { component.dispatcher().stopLoop(RAIN_LOOP);       rainActive = false; }
        if (magmaActive)      { component.dispatcher().stopLoop(MAGMA_LOOP);      magmaActive = false; }
        if (honeyActive)      { component.dispatcher().stopLoop(HONEY_LOOP);      honeyActive = false; }
        if (altitudeWindActive) { component.dispatcher().stopLoop(ALTITUDE_WIND_LOOP); altitudeWindActive = false; }
        if (portalPreActive)  { component.dispatcher().stopLoop(PORTAL_PRE_LOOP);  portalPreActive = false; portalPreTicks = 0; }
        conduitCooldown = 0;
        beeCooldown = 0;
        beaconCooldown = 0;
    }

    private void stopRideLoops() {
        if (minecartRideActive) { component.dispatcher().stopLoop(MINECART_RIDE_LOOP); minecartRideActive = false; }
        if (boatRideActive)     { component.dispatcher().stopLoop(BOAT_RIDE_LOOP);     boatRideActive = false; }
        if (horseGallopActive)  { component.dispatcher().stopLoop(HORSE_GALLOP_LOOP);  horseGallopActive = false; }
        horseAirborneLast = false;
    }

    /** Per-tick gallop pattern + jump landing thump while riding any AbstractHorse-like vehicle.
     *  The vanilla HORSE_GALLOP sound fires too rarely (one chunk every ~half-second of motion);
     *  here we run a loop that pulses every ~12 ticks scaled with horizontal speed, plus a discrete
     *  thump when the horse goes airborne→ground. */
    private void tickHorseRide(LocalPlayer player) {
        var vehicle = player.getVehicle();
        boolean onHorse = vehicle instanceof net.minecraft.world.entity.animal.equine.AbstractHorse;
        if (!onHorse) {
            if (horseGallopActive) {
                component.dispatcher().stopLoop(HORSE_GALLOP_LOOP);
                horseGallopActive = false;
            }
            horseAirborneLast = false;
            return;
        }
        net.minecraft.world.entity.animal.equine.AbstractHorse horse =
                (net.minecraft.world.entity.animal.equine.AbstractHorse) vehicle;
        var v = horse.getDeltaMovement();
        double hSpeed = Math.sqrt(v.x * v.x + v.z * v.z);

        // Detect landing: was airborne, now on ground.
        boolean airborne = !horse.onGround();
        if (horseAirborneLast && !airborne) {
            // Landing thump — bigger if the horse fell further. Vanilla HORSE_LAND fires too, but
            // its sound content varies, so synthesise a guaranteed punch on top.
            float landAmp = (float) Math.min(1.0, 0.4 + horse.fallDistance / 4.0);
            component.playHaptic(HapticWaveform.withASR(
                    HapticWaveform.sine(35f, 130, 0.85f * landAmp * intensity()), 4, 50, 76));
        }
        horseAirborneLast = airborne;

        // Gallop loop — only while on the ground and moving. Saw-tooth carrier × low-freq AM
        // gives the four-beat hoof feel; loop is only ~50ms so amplitude tracks speed responsively.
        if (!airborne && hSpeed > 0.05) {
            if (!horseGallopActive) {
                HapticWaveform carrier = HapticWaveform.sine(45f, 200, 0.7f);
                HapticWaveform mod = HapticWaveform.sine(8f, 200, 0.7f);
                HapticWaveform am = HapticWaveform.am(carrier, mod);
                component.dispatcher().startLoop(HORSE_GALLOP_LOOP, new HapticLoop(am, 50));
                horseGallopActive = true;
            }
            HapticLoop loop = component.dispatcher().getLoop(HORSE_GALLOP_LOOP);
            if (loop != null) {
                // Speed normalised: walking ~0.1, trotting ~0.25, gallop ~0.5+ m/tick.
                float amp = (float) Math.min(1.0, hSpeed / 0.5);
                loop.setAmplitude(amp * 0.55f * intensity());
            }
        } else if (horseGallopActive) {
            component.dispatcher().stopLoop(HORSE_GALLOP_LOOP);
            horseGallopActive = false;
        }
    }

    /** Brown-noise wind drone above Y≈200 (mountain peaks / high altitude). Amplitude grows with altitude. */
    private void tickAltitudeWind(LocalPlayer player) {
        // Scale 200..320 → 0..1 (320 is just above the world build limit of 320 in 1.21+).
        double y = player.getY();
        boolean active = y > 200 && !player.isUnderWater();
        if (active && !altitudeWindActive) {
            HapticWaveform drone = HapticWaveform.brownNoise(800, 0.5f);
            component.dispatcher().startLoop(ALTITUDE_WIND_LOOP, new HapticLoop(drone, 60));
            altitudeWindActive = true;
        } else if (!active && altitudeWindActive) {
            component.dispatcher().stopLoop(ALTITUDE_WIND_LOOP);
            altitudeWindActive = false;
        }
        HapticLoop loop = component.dispatcher().getLoop(ALTITUDE_WIND_LOOP);
        if (loop != null) {
            float amp = (float) Math.min(1.0, Math.max(0.0, (y - 200) / 120.0));
            loop.setAmplitude(amp * 0.6f * intensity());
        }
    }

    /** Cheap-as-possible per-2s scan for an active beacon nearby; pulses softly while close. */
    private void tickBeaconProximity(LocalPlayer player) {
        if (beaconCooldown > 0) {
            beaconCooldown--;
            return;
        }
        beaconCooldown = 40; // ~2 seconds
        Level level = player.level();
        // Scan a small box centred on the player — beacons are visible from far away but proximity
        // haptics should be felt only at close range (~16 blocks). Cheap iteration on block entities.
        AABB box = player.getBoundingBox().inflate(16.0);
        BlockPos pmin = BlockPos.containing(box.minX, box.minY, box.minZ);
        BlockPos pmax = BlockPos.containing(box.maxX, box.maxY, box.maxZ);
        double bestDistSq = Double.MAX_VALUE;
        boolean foundActive = false;
        for (BlockPos pos : BlockPos.betweenClosed(pmin, pmax)) {
            if (!level.getBlockState(pos).is(Blocks.BEACON)) continue;
            // We don't have a public getter for beacon level on the BE; treat any beacon as a source
            // (vanilla beacons are nearly always active when placed).
            double dx = pos.getX() + 0.5 - player.getX();
            double dy = pos.getY() + 0.5 - player.getY();
            double dz = pos.getZ() + 0.5 - player.getZ();
            double d2 = dx * dx + dy * dy + dz * dz;
            if (d2 < bestDistSq) {
                bestDistSq = d2;
                foundActive = true;
            }
        }
        if (!foundActive) return;
        double dist = Math.sqrt(bestDistSq);
        float proximity = (float) Math.max(0, 1.0 - dist / 16.0);
        if (proximity < 0.05f) return;
        HapticWaveform pulse = HapticWaveform.withASR(
                HapticWaveform.sine(70f, 80, 0.4f * proximity * intensity()), 6, 50, 24);
        component.playHaptic(pulse);
    }

    /** Clock midday/midnight click — fires once when the world time crosses a 12000-tick boundary
     *  (noon = 6000, midnight = 18000) while the player holds a clock. */
    private void tickClock(LocalPlayer player) {
        ItemStack main = player.getMainHandItem();
        ItemStack off = player.getOffhandItem();
        boolean holdingClock = main.is(Items.CLOCK) || off.is(Items.CLOCK);
        if (!holdingClock) {
            lastClockTimeSlot = Long.MIN_VALUE;
            return;
        }
        long dayTime = player.level().getOverworldClockTime();
        long timeOfDay = ((dayTime % 24000L) + 24000L) % 24000L;
        // Slot 0 = midnight (18000..23999 + 0..5999 reads as a "midnight" half), slot 1 = noon.
        // Detect transitions exactly at noon (6000) and midnight (18000).
        long slot = timeOfDay / 12000L; // 0 for the morning-into-afternoon half, 1 for evening-into-morning
        if (lastClockTimeSlot == Long.MIN_VALUE) {
            lastClockTimeSlot = slot;
            return;
        }
        if (slot == lastClockTimeSlot) return;
        lastClockTimeSlot = slot;
        // Different click for noon (bright) vs midnight (dull).
        boolean isNoon = slot == 0; // we just crossed 0 — was 1 before, meaning we hit 6000
        // Note: the slot transition happens at 6000 (noon) and 18000 (midnight). Pick a freq.
        float freq = isNoon ? 200f : 90f;
        component.playHaptic(HapticWaveform.withASR(
                HapticWaveform.sine(freq, 60, 0.5f * intensity()), 3, 30, 27));
    }

    /** Faint vibration drone while the player is riding a minecart. */
    private void tickMinecartRide(LocalPlayer player) {
        boolean active = player.getVehicle() instanceof net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
        if (active && !minecartRideActive) {
            HapticWaveform rumble = HapticWaveform.brownNoise(400, 0.4f);
            component.dispatcher().startLoop(MINECART_RIDE_LOOP, new HapticLoop(rumble, 50));
            minecartRideActive = true;
        } else if (!active && minecartRideActive) {
            component.dispatcher().stopLoop(MINECART_RIDE_LOOP);
            minecartRideActive = false;
        }
        HapticLoop loop = component.dispatcher().getLoop(MINECART_RIDE_LOOP);
        if (loop != null) {
            // Amplitude scales with the cart's speed, so straight rails are quieter than
            // bumpy descents. fallDistance heuristic isn't right; use horizontal velocity.
            var v = player.getVehicle().getDeltaMovement();
            double speed = Math.sqrt(v.x * v.x + v.z * v.z);
            float amp = (float) Math.min(1.0, speed / 0.5);
            loop.setAmplitude(amp * 0.4f * intensity());
        }
    }

    /** Slow wave drone while riding a boat — water rocking the hull. */
    private void tickBoatRide(LocalPlayer player) {
        boolean active = player.getVehicle() instanceof net.minecraft.world.entity.vehicle.boat.AbstractBoat;
        if (active && !boatRideActive) {
            HapticWaveform carrier = HapticWaveform.sine(35f, 1000, 0.5f);
            HapticWaveform mod = HapticWaveform.sine(0.8f, 1000, 0.5f);
            HapticWaveform am = HapticWaveform.am(carrier, mod);
            component.dispatcher().startLoop(BOAT_RIDE_LOOP, new HapticLoop(am, 60));
            boatRideActive = true;
        } else if (!active && boatRideActive) {
            component.dispatcher().stopLoop(BOAT_RIDE_LOOP);
            boatRideActive = false;
        }
        HapticLoop loop = component.dispatcher().getLoop(BOAT_RIDE_LOOP);
        if (loop != null) loop.setAmplitude(0.35f * intensity());
    }

    /** Saw × AM buzz when a Bee entity is within ~4 blocks of the player. Re-checked every 4 ticks. */
    private void tickBeeBuzz(LocalPlayer player) {
        if (beeCooldown > 0) {
            beeCooldown--;
            return;
        }
        beeCooldown = 4; // ~200ms — keeps the entity scan cheap
        var box = player.getBoundingBox().inflate(4.0);
        var bees = player.level().getEntitiesOfClass(net.minecraft.world.entity.animal.bee.Bee.class, box);
        if (bees.isEmpty()) return;
        double minDistSq = Double.MAX_VALUE;
        for (var bee : bees) {
            double d = player.distanceToSqr(bee);
            if (d < minDistSq) minDistSq = d;
        }
        double dist = Math.sqrt(minDistSq);
        float proximity = (float) Math.max(0, 1.0 - dist / 4.0);
        if (proximity < 0.05f) return;

        // 220Hz saw × 35Hz sine modulator gives the angry-buzzing-insect feel.
        HapticWaveform carrier = HapticWaveform.saw(220f, 200, 0.5f * proximity * intensity());
        HapticWaveform mod = HapticWaveform.sine(35f, 200, 0.7f);
        component.playHaptic(HapticWaveform.am(carrier, mod));
    }

    private float intensity() {
        return component.settings().synthesisIntensity * component.settings().masterIntensity;
    }

    private static Vec3 horizontalNormalized(Vec3 v) {
        double lenSq = v.x * v.x + v.z * v.z;
        if (lenSq < 1e-6) return null;
        double len = Math.sqrt(lenSq);
        return new Vec3(v.x / len, 0, v.z / len);
    }
}
