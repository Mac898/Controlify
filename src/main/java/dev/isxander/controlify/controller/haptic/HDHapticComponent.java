/*
 * Copyright (C) 2026 isXander
 * This file is part of Controlify.
 *
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.isxander.controlify.controller.haptic;

import com.mojang.blaze3d.audio.SoundBuffer;
import dev.isxander.controlify.config.settings.profile.HDHapticSettings;
import dev.isxander.controlify.controller.impl.ECSComponentImpl;
import dev.isxander.controlify.mixins.feature.hdhaptics.SoundBufferAccessor;
import dev.isxander.controlify.mixins.feature.hdhaptics.SoundEngineAccessor;
import dev.isxander.controlify.mixins.feature.hdhaptics.SoundManagerAccessor;
import dev.isxander.controlify.utils.CUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.RandomSource;

import javax.sound.sampled.AudioFormat;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public class HDHapticComponent extends ECSComponentImpl {
	public static final Identifier ID = CUtil.rl("hd_haptics");

	private Consumer<CompleteSoundData> playHapticConsumer;
	private final RandomSource randomSource;
	private final HDHapticDispatcher dispatcher;
	private final HDHapticTickHandler tickHandler;

	// the existing sound buffer library in the sound engine works on a ResourceProvider for registered sounds only
	// haptics are not sounds.
	private static final SoundBufferLibrary hapticBufferLibrary = new SoundBufferLibrary(Minecraft.getInstance().getResourceManager());
	private static final Map<Identifier, CompleteSoundData> hapticData = new HashMap<>();

	public HDHapticComponent() {
		this.randomSource = RandomSource.create();
		this.dispatcher = new HDHapticDispatcher(this);
		this.tickHandler = new HDHapticTickHandler(this);
	}

	public HDHapticDispatcher dispatcher() {
		return this.dispatcher;
	}

	public HDHapticTickHandler tickHandler() {
		return this.tickHandler;
	}

	public void playHaptic(Identifier haptic) {
		if (!settings().enabled || playHapticConsumer == null) return;

		getSoundData(haptic,  hapticBufferLibrary.getCompleteBuffer(haptic))
				.thenAccept(data -> {
					if (data != null) playHapticConsumer.accept(data);
				})
				.exceptionally(throwable -> {
					throwable.printStackTrace();
					return null;
				});
	}

	public void playHaptic(SoundEvent sound) {
		playHaptic(sound, 1f);
	}

	/** Play a vanilla SoundEvent's OGG content at a per-call gain — used by the dispatcher's
	 *  passthrough path so distance attenuation actually scales amplitude (previously the gain
	 *  was computed but ignored, making the spatial range a hard gate rather than a falloff). */
	public void playHaptic(SoundEvent sound, float gain) {
		if (!settings().enabled || playHapticConsumer == null) return;
		if (gain <= 0.001f) return;

		var resolved = Minecraft.getInstance().getSoundManager().getSoundEvent(sound.location());
		if (resolved == null) return;
		var picked = resolved.getSound(randomSource);
		if (picked == null) return;
		Identifier location = picked.getLocation();

		SoundManager soundManager = Minecraft.getInstance().getSoundManager();
		SoundEngine soundEngine = ((SoundManagerAccessor) soundManager).controlify$getSoundEngine();
		SoundBufferLibrary bufferLibrary = ((SoundEngineAccessor) soundEngine).controlify$getSoundBuffers();

		Identifier soundId = location.withPrefix("sounds/").withSuffix(".ogg");

		final float finalGain = gain;
		getSoundData(soundId, bufferLibrary.getCompleteBuffer(soundId))
				.thenAccept(data -> {
					if (data == null) return;
					if (finalGain >= 0.999f) {
						playHapticConsumer.accept(data);
					} else {
						byte[] scaled = HapticWaveform.scaleSamples(data.audio(), finalGain);
						playHapticConsumer.accept(new CompleteSoundData(scaled, data.format()));
					}
				})
				.exceptionally(throwable -> {
					throwable.printStackTrace();
					return null;
				});
	}

	public void playHaptic(byte[] pcm, AudioFormat format) {
		playHaptic(pcm, format, 1f);
	}

	/** Play raw PCM at a per-call gain in [0,1] — used by the dispatcher to apply linear-falloff
	 *  distance attenuation. Gain ≥ 0.999 is treated as a no-op so the common case bypasses scaling. */
	public void playHaptic(byte[] pcm, AudioFormat format, float gain) {
		if (!settings().enabled || playHapticConsumer == null) return;
		if (gain <= 0.001f) return;
		byte[] scaled = gain >= 0.999f ? pcm : HapticWaveform.scaleSamples(pcm, gain);
		playHapticConsumer.accept(new CompleteSoundData(scaled, format));
	}

	public void playHaptic(HapticWaveform waveform) {
		playHaptic(waveform.pcm(), waveform.format(), 1f);
	}

	public void playHaptic(HapticWaveform waveform, float gain) {
		playHaptic(waveform.pcm(), waveform.format(), gain);
	}

	public void acceptPlayHaptic(Consumer<CompleteSoundData> consumer) {
		this.playHapticConsumer = consumer;
	}

	private CompletableFuture<CompleteSoundData> getSoundData(Identifier id, CompletableFuture<SoundBuffer> sound) {
		return sound
				.thenApply(soundBuffer -> hapticData.computeIfAbsent(id, key -> {
					var accessor = (SoundBufferAccessor) soundBuffer;
					ByteBuffer bytes = accessor.controlify$getData();
					AudioFormat format = accessor.controlify$getFormat();

					if (bytes == null) {
						return null;
					}

					bytes.rewind();

					byte[] audio = new byte[bytes.remaining()];

					bytes.get(audio);

					return new CompleteSoundData(audio, format);
				}));
	}

	public HDHapticSettings settings() {
		return this.controller().settings().hdHaptic;
	}

	public HDHapticSettings defaultSettings() {
		return this.controller().defaultSettings().hdHaptic;
	}

	@Override
	public Identifier id() {
		return ID;
	}
}
