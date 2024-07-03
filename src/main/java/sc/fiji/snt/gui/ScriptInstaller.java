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

package sc.fiji.snt.gui;

import java.awt.Component;
import java.awt.HeadlessException;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.*;
import java.util.concurrent.Future;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.swing.Icon;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.MenuElement;
import javax.swing.MenuSelectionManager;
import javax.swing.SwingUtilities;
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
	private static TextEditor editor;

	private boolean openInsteadOfRun;

	public ScriptInstaller(final Context context, final Component parent){
		context.inject(this);
		if (parent instanceof SNTUI)
			ui = (SNTUI)parent;
		else
			ui = null;
		guiUtils = new GuiUtils(parent);
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

	public File getScriptsDir() {
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
		if (fsm.isCancelled() && ui != null) {
			ui.showStatus("Script canceled...", true);
		}
		else if (fsm.isDone() && ui != null) {
			ui.showStatus("Script completed...", true);
		}
	}

	private TextEditor getTextEditor() {
		if (editor == null)
			editor = new TextEditor((context == null) ? SNTUtils.getContext() : context);
		return editor;
	}

	private void openScript(final ScriptInfo si) {
		if (ui != null) ui.showStatus("Opening script...", false);
		final BufferedReader reader = si.getReader();
		if (reader == null) { // local file
			getTextEditor().open(new File(si.getPath()));
		}
		else { // jar file
			try {
				final StringBuilder stringBuffer = new StringBuilder();
				String line = null;
				while ((line = reader.readLine()) != null) {
					stringBuffer.append(line).append("\n");
				}
				getTextEditor().createNewDocument(getScriptLabel(si), stringBuffer.toString());
			}
			catch (final IOException e) {
				e.printStackTrace();
			}
		}
		getTextEditor().getTextArea().setCaretPosition(0); // scroll to top
		getTextEditor().setVisible(true);
		if (ui != null) ui.showStatus("", false);
	}

	private JMenu getMenu(final String folder, final Pattern excludePattern, final boolean trimExtension) {
		final JMenu sMenu = new JMenu((folder == null) ? "Full List" : folder.replace("_", " "));
		sMenu.setIcon(getIcon(sMenu.getText()));
		for (final ScriptInfo si : scripts) {
			final String[] dirAndFile = getDirAndFilename(si.getPath());
			if (dirAndFile == null || (folder != null && !dirAndFile[0].contains(folder))) continue;
			if (excludePattern != null && excludePattern.matcher(dirAndFile[1]).matches()) continue;
			sMenu.add(menuItem(si, trimExtension));
		}
		return sMenu;
	}

	private Icon getIcon(final String menuName) {
		switch(menuName) {
		case "Analysis":
			return IconFactory.getMenuIcon(GLYPH.CHART);
		case "Batch":
			return IconFactory.getMenuIcon(GLYPH.COG);
		case "Demos":
			return IconFactory.getMenuIcon(GLYPH.GRADUATION_CAP);
		case "Full List":
			return IconFactory.getMenuIcon(GLYPH.LIST);
		case "Misc":
			return IconFactory.getMenuIcon(GLYPH.ELLIPSIS);
		case "Render":
			return IconFactory.getMenuIcon(GLYPH.CUBE);
		case "Skeletons and ROIs":
			return IconFactory.getMenuIcon(GLYPH.BEZIER_CURVE);
		case "Tracing":
			return IconFactory.getMenuIcon(GLYPH.ROUTE);
		case "Time-lapses":
			return IconFactory.getMenuIcon(GLYPH.VIDEO);
		default:
			return null;
		}
	}
	private JMenuItem menuItem(final ScriptInfo si, final boolean trimExtension) {
		final JMenuItem mItem = new JMenuItem(getScriptLabel(si, trimExtension));
		mItem.setToolTipText("Click to run script. Click holding Shift to open it");
		mItem.addMenuKeyListener(this);
		mItem.addChangeListener(e -> updateMenuItemIcon(mItem));
		mItem.addActionListener(e -> {
			if (openInsteadOfRun) {
				openScript(si);
			} else {
				runScript(si);
			}
			openInsteadOfRun = false;
		});
		return mItem;
	}

	private void updateMenuItemIcon(final JMenuItem item) {
		if (openInsteadOfRun && (item.isSelected() || item.isArmed())) {
			item.setIcon(IconFactory.getMenuIcon(GLYPH.EYE));
		} else {
			item.setIcon(null);
		}
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
		final JMenu menus = getScriptsMenu(DEMO_SCRIPT, "Analysis", "Batch", "Misc", "Render", "Skeletons_and_ROIs", "Tracing", "Time-lapses");
		menus.insert(getDemosMenu(), 2);
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
				sMenu.add(menu);
		}

		final JMenu listMenu = getFullListMenu();
		final int listMenuPosition = sMenu.getItemCount();
		final JMenuItem reloadMI = new JMenuItem("Reload Scripts...", IconFactory.getMenuIcon(GLYPH.REDO));
		reloadMI.addActionListener(e -> {
			final int oldCount = scripts.size();
			addLocalScripts();
			final int newCount = scripts.size();
			if (oldCount == newCount) {
				guiUtils.centeredMsg("" + newCount + " items loaded. No new scripts detected.", "List Reloaded");
				return;
			}
			sMenu.remove(listMenuPosition);
			sMenu.add(getFullListMenu(), listMenuPosition);
			sMenu.revalidate();
			guiUtils.centeredMsg(""+ (newCount-oldCount) +" new script(s) added to \"Scripts>Full List>\".", "New Script(s) Detected");
		});
		final JMenuItem mi1 = new JMenuItem("From Template...", IconFactory.getMenuIcon(GLYPH.FILE));
		mi1.addActionListener(e -> {

			final HashMap<String, String> map = new HashMap<>();
			map.put("BeanShell", "BSH.bsh");
			map.put("Groovy", "GVY.groovy");
			map.put("Python", "PY.py");
			final String choice = guiUtils.getChoice("Language:", "New SNT Script",
					map.keySet().toArray(new String[0]), "");
			if (choice == null) return; // user pressed cancel

			final ClassLoader classloader = Thread.currentThread().getContextClassLoader();
			boolean save = true;
			try {
				getTextEditor().loadTemplate(
						classloader.getResource("script_templates/Neuroanatomy/Boilerplate/" + map.get(choice)));
			} catch (final NullPointerException ignored) {
				ui.error("Boilerpate script could not be retrieved. Use Script Editor's Templates>Neuroanatomy> instead.");
				save = false;
			} finally {
				getTextEditor().setVisible(true);
				if (save)
					getTextEditor().saveAs(
							getScriptsDir() + File.separator + "_SNT_script." + FileUtils.getExtension(map.get(choice)));
			}
		});
		final JMenuItem mi2 = new JMenuItem("From Clipboard...", IconFactory.getMenuIcon(GLYPH.CLIPBOARD));
		mi2.addActionListener(e -> newScriptFromClipboard());
		final JMenu nMenu = new JMenu("New");
		nMenu.setIcon(IconFactory.getMenuIcon(GLYPH.PLUS));
		nMenu.add(mi1);
		nMenu.add(mi2);
		if (ui != null) {
			final JMenuItem mi3 = new JMenuItem("Record... (Experimental)", IconFactory.getMenuIcon(GLYPH.CIRCLE));
			mi3.addActionListener(e -> {
				if (ui.getRecorder(false) == null) {
					ui.getRecorder(true).setVisible(true);
				} else {
					ui.error("Script Recorder is already open.");
				}
			});
			nMenu.addSeparator();
			nMenu.add(mi3);
		}
		sMenu.add(listMenu);
		sMenu.addSeparator();
		sMenu.add(nMenu);
		sMenu.add(reloadMI);
		sMenu.addSeparator();
		sMenu.add(about());
		return sMenu;
	}

	public void openScript(final String folder, final String name) throws IllegalArgumentException{
		for (final ScriptInfo si : scripts) {
			final String path = si.getPath();
			if (path == null || (folder != null && !path.contains(folder))) continue;
			if (name.equals(getScriptLabel(si, true)) || name.equals(getScriptLabel(si, false))) {
				openScript(si); 
				MenuSelectionManager.defaultManager().clearSelectedPath();
				return;
			}
		}
		throw new IllegalArgumentException("Script not found");
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
				+ "<a href='https://imagej.net/plugins/snt/scripting'>enhance SNT functionality</a>. " //
				+ "The list is automatically populated at startup.<br><br>" //
				+ "To have your own scripts listed here, save them in the <tt>scripts</tt> " //
				+ "directory while including <i>SNT</i> in the filename (e.g., <tt>" //
				+ getScriptsDirPath() + File.separator + "My_SNT_script.py</tt>) <br><br>" //
				+ "To edit a listed script hold \"Shift\" while clicking on its menu entry.<br><br>" //
				+ "Many other programming examples are available through the Script Editor's " //
				+ "<i>Templates> Neuroanatomy></i> menu.<br>Please submit a pull request to " //
				+ "<a href='https://github.com/morphonets/SNT/'>SNT's repository</a> if " //
				+ "you would like to have your scripts distributed with SNT.",
				"About SNT Scripts...", true);
		});
		return mItem;
	}

	private void newScriptFromClipboard() {
		try {
			final String data = (String) java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
					.getData(java.awt.datatransfer.DataFlavor.stringFlavor);
			final String ext = guessScriptingLanguage(data);
			getTextEditor().newTab(data, ext);
			getTextEditor().setVisible(true);
			if (ext.isEmpty())
				new GuiUtils(getTextEditor()).centeredMsg(
						"Unable to detect scripting language. Please define it using the Language> menu.",
						"Unknown Language");
		} catch (final UnsupportedFlavorException | HeadlessException | IOException ignored) {
			guiUtils.error("No script could be extracted from the clipboard.");
		}
	}

	private static String guessScriptingLanguage(final String text) {
		boolean javaKeyword = false;
		boolean javaComment = false;
		boolean groovyKeyword = false;
		boolean pythonComment = false;
		boolean pythonKeyword = false;
		try (final Scanner scanner = new Scanner(text)) {
			while (scanner.hasNextLine()) {
				final String l = scanner.nextLine().trim();
				if (l.startsWith("#@"))
					continue; // script parameter
				javaComment |= l.startsWith("//");
				javaKeyword |= l.contains("new ");
				groovyKeyword |= (!l.endsWith(";") || javaKeyword || l.startsWith("def "));
				pythonComment |= l.startsWith("#");
				pythonKeyword |= l.startsWith("from ");
				if (groovyKeyword && (javaKeyword || javaComment))
					return ".groovy";
				if (pythonKeyword && pythonComment)
					return ".py";
				if (javaKeyword && javaComment)
					return ".bsh";
			}
		}
		return "";
	}

	private void setOpenInsteadOfRun(final boolean b) {
		openInsteadOfRun = b;
		final MenuElement[] selectedMenuPath = MenuSelectionManager.defaultManager().getSelectedPath();
		if (selectedMenuPath.length == 0)
			return;
		final MenuElement lastElem = selectedMenuPath[selectedMenuPath.length - 1];
		if (lastElem instanceof JMenuItem)
			updateMenuItemIcon((JMenuItem) lastElem);
	}

	@Override
	public void menuKeyTyped(final MenuKeyEvent e) {
		// ignored
	}

	@Override
	public void menuKeyPressed(final MenuKeyEvent e) {
		setOpenInsteadOfRun(e.isShiftDown() || e.isAltDown());
	}

	@Override
	public void menuKeyReleased(final MenuKeyEvent e) {
		setOpenInsteadOfRun(e.isShiftDown() || e.isAltDown());
	}

	public static void newScript(final String contents, final String scriptNameWithExtension) {
		if (editor == null)
			editor = new TextEditor(SNTUtils.getContext());
		editor.createNewDocument(scriptNameWithExtension, contents);
		SwingUtilities.invokeLater(() -> editor.setVisible(true));
	}

	public static String getBoilerplateScript(final String extension) {
		final ClassLoader classloader = Thread.currentThread().getContextClassLoader();
		final InputStream is = classloader.getResourceAsStream("script_templates/Neuroanatomy/Boilerplate/"
				+ getBoilerPlateFile(extension));
		return  new BufferedReader(new InputStreamReader(is)).lines().collect(Collectors.joining("\n"));
	}

	public static boolean runScript(final String dir, final String file, final Map<String, Object> inputMap) {
		final ClassLoader classloader = Thread.currentThread().getContextClassLoader();
		final InputStream is = classloader.getResourceAsStream(dir + "/" + file);
		if (is == null)
			throw new IllegalArgumentException("Resource does not exist: '" + dir + "/" + file + "'" );
		final ScriptService sService = SNTUtils.getContext().getService(ScriptService.class);
        return sService.run(file, new InputStreamReader(is), true, inputMap) != null;
	}

	public static boolean runScript(final String scriptPath) {
		final ScriptService sService = SNTUtils.getContext().getService(ScriptService.class);
		for (final ScriptInfo si : sService.getScripts()) {
			if (scriptPath.equals(si.getPath()) || scriptPath.equals(si.getMenuPath())) {
				sService.run(si, true, (Object)null);
				return true;
			}
        }
		return false;
	}

	private static String getBoilerPlateFile(final String extension) {
		final String ext = extension.toLowerCase();
		if (ext.contains("bsh"))
			return "BSH.bsh";
		if (ext.contains("py"))
			return "PY.py";
		return "GVY.groovy";
	}
}
