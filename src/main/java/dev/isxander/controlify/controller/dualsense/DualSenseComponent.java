package dev.isxander.controlify.controller.dualsense;

import dev.isxander.controlify.controller.impl.ECSComponentImpl;
import dev.isxander.controlify.driver.sdl.dualsense.DualsenseTriggerEffect;
import dev.isxander.controlify.utils.CUtil;
import net.minecraft.resources.Identifier;

import java.util.Objects;

public class DualSenseComponent extends ECSComponentImpl {
    public static final Identifier ID = CUtil.rl("dualsense");

    public enum MuteLightMode {
        OFF, ON, BREATHING;

        public byte toJnaValue() {
            return (byte) ordinal();
        }
    }

    private final DualSenseFeatureSupport support;
    private final AdaptiveTriggerDispatcher adaptiveTriggers;
    private final AdaptiveTriggerTickHandler triggerTickHandler;

    private DualsenseTriggerEffect leftTriggerEffect;
    private DualsenseTriggerEffect rightTriggerEffect;

    private MuteLightMode muteLightMode;

    private int playerIndicatorMask = -1;
    private byte playerIndicatorBrightness = 0;

    private boolean audioMuteStateSet = false;
    private boolean micMuted = false;
    private boolean speakerMuted = false;

    private int speakerVolume = -1;
    private int headphoneVolume = -1;

    private boolean hapticLowPass = false;
    private boolean resetLightsPending = false;

    private boolean dirty;

    public DualSenseComponent() {
        this(DualSenseFeatureSupport.unsupported());
    }

    public DualSenseComponent(DualSenseFeatureSupport support) {
        this.support = support;
        this.adaptiveTriggers = new AdaptiveTriggerDispatcher(this);
        this.triggerTickHandler = new AdaptiveTriggerTickHandler(this);
    }

    public DualSenseFeatureSupport support() {
        return this.support;
    }

    public AdaptiveTriggerDispatcher adaptiveTriggers() {
        return this.adaptiveTriggers;
    }

    public AdaptiveTriggerTickHandler triggerTickHandler() {
        return this.triggerTickHandler;
    }

    public void setLeftTriggerEffect(DualsenseTriggerEffect effect) {
        if (!Objects.equals(this.leftTriggerEffect, effect)) {
            this.leftTriggerEffect = effect;
            this.setDirty();
        }
    }

    public DualsenseTriggerEffect getLeftTriggerEffect() {
        return this.leftTriggerEffect;
    }

    public void setRightTriggerEffect(DualsenseTriggerEffect effect) {
        if (!Objects.equals(this.rightTriggerEffect, effect)) {
            this.rightTriggerEffect = effect;
            this.setDirty();
        }
    }

    public DualsenseTriggerEffect getRightTriggerEffect() {
        return this.rightTriggerEffect;
    }

    public void setMuteLightMode(MuteLightMode mode) {
        if (this.muteLightMode != mode) {
            this.muteLightMode = mode;
            this.setDirty();
        }
    }

    public MuteLightMode getMuteLightMode() {
        return this.muteLightMode;
    }

    public void setMuteLight(boolean on) {
        setMuteLightMode(on ? MuteLightMode.ON : MuteLightMode.OFF);
    }

    public boolean getMuteLight() {
        return this.muteLightMode == MuteLightMode.ON;
    }

    public void setPlayerIndicator(int mask, byte brightness) {
        int safeMask = mask & 0xFF;
        if (safeMask != this.playerIndicatorMask || brightness != this.playerIndicatorBrightness) {
            this.playerIndicatorMask = safeMask;
            this.playerIndicatorBrightness = brightness;
            this.setDirty();
        }
    }

    public int getPlayerIndicatorMask() {
        return this.playerIndicatorMask;
    }

    public byte getPlayerIndicatorBrightness() {
        return this.playerIndicatorBrightness;
    }

    public void setMicMuted(boolean muted) {
        if (!this.audioMuteStateSet || this.micMuted != muted) {
            this.audioMuteStateSet = true;
            this.micMuted = muted;
            this.setDirty();
        }
    }

    public void setSpeakerMuted(boolean muted) {
        if (!this.audioMuteStateSet || this.speakerMuted != muted) {
            this.audioMuteStateSet = true;
            this.speakerMuted = muted;
            this.setDirty();
        }
    }

    public boolean hasAudioMuteState() {
        return this.audioMuteStateSet;
    }

    public boolean isMicMuted() {
        return this.micMuted;
    }

    public boolean isSpeakerMuted() {
        return this.speakerMuted;
    }

    public void setSpeakerVolume(int volume) {
        int clamped = clamp(volume, 0, 100);
        if (clamped != this.speakerVolume) {
            this.speakerVolume = clamped;
            this.setDirty();
        }
    }

    public int getSpeakerVolume() {
        return this.speakerVolume;
    }

    public void setHeadphoneVolume(int volume) {
        int clamped = clamp(volume, 0, 100);
        if (clamped != this.headphoneVolume) {
            this.headphoneVolume = clamped;
            this.setDirty();
        }
    }

    public int getHeadphoneVolume() {
        return this.headphoneVolume;
    }

    public void setHapticLowPass(boolean on) {
        if (this.hapticLowPass != on) {
            this.hapticLowPass = on;
            this.setDirty();
        }
    }

    public boolean isHapticLowPass() {
        return this.hapticLowPass;
    }

    public void resetLights() {
        this.resetLightsPending = true;
        this.setDirty();
    }

    public boolean consumeResetLights() {
        boolean v = this.resetLightsPending;
        this.resetLightsPending = false;
        return v;
    }

    private void setDirty() {
        this.dirty = true;
    }

    public boolean consumeDirty() {
        boolean old = this.dirty;
        this.dirty = false;
        return old;
    }

    @Override
    public Identifier id() {
        return ID;
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
