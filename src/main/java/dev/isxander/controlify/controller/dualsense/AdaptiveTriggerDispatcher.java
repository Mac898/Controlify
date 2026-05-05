package dev.isxander.controlify.controller.dualsense;

import dev.isxander.controlify.driver.sdl.dualsense.DualsenseTriggerEffect;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

import java.util.EnumMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Push-based, layered priority stack for DualSense <b>adaptive trigger</b> effects — i.e. effects
 * produced by the two motors inside L2 / R2 that provide dynamic resistance and trigger-local
 * vibration. This does not control the voice-coil HD haptics (see {@code HDHapticDispatcher}).
 *
 * <p>Each tick the highest-priority layer per trigger side that is visible under the current
 * screen scope wins, and the resolved effect is pushed to the {@link DualSenseComponent} which
 * then writes the DS5 effects-state HID report. Layers expire after
 * {@link #HEARTBEAT_TIMEOUT_TICKS} ticks without a heartbeat (or after their explicit
 * {@code durationTicks} for one-shot pushes).
 */
public final class AdaptiveTriggerDispatcher {
    public static final int HEARTBEAT_TIMEOUT_TICKS = 2;

    private final DualSenseComponent component;
    private final EnumMap<TriggerSide, Map<Identifier, Layer>> layersBySide;
    private final EnumMap<TriggerSide, DualsenseTriggerEffect> lastFlushed;

    public AdaptiveTriggerDispatcher(DualSenseComponent component) {
        this.component = component;
        this.layersBySide = new EnumMap<>(TriggerSide.class);
        this.lastFlushed = new EnumMap<>(TriggerSide.class);
        for (TriggerSide side : TriggerSide.values()) {
            this.layersBySide.put(side, new LinkedHashMap<>());
            this.lastFlushed.put(side, new DualsenseTriggerEffect.Off());
        }
    }

    /**
     * Push a layer that needs heartbeat-or-die semantics — re-call {@link TriggerHandle#heartbeat()}
     * each tick to keep alive. Times out after {@link #HEARTBEAT_TIMEOUT_TICKS} ticks of inactivity.
     */
    public TriggerHandle setLayer(TriggerSide side, TriggerReason reason, int priority, DualsenseTriggerEffect effect) {
        return setLayer(side, reason, priority, effect, HEARTBEAT_TIMEOUT_TICKS);
    }

    /**
     * Push a layer that auto-expires after {@code durationTicks} ticks regardless of heartbeat. Use this
     * for one-shot effects (e.g. a 60ms snap on bow release): pass the duration explicitly and never call heartbeat.
     */
    public TriggerHandle setLayer(TriggerSide side, TriggerReason reason, int priority, DualsenseTriggerEffect effect, int durationTicks) {
        Map<Identifier, Layer> map = layersBySide.get(side);
        Layer layer = map.computeIfAbsent(reason.id(), id -> new Layer());
        layer.reason = reason;
        layer.priority = priority;
        layer.effect = effect;
        layer.age = 0;
        layer.maxAge = Math.max(1, durationTicks);
        return new HandleImpl(side, reason.id());
    }

    public void clearAll() {
        for (Map<Identifier, Layer> map : layersBySide.values()) {
            map.clear();
        }
    }

    public void clearSide(TriggerSide side) {
        layersBySide.get(side).clear();
    }

    public void tick() {
        boolean screenOpen = Minecraft.getInstance().screen != null;

        for (TriggerSide side : TriggerSide.values()) {
            Map<Identifier, Layer> map = layersBySide.get(side);

            // Age and reap expired layers.
            Iterator<Layer> it = map.values().iterator();
            while (it.hasNext()) {
                Layer layer = it.next();
                layer.age++;
                if (layer.age > layer.maxAge) {
                    it.remove();
                }
            }

            // Resolve highest-priority visible layer.
            DualsenseTriggerEffect resolved = new DualsenseTriggerEffect.Off();
            int bestPriority = Integer.MIN_VALUE;
            for (Layer layer : map.values()) {
                if (!isVisible(layer.reason.scope(), screenOpen)) continue;
                if (layer.priority > bestPriority) {
                    bestPriority = layer.priority;
                    resolved = layer.effect;
                }
            }

            // Dedup: only push when the resolved effect changed since the last flush.
            if (!Objects.equals(resolved, lastFlushed.get(side))) {
                if (side == TriggerSide.LEFT) {
                    component.setLeftTriggerEffect(resolved);
                } else {
                    component.setRightTriggerEffect(resolved);
                }
                lastFlushed.put(side, resolved);
            }
        }
    }

    private static boolean isVisible(TriggerReason.ReasonScope scope, boolean screenOpen) {
        return switch (scope) {
            case ALWAYS -> true;
            case IN_WORLD -> !screenOpen;
            case GUI_ONLY -> screenOpen;
        };
    }

    private static final class Layer {
        TriggerReason reason;
        int priority;
        DualsenseTriggerEffect effect;
        int age;
        int maxAge = HEARTBEAT_TIMEOUT_TICKS;
    }

    private final class HandleImpl implements TriggerHandle {
        private final TriggerSide side;
        private final Identifier id;

        HandleImpl(TriggerSide side, Identifier id) {
            this.side = side;
            this.id = id;
        }

        @Override
        public void heartbeat() {
            Layer layer = layersBySide.get(side).get(id);
            if (layer != null) layer.age = 0;
        }

        @Override
        public void update(DualsenseTriggerEffect newEffect) {
            Layer layer = layersBySide.get(side).get(id);
            if (layer != null) {
                layer.effect = newEffect;
                layer.age = 0;
            }
        }

        @Override
        public void release() {
            layersBySide.get(side).remove(id);
        }
    }
}
