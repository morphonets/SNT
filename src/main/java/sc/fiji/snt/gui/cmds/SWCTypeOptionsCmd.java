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

	private static final String MAP_KEY = "colors";
	private static final String ASSIGN_KEY = "assign";
	private static String HEADER = "<HTML><body><div style='width:"
			+ GuiUtils.renderedWidth("When color pairing is enable, assigning a") + ";'>";

	@Parameter
	private PrefService prefService;

	/*
	 * NB: This prompt is just a GUI for PrefService. Since we'll be storing the
	 * values of all these fields manually we'll set everything to non-persistent
	 */

	@Parameter(required = false, label = "   Enable color pairing")
	private boolean enableColors;

	@Parameter(persist = false, label = Path.SWC_SOMA_LABEL)
	private ColorRGB somaColor;

	@Parameter(persist = false, label = Path.SWC_AXON_LABEL)
	private ColorRGB axonColor;

	@Parameter(persist = false, label = Path.SWC_DENDRITE_LABEL)
	private ColorRGB basalDendriteColor;

	@Parameter(persist = false, label = Path.SWC_APICAL_DENDRITE_LABEL)
	private ColorRGB apicalDendriteColor;

	@Parameter(persist = false, label = Path.SWC_UNDEFINED_LABEL)
	private ColorRGB undefinedColor;

	@Parameter(persist = false, label = Path.SWC_UNSPECIFIED_LABEL)
	private ColorRGB unspecifiedColor;

	@Parameter(persist = false, label = Path.SWC_GLIA_PROCESS_LABEL)
	private ColorRGB gliaColor;

	@Parameter(persist = false, label = Path.SWC_CUSTOM_LABEL)
	private ColorRGB customColor;

	@Parameter(persist = false, label = Path.SWC_CUSTOM2_LABEL)
	private ColorRGB custom2Color;

	@Parameter(required = false, persist = false, label = "Reset Defaults", callback = "reset")
	private Button reset;

	@Parameter(visibility = ItemVisibility.MESSAGE)
	private final String msg = HEADER + "When <i>color pairing</i> is enabled, " +
		"assigning a <i>SWC-type</i> tag automaticaly colors the path " +
		"with its associated color. Note that it is also possible " +
		"to assign ad-hoc colors using the <i>Tag>Color></i> menu.";

	/*
	 * (non-Javadoc)
	 *
	 * @see java.lang.Runnable#run()
	 */
	@Override
	public void run() {
		final LinkedHashMap<String, String> map = new LinkedHashMap<>();
		map.put(String.valueOf(Path.SWC_SOMA), somaColor.toHTMLColor());
		map.put(String.valueOf(Path.SWC_AXON), axonColor.toHTMLColor());
		map.put(String.valueOf(Path.SWC_DENDRITE), basalDendriteColor.toHTMLColor());
		map.put(String.valueOf(Path.SWC_APICAL_DENDRITE), apicalDendriteColor.toHTMLColor());
		map.put(String.valueOf(Path.SWC_UNDEFINED), undefinedColor.toHTMLColor());
		map.put(String.valueOf(Path.SWC_UNSPECIFIED), unspecifiedColor.toHTMLColor());
		map.put(String.valueOf(Path.SWC_GLIA_PROCESS), gliaColor.toHTMLColor());
		map.put(String.valueOf(Path.SWC_CUSTOM), customColor.toHTMLColor());
		map.put(String.valueOf(Path.SWC_CUSTOM2), custom2Color.toHTMLColor());
		prefService.put(SWCTypeOptionsCmd.class, MAP_KEY, map);
		prefService.put(SWCTypeOptionsCmd.class, ASSIGN_KEY, enableColors);
	}

	private Map<Integer, ColorRGB> getDefaultMap() {
		final LinkedHashMap<Integer, ColorRGB> map = new LinkedHashMap<>();
		map.put(Path.SWC_SOMA, getDefaultSWCColorRGB(Path.SWC_SOMA));
		map.put(Path.SWC_AXON, getDefaultSWCColorRGB(Path.SWC_AXON));
		map.put(Path.SWC_DENDRITE, getDefaultSWCColorRGB(Path.SWC_DENDRITE));
		map.put(Path.SWC_APICAL_DENDRITE, getDefaultSWCColorRGB(Path.SWC_APICAL_DENDRITE));
		map.put(Path.SWC_UNDEFINED, getDefaultSWCColorRGB(Path.SWC_UNDEFINED));
		map.put(Path.SWC_UNSPECIFIED, getDefaultSWCColorRGB(Path.SWC_UNSPECIFIED));
		map.put(Path.SWC_GLIA_PROCESS, getDefaultSWCColorRGB(Path.SWC_GLIA_PROCESS));
		map.put(Path.SWC_CUSTOM, getDefaultSWCColorRGB(Path.SWC_CUSTOM));
		map.put(Path.SWC_CUSTOM2, getDefaultSWCColorRGB(Path.SWC_CUSTOM2));
		return map;
	}

	private Map<Integer, ColorRGB> getSavedMap() {
		final Map<String, String> smap = prefService.getMap(SWCTypeOptionsCmd.class, MAP_KEY);
		if (smap == null || smap.isEmpty() || smap.size() < 9) { // only 6 colors before v4.3
			return getDefaultMap();
		}
		final LinkedHashMap<Integer, ColorRGB> map = new LinkedHashMap<>();
		smap.forEach((k, v) -> map.put(Integer.valueOf(k), ColorRGB.fromHTMLColor(v)));
		return map;
	}

	@SuppressWarnings("unused")
	private void init() {
		enableColors = isColorPairingEnabled();
		assignColors(getSavedMap());
	}

	@SuppressWarnings("unused")
	private void reset() {
		enableColors = true;
		assignColors(getDefaultMap());
		prefService.clear(SWCTypeOptionsCmd.class);
	}

	private void assignColors(final Map<Integer, ColorRGB> map) {
		somaColor = map.get(Path.SWC_SOMA);
		axonColor = map.get(Path.SWC_AXON);
		basalDendriteColor = map.get(Path.SWC_DENDRITE);
		apicalDendriteColor = map.get(Path.SWC_APICAL_DENDRITE);
		undefinedColor = map.get(Path.SWC_UNDEFINED);
		unspecifiedColor = map.get(Path.SWC_UNSPECIFIED);
		gliaColor = map.get(Path.SWC_GLIA_PROCESS);
		customColor = map.get(Path.SWC_CUSTOM);
		custom2Color = map.get(Path.SWC_CUSTOM2);
	}

	private static class SWCTypeComparator implements Comparator<Integer> {

		@Override
		public int compare(final Integer i1, final Integer i2) {
			final String s1 = Path.getSWCtypeName(i1, false);
			final String s2 = Path.getSWCtypeName(i2, false);
			return s1.compareTo(s2);
		}
	}

	public TreeMap<Integer, Color> getColorMap() {
		final Map<Integer, ColorRGB> maprgb = getSavedMap();
		final TreeMap<Integer, Color> map = new TreeMap<>(new SWCTypeComparator());
		maprgb.forEach((k,v) -> map.put(k, getColorFromColorRGB(v)));
		return map;
	}

	public boolean isColorPairingEnabled() {
		return prefService.getBoolean(SWCTypeOptionsCmd.class, ASSIGN_KEY, true);
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
