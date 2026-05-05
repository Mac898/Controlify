package dev.isxander.controlify.controller.dualsense;

import dev.isxander.controlify.utils.CUtil;
import net.minecraft.resources.Identifier;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Identifies why a layer was pushed to the {@link AdaptiveTriggerDispatcher}.
 * Reserved priority bands (set when calling {@code setLayer}, not stored on the reason):
 * 0–99 fallback / passive, 100–199 action-in-progress, 200–299 combat-imminent, 1000+ system / debug.
 */
public record TriggerReason(Identifier id, String description, ReasonScope scope) {
    private static final Map<Identifier, TriggerReason> REGISTRY = new LinkedHashMap<>();

    public static final TriggerReason DEBUG = register("debug", "Debug / diagnostics", ReasonScope.ALWAYS);
    public static final TriggerReason TEST = register("test", "Settings UI test buttons", ReasonScope.GUI_ONLY);

    public enum ReasonScope {
        /** Active only when no GUI is open. The default for in-world gameplay. */
        IN_WORLD,
        /** Active regardless of screen state. */
        ALWAYS,
        /** Active only while a GUI is open. */
        GUI_ONLY
    }

    public static TriggerReason register(Identifier id, String description, ReasonScope scope) {
        TriggerReason reason = new TriggerReason(id, description, scope);
        REGISTRY.put(id, reason);
        return reason;
    }

    public static TriggerReason register(String path, String description, ReasonScope scope) {
        return register(CUtil.rl(path), description, scope);
    }

    public static Collection<TriggerReason> values() {
        return REGISTRY.values();
    }
}
