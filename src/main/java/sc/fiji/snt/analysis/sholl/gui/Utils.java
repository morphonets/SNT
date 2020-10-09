/*
 * #%L
 * Sholl Analysis plugin for ImageJ.
 * %%
 * Copyright (C) 2005 - 2020 Tiago Ferreira.
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
package sc.fiji.snt.analysis.sholl.gui;

import java.awt.Color;
import java.awt.GraphicsEnvironment;

import javax.swing.JMenuItem;

import sc.fiji.snt.gui.EnhancedGenericDialog;

@Deprecated
public class Utils {

	private Utils() {
	}

	@Deprecated
	public static final void improveRecording() {
		EnhancedGenericDialog.improveRecording();
	}

	@Deprecated
	public static Color getDisabledComponentColor() {
		return EnhancedGenericDialog.getDisabledComponentColor();
	}

	@Deprecated
	public static JMenuItem menuItemTrigerringURL(final String label, final String URL) {
		return EnhancedGenericDialog.menuItemTrigerringURL(label, URL);
	}

	@Deprecated
	public static JMenuItem menuItemTriggeringResources() {
		return EnhancedGenericDialog.menuItemTriggeringResources();
	}

	/**
	 * @return {@code true} if running on an headless environment
	 */
	public static boolean isHeadless() {
		return GraphicsEnvironment.isHeadless();
	}

	@Deprecated
	public final static Color infoColor() {
		return EnhancedGenericDialog.infoColor();
	}

	@Deprecated
	public final static Color warningColor() {
		return EnhancedGenericDialog.warningColor();
	}

}
