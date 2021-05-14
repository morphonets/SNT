package sc.fiji.snt.gui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Hashtable;
import java.util.List;

import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.RowSorter;
import javax.swing.SortOrder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableColumnModel;
import javax.swing.table.TableRowSorter;

import org.scijava.ui.awt.AWTWindows;

import ij.IJ;
import ij.gui.GUI;
import sc.fiji.snt.SNTUI;
import sc.fiji.snt.gui.IconFactory.GLYPH;

public class SNTCommandFinder {

	private final SNTUI sntui;
	private Hashtable<String, CommandAction> commandsHash;

	private static final int TABLE_WIDTH = 640;
	private static final int TABLE_ROWS = 18;
	private static JFrame frame;
	private JTextField prompt;
	private String[] commands;
	private JTable table;
	private TableModel tableModel;
	private Component relativeToComponent;

	public SNTCommandFinder(final SNTUI sntui) {
		super();
		this.sntui = sntui;
	}

	public void setLocationRelativeTo(final Component c) {
		this.relativeToComponent = c;
		if (relativeToComponent != null) {
			final Window win = findWindow(relativeToComponent);
			if (win != null) {
				win.addComponentListener(new ComponentAdapter() {
					@Override
					public void componentMoved(final ComponentEvent ce) {
						super.componentMoved(ce);
						if (frame != null && frame.isVisible())
							setFrameLocation();
					}
				});
			}
		}
	}

	public void dispose() {
		if (frame != null) frame.dispose();
		frame = null;
	}

	private void findAllMenuItems() {
		parseMenuBar("Main", sntui.getJMenuBar());
		parseMenuBar("PM", sntui.getPathManager().getJMenuBar());
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
			final JMenuItem m = menu.getItem(i);
			if (m != null) {
				String label = m.getActionCommand();
				if (label == null)
					label = m.getText();
				if (m instanceof JMenu) {
					final JMenu subMenu = (JMenu) m;
					parseMenu(componentHostingMenu, path + ">" + label, subMenu);
				} else {
					final String trimmedLabel = label.trim();
					if (trimmedLabel.length() == 0 || trimmedLabel.equals("-"))
						continue;
					final CommandAction ca = (CommandAction) commandsHash.get(label);
					if (ca == null)
						commandsHash.put(label, new CommandAction(label, componentHostingMenu, m, path));
					else {
						ca.menuItem = m;
						ca.menuLocation = path;
					}
				}
			}
		}
	}

	private String[] makeRow(final String command, final CommandAction ca) {
		final String[] result = new String[tableModel.getColumnCount()];
		result[0] = command;
		if (ca.componentHostingMenu != null)
			result[1] = ca.componentHostingMenu;
		if (ca.menuLocation != null)
			result[2] = ca.menuLocation;
		return result;
	}

	private void populateList(final String matchingSubstring) {
		final String substring = (matchingSubstring == null) ? "" : matchingSubstring.toLowerCase();
		final ArrayList<String[]> list = new ArrayList<>();
		if (commands == null)
			reloadCommands();
		for (final String commandName : commands) {
			final String command = commandName.toLowerCase();
			final CommandAction ca = (CommandAction) commandsHash.get(commandName);
			String menuPath = ca.menuLocation;
			if (menuPath == null)
				menuPath = "";
			menuPath = menuPath.toLowerCase();
			if (command.indexOf(substring) > -1 || menuPath.indexOf(substring) > -1) {
				final String[] row = makeRow(commandName, ca);
				list.add(row);
			}
		}
		tableModel.setData(list);
		if (prompt != null)
			prompt.requestFocus();
	}

	private void helpMsg() {
		final String text = "Shortcuts:<br>" + "&emsp;&uarr; &darr;&ensp; Select items<br>"
				+ "&emsp;&crarr;&emsp; Open item<br>" + "&ensp;A-Z&ensp; Alphabetic scroll<br>"
				+ "&emsp;&#9003;&emsp;Activate search field</html>";
		new GuiUtils(frame).tempMsg(text);
	}

	private void runCommand(final String command) {
		commandsHash.get(command).menuItem.doClick();
		hideWindow();
	}

	private static Window findWindow(final Component c) {
		if (c == null) {
			return null;
		} else if (c instanceof Window) {
			return (Window) c;
		} else {
			return findWindow(c.getParent());
		}
	}

	class CommandAction {
		CommandAction(final String cmdName, final String componentHostingMenu, final JMenuItem menuItem,
				final String menuLocation) {
			this.cmdName = cmdName;
			this.componentHostingMenu = componentHostingMenu;
			this.menuItem = menuItem;
			this.menuLocation = menuLocation;
		}

		final String cmdName;
		String componentHostingMenu;
		JMenuItem menuItem;
		String menuLocation;

		@Override
		public String toString() {
			return "cmdName: " + cmdName + ", menuItem: " + menuItem + ", menuLocation: " + menuLocation;
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
			if (key == KeyEvent.VK_ESCAPE || (key == KeyEvent.VK_W && meta)) {
				hideWindow();
			} else if (source == prompt) {
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
				prompt.requestFocus();
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
			populateList(prompt.getText());
		}

		public void removeUpdate(final DocumentEvent e) {
			populateList(prompt.getText());
		}

		public void changedUpdate(final DocumentEvent e) {
			populateList(prompt.getText());
		}
	}

	public void reloadCommands() {
		commandsHash = new Hashtable<String, CommandAction>();
		if (frame != null)
			frame.setVisible(false); // Is this really needed?
		commandsHash = new Hashtable<String, CommandAction>();
		findAllMenuItems();

		/*
		 * Sort the commands, generate list labels for each and put them into a hash:
		 */
		commands = (String[]) commandsHash.keySet().toArray(new String[0]);
		Arrays.sort(commands);

	}

	private void init() {
		if (commandsHash != null)
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
		frame = new JFrame("SNT Command Finder") {
			private static final long serialVersionUID = 6568953182481036426L;

			public void dispose() {
				frame = null;
				super.dispose();
			}
		};
		final Container contentPane = frame.getContentPane();
		contentPane.setLayout(new BorderLayout());
		frame.setUndecorated(true);
		frame.setAlwaysOnTop(true);
		frame.addWindowListener(new WindowAdapter() {
			@Override
			public void windowClosing(final WindowEvent e) {
				hideWindow();
			}
		});

		final JPanel northPanel = new JPanel(new BorderLayout());
		final JButton searchLabel = new JButton(IconFactory.getButtonIcon(GLYPH.SEARCH));
		searchLabel.addActionListener(e -> helpMsg());
		northPanel.add(searchLabel, BorderLayout.WEST);
		prompt = new GuiUtils().textField("Search");
		prompt.getDocument().addDocumentListener(new PromptDocumentListener());
		final InternalKeyListener keyListener = new InternalKeyListener();
		prompt.addKeyListener(keyListener);
		northPanel.add(prompt);
		contentPane.add(northPanel, BorderLayout.NORTH);

		tableModel = new TableModel();
		table = new JTable(tableModel);
		populateList("");
		table.setAutoCreateRowSorter(true);
		table.setShowVerticalLines(false);
		table.setRowSelectionAllowed(true);
		table.setColumnSelectionAllowed(false);

		// Adjustments to row height and column width
		table.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);
		resizeColumnWidthAndRowHeight(table);
		table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF); // frame is not resizable: will 'activate' scrollpane
															// scrollbars instead

		final Dimension dim = new Dimension(TABLE_WIDTH, table.getRowHeight() * TABLE_ROWS);
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
		frame.pack();
	}

	public void toggleVisibility() {
		setVisible(!(frame != null && frame.isVisible()));
	}

	public void setVisible(final boolean b) {
		if (!b) {
			if (frame != null)
				frame.setVisible(false);
			return;
		}
		if (frame == null) {
			assembleFrame();
		}
		setFrameLocation();
		frame.setVisible(true);
	}

	private void resizeColumnWidthAndRowHeight(final JTable table) {
		final int ROW_HEIGHT = new JLabel().getFont().getSize();
		final int MIN_ROW_WIDTH = 15;
		final int MAX_ROW_WIDTH = 300;
		final TableColumnModel columnModel = table.getColumnModel();
		for (int column = 0; column < table.getColumnCount(); column++) {
			int width = MIN_ROW_WIDTH;
			for (int row = 0; row < table.getRowCount(); row++) {
				final TableCellRenderer renderer = table.getCellRenderer(row, column);
				final Component comp = table.prepareRenderer(renderer, row, column);
				width = Math.max(comp.getPreferredSize().width + 1, width);
				table.setRowHeight(row, ROW_HEIGHT);
			}
			if (width > MAX_ROW_WIDTH)
				width = MAX_ROW_WIDTH;
			columnModel.getColumn(column).setPreferredWidth(width);
		}
	}

	private void setFrameLocation() {
		if (relativeToComponent == null) {
			AWTWindows.centerWindow(frame);
		} else {

			final int dialogWidth = frame.getWidth();
			final int dialogHeight = frame.getHeight();
			final Point pos = relativeToComponent.getLocationOnScreen();
			final Dimension size = relativeToComponent.getSize();

			/*
			 * Generally try to position the dialog slightly offset from the main ImageJ
			 * window, but if that would push the dialog off to the screen to any side,
			 * adjust it so that it's on the screen.
			 */
			int initialX = pos.x + 5;
			int initialY = pos.y + 5 + size.height;
			final Rectangle screen = GUI.getMaxWindowBounds(IJ.getInstance());

			initialX = Math.max(screen.x, Math.min(initialX, screen.x + screen.width - dialogWidth));
			initialY = Math.max(screen.y, Math.min(initialY, screen.y + screen.height - dialogHeight));
			frame.setLocation(initialX, initialY);
		}
	}

	private class TableModel extends AbstractTableModel {
		private static final long serialVersionUID = 1L;
		protected ArrayList<String[]> list;
		public final static int COLUMNS = 3;

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
				return "Dialog";
			case 2:
				return "Menu Path";
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

}
