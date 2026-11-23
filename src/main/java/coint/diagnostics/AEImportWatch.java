package coint.diagnostics;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import coint.CointConfig;
import coint.CointCore;

/** Samples a stalled import from a separate thread. Never reads a world or changes an AE2 operation. */
public final class AEImportWatch {

    private static final long SLOW_NANOS = TimeUnit.SECONDS.toNanos(3);
    private static final long REPORT_NANOS = TimeUnit.SECONDS.toNanos(15);
    private static final ThreadLocal<Trace> LOCAL = new ThreadLocal<>();
    private static final Map<Thread, Trace> ACTIVE = new ConcurrentHashMap<>();
    private static volatile ScheduledExecutorService timer;

    private AEImportWatch() {}

    public static synchronized void start() {
        stop();
        if (!CointConfig.general.aeImportDiagnostics) return;
        timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "CointCore-AEImportWatch");
            thread.setDaemon(true);
            return thread;
        });
        timer.scheduleWithFixedDelay(AEImportWatch::sample, 1, 1, TimeUnit.SECONDS);
    }

    public static synchronized void stop() {
        if (timer != null) timer.shutdownNow();
        timer = null;
        ACTIVE.clear();
        LOCAL.remove();
    }

    public static Trace begin(int dimension, int x, int y, int z, String side) {
        if (!enabled()) return null;
        Trace trace = LOCAL.get();
        // Reuse the buffers for normal bus ticks; a nested bus gets its own diagnostic scope.
        if (trace == null || trace.active) trace = new Trace(trace);
        trace.reset(dimension, x, y, z, side);
        LOCAL.set(trace);
        ACTIVE.put(Thread.currentThread(), trace);
        return trace;
    }

    public static boolean enabled() {
        return timer != null && CointConfig.general.aeImportDiagnostics;
    }

    public static Trace current() {
        Trace trace = LOCAL.get();
        return trace != null && trace.active ? trace : null;
    }

    public static void end(Trace trace) {
        if (trace == null) return;
        trace.active = false;
        if (trace.parent != null && trace.parent.active) {
            LOCAL.set(trace.parent);
            ACTIVE.put(Thread.currentThread(), trace.parent);
        } else {
            ACTIVE.remove(Thread.currentThread(), trace);
        }
    }

    private static void sample() {
        try {
            long now = System.nanoTime();
            for (Map.Entry<Thread, Trace> entry : ACTIVE.entrySet()) {
                Trace trace = entry.getValue();
                long started = trace.started;
                if (!trace.active || now - started < SLOW_NANOS || now - trace.nextReport < 0) continue;
                String details = trace.describe(now);
                StringBuilder stack = new StringBuilder();
                StackTraceElement[] frames = entry.getKey()
                    .getStackTrace();
                for (int i = 0; i < Math.min(frames.length, 12); i++) stack.append("\n    at ")
                    .append(frames[i]);
                // A fast operation may have finished/reused the buffers while the stack was sampled.
                if (!trace.active || trace.started != started) continue;
                trace.nextReport = now + REPORT_NANOS;
                CointCore.LOG.warn("[AEImportWatch] {}{}", details, stack);
            }
        } catch (RuntimeException e) {
            CointCore.LOG.warn("[AEImportWatch] Could not sample a slow import", e);
        }
    }

    public static final class Trace {

        private final Trace parent;
        private volatile boolean active;
        private volatile long started;
        private volatile long nextReport;
        private volatile String location;
        private volatile int initialBudget;
        private volatile int remaining;
        private volatile long attempts;
        private volatile long completed;
        private volatile long simulateInject;
        private volatile long simulateExtract;
        private volatile long modulateInject;
        private volatile long modulateExtract;
        private volatile int depth;
        private volatile int maxDepth;
        private volatile int maxHandlers;
        private volatile String operation;

        private Trace(Trace parent) {
            this.parent = parent;
        }

        private void reset(int dimension, int x, int y, int z, String side) {
            active = false;
            location = "dimension=" + dimension + ", x=" + x + ", y=" + y + ", z=" + z + ", side=" + side;
            initialBudget = -1;
            remaining = -1;
            attempts = completed = simulateInject = simulateExtract = modulateInject = modulateExtract = 0;
            depth = maxDepth = maxHandlers = 0;
            operation = "before importStuff";
            started = System.nanoTime();
            nextReport = started + SLOW_NANOS;
            active = true;
        }

        public void beforeImport(int budget) {
            if (initialBudget < 0) initialBudget = budget;
            remaining = budget;
            attempts++;
            operation = "importStuff";
        }

        public void afterImport(int budget) {
            remaining = budget;
            completed++;
            operation = "between imports";
        }

        public int enterNetwork(boolean inject, boolean simulate, int handlers) {
            if (inject) {
                if (simulate) simulateInject++;
                else modulateInject++;
            } else {
                if (simulate) simulateExtract++;
                else modulateExtract++;
            }
            int previousDepth = depth;
            depth = previousDepth + 1;
            if (depth > maxDepth) maxDepth = depth;
            if (handlers > maxHandlers) maxHandlers = handlers;
            operation = simulate ? (inject ? "SIMULATE inject" : "SIMULATE extract")
                : (inject ? "MODULATE inject" : "MODULATE extract");
            return previousDepth;
        }

        public void leaveNetwork(int previousDepth) {
            depth = previousDepth;
        }

        public String describe(long now) {
            return location + ", seconds="
                + TimeUnit.NANOSECONDS.toSeconds(now - started)
                + ", budget="
                + initialBudget
                + " -> "
                + remaining
                + ", attempts="
                + attempts
                + ", completed="
                + completed
                + ", simulateInject="
                + simulateInject
                + ", simulateExtract="
                + simulateExtract
                + ", modulateInject="
                + modulateInject
                + ", modulateExtract="
                + modulateExtract
                + ", depth="
                + depth
                + ", maxDepth="
                + maxDepth
                + ", maxHandlers="
                + maxHandlers
                + ", lastOperation="
                + operation;
        }
    }
}
