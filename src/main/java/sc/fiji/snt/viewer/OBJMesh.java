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

import com.jogamp.common.nio.Buffers;
import com.jogamp.opengl.GL;
import com.jogamp.opengl.GL2;
import com.jogamp.opengl.GLException;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.JarURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

import org.jzy3d.colors.Color;
import org.jzy3d.io.IGLLoader;
import org.jzy3d.io.obj.OBJFile;
import org.jzy3d.maths.BoundingBox3d;
import org.jzy3d.maths.Coord3d;
import org.jzy3d.painters.IPainter;
import org.jzy3d.painters.NativeDesktopPainter;
import org.jzy3d.plot3d.primitives.vbo.drawable.DrawableVBO;
import org.scijava.util.ColorRGB;
import org.scijava.util.Colors;

import org.apache.commons.math3.linear.Array2DRowRealMatrix;
import org.apache.commons.math3.linear.EigenDecomposition;
import org.apache.commons.math3.linear.RealMatrix;
import org.apache.commons.math3.stat.correlation.Covariance;

import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.analysis.PCAnalyzer;
import sc.fiji.snt.util.BoundingBox;
import sc.fiji.snt.util.PointInImage;
import sc.fiji.snt.util.SNTPoint;

/**
 * An OBJMesh stores information about a Wavefront .obj mesh loaded
 * into {@link Viewer3D}, with access points to its {@link OBJFile} and
 * {@link DrawableVBO}
 *
 * @author Tiago Ferreira
 */
public class OBJMesh {

	/** Shading mode: jzy3d fixed-function rendering (default). */
	public static final int SHADING_DEFAULT = 0;
	/** Shading mode: per-fragment Phong lighting with hemispherical ambient (GLSL 1.20). */
	public static final int SHADING_SMOOTH = 1;

	protected OBJFileLoaderPlus loader;
	protected RemountableDrawableVBO drawable;
	private String label;
	protected String unit;
	private double volume = Double.NaN;
	private int symmetryAxis;
	private double mirrorCoord = Double.NaN;
	int meshShadingMode = SHADING_DEFAULT;
	boolean backfaceCull = false;
	private String displayedHemisphere = "both";

	/**
	 * Instantiates a new wavefront OBJ mesh from a file path/URL.
	 *
	 * @param filePath the absolute path to the .OBJ file to be imported. URL
	 *          representations accepted
	 * @throws IllegalArgumentException if filePath is invalid or file does not
	 *           contain a compilable mesh
	 */
	public OBJMesh(final String filePath) {
		this(getURL(filePath), null);
	}

	public OBJMesh(final URL url, final String meshUnit) {
		loader = new OBJFileLoaderPlus(url);
		if (!loader.compileModel(null)) {
			throw new IllegalArgumentException(
				"Mesh could not be compiled. Invalid file?");
		}
		drawable = new RemountableDrawableVBO(loader, this);
		unit = meshUnit;
	}

	private OBJMesh() {}

	/**
	 * Sets the axis defining the symmetry plane of this mesh (e.g., the sagittal
	 * plane for most bilateria models),  where X=0; Y=1; Z=2;
	 */
	public void setSymmetryAxis(final int axis) {
		this.symmetryAxis = axis;
	}

	public int getSymmetryAxis() {
		return symmetryAxis;
	}

	/**
	 * Sets which hemisphere of this mesh is displayed.
	 *
	 * @param hemiHalf "left" (or "l", "1"), "right" (or "r", "2"), or anything
	 *                 else for both hemispheres
	 */
	public void setDisplayedHemisphere(final String hemiHalf) {
		this.displayedHemisphere = getHemisphere(hemiHalf);
	}

	/**
	 * Returns the currently displayed hemisphere: "left", "right", or "both".
	 *
	 * @return the displayed hemisphere
	 */
	public String getDisplayedHemisphere() {
		return displayedHemisphere;
	}

	/**
	 * Returns the mirror coordinate along the symmetry axis, computing it lazily
	 * from the mesh barycentre if not yet set.
	 */
	double ensuredMirrorCoord() {
		if (Double.isNaN(mirrorCoord)) {
			final Coord3d c = getBarycentreCoord();
			mirrorCoord = switch (symmetryAxis) {
				case 0 -> c.x;
				case 1 -> c.y;
				default -> c.z;
			};
		}
		return mirrorCoord;
	}

	public OBJMesh duplicate() {
		//return new OBJMesh(loader.url, unit);
		final OBJMesh dup = new OBJMesh();
		dup.loader = loader;
		dup.drawable = new RemountableDrawableVBO(loader, dup);
		dup.drawable.setColor(drawable.getColor());
		dup.drawable.setBoundingBoxColor(drawable.getBoundingBoxColor());
		dup.drawable.setBoundingBoxDisplayed(drawable.isBoundingBoxDisplayed());
		dup.unit = unit;
		dup.volume = volume;
		dup.label = label;
		dup.mirrorCoord = mirrorCoord;
		dup.symmetryAxis = symmetryAxis;
		dup.displayedHemisphere = displayedHemisphere;
		dup.meshShadingMode = meshShadingMode;
		dup.backfaceCull = backfaceCull;
		return dup;
	}

	/**
	 * Translates the vertices of this mesh by the specified offset. If mesh is
	 * displayed, changes may only occur once scene is rebuilt.
	 *
	 * @param offset the translation offset
	 */
	public void translate(final SNTPoint offset) {
		if (drawable.hasMountedOnce()) {
			drawable.unmount();
			SNTUtils.log("If mesh is displayed, changes may only occur once scene is rebuilt");
		}
		loader.compileModel(offset);
		// drawable.updateBounds();
//		final Transform tTransform = new Transform(new Translate(new Coord3d(offset.getX(), offset.getY(), offset.getZ())));
//		drawable.applyGeometryTransform(tTransform);
	}

	private static URL getURL(final String filePath) {
		if (filePath == null || filePath.isEmpty()) {
			throw new IllegalArgumentException("Invalid file path");
		}
		SNTUtils.log("Retrieving " + filePath);
		final URL url;
		try {
			// see https://stackoverflow.com/a/402771
			if (filePath.startsWith("jar")) {
				final URL jarUrl = new URI(filePath).toURL();
				final JarURLConnection connection = (JarURLConnection) jarUrl
					.openConnection();
				url = connection.getJarFileURL();
			}
			else if (!filePath.startsWith("http")) {
				url = (new File(filePath)).toURI().toURL();
			}
			else {
				url = new URI(filePath).toURL();
			}
		}
		catch (final ClassCastException | IOException | URISyntaxException e) {
			throw new IllegalArgumentException("Invalid path: " + filePath);
		}
		return url;
	}

	/**
	 * Script friendly method to assign a color to the mesh.
	 *
	 * @param color               the color to render the imported file, either a 1)
	 *                            HTML color codes starting with hash ({@code #}), a
	 *                            color preset ("red", "blue", etc.), or integer
	 *                            triples of the form {@code r,g,b} and range
	 *                            {@code [0, 255]}
	 * @param transparencyPercent the color transparency (in percentage)
	 */
	public void setColor(final String color, final double transparencyPercent) {
		setColor(new ColorRGB(color), transparencyPercent);
	}

	/**
	 * Assigns a color to the mesh.
	 * 
	 * @param color the color to render the imported file
	 * @param transparencyPercent the color transparency (in percentage)
	 */
	public void setColor(final ColorRGB color, final double transparencyPercent) {
		final ColorRGB inputColor = (color == null) ? Colors.WHITE : color;
		final Color c = new Color(inputColor.getRed(), inputColor.getGreen(),
			inputColor.getBlue(), (int) Math.round((100 - transparencyPercent) * 255 /
				100));
		drawable.setColor(c);
	}

	/**
	 * Changes the transparency of this mesh.
	 * 
	 * @param transparencyPercent the mesh transparency (in percentage).
	 */
	public void setTransparency(final double transparencyPercent) {
		final Color existing = drawable.getColor();
		final Color adjusted = new Color(existing.r, existing.g, existing.b,
				(float) (1 - (transparencyPercent / 100.0)));
		drawable.setColor(adjusted);
	}

	/**
	 * Determines whether the mesh bounding box should be displayed.
	 * 
	 * @param boundingBoxColor the color of the mesh bounding box. If null, no
	 *          bounding box is displayed
	 */
	public void setBoundingBoxColor(final ColorRGB boundingBoxColor) {
		final Color c = (boundingBoxColor == null) ? null : new Color(
			boundingBoxColor.getRed(), boundingBoxColor.getGreen(), boundingBoxColor
				.getBlue(), boundingBoxColor.getAlpha());
		drawable.setBoundingBoxColor(c);
		drawable.setBoundingBoxDisplayed(c != null);
	}

	/**
	 * Determines whether the mesh bounding box should be displayed.
	 * 
	 * @param boundingBoxColor the color of the mesh bounding box, either a 1) HTML
	 *                         color codes starting with hash ({@code #}), a color
	 *                         preset ("red", "blue", etc.), or integer triples of
	 *                         the form {@code r,g,b} and range {@code [0, 255]}. If
	 *                         null, or if string does not encode a valid color, no
	 *                         bounding box is displayed
	 */
	public void setBoundingBoxColor(final String boundingBoxColor) {
		setBoundingBoxColor(new ColorRGB(boundingBoxColor));
	}

	/**
	 * Gets the minimum bounding box of this mesh.
	 * 
	 * @param hemiHalf either "left", "l", "right", "r" otherwise bounding box is
	 *                 retrieved for both hemi-halves, i.e., the full mesh. It is
	 *                 ignored if a hemisphere was already specified in the
	 *                 constructor.
	 * @return the minimum bounding box
	 */
	public BoundingBox getBoundingBox(final String hemiHalf) {
		final String normHemisphere = getHemisphere(hemiHalf);
		final BoundingBox bbox = new BoundingBox();
		bbox.info = label + " (BBox)";
		if ("both".equals(normHemisphere) && drawable.getBounds() != null) {
			drawable.updateBounds();
			final BoundingBox3d bBox3d = drawable.getBounds();
			bbox.setOrigin(new PointInImage(bBox3d.getXmin(), bBox3d.getYmin(), bBox3d.getZmin()));
			bbox.setOriginOpposite(new PointInImage(bBox3d.getXmax(), bBox3d.getYmax(), bBox3d.getZmax()));
			return bbox;
		}
		bbox.compute(getVertices(normHemisphere).iterator());
		return bbox;
	}

	/** @deprecated use {@link #label} instead */
	@Deprecated
	public String getLabel() {
		return label();
	}

	/**
	 * Gets this mesh label.
	 */
	public String label() {
		return (label == null) ? loader.getLabel() : label;
	}

	/**
	 * Sets the label for this mesh.
	 *
	 * @param label the label to set
	 */
	public void setLabel(final String label) {
		this.label = label;
	}
	
	/**
	 * Sets the volume of this mesh.
	 *
	 * @param volume the volume to set
	 */
	public void setVolume(final double volume) {
		this.volume = volume;
	}
	
	/**
	 * Gets the volume of this mesh.
	 *
	 * @return the mesh volume
	 */
	public double getVolume() {
		return this.volume;
	}

	/**
	 * Returns the mesh vertices.
	 *
	 * @return the mesh vertices as {@link SNTPoint}s
	 */
	public Collection<? extends SNTPoint> getVertices() {
		return loader.obj.getVertices();
	}

	/**
	 * Returns the mesh vertices.
	 * 
	 * @param hemihalf either "left", "l", "right", "r" otherwise centroid is
	 *                 retrieved for both hemi-halves, i.e., the full mesh
	 * @return the mesh vertices as {@link SNTPoint}s
	 */
	public Collection<? extends SNTPoint> getVertices(final String hemihalf) {
		final String normHemisphere = getHemisphere(hemihalf);
		return "both".equals(normHemisphere) ? getVertices() : loader.obj.getVertices(normHemisphere);
	}

	/* returns 'left', 'right' or 'both' */
	private String getHemisphere(final String label) {
		if (label == null || label.trim().isEmpty()) return "both";
		final String normLabel = label.toLowerCase().substring(0, 1);
		if ("l1".contains(normLabel)) return "left"; // left, 1
		else if ("r2".contains(normLabel)) return "right"; // right, 2
		else return "both";
	}

	private Coord3d getBarycentreCoord() {
		final Coord3d center;
		if (getDrawable() != null && getDrawable().getBounds() != null) {
			center = getDrawable().getBounds().getCenter();
		} else {
			center = loader.obj.computeBoundingBox().getCenter();
		}
		return center;
	}

	private SNTPoint getBarycentre() {
		final Coord3d center = getBarycentreCoord();
		return new PointInImage(center.x, center.y, center.z);
	}
	/**
	 * Returns the spatial centroid of the specified (hemi)mesh.
	 *
	 * @param hemihalf either "left", "l", "right", "r", otherwise centroid is
	 *                 retrieved for both hemi-halves, i.e., the full mesh
	 * @return the SNT point defining the (X,Y,Z) center of the (hemi)mesh.
	 */
	public SNTPoint getCentroid(final String hemihalf) {
		final String normHemisphere = getHemisphere(hemihalf);
		if ("both".contentEquals(normHemisphere)) {
			return getBarycentre();
		}
		return loader.obj.getCenter(normHemisphere);
	}

	/**
	 * Computes the principal axes of the mesh using Principal Component Analysis (PCA).
	 * The principal axes represent the directions of maximum, medium, and minimum variance
	 * in the mesh geometry, providing insight into the overall shape orientation of this mesh.
	 *
	 * @param hemiHalf either "left", "l", "right", "r", otherwise principal axes are
	 *                 computed for both hemi-halves, i.e., the full mesh
	 * @return array of three PrincipalAxis objects ordered by decreasing variance
	 *         (primary, secondary, tertiary), or null if computation fails
	 */
	public PCAnalyzer.PrincipalAxis[] getPrincipalAxes(final String hemiHalf) {
		return PCAnalyzer.getPrincipalAxes(getVertices(hemiHalf));
	}

	/**
	 * Computes the principal axes of this mesh using the new PCAnalyzer.
	 * This method replaces the deprecated {@link #getPrincipalAxes(String)} method.
	 *
	 * @param hemiHalf either "left", "l", "right", "r", otherwise principal axes are
	 *                 computed for both hemi-halves, i.e., the full mesh
	 * @return array of three PrincipalAxis objects ordered by decreasing variance
	 *         (primary, secondary, tertiary), or null if computation fails
	 */
	public PCAnalyzer.PrincipalAxis[] computePrincipalAxes(final String hemiHalf) {
		return PCAnalyzer.getPrincipalAxes(getVertices(hemiHalf));
	}

	/**
	 * Computes the local direction of the mesh at a specific point using nearest neighbor analysis.
	 * This method finds the dominant direction of mesh curvature in the local neighborhood of the
	 * specified point, which is useful for analyzing how structures align with curved anatomical surfaces.
	 *
	 * @param point the point at which to compute the local mesh direction
	 * @param hemiHalf either "left", "l", "right", "r", otherwise analysis is performed
	 *                 on both hemi-halves, i.e., the full mesh
	 * @param neighborCount the number of nearest mesh vertices to use for local analysis (default: 20)
	 * @return the local mesh direction as a normalized vector, or null if computation fails
	 */
	public double[] getLocalDirection(final SNTPoint point, final String hemiHalf, final int neighborCount) {
		final Collection<? extends SNTPoint> vertices = getVertices(hemiHalf);
		if (vertices == null || vertices.isEmpty() || vertices.size() < neighborCount) {
			return null;
		}

		// Find nearest vertices to the specified point
		final List<? extends SNTPoint> vertexList = new ArrayList<>(vertices);
		final List<VertexDistance> distances = new ArrayList<>();
		for (final SNTPoint vertex : vertexList) {
			final double dx = vertex.getX() - point.getX();
			final double dy = vertex.getY() - point.getY();
			final double dz = vertex.getZ() - point.getZ();
			final double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
			distances.add(new VertexDistance(vertex, distance));
		}

		// Sort by distance and take nearest neighbors
		distances.sort(Comparator.comparingDouble(a -> a.distance));
		final List<SNTPoint> nearestVertices = distances.stream()
				.limit(neighborCount)
				.map(vd -> vd.vertex)
				.toList();

		// Compute the local direction using PCA on nearest neighbors
		// Center the data around the query point for true local analysis
		final int n = nearestVertices.size();
		final double[][] data = new double[n][3];

		for (int i = 0; i < n; i++) {
			final SNTPoint vertex = nearestVertices.get(i);
			// Use relative coordinates from the query point
			data[i][0] = vertex.getX() - point.getX();
			data[i][1] = vertex.getY() - point.getY();
			data[i][2] = vertex.getZ() - point.getZ();
		}

		try {
			// Compute covariance matrix manually for better control
			// Data is already centered around the query point
			final double[][] covMatrix = new double[3][3];
			
			// Compute covariance matrix elements
			for (int i = 0; i < 3; i++) {
				for (int j = 0; j < 3; j++) {
					double sum = 0.0;
					for (int k = 0; k < n; k++) {
						sum += data[k][i] * data[k][j];
					}
					covMatrix[i][j] = sum / (n - 1);
				}
			}

			// Compute eigenvalues and eigenvectors
			final RealMatrix covRealMatrix = new Array2DRowRealMatrix(covMatrix);
			final EigenDecomposition eigenDecomp = new EigenDecomposition(covRealMatrix);
			final RealMatrix eigenVectors = eigenDecomp.getV();
			final double[] eigenValues = eigenDecomp.getRealEigenvalues();

			// Find the eigenvector with the largest eigenvalue (principal direction)
			int maxIndex = 0;
			for (int i = 1; i < eigenValues.length; i++) {
				if (Math.abs(eigenValues[i]) > Math.abs(eigenValues[maxIndex])) {
					maxIndex = i;
				}
			}

			// Return the principal direction (normalized eigenvector)
			return new double[]{
				eigenVectors.getEntry(0, maxIndex),
				eigenVectors.getEntry(1, maxIndex),
				eigenVectors.getEntry(2, maxIndex)
			};

		} catch (final Exception ignored) {
			// Handle any numerical issues with PCA computation
			return null;
		}
	}

	/**
	 * Computes the angle between a direction vector and the local mesh direction at a point.
	 * This is useful for e.g., analyzing how neuronal processes align with the local curvature
	 * of surfaces (neuropil meshes).
	 *
	 * @param point the point at which to compute the local mesh direction
	 * @param direction the normalized vector for which the angle will be computed
	 * @param hemiHalf either "left", "l", "right", "r", otherwise analysis is performed
	 *                 on both hemi-halves, i.e., the full mesh
	 * @param neighborCount the number of nearest mesh vertices to use for local analysis
	 * @return the acute angle in degrees (0-90°) between the direction vector and local mesh direction,
	 *         or NaN if computation fails
	 */
	public double getAngleWithLocalDirection(final SNTPoint point, final double[] direction,
											 final String hemiHalf, final int neighborCount) {
		if (direction.length < 3) {
			throw new IllegalArgumentException("direction array must have at least 3 elements (X,Y,Z)");
		}
		final double[] localDirection = getLocalDirection(point, hemiHalf, neighborCount);
		if (localDirection == null) return Double.NaN;

		// Normalize direction vector
		final double dirMag = Math.sqrt(direction[0] * direction[0] + direction[1] * direction[1] + direction[2] * direction[2]);
		if (dirMag == 0) return Double.NaN;

		final double normDirX = direction[0] / dirMag;
		final double normDirY = direction[1] / dirMag;
		final double normDirZ = direction[2] / dirMag;

		// Compute dot product (local direction is already normalized from PCA)
		double dotProduct = normDirX * localDirection[0] + normDirY * localDirection[1] + normDirZ * localDirection[2];

		// Clamp to [-1, 1] to handle numerical errors
		dotProduct = Math.max(-1.0, Math.min(1.0, dotProduct));

		// Return acute angle (0-90 degrees)
		final double angle = Math.acos(Math.abs(dotProduct));
		return Math.toDegrees(angle);
	}

	/**
	 * Helper class for storing vertex-distance pairs during nearest neighbor search.
	 */
	private static class VertexDistance {
		final SNTPoint vertex;
		final double distance;

		VertexDistance(final SNTPoint vertex, final double distance) {
			this.vertex = vertex;
			this.distance = distance;
		}
	}



	/**
	 * Returns the {@link OBJFile} associated with this mesh
	 *
	 * @return the OBJFile
	 */
	public OBJFile getObj() {
		return loader.obj;
	}

	/**
	 * Returns the {@link DrawableVBO} associated with this mesh
	 *
	 * @return the DrawableVBO
	 */
	public DrawableVBO getDrawable() {
		return drawable;
	}

	/**
	 * This is just to make {@link DrawableVBO#hasMountedOnce()} accessible,
	 * allowing to force the re-loading of meshes during an interactive session
	 */
	static class RemountableDrawableVBO extends DrawableVBO {

		protected OBJMesh objMesh;

		// --- Smooth-shading shader (GLSL 1.20 / GL2 — works on all platforms) ---
		private static final String MESH_VERT_SHADER = """
				#version 120
				varying vec3 vNormal;
				void main() {
				    vNormal = normalize(gl_NormalMatrix * gl_Normal);
				    vec4 eyePos = gl_ModelViewMatrix * gl_Vertex;
				    gl_Position = gl_ProjectionMatrix * eyePos;
				    // Required for GL_CLIP_PLANEi to work when a shader is active (GLSL 1.20).
				    // Without this, clip plane evaluation is implementation-defined and often ignored.
				    gl_ClipVertex = eyePos;
				    gl_FrontColor = gl_Color;
				}
				""";
		private static final String MESH_FRAG_SHADER = """
				#version 120
				varying vec3 vNormal;
				void main() {
				    vec3 N = normalize(vNormal);
				    vec3 L = normalize(vec3(0.0, 0.0, 1.0));
				    float NdotL = max(dot(N, L), 0.0);
				    float hemi = 0.5 + 0.5 * N.y;
				    vec3 ambient = gl_Color.rgb * mix(0.35, 0.6, hemi);
				    vec3 diffuse = gl_Color.rgb * NdotL * 0.55;
				    gl_FragColor = vec4(min(ambient + diffuse, vec3(1.0)), gl_Color.a);
				}
				""";

		private static int meshShaderProgram = 0;
		private static boolean meshShaderInitAttempted = false;

		protected RemountableDrawableVBO(final IGLLoader<DrawableVBO> loader,
			final OBJMesh objMesh)
		{
			super(loader);
			this.objMesh = objMesh;
		}

		protected void unmount() {
			super.hasMountedOnce = false;
		}

		private void computeBoundingBoxAsNeeded() {
			if (bbox == null || !bbox.valid() || bbox.isReset() || bbox.isPoint()) {
				bbox = objMesh.getObj().computeBoundingBox();
			}
		}

		@Override
		public Coord3d getBarycentre() {
			computeBoundingBoxAsNeeded();
			return super.getBarycentre();
		}

		@Override
		public void draw(final IPainter painter) {
			final boolean smooth = objMesh.meshShadingMode == SHADING_SMOOTH;
			final boolean clip = !"both".equals(objMesh.displayedHemisphere);
			// In default mode with no culling and no hemisphere clipping, delegate
			// directly — no GL state changes needed.
			if (!smooth && !objMesh.backfaceCull && !clip) {
				super.draw(painter);
				return;
			}
			final GL2 gl2 = ((NativeDesktopPainter) painter).getGL().getGL2();
			// Save states we may modify so they can be restored after drawing,
			// preventing leakage into the rest of the scene (e.g. ArborVBO rendering).
			final boolean lightingWasOn = gl2.glIsEnabled(GL2.GL_LIGHTING);
			final boolean cullWasOn = gl2.glIsEnabled(GL.GL_CULL_FACE);
			final boolean clipWasOn = gl2.glIsEnabled(GL2.GL_CLIP_PLANE0);
			final int[] prevProgram = new int[1];
			gl2.glGetIntegerv(GL2.GL_CURRENT_PROGRAM, prevProgram, 0);
			try {
				if (smooth) {
					if (!meshShaderInitAttempted) initMeshShaders(gl2);
					if (meshShaderProgram != 0) {
						gl2.glUseProgram(meshShaderProgram);
						gl2.glDisable(GL2.GL_LIGHTING);
					}
				}
				if (objMesh.backfaceCull) {
					gl2.glEnable(GL.GL_CULL_FACE);
					gl2.glCullFace(GL.GL_BACK);
				}
				if (clip) {
					final double mc = objMesh.ensuredMirrorCoord();
					final boolean left = "left".equals(objMesh.displayedHemisphere);
					final double[] eq = switch (objMesh.symmetryAxis) {
						case 0 -> left ? new double[]{-1, 0, 0, mc} : new double[]{1, 0, 0, -mc};
						case 1 -> left ? new double[]{0, -1, 0, mc} : new double[]{0, 1, 0, -mc};
						default -> left ? new double[]{0, 0, -1, mc} : new double[]{0, 0, 1, -mc};
					};
					gl2.glClipPlane(GL2.GL_CLIP_PLANE0, eq, 0);
					gl2.glEnable(GL2.GL_CLIP_PLANE0);
				}
				super.draw(painter);
			} finally {
				// Always restore GL state regardless of any exception during draw.
				gl2.glUseProgram(prevProgram[0]);
				if (lightingWasOn) gl2.glEnable(GL2.GL_LIGHTING);
				else gl2.glDisable(GL2.GL_LIGHTING);
				if (cullWasOn) gl2.glEnable(GL.GL_CULL_FACE);
				else gl2.glDisable(GL.GL_CULL_FACE);
				if (clipWasOn) gl2.glEnable(GL2.GL_CLIP_PLANE0);
				else gl2.glDisable(GL2.GL_CLIP_PLANE0);
			}
		}

		private static void initMeshShaders(final GL2 gl2) {
			meshShaderInitAttempted = true;
			try {
				final int vs = compileMeshShader(gl2, GL2.GL_VERTEX_SHADER, MESH_VERT_SHADER);
				final int fs = compileMeshShader(gl2, GL2.GL_FRAGMENT_SHADER, MESH_FRAG_SHADER);
				if (vs == 0 || fs == 0) {
					SNTUtils.log("OBJMesh: smooth shading shader compile failed.");
					return;
				}
				final int prog = gl2.glCreateProgram();
				gl2.glAttachShader(prog, vs);
				gl2.glAttachShader(prog, fs);
				gl2.glLinkProgram(prog);
				final int[] status = new int[1];
				gl2.glGetProgramiv(prog, GL2.GL_LINK_STATUS, status, 0);
				if (status[0] == GL.GL_FALSE) {
					final int[] len = new int[1];
					gl2.glGetProgramiv(prog, GL2.GL_INFO_LOG_LENGTH, len, 0);
					final byte[] log = new byte[len[0]];
					gl2.glGetProgramInfoLog(prog, len[0], null, 0, log, 0);
					SNTUtils.log("OBJMesh: smooth shading shader link failed: " + new String(log).trim());
					gl2.glDeleteProgram(prog);
					return;
				}
				gl2.glDetachShader(prog, vs);
				gl2.glDetachShader(prog, fs);
				gl2.glDeleteShader(vs);
				gl2.glDeleteShader(fs);
				meshShaderProgram = prog;
				SNTUtils.log("OBJMesh: smooth shading shader compiled successfully.");
			} catch (final GLException e) {
				SNTUtils.log("OBJMesh: smooth shading shader unavailable: " + e.getMessage());
			}
		}

		private static int compileMeshShader(final GL2 gl2, final int type, final String source) {
			final int shader = gl2.glCreateShader(type);
			gl2.glShaderSource(shader, 1, new String[]{ source }, null, 0);
			gl2.glCompileShader(shader);
			final int[] status = new int[1];
			gl2.glGetShaderiv(shader, GL2.GL_COMPILE_STATUS, status, 0);
			if (status[0] == GL.GL_FALSE) {
				final int[] len = new int[1];
				gl2.glGetShaderiv(shader, GL2.GL_INFO_LOG_LENGTH, len, 0);
				final byte[] log = new byte[len[0]];
				gl2.glGetShaderInfoLog(shader, len[0], null, 0, log, 0);
				SNTUtils.log("OBJMesh: shader compile error: " + new String(log));
				gl2.glDeleteShader(shader);
				return 0;
			}
			return shader;
		}

	}

	/**
	 * This is a copy of {@code OBJFileLoader} with extra methods that allow to
	 * check if OBJFile is valid before converting it into a Drawable #
	 */
	private class OBJFileLoaderPlus implements IGLLoader<DrawableVBO> {

		private final URL url;
		private OBJFilePlus obj;

		public OBJFileLoaderPlus(final URL url) {
			this.url = url;
			if (url == null) throw new IllegalArgumentException("Null URL");
		}

		private String getLabel() {
			String label = url.toString();
			label = label.substring(label.lastIndexOf("/") + 1);
			return label;
		}

		private boolean compileModel(final SNTPoint offset) {
			obj = new OBJFilePlus();
			SNTUtils.log("Loading OBJ file '" + new File(url.getPath()).getName() + "'");
			if (!obj.loadModelFromURL(url)) {
				SNTUtils.log("Loading failed. Invalid file?");
				return false;
			}
			if (offset != null) obj.translate(offset);
			obj.compileModel();
			SNTUtils.log(String.format("Mesh compiled: %d vertices and %d triangles", obj
				.getPositionCount(), (obj.getIndexCount() / 3)));
			return obj.getPositionCount() > 0;
		}

		@Override
		public void load(final IPainter painter, final DrawableVBO drawable) {
			// compileModel() is already called in the OBJMesh constructor (and by
			// translate()), so obj is always set before the GL thread reaches here.
			// Calling it again would re-parse the file on the render thread, blocking
			// the viewer until loading completes.
			if (obj == null) compileModel(null);
			final int size = obj.getIndexCount();
			final int indexSize = size * Buffers.SIZEOF_INT;
			final int vertexSize = obj.getCompiledVertexCount() * Buffers.SIZEOF_FLOAT;
			final int byteOffset = obj.getCompiledVertexSize() * Buffers.SIZEOF_FLOAT;
			final int normalOffset = obj.getCompiledNormalOffset() * Buffers.SIZEOF_FLOAT;
			final int dimensions = obj.getPositionSize();

			final int pointer = 0;

			final FloatBuffer vertices = obj.getCompiledVertices();
			final IntBuffer indices = obj.getCompiledIndices();
			final BoundingBox3d bounds = obj.computeBoundingBox();

			drawable.doConfigure(pointer, size, byteOffset, normalOffset, dimensions);
			drawable.doLoadArrayFloatBuffer(((NativeDesktopPainter) painter).getGL(), vertexSize, vertices);
			drawable.doLoadElementIntBuffer(((NativeDesktopPainter) painter).getGL(), indexSize, indices);
			drawable.doSetBoundingBox(bounds);
		}

	}

	private class OBJFilePlus extends OBJFile {

		/*
		 * Copied ipsis verbis from
		 * {@link org.jzy3d.io.obj.OBJFile#loadModelFromURL(URL)} but accommodates files
		 * with trailing spaces //TODO: submit PR upstream
		 */
		@Override
		public boolean loadModelFromURL(final URL fileURL) {
			if (fileURL != null) {
                try (BufferedReader input = new BufferedReader(new InputStreamReader(fileURL.openStream()))) {

                    String line = null;
                    final float[] val = new float[4];
                    final int[][] idx = new int[3][3];
                    boolean hasNormals = false;

                    while ((line = input.readLine()) != null) {
                        line = line.trim();
                        if (line.isEmpty()) {
                            continue;
                        }
                        switch (line.charAt(0)) {
                            case 'v':
                                parseObjVertex(line, val);
                                break;
                            case 'f':
                                hasNormals = parseObjFace(line, idx, hasNormals);
                                break;
                            case '#':
                            default:
                                break;
                        }
                    }
                    // post-process data
                    // free anything that ended up being unused
                    if (!hasNormals) {
                        normals_.clear();
                        nIndex_.clear();
                    }

                    posSize_ = 3;
                    return true;

                } catch (final FileNotFoundException kFNF) {
                    SNTUtils.log("Unable to find the shader file " + fileURL + " : FileNotFoundException : "
                            + kFNF.getMessage());
                } catch (final IOException kIO) {
                    SNTUtils.log("Problem reading the shader file " + fileURL + " : IOException : " + kIO.getMessage());
                } catch (final NumberFormatException kIO) {
                    SNTUtils.log("Problem reading the shader file " + fileURL + " : NumberFormatException : "
                            + kIO.getMessage());
                }
                // ignore
            } else {
				SNTUtils.log("URL was null");
			}

			return false;
		}

		/**
		 * See {@link org.jzy3d.io.obj.OBJFile#compileModel()}
		 */
		@Override
		public void compileModel() {
			try {
				super.compileModel();
			} catch (final NoSuchMethodError nsme) {
				if (nsme.getMessage().contains("java.nio.FloatBuffer.rewind()")) {
					((java.nio.Buffer) vertices_).rewind(); // HACK: Cast required for java 8/11 compiler mismatch!?
					((java.nio.Buffer) indices_).rewind(); // HACK: Cast required for java 8/11 compiler mismatch!?
				} else {
					throw new NoSuchMethodError(nsme.getLocalizedMessage());
				}
			}
		}

		private Collection<PointInImage> getVertices() {
			if (positions_.isEmpty()) return null;
			final List<PointInImage> points = new ArrayList<>();
			for (int i = 0; i < positions_.size(); i += 3) {
				final float x = positions_.get(i);
				final float y = positions_.get(i + 1);
				final float z = positions_.get(i + 2);
				points.add(new PointInImage(x, y, z));
			}
			return points;
		}

		private boolean assessHemisphere(final float cc, final boolean isLeft) {
			return (isLeft && cc <= mirrorCoord || !isLeft && cc > mirrorCoord);
		}

		private void setMirrorCoord() {
			if (Double.isNaN(mirrorCoord)) {
				switch (symmetryAxis) {
				case 0:
					mirrorCoord = getBarycentre().getX();
					break;
				case 1:
					mirrorCoord = getBarycentre().getY();
					break;
				default:
					mirrorCoord = getBarycentre().getZ();
					break;
				}
			}
		}

		private Collection<PointInImage> getVertices(final String hemiHalf) {
			if (positions_.isEmpty())
				return null;
			setMirrorCoord();
			final boolean isLeft = "left".equals(hemiHalf);
			final List<PointInImage> points = new ArrayList<>();
			for (int i = symmetryAxis; i < positions_.size(); i += 3) {
				switch (symmetryAxis) {
				case 0:
					final float x = positions_.get(i);
					if (assessHemisphere(x, isLeft)) {
						final float y = positions_.get(i + 1);
						final float z = positions_.get(i + 2);
						points.add(new PointInImage(x, y, z));
					}
					break;
				case 1:
					final float yy = positions_.get(i);
					if (assessHemisphere(yy, isLeft)) {
						final float xx = positions_.get(i - 1);
						final float zz = positions_.get(i + 1);
						points.add(new PointInImage(xx, yy, zz));
					}
					break;
				default:
					final float zzz = positions_.get(i);
					if (assessHemisphere(zzz, isLeft)) {
						final float xxx = positions_.get(i - 2);
						final float yyy = positions_.get(i - 1);
						points.add(new PointInImage(xxx, yyy, zzz));
					}
					break;
				}

			}
			return points;
		}

		private PointInImage getCenter(final String hemiHalf) {
			setMirrorCoord();
			return SNTPoint.average( getVertices(hemiHalf));
		}

		private void translate(final SNTPoint offset) {
			if (positions_.isEmpty()) return;
			for (int i = 0; i < positions_.size(); i += 3) {
				positions_.set(i, (float) (positions_.get(i) + offset.getX()));
				positions_.set(i + 1, (float) (positions_.get(i + 1) + offset.getY()));
				positions_.set(i + 2, (float) (positions_.get(i + 2) + offset.getZ()));
			}
		}
	}

}
