/*
 * Copyright (C) 2026 isXander
 * This file is part of Controlify.
 *
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.isxander.controlify.driver.sdl;

import dev.isxander.controlify.Controlify;
import dev.isxander.controlify.controller.ControllerEntity;
import dev.isxander.controlify.controller.battery.BatteryLevelComponent;
import dev.isxander.controlify.controller.battery.PowerState;
import dev.isxander.controlify.controller.dualsense.DualSenseFeatureSupport;
import dev.isxander.controlify.controller.dualsense.DualsenseComponent;
import dev.isxander.controlify.controller.haptic.CompleteSoundData;
import dev.isxander.controlify.controller.haptic.HDHapticComponent;
import dev.isxander.controlify.controller.id.ControllerType;
import dev.isxander.controlify.controller.info.DriverNameComponent;
import dev.isxander.controlify.controller.info.GUIDComponent;
import dev.isxander.controlify.controller.info.UIDComponent;
import dev.isxander.controlify.controller.led.LEDComponent;
import dev.isxander.controlify.controller.misc.BluetoothDeviceComponent;
import dev.isxander.controlify.controller.rumble.RumbleComponent;
import dev.isxander.controlify.controller.rumble.TriggerRumbleComponent;
import dev.isxander.controlify.driver.Driver;
import dev.isxander.controlify.driver.dualsense.DualsenseEffectsState;
import dev.isxander.controlify.driver.dualsense.DualsenseTriggerEffect;
import dev.isxander.controlify.rumble.RumbleState;
import dev.isxander.controlify.rumble.TriggerRumbleState;
import dev.isxander.controlify.utils.CUtil;
import dev.isxander.controlify.utils.log.ControlifyLogger;
import dev.isxander.sdl.*;
import net.minecraft.util.Util;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.sound.sampled.AudioFormat;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static dev.isxander.sdl.SdlAudio.*;
import static dev.isxander.sdl.SdlGamepad.*;
import static dev.isxander.sdl.SdlInit.*;
import static dev.isxander.sdl.SdlProperties.*;

public abstract class SDLCommonDriver<SdlController> implements Driver {
	private static final int AUDIO_STREAM_TIMEOUT_TICKS = 5 * 60 * 60 * 20; // 5 minutes

	protected final Sdl sdl;
	private final ControlifyLogger logger;

	protected final Arena arena;

	protected SdlController ptrController;

	protected BatteryLevelComponent batteryLevelComponent;
	protected RumbleComponent rumbleComponent;
	protected TriggerRumbleComponent triggerRumbleComponent;
	protected HDHapticComponent hdHapticComponent;
	protected LEDComponent ledComponent;
	protected DualsenseComponent dualSenseComponent;

	protected final boolean isRumbleSupported, isTriggerRumbleSupported;
	protected final boolean isDualsense;
	protected final boolean isRGBLedSupported;

	protected final SdlGuid guid;
	protected final String guidString;
	protected final @Nullable String serial;
	protected final @NotNull String name;
	protected final SdlPropertiesId props;
	protected final short vendorId, productId;
	protected final SDLJoystickConnectionState connectionState;

	@Nullable protected SdlAudioDeviceId dualsenseAudioDev;
	@Nullable protected SdlAudioSpec dualsenseAudioSpec;
	protected final List<AudioStreamHandle> dualsenseAudioHandles;

	public SDLCommonDriver(Sdl sdl, SdlController ptrController, SdlJoystickId jid, ControllerType type, ControlifyLogger logger) {
		this.sdl = sdl;
		this.arena = Arena.ofConfined();

		this.ptrController = ptrController;
		this.logger = logger;

		this.props = SDL_GetControllerProperties(ptrController);

		this.name = Optional.ofNullable(SDL_GetControllerName(ptrController))
			.orElse("SDL Controller");

		this.guid = SDL_GetControllerGUIDForID(jid);
		this.guidString = sdl.guid().SDL_GUIDToString(guid);
		logger.debugLog("SDL GUID: {}", guidString);

		this.serial = SDL_GetControllerSerial(ptrController);
		logger.debugLog("SDL Serial: {}", serial);

		this.vendorId = SDL_GetControllerVendor(ptrController);
		this.productId = SDL_GetControllerProduct(ptrController);
		logger.debugLog("SDL VID: {} PID: {}", vendorId, productId);

		this.connectionState = SDLJoystickConnectionState.fromInt(SDL_GetControllerConnectionState(ptrController));
		logger.debugLog("SDL Connection State: {}", connectionState);

		this.isRumbleSupported = sdl.properties().SDL_GetBooleanProperty(props, SDL_PROP_GAMEPAD_CAP_RUMBLE_BOOLEAN, false);
		this.isTriggerRumbleSupported = sdl.properties().SDL_GetBooleanProperty(props, SDL_PROP_GAMEPAD_CAP_TRIGGER_RUMBLE_BOOLEAN, false);
		this.isRGBLedSupported = sdl.properties().SDL_GetBooleanProperty(props, SDL_PROP_GAMEPAD_CAP_RGB_LED_BOOLEAN, false);

		DecodedGUID decodedGuid = DecodedGUID.fromGUID(this.guid);
		logger.log("SDL GUID driver signature: {}", decodedGuid.getDriverHint());

		// open audio device for dualsense hd haptics
		this.dualsenseAudioHandles = new ArrayList<>();

		this.isDualsense = CUtil.rl("dualsense").equals(type.namespace());
		if (this.isDualsense) {
			logger.debugLog("DualSense controller detected.");

			// macOS HD haptics are broken
			if (Util.getPlatform() != Util.OS.OSX && sdl.init().SDL_WasInit(SDL_INIT_AUDIO) != 0) {
				SdlAudioDeviceId dualsenseAudioDev = null;
				SdlAudio.SdlAudioSpecRef devSpec = null;

				logger.log("DualSense HD Haptics: probing SDL audio playback devices.");
				for (SdlAudioDeviceId dev : sdl.audio().SDL_GetAudioPlaybackDevices()) {
					String name = sdl.audio().SDL_GetAudioDeviceName(dev);
					SdlAudio.SdlAudioSpecRef probeSpec = new SdlAudio.SdlAudioSpecRef();
					sdl.audio().SDL_GetAudioDeviceFormat(dev, probeSpec, new SdlRefs.IntRef());
					logger.log("DualSense HD Haptics: device='{}' channels={} freq={} fmt={}",
							name, probeSpec.value.channels(), probeSpec.value.frequency(), probeSpec.value.format());

					String lower = name.toLowerCase();
					if ((lower.contains("dualsense") || lower.contains("ps5") || lower.contains("wireless controller"))
							&& probeSpec.value.channels() == 4) {
						dualsenseAudioDev = dev;
						devSpec = probeSpec;
						break;
					}
				}

				if (dualsenseAudioDev != null) {
					logger.log("DualSense HD Haptics audio device found.");
					this.dualsenseAudioSpec = devSpec.value;
					this.dualsenseAudioDev = sdl.audio().SDL_OpenAudioDevice(dualsenseAudioDev, this.dualsenseAudioSpec);
				} else {
					logger.log("DualSense HD Haptics audio device not found (no 4-channel match in the list above).");
				}
			}
		}
	}

	@Override
	public void addComponents(ControllerEntity controller) {
		controller.setComponent(new DriverNameComponent(this.name));
		controller.setComponent(new GUIDComponent(this.guidString));
		controller.setComponent(new UIDComponent(createUid()));

		controller.setComponent(this.batteryLevelComponent = new BatteryLevelComponent());
		if (this.isRumbleSupported) {
			controller.setComponent(this.rumbleComponent = new RumbleComponent());
		}
		if (this.isTriggerRumbleSupported) {
			controller.setComponent(this.triggerRumbleComponent = new TriggerRumbleComponent());
		}
		if (this.isRGBLedSupported) {
			controller.setComponent(this.ledComponent = new LEDComponent(1));
		}
		if (this.isDualsense) {
			DualSenseFeatureSupport support = new DualSenseFeatureSupport(
					true,
					this.dualsenseAudioDev != null,
					true,
					this.dualsenseAudioDev != null,
					isBluetooth()
			);
			controller.setComponent(this.dualSenseComponent = new DualsenseComponent(support));
		}
		if (this.dualsenseAudioDev != null) {
			controller.setComponent(this.hdHapticComponent = new HDHapticComponent());
			this.hdHapticComponent.acceptPlayHaptic(this::playHaptic);
		}

		if (isBluetooth()) {
			controller.setComponent(new BluetoothDeviceComponent());
		}
	}

	@Override
	public void update(ControllerEntity controller, boolean outOfFocus) {
		if (ptrController == null) {
			throw new IllegalStateException("Tried to update controller when it's closed.");
		}

		updateRumble();
		updateBatteryLevel();
		updateLED();
		updateDualSense();
		updateHDHaptic();
	}

	@Override
	public void close() {
		if (ptrController == null) {
			throw new IllegalStateException("Tried to close controller when it's already closed.");
		}

		if (dualSenseComponent != null) {
			dualSenseComponent.setLeftTriggerEffect(DualsenseTriggerEffect.Off.INSTANCE);
			dualSenseComponent.setRightTriggerEffect(DualsenseTriggerEffect.Off.INSTANCE);
			dualSenseComponent.setMuteLight(false);
			updateDualSense();
		}

		SDL_CloseController(ptrController);
		ptrController = null;

		if (dualsenseAudioDev != null) {
			sdl.audio().SDL_CloseAudioDevice(dualsenseAudioDev);
			dualsenseAudioDev = null;
			for (AudioStreamHandle handle : dualsenseAudioHandles) {
				handle.close();
			}
		}

		arena.close();
	}

	protected void updateRumble() {
		if (isRumbleSupported) {
			Optional<RumbleState> stateOpt = this.rumbleComponent.consumeRumble();

			// On a DualSense, the voice-coil actuators that produce HD haptics are the same
			// hardware that produces rumble. Sending both simultaneously superimposes them and
			// feels muddy. When HD haptics is enabled and the user hasn't disabled the suppress
			// toggle, drop the rumble call (state is already consumed so it won't accumulate).
			if (!shouldSuppressRumble()) {
				stateOpt.ifPresent(state -> {
					if (!SDL_RumbleController(ptrController, state.strong(), state.weak(), 5000)) {
						CUtil.LOGGER.error("Could not rumble gamepad: {}", sdl.error().SDL_GetError());
					}
				});
			}
		}

		if (isTriggerRumbleSupported) {
			Optional<TriggerRumbleState> stateOpt = this.triggerRumbleComponent.consumeTriggerRumble();

			stateOpt.ifPresent(state -> {
				if (!SDL_RumbleControllerTriggers(ptrController, state.left(), state.right(), 0)) {
					CUtil.LOGGER.error("Could not rumble triggers gamepad: {}", sdl.error().SDL_GetError());
				}
			});
		}
	}

	private void updateBatteryLevel() {
		SdlRefs.IntRef percent = new SdlRefs.IntRef();
		int powerState = SDL_GetControllerPowerInfo(ptrController, percent);

		PowerState level = switch (powerState) {
			case SDL_POWERSTATE_ERROR, SDL_POWERSTATE_UNKNOWN -> new PowerState.Unknown();
			case SDL_POWERSTATE_ON_BATTERY -> new PowerState.Depleting(percent.value);
			case SDL_POWERSTATE_NO_BATTERY -> new PowerState.WiredOnly();
			case SDL_POWERSTATE_CHARGING -> new PowerState.Charging(percent.value);
			case SDL_POWERSTATE_CHARGED -> new PowerState.Full();
			default -> throw new IllegalStateException("Unexpected value");
		};

		this.batteryLevelComponent.setBatteryLevel(level);
	}

	private void updateLED() {
		if (ledComponent == null) return;

		if (ledComponent.consumeDirty()) {
			int color = ledComponent.get(0); // SDL only supports one LED

			byte red = (byte) ((color >> 16) & 0xFF);
			byte green = (byte) ((color >> 8) & 0xFF);
			byte blue = (byte) (color & 0xFF);

			if (!SDL_SetControllerLED(ptrController, red, green, blue)) {
				logger.error("Could not set controller LED: {}", sdl.error().SDL_GetError());
			} else {
				logger.debugLog("Set controller LED to color: R={}, G={}, B={}", red, green, blue);
			}
		}
	}

	private void updateDualSense() {
		if (dualSenseComponent == null) return;
		if (!this.dualSenseComponent.consumeDirty()) return;

		DualsenseEffectsState effectsState = new DualsenseEffectsState();

		Optional.ofNullable(this.dualSenseComponent.getLeftTriggerEffect()).ifPresent(effect -> {
			effectsState.ucEnableBits1 |= DualsenseEffectsState.EnableBitFlags1.ALLOW_LEFT_TRIGGER_FFB;
			effectsState.rgucLeftTriggerEffect = effect.createState();
		});

		Optional.ofNullable(this.dualSenseComponent.getRightTriggerEffect()).ifPresent(effect -> {
			effectsState.ucEnableBits1 |= DualsenseEffectsState.EnableBitFlags1.ALLOW_RIGHT_TRIGGER_FFB;
			effectsState.rgucRightTriggerEffect = effect.createState();
		});

		DualsenseComponent.MuteLightMode muteMode = this.dualSenseComponent.getMuteLightMode();
		if (muteMode != null) {
			effectsState.ucEnableBits2 |= DualsenseEffectsState.EnableBitFlags2.ALLOW_MUTE_LIGHT;
			effectsState.ucMicLightMode = muteMode.toJnaValue();
		}

		int playerMask = this.dualSenseComponent.getPlayerIndicatorMask();
		if (playerMask >= 0) {
			effectsState.ucEnableBits2 |= DualsenseEffectsState.EnableBitFlags2.ALLOW_PLAYER_INDICATORS;
			effectsState.ucPadLights = (byte) playerMask;
			effectsState.ucLedBrightness = this.dualSenseComponent.getPlayerIndicatorBrightness();
		}

		if (this.dualSenseComponent.hasAudioMuteState()) {
			effectsState.ucEnableBits2 |= DualsenseEffectsState.EnableBitFlags2.ALLOW_AUDIO_MUTE;
			byte muteBits = 0;
			if (this.dualSenseComponent.isMicMuted()) muteBits |= 0x1;
			if (this.dualSenseComponent.isSpeakerMuted()) muteBits |= 0x2;
			effectsState.ucAudioMuteBits = muteBits;
		}

		int speakerVol = this.dualSenseComponent.getSpeakerVolume();
		if (speakerVol >= 0) {
			effectsState.ucEnableBits1 |= DualsenseEffectsState.EnableBitFlags1.ALLOW_SPEAKER_VOLUME;
			effectsState.ucSpeakerVolume = (byte) (0x3D + Math.round((speakerVol / 100.0f) * (0x64 - 0x3D)));
		}

		int headphoneVol = this.dualSenseComponent.getHeadphoneVolume();
		if (headphoneVol >= 0) {
			effectsState.ucEnableBits1 |= DualsenseEffectsState.EnableBitFlags1.ALLOW_HEADPHONE_VOLUME;
			effectsState.ucHeadphoneVolume = (byte) Math.round((headphoneVol / 100.0f) * 0x7F);
		}

		if (this.dualSenseComponent.isHapticLowPass()) {
			effectsState.ucEnableBits2 |= DualsenseEffectsState.EnableBitFlags2.ALLOW_HAPTIC_LOW_PASS;
		}

		if (this.dualSenseComponent.consumeResetLights()) {
			effectsState.ucEnableBits2 |= DualsenseEffectsState.EnableBitFlags2.RESET_LIGHTS;
		}

		try (Arena arena = Arena.ofConfined()) {
			MemorySegment memory = arena.allocate(DualsenseEffectsState.LAYOUT);
			effectsState.writeTo(memory);
			SDL_SendControllerEffect(ptrController, memory.asByteBuffer());
		}
	}

	private void updateHDHaptic() {
		for (int i = 0; i < dualsenseAudioHandles.size(); i++) {
			AudioStreamHandle handle = dualsenseAudioHandles.get(i);
			if (handle.isTimedOut()) {
				handle.close();
				dualsenseAudioHandles.remove(handle);
			} else {
				handle.tick();
			}
		}
	}

	private void playHaptic(CompleteSoundData sound) {
		if (sound == null) return;
		if (ptrController == null || dualsenseAudioDev == null || dualsenseAudioSpec == null) {
			return;
		}

		int format = -1;
		int ss = sound.format().getSampleSizeInBits();
		int byteSs = ss / 8;
		AudioFormat.Encoding encoding = sound.format().getEncoding();
		if (ss == 8) {
			if (encoding == AudioFormat.Encoding.PCM_SIGNED) {
				format = SDL_AUDIO_S8;
			} else if (encoding == AudioFormat.Encoding.PCM_UNSIGNED) {
				format = SDL_AUDIO_U8;
			}
		} else if (sound.format().isBigEndian()) {
			format = audioFmtEndian(ss, encoding, SDL_AUDIO_S16BE, SDL_AUDIO_S32BE, SDL_AUDIO_F32BE);
		} else {
			format = audioFmtEndian(ss, encoding, SDL_AUDIO_S16LE, SDL_AUDIO_S32LE, SDL_AUDIO_F32LE);
		}

		if (format == -1) {
			throw new IllegalStateException("Unsupported format");
		}

		SdlAudioSpec spec = new SdlAudioSpec(
			(int) sound.format().getSampleRate(),
			format,
			sound.format().getChannels()
		);

		AudioStreamHandle handle = null;
		for (AudioStreamHandle stream : dualsenseAudioHandles) {
			SdlAudioSpec streamSpec = stream.getSpec();
			if (streamSpec.format() == spec.format()
				&& streamSpec.frequency() == spec.frequency()
				&& streamSpec.channels() == spec.channels()
				&& !stream.isInUse()
			) {
				handle = stream;
				break;
			}
		}
		// Multiply before dividing so short audio (< 1 tick of total throughput) doesn't truncate
		// to 0 ticks. With the bad floor, isInUse() flipped to false right after queueing — so
		// rapid one-shots queued onto the same stream sequentially instead of spawning parallel
		// streams, breaking additive playback (e.g. rapid note-block clicks).
		int byteRate = spec.frequency() * spec.channels() * byteSs;
		int length = byteRate <= 0
				? 0
				: (int) Math.ceil((double) (sound.audio().length * 20L) / byteRate);
		length = Math.max(1, length);

		if (handle != null) {
			handle.queueAudio(sound.audio(), length);
		} else {
			if (dualsenseAudioHandles.size() >= 16) {
				dualsenseAudioHandles.removeFirst().close();
			}

			AudioStreamHandle newHandle = AudioStreamHandle.createWithAudio(sdl, dualsenseAudioDev, spec, dualsenseAudioSpec, sound.audio(), length);
			dualsenseAudioHandles.add(newHandle);
		}
	}

	protected String createUid() {
		int identifiers = 0;
		List<byte[]> bytes = new ArrayList<>();

		// IMPORTANT: the order of these identifiers are important, as they are passed through a hash function
		// rearranging the order will result in a different UID

		// add vendor and product id if available
		if (vendorId != 0 && productId != 0) {
			bytes.add(new byte[] {
					(byte) (vendorId >> 8), (byte) vendorId,
					(byte) (productId >> 8), (byte) productId
			});
			identifiers++;
		}

		// add serial if available - even with different drivers, serials should remain constant, if provided
		if (this.serial != null) {
			bytes.add(this.serial.getBytes());
			identifiers++;
		}

		if (identifiers == 0) {
			// if no other providers are available, use the GUID
			// the GUID is prone to changing quite a bit, so it's not a good identifier
			bytes.add(this.guid.data().clone());
		}

		String uid = CUtil.createUIDFromBytes(bytes.toArray(new byte[0][]));

		String nonDuplicateUid = uid;
		int duplicateCount = (int) Controlify.instance().getControllerManager().orElseThrow()
				.getConnectedControllers()
				.stream()
				.filter(controller -> controller.uid().startsWith(nonDuplicateUid))
				.count();
		if (duplicateCount > 0) {
			uid += "-" + duplicateCount;
		}

		return uid;
	}

	protected boolean isBluetooth() {
		return connectionState == SDLJoystickConnectionState.WIRELESS;
	}

	/**
	 * True when the simple rumble path should be skipped because HD haptics is rendering on the
	 * same physical hardware (DualSense voice coils). Only applies when HD haptics is actually
	 * available and active for this controller — it never turns off rumble for non-DualSense
	 * controllers, which would have nothing else to fall back to.
	 */
	private boolean shouldSuppressRumble() {
		if (hdHapticComponent == null) return false;
		var settings = hdHapticComponent.settings();
		return settings.enabled && settings.suppressRumble;
	}

	protected abstract SdlPropertiesId SDL_GetControllerProperties(SdlController ptrController);
	protected abstract String SDL_GetControllerName(SdlController ptrController);
	protected abstract SdlGuid SDL_GetControllerGUIDForID(SdlJoystickId jid);
	protected abstract @Nullable String SDL_GetControllerSerial(SdlController ptrController);
	protected abstract short SDL_GetControllerVendor(SdlController ptrController);
	protected abstract short SDL_GetControllerProduct(SdlController ptrController);
	//@MagicConstant(valuesFromClass = SDL_JoystickConnectionState.class)
	protected abstract int SDL_GetControllerConnectionState(SdlController ptrController);
	protected abstract boolean SDL_CloseController(SdlController ptrController);
	protected abstract boolean SDL_RumbleController(SdlController ptrController, float strong, float weak, int durationMs);
	protected abstract boolean SDL_RumbleControllerTriggers(SdlController ptrController, float left, float right, int durationMs);
	//@MagicConstant(valuesFromClass = SDL_PowerState.class)
	protected abstract int SDL_GetControllerPowerInfo(SdlController ptrController, SdlRefs.IntRef percent);
	protected abstract boolean SDL_SendControllerEffect(SdlController ptrController, ByteBuffer effect);
	protected abstract boolean SDL_SetControllerLED(SdlController ptrController, byte red, byte green, byte blue);

	private static int audioFmtEndian(int ss, AudioFormat.Encoding encoding, int signed16, int signed32, int float32) {
		if (ss == 16) {
			if (encoding == AudioFormat.Encoding.PCM_SIGNED) {
				return signed16;
			}
		} else if (ss == 32) {
			if (encoding == AudioFormat.Encoding.PCM_SIGNED) {
				return signed32;
			} else if (encoding == AudioFormat.Encoding.PCM_FLOAT) {
				return float32;
			}
		}
		return -1;
	}

	protected static class AudioStreamHandle {
		private int streamLastPlayed;
		private final Sdl sdl;
		private final SdlAudioStreamHandle stream;
		private final SdlAudioSpec spec;
		private final int[] channelMap;

		private AudioStreamHandle(Sdl sdl, SdlAudioStreamHandle stream, SdlAudioSpec spec, int[] channelMap) {
			this.sdl = sdl;
			this.stream = stream;
			this.spec = spec;
			this.channelMap = channelMap;
			this.streamLastPlayed = 0;
		}

		public void queueAudio(byte[] audio, int tickLength) {
			try (Arena arena = Arena.ofConfined()) {
				MemorySegment memory = arena.allocateFrom(ValueLayout.JAVA_BYTE, audio);

				// Re-apply the output channel map before every queue. SDL3 has been observed to drop it
				// after the stream's queue drains; without this, mono haptic data spills onto the
				// controller speaker (channels 0/1) on the next reuse.
				sdl.audio().SDL_SetAudioStreamOutputChannelMap(stream, channelMap);
				sdl.audio().SDL_PutAudioStreamData(stream, memory.asByteBuffer());

				streamLastPlayed = Math.min(0, streamLastPlayed);
				streamLastPlayed -= tickLength;
			}
		}

		public SdlAudioSpec getSpec() {
			return this.spec;
		}

		public boolean isInUse() {
			return streamLastPlayed < 0;
		}

		public boolean isTimedOut() {
			return streamLastPlayed >= AUDIO_STREAM_TIMEOUT_TICKS;
		}

		public void tick() {
			streamLastPlayed++;
		}

		public void close() {
			sdl.audio().SDL_DestroyAudioStream(stream);
		}

		public static AudioStreamHandle createWithAudio(Sdl sdl, SdlAudioDeviceId device, SdlAudioSpec audioSpec, SdlAudioSpec devSpec, byte[] audio, int tickLength) {
			SdlAudioStreamHandle stream = sdl.audio().SDL_CreateAudioStream(audioSpec, devSpec);

			if (!sdl.audio().SDL_BindAudioStream(device, stream)) {
				throw SDLException.useSDLError(sdl, "binding audio stream");
			}

			int[] channelMap = switch (audioSpec.channels()) {
				case 1 -> new int[]{ -1, -1, 0, 0 };
				case 2 -> new int[]{ -1, -1, 0, 1 };
				default -> throw new IllegalStateException("Unsupported channel count " + audioSpec.channels());
			};
			if (!sdl.audio().SDL_SetAudioStreamOutputChannelMap(stream, channelMap)) {
				throw SDLException.useSDLError(sdl, "setting channel map");
			}

			var handle = new AudioStreamHandle(sdl, stream, audioSpec, channelMap);
			handle.queueAudio(audio, tickLength);
			return handle;
		}
	}
}
