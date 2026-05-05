package dev.isxander.controlify.controller.dualsense;

import dev.isxander.controlify.driver.dualsense.DualsenseTriggerEffect;

/**
 * Returned from {@link AdaptiveTriggerDispatcher#setLayer}. Callers use this to keep their
 * layer alive (heartbeat each tick), to swap the effect without re-priority, or to release the layer.
 * Layers without a heartbeat for {@link AdaptiveTriggerDispatcher#HEARTBEAT_TIMEOUT_TICKS} ticks are reaped.
 */
public interface TriggerHandle {
    /** Reset the heartbeat timer. Call each tick to keep the layer alive. */
    void heartbeat();

    /** Replace the layer's effect without changing its priority. Also heartbeats. */
    void update(DualsenseTriggerEffect newEffect);

    /** Remove the layer immediately. */
    void release();
}
