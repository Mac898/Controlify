package dev.isxander.controlify.config.settings.profile;

import dev.isxander.controlify.config.dto.profile.GyroConfig;
import dev.isxander.controlify.controller.gyro.GyroButtonMode;
import dev.isxander.controlify.controller.gyro.GyroYawMode;

public class GyroSettings {
    /** Yaw (horizontal) sensitivity multiplier. UI slider 0..5 (0..500%). */
    public float lookSensitivityX;
    /** Pitch (vertical) sensitivity multiplier. UI slider 0..5 (0..500%). */
    public float lookSensitivityY;
    public boolean relativeMode;
    public boolean invertPitch;
    public boolean invertYaw;
    public GyroButtonMode buttonMode;
    public GyroYawMode yawMode;
    public boolean flickStick;

    public GyroSettings(
            float lookSensitivityX,
            float lookSensitivityY,
            boolean relativeMode,
            boolean invertPitch,
            boolean invertYaw,
            GyroButtonMode buttonMode,
            GyroYawMode yawMode,
            boolean flickStick
    ) {
        this.lookSensitivityX = lookSensitivityX;
        this.lookSensitivityY = lookSensitivityY;
        this.relativeMode = relativeMode;
        this.invertPitch = invertPitch;
        this.invertYaw = invertYaw;
        this.buttonMode = buttonMode;
        this.yawMode = yawMode;
        this.flickStick = flickStick;
    }

    public static GyroSettings fromDTO(GyroConfig dto) {
        return new GyroSettings(
                dto.lookSensitivityX(),
                dto.lookSensitivityY(),
                dto.relativeMode(),
                dto.invertPitch(),
                dto.invertYaw(),
                dto.buttonMode(),
                dto.yawMode(),
                dto.flickStick()
        );
    }

    public GyroConfig toDTO() {
        return new GyroConfig(
                lookSensitivityX,
                lookSensitivityY,
                relativeMode,
                invertPitch,
                invertYaw,
                buttonMode,
                yawMode,
                flickStick
        );
    }

    /** Highest of the two axis sensitivities — used by call sites that gate on "any sensitivity
     *  &gt; 0" (e.g. the flick-stick guard, which should engage if either axis is enabled). */
    public float maxLookSensitivity() {
        return Math.max(lookSensitivityX, lookSensitivityY);
    }
}
