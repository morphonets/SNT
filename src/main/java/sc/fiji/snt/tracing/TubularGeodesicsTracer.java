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

package sc.fiji.snt.tracing;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;

import ij.IJ;
import sc.fiji.snt.Path;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.SearchProgressCallback;
import sc.fiji.snt.util.PointInImage;

/**
 * A tracer thread for {@code FijiITKInterface.TubularGeodesics} (assumes the
 * tubularity add-on to be installed)
 */
public class TubularGeodesicsTracer extends Thread implements SearchInterface {

	protected double x_spacing;
	protected double y_spacing;
	protected double z_spacing;
	protected String spacing_units;

	protected File oofFile;

	protected float start_x_image;
	protected float start_y_image;
	protected float start_z_image;
	protected float end_x_image;
	protected float end_y_image;
	protected float end_z_image;

	protected PathResult lastPathResult;
	PathResult temporaryPathResult;


	public TubularGeodesicsTracer(final File oofFile, final float start_x_image,
		final float start_y_image, final float start_z_image,
		final float end_x_image, final float end_y_image, final float end_z_image,
		final double x_spacing, final double y_spacing, final double z_spacing,
		final String spacing_units)
	{

		this.oofFile = oofFile;
		this.start_x_image = start_x_image;
		this.start_y_image = start_y_image;
		this.start_z_image = start_z_image;
		this.end_x_image = end_x_image;
		this.end_y_image = end_y_image;
		this.end_z_image = end_z_image;
		this.x_spacing = x_spacing;
		this.y_spacing = y_spacing;
		this.z_spacing = z_spacing;
		this.spacing_units = SNTUtils.getSanitizedUnit(spacing_units);
	}

	@Override
	public Path getResult() {
		if (!lastPathResult.getSuccess()) return null;

		final float[] points = lastPathResult.getPath();
		final int numberOfPoints = lastPathResult.getNumberOfPoints();

		final Path realResult = new Path(x_spacing, y_spacing, z_spacing,
			spacing_units);
		for (int i = 0; i < numberOfPoints; ++i) {
			final int start = i * 4;
			realResult.addNode(new PointInImage(points[start], points[start + 1], points[start + 2]));
			realResult.setRadius(points[start + 3], i);
			// System.out.println("point "+i+" is "+points[start]+",
			// "+points[start+1]+", "+points[start+2]+", "+points[start+3]);
		}
		realResult.setGuessedTangents(2);
		return realResult;
	}

	protected ArrayList<SearchProgressCallback> progressListeners =
		new ArrayList<>();

	public void addProgressListener(final SearchProgressCallback callback) {
		progressListeners.add(callback);
	}

	public void reportProgress(final float proportionDone) {
		System.out.println(
			"No implementation yet for reportProgress; proportionDone: " +
				proportionDone);
	}

	public void requestStop() {

		try {

			final ClassLoader loader = IJ.getClassLoader();
			if (loader == null) throw new RuntimeException(
				"IJ.getClassLoader() failed (!)");

			try {

				/*
				 * Unfortunately, we can't be sure that the tubularity plugin will be available
				 * at compile- or run-time, so we have to try to load it via reflection.
				 */

				final Class<?> c = loader.loadClass(
					"FijiITKInterface.TubularGeodesics");
				final Object newInstance = c.getDeclaredConstructor().newInstance();

				final Class<?>[] parameterTypes = {};

				final Method m = c.getMethod("interruptSearch", parameterTypes);
				final Object[] parameters = new Object[0];

				m.invoke(newInstance, parameters);

			}
			catch (final IllegalArgumentException e) {
				reportFinished(false);
				throw new RuntimeException(
					"There was an illegal argument when trying to invoke interruptSearch: " +
						e);
			}
			catch (final InvocationTargetException e) {
				reportFinished(false);
				final Throwable realException = e.getTargetException();
				throw new RuntimeException(
					"There was an exception thrown by interruptSearch: " + realException);
			}
			catch (final ClassNotFoundException e) {
				reportFinished(false);
				throw new RuntimeException(
					"The FijiITKInterface.TubularGeodesics class was not found: " + e);
			}
			catch (final InstantiationException e) {
				reportFinished(false);
				throw new RuntimeException(
					"Failed to instantiate the FijiITKInterface.TubularGeodesics object: " +
						e);
			}
			catch (final IllegalAccessException e) {
				reportFinished(false);
				throw new RuntimeException(
					"IllegalAccessException when trying to create an instance of FijiITKInterface.TubularGeodesics: " +
						e);
			}
			catch (final NoSuchMethodException e) {
				reportFinished(false);
				throw new RuntimeException(
					"There was a NoSuchMethodException when trying to invoke interruptSearch: " +
						e);
			}
			catch (final SecurityException e) {
				reportFinished(false);
				throw new RuntimeException(
					"There was a SecurityException when trying to invoke interruptSearch: " +
						e);
			}

		}
		catch (final Throwable t) {
			System.out.println("Got an exception from call to ITK code: " + t);
			t.printStackTrace();
			IJ.error("There was an error in calling to ITK code: " + t);
		}
	}

	@Override
	public void run() {

		try {

			final float[] p1 = new float[3];
			final float[] p2 = new float[3];

			p1[0] = start_x_image;
			p1[1] = start_y_image;
			p1[2] = start_z_image;

			p2[0] = end_x_image;
			p2[1] = end_y_image;
			p2[2] = end_z_image;

			temporaryPathResult = new PathResult();

			// Call the JNI here:

			final ClassLoader loader = IJ.getClassLoader();
			if (loader == null) throw new NullPointerException(
				"IJ.getClassLoader() failed (!)");

			try {

				/*
				 * Unfortunately, we can't be sure that the tubularity plugin will be available
				 * at compile- or run-time, so we have to try to load it via reflection.
				 */

				final Class<?> c = loader.loadClass(
					"FijiITKInterface.TubularGeodesics");
				final Object newInstance = c.getDeclaredConstructor().newInstance();

				final Class<?>[] parameterTypes = { String.class, float[].class,
					float[].class, PathResult.class, TubularGeodesicsTracer.class };

				final Method m = c.getMethod("startSearch", parameterTypes);
				final Object[] parameters = new Object[5];
				parameters[0] = oofFile.getAbsolutePath();
				parameters[1] = p1;
				parameters[2] = p2;
				parameters[3] = temporaryPathResult;
				parameters[4] = this;

				m.invoke(newInstance, parameters);

			}
			catch (final IllegalArgumentException e) {
				reportFinished(false);
				throw new RuntimeException(
					"There was an illegal argument when trying to invoke startSearch: " +
						e);
			}
			catch (final InvocationTargetException e) {
				reportFinished(false);
				final Throwable realException = e.getTargetException();
				throw new RuntimeException(
					"There was an exception thrown by startSearch: " + realException);
			}
			catch (final ClassNotFoundException e) {
				reportFinished(false);
				throw new RuntimeException(
					"The FijiITKInterface.TubularGeodesics class was not found: " + e);
			}
			catch (final InstantiationException e) {
				reportFinished(false);
				throw new RuntimeException(
					"Failed to instantiate the FijiITKInterface.TubularGeodesics object: " +
						e);
			}
			catch (final IllegalAccessException e) {
				reportFinished(false);
				throw new RuntimeException(
					"IllegalAccessException when trying to create an instance of FijiITKInterface.TubularGeodesics: " +
						e);
			}
			catch (final NoSuchMethodException e) {
				reportFinished(false);
				throw new RuntimeException(
					"There was a NoSuchMethodException when trying to invoke startSearch: " +
						e);
			}
			catch (final SecurityException e) {
				reportFinished(false);
				throw new RuntimeException(
					"There was a SecurityException when trying to invoke startSearch: " +
						e);
			}

		}
		catch (final Throwable t) {
			System.out.println("Got an exception from call to ITK code: " + t);
			t.printStackTrace();
			IJ.error("There was an error in calling to ITK code: " + t);
		}
	}

	public void reportFinished(final boolean success) {
		if (success) lastPathResult = temporaryPathResult;
		for (final SearchProgressCallback progress : progressListeners)
			progress.finished(this, success);
		if (!success) {
			final String errorMessage = temporaryPathResult.getErrorMessage();
			/*
			 * If this is null, it means that the user cancelled the search, so we don't
			 * need to report an error:
			 */
			if (errorMessage != null) IJ.error("The tracing failed: " + errorMessage);
		}
	}

}
