/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2025 Fiji developers.
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

import net.imagej.ImgPlus;
import net.imagej.axis.Axes;
import net.imagej.axis.AxisType;
import net.imagej.axis.CalibratedAxis;
import net.imagej.axis.DefaultLinearAxis;
import org.json.JSONArray;
import org.json.JSONObject;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Utility for setting ImgPlus axes from OME metadata (OME-XML or OME-Zarr).
 * Supports both bioformats2raw and OME-NGFF layouts.
 */
public class OmeAxisUtils {

    /** Layout type for bioformats2raw converted files */
    public static final String LAYOUT_BIOFORMATS2RAW = "bioformats2raw";
    
    /** Layout type for native OME-NGFF files */
    public static final String LAYOUT_OME_NGFF = "ome-ngff";
    
    /** Unknown layout type */
    public static final String LAYOUT_UNKNOWN = "unknown";

    private static final Map<String, AxisType> AXIS_TYPE_MAP;

    static {
        Map<String, AxisType> map = new HashMap<>();
        map.put("X", Axes.X);
        map.put("Y", Axes.Y);
        map.put("Z", Axes.Z);
        map.put("C", Axes.CHANNEL);
        map.put("T", Axes.TIME);
        AXIS_TYPE_MAP = Collections.unmodifiableMap(map);
    }

    // ========== Layout Detection ==========

    /**
     * Detect the OME-ZARR layout type for a local path.
     *
     * @param zarrPath path to zarr directory
     * @return layout type: LAYOUT_BIOFORMATS2RAW, LAYOUT_OME_NGFF, or LAYOUT_UNKNOWN
     */
    public static String detectLayout(String zarrPath) {
        if (zarrPath.startsWith("file://")) {
            zarrPath = zarrPath.substring(7);
        }
        
        if (zarrPath.startsWith("http://") || zarrPath.startsWith("https://") || zarrPath.startsWith("s3://")) {
            return detectRemoteLayout(zarrPath);
        }
        return detectLocalLayout(zarrPath);
    }

    private static String detectLocalLayout(String zarrPath) {
        Path zarr = Path.of(zarrPath);

        if (!Files.isDirectory(zarr)) {
            return LAYOUT_UNKNOWN;
        }

        // Check root .zattrs for bioformats2raw.layout attribute or multiscales
        Path rootZattrs = zarr.resolve(".zattrs");
        if (Files.exists(rootZattrs)) {
            try {
                String content = Files.readString(rootZattrs);
                JSONObject attrs = new JSONObject(content);
                
                // bioformats2raw explicitly declares its layout
                if (attrs.has("bioformats2raw.layout")) {
                    return LAYOUT_BIOFORMATS2RAW;
                }
                
                // OME-NGFF has multiscales at root
                if (attrs.has("multiscales")) {
                    return LAYOUT_OME_NGFF;
                }
            } catch (Exception e) {
                // Fall through
            }
        }

        // Check for /0/.zattrs with multiscales (bioformats2raw structure)
        Path seriesZattrs = zarr.resolve("0").resolve(".zattrs");
        if (Files.exists(seriesZattrs)) {
            try {
                String content = Files.readString(seriesZattrs);
                JSONObject attrs = new JSONObject(content);
                if (attrs.has("multiscales")) {
                    return LAYOUT_BIOFORMATS2RAW;
                }
            } catch (Exception e) {
                // Fall through
            }
        }

        return LAYOUT_UNKNOWN;
    }

    private static String detectRemoteLayout(String zarrUrl) {
        // Normalize URL
        if (zarrUrl.endsWith("/")) {
            zarrUrl = zarrUrl.substring(0, zarrUrl.length() - 1);
        }

        // Check root .zattrs
        try {
            String content = fetchRemoteContent(zarrUrl + "/.zattrs");
            if (content != null) {
                JSONObject attrs = new JSONObject(content);
                if (attrs.has("bioformats2raw.layout")) {
                    return LAYOUT_BIOFORMATS2RAW;
                }
                if (attrs.has("multiscales")) {
                    return LAYOUT_OME_NGFF;
                }
            }
        } catch (Exception e) {
            // Fall through
        }

        // Check /0/.zattrs for bioformats2raw
        try {
            String content = fetchRemoteContent(zarrUrl + "/0/.zattrs");
            if (content != null) {
                JSONObject attrs = new JSONObject(content);
                if (attrs.has("multiscales")) {
                    return LAYOUT_BIOFORMATS2RAW;
                }
            }
        } catch (Exception e) {
            // Fall through
        }

        return LAYOUT_UNKNOWN;
    }

    // ========== Path Utilities ==========

    /**
     * Get the N5/Zarr dataset path for a given layout and level.
     *
     * @param layout layout type
     * @param level  resolution level (0 = full resolution)
     * @param series series index (only used for bioformats2raw)
     * @return dataset path string
     */
    public static String getDatasetPath(String layout, int level, int series) {
        if (LAYOUT_BIOFORMATS2RAW.equals(layout)) {
            return "/" + series + "/" + level;
        } else {
            // OME-NGFF: levels at root
            return "/" + level;
        }
    }

    /**
     * Get the path to .zattrs containing multiscales metadata.
     *
     * @param layout   layout type
     * @param zarrPath root zarr path
     * @param series   series index (only used for bioformats2raw)
     * @return path to .zattrs file
     */
    public static String getZattrsPath(String layout, String zarrPath, int series) {
        if (zarrPath.endsWith("/")) {
            zarrPath = zarrPath.substring(0, zarrPath.length() - 1);
        }
        
        if (LAYOUT_BIOFORMATS2RAW.equals(layout)) {
            return zarrPath + "/" + series + "/.zattrs";
        } else {
            // OME-NGFF: metadata at root
            return zarrPath + "/.zattrs";
        }
    }

    // ========== Axis Setting Methods ==========

    /**
     * Set axes on ImgPlus from OME-XML file.
     *
     * @param imgPlus image to set axes on
     * @param xmlPath path to METADATA.ome.xml
     * @return parsed metadata
     */
    public static OmeMetadata setAxesFromOmeXml(ImgPlus<?> imgPlus, String xmlPath) throws Exception {
        OmeMetadata metadata = parseOmeXml(xmlPath);
        applyMetadata(imgPlus, metadata);
        return metadata;
    }

    /**
     * Set axes on ImgPlus from OME-Zarr .zattrs file (level 0).
     *
     * @param imgPlus    image to set axes on
     * @param zattrsPath path to .zattrs
     * @return parsed metadata, or null if no multiscales found
     */
    public static OmeMetadata setAxesFromOmeZarrAttrs(ImgPlus<?> imgPlus, String zattrsPath) throws IOException {
        return setAxesFromOmeZarrAttrs(imgPlus, zattrsPath, 0);
    }

    /**
     * Set axes on ImgPlus from OME-Zarr .zattrs file for a specific resolution level.
     *
     * @param imgPlus    image to set axes on
     * @param zattrsPath path to .zattrs
     * @param level      resolution level (0 = full resolution)
     * @return parsed metadata, or null if no multiscales found
     */
    public static OmeMetadata setAxesFromOmeZarrAttrs(ImgPlus<?> imgPlus, String zattrsPath, int level) throws IOException {
        String content = Files.readString(Path.of(zattrsPath));
        OmeMetadata metadata = parseOmeZarrAttrsString(content, level);
        if (metadata != null) {
            applyMetadata(imgPlus, metadata);
        }
        return metadata;
    }

    /**
     * Set ImgPlus axes from OME-Zarr metadata (local or remote), level 0.
     *
     * @param imgPlus  image to set axes on
     * @param zarrPath local path or URL to zarr root
     * @return parsed metadata, or null if metadata not found
     */
    public static OmeMetadata setAxesFromZarr(ImgPlus<?> imgPlus, String zarrPath) {
        return setAxesFromZarr(imgPlus, zarrPath, 0, 0);
    }

    /**
     * Set ImgPlus axes from OME-Zarr metadata (local or remote) at specific level.
     *
     * @param imgPlus  image to set axes on
     * @param zarrPath local path or URL to zarr root
     * @param level    resolution level (0 = full resolution)
     * @return parsed metadata, or null if metadata not found
     */
    public static OmeMetadata setAxesFromZarr(ImgPlus<?> imgPlus, String zarrPath, int level) {
        return setAxesFromZarr(imgPlus, zarrPath, level, 0);
    }

    /**
     * Set ImgPlus axes from OME-Zarr metadata (local or remote) at specific level and series.
     *
     * @param imgPlus  image to set axes on
     * @param zarrPath local path or URL to zarr root
     * @param level    resolution level (0 = full resolution)
     * @param series   series index for multi-series datasets (bioformats2raw)
     * @return parsed metadata, or null if metadata not found
     */
    public static OmeMetadata setAxesFromZarr(ImgPlus<?> imgPlus, String zarrPath, int level, int series) {
        // Handle file:// URLs
        if (zarrPath.startsWith("file://")) {
            zarrPath = zarrPath.substring(7);
        }

        if (zarrPath.startsWith("http://") || zarrPath.startsWith("https://") || zarrPath.startsWith("s3://")) {
            return setAxesFromRemoteZarr(imgPlus, zarrPath, level, series);
        }
        return setAxesFromLocalZarr(imgPlus, zarrPath, level, series);
    }

    private static OmeMetadata setAxesFromLocalZarr(ImgPlus<?> imgPlus, String zarrPath, int level, int series) {
        Path zarr = Path.of(zarrPath);

        if (!Files.isDirectory(zarr)) {
            throw new IllegalArgumentException("Zarr path does not exist or is not a directory: " + zarrPath);
        }

        // Detect layout
        String layout = detectLocalLayout(zarrPath);

        // Get .zattrs path based on layout
        Path zattrsPath;
        if (LAYOUT_BIOFORMATS2RAW.equals(layout)) {
            zattrsPath = zarr.resolve(String.valueOf(series)).resolve(".zattrs");
        } else if (LAYOUT_OME_NGFF.equals(layout)) {
            zattrsPath = zarr.resolve(".zattrs");
        } else {
            // Unknown layout - try both locations
            zattrsPath = zarr.resolve(String.valueOf(series)).resolve(".zattrs");
            if (!Files.exists(zattrsPath)) {
                zattrsPath = zarr.resolve(".zattrs");
            }
        }

        // Try to read multiscales metadata
        if (Files.exists(zattrsPath)) {
            try {
                OmeMetadata metadata = setAxesFromOmeZarrAttrs(imgPlus, zattrsPath.toString(), level);
                if (metadata != null) {
                    return metadata;
                }
            } catch (Exception e) {
                // Fall through to OME-XML fallback
            }
        }

        // Fallback to OME-XML (bioformats2raw only)
        if (LAYOUT_BIOFORMATS2RAW.equals(layout)) {
            Path omeXmlPath = zarr.resolve("OME").resolve("METADATA.ome.xml");
            if (Files.exists(omeXmlPath)) {
                try {
                    return setAxesFromOmeXml(imgPlus, omeXmlPath.toString());
                } catch (Exception e) {
                    // Fall through
                }
            }
        }

        return null;
    }

    private static OmeMetadata setAxesFromRemoteZarr(ImgPlus<?> imgPlus, String zarrUrl, int level, int series) {
        // Normalize URL
        if (zarrUrl.endsWith("/")) {
            zarrUrl = zarrUrl.substring(0, zarrUrl.length() - 1);
        }

        // Detect layout
        String layout = detectRemoteLayout(zarrUrl);

        // Get .zattrs URL based on layout
        String zattrsUrl;
        if (LAYOUT_BIOFORMATS2RAW.equals(layout)) {
            zattrsUrl = zarrUrl + "/" + series + "/.zattrs";
        } else if (LAYOUT_OME_NGFF.equals(layout)) {
            zattrsUrl = zarrUrl + "/.zattrs";
        } else {
            // Unknown layout - try bioformats2raw first
            zattrsUrl = zarrUrl + "/" + series + "/.zattrs";
        }

        // Try to read multiscales metadata
        try {
            String jsonContent = fetchRemoteContent(zattrsUrl);
            if (jsonContent != null) {
                OmeMetadata metadata = parseOmeZarrAttrsString(jsonContent, level);
                if (metadata != null) {
                    applyMetadata(imgPlus, metadata);
                    return metadata;
                }
            }
        } catch (Exception e) {
            // Fall through
        }

        // For unknown layout, try root .zattrs as fallback
        if (LAYOUT_UNKNOWN.equals(layout)) {
            try {
                String jsonContent = fetchRemoteContent(zarrUrl + "/.zattrs");
                if (jsonContent != null) {
                    OmeMetadata metadata = parseOmeZarrAttrsString(jsonContent, level);
                    if (metadata != null) {
                        applyMetadata(imgPlus, metadata);
                        return metadata;
                    }
                }
            } catch (Exception e) {
                // Fall through
            }
        }

        // Fallback to OME-XML (bioformats2raw only)
        if (LAYOUT_BIOFORMATS2RAW.equals(layout)) {
            String omeXmlUrl = zarrUrl + "/OME/METADATA.ome.xml";
            try {
                String xmlContent = fetchRemoteContent(omeXmlUrl);
                if (xmlContent != null) {
                    OmeMetadata metadata = parseOmeXmlString(xmlContent);
                    applyMetadata(imgPlus, metadata);
                    return metadata;
                }
            } catch (Exception e) {
                // Fall through
            }
        }

        return null;
    }

    // ========== Parsing Methods ==========

    /**
     * Parse OME-XML metadata file.
     */
    public static OmeMetadata parseOmeXml(String xmlPath) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(new File(xmlPath));
        return parseOmeXmlDocument(doc);
    }

    /**
     * Parse OME-XML from string content (for remote files).
     */
    public static OmeMetadata parseOmeXmlString(String xmlContent) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(new org.xml.sax.InputSource(new java.io.StringReader(xmlContent)));
        return parseOmeXmlDocument(doc);
    }

    private static OmeMetadata parseOmeXmlDocument(Document doc) {
        // Find Pixels element (handle namespaced and non-namespaced)
        NodeList pixelsList = doc.getElementsByTagNameNS("*", "Pixels");
        if (pixelsList.getLength() == 0) {
            pixelsList = doc.getElementsByTagName("Pixels");
        }
        if (pixelsList.getLength() == 0) {
            throw new IllegalArgumentException("Could not find Pixels element in OME-XML");
        }

        Element pixels = (Element) pixelsList.item(0);

        String dimensionOrder = pixels.getAttribute("DimensionOrder");
        if (dimensionOrder.isEmpty()) {
            dimensionOrder = "XYZCT";
        }

        Map<String, Long> sizes = new HashMap<>();
        sizes.put("X", parseLongAttr(pixels, "SizeX", 1));
        sizes.put("Y", parseLongAttr(pixels, "SizeY", 1));
        sizes.put("Z", parseLongAttr(pixels, "SizeZ", 1));
        sizes.put("C", parseLongAttr(pixels, "SizeC", 1));
        sizes.put("T", parseLongAttr(pixels, "SizeT", 1));

        Map<String, Double> physicalSizes = new HashMap<>();
        Map<String, String> physicalUnits = new HashMap<>();

        for (String axis : new String[]{"X", "Y", "Z"}) {
            String sizeAttr = pixels.getAttribute("PhysicalSize" + axis);
            if (!sizeAttr.isEmpty()) {
                physicalSizes.put(axis, Double.parseDouble(sizeAttr));
                String unit = pixels.getAttribute("PhysicalSize" + axis + "Unit");
                physicalUnits.put(axis, unit.isEmpty() ? "µm" : unit);
            }
        }

        return new OmeMetadata(dimensionOrder, sizes, physicalSizes, physicalUnits, "ome-xml");
    }

    /**
     * Parse OME-Zarr .zattrs file (level 0).
     */
    public static OmeMetadata parseOmeZarrAttrs(String zattrsPath) throws IOException {
        return parseOmeZarrAttrs(zattrsPath, 0);
    }

    /**
     * Parse OME-Zarr .zattrs file at specific level.
     */
    public static OmeMetadata parseOmeZarrAttrs(String zattrsPath, int level) throws IOException {
        String content = Files.readString(Path.of(zattrsPath));
        return parseOmeZarrAttrsString(content, level);
    }

    /**
     * Parse OME-Zarr .zattrs from string content (level 0).
     */
    public static OmeMetadata parseOmeZarrAttrsString(String jsonContent) {
        return parseOmeZarrAttrsString(jsonContent, 0);
    }

    /**
     * Parse OME-Zarr .zattrs from string content at specific resolution level.
     *
     * @param jsonContent JSON content from .zattrs file
     * @param level       resolution level (0 = full resolution)
     * @return parsed metadata with level-specific scales, or null if no multiscales found
     */
    public static OmeMetadata parseOmeZarrAttrsString(String jsonContent, int level) {
        JSONObject zattrs = new JSONObject(jsonContent);

        if (!zattrs.has("multiscales")) {
            return null;
        }

        JSONArray multiscales = zattrs.getJSONArray("multiscales");
        if (multiscales.isEmpty()) {
            return null;
        }

        JSONObject firstMultiscale = multiscales.getJSONObject(0);
        if (!firstMultiscale.has("axes")) {
            return null;
        }

        JSONArray axesArray = firstMultiscale.getJSONArray("axes");
        JSONArray datasetsArray = firstMultiscale.optJSONArray("datasets");

        // Build axis info from axes array
        StringBuilder dimensionOrder = new StringBuilder();
        Map<String, Long> sizes = new HashMap<>();
        Map<String, Double> physicalSizes = new HashMap<>();
        Map<String, String> physicalUnits = new HashMap<>();

        // Collect axis names in OME-Zarr order (t,c,z,y,x)
        String[] axisNames = new String[axesArray.length()];

        for (int i = 0; i < axesArray.length(); i++) {
            JSONObject axis = axesArray.getJSONObject(i);

            String name = axis.optString("name", null);
            String type = axis.optString("type", null);
            String unit = axis.optString("unit", null);

            if (name == null) continue;

            String axisChar = mapOmeZarrAxisName(name, type);
            dimensionOrder.append(axisChar);
            axisNames[i] = axisChar;
            sizes.put(axisChar, -1L); // Sizes inferred from image

            if (unit != null && !unit.isEmpty()) {
                physicalUnits.put(axisChar, unit);
            }
        }

        // Extract scale values from coordinateTransformations for requested level
        if (datasetsArray != null && !datasetsArray.isEmpty()) {
            // Clamp level to available range
            int actualLevel = Math.min(level, datasetsArray.length() - 1);

            JSONObject dataset = datasetsArray.getJSONObject(actualLevel);
            JSONArray transforms = dataset.optJSONArray("coordinateTransformations");

            if (transforms != null) {
                for (int t = 0; t < transforms.length(); t++) {
                    JSONObject transform = transforms.getJSONObject(t);
                    String transformType = transform.optString("type", "");

                    if ("scale".equals(transformType)) {
                        JSONArray scaleArray = transform.optJSONArray("scale");
                        if (scaleArray != null && scaleArray.length() == axisNames.length) {
                            // Map scale values to axis names
                            for (int i = 0; i < scaleArray.length(); i++) {
                                String axisChar = axisNames[i];
                                if (axisChar != null) {
                                    double scaleValue = scaleArray.getDouble(i);
                                    physicalSizes.put(axisChar, scaleValue);
                                }
                            }
                        }
                        break; // Only use first scale transform
                    }
                }
            }
        }

        return new OmeMetadata(dimensionOrder.toString(), sizes, physicalSizes, physicalUnits, "ome-zarr");
    }

    // ========== Apply Metadata ==========

    /**
     * Apply parsed metadata to ImgPlus axes.
     */
    private static void applyMetadata(ImgPlus<?> imgPlus, OmeMetadata metadata) {
        String order = metadata.dimensionOrder();
        Map<String, Double> physicalSizes = metadata.physicalSizes();
        Map<String, String> physicalUnits = metadata.physicalUnits();

        int numDims = imgPlus.numDimensions();

        // OME-Zarr dimension order in metadata is t,c,z,y,x (reversed from image)
        // N5Utils loads data with x,y,z,c,t order
        // We need to reverse the order string for proper mapping
        String reversedOrder = new StringBuilder(order).reverse().toString();

        for (int i = 0; i < Math.min(reversedOrder.length(), numDims); i++) {
            String axisChar = String.valueOf(reversedOrder.charAt(i));
            AxisType axisType = AXIS_TYPE_MAP.get(axisChar);

            if (axisType == null) {
                continue;
            }

            CalibratedAxis axis;
            if (physicalSizes.containsKey(axisChar)) {
                double scale = physicalSizes.get(axisChar);
                String unit = physicalUnits.getOrDefault(axisChar, "µm");
                axis = new DefaultLinearAxis(axisType, unit, scale);
            } else {
                axis = new DefaultLinearAxis(axisType);
            }

            imgPlus.setAxis(axis, i);
        }
    }

    // ========== Helper Methods ==========

    private static String mapOmeZarrAxisName(String name, String type) {
        if (name == null) return "?";

        String normalized = name.toUpperCase();
        if (AXIS_TYPE_MAP.containsKey(normalized)) {
            return normalized;
        }

        if (type != null) {
            return switch (type.toLowerCase()) {
                case "time" -> "T";
                case "channel" -> "C";
                case "space" -> switch (name.toLowerCase()) {
                    case "x" -> "X";
                    case "y" -> "Y";
                    case "z" -> "Z";
                    default -> name.toUpperCase().substring(0, 1);
                };
                default -> name.toUpperCase().substring(0, 1);
            };
        }

        return name.toUpperCase().substring(0, 1);
    }

    private static long parseLongAttr(Element element, String attr, long defaultValue) {
        String value = element.getAttribute(attr);
        if (value == null || value.isEmpty()) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static String fetchRemoteContent(String url) throws IOException, InterruptedException {
        if (url.startsWith("s3://")) {
            return fetchS3Content(url);
        }

        HttpResponse<String> response;
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(java.time.Duration.ofSeconds(30))
                    .GET()
                    .build();

            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        }

        if (response.statusCode() == 200) {
            return response.body();
        } else if (response.statusCode() == 404) {
            return null; // File doesn't exist
        } else {
            throw new IOException("HTTP " + response.statusCode() + " for " + url);
        }
    }

    private static String fetchS3Content(String s3Url) throws IOException {
        // Convert s3://bucket/path to https://bucket.s3.amazonaws.com/path
        // This works for public buckets; authenticated access needs AWS SDK
        String httpsUrl = s3ToHttps(s3Url);
        try {
            return fetchRemoteContent(httpsUrl);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while fetching S3 content", e);
        }
    }

    private static String s3ToHttps(String s3Url) {
        // s3://bucket/path → https://bucket.s3.amazonaws.com/path
        String withoutPrefix = s3Url.substring(5); // Remove "s3://"
        int slashIndex = withoutPrefix.indexOf('/');
        if (slashIndex < 0) {
            return "https://" + withoutPrefix + ".s3.amazonaws.com/";
        }
        String bucket = withoutPrefix.substring(0, slashIndex);
        String key = withoutPrefix.substring(slashIndex);
        return "https://" + bucket + ".s3.amazonaws.com" + key;
    }

    // ========== Metadata Record ==========

    /**
     * Parsed OME metadata container.
     */
    public record OmeMetadata(String dimensionOrder, Map<String, Long> sizes, Map<String, Double> physicalSizes,
                              Map<String, String> physicalUnits, String source) {

        @Override
        public String toString() {
            return String.format("OmeMetadata[order=%s, physicalSizes=%s, units=%s, source=%s]",
                    dimensionOrder, physicalSizes, physicalUnits, source);
        }
    }
}
