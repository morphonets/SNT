/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2026 Fiji developers.
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-3.0.html>.
 * #L%
 */
package sc.fiji.snt.viewer;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

import javax.swing.SwingUtilities;

import org.janelia.saalfeldlab.n5.N5Reader;

/**
 * Lightweight, opt-in profiling aid for the BVV/BDV viewers (Stream mode).
 * Everything is controlled by JVM flags read once at class load. With no flag
 * set, the switches below are {@code static final} constants: the JIT removes
 * the timers entirely, so there is no per-call cost.
 * <ul>
 * <li>{@code -Dsnt.profile=true}: named timers (aggregated count/total/max per
 * label, with calls on the EDT counted separately) and a summary table printed
 * every {@code -Dsnt.profile.reportSec} (default 10). This is the only output</li>
 * <li>{@code -Dsnt.profile.n5=true}: also times {@link N5Reader} calls (see
 * {@link #wrap(N5Reader)})</li>
 * <li>{@code -Dsnt.profile.slow=true}: also prints each EDT call slower than
 * {@code -Dsnt.profile.slowMs} (default 30)</li>
 * <li>{@code -Dsnt.profile.edt=true}: also starts an EDT monitor that adds two
 * rows to the summary: the latency of a no-op posted to the EDT every 50 ms,
 * and the number and duration of stalls longer than
 * {@code -Dsnt.profile.stallMs} (default 150)</li>
 * <li>{@code -Dsnt.profile.edt.dump=true}: also dumps the EDT stack (and the
 * lock it waits for) during each stall</li>
 * </ul>
 * Output lines are prefixed with {@code [SNT-PROF]}
 */
public final class BvvProfiler {

    // static final: constant-folded by the JIT, so disabled timers cost nothing
    private static final boolean ENABLED = Boolean.getBoolean("snt.profile");
    private static final boolean LOG_SLOW = ENABLED && Boolean.getBoolean("snt.profile.slow");
    private static final boolean MONITOR_EDT = ENABLED && Boolean.getBoolean("snt.profile.edt");
    private static final boolean DUMP_EDT = MONITOR_EDT && Boolean.getBoolean("snt.profile.edt.dump");
    private static final long SLOW_NS = Long.getLong("snt.profile.slowMs", 30L) * 1_000_000L;
    private static final long STALL_MS = Long.getLong("snt.profile.stallMs", 150L);
    private static final long REPORT_SEC = Long.getLong("snt.profile.reportSec", 10L);
    private static final int MAX_FRAMES = 25;

    private static final Map<String, Stat> STATS = new ConcurrentHashMap<>();
    private static final LongAdder DECODED_BYTES = new LongAdder();
    private static final LongAdder DECODED_CHUNKS = new LongAdder();
    private static final AtomicInteger IN_FLIGHT = new AtomicInteger();
    private static final AtomicInteger PEAK_IN_FLIGHT = new AtomicInteger();
    private static boolean monitorsStarted;

    private BvvProfiler() {
    }

    private static final class Stat {
        final LongAdder count = new LongAdder();
        final LongAdder totalNs = new LongAdder();
        final LongAdder edtCount = new LongAdder();
        final LongAdder edtNs = new LongAdder();
        final AtomicLong maxNs = new AtomicLong();
    }

    public static boolean isEnabled() {
        return ENABLED;
    }

    /** Starts the periodic reporter and, if requested, the EDT monitor (idempotent) */
    public static synchronized void startIfEnabled() {
        if (!ENABLED || monitorsStarted) return;
        monitorsStarted = true;
        if (MONITOR_EDT) daemon("SNT-prof-edt-monitor", BvvProfiler::edtMonitorLoop);
        daemon("SNT-prof-reporter", BvvProfiler::reporterLoop);
    }

    /** @return a start token for {@link #stop(String, long)}; 0 if disabled */
    public static long start() {
        return ENABLED ? System.nanoTime() : 0L;
    }

    /** Records the time elapsed since {@code token} under {@code label} */
    public static void stop(final String label, final long token) {
        if (!ENABLED || token == 0L) return;
        final long dt = System.nanoTime() - token;
        final boolean edt = SwingUtilities.isEventDispatchThread();
        record(label, dt, edt);
    }

    private static void record(final String label, final long dt, final boolean edt) {
        final Stat s = STATS.computeIfAbsent(label, k -> new Stat());
        s.count.increment();
        s.totalNs.add(dt);
        s.maxNs.accumulateAndGet(dt, Math::max);
        if (edt) {
            s.edtCount.increment();
            s.edtNs.add(dt);
        }
        if (LOG_SLOW && dt > SLOW_NS && (edt || dt > 20 * SLOW_NS) && !label.startsWith("edt.")) {
            out(String.format("slow %s: %.1f ms on %s", label, dt / 1e6,
                    edt ? "EDT" : Thread.currentThread().getName()));
        }
    }

    /** Prints the summary table now, sorted by total time */
    public static void report() {
        if (STATS.isEmpty()) return;
        final List<Map.Entry<String, Stat>> rows = new ArrayList<>(STATS.entrySet());
        rows.sort(Comparator.comparingLong((Map.Entry<String, Stat> e) -> e.getValue().totalNs.sum()).reversed());
        final StringBuilder sb = new StringBuilder("summary (peak concurrent reads: ")
                .append(PEAK_IN_FLIGHT.get()).append(")\n");
        sb.append(String.format("  %-34s %8s %10s %9s %9s %8s %9s%n", "label", "n", "total ms",
                "avg ms", "max ms", "n@EDT", "EDT ms"));
        for (final Map.Entry<String, Stat> e : rows) {
            final Stat s = e.getValue();
            final long n = s.count.sum();
            sb.append(String.format("  %-34s %8d %10.0f %9.2f %9.1f %8d %9.0f%n", e.getKey(), n,
                    s.totalNs.sum() / 1e6, n == 0 ? 0 : s.totalNs.sum() / 1e6 / n,
                    s.maxNs.get() / 1e6, s.edtCount.sum(), s.edtNs.sum() / 1e6));
        }
        final long chunks = DECODED_CHUNKS.sum();
        if (chunks > 0) {
            final double mb = DECODED_BYTES.sum() / 1048576d;
            sb.append(String.format("  chunks decoded: %d, %.0f MB (avg %.1f MB each, uncompressed)%n", chunks, mb,
                    mb / chunks));
        }
        out(sb.toString());
    }

    public static void reset() {
        STATS.clear();
        PEAK_IN_FLIGHT.set(0);
    }

    /**
     * Wraps {@code reader} in a proxy timing every call (labelled
     * {@code n5.<method>}) and tracking concurrency. Returns {@code reader}
     * itself if profiling is disabled or the proxy cannot be created. NB: code
     * casting the result to a concrete reader class will fail
     */
    public static N5Reader wrap(final N5Reader reader) {
        if (!ENABLED || reader == null) return reader;
        try {
            final Set<Class<?>> ifaces = new LinkedHashSet<>();
            for (Class<?> c = reader.getClass(); c != null; c = c.getSuperclass()) {
                collectInterfaces(c, ifaces);
            }
            final N5Reader proxy = (N5Reader) Proxy.newProxyInstance(reader.getClass().getClassLoader(),
                    ifaces.toArray(new Class<?>[0]), (p, method, args) -> {
                        final long t0 = System.nanoTime();
                        final int n = IN_FLIGHT.incrementAndGet();
                        PEAK_IN_FLIGHT.accumulateAndGet(n, Math::max);
                        Object result = null;
                        try {
                            result = method.invoke(reader, args);
                            return result;
                        } catch (final InvocationTargetException ex) {
                            throw ex.getCause();
                        } finally {
                            IN_FLIGHT.decrementAndGet();
                            stop("n5." + method.getName(), t0);
                            if (result != null && method.getName().startsWith("read")) countDecoded(method, result);
                        }
                    });
            return proxy;
        } catch (final RuntimeException ex) {
            out("could not proxy N5 reader: " + ex);
            return reader;
        }
    }

    /**
     * Adds the uncompressed size of a chunk returned by a reader call. Reflection through the declared return
     * type keeps this independent of the N5 version; silently does nothing if the type is not recognized
     */
    private static void countDecoded(final java.lang.reflect.Method method, final Object chunk) {
        try {
            final Class<?> type = method.getReturnType();
            final Object elements = type.getMethod("getNumElements").invoke(chunk);
            final Object data = type.getMethod("getData").invoke(chunk);
            if (!(elements instanceof Number) || data == null) return;
            final Class<?> c = data.getClass().getComponentType();
            if (c == null) return;
            final int bytes = c == byte.class ? 1 : (c == short.class || c == char.class) ? 2
                    : (c == long.class || c == double.class) ? 8 : 4;
            DECODED_BYTES.add(((Number) elements).longValue() * bytes);
            DECODED_CHUNKS.increment();
        } catch (final ReflectiveOperationException | RuntimeException ex) {
            // not a chunk-like return type: ignore
        }
    }

    private static void collectInterfaces(final Class<?> c, final Set<Class<?>> into) {
        for (final Class<?> i : c.getInterfaces()) {
            if (into.add(i)) collectInterfaces(i, into);
        }
    }

    private static void daemon(final String name, final Runnable r) {
        final Thread t = new Thread(r, name);
        t.setDaemon(true);
        t.start();
    }

    private static void reporterLoop() {
        while (true) {
            try {
                TimeUnit.SECONDS.sleep(REPORT_SEC);
            } catch (final InterruptedException ex) {
                return;
            }
            report();
        }
    }

    private static void edtMonitorLoop() {
        while (true) {
            try {
                TimeUnit.MILLISECONDS.sleep(50);
                final CountDownLatch done = new CountDownLatch(1);
                final long t0 = System.nanoTime();
                SwingUtilities.invokeLater(done::countDown);
                boolean stalled = false;
                long waited = 0;
                while (!done.await(STALL_MS, TimeUnit.MILLISECONDS)) {
                    waited += STALL_MS;
                    // Dump at first detection, then once per second
                    if (DUMP_EDT && (!stalled || waited % 1000 < STALL_MS)) dumpEdt(waited);
                    stalled = true;
                }
                final long latency = System.nanoTime() - t0;
                record("edt.probe latency", latency, true);
                if (stalled) {
                    record("edt.stall >" + STALL_MS + " ms", latency, true);
                    if (DUMP_EDT) out(String.format("EDT recovered after %.0f ms", latency / 1e6));
                }
            } catch (final InterruptedException ex) {
                return;
            }
        }
    }

    private static void dumpEdt(final long waitedMs) {
        Thread edt = null;
        for (final Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.getName().startsWith("AWT-EventQueue")) {
                edt = t;
                break;
            }
        }
        if (edt == null) return;
        final ThreadInfo ti = ManagementFactory.getThreadMXBean().getThreadInfo(edt.getId(), MAX_FRAMES);
        if (ti == null) return;
        final StringBuilder sb = new StringBuilder("EDT unresponsive for >").append(waitedMs)
                .append(" ms, state=").append(ti.getThreadState());
        if (ti.getLockName() != null) {
            sb.append(", waiting on ").append(ti.getLockName());
            if (ti.getLockOwnerName() != null) sb.append(" held by '").append(ti.getLockOwnerName()).append("'");
        }
        sb.append('\n');
        for (final StackTraceElement f : ti.getStackTrace()) sb.append("    at ").append(f).append('\n');
        out(sb.toString());
    }

    private static void out(final String msg) {
        System.out.println("[SNT-PROF] " + msg);
    }
}
