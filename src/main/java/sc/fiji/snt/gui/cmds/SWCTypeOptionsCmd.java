/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2022 Fiji developers.
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

package sc.fiji.snt.gui.cmds;

import java.awt.Color;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

import net.imagej.ImageJ;

import org.scijava.ItemVisibility;
import org.scijava.command.Command;
import org.scijava.command.ContextCommand;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.prefs.PrefService;
import org.scijava.util.ColorRGB;
import org.scijava.widget.Button;

import sc.fiji.snt.PathManagerUI;
import sc.fiji.snt.Path;
import sc.fiji.snt.gui.GuiUtils;

/**
 * Implements {@link PathManagerUI}'s SWC-SWC-type tagging Options
 * Command.
 *
 * @author Tiago Ferreira
 */
@Plugin(type = Command.class, visible = false, label = "SWC-type Tagging",
	initializer = "init")
public class SWCTypeOptionsCmd extends ContextCommand {

	private static final String HEADER = "<html><body><div style='width:500;'>";
	private static final String MAP_KEY = "colors";
	private static final String ASSIGN_KEY = "assign";

	@Parameter
	private PrefService prefService;

	/*
	 * NB: This prompt is just a GUI for PrefService. Since we'll be storing the
	 * values of all these fields manually we'll set everything to non-persistent
	 */

	@Parameter(required = false, label = "   Enable color pairing")
	private boolean enableColors;

	@Parameter(required = true, persist = false, label = Path.SWC_DENDRITE_LABEL)
	private ColorRGB basalDendriteColor;

	@Parameter(required = true, persist = false,
		label = Path.SWC_APICAL_DENDRITE_LABEL)
	private ColorRGB apicalDendriteColor;

	@Parameter(required = true, persist = false, label = Path.SWC_AXON_LABEL)
	private ColorRGB axonColor;

	@Parameter(required = true, persist = false, label = Path.SWC_CUSTOM_LABEL)
	private ColorRGB customColor;

	@Parameter(required = true, label = Path.SWC_SOMA_LABEL)
	private ColorRGB somaColor;

	@Parameter(required = true, persist = false, label = Path.SWC_UNDEFINED_LABEL)
	private ColorRGB undefinedColor;

	@Parameter(required = false, persist = false, label = "Reset Defaults",
		callback = "reset")
	private Button reset;

	@Parameter(visibility = ItemVisibility.MESSAGE)
	private final String msg = HEADER + "When <i>color pairing</i> is enabled, " +
		"assigning a <i>SWC-type</i> tag automaticaly colors the path " +
		"with the respective listed color. Note that it is also possible " +
		"to assign ad-hoc colors using the <i>Tag>Color></i> menu.";

	/*
	 * (non-Javadoc)
	 *
	 * @see java.lang.Runnable#run()
	 */
	@Override
	public void run() {
		final LinkedHashMap<String, String> map =
			new LinkedHashMap<>();
		map.put(String.valueOf(Path.SWC_DENDRITE), basalDendriteColor
			.toHTMLColor());
		map.put(String.valueOf(Path.SWC_APICAL_DENDRITE), apicalDendriteColor
			.toHTMLColor());
		map.put(String.valueOf(Path.SWC_AXON), axonColor.toHTMLColor());
		map.put(String.valueOf(Path.SWC_CUSTOM), customColor.toHTMLColor());
		map.put(String.valueOf(Path.SWC_SOMA), somaColor.toHTMLColor());
		map.put(String.valueOf(Path.SWC_UNDEFINED), undefinedColor.toHTMLColor());
		prefService.put(SWCTypeOptionsCmd.class, MAP_KEY, map);
		prefService.put(SWCTypeOptionsCmd.class, ASSIGN_KEY, enableColors);
	}

	private Map<Integer, ColorRGB> getDefaultMap() {
		final LinkedHashMap<Integer, ColorRGB> map =
			new LinkedHashMap<>();
		map.put(Path.SWC_DENDRITE, getDefaultSWCColorRGB(Path.SWC_DENDRITE));
		map.put(Path.SWC_APICAL_DENDRITE, getDefaultSWCColorRGB(
			Path.SWC_APICAL_DENDRITE));
		map.put(Path.SWC_AXON, getDefaultSWCColorRGB(Path.SWC_AXON));
		map.put(Path.SWC_CUSTOM, getDefaultSWCColorRGB(Path.SWC_CUSTOM));
		map.put(Path.SWC_SOMA, getDefaultSWCColorRGB(Path.SWC_SOMA));
		map.put(Path.SWC_UNDEFINED, getDefaultSWCColorRGB(Path.SWC_UNDEFINED));
		return map;
	}

	private Map<Integer, ColorRGB> getSavedMap() {
		final Map<String, String> smap = prefService.getMap(SWCTypeOptionsCmd.class,
			MAP_KEY);
		if (smap == null || smap.isEmpty()) {
			return getDefaultMap();
		}
		final LinkedHashMap<Integer, ColorRGB> map =
			new LinkedHashMap<>();
		for (final Map.Entry<String, String> entry : smap.entrySet()) {
			final String key = entry.getKey();
			final String value = entry.getValue();
			map.put(Integer.valueOf(key), ColorRGB.fromHTMLColor(value));
		}
		// while at it, read other preferences
		enableColors = prefService.getBoolean(SWCTypeOptionsCmd.class, ASSIGN_KEY,
			true);
		return map;
	}

	@SuppressWarnings("unused")
	private void init() {
		assignColors(getSavedMap());
	}

	@SuppressWarnings("unused")
	private void reset() {
		enableColors = true;
		assignColors(getDefaultMap());
		prefService.clear(SWCTypeOptionsCmd.class);
	}

	private void assignColors(final Map<Integer, ColorRGB> map) {
		apicalDendriteColor = map.get(Path.SWC_APICAL_DENDRITE);
		axonColor = map.get(Path.SWC_AXON);
		basalDendriteColor = map.get(Path.SWC_DENDRITE);
		customColor = map.get(Path.SWC_CUSTOM);
		somaColor = map.get(Path.SWC_SOMA);
		undefinedColor = map.get(Path.SWC_UNDEFINED);
	}

	private class SWCTypeComparator implements Comparator<Integer> {

		@Override
		public int compare(final Integer i1, final Integer i2) {
			final String s1 = Path.getSWCtypeName(i1, false);
			final String s2 = Path.getSWCtypeName(i2, false);
			return s1.compareTo(s2);
		}
	}

	public TreeMap<Integer, Color> getColorMap() {
		final Map<Integer, ColorRGB> maprgb = getSavedMap();
		final TreeMap<Integer, Color> map = new TreeMap<>(
			new SWCTypeComparator()); // new SWCTypeComparator());
		for (final Map.Entry<Integer, ColorRGB> entry : maprgb.entrySet()) {
			final int key = entry.getKey();
			final ColorRGB color = entry.getValue();
			map.put(key, getColorFromColorRGB(color));
		}
		return map;
	}

	public boolean isColorPairingEnabled() {
		return enableColors;
	}

	private Color getColorFromColorRGB(final ColorRGB c) {
		return new Color(c.getRed(), c.getGreen(), c.getBlue());
	}

	private ColorRGB getColorRGBfromColor(final Color c) {
		return new ColorRGB(c.getRed(), c.getGreen(), c.getBlue());
	}

	private ColorRGB getDefaultSWCColorRGB(final int swcType) {
		return getColorRGBfromColor(getDefaultSWCColor(swcType));
	}

	private Color getDefaultSWCColor(final int swcType) {
		return Path.getSWCcolor(swcType);
	}

	/* IDE debug method **/
	public static void main(final String[] args) {
		GuiUtils.setLookAndFeel();
		final ImageJ ij = new ImageJ();
		ij.ui().showUI();
		ij.command().run(SWCTypeOptionsCmd.class, true);
	}

}
