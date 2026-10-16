package sc.fiji.snt.viewer;

import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;

import javax.swing.Timer;

import net.imglib2.realtransform.AffineTransform3D;
import sc.fiji.snt.Path;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.util.PointInImage;

/**
 * Plays a walkthrough along paths in a {@link AbstractBigViewer}: the view is centered on a point that travels along
 * each path at constant speed. The camera only translates (rotation is preserved, and the zoom is left untouched
 * unless a field of view is requested), so the user may rotate the view during playback.
 * <p>
 * The distance covered per tick is fixed (speed * tick period), i.e., it does not depend on how fast the viewer
 * renders: every playback samples the same positions, and the total elapsed time reflects how well the viewer keeps
 * up. This makes playback suitable for reproducible performance tests. Clicking on the display aborts playback.
 * </p>
 *
 * @author Tiago Ferreira
 */
public class PathPlayer {

	private static final int PERIOD_MS = 33;
	private static final double STALL_MS = 150;

	private final AbstractBigViewer viewer;
	private final List<double[][]> polylines = new ArrayList<>();
	private final List<String> names = new ArrayList<>();
	private final double speed; // world units per second
	private final double fov; // width of the field of view in world units, <= 0 to keep current zoom
	private final double stepLength;
	private Timer timer;
	private int pathIdx;
	private int segIdx;
	private double segPos; // distance travelled within current segment
	private long startNs;
	private long lastTickNs;
	private long ticks;
	private long stalls;
	private double maxGapMs;
	private double totalLength;
	private boolean finished;
	private boolean aborted;
	private Runnable onFinished;
	private Consumer<String> onPath;
	private String summary;

	PathPlayer(final AbstractBigViewer viewer, final Collection<Path> paths, final double speed, final double fov) {
		this.viewer = viewer;
		this.speed = (speed > 0) ? speed : 50;
		this.fov = fov;
		this.stepLength = this.speed * PERIOD_MS / 1000d;
		for (final Path p : paths) {
			if (p == null || p.size() == 0) continue;
			final double[][] pts = new double[p.size()][];
			for (int i = 0; i < pts.length; i++) {
				final PointInImage n = p.getNode(i);
				pts[i] = new double[] { n.x, n.y, n.z };
			}
			polylines.add(pts);
			names.add(p.getName());
			totalLength += length(pts);
		}
	}

	private static double length(final double[][] pts) {
		double l = 0;
		for (int i = 1; i < pts.length; i++)
			l += dist(pts[i - 1], pts[i]);
		return l;
	}

	private static double dist(final double[] a, final double[] b) {
		final double dx = a[0] - b[0], dy = a[1] - b[1], dz = a[2] - b[2];
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}

	/**
	 * Sets a callback to be run (on the EDT) once playback ends, aborted or not
	 *
	 * @param r the callback
	 * @return this player, for chaining
	 */
	public PathPlayer onFinished(final Runnable r) {
		this.onFinished = r;
		return this;
	}

	/**
	 * Sets a callback notified (on the EDT) with the name of each path as playback reaches it
	 *
	 * @param c the callback
	 * @return this player, for chaining
	 */
	public PathPlayer onPath(final Consumer<String> c) {
		this.onPath = c;
		return this;
	}

	/** @return true if playback is in progress */
	public boolean isPlaying() {
		return timer != null && timer.isRunning();
	}

	/** @return true if playback ended because it was stopped before the end of the last path */
	public boolean isAborted() {
		return aborted;
	}

	/** @return the total length of the paths (world units) */
	public double getTotalLength() {
		return totalLength;
	}

	/**
	 * @return a one-line summary of the last playback (elapsed time, ticks, stalls), or null if playback has not
	 *         finished
	 */
	public String getSummary() {
		return summary;
	}

	/**
	 * Starts playback (non-blocking). Does nothing if already playing or if there is nothing to play
	 *
	 * @return this player, for chaining
	 */
	public synchronized PathPlayer play() {
		if (isPlaying() || polylines.isEmpty()) return this;
		pathIdx = 0;
		segIdx = 0;
		segPos = 0;
		ticks = 0;
		stalls = 0;
		maxGapMs = 0;
		finished = false;
		aborted = false;
		summary = null;
		startNs = System.nanoTime();
		lastTickNs = startNs;
		announce();
		timer = new Timer(PERIOD_MS, e -> tick());
		timer.setCoalesce(true);
		timer.start();
		return this;
	}

	/** Stops playback. Safe to call at any time */
	public void stop() {
		if (finished) return;
		aborted = true;
		finish();
	}

	private void announce() {
		if (onPath != null) onPath.accept(names.get(pathIdx));
		viewer.showViewerMessage(String.format("Playing %s (%d/%d)", names.get(pathIdx), pathIdx + 1, polylines.size()));
	}

	private void tick() {
		final long now = System.nanoTime();
		final double gapMs = (now - lastTickNs) / 1e6;
		BvvProfiler.stop("play.tick interval", lastTickNs);
		lastTickNs = now;
		if (ticks > 0) {
			maxGapMs = Math.max(maxGapMs, gapMs);
			if (gapMs > STALL_MS) stalls++;
		}
		ticks++;
		final double[] pos = advance();
		if (pos == null) {
			finish();
			return;
		}
		center(pos);
	}

	/** Advances stepLength along the paths. Returns the new position, or null if the end was reached */
	private double[] advance() {
		double remaining = stepLength;
		while (true) {
			final double[][] pts = polylines.get(pathIdx);
			if (pts.length == 1 || segIdx >= pts.length - 1) {
				// Path exhausted: move on to the next path
				if (++pathIdx >= polylines.size()) return null;
				segIdx = 0;
				segPos = 0;
				announce();
				return polylines.get(pathIdx)[0].clone();
			}
			final double segLen = dist(pts[segIdx], pts[segIdx + 1]);
			final double left = segLen - segPos;
			if (remaining < left) {
				segPos += remaining;
				final double f = (segLen > 0) ? segPos / segLen : 0;
				final double[] a = pts[segIdx], b = pts[segIdx + 1];
				return new double[] { a[0] + f * (b[0] - a[0]), a[1] + f * (b[1] - a[1]), a[2] + f * (b[2] - a[2]) };
			}
			remaining -= left;
			segIdx++;
			segPos = 0;
		}
	}

	private void center(final double[] p) {
		final int cw = viewer.getViewerWidth(), ch = viewer.getViewerHeight();
		if (cw <= 0 || ch <= 0) return;
		final AffineTransform3D t = viewer.getViewerTransform();
		if (fov > 0) {
			// Rescale rotation/scale block to the requested field of view, preserving rotation
			final double s0 = Math.sqrt(t.get(0, 0) * t.get(0, 0) + t.get(1, 0) * t.get(1, 0) + t.get(2, 0) * t.get(2, 0));
			final double f = (s0 > 0 && Double.isFinite(s0)) ? (cw / fov) / s0 : 1;
			for (int r = 0; r < 3; r++)
				for (int c = 0; c < 3; c++)
					t.set(t.get(r, c) * f, r, c);
		}
		// screenPos = R * worldPos + translation, so translation = screenCenter - R * worldPos
		t.set(0, 0, 3);
		t.set(0, 1, 3);
		t.set(0, 2, 3);
		final double[] m = new double[3];
		t.apply(p, m);
		t.set(cw / 2d - m[0], 0, 3);
		t.set(ch / 2d - m[1], 1, 3);
		t.set(-m[2], 2, 3);
		viewer.setViewerTransformImmediate(t);
	}

	private synchronized void finish() {
		if (finished) return;
		finished = true;
		if (timer != null) timer.stop();
		final double elapsed = (System.nanoTime() - startNs) / 1e9;
		final double ideal = ticks * PERIOD_MS / 1000d;
		summary = String.format("Path playback %s: %.1f s elapsed (%.1f s ideal), %d ticks, %d tick gaps >%.0f ms, "
				+ "max gap %.0f ms, %.0f units at %.1f units/s", aborted ? "aborted" : "finished", elapsed, ideal, ticks,
				stalls, STALL_MS, maxGapMs, totalLength, speed);
		SNTUtils.log(summary);
		if (BvvProfiler.isEnabled()) System.out.println("[SNT-prof] " + summary);
		if (onFinished != null) onFinished.run();
	}

	/** Mouse listener that aborts the player it is currently pointed at */
	static final class AbortListener extends MouseAdapter {
		volatile PathPlayer current;

		@Override
		public void mousePressed(final MouseEvent e) {
			final PathPlayer p = current;
			if (p != null && p.isPlaying()) p.stop();
		}
	}
}
