package dev.isxander.controlify.config.dto.profile;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.isxander.controlify.controller.gyro.GyroButtonMode;
import dev.isxander.controlify.controller.gyro.GyroYawMode;

import java.util.Optional;

public record GyroConfig(
        float lookSensitivityX,
        float lookSensitivityY,
        boolean relativeMode,
        boolean invertPitch,
        boolean invertYaw,
        GyroButtonMode buttonMode,
        GyroYawMode yawMode,
        boolean flickStick
) {
    public static final Codec<GyroConfig> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            // Legacy single-axis sensitivity field — read for backwards compatibility, never
            // written. Older configs only stored {@code look_sensitivity}; if the new per-axis
            // fields are absent we fall back to this. The forGetter returns empty so toDTO
            // serialisation never writes the legacy key.
            Codec.FLOAT.optionalFieldOf("look_sensitivity").forGetter(c -> Optional.<Float>empty()),
            Codec.FLOAT.optionalFieldOf("look_sensitivity_x").forGetter(c -> Optional.of(c.lookSensitivityX)),
            Codec.FLOAT.optionalFieldOf("look_sensitivity_y").forGetter(c -> Optional.of(c.lookSensitivityY)),
            Codec.BOOL.fieldOf("relative_mode").forGetter(GyroConfig::relativeMode),
            Codec.BOOL.fieldOf("invert_pitch").forGetter(GyroConfig::invertPitch),
            Codec.BOOL.fieldOf("invert_yaw").forGetter(GyroConfig::invertYaw),
            GyroButtonMode.CODEC.fieldOf("button_mode").forGetter(GyroConfig::buttonMode),
            GyroYawMode.CODEC.fieldOf("yaw_mode").forGetter(GyroConfig::yawMode),
            Codec.BOOL.fieldOf("flick_stick").forGetter(GyroConfig::flickStick)
    ).apply(instance, (legacy, optX, optY, relative, invertPitch, invertYaw, buttonMode, yawMode, flick) -> {
        float fallback = legacy.orElse(1.0f);
        float x = optX.orElse(fallback);
        float y = optY.orElse(fallback);
        return new GyroConfig(x, y, relative, invertPitch, invertYaw, buttonMode, yawMode, flick);
    }));
}
