/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2024 Fiji developers.
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

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Collection;
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

import sc.fiji.snt.SNTUtils;
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

	protected OBJFileLoaderPlus loader;
	protected RemountableDrawableVBO drawable;
	private String label;
	protected String unit;
	private double volume = Double.NaN;
	private int symmetryAxis;
	private double mirrorCoord = Double.NaN;

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
	 * plane for most bilateria models)
	 *
	 * @return the axis defining the symmetry plane where X=0; Y=1; Z=2;
	 */
	public void setSymmetryAxis(final int axis) {
		this.symmetryAxis = axis;
	}

	public int getSymmetryAxis() {
		return symmetryAxis;
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
				final URL jarUrl = new URL(filePath);
				final JarURLConnection connection = (JarURLConnection) jarUrl
					.openConnection();
				url = connection.getJarFileURL();
			}
			else if (!filePath.startsWith("http")) {
				url = (new File(filePath)).toURI().toURL();
			}
			else {
				url = new URL(filePath);
			}
		}
		catch (final ClassCastException | IOException e) {
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

	protected String getLabel() {
		return (label == null) ? loader.getLabel() : label;
	}

	public void setLabel(final String label) {
		this.label = label;
	}
	
	public void setVolume(final double volume) {
		this.volume = volume;
	}
	
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
			compileModel(null);
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
				BufferedReader input = null;
				try {

					input = new BufferedReader(new InputStreamReader(fileURL.openStream()));
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
				} finally {
					try {
						if (input != null) {
							input.close();
						}
					} catch (final IOException closee) {
						// ignore
					}
				}
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
