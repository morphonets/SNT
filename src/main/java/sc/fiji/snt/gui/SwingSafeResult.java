/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2020 Fiji developers.
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

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

import javax.swing.SwingUtilities;

/**
 * Utility class that ensures EDT execution.
 *
 * @author Tiago Ferreira
 */
public class SwingSafeResult {

	public static <T> T getResult(final Callable<T> c) {

		final FutureTask<T> ft = new FutureTask<>(c);

		if (SwingUtilities.isEventDispatchThread()) ft.run();
		else SwingUtilities.invokeLater(ft);

		while (true) {
			try {
				return ft.get();
			}
			catch (final InterruptedException e) {
				// just try again...
			}
			catch (final ExecutionException e) {
				e.getCause().printStackTrace();
				return null;
			}
		}
	}
}
