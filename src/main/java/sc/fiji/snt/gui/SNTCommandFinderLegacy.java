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
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Hashtable;

import javax.swing.AbstractAction;
import javax.swing.AbstractButton;
import javax.swing.Action;
import javax.swing.ActionMap;
import javax.swing.BorderFactory;
import javax.swing.InputMap;
import javax.swing.JButton;
import javax.swing.JCheckBoxMenuItem;
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
import javax.swing.KeyStroke;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableColumnModel;

import org.scijava.ui.awt.AWTWindows;
import org.scijava.util.PlatformUtils;

import sc.fiji.snt.SNTUI;
import sc.fiji.snt.gui.IconFactory.GLYPH;
import sc.fiji.snt.viewer.Viewer3D;

/**
 * @deprecated see {@link SNTCommandFinder}
 *
 */
@Deprecated 
public class SNTCommandFinderLegacy {

	private static final String NAME = "Command Palette...";
	private static final KeyStroke ACCELERATOR= KeyStroke.getKeyStroke(KeyEvent.VK_P,
			java.awt.Toolkit.getDefaultToolkit().getMenuShortcutKeyMask() | KeyEvent.SHIFT_MASK);
	private static final int TABLE_ROWS = 6;
	private static int TABLE_WIDTH;
	static {
		TABLE_WIDTH = 2 * GuiUtils.renderedWidth("Append Direct Children To Selection  "); // example of a large cmd string
	}
	private static JFrame frame;
	private final SNTUI sntui;
	private final Viewer3D recViewer;


	private Hashtable<String, CommandAction> defaultCmdsHash;
	private Hashtable<String, CommandAction> otherCmdsHash;
	private JTextField searchField;
	private String[] commands;
	private JTable table;
	private TableModel tableModel;
	private boolean recordCmd;

	public SNTCommandFinderLegacy(final SNTUI sntui) {
		this.sntui = sntui;
		this.recViewer = null;
	}

	public SNTCommandFinderLegacy(final Viewer3D recViewer) {
		this.sntui = null;
		this.recViewer = recViewer;
	}

	public void dispose() {
		if (frame != null)
			frame.dispose();
		frame = null;
	}

	public void register(final AbstractButton button, final String descriptionOfPathToButton) {
		register(button, null, descriptionOfPathToButton);
	}

	public void register(final AbstractButton button, final String descriptionOfComponentHostingButton,
			final String descriptionOfPathToButton) {
		if (otherCmdsHash == null)
			otherCmdsHash = new Hashtable<String, CommandAction>();
		register(otherCmdsHash, button, descriptionOfComponentHostingButton, descriptionOfPathToButton);
	}

	private void findAllMenuItemsInSNTUI() {
		parsePopupMenu(" Image", sntui.getTracingCanvasPopupMenu());
		parseMenuBar("", sntui.getJMenuBar());
		parsePopupMenu(" PM", sntui.getPathManager().getJTree().getComponentPopupMenu()); // before PM's menu bar
		parseMenuBar("", sntui.getPathManager().getJMenuBar());
	}

	private void parsePopupMenu(final String hostingComponent, final JPopupMenu popup) {
		if (popup != null) {
			GuiUtils.getMenuItems(popup).forEach(mi -> {
				registerMenuItem(mi, hostingComponent, "context menu");
			});
		}
	}

	private void parseMenuBar(final String componentHostingMenu, final JMenuBar menuBar) {
		final int topLevelMenus = menuBar.getMenuCount();
		for (int i = 0; i < topLevelMenus; ++i) {
			final JMenu topLevelMenu = menuBar.getMenu(i);
			if (topLevelMenu != null && topLevelMenu.getText() != null)
				parseMenu(componentHostingMenu, topLevelMenu.getText(), topLevelMenu);
		}
	}

	private void parseMenu(final String componentHostingMenu, final String path, final JMenu menu) {
		final int n = menu.getItemCount();
		for (int i = 0; i < n; ++i) {
			registerMenuItem(menu.getItem(i), componentHostingMenu, path);
		}
	}

	private void registerMenuItem(final JMenuItem m, final String hostingComponent, final String path) {
		if (m != null) {
			String label = m.getActionCommand();
			if (label == null)
				label = m.getText();
			if (m instanceof JMenu) {
				final JMenu subMenu = (JMenu) m;
				parseMenu(hostingComponent, path + "> " + label, subMenu);
			} else {
				register(defaultCmdsHash, m, hostingComponent, path );
			}
		}
	}

	private void register(final Hashtable<String, CommandAction> storageHTable, final AbstractButton button,
			final String descriptionOfComponentHostingButton, final String descriptionOfPath) {
		String label = button.getActionCommand();
		if (NAME.equals(label)) return; // do not register command palette
		if (label == null)
			label = button.getText();
		final String trimmedLabel = label.trim();
		if (trimmedLabel.length() == 0 || trimmedLabel.equals("-"))
			return;
		final CommandAction ca = (CommandAction) storageHTable.get(label);
		if (ca == null)
			storageHTable.put(label,
					new CommandAction(label, descriptionOfComponentHostingButton, button, descriptionOfPath));
		else {
			ca.button = button;
			ca.buttonLocationDescription = descriptionOfPath;
		}
	}

	private String[] makeRow(final String command, final CommandAction ca) {
		final String[] result = new String[tableModel.getColumnCount()];
		result[0] = command;
		if (ca.buttonLocationDescription != null) {
			if (ca.buttonHostDescription == null)
				result[1] = ca.buttonLocationDescription;
			else
				result[1] = ca.buttonHostDescription + " " + ca.buttonLocationDescription;
		}
		return result;
	}

	private void populateList(final String matchingSubstring) {
		final String substring = (matchingSubstring == null) ? "" : matchingSubstring.toLowerCase();
		final ArrayList<String[]> list = new ArrayList<>();
		if (commands == null)
			reloadCommands();
		for (final String commandName : commands) {
			final String command = commandName.toLowerCase();
			final CommandAction ca = (CommandAction) defaultCmdsHash.get(commandName);
			String menuPath = ca.buttonLocationDescription;
			if (menuPath == null)
				menuPath = "";
			menuPath = menuPath.toLowerCase();
			if (command.indexOf(substring) > -1 || menuPath.indexOf(substring) > -1) {
				final String[] row = makeRow(commandName, ca);
				list.add(row);
			}
		}
		tableModel.setData(list);
		if (searchField != null)
			searchField.requestFocus();
//		if (frame != null && frame.isVisible() && table != null)
//			resizeRowHeight();
	}


	private void recordCommand(final CommandAction cmdAction) {
		if (!recordCmd) return;
		if (cmdAction.cmdName.startsWith("<HTML>") || cmdAction.buttonLocationDescription.contains("Scripts")) {
			System.out.println("SNT Command is not recordable...\n");
			return;
		}
		final StringBuilder sb = new StringBuilder();
		sb.append("#@SNTService snt\n");
		final boolean pmCmd = cmdAction.buttonHostDescription.startsWith("PM");
		if (pmCmd) {
			sb.append("snt.getUI().getPathManager().");
			if (cmdAction.buttonLocationDescription.contains("Tag") && !cmdAction.cmdName.contains("..."))
				sb.append("applyDefaultTags(");
			else
				sb.append("runCommand(");
			sb.append("\"").append(cmdAction.cmdName).append("\"").append(");");
		} else {
			sb.append("snt.getUI().");
			if (pmCmd && cmdAction.cmdName.contains("...")) {
				sb.append("runCommand(").append("\"").append(cmdAction.cmdName).append("\"")
						.append(", \"[optional prompt options...]\");");
			} else {
				sb.append("runCommand(").append("\"").append(cmdAction.cmdName).append("\"").append(");");
			}
		}
		sb.append("\n");
		System.out.println(sb.toString());
	}

	private void shortcutsMsg() {
		final GuiUtils gUtils = new GuiUtils(frame.getContentPane());
		gUtils.setTmpMsgTimeOut(4000);
		gUtils.tempMsg( //
				"<HTML><table>" //
				+ " <tr>" //
				+ "  <td>&uarr; &darr;</td>" //
				+ "  <td>Select commands</td>" //
				+ " </tr>" //
				+ " <tr>" //
				+ "  <td>&crarr;</td>" //
				+ "  <td>Run selected command&nbsp&nbsp</td>" //
				+ " </tr>" //
				+ " <tr>" //
				+ "  <td>A-Z</td>" //
				+ "  <td>Alphabetic scroll</td>" //
				+ " </tr>" //
				+ " <tr>" //
				+ "  <td><&#9003;</td>" //
				+ "  <td>Activate search field</td>" //
				+ " </tr>" //
				+ " <tr><td><&nbsp;</td><td>&nbsp;</td></tr>" // spacer
				+ " <tr>" //
				+ "  <td>" + GuiUtils.ctrlKey() + "+Shift+P&nbsp;&nbsp;</td>" //
				+ "  <td>Toggle palette visibility&nbsp;</td>" //
				+ " </tr>" //
				+ " <tr>" //
				+ "  <td>Esc</td>" //
				+ "  <td>Dismiss</td>" //
				+ " </tr>" //
				+ "</table>");
	}

	private void runCommand(final String command) {
		hideWindow(); // hide before running, in case command opens a dialog
		if (defaultCmdsHash.get(command) != null) {
			defaultCmdsHash.get(command).button.doClick();
			recordCommand(defaultCmdsHash.get(command));
		} else {
			recordCommand(otherCmdsHash.get(command));
			otherCmdsHash.get(command).button.doClick();
		}
	}

	static class CommandAction {
		CommandAction(final String cmdName, final String descriptionOfHostingGUIElement, final AbstractButton button,
				final String descriptionOfLocation) {
			this.cmdName = cmdName;
			this.buttonHostDescription = descriptionOfHostingGUIElement;
			this.button = button;
			this.buttonLocationDescription = descriptionOfLocation;
		}

		final String cmdName;
		AbstractButton button;
		String buttonHostDescription;
		String buttonLocationDescription;

		@Override
		public String toString() {
			return "cmdName: " + cmdName + ", button: " + button + ", host: " + buttonLocationDescription;
		}
	}

	class InternalKeyListener extends KeyAdapter {

		@Override
		public void keyPressed(final KeyEvent ke) {
			final int key = ke.getKeyCode();
			final int flags = ke.getModifiers();
			final int items = tableModel.getRowCount();
			final Object source = ke.getSource();
			final boolean meta = ((flags & KeyEvent.META_MASK) != 0) || ((flags & KeyEvent.CTRL_MASK) != 0);
			if (key == KeyEvent.VK_ESCAPE || (key == KeyEvent.VK_W && meta) || (key == KeyEvent.VK_P && meta)) {
				hideWindow();
			} else if (source == searchField) {
				/*
				 * If you hit enter in the text field, and there's only one command that
				 * matches, run that:
				 */
				if (key == KeyEvent.VK_ENTER) {
					if (1 == items)
						runCommand(tableModel.getCommand(0));
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
						runCommand(tableModel.getCommand(row));
					/* Loop through the list using the arrow keys */
				} else if (key == KeyEvent.VK_UP) {
					if (table.getSelectedRow() == 0)
						table.setRowSelectionInterval(tableModel.getRowCount() - 1, tableModel.getRowCount() - 1);
				} else if (key == KeyEvent.VK_DOWN) {
					if (table.getSelectedRow() == tableModel.getRowCount() - 1)
						table.setRowSelectionInterval(0, 0);
				}
			}
		}
	}

	class PromptDocumentListener implements DocumentListener {
		public void insertUpdate(final DocumentEvent e) {
			populateList(searchField.getText());
		}

		public void removeUpdate(final DocumentEvent e) {
			populateList(searchField.getText());
		}

		public void changedUpdate(final DocumentEvent e) {
			populateList(searchField.getText());
		}
	}

	public void reloadCommands() {
		defaultCmdsHash = new Hashtable<String, CommandAction>();
		if (otherCmdsHash != null)
			defaultCmdsHash.putAll(otherCmdsHash);

		if (frame != null)
			frame.setVisible(false); // Is this really needed?
		if (sntui != null)
			findAllMenuItemsInSNTUI();

		/*
		 * Sort the commands, generate list labels for each and put them into a hash:
		 */
		commands = (String[]) defaultCmdsHash.keySet().toArray(new String[0]);
		Arrays.sort(commands);

	}

	private void init() {
		if (defaultCmdsHash != null)
			return; // no need to proceed
		reloadCommands();
	}

	private void hideWindow() {
		if (frame != null)
			frame.setVisible(false);
	}

	private void assembleFrame() {
		if (frame != null)
			return;
		init();
		frame = new JFrame("Command Palette") {
			private static final long serialVersionUID = 6568953182481036426L;

			public void dispose() {
				frame = null;
				super.dispose();
			}
		};
		frame.setUndecorated(true);
		frame.setAlwaysOnTop(true);
		frame.getRootPane().setWindowDecorationStyle(JRootPane.NONE);
		final Container contentPane = frame.getContentPane();
		contentPane.setLayout(new BorderLayout());
		frame.setUndecorated(true);
		frame.setAlwaysOnTop(true);
		//frame.setOpacity(.95f);
		frame.addWindowListener(new WindowAdapter() {
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

		final JPanel northPanel = new JPanel(new BorderLayout());
		final JButton options = new JButton("\u22EE");
		//options.setOpaque(false);
		final JPopupMenu optionsMenu = new JPopupMenu();
		options.addMouseListener(new MouseAdapter() {
			@Override
			public void mousePressed(final MouseEvent e) {
				optionsMenu.show(options, 0, options.getHeight());
			}
		});
		final JCheckBoxMenuItem jcmi = new JCheckBoxMenuItem("Enable Command Recording", recordCmd);
		jcmi.setEnabled(sntui != null);
		optionsMenu.add(jcmi);
		jcmi.addActionListener(e -> {
			recordCmd = jcmi.isSelected();
			System.out.println("Recording of selected commands enabled: " + recordCmd);
		});
		final JMenuItem jmi = new JMenuItem("Command Palette Shortcuts...");
		optionsMenu.add(jmi);
		jmi.addActionListener(e -> shortcutsMsg());

		northPanel.add(options, BorderLayout.WEST);
		searchField = GuiUtils.textField("Search");
		searchField.getDocument().addDocumentListener(new PromptDocumentListener());
		final InternalKeyListener keyListener = new InternalKeyListener();
		searchField.addKeyListener(keyListener);
		northPanel.add(searchField);
		contentPane.add(northPanel, BorderLayout.NORTH);

		tableModel = new TableModel();
		table = new JTable(tableModel);
		populateList("");
		table.setAutoCreateRowSorter(true);
		table.setShowVerticalLines(false);
		// table.setShowHorizontalLines(false);
		table.setRowSelectionAllowed(true);
		table.setColumnSelectionAllowed(false);
		//table.getTableHeader().setDefaultRenderer(new TableHeaderRenderer(table.getTableHeader().getDefaultRenderer()));

		// Adjustments to row height and column width
		resizeColumnWidthAndRowHeight();
		table.setAutoResizeMode(JTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS);
		final Dimension dim = new Dimension(TABLE_WIDTH, table.getRowHeight() * TABLE_ROWS);
		//table.setPreferredSize(dim);
		table.setPreferredScrollableViewportSize(dim);
		table.addKeyListener(keyListener);
		table.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseClicked(final MouseEvent e) {
				if (e.getClickCount() == 2 && table.getSelectedRow() > -1) {
					runCommand(tableModel.getCommand(table.getSelectedRow()));
				}
			}
		});

		// Auto-scroll table using keystrokes
		table.addKeyListener(new KeyAdapter() {
			public void keyTyped(final KeyEvent evt) {
				if (evt.isControlDown() || evt.isMetaDown())
					return;
				final int nRows = tableModel.getRowCount();
				final char ch = Character.toLowerCase(evt.getKeyChar());
				if (!Character.isLetterOrDigit(ch)) {
					return; // Ignore searches for non alpha-numeric characters
				}
				final int sRow = table.getSelectedRow();
				for (int row = (sRow + 1) % nRows; row != sRow; row = (row + 1) % nRows) {
					final String rowData = tableModel.getValueAt(row, 0).toString();
					final char rowCh = Character.toLowerCase(rowData.charAt(0));
					if (ch == rowCh) {
						table.setRowSelectionInterval(row, row);
						table.scrollRectToVisible(table.getCellRect(row, 0, true));
						break;
					}
				}
			}
		});

		final JScrollPane scrollPane = new JScrollPane(table, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
				JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
		contentPane.add(scrollPane, BorderLayout.CENTER);

		// Make frame resizable
		final int BORDER = (ij.IJ.isMacOSX()) ? 1 : 2;
		frame.getRootPane().setBorder(BorderFactory.createEmptyBorder(0, 0, BORDER, BORDER));
		new ComponentResizer(frame); 
		frame.pack();

	}

	private Component getActiveWindow() {
		if (sntui != null) {
			if (sntui.isActive()) return sntui;
			if (sntui.getPathManager().isActive()) return sntui.getPathManager();
			if (sntui.getFillManager().isActive()) return sntui.getFillManager();
		} else if (recViewer != null) {
			return recViewer.getFrame();
		}
		return null;
	}

	private void toggleVisibility(final Component parent) {
		if (frame == null) {
			assembleFrame();
		}
		if (frame.isVisible()) {
			setVisible(false);
		} else {
			if (parent == null) 
				AWTWindows.centerWindow(frame);
			else
				AWTWindows.centerWindow(parent.getBounds(), frame);
			setVisible(true);
		}
	}

	public void toggleVisibility() {
		toggleVisibility(getActiveWindow());
	}

	public void setVisible(final boolean b) {
		if (!b) {
			hideWindow();
			return;
		}
		if (frame == null) {
			assembleFrame();
		}
		frame.setVisible(true);
		if (searchField != null) searchField.requestFocus();
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

	public void attach(final JDialog dialog) {
		final int condition = JPanel.WHEN_IN_FOCUSED_WINDOW;
		final InputMap inputMap = ((JPanel) dialog.getContentPane()).getInputMap(condition);
		final ActionMap actionMap = ((JPanel) dialog.getContentPane()).getActionMap();
		inputMap.put(ACCELERATOR, NAME);
		actionMap.put(NAME, getAction());
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

	public JButton getButton() {
		final JButton button = new JButton(getAction());
		button.setText(null);
		button.setIcon(IconFactory.getButtonIcon(IconFactory.GLYPH.SEARCH));
		button.getInputMap().put(ACCELERATOR, NAME);
		button.setToolTipText(NAME + "  " + GuiUtils.ctrlKey() + "+Shift+P");
		return button;
	}

	private void resizeColumnWidthAndRowHeight() {
		//resizeRowHeight();
		final int MIN_ROW_WIDTH = 20;
		final int MAX_ROW_WIDTH = TABLE_WIDTH * 3/4;
		final TableColumnModel columnModel = table.getColumnModel();
		for (int column = 0; column < table.getColumnCount(); column++) {
			int width = MIN_ROW_WIDTH;
			for (int row = 0; row < table.getRowCount(); row++) {
				final TableCellRenderer renderer = table.getCellRenderer(row, column);
				final Component comp = table.prepareRenderer(renderer, row, column);
				width = Math.max(comp.getPreferredSize().width + 1, width);
			}
			if (width > MAX_ROW_WIDTH)
				width = MAX_ROW_WIDTH;
			columnModel.getColumn(column).setPreferredWidth(width);
		}
	}

	@SuppressWarnings("unused")
	private void resizeRowHeight() {
		// this just seems to be needed on Linux, in which cells appear truncated
		// vertically? /// -----> no longer needed with FlatLaf look and feel
		if (PlatformUtils.isLinux()) {
			final int ROW_HEIGHT = new JLabel().getFont().getSize();
			for (int row = 0; row < table.getRowCount(); row++) {
				table.setRowHeight(row, ROW_HEIGHT);
			}
		}
	}

	private static class TableModel extends AbstractTableModel {
		private static final long serialVersionUID = 1L;
		protected ArrayList<String[]> list;
		public final static int COLUMNS = 2;

		public TableModel() {
			list = new ArrayList<>();
		}

		public void setData(final ArrayList<String[]> list) {
			this.list = list;
			fireTableDataChanged();
		}

		public int getColumnCount() {
			return COLUMNS;
		}

		public String getColumnName(final int column) {
			switch (column) {
			case 0:
				return "Command";
			case 1:
				return "Where?";
			}
			return null;
		}

		public int getRowCount() {
			return list.size();
		}

		public Object getValueAt(final int row, final int column) {
			if (row >= list.size() || column >= COLUMNS)
				return null;
			final String[] strings = (String[]) list.get(row);
			return strings[column];
		}

		public String getCommand(final int row) {
			if (row < 0 || row >= list.size())
				return "";
			else
				return (String) getValueAt(row, 0);
		}

	}

	@SuppressWarnings("unused")
	private static class TableHeaderRenderer implements TableCellRenderer {
		// https://stackoverflow.com/a/7794786

		private final TableCellRenderer delegate;

		public TableHeaderRenderer(final TableCellRenderer delegate) {
			this.delegate = delegate;
		}

		@Override
		public Component getTableCellRendererComponent(final JTable table, final Object value, final boolean isSelected,
				final boolean hasFocus, final int row, final int column) {

			final Component c = delegate.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
			if (c instanceof JLabel) {
				final JLabel label = (JLabel) c;
				switch (label.getText()) {
				case "Dialog":
					final JLabel replacementLabel = new JLabel(IconFactory.getMenuIcon(GLYPH.WINDOWS));
					replacementLabel.setBorder(label.getBorder());
					replacementLabel.setBackground(label.getBackground());
					replacementLabel.setForeground(label.getForeground());
					replacementLabel.setToolTipText("Dialog associated with command");
					return replacementLabel;
				default:
					break; // do nothing. Customizations for other columns could go here
				}
			}
			return c;
		}
	}

	static class ComponentWithFocusTimer {
		final Component component;
		final Timer focusTimer;

		ComponentWithFocusTimer(final Component c) {
			this.component = c;
			focusTimer = new Timer(20, new ActionListener() {
				// HACK: ensure frame does not flicker upon repeated clicks on relativeToComponent
				private int count = 0;
				private final int maxCount = 100;

				@Override
				public void actionPerformed(final ActionEvent e) {
					if (count++ >= maxCount) {
						((Timer) e.getSource()).stop();
					}
				}
			});
		}
	}
}
