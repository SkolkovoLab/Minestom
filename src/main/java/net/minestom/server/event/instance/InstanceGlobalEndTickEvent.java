package net.minestom.server.event.instance;

import net.minestom.server.event.trait.InstanceEvent;
import net.minestom.server.instance.Instance;
import org.jetbrains.annotations.NotNull;

/**
 * Called when an instance processes a tick.
 */
public class InstanceGlobalEndTickEvent implements InstanceEvent {

    private final Instance instance;
    private final long tickStart;

    public InstanceGlobalEndTickEvent(@NotNull Instance instance, long tickStart) {
        this.instance = instance;
        this.tickStart = tickStart;
    }

    @Override
    public @NotNull Instance getInstance() {
        return instance;
    }

    public long getTickStart() {
        return tickStart;
    }
}