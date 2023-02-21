/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2023 Fiji developers.
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

package sc.fiji.snt;

/**
 * Classes transforming {@link Path}s should implement this interface.
 * <p>
 * Methods accepting integer values assume those to be indexes into the image's
 * samples, with z being 0-based. Methods accepting double values assume those
 * to be world coordinates (i.e. spatially calibrated).
 * </p>
 * <p>
 * If the corresponding point is not found, the transformed values are set to
 * {@code Integer.MIN_VALUE} or {@code Double.NaN}
 * </p>
 */
public interface PathTransformer {

	public void transformPoint(double x, double y, double z,
		double[] transformed);

	public void transformPoint(double x, double y, double z, int[] transformed);

	public void transformPoint(int x, int y, int z, int[] transformed);

	public void transformPoint(int x, int y, int z, double[] transformed);

}
