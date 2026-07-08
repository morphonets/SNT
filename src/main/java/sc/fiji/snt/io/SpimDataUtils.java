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

package sc.fiji.snt.io;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import bdv.cache.SharedQueue;
import bdv.img.imaris.Imaris;
import bdv.spimdata.SpimDataMinimal;
import bdv.spimdata.XmlIoSpimDataMinimal;
import bdv.tools.brightness.ConverterSetup;
import bdv.util.BdvOptions;
import bdv.viewer.SourceAndConverter;
import mpicbg.spim.data.SpimDataException;
import mpicbg.spim.data.generic.AbstractSpimData;
import mpicbg.spim.data.sequence.FinalVoxelDimensions;
import mpicbg.spim.data.sequence.VoxelDimensions;
import net.imagej.ImgPlus;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.numeric.NumericType;
import net.imglib2.view.Views;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.bdv.N5Viewer;
import org.janelia.saalfeldlab.n5.bdv.N5ViewerCreator;
import org.janelia.saalfeldlab.n5.ij.N5Importer;
import org.janelia.saalfeldlab.n5.ui.DataSelection;
import org.janelia.saalfeldlab.n5.universe.N5DatasetDiscoverer;
import org.janelia.saalfeldlab.n5.universe.N5TreeNode;
import org.janelia.saalfeldlab.n5.universe.metadata.N5Metadata;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.util.ImgUtils;

/**
 * Utilities for working with {@link AbstractSpimData} and BigDataViewer XML
 * descriptors.
 *
 * @author Tiago Ferreira
 */
public class SpimDataUtils {

    private SpimDataUtils() {
        // static utility class
    }

    // -- Path resolution --

    /**
     * Resolves a file path to an {@link AbstractSpimData} (for {@code .ims} and
     * {@code .xml} files), an {@link N5Sources} (for {@code .n5}/{@code .zarr}
     * containers), or an {@link ImgPlus} (fallback).
     *
     * @param filePathOrUrl path or URL to the image file
     * @return an {@link AbstractSpimData}, {@link N5Sources}, or {@link ImgPlus}
     * @throws IllegalArgumentException if the file cannot be opened
     */
    public static Object resolvePathToSource(final String filePathOrUrl) {
        final File file = new File(filePathOrUrl);
        final String lower = file.getName().toLowerCase();

        if (lower.endsWith(".ims")) {
            final String basePath = file.getAbsolutePath();
            final String xmlPath = basePath.substring(0, basePath.length() - 4) + ".xml";
            try {
                if (new File(xmlPath).exists()) {
                    SNTUtils.log("BVV: reusing existing XML sidecar: " + xmlPath);
                    return new XmlIoSpimDataMinimal().load(xmlPath);
                }
                final File dir = file.getParentFile();
                if (dir != null && !dir.canWrite()) {
                    throw new IllegalArgumentException(
                            "Cannot write to directory: " + dir.getAbsolutePath() + "\n" +
                                    "Create the BDV XML file manually via " +
                                    "Plugins > BigDataViewer > Create XML for Imaris file, " +
                                    "then use Bvv.open(\"/path/to/dataset.xml\").");
                }
                final SpimDataMinimal spimData = Imaris.openIms(file.getAbsolutePath());
                new XmlIoSpimDataMinimal().save(spimData, xmlPath);
                final String base = file.getName().endsWith(".ims")
                        ? file.getName().substring(0, file.getName().length() - 4)
                        : file.getName();
                patchImsXml(xmlPath, base);
                SNTUtils.log("BVV: created XML sidecar: " + xmlPath);
                return new XmlIoSpimDataMinimal().load(xmlPath);
            } catch (final IOException | SpimDataException e) {
                throw new IllegalArgumentException("Could not open IMS file: " + e.getMessage(), e);
            }
        }

        if (lower.endsWith(".xml")) {
            try {
                return new XmlIoSpimDataMinimal().load(filePathOrUrl);
            } catch (final SpimDataException e) {
                throw new IllegalArgumentException("Could not open XML file: " + e.getMessage(), e);
            }
        }

        if (file.isDirectory() && (lower.endsWith(".zarr") || lower.endsWith(".n5"))) {
            return resolveN5ToSources(file);
        }

        if (file.getParent() != null && (lower.endsWith(".json") && file.getParent().endsWith(".n5")
                        || lower.startsWith(".z") && file.getParent().endsWith(".zarr"))) {
            return resolveN5ToSources(file.getParentFile());
        }

        // Fallback: open as ImgPlus (includes size check before reaching BVV)
        final ImgPlus<?> img = ImgUtils.open(filePathOrUrl);
        if (img == null)
            throw new IllegalArgumentException("Could not open file: " + filePathOrUrl);
        return img;
    }

    // -- IMS XML patching --

    /**
     * Patches an IMS XML sidecar file, replacing all {@code "(name not specified)"}
     * placeholders with sequential channel names derived from the base name.
     *
     * @param xmlPath path to the XML sidecar
     * @param base    base name to use (typically the IMS filename without extension)
     * @throws IOException if the file cannot be read or written
     */
    public static void patchImsXml(final String xmlPath, final String base) throws IOException {
        final Path path = Paths.get(xmlPath);
        String xml = Files.readString(path);
        int ch = 1;
        while (xml.contains("(name not specified)"))
            xml = xml.replaceFirst("\\(name not specified\\)", base + " (Ch" + ch++ + ")");
        Files.writeString(path, xml);
    }

    // -- CalibratedSource --

    /**
     * Wraps a {@link bdv.util.RandomAccessibleIntervalSource} and overrides
     * {@link #getVoxelDimensions()} to carry the physical unit (e.g. "µm").
     * Without this, BDV's {@code ScaleBarOverlayRenderer} reads {@code "pixel"}
     * from {@code RandomAccessibleIntervalSource.getVoxelDimensions()} and the
     * scale bar label is wrong even when calibration is applied.
     *
     * @param <T> the pixel type
     */
    public static class CalibratedSource<T extends NumericType<T>> extends bdv.util.RandomAccessibleIntervalSource<T> {

        private FinalVoxelDimensions voxelDimensions;
        private final AffineTransform3D calibrationTransform;
        private final RandomAccessibleInterval<T> fullRai;
        private final int timeDim; // index of the T axis in fullRai, or -1 if no time axis

        /**
         * Full constructor. When {@code timeDim >= 0}, {@link #getSource(int, int)}
         * slices the RAI along that axis so each BVV timepoint returns the correct
         * frame. {@code RandomAccessibleIntervalSource} ignores the timepoint argument
         * entirely, causing all frames to show frame 0 for timelapse data.
         */
        public CalibratedSource(final RandomAccessibleInterval<T> rai,
                                final T type,
                                final AffineTransform3D sourceTransform,
                                final String name,
                                final double[] cal,
                                final String unit,
                                final int timeDim) {
            super(timeDim >= 0 ? Views.hyperSlice(rai, timeDim, 0) : rai,
                    type, sourceTransform, name);
            this.calibrationTransform = sourceTransform;
            this.fullRai = rai;
            this.timeDim = timeDim;
            this.voxelDimensions = new FinalVoxelDimensions(
                    unit != null && !unit.isBlank() ? unit : "pixel",
                    cal[0], cal[1], cal.length > 2 ? cal[2] : 1.0);
        }

        /** Convenience constructor for sources without a time axis (timeDim = -1). */
        public CalibratedSource(final RandomAccessibleInterval<T> rai,
                                final T type,
                                final AffineTransform3D sourceTransform,
                                final String name,
                                final double[] cal,
                                final String unit) {
            this(rai, type, sourceTransform, name, cal, unit, -1);
        }

        /**
         * Updates the voxel calibration (spacing and unit) of this source.
         * Mutates the source transform diagonal and replaces the
         * {@link VoxelDimensions} so the BVV scale bar reflects the new values.
         *
         * @param cal  voxel spacing {x, y, z}
         * @param unit physical unit string (e.g. "µm")
         */
        public void setCalibration(final double[] cal, final String unit) {
            calibrationTransform.set(cal[0], 0, 0);
            calibrationTransform.set(cal[1], 1, 1);
            calibrationTransform.set(cal.length > 2 ? cal[2] : 1.0, 2, 2);
            voxelDimensions = new FinalVoxelDimensions(
                    unit != null && !unit.isBlank() ? unit : "pixel",
                    cal[0], cal[1], cal.length > 2 ? cal[2] : 1.0);
        }

        @Override
        public RandomAccessibleInterval<T> getSource(final int t, final int level) {
            if (timeDim < 0) return super.getSource(t, level);
            final long tClamped = Math.min(Math.max(t, 0), fullRai.dimension(timeDim) - 1);
            return Views.hyperSlice(fullRai, timeDim, tClamped);
        }

        @Override
        public VoxelDimensions getVoxelDimensions() {
            return voxelDimensions;
        }
    }

    /**
     * Holds the sources produced by opening an N5 or OME-Zarr container via
     * {@code n5-ij}/{@code n5-universe}/{@code n5-viewer_fiji} (see
     * {@link #resolvePathToSource(String)}). Unlike {@link AbstractSpimData},
     * there is no BDV-XML descriptor involved: sources are built directly from
     * the container's own N5/OME-NGFF metadata via
     * {@link N5Viewer#buildN5Sources}.
     */
    public static class N5Sources {

        /** One {@link SourceAndConverter} per channel/setup, in discovery order. */
        public final List<SourceAndConverter<?>> sources;

        /** Number of timepoints shared by all sources. */
        public final int numTimepoints;

        /** Display name derived from the container's directory name. */
        public final String name;

        public N5Sources(final List<SourceAndConverter<?>> sources, final int numTimepoints, final String name) {
            this.sources = sources;
            this.numTimepoints = numTimepoints;
            this.name = name;
        }
    }

    /**
     * Opens an N5 or OME-Zarr container directly via {@code n5-ij}/{@code n5-universe}/{@code n5-viewer_fiji}.
     *
     * @param dir the {@code .n5} or {@code .zarr} directory
     * @return the resolved sources
     * @throws IllegalArgumentException if the container cannot be opened or no
     *                                  recognized metadata is found
     */
    private static N5Sources resolveN5ToSources(final File dir) {
        try {
            final N5Reader n5 = new N5Importer.N5ViewerReaderFun().apply(dir.getAbsolutePath());
            if (n5 == null)
                throw new IllegalArgumentException("Could not open N5/Zarr container: " + dir.getAbsolutePath());

            final N5TreeNode root = N5DatasetDiscoverer.discover(n5,
                    Arrays.asList(N5ViewerCreator.n5vParsers),
                    Arrays.asList(N5ViewerCreator.n5vGroupParsers));
            final List<N5Metadata> found = new ArrayList<>();
            collectMetadata(root, found);
            if (found.isEmpty())
                throw new IllegalArgumentException(
                        "No recognized N5/OME-NGFF metadata found at '" + dir.getAbsolutePath() + "'.");

            final List<N5Metadata> selected = N5Viewer.unwrapMultichannelSelections(new DataSelection(n5, found));
            SNTUtils.log("BVV: opening N5/Zarr container via n5-viewer_fiji: " + dir.getAbsolutePath()
                    + " (" + found.size() + " dataset(s) found)");
            return buildN5Sources(n5, selected, dir.getName());
        } catch (final IOException | RuntimeException e) {
            throw new IllegalArgumentException("Could not open N5/Zarr container: " + e.getMessage(), e);
        }
    }

    /**
     * Recursively collects the metadata of every node in the tree that has any, without descending into a matched
     * node's own children (e.g. resolution-level subfolders inside a multiscale group, which are not separate datasets)
     */
    private static void collectMetadata(final N5TreeNode node, final List<N5Metadata> found) {
        final N5Metadata m = node.getMetadata();
        if (m != null) {
            found.add(m);
            return;
        }
        for (final N5TreeNode child : node.childrenList())
            collectMetadata(child, found);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static N5Sources buildN5Sources(
            final N5Reader n5, final List<N5Metadata> selected, final String name) throws IOException {
        final SharedQueue sharedQueue = new SharedQueue(Math.max(1, Runtime.getRuntime().availableProcessors() / 2));
        final List<ConverterSetup> converterSetups = new ArrayList<>();
        final List<SourceAndConverter<?>> sourcesAndConverters = new ArrayList<>();
        final int numTimepoints = N5Viewer.buildN5Sources(
                n5, selected, sharedQueue, converterSetups, (List) sourcesAndConverters, BdvOptions.options());
        if (sourcesAndConverters.isEmpty())
            throw new IllegalArgumentException("No displayable sources found for '" + name + "'.");
        return new N5Sources(new ArrayList<>(sourcesAndConverters), numTimepoints, name);
    }

    /**
     * Writes a BDV-compatible XML descriptor for an N5 dataset. The resulting
     * XML can be opened by BigDataViewer, BigVolumeViewer, or any BDV-based
     * tool via {@code XmlIoSpimDataMinimal.load()}.
     * <p>
     * The descriptor references the N5 container using a relative path (assumes
     * the XML sits next to the N5 directory). A single ViewSetup (id&nbsp;0) is
     * created with the supplied voxel size and calibration unit.
     *
     * @param xmlFile     the XML file to write (will be overwritten if it exists)
     * @param n5DirName   name of the N5 container directory (relative to the XML
     *                    file's parent; e.g., {@code "dataset_unmixed"})
     * @param levelDims   per-level dimensions: {@code levelDims[level] = {x, y, z}}
     * @param voxelSize   physical voxel size at level&nbsp;0: {@code {sx, sy, sz}}
     * @param unit        calibration unit (e.g., {@code "um"}, {@code "pixel"})
     * @param nTimepoints number of timepoints in the dataset
     * @param setupName   display name for the single ViewSetup (e.g.,
     *                    {@code "unmixed"})
     * @throws IOException if the file cannot be written
     */
    public static void writeBdvN5Xml(final File xmlFile,
                                      final String n5DirName,
                                      final long[][] levelDims,
                                      final double[] voxelSize,
                                      final String unit,
                                      final int nTimepoints,
                                      final String setupName) throws IOException {
        writeBdvN5Xml(xmlFile, n5DirName, levelDims, voxelSize, unit, nTimepoints, setupName, 1);
    }

    public static void writeBdvN5Xml(final File xmlFile,
                                      final String n5DirName,
                                      final long[][] levelDims,
                                      final double[] voxelSize,
                                      final String unit,
                                      final int nTimepoints,
                                      final String setupName,
                                      final int nChannels) throws IOException {
        final int nLevels = levelDims.length;
        final String vs = String.format(Locale.US, "%g %g %g",
                voxelSize[0], voxelSize[1], voxelSize[2]);
        final String sizeStr = String.format("%d %d %d",
                levelDims[0][0], levelDims[0][1], levelDims[0][2]);

        final StringBuilder subdivisions = new StringBuilder();
        final StringBuilder resolutions = new StringBuilder();
        for (int level = 0; level < nLevels; level++) {
            final long[] ld = levelDims[level];
            final int scale = (int) Math.pow(2, level);
            subdivisions.append(String.format("          %d %d %d\n",
                    Math.min(ld[0], 64), Math.min(ld[1], 64), Math.min(ld[2], 64)));
            resolutions.append(String.format("          %d %d %d\n",
                    scale, scale, scale));
        }

        // ViewSetups: one per channel
        final StringBuilder viewSetups = new StringBuilder();
        final StringBuilder channels = new StringBuilder();
        for (int ch = 0; ch < nChannels; ch++) {
            final String chName = nChannels > 1 ? setupName + " Ch" + ch : setupName;
            viewSetups.append(String.format(Locale.US,
                    "      <ViewSetup>\n" +
                    "        <id>%d</id>\n" +
                    "        <name>%s</name>\n" +
                    "        <size>%s</size>\n" +
                    "        <voxelSize>\n" +
                    "          <unit>%s</unit>\n" +
                    "          <size>%s</size>\n" +
                    "        </voxelSize>\n" +
                    "        <attributes>\n" +
                    "          <channel>%d</channel>\n" +
                    "        </attributes>\n" +
                    "      </ViewSetup>\n",
                    ch, chName, sizeStr, unit, vs, ch));
            channels.append(String.format(
                    "        <Channel>\n" +
                    "          <id>%d</id>\n" +
                    "          <name>%s</name>\n" +
                    "        </Channel>\n",
                    ch, chName));
        }

        // MipmapResolutions: one block per setup (all share same pyramid)
        final StringBuilder mipmaps = new StringBuilder();
        for (int ch = 0; ch < nChannels; ch++) {
            mipmaps.append(String.format(
                    "    <MipmapResolutions setup=\"%d\">\n" +
                    "      <subdivisions>\n" +
                    "%s" +
                    "      </subdivisions>\n" +
                    "      <resolutions>\n" +
                    "%s" +
                    "      </resolutions>\n" +
                    "    </MipmapResolutions>\n",
                    ch, subdivisions, resolutions));
        }

        // ViewRegistrations: one per timepoint × channel
        final StringBuilder registrations = new StringBuilder();
        for (int t = 0; t < nTimepoints; t++) {
            for (int ch = 0; ch < nChannels; ch++) {
                registrations.append(String.format(Locale.US,
                        "      <ViewRegistration timepoint=\"%d\" setup=\"%d\">\n" +
                        "        <ViewTransform type=\"affine\">\n" +
                        "          <affine>%g 0.0 0.0 0.0 0.0 %g 0.0 0.0 0.0 0.0 %g 0.0</affine>\n" +
                        "        </ViewTransform>\n" +
                        "      </ViewRegistration>\n",
                        t, ch, voxelSize[0], voxelSize[1], voxelSize[2]));
            }
        }

        final String xml = String.format(
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<SpimData version=\"0.2\">\n" +
                "  <BasePath type=\"relative\">.</BasePath>\n" +
                "  <SequenceDescription>\n" +
                "    <ImageLoader format=\"bdv.n5\" version=\"1.0\">\n" +
                "      <n5 type=\"relative\">%s</n5>\n" +
                "    </ImageLoader>\n" +
                "    <ViewSetups>\n" +
                "%s" +
                "      <Attributes name=\"channel\">\n" +
                "%s" +
                "      </Attributes>\n" +
                "    </ViewSetups>\n" +
                "    <Timepoints type=\"range\">\n" +
                "      <first>0</first>\n" +
                "      <last>%d</last>\n" +
                "    </Timepoints>\n" +
                "%s" +
                "  </SequenceDescription>\n" +
                "  <ViewRegistrations>\n" +
                "%s" +
                "  </ViewRegistrations>\n" +
                "</SpimData>\n",
                n5DirName, viewSetups, channels, nTimepoints - 1,
                mipmaps, registrations);

        try (final PrintWriter pw = new PrintWriter(xmlFile)) {
            pw.print(xml);
        }
    }
}
