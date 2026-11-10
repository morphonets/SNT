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

package sc.fiji.snt.gui;

import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.util.BigDataUtils;
import sc.fiji.snt.util.IOUtils;

import javax.swing.SwingUtilities;
import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Classifies files and folders (e.g., those dropped onto a window) without ever
 * blocking the caller for longer than a fixed budget. Intended to decide what
 * to do with them before anything is imported.
 * <p>
 * Design rules:
 * </p>
 * <ul>
 * <li>All file system access happens on daemon worker threads. Blocking I/O
 * (e.g., on a stalled network mount) cannot be interrupted, so probes that
 * exceed their budget are abandoned, not canceled, and the result flags the
 * item as {@link Kind#UNREACHABLE}</li>
 * <li>A circuit breaker skips further probes under a mount that recently timed
 * out</li>
 * <li>Folders are classified from the names of a few sentinel files (session,
 * Zarr and N5 datasets) without being listed. Other folders are listed
 * shallowly, up to a fixed number of entries</li>
 * <li>Files are classified from their names. Only zip archives are sniffed:
 * the central directory is read, but only its first entries are examined</li>
 * <li>Nothing is opened, parsed or imported</li>
 * </ul>
 *
 * @author Tiago Ferreira
 */
public final class FileInspector {

	/** The kinds of items recognized by the inspector */
	public enum Kind {
		SESSION_DIR("SNT session"), SESSIONS_DIR("SNT sessions folder"),
		ZARR_DIR("Zarr dataset"), N5_DIR("N5 dataset"), DIRECTORY("folder"),
		TRACES(".traces file", IOUtils.FileType.TRACES), SWC("SWC file", IOUtils.FileType.SWC),
		JSON("JSON file", IOUtils.FileType.JSON), NDF("NDF file", IOUtils.FileType.NDF),
		XML("XML file", IOUtils.FileType.XML), IMAGE("image", IOUtils.FileType.IMAGE),
		IMS("Imaris file", IOUtils.FileType.IMS), BDV_XML("BDV dataset"), ROI_ZIP("ROI set"),
		RECONSTRUCTION_ZIP("reconstruction archive"), ROI("ROI file", IOUtils.FileType.ROI),
		CSV("CSV file", IOUtils.FileType.CSV), ZIP("zip archive", IOUtils.FileType.ZIP),
		UNKNOWN("unrecognized item", IOUtils.FileType.UNKNOWN), MISSING("missing item"),
		UNREACHABLE("unreachable item");

		private final String label;

		// The kind of file this is for file types recognized by name alone. Null otherwise
		private final IOUtils.FileType type;

		Kind(final String label) {
			this(label, null);
		}

		Kind(final String label, final IOUtils.FileType type) {
			this.label = label;
			this.type = type;
		}

		/** @return the kind that corresponds to a file type recognized by name */
		static Kind of(final IOUtils.FileType type) {
			for (final Kind k : values()) {
				if (k.type == type) return k;
			}
			return UNKNOWN;
		}

		/** @return a human readable singular label */
		public String label() {
			return label;
		}

		/** @return whether this is a (folder of) SNT session(s) */
		public boolean isSession() {
			return this == SESSION_DIR || this == SESSIONS_DIR;
		}

		/** @return whether this is a (streamable) big data dataset: Imaris, BDV XML, N5 or Zarr */
		public boolean isDataset() {
			return this == IMS || this == BDV_XML || this == ZARR_DIR || this == N5_DIR;
		}

		/** @return whether this is a reconstruction file by name (XML included: it may be Neurolucida) */
		public boolean isReconstructionCandidate() {
			return type != null && type.isReconstructionCandidate();
		}
	}

	/**
	 * Limits applied to every inspection.
	 *
	 * @param perPath maximum time to wait for the inspection of a single path
	 * @param total maximum time for the whole inspection
	 * @param maxPaths maximum number of paths inspected. Others are skipped
	 * @param maxDirEntries maximum number of entries listed in a folder
	 * @param maxZipBytes zip archives larger than this are not sniffed
	 */
	public record Budget(Duration perPath, Duration total, int maxPaths,
			int maxDirEntries, long maxZipBytes) {

		public Budget {
			Objects.requireNonNull(perPath);
			Objects.requireNonNull(total);
			if (perPath.isNegative() || total.isNegative() || maxPaths < 0 ||
				maxDirEntries < 0 || maxZipBytes < 0) throw new IllegalArgumentException(
					"Negative budget");
		}

		/** @return 2s per path, 5s overall, 500 paths, 200 entries, 4GB (only the central directory is read) */
		public static Budget defaults() {
			return new Budget(Duration.ofSeconds(2), Duration.ofSeconds(5), 500, 200,
				4L * 1024 * 1024 * 1024);
		}
	}

	/**
	 * The classification of a single path.
	 *
	 * @param file the inspected path
	 * @param kind the kind of item
	 * @param entries for folders that were listed, the number of entries read
	 * @param truncated whether the listing was cut short by the entry limit
	 * @param contents for folders that were listed, the number of entries of
	 *          each recognized kind (classified by name only)
	 */
	public record Item(File file, Kind kind, int entries, boolean truncated,
			Map<Kind, Integer> contents) {

		/** @return whether this is a folder that holds reconstruction files (classified by name) at its top level */
		public boolean hasReconstructions() {
			return contents.entrySet().stream().anyMatch(e -> e.getKey().isReconstructionCandidate() && e.getValue() > 0);
		}

		/** @return whether {@link #describe()} says more than the kind itself (i.e., recognized contents were found) */
		public boolean hasDetails() {
			return !contents.isEmpty() || truncated;
		}

		/** @return a short description, e.g., "folder (12 SWC files, 3 images)" */
		public String describe() {
			if (contents.isEmpty() && !truncated) return kind.label();
			final String c = describeCounts(contents);
			return kind.label() + " (" + c + ((truncated) ? (c.isEmpty() ? "" : "; ") +
				entries + "+ entries" : "") + ")";
		}
	}

	/**
	 * The outcome of an inspection.
	 *
	 * @param items the classification of each inspected path, in input order
	 * @param skipped number of paths that were not inspected because the
	 *          path limit was exceeded
	 * @param degraded whether at least one path could not be inspected in time
	 */
	public record Result(List<Item> items, int skipped, boolean degraded) {

		public long count(final Kind kind) {
			return items.stream().filter(i -> i.kind() == kind).count();
		}

		/** @return the kind shared by all items, or null if kinds are mixed/empty */
		public Kind uniformKind() {
			if (items.isEmpty()) return null;
			final Kind k = items.getFirst().kind();
			return items.stream().allMatch(i -> i.kind() == k) ? k : null;
		}

		/** @return a short description, e.g., "2 SWC files, 1 folder" */
		public String describe() {
			final Map<Kind, Integer> counts = new EnumMap<>(Kind.class);
			items.forEach(i -> counts.merge(i.kind(), 1, Integer::sum));
			final String s = describeCounts(counts);
			return (skipped > 0) ? s + " and " + skipped + " more" : s;
		}
	}

	private static final String[] ZARR_SENTINELS = { ".zgroup", ".zattrs", "zarr.json" };
	private static final String SESSION_SENTINEL = IOUtils.SESSION_MANIFEST;
	private static final String SESSION_PREFIX = IOUtils.SESSION_PREFIX;
	private static final String N5_SENTINEL = "attributes.json";
	private static final int ZIP_SNIFF_ENTRIES = 50;
	private static final Duration STUCK_TTL = Duration.ofSeconds(60);
	// Blocked probes cannot be interrupted and keep their worker thread. Limiting the number of
	// abandoned (timed out, but still running) probes per mount ensures that a stalled mount cannot
	// starve the shared pool. Healthy mounts are not limited
	private static final int MAX_ABANDONED_PER_MOUNT = 3;

	private static final AtomicInteger THREAD_COUNTER = new AtomicInteger();
	private static final ThreadPoolExecutor PROBES = newPool("SNT-FileProbe", 16);
	private static final ThreadPoolExecutor ORCHESTRATORS = newPool("SNT-FileInspector", 2);
	private static FileInspector shared;

	private final Budget budget;
	private final Function<File, Item> probe;
	// Mount keys that recently timed out, and when to forget them (nanoTime)
	private final Map<String, Long> stuck = new ConcurrentHashMap<>();
	// Number of abandoned probes that have not finished yet, by mount key
	private final Map<String, AtomicInteger> abandoned = new ConcurrentHashMap<>();

	public FileInspector() {
		this(Budget.defaults());
	}

	public FileInspector(final Budget budget) {
		this.budget = Objects.requireNonNull(budget);
		this.probe = this::probe;
	}

	// For tests: allows simulating probes that block
	FileInspector(final Budget budget, final Function<File, Item> probe) {
		this.budget = Objects.requireNonNull(budget);
		this.probe = Objects.requireNonNull(probe);
	}

	/** @return a shared inspector using the default budget */
	public static synchronized FileInspector shared() {
		if (shared == null) shared = new FileInspector();
		return shared;
	}

	/**
	 * Inspects the specified files in the background. Never blocks the caller,
	 * and never throws.
	 *
	 * @param files the files to be inspected
	 * @param callback receives the result on the Event Dispatch Thread. It is
	 *          always called exactly once, unless the returned future is
	 *          canceled before the inspection completes
	 * @return a future that can be used to discard the result
	 */
	public Future<?> inspect(final File[] files, final Consumer<Result> callback) {
		final File[] copy = (files == null) ? new File[0] : files.clone();
		try {
			return ORCHESTRATORS.submit(() -> {
				Result r;
				try {
					r = inspectNow(copy);
				} catch (final Throwable t) {
					SNTUtils.error("FileInspector: inspection failed", t);
					r = unreachable(copy);
				}
				deliver(callback, r);
			});
		} catch (final RejectedExecutionException ex) {
			deliver(callback, unreachable(copy));
			return java.util.concurrent.CompletableFuture.completedFuture(null);
		}
	}

	private static void deliver(final Consumer<Result> callback,
		final Result result)
	{
		SwingUtilities.invokeLater(() -> {
			try {
				callback.accept(result);
			} catch (final Throwable t) {
				SNTUtils.error("FileInspector: callback failed", t);
			}
		});
	}

	/**
	 * Inspects the specified files, blocking for at most the total budget. Must
	 * not be called from the Event Dispatch Thread.
	 *
	 * @param files the files to be inspected
	 * @return the result. Never null
	 * @throws IllegalStateException if called from the Event Dispatch Thread
	 */
	public Result inspectNow(final File... files) {
		if (SwingUtilities.isEventDispatchThread()) throw new IllegalStateException(
			"FileInspector must not be called from the EDT");
		final List<File> input = (files == null) ? List.of() : Arrays.stream(files).filter(Objects::nonNull).toList();
		final int n = Math.min(input.size(), budget.maxPaths());
		final long deadline = System.nanoTime() + budget.total().toNanos();
		final List<Probe> probes = new ArrayList<>(n);
		for (int i = 0; i < n; i++) {
			final File f = input.get(i);
			probes.add(isStuck(f) ? null : submit(f));
		}
		final List<Item> items = new ArrayList<>(n);
		boolean degraded = false;
		for (int i = 0; i < n; i++) {
			final File f = input.get(i);
			final Probe pr = probes.get(i);
			Item item = null;
			if (pr != null) {
				final long perPath = budget.perPath().toNanos();
				final long wait = Math.min(perPath, deadline - System.nanoTime());
				try {
					item = pr.future.get(Math.max(wait, 0), TimeUnit.NANOSECONDS);
				} catch (final TimeoutException ex) {
					pr.future.cancel(true);
					// A probe that never started was only queued behind others: it says nothing about its mount
					if (pr.started.get()) {
						pr.abandon();
						// Only blame the mount if the probe ran for its whole budget: when the total budget ran
						// out, it may simply have been waiting behind slower items
						if (wait >= perPath) markStuck(f);
					}
				} catch (final ExecutionException ex) {
					SNTUtils.log("FileInspector: probe failed for " + f + ": " + ex
						.getCause());
				} catch (final InterruptedException ex) {
					Thread.currentThread().interrupt();
					pr.future.cancel(true);
					if (pr.started.get()) pr.abandon();
				}
			}
			if (item == null) {
				degraded = true;
				item = item(f, Kind.UNREACHABLE);
			}
			items.add(item);
		}
		return new Result(Collections.unmodifiableList(items), input.size() - n,
			degraded);
	}

	private static Result unreachable(final File[] files) {
		final List<Item> items = Arrays.stream(files).filter(Objects::nonNull).map(
			f -> item(f, Kind.UNREACHABLE)).toList();
		return new Result(items, 0, true);
	}

	// A submitted probe. Once abandoned, it counts against its mount until it actually finishes
	private static final class Probe {

		Future<Item> future;
		final AtomicBoolean started = new AtomicBoolean();
		private final AtomicInteger counter;
		private boolean finished;
		private boolean abandoned;

		Probe(final AtomicInteger counter) {
			this.counter = counter;
		}

		synchronized void abandon() {
			if (finished || abandoned) return;
			abandoned = true;
			counter.incrementAndGet();
		}

		synchronized void finish() {
			finished = true;
			if (abandoned) {
				abandoned = false;
				counter.decrementAndGet();
			}
		}
	}

	// Returns null if the mount has too many abandoned probes, or if the pool is saturated
	private Probe submit(final File f) {
		final AtomicInteger counter = abandoned.computeIfAbsent(mountKey(f), k -> new AtomicInteger());
		if (counter.get() >= MAX_ABANDONED_PER_MOUNT) return null;
		final Probe pr = new Probe(counter);
		try {
			pr.future = PROBES.submit(() -> {
				pr.started.set(true);
				try {
					return probe.apply(f);
				} finally {
					pr.finish();
				}
			});
		} catch (final RejectedExecutionException ex) {
			return null;
		}
		return pr;
	}

	// Circuit breaker

	private boolean isStuck(final File f) {
		final String key = mountKey(f);
		final Long expiry = stuck.get(key);
		if (expiry == null) return false;
		if (System.nanoTime() - expiry < 0) return true;
		stuck.remove(key);
		return false;
	}

	private void markStuck(final File f) {
		stuck.put(mountKey(f), System.nanoTime() + STUCK_TTL.toNanos());
	}

	/** Root plus first two name elements, e.g., /Volumes/share or \\server\share */
	static String mountKey(final File f) {
		try {
			final Path p = f.toPath().toAbsolutePath().normalize();
			final StringBuilder sb = new StringBuilder(String.valueOf(p.getRoot()));
			for (int i = 0; i < Math.min(2, p.getNameCount()); i++)
				sb.append('/').append(p.getName(i));
			return sb.toString();
		} catch (final InvalidPathException | SecurityException ex) {
			return f.getPath();
		}
	}

	// Probes. These run on worker threads, and may block

	private Item probe(final File f) {
		final Path p;
		try {
			p = f.toPath();
		} catch (final InvalidPathException ex) {
			return item(f, Kind.UNKNOWN);
		}
		final BasicFileAttributes attrs;
		try {
			attrs = Files.readAttributes(p, BasicFileAttributes.class);
		} catch (final NoSuchFileException ex) {
			return item(f, Kind.MISSING);
		} catch (final IOException | SecurityException ex) {
			return item(f, Kind.UNREACHABLE);
		}
		if (attrs.isDirectory()) return probeDirectory(p, f);
		if (!attrs.isRegularFile()) return item(f, Kind.UNKNOWN);
		Kind kind = kindOfName(f.getName());
		if (kind == Kind.ZIP && attrs.size() <= budget.maxZipBytes()) kind = classifyZip(p);
		if (kind == Kind.XML && BigDataUtils.isSpimDataXml(f)) kind = Kind.BDV_XML;
		return item(f, kind);
	}

	private Item probeDirectory(final Path p, final File f) {
		final String name = f.getName().toLowerCase(Locale.ROOT);
		// Sentinels: classified without listing the folder
		if (Files.exists(p.resolve(SESSION_SENTINEL))) return item(f,
			Kind.SESSION_DIR);
		if (name.endsWith(".zarr") || anyExists(p, ZARR_SENTINELS)) return item(f,
			Kind.ZARR_DIR);
		if (name.endsWith(".n5") || Files.exists(p.resolve(N5_SENTINEL))) return item(
			f, Kind.N5_DIR);
		// Shallow, capped listing
		final Map<Kind, Integer> contents = new EnumMap<>(Kind.class);
		int entries = 0;
		int sessions = 0;
		boolean truncated = false;
		try (DirectoryStream<Path> ds = Files.newDirectoryStream(p)) {
			for (final Path child : ds) {
				if (entries >= budget.maxDirEntries()) {
					truncated = true;
					break;
				}
				entries++;
				final String cn = String.valueOf(child.getFileName());
				if (cn.startsWith(SESSION_PREFIX)) {
					sessions++;
					continue;
				}
				if (cn.startsWith(".")) continue;
				final Kind k = kindOfName(cn);
				if (k != Kind.UNKNOWN) contents.merge(k, 1, Integer::sum);
			}
		} catch (final IOException | SecurityException ex) {
			return item(f, Kind.UNREACHABLE);
		}
		final Kind kind = (sessions > 0) ? Kind.SESSIONS_DIR : Kind.DIRECTORY;
		if (sessions > 0) contents.put(Kind.SESSION_DIR, sessions);
		return new Item(f, kind, entries, truncated, Collections.unmodifiableMap(
			contents));
	}

	private static boolean anyExists(final Path dir, final String... names) {
		for (final String n : names) {
			if (Files.exists(dir.resolve(n))) return true;
		}
		return false;
	}

	// ROI sets win over reconstruction archives
	private static Kind classifyZip(final Path zip) {
		return switch (IOUtils.sniffZip(zip.toFile(), ZIP_SNIFF_ENTRIES)) {
			case ROIS -> Kind.ROI_ZIP;
			case RECONSTRUCTIONS -> Kind.RECONSTRUCTION_ZIP;
			case OTHER -> Kind.ZIP;
		};
	}

	/** Classifies from the file name only (no I/O) */
	static Kind kindOfName(final String name) {
		return Kind.of(IOUtils.getType(name));
	}

	private static Item item(final File f, final Kind kind) {
		return new Item(f, kind, 0, false, Map.of());
	}

	private static String describeCounts(final Map<Kind, Integer> counts) {
		final List<String> parts = new ArrayList<>();
		counts.forEach((k, n) -> parts.add(n + " " + k.label() + ((n > 1) ? "s" :
			"")));
		return String.join(", ", parts);
	}

	private static ThreadPoolExecutor newPool(final String prefix,
		final int threads)
	{
		final ThreadFactory tf = r -> {
			final Thread t = new Thread(r, prefix + "-" + THREAD_COUNTER
				.incrementAndGet());
			t.setDaemon(true);
			return t;
		};
		final ThreadPoolExecutor pool = new ThreadPoolExecutor(threads, threads,
			30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(2048), tf);
		pool.allowCoreThreadTimeOut(true);
		return pool;
	}
}
