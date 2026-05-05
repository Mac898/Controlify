package dev.isxander.controlify.config.dto.profile;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.Map;

public record ProfileConfig(
        GenericControllerConfig generic,
        InputConfig input,
        RumbleConfig rumble,
        HDHapticConfig hdHaptic,
        GyroConfig gyro,
        BluetoothDeviceConfig bluetoothDevice,
        DualSenseConfig dualSense
) {
    public static final Codec<ProfileConfig> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            GenericControllerConfig.CODEC.fieldOf("generic").forGetter(ProfileConfig::generic),
            InputConfig.CODEC.fieldOf("input").forGetter(ProfileConfig::input),
            RumbleConfig.CODEC.fieldOf("rumble").forGetter(ProfileConfig::rumble),
            HDHapticConfig.CODEC.fieldOf("hd_haptic").forGetter(ProfileConfig::hdHaptic),
            GyroConfig.CODEC.fieldOf("gyro").forGetter(ProfileConfig::gyro),
            BluetoothDeviceConfig.CODEC.fieldOf("bluetooth_device").forGetter(ProfileConfig::bluetoothDevice),
            DualSenseConfig.CODEC.optionalFieldOf("dualsense", new DualSenseConfig(true, 1.0f, Map.of(), true)).forGetter(ProfileConfig::dualSense)
    ).apply(instance, ProfileConfig::new));
}
