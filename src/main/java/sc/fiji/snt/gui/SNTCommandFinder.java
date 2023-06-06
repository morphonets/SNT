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

package sc.fiji.snt.gui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.InputEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import javax.swing.AbstractAction;
import javax.swing.AbstractButton;
import javax.swing.Action;
import javax.swing.ActionMap;
import javax.swing.InputMap;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JRootPane;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.MenuElement;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;

import org.scijava.util.PlatformUtils;

import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.icons.FlatSearchIcon;
import com.formdev.flatlaf.ui.FlatButtonUI;

import sc.fiji.snt.SNTUI;
import sc.fiji.snt.viewer.Viewer3D;

/**
 * Implements SNT's Command Palette. This is the same code that is used by the
 * Script Editor. In the future, this code will likely move to a common library
 * to avoid this kind of duplication
 */
public class SNTCommandFinder {

	private static final String NAME = "Command Palette...";

	/** Settings. Ought to become adjustable some day */
	private static final KeyStroke ACCELERATOR = KeyStroke.getKeyStroke(KeyEvent.VK_P,
			java.awt.Toolkit.getDefaultToolkit().getMenuShortcutKeyMask() | KeyEvent.SHIFT_DOWN_MASK);
	private static final int TABLE_ROWS = 6; // no. of commands to be displayed
	private static final float OPACITY = 1f; // 0-1 range
	private static final boolean IGNORE_WHITESPACE = true; // Ignore white spaces while matching?
	private static final boolean INCLUDE_REBUILD_ACTION = false; // Add entry to re-scrape commands?
	private static final Font REF_FONT = refFont();

	private static Palette frame;
	private ScriptRecorder recorder;
	private SearchField searchField;
	private CmdTable table;
	private boolean scriptCall;
	private boolean recordFromPalette;
	private final CmdAction noHitsCmd;
	private final CmdScrapper cmdScrapper;
	private final List<String> keyWordsToIgnoreInMenuPaths; // menus to be ignored
	private final int maxPath; // No. of submenus to be included in path description
	private final String widestCmd; // String defining the width of first column of the palette list
	private final GuiUtils guiUtils;

	public SNTCommandFinder(final SNTUI sntui) {
		noHitsCmd = new SearchWebCmd();
		cmdScrapper = new CmdScrapper(sntui);
		maxPath = 2;
		keyWordsToIgnoreInMenuPaths = Arrays.asList("Full List", "Batch Scripts"); // alias menus listing cmds elsewhere
		widestCmd = "Get Branch Points in Brain Compartment ";
		guiUtils = new GuiUtils(sntui);
	}

	public SNTCommandFinder(final Viewer3D viewer3D) {
		noHitsCmd = new SearchWebCmd();
		cmdScrapper = new CmdScrapper(viewer3D); // recViewer commands are all registered in cmdScrapper.otherMap
		maxPath = 1;
		keyWordsToIgnoreInMenuPaths = Collections.singletonList("Select"); // "None, All, Trees, etc. ": hard to interpreter without
																// context
		widestCmd = "Bounding Boxes of Visible Meshes ";
		guiUtils = new GuiUtils(viewer3D.getFrame());
	}

	void install(final JMenu toolsMenu) {
		final Action action = new AbstractAction(NAME) {

			private static final long serialVersionUID = -7030359886427866104L;

			@Override
			public void actionPerformed(final ActionEvent e) {
				toggleVisibility();
			}

		};
		action.putValue(Action.ACCELERATOR_KEY, ACCELERATOR);
		toolsMenu.add(new JMenuItem(action));
	}

	Map<String, String> getShortcuts() {
		if (!cmdScrapper.scrapeSuccessful())
			cmdScrapper.scrape();
		final TreeMap<String, String> result = new TreeMap<>();
		cmdScrapper.getCmdMap().forEach((id, cmdAction) -> {
			if (cmdAction.hotkey != null && !cmdAction.hotkey.isEmpty())
				result.put(id, cmdAction.hotkey);

		});
		return result;
	}

	static Font refFont() {
		try {
			return UIManager.getFont("TextField.font");
		} catch (final Exception ignored) {
			return new JTextField().getFont();
		}
	}

	private Action getAction() {
		final Action action = new AbstractAction(NAME) {

			private static final long serialVersionUID = -7030359886427866104L;

			@Override
			public void actionPerformed(final ActionEvent e) {
				toggleVisibility();
			}

		};
		action.putValue(Action.ACCELERATOR_KEY, ACCELERATOR);
		return action;
	}

	public void dispose() {
		if (frame != null)
			frame.dispose();
		frame = null;
	}

	private void hideWindow() {
		if (frame != null)
			frame.setVisible(false);
	}

	private void assemblePalette() {
		frame = new Palette();
		frame.setLayout(new BorderLayout());
		searchField = new SearchField(true);
		frame.add(searchField, BorderLayout.NORTH);
		searchField.getDocument().addDocumentListener(new PromptDocumentListener());
		final InternalKeyListener keyListener = new InternalKeyListener();
		searchField.addKeyListener(keyListener);
		table = new CmdTable();
		table.addKeyListener(keyListener);
		table.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseClicked(final MouseEvent e) {
				if (e.getClickCount() == 2 && table.getSelectedRow() > -1) {
					runCmd(table.getInternalModel().getCommand(table.getSelectedRow()));
				}
			}
		});
		populateList("");
		frame.add(table.getScrollPane());
		frame.pack();
	}

	private String[] makeRow(final CmdAction ca) {
		return new String[] { ca.id, ca.description() };
	}

	private void populateList(final String matchingSubstring) {
		final ArrayList<String[]> list = new ArrayList<>();
		if (!cmdScrapper.scrapeSuccessful())
			cmdScrapper.scrape();
		cmdScrapper.getCmdMap().forEach((id, cmd) -> {
			if (cmd.matches(matchingSubstring)) {
				list.add(makeRow(cmd));
			}
		});
		if (list.isEmpty()) {
			list.add(makeRow(noHitsCmd));
		}
		table.getInternalModel().setData(list);
		if (searchField != null)
			searchField.requestFocus();
	}

	private void runCmd(final String command) {
		SwingUtilities.invokeLater(() -> {
			if (CmdScrapper.REBUILD_ID.equals(command)) {
				resetScrapper();
				frame.setVisible(true);
				return;
			}
			CmdAction cmd;
			if (noHitsCmd != null && command.equals(noHitsCmd.id)) {
				cmd = noHitsCmd;
			} else {
				cmd = cmdScrapper.getCmdMap().get(command);
			}
			runCmd(cmd);
		});
	}

	private void runCmd(final CmdAction cmd) {
		if (cmd != null) {
			final boolean hasButton = cmd.button != null;
			if (hasButton && (!cmd.button.isEnabled()
					|| (cmd.button instanceof JMenuItem && !cmd.button.getParent().isEnabled()))) {
				guiUtils.error("Command is currently disabled. Either execution requirements "
						+ "are unmet or it is not supported in current state.");
				frame.setVisible(true);
				return;
			}
			if (!scriptCall)
				hideWindow(); // hide before running, in case command opens a dialog
			if (hasButton) {
				cmd.button.doClick();
			} else if (cmd.action != null) {
				cmd.action.actionPerformed(new ActionEvent(this, ActionEvent.ACTION_PERFORMED, cmd.id));
			}
		}
	}

	private void recordCommand(final CmdAction cmdAction) {
		if (recorder == null)
			return;
		if (cmdAction.id.startsWith("<HTML>") || cmdAction.description().startsWith("Scripts")) {
			recordComment("Command is not recordable... [id: "+ cmdAction.id +"]");
			return;
		}
		if (cmdScrapper.sntui != null)
			recordCmdSNTUI(cmdAction);
		else
			recordSNTViewerCmd(cmdAction);
	}

	private void recordCmdSNTUI(final CmdAction cmdAction) {
		final StringBuilder sb = new StringBuilder();
		final boolean pmCmd = cmdAction.pathDescription().startsWith("PM") || cmdAction.pathDescription().startsWith("Tag")
				|| cmdAction.pathDescription().startsWith("Edit") || cmdAction.pathDescription().startsWith("Refine")
				|| cmdAction.pathDescription().startsWith("Fill") || cmdAction.pathDescription().startsWith("Analyze");
		final boolean promptCmd = cmdAction.id.endsWith("...");
		final boolean tagCmd = pmCmd && !promptCmd && cmdAction.pathDescription().contains("Tag") && !cmdAction.pathDescription().contains("Type");
		if (pmCmd) {
			sb.append("snt.getUI().getPathManager().");
			if (tagCmd)
				sb.append("applyDefaultTags(\"").append(cmdAction.id).append("\")");
			else if (promptCmd)
				sb.append("runCommand(\"").append(cmdAction.id).append("\", \"[(optional) prompt options here...]\")");
			else
				sb.append("runCommand(\"").append(cmdAction.id).append("\")");
		} else {
			sb.append("snt.getUI().runCommand(\"").append(cmdAction.id).append("\")");
		}
		if (promptCmd && !pmCmd)
			noOptionsRecorderComment();
		recorder.recordCmd(sb.toString());
	}

	private void noOptionsRecorderComment() {
		recorder.recordComment("NB: Next line may rely on non-recorded prompt options");
	}

	private void recordSNTViewerCmd(final CmdAction cmdAction) {
		if (cmdAction.id.endsWith("..."))
			noOptionsRecorderComment();
		recorder.recordCmd("viewer.runCommand(\"" + cmdAction.id +"\")");
	}

	public void setRecorder(final ScriptRecorder recorder) {
		this.recorder = recorder;
		if (!cmdScrapper.scrapeSuccessful())
			cmdScrapper.scrape();
		if (recorder == null) {
			cmdScrapper.removeRecordActions();
		} else {
			cmdScrapper.appendRecordActions();
		}
	}

	private void resetScrapper() {
		cmdScrapper.scrape();
		table.clearSelection();
		searchField.setText("");
		searchField.requestFocus();
	}

	public void runCommand(final String actionIdentifier) {
		scriptCall = true;
		final CmdAction cmdAction = cmdScrapper.getCmdAction(actionIdentifier);
		if (cmdAction == null)
			throw new IllegalArgumentException("Unrecognized command: '" + actionIdentifier + "'");
		SwingUtilities.invokeLater(() -> {
			runCmd(cmdAction);
		});
		scriptCall = false;
	}

	private void recordComment(final String string) {
		if (recorder == null)
			System.out.println(">> " + string);
		else
			recorder.recordComment(string);
	}

	public void toggleVisibility() {
		if (frame == null || table == null) {
			assemblePalette();
		}
		if (frame.isVisible()) {
			hideWindow();
		} else {
			frame.center();
			table.clearSelection();
			frame.setVisible(true);
			searchField.requestFocus();
		}
	}

	public void attach(final JDialog dialog) {
		final int condition = JPanel.WHEN_IN_FOCUSED_WINDOW;
		final InputMap inputMap = ((JPanel) dialog.getContentPane()).getInputMap(condition);
		final ActionMap actionMap = ((JPanel) dialog.getContentPane()).getActionMap();
		inputMap.put(ACCELERATOR, NAME);
		actionMap.put(NAME, getAction());
	}

	public JButton getButton() {
		final JButton button = new JButton(getAction());
		button.setText(null);
		button.setIcon(IconFactory.getButtonIcon(IconFactory.GLYPH.SEARCH));
		button.getInputMap().put(ACCELERATOR, NAME);
		button.setToolTipText(NAME + "  " + GuiUtils.ctrlKey() + "+Shift+P");
		return button;
	}

	public AbstractButton getMenuItem(final JMenuBar menubar, final boolean asButton) {
		final AbstractButton jmi = (asButton) ? GuiUtils.menubarButton(IconFactory.GLYPH.SEARCH, getAction())
				: new JMenuItem(getAction());
		if (asButton) {
			jmi.setToolTipText(NAME + "  " + GuiUtils.ctrlKey() + "+Shift+P");
		} else {
			jmi.setIcon(IconFactory.getMenuIcon(IconFactory.GLYPH.SEARCH));
		}
		return jmi;
	}

	public void register(final AbstractButton button, final String... description) {
		register(button, new ArrayList<>(Arrays.asList(description)));
	}

	public void register(final AbstractButton button, final List<String> pathDescription) {
		cmdScrapper.registerOther(button, pathDescription);
	}

	public void setVisible(final boolean b) {
		if (!b) {
			hideWindow();
			return;
		}
		toggleVisibility();
	}

	private void initRecorder() {
		if (recorder == null) {
			if (cmdScrapper.sntui == null)
				recorder = cmdScrapper.viewer3D.getRecorder(true);
			else
				recorder = cmdScrapper.sntui.getRecorder(true);
		}
	}

	private static class RecordIcon extends FlatSearchIcon {
		private Area area;

		@Override
		protected void paintIcon(final Component c, final Graphics2D g) {
			g.setColor(FlatButtonUI.buttonStateColor(c, searchIconColor, searchIconColor, null, searchIconHoverColor,
					searchIconPressedColor));
			if (area == null) {
				area = new Area(new Ellipse2D.Float(2.5f, 2.5f, 12, 12));
				//area.subtract(new Area(new Ellipse2D.Float(7f, 7f, 3, 3)));
			}
			g.fill(area);
		}
	}

	private class SearchField extends GuiUtils.TextFieldWithPlaceholder {
		private static final long serialVersionUID = 1L;
		private static final int PADDING = 4;

		SearchField(final boolean enableRecordButton) {
			super("    Search for commands and actions (e.g., Sholl)");
			setMargin(new Insets(PADDING, PADDING, PADDING, PADDING));
			setFont(REF_FONT.deriveFont(REF_FONT.getSize() * 1.2f));
			putClientProperty( FlatClientProperties.TEXT_FIELD_LEADING_COMPONENT, new JLabel( new FlatSearchIcon( false ) ) );
			if (enableRecordButton)
				putClientProperty( FlatClientProperties.TEXT_FIELD_TRAILING_COMPONENT, recordButton() );
		}

		JToggleButton recordButton( ) {
			final JToggleButton recButton = new JToggleButton("REC ", new RecordIcon());
			recButton.setFont(recButton.getFont().deriveFont((float) (recButton.getFont().getSize() * .65)));
			recButton.setIconTextGap((int) (recButton.getIconTextGap() * .5));
			recButton.setToolTipText("Record executed actions to Script Recorder?");
			recButton.addItemListener(e -> {
				recordFromPalette = recButton.isSelected();
				if (recordFromPalette) {
					initRecorder();
					recorder.setVisible(true);
					frame.setVisible(true);
				}
			});
			return recButton;
		}

		@Override
		Font getPlaceholderFont() {
			return getFont().deriveFont(Font.ITALIC);
		}

	}

	private class PromptDocumentListener implements DocumentListener {
		public void insertUpdate(final DocumentEvent e) {
			populateList(getQueryFromSearchField());
		}

		public void removeUpdate(final DocumentEvent e) {
			populateList(getQueryFromSearchField());
		}

		public void changedUpdate(final DocumentEvent e) {
			populateList(getQueryFromSearchField());
		}

		String getQueryFromSearchField() {
			final String text = searchField.getText();
			if (text == null)
				return "";
			final String query = text.toLowerCase();
			return (IGNORE_WHITESPACE) ? query.replaceAll("\\s+", "") : query;
		}
	}

	private class InternalKeyListener extends KeyAdapter {

		@Override
		public void keyPressed(final KeyEvent ke) {
			final int key = ke.getKeyCode();
			final int flags = ke.getModifiersEx();
			final int items = table.getInternalModel().getRowCount();
			final Object source = ke.getSource();
			final boolean meta = ((flags & KeyEvent.META_DOWN_MASK) != 0) || ((flags & KeyEvent.CTRL_DOWN_MASK) != 0);
			if (key == KeyEvent.VK_ESCAPE || (key == KeyEvent.VK_W && meta) || (key == KeyEvent.VK_P && meta)) {
				hideWindow();
			} else if (source == searchField) {
				/*
				 * If you hit enter in the text field, and there's only one command that
				 * matches, run that:
				 */
				if (key == KeyEvent.VK_ENTER) {
					if (1 == items)
						runCmd(table.getInternalModel().getCommand(0));
				}
				/*
				 * If you hit the up or down arrows in the text field, move the focus to the
				 * table and select the row at the bottom or top.
				 */
				int index = -1;
				if (key == KeyEvent.VK_UP) {
					index = table.getSelectedRow() - 1;
					if (index < 0)
						index = items - 1;
				} else if (key == KeyEvent.VK_DOWN) {
					index = table.getSelectedRow() + 1;
					if (index >= items)
						index = Math.min(items - 1, 0);
				}
				if (index >= 0) {
					table.requestFocus();
					// completions.ensureIndexIsVisible(index);
					table.setRowSelectionInterval(index, index);
				}
			} else if (key == KeyEvent.VK_BACK_SPACE || key == KeyEvent.VK_DELETE) {
				/*
				 * If someone presses backspace or delete they probably want to remove the last
				 * letter from the search string, so switch the focus back to the prompt:
				 */
				searchField.requestFocus();
			} else if (source == table) {
				/* If you hit enter with the focus in the table, run the selected command */
				if (key == KeyEvent.VK_ENTER) {
					ke.consume();
					final int row = table.getSelectedRow();
					if (row >= 0)
						runCmd(table.getInternalModel().getCommand(row));
					/* Loop through the list using the arrow keys */
				} else if (key == KeyEvent.VK_UP) {
					if (table.getSelectedRow() == 0)
						table.setRowSelectionInterval(table.getRowCount() - 1, table.getRowCount() - 1);
				} else if (key == KeyEvent.VK_DOWN) {
					if (table.getSelectedRow() == table.getRowCount() - 1)
						table.setRowSelectionInterval(0, 0);
				}
			}
		}
	}

	private class Palette extends JFrame {
		private static final long serialVersionUID = 1L;

		Palette() {
			super("Command Palette");
			setUndecorated(true);
			setAlwaysOnTop(true);
			setOpacity(OPACITY);
			getRootPane().setWindowDecorationStyle(JRootPane.NONE);
			// it should NOT be possible to minimize this frame, but just to
			// be safe, we'll ensure the frame is never in an awkward state
			addWindowListener(new WindowAdapter() {
				@Override
				public void windowClosing(final WindowEvent e) {
					hideWindow();
				}

				@Override
				public void windowIconified(final WindowEvent e) {
					hideWindow();
				}

				@Override
				public void windowDeactivated(final WindowEvent e) {
					hideWindow();
				}
			});
		}

		void center() {
			center(javax.swing.FocusManager.getCurrentManager().getActiveWindow());
		}

		void center(final Container component) {
			if (component == null)
				return;
			final Rectangle bounds = component.getBounds();
			final Dimension w = getSize();
			int x = bounds.x + (bounds.width - w.width) / 2;
			int y = bounds.y + (bounds.height - w.height) / 2;
			if (x < 0)
				x = 0;
			if (y < 0)
				y = 0;
			setLocation(x, y);
		}
	}

	private class CmdTable extends JTable {
		private static final long serialVersionUID = 1L;

		CmdTable() {
			super(new CmdTableModel());
			setAutoCreateRowSorter(false);
			setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
			setShowGrid(false);
			setRowSelectionAllowed(true);
			setColumnSelectionAllowed(false);
			setTableHeader(null);
			setAutoResizeMode(AUTO_RESIZE_LAST_COLUMN);
			final CmdTableRenderer renderer = new CmdTableRenderer();
			final int col0Width = renderer.maxWidh(0);
			final int col1Width = renderer.maxWidh(1);
			setDefaultRenderer(Object.class, renderer);
			getColumnModel().getColumn(0).setMaxWidth(col0Width);
			getColumnModel().getColumn(1).setMaxWidth(col1Width);
			setRowHeight(renderer.rowHeight());
			int height = TABLE_ROWS * getRowHeight();
			if (getRowMargin() > 0)
				height *= getRowMargin();
			setPreferredScrollableViewportSize(new Dimension(col0Width + col1Width, height));
			setFillsViewportHeight(true);
		}

		private JScrollPane getScrollPane() {
			final JScrollPane scrollPane = new JScrollPane(this, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
					JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
			scrollPane.setWheelScrollingEnabled(true);
			return scrollPane;
		}

		CmdTableModel getInternalModel() {
			return (CmdTableModel) getModel();
		}

	}

	private class CmdTableRenderer extends DefaultTableCellRenderer {

		private static final long serialVersionUID = 1L;
		final Font col0Font = REF_FONT.deriveFont(REF_FONT.getSize() * 1.1f);
		final Font col1Font = REF_FONT.deriveFont(REF_FONT.getSize() * 1f);

		@Override
		public Component getTableCellRendererComponent(final JTable table, final Object value, final boolean isSelected,
				final boolean hasFocus, final int row, final int column) {
			final Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
			if (column == 1) {
				setHorizontalAlignment(JLabel.RIGHT);
				setEnabled(false);
				setFont(col1Font);
			} else {
				setHorizontalAlignment(JLabel.LEFT);
				setEnabled(true);
				setFont(col0Font);
			}
			return c;
		}

		int rowHeight() {
			return (int) (col0Font.getSize() * 1.5f);
		}

		int maxWidh(final int columnIndex) {
			if (columnIndex == 1)
				return SwingUtilities.computeStringWidth(getFontMetrics(col1Font),
						"A really long menu>And long submenu>");
			return SwingUtilities.computeStringWidth(getFontMetrics(col0Font), widestCmd);
		}

	}

	private static class CmdTableModel extends AbstractTableModel {
		private static final long serialVersionUID = 1L;
		private static final int COLUMNS = 2;
		List<String[]> list;

		void setData(final ArrayList<String[]> list) {
			this.list = list;
			fireTableDataChanged();
		}

		String getCommand(final int row) {
			if (list.size() == 1)
				return (String) getValueAt(row, 0);
			else if (row < 0 || row >= list.size())
				return "";
			else
				return (String) getValueAt(row, 0);
		}

		@Override
		public int getColumnCount() {
			return COLUMNS;
		}

		@Override
		public Object getValueAt(final int row, final int column) {
			if (row >= list.size() || column >= COLUMNS)
				return null;
			final String[] strings = (String[]) list.get(row);
			return strings[column];
		}

		@Override
		public int getRowCount() {
			return list.size();
		}

	}

	private class CmdAction {

		final String id;
		List<String> path;
		String pathDescription;
		String hotkey;
		AbstractButton button;
		Action action;

		CmdAction(final String cmdName) {
			this.id = capitalize(cmdName);
			this.path = new ArrayList<>();
			this.hotkey = "";
		}

		CmdAction(final String cmdName, final AbstractButton button) {
			this(cmdName);
			this.button = button;
			if (button.getAction() instanceof AbstractAction)
				action = button.getAction();
		}

		String pathDescription() {
			if (pathDescription == null) {
				final List<String> tail = path.subList(Math.max(path.size() - maxPath, 0), path.size());
				final StringBuilder sb = new StringBuilder();
				final Iterator<String> it = tail.iterator();
				if (it.hasNext()) {
					sb.append(it.next());
					while (it.hasNext()) {
						sb.append('〉').append(it.next());
					}
				}
				pathDescription = sb.toString();
			}
			return pathDescription;
		}

		String description() {
			if (!hotkey.isEmpty())
				return hotkey;
			if (!path.isEmpty())
				return pathDescription();
			return "";
		}

		boolean matches(final String lowercaseQuery) {
			if (IGNORE_WHITESPACE) {
				return id.toLowerCase().replaceAll("\\s+", "").contains(lowercaseQuery)
						|| pathDescription().toLowerCase().contains(lowercaseQuery);
			}
			return id.toLowerCase().contains(lowercaseQuery)
					|| pathDescription().toLowerCase().contains(lowercaseQuery);
		}

		void setkeyString(final KeyStroke key) {
			if (hotkey.isEmpty()) {
				hotkey = prettifiedKey(key);
			} else {
				final String oldHotkey = hotkey;
				final String newHotKey = prettifiedKey(key);
				if (!oldHotkey.contains(newHotKey)) {
					hotkey = oldHotkey + " or " + newHotKey;
				}
			}
		}

		private String capitalize(final String string) {
			return string.substring(0, 1).toUpperCase() + string.substring(1);
		}

		private String prettifiedKey(final KeyStroke key) {
			if (key == null)
				return "";
			final StringBuilder s = new StringBuilder();
			final int m = key.getModifiers();
			if ((m & InputEvent.CTRL_DOWN_MASK) != 0) {
				s.append((PlatformUtils.isMac()) ? "⌃ " : "Ctrl ");
			}
			if ((m & InputEvent.META_DOWN_MASK) != 0) {
				s.append((PlatformUtils.isMac()) ? "⌘ " : "Ctrl ");
			}
			if ((m & InputEvent.ALT_DOWN_MASK) != 0) {
				s.append((PlatformUtils.isMac()) ? "⎇ " : "Alt ");
			}
			if ((m & InputEvent.SHIFT_DOWN_MASK) != 0) {
				s.append("⇧ ");
			}
			if ((m & InputEvent.BUTTON1_DOWN_MASK) != 0) {
				s.append("L-click ");
			}
			if ((m & InputEvent.BUTTON2_DOWN_MASK) != 0) {
				s.append("R-click ");
			}
			if ((m & InputEvent.BUTTON3_DOWN_MASK) != 0) {
				s.append("M-click ");
			}
			switch (key.getKeyEventType()) {
			case KeyEvent.KEY_TYPED:
				s.append(key.getKeyChar()).append(" ");
				break;
			case KeyEvent.KEY_PRESSED:
			case KeyEvent.KEY_RELEASED:
				s.append(getKeyText(key.getKeyCode())).append(" ");
				break;
			default:
				break;
			}
			return s.toString();
		}

		String getKeyText(final int keyCode) {
			if (keyCode >= KeyEvent.VK_0 && keyCode <= KeyEvent.VK_9
					|| keyCode >= KeyEvent.VK_A && keyCode <= KeyEvent.VK_Z) {
				return String.valueOf((char) keyCode);
			}
			switch (keyCode) {
			case KeyEvent.VK_COMMA:
			case KeyEvent.VK_COLON:
				return ",";
			case KeyEvent.VK_PERIOD:
				return ".";
			case KeyEvent.VK_SLASH:
				return "/";
			case KeyEvent.VK_SEMICOLON:
				return ";";
			case KeyEvent.VK_EQUALS:
				return "=";
			case KeyEvent.VK_OPEN_BRACKET:
				return "[";
			case KeyEvent.VK_BACK_SLASH:
				return "\\";
			case KeyEvent.VK_CLOSE_BRACKET:
				return "]";
			case KeyEvent.VK_ENTER:
				return "↵";
			case KeyEvent.VK_BACK_SPACE:
				return "⌫";
			case KeyEvent.VK_TAB:
				return "↹";
			case KeyEvent.VK_CANCEL:
				return "Cancel";
			case KeyEvent.VK_CLEAR:
				return "Clear";
			case KeyEvent.VK_PAUSE:
				return "Pause";
			case KeyEvent.VK_CAPS_LOCK:
				return "Caps Lock";
			case KeyEvent.VK_ESCAPE:
				return "Esc";
			case KeyEvent.VK_SPACE:
				return "Space";
			case KeyEvent.VK_PAGE_UP:
				return "⇞";
			case KeyEvent.VK_PAGE_DOWN:
				return "⇟";
			case KeyEvent.VK_END:
				return "END";
			case KeyEvent.VK_HOME:
				return "Home"; // "⌂";
			case KeyEvent.VK_LEFT:
				return "←";
			case KeyEvent.VK_UP:
				return "↑";
			case KeyEvent.VK_RIGHT:
				return "→";
			case KeyEvent.VK_DOWN:
				return "↓";
			case KeyEvent.VK_MULTIPLY:
				return "[Num ×]";
			case KeyEvent.VK_ADD:
				return "[Num +]";
			case KeyEvent.VK_SUBTRACT:
				return "[Num −]";
			case KeyEvent.VK_DIVIDE:
				return "[Num /]";
			case KeyEvent.VK_DELETE:
				return "⌦";
			case KeyEvent.VK_INSERT:
				return "Ins";
			case KeyEvent.VK_BACK_QUOTE:
				return "`";
			case KeyEvent.VK_QUOTE:
				return "'";
			case KeyEvent.VK_AMPERSAND:
				return "&";
			case KeyEvent.VK_ASTERISK:
				return "*";
			case KeyEvent.VK_QUOTEDBL:
				return "\"";
			case KeyEvent.VK_LESS:
				return "<";
			case KeyEvent.VK_GREATER:
				return ">";
			case KeyEvent.VK_BRACELEFT:
				return "{";
			case KeyEvent.VK_BRACERIGHT:
				return "}";
			case KeyEvent.VK_CIRCUMFLEX:
				return "^";
			case KeyEvent.VK_DEAD_TILDE:
				return "~";
			case KeyEvent.VK_DOLLAR:
				return "$";
			case KeyEvent.VK_EXCLAMATION_MARK:
				return "!";
			case KeyEvent.VK_LEFT_PARENTHESIS:
				return "(";
			case KeyEvent.VK_MINUS:
				return "-";
			case KeyEvent.VK_PLUS:
				return "+";
			case KeyEvent.VK_RIGHT_PARENTHESIS:
				return ")";
			case KeyEvent.VK_UNDERSCORE:
				return "_";
			default:
				return KeyEvent.getKeyText(keyCode);
			}
		}
	}

	private class CmdScrapper {
		static final String REBUILD_ID = "Rebuild Actions Index";
		private final TreeMap<String, CmdAction> cmdMap;
		private TreeMap<String, CmdAction> otherMap;
		private final SNTUI sntui;
		private final Viewer3D viewer3D;

		CmdScrapper(final Viewer3D viewer3D) {
			cmdMap = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
			this.sntui = null;
			this.viewer3D = viewer3D;
		}

		public CmdScrapper(final SNTUI sntui) {
			cmdMap = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
			this.sntui = sntui;
			this.viewer3D = null;
		}

		List<AnnotatedComponent> getComponents() {
			final List<AnnotatedComponent> components = new ArrayList<>();
			if (sntui != null) {
				components.add(new AnnotatedComponent(sntui.getTracingCanvasPopupMenu(), "Image Contextual Menu"));
				components.add(new AnnotatedComponent(sntui.getJMenuBar()));
				components.add(new AnnotatedComponent(sntui.getPathManager().getJTree().getComponentPopupMenu(),
						"PM Contextual Menu")); // before PM's menu bar
				components.add(new AnnotatedComponent(sntui.getPathManager().getJMenuBar()));
			}
			return components; // recViewer commands are all registered in otherMap
		}

		TreeMap<String, CmdAction> getCmdMap() {
			if (otherMap != null)
				cmdMap.putAll(otherMap);
			return cmdMap;
		}

		CmdAction getCmdAction(final String identifier) {
			CmdAction cmdAction = cmdMap.get(identifier);
			if (cmdAction == null)
				cmdAction = otherMap.get(identifier);
			return cmdAction;
		}

		boolean scrapeSuccessful() {
			return !cmdMap.isEmpty();
		}

		void scrape() {
			cmdMap.clear();
			if (INCLUDE_REBUILD_ACTION)
				cmdMap.put(REBUILD_ID, new CmdAction(REBUILD_ID));
			for (final AnnotatedComponent ac : getComponents()) {
				if (ac == null)
					continue;
				if (ac.component instanceof JMenuBar) {
					final JMenuBar menuBar = (JMenuBar) ac.component;
					final int topLevelMenus = menuBar.getMenuCount();
					for (int i = 0; i < topLevelMenus; ++i) {
						final JMenu topLevelMenu = menuBar.getMenu(i);
						if (topLevelMenu != null && topLevelMenu.getText() != null) {
							parseMenu(topLevelMenu, new ArrayList<>(Collections.singletonList(topLevelMenu.getText())));
						}
					}
				}
				if (ac.component instanceof JPopupMenu) {
					final JPopupMenu popup = (JPopupMenu) ac.component;
					if (popup != null) {
						getMenuItems(popup).forEach(mi -> {
							registerMenuItem(mi, new ArrayList<>(Collections.singletonList(ac.annotation)));
						});
					}
				}
			}

		}

		private void parseMenu(final JMenu menu, final List<String> path) {
			if (keyWordsToIgnoreInMenuPaths != null) {
				for (final String ignored : keyWordsToIgnoreInMenuPaths) {
					if (path.contains(ignored)) {
						return;
					}
				}
			}
			final int n = menu.getItemCount();
			for (int i = 0; i < n; ++i) {
				registerMenuItem(menu.getItem(i), path);
			}
		}

		private void registerMenuItem(final JMenuItem m, final List<String> path) {
			if (m != null) {
				String label = m.getActionCommand();
				if (label == null)
					label = m.getText();
				if (m instanceof JMenu) {
					final JMenu subMenu = (JMenu) m;
					final String hostDesc = subMenu.getText();
					final List<String> newPath = new ArrayList<>(path);
					if (hostDesc != null)
						newPath.add(hostDesc);
					parseMenu(subMenu, newPath);
				} else {
					registerMain(m, path);
				}
			}
		}

		private List<JMenuItem> getMenuItems(final JPopupMenu popupMenu) {
			final List<JMenuItem> list = new ArrayList<>();
			for (final MenuElement me : popupMenu.getSubElements()) {
				if (me == null) {
					continue;
				} else if (me instanceof JMenuItem) {
					list.add((JMenuItem) me);
				} else if (me instanceof JMenu) {
					getMenuItems((JMenu) me, list);
				}
			}
			return list;
		}

		private void getMenuItems(final JMenu menu, final List<JMenuItem> holdingList) {
			for (int j = 0; j < menu.getItemCount(); j++) {
				final JMenuItem jmi = menu.getItem(j);
				if (jmi == null)
					continue;
				if (jmi instanceof JMenu) {
					getMenuItems((JMenu) jmi, holdingList);
				} else {
					holdingList.add(jmi);
				}
			}
		}

		private boolean irrelevantCommand(final String label) {
			// commands that don't sort well and would only add clutter to the palette
			return label == null || label.startsWith("<HTML>Help");
		}

		private void removeRecordActions(TreeMap<String, CmdAction> map) {
			if (map == null)
				return;
			map.values().forEach(cmdAction -> {
				if (cmdAction.button != null) {
					for (ActionListener listener : cmdAction.button.getActionListeners()) {
						if (listener instanceof RecordAction) {
							cmdAction.button.removeActionListener(listener);
						}
					}
				}
			});
		}
		private void appendRecordActions(TreeMap<String, CmdAction> map) {
			if (map == null)
				return;
			map.values().forEach(cmdAction -> {
				if (cmdAction.button != null)
					cmdAction.button.addActionListener(new RecordAction(cmdAction));
			});
		}
		void removeRecordActions() {
			removeRecordActions(cmdMap);
			removeRecordActions(otherMap);
		}

		void appendRecordActions() {
			appendRecordActions(cmdMap);
			appendRecordActions(otherMap);
		}

		void registerMain(final AbstractButton button, final List<String> path) {
			register(cmdMap, button, path);
		}

		void registerOther(final AbstractButton button, final List<String> path) {
			if (otherMap == null)
				otherMap = new TreeMap<>();
			register(otherMap, button, path);
		}

		private void register(final TreeMap<String, CmdAction> map, final AbstractButton button,
				final List<String> path) {
			String label = button.getActionCommand();
			if (NAME.equals(label))
				return; // do not register command palette
			if (label == null)
				label = button.getText();
			if (irrelevantCommand(label))
				return;
			// handle special cases and trim whitespace as some contextual menu items are indented
			if (label.equals("Edit %s") || label.startsWith("Edit Path "))
				label = "Edit Selected Path";
			else 
				label = label.trim();
			// If a command has already been registered, we'll include its accelerator
			final boolean isMenuItem = button instanceof JMenuItem;
			final CmdAction registeredAction = (CmdAction) map.get(label);
			final KeyStroke accelerator = (isMenuItem) ? ((JMenuItem) button).getAccelerator() : null;
			if (registeredAction != null && accelerator != null) {
				registeredAction.setkeyString(accelerator);
			} else {
				final CmdAction ca = new CmdAction(label, button);
				ca.path = path;
				if (accelerator != null)
					ca.setkeyString(accelerator);
				map.put(ca.id, ca);
			}
		}

	}

	private static class AnnotatedComponent {
		final Component component;
		final String annotation;

		AnnotatedComponent(final Component component, final String annotation) {
			this.component = component;
			this.annotation = annotation;
		}

		AnnotatedComponent(final Component component) {
			this.component = component;
			this.annotation = "";
		}
	}

	private class RecordAction extends AbstractAction {
		private static final long serialVersionUID = 6898683194911491963L;
		static final String ID = "SNT_REC_ACTION";
		private final transient CmdAction cmdAction;

		public RecordAction(final CmdAction cmdAction) {
			super(ID);
			this.cmdAction = cmdAction;
		}

		@Override
		public void actionPerformed(final ActionEvent e) {
			if (recorder != null && !scriptCall && recordFromPalette)
				recordCommand(cmdAction);
		}
	}

	private class SearchWebCmd extends CmdAction {
		SearchWebCmd() {
			super("Search forum.image.sc");
			button = new JMenuItem(new AbstractAction(id) {
				private static final long serialVersionUID = 1L;

				@Override
				public void actionPerformed(final ActionEvent e) {
					guiUtils.searchForum(searchField.getText());
				}
			});
		}

		@Override
		String description() {
			return "|Unmatched action|";
		}
	}

}
