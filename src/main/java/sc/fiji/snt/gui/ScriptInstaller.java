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

package sc.fiji.snt.gui;

import java.awt.Component;
import java.awt.event.InputEvent;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.Future;
import java.util.regex.Pattern;

import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.event.MenuKeyEvent;
import javax.swing.event.MenuKeyListener;

import org.scijava.Context;
import org.scijava.plugin.Parameter;
import org.scijava.script.ScriptInfo;
import org.scijava.script.ScriptLanguage;
import org.scijava.script.ScriptModule;
import org.scijava.script.ScriptService;
import org.scijava.ui.swing.script.TextEditor;
import org.scijava.util.FileUtils;

import sc.fiji.snt.SNTUI;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.gui.IconFactory.GLYPH;

/**
 * Utility class for discovery of SNT scripts
 * 
 * @author Tiago Ferreira
 */
public class ScriptInstaller implements MenuKeyListener {

	@Parameter
	private Context context;

	@Parameter
	private ScriptService scriptService;

	public static final Pattern DEMO_SCRIPT = Pattern.compile(".*demo.*", Pattern.CASE_INSENSITIVE);
	public static final Pattern NON_DEMO_SCRIPT = Pattern.compile("^(?!.*demo).*$", Pattern.CASE_INSENSITIVE);

	private final SNTUI ui;
	private final GuiUtils guiUtils;
	private TreeSet<ScriptInfo> scripts;
	private boolean openInsteadOfRun;

	public ScriptInstaller(final Context context, final Component parent){
		context.inject(this);
		ui = null;
		guiUtils = new GuiUtils(parent);
		init();
	}

	protected ScriptInstaller(final Context context, final SNTUI ui) {
		context.inject(this);
		this.ui = ui;
		guiUtils = new GuiUtils(ui);
		init();
	}

	private void init() {
		scripts = new TreeSet<>(Comparator.comparing(this::getScriptLabel));

		// 1. Include script_templates that are not discovered by ScriptService
		final Map<String, URL> map = FileUtils.findResources(null,
			"script_templates/Neuroanatomy", null);
		if (map != null) {
			map.forEach((k, v) -> {
				try {
					if (!k.toLowerCase().contains("boilerplate"))
						scripts.add(new ScriptInfo(context, v, k));
				}
				catch (final IOException ignored) {
					// just skip file
				}
			});
		}
		// 2. Parse discovered scripts
		addAllDiscoveredScripts();

		// 3. Include all other scripts
		addLocalScripts();

	}

	private void addLocalScripts() {
		// Do a second pass in case scripts outside the plugins directory are missing
		final File dir = getScriptsDir();
		if (dir == null) return;
		final File[] filteredScripts = dir.listFiles((file) -> {
			final ScriptLanguage lang = scriptService.getLanguageByExtension(FileUtils.getExtension(file));
			if (lang == null) return false;
			final String name = file.getName();
			return file.canRead() && (name.contains("SNT") || name.toLowerCase().contains("neuroanatomy"));
		});
		if (filteredScripts != null) {
			for (final File file : filteredScripts) {
				final ScriptInfo si = scriptService.getScript(file);
				if (si != null) {
					scripts.add(si);
				}
			}
		}
	}

	private String getScriptsDirPath() {
		File dir = getScriptsDir();
		return (dir==null) ? null : dir.getAbsolutePath();
	}

	private File getScriptsDir() {
		final List<File> dirs = scriptService.getScriptDirectories();
		for (final File dir : dirs) {
			if (!dir.getAbsolutePath().contains("plugins")) return dir;
		}
		return null;
	}

	private void addAllDiscoveredScripts() {
		for (final ScriptInfo si : scriptService.getScripts()) {
			final boolean pathMatch = si.getPath() != null && (si.getPath().contains(
				"SNT") || si.getPath().toLowerCase().contains("neuroanatomy"));
			if (pathMatch) scripts.add(si);
		}
	}

	private void runScript(final ScriptInfo si) {
		if (ui != null) ui.showStatus("Running script...", false);
		final Future<ScriptModule> fsm = scriptService.run(si, true,
			(Map<String, Object>) null);
		if (fsm.isCancelled()) {
			ui.showStatus("Script canceled...", true);
		}
		else if (fsm.isDone() && ui != null) {
			ui.showStatus("Script completed...", true);
		}
	}

	private void openScript(final ScriptInfo si) {
		if (ui != null) ui.showStatus("Opening script...", false);
		final TextEditor editor = new TextEditor(context);
		final BufferedReader reader = si.getReader();
		if (reader == null) { // local file
			editor.open(new File(si.getPath()));
		}
		else { // jar file
			try {
				final StringBuilder stringBuffer = new StringBuilder();
				String line = null;
				while ((line = reader.readLine()) != null) {
					stringBuffer.append(line).append("\n");
				}
				editor.createNewDocument(getScriptLabel(si), stringBuffer.toString());
			}
			catch (final IOException e) {
				e.printStackTrace();
			}
		}
		editor.setVisible(true);
		if (ui != null) ui.showStatus("", false);
	}

	private JMenu getMenu(final String folder, final Pattern excludePattern, final boolean trimExtension) {
		final JMenu sMenu = new JMenu((folder == null) ? "Full List" : folder.replace("_", " "));
		sMenu.addMenuKeyListener(this);
		for (final ScriptInfo si : scripts) {
			final String[] dirAndFile = getDirAndFilename(si.getPath());
			if (dirAndFile == null || (folder != null && !dirAndFile[0].contains(folder))) continue;
			if (excludePattern != null && excludePattern.matcher(dirAndFile[1]).matches()) continue;
			final JMenuItem mItem = new JMenuItem(getScriptLabel(si,trimExtension));
			mItem.setToolTipText("Click to run script. Click holding Shift to open it");
			sMenu.add(mItem);
			mItem.addMenuKeyListener(this);
			mItem.addActionListener(e -> {
				final int mods = e.getModifiers();
				if (openInsteadOfRun || (mods & InputEvent.SHIFT_MASK) != 0
						|| (mods & InputEvent.SHIFT_DOWN_MASK) != 0)
				{
					openScript(si);
				}
				else {
					runScript(si);
				}
				openInsteadOfRun = false;
			});
		}
		return sMenu;
	}

	private String[] getDirAndFilename(final String resourcePath) {
		if (resourcePath == null) return null;
		final String[] result = new String[2];
		final int slashIndex = resourcePath.lastIndexOf("/"); // path separator in JARs is always "/"
		if (slashIndex == -1) {
			result[0] = resourcePath;
			result[1] = resourcePath;
		} else {
			result[0] = resourcePath.substring(0, slashIndex);
			result[1] = resourcePath.substring(slashIndex);
		}
		return result;
	}

	/** Returns a UI list of SNT's 'Batch' scripts **/
	public JMenu getBatchScriptsMenu() {
		final JMenu menu = getMenu("Batch", DEMO_SCRIPT, true);
		for (int i = 0; i < menu.getItemCount(); i++) {
			final JMenuItem mItem = menu.getItem(i);
			mItem.setText(SNTUtils.stripExtension(mItem.getText()) + "...");
		}
		return menu;
	}

	/** Returns a UI list with all the bundled non-demo SNT scripts **/
	public JMenu getScriptsMenu() {
		final JMenu menus = getScriptsMenu(DEMO_SCRIPT, "Analysis", "Batch", "Render", "Skeletons_and_ROIs", "Tracing");
		menus.insert(getDemosMenu(), 5);
		return menus;
	}

	/**
	 * Returns a UI list with the bundled SNT scripts (specified directories only).
	 *
	 * @param excludePattern the exclusion pattern (e.g., {@link #DEMO_SCRIPT}).
	 *                       Null allowed.
	 * @param directories    the subset of directories (e.g., Analysis, Batch, etc.)
	 * @return the scripts menu
	 */
	public JMenu getScriptsMenu(final Pattern excludePattern, final String... directories) {
		final JMenu sMenu = new JMenu("Scripts");
		for (final String dir : directories) {
			final JMenu menu = getMenu(dir, excludePattern, true);
			if (menu.getMenuComponents().length > 0)
				sMenu.add(getMenu(dir, excludePattern, true));
		}
		final JMenu listMenu = getFullListMenu();
		final int listMenuPosition = sMenu.getItemCount();
		sMenu.add(listMenu);
		final JMenuItem reloadMI = new JMenuItem("Reload Scripts...", IconFactory.getMenuIcon(GLYPH.REDO));
		reloadMI.addActionListener(e -> {
			final int oldCount = scripts.size();
			addLocalScripts();
			final int newCount = scripts.size();
			if (oldCount == newCount) {
				guiUtils.centeredMsg("No new scripts detected.", "List Reloaded");
				return;
			}
			sMenu.remove(listMenuPosition);
			sMenu.add(getFullListMenu(), listMenuPosition);
			sMenu.revalidate();
			guiUtils.centeredMsg(""+ (newCount-oldCount) +" new script(s) added to \"Scripts>Full List>\".", "New Script(s) Detected");
		});
		sMenu.add(reloadMI);
		sMenu.addSeparator();
		final JMenuItem mi = new JMenuItem("New...", IconFactory.getMenuIcon(GLYPH.CODE));
		mi.addActionListener(e -> {

			final HashMap<String, String> map = new HashMap<>();
			map.put("BeanShell", "BSH.bsh");
			map.put("Groovy", "GVY.groovy");
			map.put("Python", "PY.py");
			final String choice = guiUtils.getChoice("Language:", "New Script",
					map.keySet().toArray(new String[map.keySet().size()]), "");
			if (choice == null) return; // user pressed cancel

			final TextEditor editor = new TextEditor(context);
			final ClassLoader classloader = Thread.currentThread().getContextClassLoader();
			boolean save = true;
			try {
				editor.loadTemplate(
						classloader.getResource("script_templates/Neuroanatomy/Boilerplate/" + map.get(choice)));
			} catch (final NullPointerException ignored) {
				ui.error("Boilerpate script could not be retrieved. Use Script Editor's Templates>Neuroanatomy> instead.");
				save = false;
			} finally {
				editor.setVisible(true);
				if (save)
					editor.saveAs(
							getScriptsDir() + File.separator + "_SNT_script." + FileUtils.getExtension(map.get(choice)));
			}
		});
		sMenu.add(mi);
		sMenu.addSeparator();
		sMenu.add(about());
		return sMenu;
	}

	public void runScript(final String folder, final String name) throws IllegalArgumentException{
		for (final ScriptInfo si : scripts) {
			final String path = si.getPath();
			if (path == null || (folder != null && !path.contains(folder))) continue;
			if (name.equals(getScriptLabel(si, true)) || name.equals(getScriptLabel(si, false))) {
				runScript(si); 
				return;
			}
		}
		throw new IllegalArgumentException("Script not found");
	}

	private JMenu getDemosMenu() { // Will include demo scripts
		final JMenu demoMenu = getMenu(null, NON_DEMO_SCRIPT, true);
		demoMenu.setText("Demos");
		demoMenu.setToolTipText("Demo scripts. Please save your work before running a demo mid-tracing");
		demoMenu.setIcon(IconFactory.getMenuIcon(IconFactory.GLYPH.GRADUATION_CAP));
		return demoMenu;
	}

	private JMenu getFullListMenu() { // Will include _ALL_ scripts (no exclusions)
		final JMenu listMenu = getMenu(null, null, false);
		listMenu.setIcon(IconFactory.getMenuIcon(IconFactory.GLYPH.LIST));
		return listMenu;
	}

	private String getScriptLabel(final ScriptInfo si, final boolean trimExtension) {
		final String label = (trimExtension) ? SNTUtils.stripExtension(getScriptLabel(si)) : getScriptLabel(si);
		return label.replace('_', ' ');
	}

	private String getScriptLabel(final ScriptInfo si) {
		String label = si.getLabel();
		if (label != null) return label;
		label = si.getName();
		if (label != null) return label;
		label = si.getPath();
		if (label != null) return label.substring(label.lastIndexOf(
			File.separator) + 1);
		return si.getIdentifier(); // never null
	}

	private JMenuItem about() {
		final JMenuItem mItem = new JMenuItem("About SNT Scripts...");
		mItem.setIcon(IconFactory.getMenuIcon(IconFactory.GLYPH.QUESTION));
		mItem.addActionListener(e -> {
			guiUtils.showHTMLDialog(
				"<HTML><div WIDTH=500>This menu lists scripting routines that " //
				+ "<a href='https://imagej.net/SNT:_Scripting'>enhance SNT functionality</a>. " //
				+ "The list is automatically populated at startup.<br><br>" //
				+ "To have your own scripts listed here, save them in the <tt>scripts</tt> " //
				+ "directory while including <i>SNT</i> in the filename (e.g., <tt>" //
				+ getScriptsDirPath() + File.separator + "My_SNT_script.py</tt>) <br><br>" //
				+ "To edit a listed script hold \"Shift\" while clicking on its menu entry.<br><br>" //
				+ "Many other programming examples are available through the Script Editor's " //
				+ "<i>Templates>Neuroanatomy></i> menu.<br>Please submit a pull request to " //
				+ "<a href='https://github.com/morphonets/SNT/'>SNT's repository</a>. if " //
				+ "you would like to have your scripts distributed with Fiji.",
				"About SNT Scripts...", true);
		});
		return mItem;
	}

	@Override
	public void menuKeyTyped(final MenuKeyEvent e) {
		// ignored
	}

	@Override
	public void menuKeyPressed(final MenuKeyEvent e) {
		openInsteadOfRun = e.isShiftDown();
	}

	@Override
	public void menuKeyReleased(final MenuKeyEvent e) {
		openInsteadOfRun = e.isShiftDown();
	}

}
