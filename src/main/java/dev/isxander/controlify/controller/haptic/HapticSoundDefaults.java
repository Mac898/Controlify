/*
 * Copyright (C) 2026 isXander
 * This file is part of Controlify.
 *
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.isxander.controlify.controller.haptic;

import dev.isxander.controlify.controller.FeedbackCategory;
import net.minecraft.core.Holder;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;

/**
 * Default sound→haptic mappings registered once at init. Covers the PR3 marquee:
 * footstep-per-surface, instrument passthrough, sword hit, block break/place.
 *
 * <p>Categorisation: footsteps and elytra/swim are MOVEMENT, attack sounds are COMBAT,
 * block break/place are ENVIRONMENT, instruments and bells are ITEMS.
 */
public final class HapticSoundDefaults {
	private HapticSoundDefaults() {}

	private static boolean registered = false;

	public static void registerAll() {
		if (registered) return;
		registered = true;

		// ---- Movement ----
		// Footsteps are synthesised per-material in feature.hdhaptics.footstep.EntityMixin so we
		// get reliable surface differentiation independent of the OGG content. No .step rule here.
		// Water-related player sounds (swim/splash) are also synthesised — see overlays section.

		// ---- Combat ----
		// Attack sounds are at the target entity's position, not the player's, so use spatial
		// attenuation rather than localOnly. 5-block range covers normal melee.
		HapticSoundRegistry.register(SoundEvents.PLAYER_ATTACK_STRONG, HapticProfile.spatial(FeedbackCategory.COMBAT, 0.9f, 5f));
		HapticSoundRegistry.register(SoundEvents.PLAYER_ATTACK_WEAK, HapticProfile.spatial(FeedbackCategory.COMBAT, 0.5f, 5f));
		HapticSoundRegistry.register(SoundEvents.PLAYER_ATTACK_CRIT, HapticProfile.spatial(FeedbackCategory.COMBAT, 1.0f, 5f));
		HapticSoundRegistry.register(SoundEvents.PLAYER_ATTACK_KNOCKBACK, HapticProfile.spatial(FeedbackCategory.COMBAT, 0.9f, 5f));
		HapticSoundRegistry.register(SoundEvents.PLAYER_ATTACK_SWEEP, HapticProfile.spatial(FeedbackCategory.COMBAT, 0.7f, 5f));
		HapticSoundRegistry.register(SoundEvents.PLAYER_ATTACK_NODAMAGE, HapticProfile.spatial(FeedbackCategory.COMBAT, 0.3f, 5f));

		// ---- Environment (block actions) ----
		// Block break / place — at the block position, attenuated by distance.
		HapticSoundRegistry.registerSuffix(".break", HapticProfile.spatial(FeedbackCategory.ENVIRONMENT, 0.7f, 6f));
		HapticSoundRegistry.registerSuffix(".place", HapticProfile.spatial(FeedbackCategory.ENVIRONMENT, 0.5f, 6f));

		// ---- Items / Instruments ----
		// Goat horn — full passthrough, the haptic actuator literally renders the note. All 8 variants.
		for (Holder.Reference<SoundEvent> variant : SoundEvents.GOAT_HORN_SOUND_VARIANTS) {
			HapticSoundRegistry.register(variant.value(), HapticProfile.of(FeedbackCategory.ITEMS));
		}

		// Bell — synth-only overlay for predictable routing and balance. The vanilla OGG mapping
		// routed unpredictably across the haptic grips on this firmware; the synth waveform is
		// mono and stays in the haptic band so it's symmetric across both grips.
		// (Registered later in the synth-overlays section.)

		// Note blocks: pattern rule removed in favour of explicit per-instrument synth overlays
		// (further down). The prefix rule was producing the "wrong trigger" feel for notes that
		// had stereo content in the OGG, and the volume curve was non-linear vs sine amplitude.

		// ---- Items / Eat-drink (passthrough so the food-eating sound appears in the actuator) ----
		HapticSoundRegistry.register(SoundEvents.GENERIC_EAT.value(), HapticProfile.localOnly(FeedbackCategory.ITEMS, 0.6f));
		HapticSoundRegistry.register(SoundEvents.GENERIC_DRINK.value(), HapticProfile.localOnly(FeedbackCategory.ITEMS, 0.6f));

		// ---- Items / Inventory feedback ----
		HapticSoundRegistry.register(SoundEvents.ITEM_PICKUP, HapticProfile.spatial(FeedbackCategory.UI, 0.3f, 4f));
		HapticSoundRegistry.register(SoundEvents.EXPERIENCE_ORB_PICKUP, HapticProfile.localOnly(FeedbackCategory.UI, 0.4f));
		HapticSoundRegistry.register(SoundEvents.PLAYER_LEVELUP, HapticProfile.localOnly(FeedbackCategory.UI, 1.0f));
		HapticSoundRegistry.register(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, HapticProfile.of(FeedbackCategory.UI));
		HapticSoundRegistry.register(SoundEvents.UI_BUTTON_CLICK.value(), HapticProfile.of(FeedbackCategory.UI));
		HapticSoundRegistry.register(SoundEvents.VILLAGER_YES, HapticProfile.spatial(FeedbackCategory.UI, 0.6f, 6f));

		// ---- Items / Stations ----
		HapticSoundRegistry.register(SoundEvents.ANVIL_USE, HapticProfile.spatial(FeedbackCategory.ITEMS, 1.0f, 6f));
		HapticSoundRegistry.register(SoundEvents.ANVIL_LAND, HapticProfile.spatial(FeedbackCategory.ITEMS, 1.0f, 8f));
		HapticSoundRegistry.register(SoundEvents.BREWING_STAND_BREW, HapticProfile.spatial(FeedbackCategory.ITEMS, 0.5f, 4f));
		HapticSoundRegistry.register(SoundEvents.BLASTFURNACE_FIRE_CRACKLE, HapticProfile.spatial(FeedbackCategory.ITEMS, 0.4f, 4f));

		// ---- Combat / Weapons (already covered: PLAYER_ATTACK_*) — add projectile and ambient ----
		// Spatial with tight radius — vanilla plays this sound at the arrow's resting position
		// (which is right next to the player on impact) but float-precision drift means the
		// localOnly check (0.5-block radius) can miss. 4-block spatial covers the arrow no matter
		// where it sticks while still attenuating distant hits.
		HapticSoundRegistry.register(SoundEvents.ARROW_HIT_PLAYER, HapticProfile.spatial(FeedbackCategory.COMBAT, 1.0f, 4f));
		// Synth overlay — sharp brown-noise burst + low thud so the user feels the arrow contact
		// even if the OGG content lacks haptic-band material. 4-block range matches the
		// passthrough so MP arrow hits on other players don't fire our actuator.
		HapticSoundRegistry.registerSpatialOverlay(SoundEvents.ARROW_HIT_PLAYER, FeedbackCategory.COMBAT, 4f, () -> {
			HapticWaveform thud = HapticWaveform.withASR(HapticWaveform.sine(35f, 100, 0.85f), 3, 30, 67);
			HapticWaveform burst = HapticWaveform.withASR(HapticWaveform.brownNoise(80, 0.45f), 2, 50, 28);
			return HapticWaveform.mix(thud, burst);
		});

		// ---- Environment / Weather ----
		HapticSoundRegistry.register(SoundEvents.LIGHTNING_BOLT_THUNDER, HapticProfile.spatial(FeedbackCategory.AMBIENT, 1.0f, 30f));
		HapticSoundRegistry.register(SoundEvents.LIGHTNING_BOLT_IMPACT, HapticProfile.spatial(FeedbackCategory.AMBIENT, 1.0f, 30f));

		// ---- Environment / Boss ----
		HapticSoundRegistry.register(SoundEvents.ENDER_DRAGON_GROWL, HapticProfile.spatial(FeedbackCategory.AMBIENT, 0.9f, 64f));
		HapticSoundRegistry.register(SoundEvents.ENDER_DRAGON_DEATH, HapticProfile.spatial(FeedbackCategory.AMBIENT, 1.0f, 128f));
		HapticSoundRegistry.register(SoundEvents.WITHER_SHOOT, HapticProfile.spatial(FeedbackCategory.AMBIENT, 0.7f, 16f));
		HapticSoundRegistry.register(SoundEvents.WITHER_DEATH, HapticProfile.spatial(FeedbackCategory.AMBIENT, 1.0f, 64f));

		// ---- Environment / Mobs (close-range only) ----
		HapticSoundRegistry.register(SoundEvents.PHANTOM_SWOOP, HapticProfile.spatial(FeedbackCategory.AMBIENT, 0.7f, 8f));
		HapticSoundRegistry.register(SoundEvents.SQUID_SQUIRT, HapticProfile.spatial(FeedbackCategory.AMBIENT, 0.5f, 5f));
		HapticSoundRegistry.register(SoundEvents.GLOW_SQUID_SQUIRT, HapticProfile.spatial(FeedbackCategory.AMBIENT, 0.5f, 5f));

		// ---- Environment / Mobility blocks ----
		HapticSoundRegistry.register(SoundEvents.SLIME_BLOCK_FALL, HapticProfile.spatial(FeedbackCategory.MOVEMENT, 0.8f, 4f));
		HapticSoundRegistry.register(SoundEvents.ENDERMAN_TELEPORT, HapticProfile.spatial(FeedbackCategory.AMBIENT, 0.4f, 6f));

		// ---- Environment / Beacons + conduit ambient sound (per-tick handler will add overlays) ----
		HapticSoundRegistry.register(SoundEvents.BEACON_ACTIVATE, HapticProfile.spatial(FeedbackCategory.AMBIENT, 0.7f, 16f));
		HapticSoundRegistry.register(SoundEvents.BEACON_DEACTIVATE, HapticProfile.spatial(FeedbackCategory.AMBIENT, 0.7f, 16f));
		HapticSoundRegistry.register(SoundEvents.CONDUIT_ACTIVATE, HapticProfile.spatial(FeedbackCategory.AMBIENT, 0.7f, 16f));
		HapticSoundRegistry.register(SoundEvents.CONDUIT_DEACTIVATE, HapticProfile.spatial(FeedbackCategory.AMBIENT, 0.7f, 16f));

		// ---- Environment / Portals ----
		HapticSoundRegistry.register(SoundEvents.END_PORTAL_FRAME_FILL, HapticProfile.spatial(FeedbackCategory.AMBIENT, 0.6f, 8f));
		HapticSoundRegistry.register(SoundEvents.END_PORTAL_SPAWN, HapticProfile.spatial(FeedbackCategory.AMBIENT, 1.0f, 16f));
		// Nether portal travel — long whoosh felt locally; ambient hum only at close range.
		HapticSoundRegistry.register(SoundEvents.PORTAL_TRAVEL, HapticProfile.localOnly(FeedbackCategory.AMBIENT, 0.8f));
		HapticSoundRegistry.register(SoundEvents.PORTAL_TRIGGER, HapticProfile.spatial(FeedbackCategory.AMBIENT, 0.7f, 6f));
		HapticSoundRegistry.register(SoundEvents.PORTAL_AMBIENT, HapticProfile.spatial(FeedbackCategory.AMBIENT, 0.15f, 4f));

		// ---- Combat / Projectile launches (used to be pure trigger feedback only) ----
		HapticSoundRegistry.register(SoundEvents.ARROW_SHOOT, HapticProfile.localOnly(FeedbackCategory.COMBAT, 0.7f));
		HapticSoundRegistry.register(SoundEvents.CROSSBOW_SHOOT, HapticProfile.localOnly(FeedbackCategory.COMBAT, 0.9f));
		HapticSoundRegistry.register(SoundEvents.TRIDENT_THROW.value(), HapticProfile.localOnly(FeedbackCategory.COMBAT, 0.9f));

		// ---- Items / Fishing (cast / bite / retrieve) ----
		HapticSoundRegistry.register(SoundEvents.FISHING_BOBBER_THROW, HapticProfile.localOnly(FeedbackCategory.ITEMS, 0.5f));
		HapticSoundRegistry.register(SoundEvents.FISHING_BOBBER_SPLASH, HapticProfile.spatial(FeedbackCategory.ITEMS, 1.0f, 16f));
		HapticSoundRegistry.register(SoundEvents.FISHING_BOBBER_RETRIEVE, HapticProfile.localOnly(FeedbackCategory.ITEMS, 0.4f));

		// ---- Items / Crafting take-results (loom / stonecutter / cartography / crafter) ----
		HapticSoundRegistry.register(SoundEvents.UI_LOOM_TAKE_RESULT, HapticProfile.of(FeedbackCategory.UI));
		HapticSoundRegistry.register(SoundEvents.UI_STONECUTTER_TAKE_RESULT, HapticProfile.of(FeedbackCategory.UI));
		HapticSoundRegistry.register(SoundEvents.UI_CARTOGRAPHY_TABLE_TAKE_RESULT, HapticProfile.of(FeedbackCategory.UI));
		HapticSoundRegistry.register(SoundEvents.CRAFTER_CRAFT, HapticProfile.spatial(FeedbackCategory.ITEMS, 0.6f, 8f));

		// ---- Items / Misc ----
		HapticSoundRegistry.register(SoundEvents.BONE_MEAL_USE, HapticProfile.spatial(FeedbackCategory.ITEMS, 0.5f, 4f));
		// Furnace fire crackle — keep low; it's an ambient drone, not a "done" event. Vanilla
		// doesn't ship a furnace-done sound; the closest we get is the ambient crackle while it
		// burns. The ambient loop is registered for a faint pass-through.
		HapticSoundRegistry.register(SoundEvents.FURNACE_FIRE_CRACKLE, HapticProfile.spatial(FeedbackCategory.AMBIENT, 0.3f, 4f));
		// Enchanting table use — felt richly while pages flip and the orbs swirl.
		HapticSoundRegistry.register(SoundEvents.ENCHANTMENT_TABLE_USE, HapticProfile.spatial(FeedbackCategory.ITEMS, 0.7f, 6f));
		HapticSoundRegistry.register(SoundEvents.EXPERIENCE_BOTTLE_THROW, HapticProfile.localOnly(FeedbackCategory.ITEMS, 0.5f));

		// ---- Movement / Vehicles ----
		// Horses — gallop / jump / land at the player position; passthrough so saddle pace
		// translates to the actuator. STEP variants share the .step rule already; HORSE_STEP_WOOD
		// and HORSE_STEP do not match the suffix exactly so register both explicitly.
		HapticSoundRegistry.register(SoundEvents.HORSE_GALLOP, HapticProfile.spatial(FeedbackCategory.MOVEMENT, 0.7f, 4f));
		HapticSoundRegistry.register(SoundEvents.HORSE_JUMP, HapticProfile.localOnly(FeedbackCategory.MOVEMENT, 0.8f));
		HapticSoundRegistry.register(SoundEvents.HORSE_LAND, HapticProfile.localOnly(FeedbackCategory.MOVEMENT, 0.9f));
		HapticSoundRegistry.register(SoundEvents.HORSE_STEP, HapticProfile.spatial(FeedbackCategory.MOVEMENT, 0.5f, 4f));
		HapticSoundRegistry.register(SoundEvents.HORSE_STEP_WOOD, HapticProfile.spatial(FeedbackCategory.MOVEMENT, 0.5f, 4f));
		// Minecarts — ambient ride loop felt while the player rides; clamps to local listener.
		HapticSoundRegistry.register(SoundEvents.MINECART_RIDING, HapticProfile.localOnly(FeedbackCategory.MOVEMENT, 0.4f));
		HapticSoundRegistry.register(SoundEvents.MINECART_INSIDE, HapticProfile.localOnly(FeedbackCategory.MOVEMENT, 0.4f));
		// Boats — paddle stroke is felt per-stroke, on land or in water.
		HapticSoundRegistry.register(SoundEvents.BOAT_PADDLE_LAND, HapticProfile.spatial(FeedbackCategory.MOVEMENT, 0.5f, 6f));
		HapticSoundRegistry.register(SoundEvents.BOAT_PADDLE_WATER, HapticProfile.spatial(FeedbackCategory.MOVEMENT, 0.4f, 6f));

		// ---- Items / Note-block instrument-specific tuning (synth-only) ----
		// Each instrument gets a distinct timbre and is amplitude-balanced perceptually — the
		// human haptic actuator's sensitivity is highly frequency-dependent (most sensitive
		// around 80-120 Hz, much less at 30 Hz), so equal-amplitude raw sines feel wildly
		// different. Lower-frequency instruments need higher amplitudes to read at parity.
		// Registered as synth-only (no passthrough) so amplitude is fully under our control.
		controlify$registerNoteSynth(SoundEvents.NOTE_BLOCK_BASEDRUM, 28f, 0.45f, 80, NoteShape.THUMP);
		controlify$registerNoteSynth(SoundEvents.NOTE_BLOCK_BASS, 35f, 0.40f, 80, NoteShape.PLUCK);
		controlify$registerNoteSynth(SoundEvents.NOTE_BLOCK_DIDGERIDOO, 40f, 0.35f, 120, NoteShape.PLUCK);
		controlify$registerNoteSynth(SoundEvents.NOTE_BLOCK_SNARE, 80f, 0.18f, 60, NoteShape.NOISE);
		controlify$registerNoteSynth(SoundEvents.NOTE_BLOCK_GUITAR, 70f, 0.22f, 70, NoteShape.PLUCK);
		controlify$registerNoteSynth(SoundEvents.NOTE_BLOCK_BANJO, 80f, 0.20f, 70, NoteShape.PLUCK);
		controlify$registerNoteSynth(SoundEvents.NOTE_BLOCK_HARP, 90f, 0.18f, 70, NoteShape.PLUCK);
		controlify$registerNoteSynth(SoundEvents.NOTE_BLOCK_PLING, 100f, 0.17f, 60, NoteShape.PLUCK);
		controlify$registerNoteSynth(SoundEvents.NOTE_BLOCK_BIT, 95f, 0.17f, 50, NoteShape.PLUCK);
		controlify$registerNoteSynth(SoundEvents.NOTE_BLOCK_COW_BELL, 110f, 0.15f, 80, NoteShape.PLUCK);
		controlify$registerNoteSynth(SoundEvents.NOTE_BLOCK_BELL, 110f, 0.15f, 80, NoteShape.PLUCK);
		controlify$registerNoteSynth(SoundEvents.NOTE_BLOCK_FLUTE, 100f, 0.13f, 80, NoteShape.PLUCK);
		controlify$registerNoteSynth(SoundEvents.NOTE_BLOCK_CHIME, 130f, 0.12f, 60, NoteShape.PLUCK);
		controlify$registerNoteSynth(SoundEvents.NOTE_BLOCK_XYLOPHONE, 130f, 0.12f, 50, NoteShape.PLUCK);
		controlify$registerNoteSynth(SoundEvents.NOTE_BLOCK_IRON_XYLOPHONE, 125f, 0.13f, 50, NoteShape.PLUCK);
		controlify$registerNoteSynth(SoundEvents.NOTE_BLOCK_HAT, 90f, 0.10f, 30, NoteShape.NOISE);

		// Bell — synth-only with linear-falloff distance attenuation (was 8-block passthrough).
		HapticSoundRegistry.registerSpatialOverlay(SoundEvents.BELL_BLOCK, FeedbackCategory.ITEMS, 8f, () -> {
			HapticWaveform strike = HapticWaveform.withASR(HapticWaveform.sine(110f, 80, 0.30f), 2, 50, 28);
			HapticWaveform tail = HapticWaveform.withASR(HapticWaveform.sine(95f, 200, 0.18f), 2, 60, 138);
			return HapticWaveform.concat(strike, tail);
		});
		HapticSoundRegistry.registerSpatialOverlay(SoundEvents.BELL_RESONATE, FeedbackCategory.ITEMS, 8f, () ->
				HapticWaveform.withASR(HapticWaveform.sine(85f, 600, 0.12f), 5, 200, 395));

		// ---- Synth overlays — fire alongside the passthrough mapping above ----
		// Levelup — three short ascending pings on top of the vanilla chime.
		HapticSoundRegistry.registerOverlay(SoundEvents.PLAYER_LEVELUP, FeedbackCategory.UI, () -> {
			HapticWaveform a = HapticWaveform.withASR(HapticWaveform.sine(80f, 60, 0.6f), 5, 30, 25);
			HapticWaveform gap = HapticWaveform.sine(40f, 60, 0f);
			HapticWaveform b = HapticWaveform.withASR(HapticWaveform.sine(110f, 60, 0.6f), 5, 30, 25);
			HapticWaveform c = HapticWaveform.withASR(HapticWaveform.sine(140f, 80, 0.7f), 5, 50, 25);
			return HapticWaveform.concat(a, gap, b, gap, c);
		});
		// Advancement — bright 4-note arpeggio overlay layered on the toast chime.
		HapticSoundRegistry.registerOverlay(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, FeedbackCategory.UI, () -> {
			HapticWaveform gap = HapticWaveform.sine(40f, 50, 0f);
			HapticWaveform n1 = HapticWaveform.withASR(HapticWaveform.sine(90f, 70, 0.55f), 5, 40, 25);
			HapticWaveform n2 = HapticWaveform.withASR(HapticWaveform.sine(120f, 70, 0.55f), 5, 40, 25);
			HapticWaveform n3 = HapticWaveform.withASR(HapticWaveform.sine(160f, 70, 0.55f), 5, 40, 25);
			HapticWaveform n4 = HapticWaveform.withASR(HapticWaveform.sine(200f, 90, 0.7f), 5, 60, 25);
			return HapticWaveform.concat(n1, gap, n2, gap, n3, gap, n4);
		});
		// Item pickup — sharp synth click on top of the soft passthrough so the user feels every grab.
		// 4-block range matches the passthrough so other players' nearby pickups are silent.
		HapticSoundRegistry.registerSpatialOverlay(SoundEvents.ITEM_PICKUP, FeedbackCategory.UI, 4f,
				() -> HapticWaveform.withASR(HapticWaveform.sine(140f, 30, 0.4f), 1, 12, 17));
		// Dragon roar — sub-bass AM throb under the vanilla growl, felt across the grips.
		// 64-block range matches the passthrough so distant growls fall off proportionately.
		HapticSoundRegistry.registerSpatialOverlay(SoundEvents.ENDER_DRAGON_GROWL, FeedbackCategory.AMBIENT, 64f, () -> {
			HapticWaveform carrier = HapticWaveform.sine(28f, 700, 0.85f);
			HapticWaveform mod = HapticWaveform.sine(2.5f, 700, 0.5f);
			return HapticWaveform.am(carrier, mod);
		});
		// Enchanting — rising 4-note arpeggio under the table-use sound. Plays each enchant click
		// (vanilla doesn't ship a separate "success" sound, so the during-sound is the trigger).
		// 6-block range — matches the passthrough so MP enchanting from afar doesn't fire.
		HapticSoundRegistry.registerSpatialOverlay(SoundEvents.ENCHANTMENT_TABLE_USE, FeedbackCategory.ITEMS, 6f, () -> {
			HapticWaveform gap = HapticWaveform.sine(40f, 35, 0f);
			HapticWaveform n1 = HapticWaveform.withASR(HapticWaveform.sine(80f, 60, 0.45f), 4, 35, 21);
			HapticWaveform n2 = HapticWaveform.withASR(HapticWaveform.sine(120f, 60, 0.5f), 4, 35, 21);
			HapticWaveform n3 = HapticWaveform.withASR(HapticWaveform.sine(150f, 60, 0.55f), 4, 35, 21);
			HapticWaveform n4 = HapticWaveform.withASR(HapticWaveform.sine(190f, 80, 0.65f), 4, 55, 21);
			return HapticWaveform.concat(n1, gap, n2, gap, n3, gap, n4);
		});

		// ---- Synth-only swim/splash (no passthrough — vanilla OGG leaked to the speaker) ----
		// These register *only* an overlay, no HapticProfile, so the dispatcher fires the synth
		// waveform and then early-returns when it doesn't find a profile. Pure sub-bass content
		// stays in the haptic actuator's native band and doesn't bleed into the speaker channel.
		// The waveforms ride on the vanilla sound's playtime (one event per stroke / splash) so
		// they're naturally synced to the visuals.
		// Swim stroke — a single low "whoosh": brown noise tail under a 35 Hz sine pulse. Quick
		// attack, longer decay so the user feels each stroke without the actuator droning.
		// Local-only so other players' swims don't trigger.
		HapticSoundRegistry.registerLocalOverlay(SoundEvents.PLAYER_SWIM, FeedbackCategory.MOVEMENT, () -> {
			HapticWaveform body = HapticWaveform.sine(35f, 200, 0.7f);
			HapticWaveform tail = HapticWaveform.brownNoise(180, 0.35f);
			return HapticWaveform.concat(
					HapticWaveform.withASR(body, 8, 60, 132),
					HapticWaveform.withASR(tail, 4, 40, 136)
			);
		});
		// Generic-entity swim — softer; the local-only gate still applies since vanilla plays
		// GENERIC_SWIM at the source entity's position.
		HapticSoundRegistry.registerLocalOverlay(SoundEvents.GENERIC_SWIM, FeedbackCategory.MOVEMENT, () -> {
			HapticWaveform body = HapticWaveform.sine(32f, 180, 0.55f);
			return HapticWaveform.withASR(body, 8, 60, 112);
		});
		// Splash — body slap + spray. 25 Hz half-cycle thud (impact) + brown-noise scatter (water).
		HapticSoundRegistry.registerLocalOverlay(SoundEvents.PLAYER_SPLASH, FeedbackCategory.MOVEMENT, () -> {
			HapticWaveform thud = HapticWaveform.withASR(HapticWaveform.sine(25f, 120, 0.85f), 3, 40, 77);
			HapticWaveform scatter = HapticWaveform.withASR(HapticWaveform.brownNoise(220, 0.5f), 5, 80, 135);
			return HapticWaveform.concat(thud, scatter);
		});
		// High-speed splash — bigger thud, sharper scatter, longer decay. Same shape, more energy.
		HapticSoundRegistry.registerLocalOverlay(SoundEvents.PLAYER_SPLASH_HIGH_SPEED, FeedbackCategory.MOVEMENT, () -> {
			HapticWaveform thud = HapticWaveform.withASR(HapticWaveform.sine(22f, 160, 1.0f), 4, 50, 106);
			HapticWaveform scatter = HapticWaveform.withASR(HapticWaveform.brownNoise(320, 0.65f), 6, 110, 204);
			return HapticWaveform.concat(thud, scatter);
		});
		HapticSoundRegistry.registerLocalOverlay(SoundEvents.GENERIC_SPLASH, FeedbackCategory.MOVEMENT, () -> {
			HapticWaveform thud = HapticWaveform.withASR(HapticWaveform.sine(28f, 100, 0.7f), 3, 35, 62);
			HapticWaveform scatter = HapticWaveform.withASR(HapticWaveform.brownNoise(180, 0.4f), 5, 70, 105);
			return HapticWaveform.concat(thud, scatter);
		});
	}

	private static void registerNoteBlock(Holder.Reference<SoundEvent> ref, float intensity) {
		HapticSoundRegistry.register(ref.value(), HapticProfile.spatial(FeedbackCategory.ITEMS, intensity, 6f));
	}

	/** Per-instrument timbre shape — different envelope and noise content per category. */
	private enum NoteShape { PLUCK, THUMP, NOISE }

	/** Register a synth-only overlay for a note-block instrument with predictable per-instrument
	 *  timbre. The frequency / amplitude / duration / shape parameters define the haptic, no
	 *  passthrough is registered so OGG content can't fight or overload the actuator. Uses an
	 *  8-block linear-falloff range so distant note blocks (other players' redstone music) fade
	 *  in proportion to the visual/audio range, rather than firing at full intensity. */
	private static void controlify$registerNoteSynth(Holder.Reference<SoundEvent> ref, float freq,
													float amp, int durMs, NoteShape shape) {
		HapticSoundRegistry.registerSpatialOverlay(ref.value(), FeedbackCategory.ITEMS, 8f, () -> {
			switch (shape) {
				case THUMP: {
					// Quick attack, longer body — drum-like.
					return HapticWaveform.withASR(HapticWaveform.sine(freq, durMs, amp),
							3, durMs - 30, 27);
				}
				case PLUCK: {
					// Plucked decay — fast attack, short sustain, long-ish release.
					int sustain = Math.max(10, durMs / 4);
					int release = Math.max(20, durMs - 5 - sustain);
					return HapticWaveform.withASR(HapticWaveform.sine(freq, durMs, amp),
							5, sustain, release);
				}
				case NOISE: {
					// Snare / hat — pink-noise body with a short body sine to anchor the pitch.
					HapticWaveform body = HapticWaveform.withASR(
							HapticWaveform.sine(freq, durMs, amp * 0.6f), 2, durMs / 3, durMs - 5 - durMs / 3);
					HapticWaveform noise = HapticWaveform.withASR(
							HapticWaveform.pinkNoise(durMs, amp * 0.7f), 2, durMs / 4, durMs - 5 - durMs / 4);
					return HapticWaveform.mix(body, noise);
				}
				default:
					return HapticWaveform.sine(freq, durMs, amp);
			}
		});
	}
}
