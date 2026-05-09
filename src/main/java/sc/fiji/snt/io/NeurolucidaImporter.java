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

import org.w3c.dom.*;
import org.xml.sax.SAXException;
import sc.fiji.snt.Path;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.Tree;
import sc.fiji.snt.util.PointInImage;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.awt.Color;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

/**
 * Importer for Neurolucida XML files (Neuromorphological File Specification /
 * NMF format). Parses {@code <tree>} elements into SNT {@link Path}/{@link Tree}
 * objects and {@code <marker>} elements into bookmark data (centroid coordinates).
 * Contours and vessels are skipped in this implementation.
 *
 * @author Tiago Ferreira
 * @see <a href="https://neuromorphological-file-specification.readthedocs.io/">NMF Specification</a>
 */
public class NeurolucidaImporter {

    private final Document doc;
    private double xScale = 1.0; // µm per pixel (from <scale>)
    private double yScale = 1.0;
    private double zSpacing = 1.0; // µm per slice (from <zspacing>)
    private final String spacingUnits = "µm";
    private final List<Tree> trees = new ArrayList<>();
    private final List<double[]> markerPoints = new ArrayList<>(); // x,y,z centroids
    private final List<String> markerLabels = new ArrayList<>();
    private final List<Color> markerColors = new ArrayList<>();

    /**
     * Creates a new importer from a file path.
     *
     * @param filePath the absolute path to the Neurolucida XML file
     * @throws IOException if the file cannot be read or parsed
     */
    public NeurolucidaImporter(final String filePath) throws IOException {
        this(new BufferedInputStream(Files.newInputStream(Paths.get(filePath))));
    }

    /**
     * Creates a new importer from a File.
     *
     * @param file the Neurolucida XML file
     * @throws IOException if the file cannot be read or parsed
     */
    public NeurolucidaImporter(final File file) throws IOException {
        this(new BufferedInputStream(Files.newInputStream(file.toPath())));
    }

    /**
     * Creates a new importer from an InputStream.
     *
     * @param is the input stream containing the Neurolucida XML data
     * @throws IOException if the stream cannot be read or parsed
     */
    public NeurolucidaImporter(final InputStream is) throws IOException {
        try (is) {
            final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            final DocumentBuilder builder = factory.newDocumentBuilder();
            doc = builder.parse(is);
            doc.getDocumentElement().normalize();
        } catch (final ParserConfigurationException | SAXException e) {
            throw new IOException("Failed to parse Neurolucida XML", e);
        }
        parseDocument();
    }

    private void parseDocument() {
        final Element root = doc.getDocumentElement();
        if (!"mbf".equalsIgnoreCase(root.getTagName())) {
            SNTUtils.warn("Root element is '" + root.getTagName() + "', expected 'mbf'. Attempting parse anyway.");
        }
        parseCalibration(root);
        parseTrees(root);
        parseMarkers(root);
        applyCanvasOffsets();
    }

    /**
     * Applies a canvas offset to each parsed tree so that the bounding box origin
     * maps to (0, 0, 0) in pixel space. NMF coordinates are in micrometers with
     * arbitrary origin (possibly negative), so a rendering offset is needed for
     * proper display without modifying the native coordinates.
     */
    private void applyCanvasOffsets() {
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
        for (final Tree tree : trees) {
            for (final Path path : tree.list()) {
                for (int i = 0; i < path.size(); i++) {
                    final PointInImage p = path.getNode(i);
                    if (p.x < minX) minX = p.x;
                    if (p.y < minY) minY = p.y;
                    if (p.z < minZ) minZ = p.z;
                }
            }
        }
        if (minX == Double.MAX_VALUE) return;
        if (minX == 0 && minY == 0 && minZ == 0) return;
        // canvasOffset is in pixel units: convert from µm by dividing by spacing
        final double xOff = -minX / xScale;
        final double yOff = -minY / yScale;
        final double zOff = -minZ / zSpacing;
        SNTUtils.log("Neurolucida: applying canvas offset (" + xOff + ", " + yOff + ", " + zOff + ") pixels");
        for (final Tree tree : trees) {
            tree.applyCanvasOffset(xOff, yOff, zOff);
        }
    }

    private void parseCalibration(final Element root) {
        final NodeList imagesList = root.getElementsByTagName("images");
        if (imagesList.getLength() == 0) return;
        final Element images = (Element) imagesList.item(0);
        // <scale x="0.114" y="0.114" />
        final NodeList scaleList = images.getElementsByTagName("scale");
        if (scaleList.getLength() > 0) {
            final Element scale = (Element) scaleList.item(0);
            xScale = parseDoubleAttr(scale, "x", 1.0);
            yScale = parseDoubleAttr(scale, "y", 1.0);
        }
        // <zspacing z="0.3" slices="50" /> (z can be negative; use absolute value)
        final NodeList zsList = images.getElementsByTagName("zspacing");
        if (zsList.getLength() > 0) {
            final Element zs = (Element) zsList.item(0);
            zSpacing = Math.abs(parseDoubleAttr(zs, "z", 1.0));
        }
        SNTUtils.log("Neurolucida calibration: xScale=" + xScale + " yScale=" + yScale + " zSpacing=" + zSpacing);
    }

    private void parseTrees(final Element root) {
        // Only direct children <tree> of root, not nested <tree> inside contours etc.
        final NodeList children = root.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (!(children.item(i) instanceof Element el)) continue;
            if (!"tree".equalsIgnoreCase(el.getTagName())) continue;
            final Tree tree = parseTree(el);
            if (tree != null && !tree.isEmpty()) {
                trees.add(tree);
            }
        }
    }

    private Tree parseTree(final Element treeEl) {
        final String typeStr = treeEl.getAttribute("type");
        final int swcType = mapSWCType(typeStr);
        final Color color = parseColor(treeEl);
        final String label = treeEl.hasAttribute("leaf") ? treeEl.getAttribute("leaf") : typeStr;

        final Tree tree = new Tree();
        tree.setLabel(label != null && !label.isEmpty() ? label : "Neurolucida Tree");

        // A <tree> contains point data at the top level (the root segment)
        // followed by zero or more <branch> children
        final List<Path> paths = new ArrayList<>();
        parseBranch(treeEl, null, null, swcType, color, paths);

        for (final Path p : paths) {
            if (p.size() > 0) tree.add(p);
        }
        return tree;
    }

    /**
     * Recursively parses a branch element (or the top-level tree element).
     * Each {@code <branch>} (or the root segment of {@code <tree>}) becomes one Path.
     * Points before the first child {@code <branch>} belong to the current path.
     * Each child {@code <branch>} starts a new path that branches from the current one.
     */
    private void parseBranch(final Element branchEl, final Path parentPath,
                             final PointInImage branchPoint, final int swcType,
                             final Color color, final List<Path> paths) {
        final Path path = new Path(xScale, yScale, zSpacing, spacingUnits);
        path.setSWCType(swcType);
        if (color != null) path.setColor(color);

        if (parentPath != null && branchPoint != null) {
            path.setBranchFrom(parentPath, branchPoint);
        }
        // Paths without a parent are implicitly primary (parentPath == null)
        paths.add(path);

        // Walk direct children: <point> elements go into this path,
        // <branch> elements trigger recursion
        final NodeList children = branchEl.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (!(children.item(i) instanceof Element childEl)) continue;
            final String tag = childEl.getTagName().toLowerCase();
            if ("point".equals(tag)) {
                addPointToPath(path, childEl);
            } else if ("branch".equals(tag)) {
                // The branch point is the last node added to the current path
                final PointInImage bp = path.size() > 0
                        ? path.getNode(path.size() - 1)
                        : (branchPoint != null ? branchPoint : new PointInImage(0, 0, 0));
                parseBranch(childEl, path, bp, swcType, color, paths);
            }
            // skip <spine>, <varicosity>, <property>, etc.
        }
    }

    private void addPointToPath(final Path path, final Element pointEl) {
        // NMF coordinates are in micrometers; d is diameter (not radius)
        final double x = parseDoubleAttr(pointEl, "x", 0);
        final double y = parseDoubleAttr(pointEl, "y", 0);
        final double z = parseDoubleAttr(pointEl, "z", 0);
        final double d = parseDoubleAttr(pointEl, "d", 0);
        path.addPointDouble(x, y, z);
        if (d > 0 && path.size() > 0) {
            path.getNode(path.size() - 1).setRadius(d / 2.0);
        }
    }

    private void parseMarkers(final Element root) {
        final NodeList children = root.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (!(children.item(i) instanceof Element el)) continue;
            if (!"marker".equalsIgnoreCase(el.getTagName())) continue;
            parseMarker(el);
        }
    }

    private void parseMarker(final Element markerEl) {
        final String name = markerEl.hasAttribute("name") ? markerEl.getAttribute("name") : "";
        final String type = markerEl.hasAttribute("type") ? markerEl.getAttribute("type") : "";
        final Color color = parseColor(markerEl);
        final String label = !name.isEmpty() ? name : (!type.isEmpty() ? type : "Marker");

        // Collect all <point> children and compute centroid
        final List<double[]> points = new ArrayList<>();
        final NodeList children = markerEl.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (!(children.item(i) instanceof Element childEl)) continue;
            if (!"point".equalsIgnoreCase(childEl.getTagName())) continue;
            final double x = parseDoubleAttr(childEl, "x", 0);
            final double y = parseDoubleAttr(childEl, "y", 0);
            final double z = parseDoubleAttr(childEl, "z", 0);
            points.add(new double[]{x, y, z});
        }
        if (points.isEmpty()) return;

        final double cx = points.stream().mapToDouble(p -> p[0]).average().orElse(0);
        final double cy = points.stream().mapToDouble(p -> p[1]).average().orElse(0);
        final double cz = points.stream().mapToDouble(p -> p[2]).average().orElse(0);

        markerPoints.add(new double[]{cx, cy, cz});
        markerLabels.add(label);
        markerColors.add(color);
    }

    /**
     * Returns the parsed trees (one per {@code <tree>} element in the file).
     *
     * @return collection of Tree objects; may be empty if no trees were found
     */
    public Collection<Tree> getTrees() {
        return trees;
    }

    /**
     * Returns the marker centroids parsed from {@code <marker>} elements.
     * Each entry is a {@code double[3]} array of {x, y, z} coordinates in
     * the file's coordinate system (typically micrometers).
     *
     * @return list of marker centroid positions
     */
    public List<double[]> getMarkerPoints() {
        return markerPoints;
    }

    /**
     * Returns the labels for each parsed marker, in the same order as
     * {@link #getMarkerPoints()}.
     *
     * @return list of marker labels
     */
    public List<String> getMarkerLabels() {
        return markerLabels;
    }

    /**
     * Returns the colors for each parsed marker, in the same order as
     * {@link #getMarkerPoints()}.
     *
     * @return list of marker colors (may contain nulls)
     */
    public List<Color> getMarkerColors() {
        return markerColors;
    }

    /**
     * Returns the spatial calibration parsed from the file header.
     *
     * @return a double array {xScale, yScale, zSpacing} in the file's units
     */
    public double[] getCalibration() {
        return new double[]{xScale, yScale, zSpacing};
    }

    /**
     * Returns the spacing units string (default "um").
     *
     * @return the spacing units
     */
    public String getSpacingUnits() {
        return spacingUnits;
    }

    // -- Utility methods --

    private static int mapSWCType(final String nlType) {
        if (nlType == null || nlType.isEmpty()) return Path.SWC_UNDEFINED;
        return switch (nlType.toLowerCase()) {
            case "axon" -> Path.SWC_AXON;
            case "dendrite" -> Path.SWC_DENDRITE;
            case "apical", "apical dendrite" -> Path.SWC_APICAL_DENDRITE;
            default -> Path.SWC_UNDEFINED;
        };
    }

    private static Color parseColor(final Element el) {
        if (!el.hasAttribute("color")) return null;
        final String colorStr = el.getAttribute("color").trim();
        if (colorStr.startsWith("#")) {
            try {
                return Color.decode(colorStr);
            } catch (final NumberFormatException ignored) {
                return null;
            }
        }
        // NMF uses comma-separated RGB triplets: "color" = "255,0,0"
        final String[] parts = colorStr.split("[,\\s]+");
        if (parts.length >= 3) {
            try {
                return new Color(Integer.parseInt(parts[0].trim()),
                        Integer.parseInt(parts[1].trim()),
                        Integer.parseInt(parts[2].trim()));
            } catch (final NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static double parseDoubleAttr(final Element el, final String attr, final double defaultVal) {
        if (!el.hasAttribute(attr)) return defaultVal;
        try {
            return Double.parseDouble(el.getAttribute(attr));
        } catch (final NumberFormatException e) {
            return defaultVal;
        }
    }

    /**
     * Checks whether the given input stream starts with Neurolucida XML content.
     * The stream must support {@link InputStream#mark(int)}.
     *
     * @param is a mark-supported input stream positioned at the start of the file
     * @return true if the content appears to be a Neurolucida XML file
     * @throws IOException if the stream cannot be read
     */
    public static boolean isNeurolucidaXML(final InputStream is) throws IOException {
        if (!is.markSupported())
            throw new IllegalArgumentException("InputStream must support mark/reset");
        is.mark(4096);
        try {
            final byte[] buf = new byte[4096];
            final int read = is.read(buf);
            if (read <= 0) return false;
            final String header = new String(buf, 0, read);
            return header.contains("<mbf");
        } finally {
            is.reset();
        }
    }
}
