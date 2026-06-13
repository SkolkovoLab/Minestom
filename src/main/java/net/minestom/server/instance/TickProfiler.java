package net.minestom.server.instance;

/**
 * Dependency-free hook that lets a higher layer (engine-core) wrap each instance tick
 * ({@link Instance#threadLoop(long)}) in instrumentation without lib-minestom depending on
 * OpenTelemetry/Pyroscope or on any Skolengine type.
 * <p>
 * The default is a pass-through that simply runs the tick. An implementation is installed once at
 * startup, before any {@link InstanceThread} is created, and is then read-only from every instance
 * thread. The wait/sleep between ticks lives in {@link InstanceThread#run()} and is therefore always
 * outside the profiled call.
 */
@FunctionalInterface
public interface TickProfiler {

    /**
     * Runs {@code tick}, optionally wrapped in instrumentation for {@code instance}.
     *
     * @param instance the ticking instance
     * @param tick     the per-tick work to execute (a single {@link Instance#threadLoop(long)})
     */
    void profile(Instance instance, Runnable tick);

    /** Pass-through default: just run the tick, no instrumentation. */
    TickProfiler NOOP = (instance, tick) -> tick.run();

    /**
     * Single global holder. Written once at startup (before instance threads start) and read by
     * every {@link InstanceThread}, hence {@code volatile}.
     */
    final class Holder {
        private static volatile TickProfiler current = NOOP;

        private Holder() {
        }

        /** Installs the active profiler. {@code null} resets to {@link #NOOP}. */
        public static void set(TickProfiler profiler) {
            current = profiler == null ? NOOP : profiler;
        }

        public static TickProfiler get() {
            return current;
        }
    }
}
