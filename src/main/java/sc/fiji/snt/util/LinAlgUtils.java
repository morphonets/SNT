/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2021 Fiji developers.
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

/**
 * Static methods for linear algebra
 *
 * @author Cameron Arshadi
 */
public class LinAlgUtils {

    private LinAlgUtils() {
    }

    /**
     * 4x4 Householder reflection matrix
     */
    public static double[][] reflectionMatrix(final double[] planePoint, final double[] planeNormal) {
        final double a = planeNormal[0];
        final double b = planeNormal[1];
        final double c = planeNormal[2];
        final double d = -1 * a * planePoint[0] - b * planePoint[1] - c * planePoint[2];
        return new double[][]{
                {1 - 2 * a * a, -2 * a * b, -2 * a * c, -2 * a * d},
                {-2 * a * b, 1 - 2 * b * b, -2 * b * c, -2 * b * d},
                {-2 * a * c, -2 * b * c, 1 - 2 * c * c, -2 * c * d},
                {0, 0, 0, 1}
        };
    }

}
