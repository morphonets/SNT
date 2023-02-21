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

package sc.fiji.snt.util;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ByteProcessor;

/**
 * Static utilities for handling and manipulation of {@link ImagePlus}s
 *
 * @author Tiago Ferreira
 *
 */
public class ImpUtils {

	private ImpUtils() {} // prevent class instantiation

	public static void removeIsolatedPixels(final ImagePlus binaryImp) {
		final ImageStack stack = binaryImp.getStack();
		for (int i = 1; i <= stack.getSize(); i++)
			((ByteProcessor) stack.getProcessor(i)).erode(8, 0);
	}

}
