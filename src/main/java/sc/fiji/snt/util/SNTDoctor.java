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

package sc.fiji.snt.util;

import com.jogamp.opengl.*;
import net.imglib2.algorithm.gauss3.Gauss3;
import net.imglib2.img.array.ArrayImg;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.parallel.Parallelization;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.view.Views;
import sc.fiji.snt.Path;
import sc.fiji.snt.SNT;
import sc.fiji.snt.SNTPrefs;
import sc.fiji.snt.SNTService;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.Tree;
import sc.fiji.snt.analysis.TreeStatistics;
import sc.fiji.snt.tracing.TracerThread;
import sc.fiji.snt.tracing.cost.Reciprocal;
import sc.fiji.snt.tracing.heuristic.Euclidean;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks what the current machine can be expected to handle. This class is
 * GUI-free: results are plain records that can be printed to a console, shown
 * in a window or consumed by scripts. Statuses are only assigned when there is
 * an objective cause (e.g., software OpenGL renderer, failed context creation,
 * heap well below physical RAM). Everything else is reported as a measurement
 * of a named task, or as {@link Status#UNKNOWN}.
 *
 * @author Tiago Ferreira
 */
public final class SNTDoctor {

    /**
     * Outcome of an individual check
     */
    public enum Status {
        OK("OK"), LIMITED("Limited"), UNAVAILABLE("Unavailable"), FAILED("Failed"), UNKNOWN("Unknown");

        private final String label;

        Status(final String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /**
     * @param name    short name of the checked capability, e.g., "Memory"
     * @param status  the outcome
     * @param summary what was found/measured, with its practical consequence
     * @param advice  what the user can do about it (may be empty)
     */
    public record Result(String name, Status status, String summary, String advice) {
        @Override
        public String toString() {
            final String head = String.format("[%-11s] ", status.label());
            final String indent = " ".repeat(head.length());
            final StringBuilder sb = new StringBuilder();
            sb.append(wrap(name + ": " + summary, head, indent));
            if (advice != null && !advice.isEmpty()) sb.append("\n").append(wrap("Tip: " + advice, indent, indent));
            return sb.toString();
        }
    }

    /**
     * @param results the individual results, in the order they were run
     */
    public record Report(List<Result> results) {
        @Override
        public String toString() {
            final StringBuilder sb = new StringBuilder(header());
            for (final Result r : results)
                sb.append(r).append("\n\n");
            return sb.toString();
        }
    }

    /**
     * @return the first lines of a report: title, SNT version, and Java/OS details
     */
    public static String header() {
        return "SNT Doctor report\n" + SNTUtils.getReadableVersion() + "\n"
                + "Java " + System.getProperty("java.version") + " / " + System.getProperty("os.name") + " "
                + System.getProperty("os.version") + " (" + System.getProperty("os.arch") + ")\n\n";
    }

    /**
     * Max line length (in characters) of wrapped report text
     */
    public static final int WRAP_WIDTH = 88;

    /**
     * Word-wraps {@code text} at {@link #WRAP_WIDTH} columns. The first line starts with {@code first}, and
     * continuation lines with {@code cont} (hanging indent). Words longer than a line are not split
     */
    private static String wrap(final String text, final String first, final String cont) {
        final StringBuilder sb = new StringBuilder(first);
        int col = first.length();
        boolean lineStart = true;
        for (final String word : text.split(" ")) {
            if (!lineStart && col + 1 + word.length() > WRAP_WIDTH) {
                sb.append("\n").append(cont);
                col = cont.length();
                lineStart = true;
            }
            if (!lineStart) {
                sb.append(' ');
                col++;
            }
            sb.append(word);
            col += word.length();
            lineStart = false;
        }
        return sb.toString();
    }

    private static final int GL_TEXTURE_3D = 0x806F;
    private static final int GL_R8 = 0x8229;
    private static final int GL_RED = 0x1903;
    private static final int PROBE_TEXTURE_SIZE = 256;
    private static final double MIN_GL_VERSION = 3.3;
    private static final Pattern SOFTWARE_GL = Pattern.compile(
            "llvmpipe|softpipe|swrast|software|swiftshader|gdi generic|basic render",
            Pattern.CASE_INSENSITIVE);

    private record NamedCheck(String name, java.util.function.Supplier<Result> fn) {
    }

    private SNTDoctor() {
    } // prevent class instantiation

    /**
     * Runs all checks. This may take several seconds: do not call on the EDT
     */
    public static Report run() {
        return run(null);
    }

    /**
     * Runs all checks. This may take several seconds: do not call on the EDT
     *
     * @param progress optional callback notified as each result becomes
     *                 available (on the calling thread)
     * @return the full report
     */
    public static Report run(final Consumer<Result> progress) {
        final List<Result> results = new ArrayList<>();
        final List<NamedCheck> checks = List.of(
                new NamedCheck("Java", SNTDoctor::checkJava),
                new NamedCheck("Launcher", SNTDoctor::checkLauncher),
                new NamedCheck("Memory", SNTDoctor::checkMemory),
                new NamedCheck("Compute", SNTDoctor::checkCompute),
                new NamedCheck("Graphics", SNTDoctor::checkGraphics),
                new NamedCheck("Disk", SNTDoctor::checkDisk),
                new NamedCheck("Network", SNTDoctor::checkNetwork),
                new NamedCheck("Integrations", SNTDoctor::checkIntegrations),
                new NamedCheck("Sanity test", SNTDoctor::checkSanityTest));
        for (final NamedCheck check : checks) {
            Result r;
            try {
                r = check.fn().get();
            } catch (final Throwable t) {
                r = new Result(check.name(), Status.UNKNOWN, "Could not be completed (" + t + ")", "");
            }
            results.add(r);
            if (progress != null) progress.accept(r);
        }
        return new Report(results);
    }

    private static long physicalRamBytes() {
        try {
            if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os)
                return os.getTotalMemorySize();
        } catch (final Throwable ignored) {
            // fall through
        }
        return -1;
    }

    /** @return the dimensions of a cubic image with the given number of voxels, e.g., "1030x1030x1030" */
    private static String cube(final double voxels) {
        final long side = (Math.round(Math.cbrt(voxels)) / 10) * 10; // rounded down: avoids false precision
        return side + "x" + side + "x" + side;
    }

    static Result checkMemory() {
        final long maxHeap = Runtime.getRuntime().maxMemory();
        final long ram = physicalRamBytes();
        final SNTUtils.HeapInfo heap = SNTUtils.getHeapInfo();
        // Capacity is expressed in voxels at a reference depth (16-bit), as it depends on bit depth.
        // Rule of thumb: processing (filtering, tracing) needs about as much memory as 3 copies of the image
        final long voxels = Math.max(0, heap.availableMB()) * 1024L * 1024L / 3 / 2;
        final String ramStr = (ram > 0) ? SNTUtils.formatBytes(ram) + " RAM, " : "";
        final String summary = ramStr + SNTUtils.formatBytes(maxHeap) + " max heap. A 16-bit image of up to ~"
                + cube(voxels) + " voxels can be held and processed in memory (~" + cube(voxels / 2.0)
                + " at 32-bit, ~" + cube(voxels * 2.0) + " at 8-bit). "
                + "Larger images: stream them lazily with SNT Stream (N5, Zarr, HDF5, IMS, BDV XML), or crop/downsample them first";
        final String raise = (System.getProperty("pysnt.version") != null)
                ? "Set the heap when initializing PySNT, e.g., pysnt.initialize(max_heap=\"8g\")"
                : "Increase heap in Edit > Options > Memory & Threads (Fiji's default is 75% of physical RAM)";
        if (maxHeap < 2L << 30) return new Result("Memory", Status.LIMITED, summary, raise);
        if (ram > 0 && maxHeap < ram / 2 && (ram - maxHeap) > (4L << 30)) {
            return new Result("Memory", Status.LIMITED, summary + ". Heap is under half of physical RAM", raise);
        }
        return new Result("Memory", Status.OK, summary, "");
    }

    static Result checkCompute() {
        final int cores = Runtime.getRuntime().availableProcessors();
        final int threads = SNTPrefs.getThreads();
        final int[] dims = {256, 256, 64};
        final ArrayImg<FloatType, ?> in = ArrayImgs.floats(dims[0], dims[1], dims[2]);
        final ArrayImg<FloatType, ?> out = ArrayImgs.floats(dims[0], dims[1], dims[2]);
        in.forEach(t -> t.set((float) Math.random()));
        final double[] sigma = {2, 2, 2};
        // Gauss3 uses imglib2's default parallelism, so restrict it to SNT's thread preference
        final Runnable filter = () -> Gauss3.gauss(sigma, Views.extendBorder(in), out);
        Parallelization.runWithNumThreads(threads, filter); // warm-up
        final long t0 = System.nanoTime();
        Parallelization.runWithNumThreads(threads, filter);
        final double ms = (System.nanoTime() - t0) / 1e6;
        final double mvox = (double) dims[0] * dims[1] * dims[2] / 1e6;
        final String summary = String.format(Locale.US,
                "%d/%d cores used by SNT. Gaussian filter on %dx%dx%d voxels took %.0f ms (%.1f Mvoxels/s)",
                threads, cores, dims[0], dims[1], dims[2], ms, mvox / (ms / 1000));
        final String prefs = "Adjust the number of threads in SNT's preferences";
        if (threads > cores) return new Result("Compute", Status.LIMITED,
                summary + ". More threads than cores", prefs);
        if (threads == 1 && cores > 2) return new Result("Compute", Status.LIMITED,
                summary + ". Multithreading is disabled", prefs);
        if (cores <= 2) return new Result("Compute", Status.LIMITED, summary,
                "Few cores: auto-tracing and filtering of large images will be slow");
        return new Result("Compute", Status.OK, summary, "");
    }

    static Result checkGraphics() {
        final GLUtils.Info gl = GLUtils.getInfo();
        final String tip = "2D tracing is unaffected. The Reconstruction Viewer and BigVolumeViewer need OpenGL";
        if (!gl.available()) {
            return new Result("Graphics", Status.UNAVAILABLE,
                    "No OpenGL context could be created: 3D viewers will not work", tip);
        }
        final String id = gl.renderer() + ", OpenGL " + gl.version();
        if (SOFTWARE_GL.matcher(gl.renderer()).find()) {
            return new Result("Graphics", Status.LIMITED, id + ". Software rendering: expect slow 3D viewers",
                    "Update GPU drivers, or check that Fiji is using your GPU (hybrid graphics systems)");
        }
        final Matcher m = Pattern.compile("^(\\d+)\\.(\\d+)").matcher(gl.version());
        if (m.find() && Double.parseDouble(m.group(1) + "." + m.group(2)) < MIN_GL_VERSION) {
            return new Result("Graphics", Status.LIMITED, id + ". OpenGL " + MIN_GL_VERSION + " or newer is recommended",
                    "Update GPU drivers");
        }
        final String probe = probeContext();
        if (probe == null) {
            return new Result("Graphics", Status.LIMITED, id + ". Test frame/texture could not be created",
                    "Update GPU drivers");
        }
        // Speed of a working GPU is not predicted by this test
        return new Result("Graphics", Status.OK, id + ". " + probe + ". Rendering speed: unknown", "");
    }

    /**
     * @return description of the probe's outcome, or null if it failed
     */
    private static String probeContext() {
        GLOffscreenAutoDrawable drawable = null;
        try {
            final GLProfile profile = GLProfile.getMaxProgrammableCore(true);
            final GLDrawableFactory factory = GLDrawableFactory.getFactory(profile);
            drawable = factory.createOffscreenAutoDrawable(null, new GLCapabilities(profile), null, 256, 256);
            drawable.display();
            drawable.getContext().makeCurrent();
            try {
                final GL2ES2 gl = drawable.getContext().getGL().getGL2ES2();
                final long t0 = System.nanoTime();
                gl.glClearColor(0f, 0f, 0f, 1f);
                gl.glClear(GL.GL_COLOR_BUFFER_BIT);
                final int[] tex = new int[1];
                gl.glGenTextures(1, tex, 0);
                gl.glBindTexture(GL_TEXTURE_3D, tex[0]);
                final int n = PROBE_TEXTURE_SIZE;
                gl.glTexImage3D(GL_TEXTURE_3D, 0, GL_R8, n, n, n, 0, GL_RED, GL.GL_UNSIGNED_BYTE,
                        ByteBuffer.allocateDirect(n * n * n));
                gl.glFinish();
                final double ms = (System.nanoTime() - t0) / 1e6;
                final boolean ok = gl.glGetError() == GL.GL_NO_ERROR;
                gl.glDeleteTextures(1, tex, 0);
                if (!ok) return null;
                return String.format(Locale.US, "Test frame and %d^3 3D texture OK (%.0f ms)", n, ms);
            } finally {
                drawable.getContext().release();
            }
        } catch (final Throwable t) {
            SNTUtils.log("SNTDoctor: GL probe failed (" + t.getMessage() + ")");
            return null;
        } finally {
            if (drawable != null) drawable.destroy();
        }
    }

    /** Replaces the user's home directory with "~": reports are often pasted in public forums */
    private static String anonymize(final String path) {
        final String home = System.getProperty("user.home");
        return (home != null && !home.isEmpty() && path.startsWith(home)) ? "~" + path.substring(home.length()) : path;
    }

    static Result checkDisk() {
        final File dir = SNTUtils.getCacheDir();
        final long free = dir.getUsableSpace();
        final String summary = SNTUtils.formatBytes(free) + " free in cache directory (" + anonymize(dir.getAbsolutePath())
                + ")";
        if (free <= 0 || !dir.canWrite()) return new Result("Disk", Status.UNAVAILABLE, summary,
                "Disk-backed operations (e.g., auto-tracing of large images) will fail");
        if (free < (1L << 30)) return new Result("Disk", Status.LIMITED, summary,
                "Free up space or change java.io.tmpdir");
        return new Result("Disk", Status.OK, summary, "");
    }

    static Result checkNetwork() {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) URI.create("https://imagej.net").toURL().openConnection();
            c.setRequestMethod("HEAD");
            c.setConnectTimeout(4000);
            c.setReadTimeout(4000);
            final int code = c.getResponseCode();
            if (code < 400)
                return new Result("Network", Status.OK, "imagej.net reachable: remote data can be accessed", "");
            return new Result("Network", Status.LIMITED, "imagej.net answered with HTTP " + code, "Check proxy/firewall settings");
        } catch (final Exception e) {
            return new Result("Network", Status.UNAVAILABLE,
                    "imagej.net unreachable: remote demo images and datasets may not load",
                    "Check connection, proxy and firewall settings");
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String exec(final String... cmd) {
        try {
            final Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            if (!p.waitFor(3, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            return new String(p.getInputStream().readAllBytes()).trim();
        } catch (final Exception e) {
            return null;
        }
    }

    /** Detects macOS Rosetta only. Emulation on ARM Windows is not detected */
    private static boolean runsUnderRosetta() {
        final String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        final String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        return os.contains("mac") && (arch.equals("x86_64") || arch.equals("amd64"))
                && "1".equals(exec("sysctl", "-n", "sysctl.proc_translated"));
    }

    static Result checkJava() {
        final String summary = "Java " + System.getProperty("java.version") + " ("
                + System.getProperty("java.vendor", "unknown vendor") + "), "
                + System.getProperty("os.arch") + " on " + System.getProperty("os.name");
        if (runsUnderRosetta()) return new Result("Java", Status.LIMITED,
                summary + ". Intel Java is running under Rosetta emulation on Apple Silicon: expect slow performance",
                "Use a Fiji distribution bundling a native (aarch64) Java");
        if ("32".equals(System.getProperty("sun.arch.data.model"))) return new Result("Java", Status.LIMITED,
                summary + ". 32-bit Java: memory is limited to ~1.5 GB", "Use a 64-bit Fiji");
        return new Result("Java", Status.OK, summary, "");
    }

    /**
     * Describes how this session was initialized. PySNT publishes {@code pysnt.*} system properties once the JVM is
     * up (version, python, source, mode, env, libs). These are the definitive signal: a jgo-managed classpath
     * (~/.jgo cache) is only a hint, and the Fiji launcher sets {@code fiji.dir}. Never reports a problem: this
     * row is purely descriptive
     */
    static Result checkLauncher() {
        final String version = System.getProperty("pysnt.version");
        final boolean headless = java.awt.GraphicsEnvironment.isHeadless();
        final String sep = File.separator;
        final boolean jgo = System.getProperty("java.class.path", "").contains(sep + ".jgo" + sep);
        final StringBuilder sb = new StringBuilder("Initialized through ");
        final List<String> extra = new ArrayList<>(); // details for non-PySNT sessions
        if (version != null) {
            sb.append("PySNT ").append(version);
            final List<String> details = new ArrayList<>();
            addIfSet(details, "Python ", System.getProperty("pysnt.python"), "");
            addIfSet(details, "", System.getProperty("pysnt.mode"), " mode");
            final String source = System.getProperty("pysnt.source", "");
            if (source.equals("maven")) details.add("SNT from Maven");
            else if (source.equals("fiji")) details.add("SNT from a local Fiji");
            addIfSet(details, "", System.getProperty("pysnt.env"), "");
            addIfSet(details, "", System.getProperty("pysnt.libs"), "");
            if (!details.isEmpty()) sb.append(" (").append(String.join("; ", details)).append(")");
        } else if (jgo) {
            sb.append("a jgo-managed classpath (Python launcher)");
        } else if (System.getProperty("fiji.dir") != null) {
            sb.append("Fiji");
            if (System.getProperty("scijava.app.config-file", "").toLowerCase(Locale.ROOT).contains("jaunch"))
                extra.add("Jaunch launcher");
        } else if (System.getProperty("ij.dir") != null) {
            sb.append("ImageJ");
        } else {
            sb.append("a standalone Java session");
        }
        if (headless && version == null) extra.add("headless mode");
        if (!extra.isEmpty()) sb.append(" (").append(String.join("; ", extra)).append(")");
        return new Result("Launcher", Status.OK, sb.toString(), "");
    }

    private static void addIfSet(final List<String> list, final String prefix, final String value, final String suffix) {
        if (value != null && !value.isBlank()) list.add(prefix + value + suffix);
    }

    static Result checkIntegrations() {
        final List<Integration> available = Integration.available();
        final List<Integration> missing = new ArrayList<>(List.of(Integration.values()));
        missing.removeAll(available);
        final StringBuilder sb = new StringBuilder();
        sb.append(available.size()).append("/").append(Integration.values().length).append(" optional integrations available");
        if (!available.isEmpty()) {
            sb.append(" (");
            available.forEach(i -> sb.append(i.getDisplayName()).append(", "));
            sb.setLength(sb.length() - 2);
            sb.append(")");
        }
        if (missing.isEmpty()) return new Result("Integrations", Status.OK, sb.toString(), "");
        final StringBuilder tip = new StringBuilder("Not installed: ");
        missing.forEach(i -> tip.append(i.getDisplayName())
                .append(i.getUpdateSite() == null ? "" : " (update site: " + i.getUpdateSite() + ")").append(", "));
        tip.setLength(tip.length() - 2);
        // Optional software: its absence is not a limitation of this machine
        return new Result("Integrations", Status.OK, sb.toString(), tip.toString());
    }

    /** Exercises the core pipeline: demo tree loading, metrics and A* tracing on a synthetic image */
    static Result checkSanityTest() {
        final List<String> failures = new ArrayList<>();
        double cableLength = Double.NaN;
        int nodes = -1;
        final long t0 = System.nanoTime();
        try {
            cableLength = sanityTreeLength();
        } catch (final Throwable t) {
            failures.add("demo tree/metrics (" + t + ")");
        }
        try {
            nodes = sanityTrace();
        } catch (final Throwable t) {
            failures.add("A* tracing (" + t + ")");
        }
        final double ms = (System.nanoTime() - t0) / 1e6;
        if (!failures.isEmpty()) return new Result("Sanity test", Status.FAILED,
                "Failed: " + String.join("; ", failures),
                "Update Fiji (Help > Update...) and report the issue with this output at forum.image.sc/tag/snt");
        return new Result("Sanity test", Status.OK, String.format(Locale.US,
                "Measured %.0f units of cable length in demo tree, and traced a %d-node path in a synthetic image (%.0f ms in total)",
                cableLength, nodes, ms), "");
    }

    private static double sanityTreeLength() {
        final Tree tree = SNTUtils.getContext().getService(SNTService.class).demoTree("fractal");
        if (tree == null) throw new IllegalStateException("demo tree could not be loaded");
        final double length = new TreeStatistics(tree).getCableLength();
        if (!(length > 0)) throw new IllegalStateException("invalid cable length: " + length);
        return length;
    }

    /** Traces a bright line (noise background) between its two ends */
    private static int sanityTrace() {
        final int[] dims = {64, 64, 16};
        final int y = 32, z = 8, x0 = 4, x1 = 60;
        final ArrayImg<FloatType, ?> img = ArrayImgs.floats(dims[0], dims[1], dims[2]);
        final java.util.Random rnd = new java.util.Random(42);
        final var cursor = img.localizingCursor();
        while (cursor.hasNext()) {
            cursor.fwd();
            final boolean onLine = cursor.getIntPosition(1) == y && cursor.getIntPosition(2) == z
                    && cursor.getIntPosition(0) >= x0 && cursor.getIntPosition(0) <= x1;
            cursor.get().set(onLine ? 1f : rnd.nextFloat() * 0.1f);
        }
        // Unit calibration (avoids importing ij classes here)
        final BoundingBox bb = new BoundingBox();
        bb.setOrigin(new PointInImage(0, 0, 0));
        final var cal = bb.getCalibration();
        final TracerThread search = new TracerThread(img, cal, x0, y, z, x1, y, z, 10, 1000,
                SNT.SearchImageType.ARRAY, new Reciprocal(0, 1), new Euclidean(cal));
        search.run();
        final Path result = search.getResult();
        if (result == null) throw new IllegalStateException("no path found");
        // A straight line has x1 - x0 + 1 nodes: allow some slack for path refinement
        final int expected = x1 - x0 + 1;
        if (result.size() < 0.9 * expected || result.size() > 1.2 * expected)
            throw new IllegalStateException("unexpected path: " + result.size() + " nodes (expected ~" + expected + ")");
        return result.size();
    }
}
