/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2026 Fiji developers.
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

import com.jidesoft.swing.CheckBoxList;
import net.imagej.ImageJ;
import org.apache.commons.math3.stat.descriptive.SummaryStatistics;
import org.scijava.Context;
import org.scijava.command.CommandService;
import org.scijava.plugin.Parameter;
import org.scijava.prefs.PrefService;
import sc.fiji.snt.SNT;
import sc.fiji.snt.SNTService;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.Tree;
import sc.fiji.snt.analysis.PathProfiler;
import sc.fiji.snt.analysis.SNTTable;
import sc.fiji.snt.analysis.TreeStatistics;
import sc.fiji.snt.gui.cmds.FigCreatorCmd;

import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.extras.components.FlatTriStateCheckBox;
import sc.fiji.snt.util.SNTColor;

import javax.swing.*;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import javax.swing.table.*;
import java.awt.*;
import java.awt.event.*;
import java.io.Serial;
import java.util.List;
import java.util.*;
import java.util.stream.IntStream;


/**
 * The MeasureUI class is a graphical user interface for measuring and analyzing {@link Tree}s.
 * It provides a panel for selecting metrics and statistics, displaying results in a dedicated table.
 * It also includes options for saving and summarizing the measurements.
 *
 * @author Cameron Arshadi
 * @author Tiago Ferreira
 */
public class MeasureUI extends JFrame {

	@Serial
	private static final long serialVersionUID = 6565638887510865592L;
	private static final String MIN = "Min";
	private static final String MAX = "Max";
	private static final String MEAN = "Mean";
	private static final String STDDEV = "SD";
	private static final String SUM = "Sum";
	private static final String CV = "CV";
	private static final String N = "N";
	private static final String[] allFlags = new String[] { MIN, MAX, MEAN, STDDEV, CV, SUM, N };
	private static final Class<?>[] columnClasses = new Class<?>[] { String.class, Boolean.class, Boolean.class,
			Boolean.class, Boolean.class, Boolean.class, Boolean.class, Boolean.class };
	private final MeasurePanel panel;

	@Parameter
	private PrefService prefService;

	private SNTTable table;
	private boolean distinguishCompartments;
	private String lastDirPath;
	private final GuiUtils guiUtils;
	private SNT plugin;
	private JProgressBar bar;
	private GenerateTableAction.BackgroundTask backgroundTask;


	public MeasureUI(final Collection<Tree> trees) {
		this(SNTUtils.getContext(), trees);
		lastDirPath = System.getProperty("user.home");
	}

	public MeasureUI(final SNT plugin, final Collection<Tree> trees) {
		this(plugin.getContext(), trees);
		this.plugin = plugin;
		lastDirPath = plugin.getPrefs().getRecentDir().getAbsolutePath();
		if (plugin.getUI() != null)
			setTable(plugin.getUI().getTable());
	}

	private MeasureUI(final Context context, final Collection<Tree> trees) {
		super("SNT Measurements "); // 	important: distinguish from table title
		//getRootPane().putClientProperty("Window.style", "small");
		context.inject(this);
		guiUtils = new GuiUtils(this);
		panel = new MeasurePanel(trees);
		add(panel);
		pack();
		setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
		addWindowListener(new WindowAdapter() {
			@Override
			public void windowClosing(final WindowEvent e) {
				final boolean exit = backgroundTask == null || backgroundTask.isDone() || guiUtils.getConfirmation(
						"Measurements are still being retrieved. Interrupt nevertheless?", "Interrupt?");
				if (exit) dispose();
			}
		});
	}

	@Override
	public void dispose() {
		panel.savePreferences();
		if (backgroundTask != null && !backgroundTask.isDone()) {
			backgroundTask.cancel(true);
			backgroundTask.done();
		}
		super.dispose();
	}

	@Override
	public void setVisible(final boolean b) {
		// Script friendly version
		setLocationRelativeTo((plugin == null) ? null : plugin.getUI());
		SwingUtilities.invokeLater(() -> super.setVisible(b));
	}

	public void setTable(final SNTTable table) {
		this.table = table;
	}

	private void wipeTable() {
		if (table != null) table.clear();
		updateTable(false);
	}

	private void updateTable(final boolean createAsNeeded) {
		if (table == null) {
			UIManager.getLookAndFeel().provideErrorFeedback(this);
			return;
		}
		if (createAsNeeded)
			table.createOrUpdateDisplay();
		else
			table.updateDisplay();
	}

	// Writes value into the given model rows/columns of table in a single pass, firing one
	// table-changed event instead of the one-event-per-cell behavior of JTable#setValueAt
	@SuppressWarnings({"unchecked", "rawtypes"})
	private static void batchSetValues(final JTable table, final int[] modelRows, final int[] modelColumns,
			final Object value) {
		final DefaultTableModel model = (DefaultTableModel) table.getModel();
		final Vector<Vector> data = model.getDataVector();
		for (final int r : modelRows) {
			final Vector<Object> rowVector = data.get(r);
			for (final int c : modelColumns) {
				rowVector.set(c, value);
			}
		}
		model.fireTableDataChanged();
	}

	class MeasurePanel extends JPanel {

		@Serial
		private static final long serialVersionUID = 1L;
		private final CheckBoxList metricList;
		private final DefaultTableModel statsTableModel;
		private final JButton runButton;

		@SuppressWarnings("unchecked")
		MeasurePanel(final Collection<Tree> trees) {

			// Stats table
			final DefaultTableModel statsTableModelImpl = new DefaultTableModel() {

				@Serial
				private static final long serialVersionUID = 1L;

				@Override
				public Class<?> getColumnClass(final int column) {
					return columnClasses[column];
				}

				@Override
				public boolean isCellEditable(final int row, final int column) {
					return column != 0;
				}
			};
			final JTable statsTable = GuiUtils.JTables.tableWithPlaceholder(statsTableModelImpl,
					() -> "No metrics selected on the left pane.", null);

			// initialize table mode.
			statsTableModel = statsTableModelImpl;
			statsTableModel.addColumn("Selected Metric");

			// tweak table
			statsTable.setAutoCreateRowSorter(true);
			statsTable.setAutoResizeMode(JTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS);
			for (final String metric : allFlags) {
				statsTableModel.addColumn(metric);
			}
			final SelectAllHeader[] statHeaders = new SelectAllHeader[statsTable.getColumnCount()];
			for (int i = 1; i < statsTable.getColumnCount(); ++i) {
				final SelectAllHeader statHeader = new SelectAllHeader(statsTable, i, statsTable.getColumnName(i));
				statsTable.getColumnModel().getColumn(i).setHeaderRenderer(statHeader);
				statHeaders[i] = statHeader;
			}
			// Set the two panes of the dialog visually apart, now that all columns are in place
			GuiUtils.JTables.installAlternatingRows(statsTable);
			// Keep each column header's tri-state checkbox in sync with individual cell edits:
			// any change to the stats table (row add/remove, or a single cell toggle) recomputes
			// every header so a mixed column is shown as indeterminate
			statsTableModel.addTableModelListener(e -> {
				for (final SelectAllHeader statHeader : statHeaders) {
					if (statHeader != null) statHeader.refreshState();
				}
				statsTable.getTableHeader().repaint();
			});
			// Enlarge default width of first column. Another option would be to have all
			// columns to auto-fit at all times, e.g., https://stackoverflow.com/a/25570812.
			// Maybe that would be better?
			final String prototypeMetric = TreeStatistics.N_BRANCH_NODES;
			for (int i = 0; i < statsTable.getColumnCount(); ++i) {
				final int width = SwingUtilities.computeStringWidth(statsTable.getFontMetrics(statsTable.getFont()),
						(i == 0) ? prototypeMetric : MEAN);
				statsTable.getColumnModel().getColumn(i).setMinWidth(width);
			}
			statsTable.setPreferredScrollableViewportSize(statsTable.getPreferredSize());
			statsTable.addComponentListener(new ComponentAdapter() {
				@Override
				public void componentResized(final ComponentEvent e) {
					GuiUtils.JTables.scrollToBottom(statsTable);
				}
			});

			// List of choices
			final DefaultListModel<String> listModel = new DefaultListModel<>();
			final List<String> allMetrics = TreeStatistics.getAllMetrics();
			Collections.sort(allMetrics);
			allMetrics.forEach(listModel::addElement);
			metricList = new CheckBoxList(listModel);
			metricList.setClickInCheckBoxOnly(false);
			metricList.setComponentPopupMenu(listPopupMenu());
			metricList.setPrototypeCellValue(prototypeMetric);
			metricList.getCheckBoxListSelectionModel().addListSelectionListener(e -> {
				if (!e.getValueIsAdjusting()) {
					final List<Object> selectedMetrics = new ArrayList<>(
							Arrays.asList(metricList.getCheckBoxListSelectedValues()));
					addMetricsToStatsTableModel(selectedMetrics);
				}
			});

			// Help icon: a small "?" glyph docked to the right edge of the row, shown only for
			// the currently selected (highlighted) row; clicking it opens that metric's
			// documentation -- the same action as the popup menu's "Define Highlighted Metric..."
			// A real JButton isn't practical here: JList renderers are painted stand-ins, not live
			// components, so instead the hand cursor + a link-colored icon give the same "this is
			// clickable" feedback, tracked only for the one row where the icon can ever be shown.
			final MetricListRenderer metricListRenderer = new MetricListRenderer(metricList);
			metricList.setCellRenderer(metricListRenderer);
			// The highlighted row can change (click, arrow keys) without the mouse itself moving,
			// which would otherwise leave a stale hover state applied to whichever row is newly selected
			metricList.addListSelectionListener(e -> {
				if (!e.getValueIsAdjusting() && metricListRenderer.isIconHovered()) {
					metricListRenderer.setIconHovered(false);
					metricList.repaint();
				}
			});
			metricList.addMouseMotionListener(new MouseMotionAdapter() {
				@Override
				public void mouseMoved(final MouseEvent e) {
					final int row = metricList.getSelectedIndex();
					final boolean overIcon = row >= 0
							&& metricListRenderer.helpIconRect(metricList.getCellBounds(row, row)).contains(e.getPoint());
					if (overIcon != metricListRenderer.isIconHovered()) {
						metricListRenderer.setIconHovered(overIcon);
						if (row >= 0) metricList.repaint(metricList.getCellBounds(row, row));
					}
					metricList.setCursor(Cursor.getPredefinedCursor(overIcon ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
				}
			});
			metricList.addMouseListener(new MouseAdapter() {
				@Override
				public void mouseExited(final MouseEvent e) {
					if (metricListRenderer.isIconHovered()) {
						metricListRenderer.setIconHovered(false);
						final int row = metricList.getSelectedIndex();
						if (row >= 0) metricList.repaint(metricList.getCellBounds(row, row));
					}
					metricList.setCursor(Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR));
				}

				@Override
				public void mouseClicked(final MouseEvent e) {
					final int row = metricList.getSelectedIndex();
					if (row < 0) return;
					if (metricListRenderer.helpIconRect(metricList.getCellBounds(row, row)).contains(e.getPoint())) {
						showMetricHelp(metricList.getModel().getElementAt(row).toString());
					}
				}
			});

			// searchable
			final SNTSearchableBar searchableBar = new SNTSearchableBar(metricList,
					"Search " + allMetrics.size() + " metrics");
			searchableBar.setVisibleButtons(SNTSearchableBar.SHOW_HIGHLIGHTS | SNTSearchableBar.SHOW_NAVIGATION);
			searchableBar.setVisible(true);
			searchableBar.setHighlightAll(true);
			searchableBar.setGuiUtils(guiUtils);

			// progress bar
			bar = new JProgressBar();
			bar.setVisible(true);
			bar.setFocusable(false);
			bar.setMinimum(0);
			bar.setMaximum(trees.size());
			bar.setVisible(true);
			bar.setStringPainted(true);
			bar.setString(String.format("Measuring %d reconstruction(s)", trees.size()));

			// remember previous state
			loadPreferences();

			// assemble GUI
			setLayout(new GridBagLayout());
			final GridBagConstraints c = new GridBagConstraints();
			c.gridx = 0;
			c.gridy = 0;
			c.weighty = 1.0; // fill height when resizing pane
			c.fill = GridBagConstraints.BOTH;
			c.weightx = 0.0; // do not fill width when resizing panel
			c.gridheight = 1;
			final JScrollPane metricListScrollPane = new JScrollPane(metricList);
			// The help icon is painted over the cell, not part of its own preferred (text-only)
			// width, so widen the list a tad to keep it from overlapping long metric names
			final Dimension metricListPreferredSize = metricListScrollPane.getPreferredSize();
			metricListScrollPane.setPreferredSize(new Dimension(
					metricListPreferredSize.width + metricListRenderer.extraWidth(), metricListPreferredSize.height));
			add(metricListScrollPane, c);

			c.gridy = 1;
			c.weighty = 0; // do not allow panel to fill height when resizing pane
			add(searchableBar, c);

			c.gridx = 1;
			c.gridy = 0;
			c.weightx = 1.0; // fill width when resizing panel
			c.weighty = 1.0; // fill height when resizing pane
			c.gridwidth = 1;

			final JScrollPane statsTableScrollPane = new JScrollPane(statsTable);
			TablePopupMenu.install(statsTable, statsTableScrollPane);
			add(statsTableScrollPane, c);

			runButton = new JButton("Measure", IconFactory.buttonIcon(IconFactory.GLYPH.TABLE, 1.1f));
			runButton.addActionListener(new GenerateTableAction(trees, statsTableModel));
			final JButton optionsButton = optionsButton(trees);
			final JToggleButton splitButton = splitButton();
			final JToolBar toolbar = new JToolBar();
			toolbar.setBorder(searchableBar.getBorder()); // top, left, bottom, right margins
			toolbar.add(Box.createHorizontalGlue());
			toolbar.add(bar);
			toolbar.add(Box.createHorizontalGlue());
			toolbar.add(optionsButton);
			toolbar.addSeparator();
			toolbar.add(splitButton);
			toolbar.addSeparator();
			toolbar.add(runButton);
			c.gridx = 1;
			c.gridy = 1;
			c.gridwidth = 1;
			c.weighty = 0.0; // do not allow panel to fill height when resizing pane
			add(toolbar, c);
		}

		private JToggleButton splitButton() {
			final JToggleButton button = new JToggleButton("Compartments", distinguishCompartments);
			IconFactory.assignIcon(button, IconFactory.GLYPH.SCALE_BALANCED, IconFactory.GLYPH.SCALE_UNBALANCED);
			button.setToolTipText("Whether measurements should be split into cellular\n"
					+ "compartment (e.g., \"axon\", \"dendrites\", etc.)");
			button.addItemListener(e -> {
				distinguishCompartments = button.isSelected();
				runButton.setToolTipText((distinguishCompartments)
						? "Measurements will be split into known compartments\n(axon, dendrites, etc.)"
						: "Measurements will be retrieved for the whole cell\nwithout compartment (axon, dendrites, etc.) distinction");
			});
			return button;
		}

		private JButton optionsButton(final Collection<Tree> trees) {
			final JButton optionsButton = GuiUtils.Buttons.options();
			optionsButton.setFont(
					optionsButton.getFont().deriveFont(optionsButton.getFont().getSize2D()*1.1f)); // scale icon (font-based)
			optionsButton.setToolTipText("Options & Utilities");
			final JPopupMenu optionsMenu = new JPopupMenu();
			GuiUtils.addSeparator(optionsMenu, "General:");
			final JCheckBoxMenuItem jcmi4 = GuiUtils.MenuItems.debugMode();
			jcmi4.addActionListener(e -> {
				SNTUtils.setDebugMode(jcmi4.isSelected());
				if (plugin != null && plugin.getUI() != null) {
					plugin.getUI().setEnableDebugMode(jcmi4.isSelected());
				}
			});
			optionsMenu.add(jcmi4);
			GuiUtils.addSeparator(optionsMenu, "Measurements Table:");
			JMenuItem jmi = new JMenuItem("Clear Measurements", IconFactory.menuIcon(IconFactory.GLYPH.TRASH));
			jmi.addActionListener(e -> wipeTable());
			optionsMenu.add(jmi);
			jmi = GuiUtils.MenuItems.save(() -> table, null);
			jmi.setToolTipText("Save measurements table. Note that tables can always\n"
					+ "be saved using the 'Save Tables and Analysis Plot' command");
			optionsMenu.add(jmi);
			jmi = GuiUtils.MenuItems.summarize(() -> table, null);
			jmi.setToolTipText("Computes Mean, SD, Sum, etc. for existing measurements");
			optionsMenu.add(jmi);
			optionsMenu.add( GuiUtils.MenuItems.distribution(() -> table));
			GuiUtils.addSeparator(optionsMenu, "Utilities:");
			jmi = GuiUtils.MenuItems.renderQuick();
			jmi.addActionListener(e -> {
				final Map<String, Object> inputs = new HashMap<>();
				inputs.put("trees", trees);
				SNTUtils.getContext().getService(CommandService.class).run(FigCreatorCmd.class, true, inputs);
			});
			optionsMenu.add(jmi);
			jmi = new JMenuItem("List Cell(s) Being Measured...", IconFactory.menuIcon(IconFactory.GLYPH.LIST));
			jmi.addActionListener(e -> showDetails(trees));
			optionsMenu.add(jmi);
			GuiUtils.addSeparator(optionsMenu, "Help:");
			jmi = new JMenuItem("Quick Guide...", IconFactory.menuIcon(IconFactory.GLYPH.INFO));
			jmi.addActionListener(e -> showHelp());
			optionsMenu.add(jmi);
			jmi = GuiUtils.MenuItems.openURL("Definition of Metrics", "https://imagej.net/plugins/snt/metrics");
			jmi.setIcon(IconFactory.menuIcon(IconFactory.GLYPH.QUESTION));
			optionsMenu.add(jmi);
			optionsButton.addMouseListener(new MouseAdapter() {

				@Override
				public void mousePressed(final MouseEvent e) {
					optionsMenu.show(optionsButton, optionsButton.getWidth() / 2, optionsButton.getHeight() / 2);
				}
			});
			return optionsButton;
		}

		private void showDetails(final Collection<Tree> trees) {
			final StringBuilder sb = new StringBuilder("<HTML><div align='center'>");
			sb.append("<table><tbody>");
			sb.append("<tr style='border-top:1px solid; border-bottom:1px solid; '>");
			sb.append("<td style='text-align: center;'><b>&nbsp;#&nbsp;</b></td>");
			sb.append("<td style='text-align: center;'><b>Label</b></td>");
			sb.append("<td style='text-align: center;'><b>Spatial Unit</b></td>");
			sb.append("<td style='text-align: center;'><b>No. Compartments</b></td>");
			sb.append("<td style='text-align: center;'><b>Source</b></td>");
			sb.append("</tr>");
			final int[] counter = { 1 };
			trees.forEach(tree -> {
				final Properties props = tree.getProperties();
				sb.append("<tr style='border-bottom:1px solid'>");
				sb.append("<td style='text-align: center;'>").append(counter[0]++).append("</td>");
				sb.append("<td style='text-align: center;'>").append(tree.getLabel()).append("</td>");
				sb.append("<td style='text-align: center;'>").append(props.getOrDefault(Tree.KEY_SPATIAL_UNIT, "N/A"))
						.append("</td>");
				sb.append("<td style='text-align: center;'>").append( tree.getSWCTypes(false).size())
				.append("</td>");
				sb.append("<td style='text-align: center;'>").append(props.getOrDefault(Tree.KEY_SOURCE, "N/A"))
						.append("</td>");
				sb.append("</tr>");
			});
			sb.append("</tbody></table>");
			GuiUtils.showHTMLDialog(sb.toString(), trees.size() + " Tree(s) Being Measured");
		}

		private void showHelp() {
			GuiUtils.showHTMLDialog("<p><b>How to Measure Reconstructions:</b></p>"//
					+ "<ol>"//
					+ "<li>Select metrics on the left panel. <i>Tip</i>: Use the search box on the bottom left and "//
					+ "the right-click options for faster selection(s)</li>"//
					+ "<li>Select statistics on the right panel</li>"//
					+ "<li>Toggle the 'Split by Compartment' option as needed</li>"//
					+ "<li>Press 'Measure'</li>"//
					+ "<li>Use the gear menu for common actions</li>"//
					+ "</ol>"//
					+ "<p><b>Notes on Metrics:</b></p>"//
					+ "<ul>"//
					+ "<li>Some metrics assume the reconstruction being parsed is a valid mathematical Tree. " //
					+ "If that is not verified, values may be reported as <em>NaN</em> and related errors "//
					+ "reported to the Console (when running in <em>Debug</em> mode)</li>"//
					+ "<li>Computation of convex hulls and Sholl metrics based on automated curve fitting can be "//
					+ "computationally-intensive. When parsing large groups of cells, it is highly recommended to "//
					+ "retrieve metrics of such analyses using their dedicated commands</li>"//
					+ "<li>Other analysis commands (Strahler, Sholl, Graph Analysis, etc.) will retrieve further "//
					+ "measurements that are not listed in this prompt</li>"//
					+ "</ul>"//
					+ "<p><b>Notes on Statistics:</b></p>"//
					+ "<ul>"//
					+ "<li>Some combinations of metrics/statistics may not be meaningful: e.g., if "//
					+ "you are only measuring a single cell, pairing <em>Cable length&nbsp;</em> to <em>SD</em> "//
					+ "will not be useful, since only one value has been computed. In this case, the Measurement "//
					+ "table will append '[Single metric]' to such data</li>"//
					+ "<li>Some combinations of metrics/statistics can become obnoxiously redundant. E.g., for a "//
					+ "given cell, retrieving 'N' for 'Branch length' is the same as retrieving the cell's 'No. of "//
					+ "Branches' metric.</li"//
					+ "<li><em>N</em> may refer to different components (no. of cells, no. of branches, no. of " //
					+ "nodes, etc.) depending on the metric being retrieved</li>"//
					+ "</ul>"//
					+ "<p><b>Note on Fitted Paths:</b></p>"//
					+ "<p>Some branch-based metrics may not be available when mixing fitted and "//
					+ "non-fitted paths because paths are fitted independently from one another and "//
					+ "may not be aware of the original connectivity. "//
					+ "When this happens, metrics may be reported as <em>NaN</em> and related errors "//
					+ "reported to the Console (when running in <em>Debug</em> mode).</p>"//
					+ "<p>If this becomes an issue, consider fitting paths in situ using the <em>Replace existing "//
					+ "nodes</em> option instead. Also, remember that you can also use the Path Manager&#39;s "//
					+ "Edit&gt;Rebuild... command to re-compute "//
					+ "relationships between paths.</p>", "Measure: Offline Guide");
		}

		private void loadPreferences() {
			distinguishCompartments = prefService.getBoolean(getClass(), "distinguish", false);
			lastDirPath = prefService.get(getClass(), "lastdir", lastDirPath);
			final List<String> metrics = prefService.getList(getClass(), "metrics");
			final List<String> stats = prefService.getList(getClass(), "stats");
			metricList.addCheckBoxListSelectedValues(metrics.toArray(new String[0]));
			for (int row = 0; row < statsTableModel.getRowCount(); ++row) {
				for (int column = 1; column < statsTableModel.getColumnCount(); ++column) {
					statsTableModel.setValueAt(getBoolean(stats, allFlags.length * row + column - 1), row, column);
				}
			}
		}

		private void savePreferences() {
			prefService.put(getClass(), "distinguish", distinguishCompartments);
			prefService.put(getClass(), "lastdir", lastDirPath);
			final List<String> metrics = new ArrayList<>();
			final List<String> stats = new ArrayList<>();
			for (int row = 0; row < statsTableModel.getRowCount(); ++row) {
				for (int column = 0; column < statsTableModel.getColumnCount(); ++column) {
					if (column == 0)
						metrics.add((statsTableModel.getValueAt(row, 0)).toString());
					else {
						final Object v = statsTableModel.getValueAt(row, column);
						stats.add( (null == v) ? "false" : v.toString() );
					}
				}
			}
			prefService.put(getClass(), "metrics", metrics);
			prefService.put(getClass(), "stats", stats);
		}

		private void addMetricsToStatsTableModel(final List<?> metrics) {
			final Set<Object> selectedMetricsSet = new HashSet<>(metrics);
			final Set<Object> existingMetricsSet = new HashSet<>();
			final List<Integer> rowsToRemove = new ArrayList<>();

			// Identify rows to remove and existing metrics
			for (int i = 0; i < statsTableModel.getRowCount(); ++i) {
				final Object existingMetric = statsTableModel.getValueAt(i, 0);
				if (!selectedMetricsSet.contains(existingMetric)) {
					rowsToRemove.add(i);
				} else {
					existingMetricsSet.add(existingMetric);
				}
			}

			// Collect only new metrics (not already in table)
			final boolean[] defChoices = getLastChosenStats();
			final List<Vector<Object>> rowsToAdd = new ArrayList<>();
			for (final Object metric : metrics) {
				if (!existingMetricsSet.contains(metric)) {
					rowsToAdd.add(new Vector<>(Arrays.asList(metric, defChoices[0], defChoices[1],
							defChoices[2], defChoices[3], defChoices[4], defChoices[5], defChoices[6])));
				}
			}

			// Apply removal and addition together, firing a single table-changed event for the
			// whole selection change instead of one removeRow/addRow event per row
			batchUpdateRows(statsTableModel, rowsToRemove, rowsToAdd);
		}

		private boolean[] getLastChosenStats() {
			final boolean[] bools = new boolean[allFlags.length];
			if (statsTableModel.getRowCount() > 0) {
				try {
					for (int column = 1; column < statsTableModel.getColumnCount(); ++column) {
						bools[column-1] = (boolean) statsTableModel.getValueAt(statsTableModel.getRowCount()-1, column);
					}
				} catch (final Exception ignored) {
					return bools;
				}
			}
			return bools;
		}

		private boolean getBoolean(final List<String> stats, final int index) {
			try {
				return Boolean.parseBoolean(stats.get(index));
			} catch (final Exception ignored) {
				return false;
			}
		}

		@SuppressWarnings({"rawtypes"})
		private void batchUpdateRows(final DefaultTableModel model, final List<Integer> rowsToRemove,
				final List<Vector<Object>> rowsToAdd) {
			if (rowsToRemove.isEmpty() && rowsToAdd.isEmpty()) return;
			final Vector<Vector> data = model.getDataVector();
			Collections.sort(rowsToRemove);
			for (int i = rowsToRemove.size() - 1; i >= 0; i--) {
				data.remove(rowsToRemove.get(i).intValue());
			}
			data.addAll(rowsToAdd);
			model.fireTableDataChanged();
		}

		private JPopupMenu listPopupMenu() {
			final JPopupMenu pMenu = new JPopupMenu();
			GuiUtils.addSeparator(pMenu, "Selection of Metrics:");
			JMenuItem mi = new JMenuItem("Select All");
			mi.addActionListener(e -> metricList.selectAll());
			pMenu.add(mi);
			mi = new JMenuItem("Select None");
			mi.addActionListener(e -> metricList.clearCheckBoxListSelection());
			pMenu.add(mi);
			pMenu.addSeparator();
			mi = new JMenuItem("Select Highlighted");
			mi.addActionListener(e -> setHighlightedSelected(true));
			pMenu.add(mi);
			mi = new JMenuItem("Deselect Highlighted");
			mi.addActionListener(e -> setHighlightedSelected(false));
			pMenu.add(mi);
			pMenu.addSeparator();
			mi = new JMenuItem("Invert Selection");
			mi.addActionListener(e -> invertSelection());
			pMenu.add(mi);
			pMenu.addSeparator();
			mi = new JMenuItem("Define Highlighted Metric...", IconFactory.menuIcon(IconFactory.GLYPH.QUESTION));
			mi.addActionListener(e -> getHelpOnHighlightedMetric());
			pMenu.add(mi);
			return pMenu;
		}

		void invertSelection() {
			final ListSelectionModel mdl = metricList.getCheckBoxListSelectionModel();
			final int[] selected = metricList.getCheckBoxListSelectedIndices();
			mdl.setValueIsAdjusting(true);
			mdl.setSelectionInterval(0, metricList.getModel().getSize() - 1);
			for (final int i : selected) {
				mdl.removeSelectionInterval(i, i);
			}
			mdl.setValueIsAdjusting(false);
		}

		private void setHighlightedSelected(final boolean select) {
			final int[] indices = metricList.getSelectedIndices();
			if (indices.length == 0) {
				UIManager.getLookAndFeel().provideErrorFeedback(this);
				return;
			}
			metricList.setValueIsAdjusting(true);
			for (final int index : indices) {
				if (select)
					metricList.addCheckBoxListSelectedIndex(index);
				else
					metricList.removeCheckBoxListSelectedIndex(index);
			}
			metricList.setValueIsAdjusting(false);
		}

		private void getHelpOnHighlightedMetric() {
			final int idx = metricList.getSelectedIndex();
			if (idx < 0) {
				UIManager.getLookAndFeel().provideErrorFeedback(this);
				return;
			}
			showMetricHelp(metricList.getModel().getElementAt(idx).toString());
		}

		private void showMetricHelp(String metric) {
			metric = switch (metric) {
				case TreeStatistics.X_COORDINATES, TreeStatistics.Y_COORDINATES, TreeStatistics.Z_COORDINATES ->
						"xyz-coordinates";
				default -> metric.replace(".", "").replace(":", "")//
						.replace("(", "").replace(")", "") //
						.replace("[", "").replace("]", "") //
						.replace("/", "").replace("\\", "") //
						.replace(" ", "-");
			};
			GuiUtils.openURL("https://imagej.net/plugins/snt/metrics#" + metric.toLowerCase());
		}

		/**
		 * List cell renderer that augments the default metric-name label with a help icon docked
		 * to the row's right edge, shown only for the currently selected (highlighted) row.
		 */
		private static class MetricListRenderer extends DefaultListCellRenderer {

			@Serial
			private static final long serialVersionUID = 1L;
			private static final int ICON_MARGIN = 6;
			private final int iconWidth;
			private Icon helpIcon;
			private Icon helpIconHover;
			private Color helpIconColor;
			private boolean showIcon;
			private boolean iconHovered;

			MetricListRenderer(final JList<?> list) {
				// The reference icon's width only depends on the list's font size, not on color,
				// so it's safe to build once, up front.
				iconWidth = IconFactory.get(IconFactory.GLYPH.QUESTION, list.getFont().getSize() * 1.5f, null).getIconWidth();
				// Placeholder tint, just so helpIcon/helpIconHover have valid dimensions the moment
				// extraWidth() is consulted during dialog layout, before the list is ever painted.
				// list.getSelectionForeground() -- the color we actually want -- isn't reliably
				// resolved yet at construction time (the list hasn't been realized/painted), so the
				// real tint is (re)applied lazily in getListCellRendererComponent below.
				updateIcons(list, list.getForeground());
			}

			/** (Re)builds the glyph icons tinted for the given color, if it actually changed. */
			private void updateIcons(final JList<?> list, final Color color) {
				if (color.equals(helpIconColor)) return;
				helpIconColor = color;
				// Only visible when the row is selected; tinted with the selection foreground so it
				// reads consistently with the row's own (also selection-colored) text
				final Icon glyph = IconFactory.listIcon(list, IconFactory.GLYPH.QUESTION, color);
				helpIcon = IconFactory.fixedWidthIcon(glyph, iconWidth);
				// Only visible when the row is selected AND the mouse is hovering it; contrasted
				// against the selection foreground so it additionally signals "clickable"
				final Icon hoverGlyph = IconFactory.listIcon(list, IconFactory.GLYPH.QUESTION,
						SNTColor.contrastColor(color));
				helpIconHover = IconFactory.fixedWidthIcon(hoverGlyph, iconWidth);
			}

			/** Extra horizontal space the help icon needs beyond the cell's own (text-only) width. */
			int extraWidth() {
				return helpIcon.getIconWidth() + ICON_MARGIN * 2;
			}

			/** Bounds of the help icon within rowBounds (row-local or list-space; both work). */
			Rectangle helpIconRect(final Rectangle rowBounds) {
				return new Rectangle(rowBounds.x + rowBounds.width - helpIcon.getIconWidth() - ICON_MARGIN,
						rowBounds.y + (rowBounds.height - helpIcon.getIconHeight()) / 2,
						helpIcon.getIconWidth(), helpIcon.getIconHeight());
			}

			boolean isIconHovered() {
				return iconHovered;
			}

			void setIconHovered(final boolean iconHovered) {
				this.iconHovered = iconHovered;
			}

			@Override
			public Component getListCellRendererComponent(final JList<?> list, final Object value, final int index,
					final boolean isSelected, final boolean cellHasFocus) {
				super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
				showIcon = isSelected;
				if (isSelected) {
					// super's call above already resolved the real, current selection foreground
					// onto this component -- use that instead of list.getSelectionForeground()
					// directly, which can still be stale if queried before the list was painted
					updateIcons(list, getForeground());
				}
				return this;
			}

			@Override
			protected void paintComponent(final Graphics g) {
				super.paintComponent(g);
				if (showIcon) {
					final Rectangle r = helpIconRect(new Rectangle(0, 0, getWidth(), getHeight()));
					(iconHovered ? helpIconHover : helpIcon).paintIcon(this, g, r.x, r.y);
				}
			}
		}

	}

	private class GenerateTableAction extends AbstractAction {

		@Serial
		private static final long serialVersionUID = 1L;
		final Collection<Tree> trees;
		final DefaultTableModel tableModel;

		GenerateTableAction(final Collection<Tree> trees, final DefaultTableModel tableModel) {
			super("Run", null);
			this.trees = trees;
			this.tableModel = tableModel;
		}

		@Override
		public void actionPerformed(final ActionEvent e) {
			if (tableModel.getRowCount() == 0) {
				guiUtils.error("You must select at least one metric.");
				return;
			}
			if (!atLeastOneStatChosen() && trees.size() > 1) {
				guiUtils.error("You must select at least one statistic.");
				return;
			}
			if (table == null)
				table = new SNTTable();
			backgroundTask =  new BackgroundTask((JButton) e.getSource());
			backgroundTask.execute();
		}

		private boolean atLeastOneStatChosen() {
			for (int i = 0; i < tableModel.getRowCount(); ++i) {
				for (int j = 1; j < tableModel.getColumnCount(); ++j) {
					final Object cell = tableModel.getValueAt(i, j);
					if (cell != null && (boolean) cell)
						return true;
				}
			}
			return false;
		}

		private boolean atLeastOneStatChosen(final int row) {
			for (int j = 1; j < tableModel.getColumnCount(); ++j) {
				final Object cell = tableModel.getValueAt(row, j);
				if (cell != null && (boolean) cell)
					return true;
			}
			return false;
		}

		private int measureTree(final Tree tree) {
			// Resolved once so the metric loop below does not repeat a linear
			// row-header scan (org.scijava.table.Table#getRowIndex) on every write
			final int treeRow = getOrCreateRow(tree.getLabel());
			final TreeStatistics tStats = new TreeStatistics(tree);
			TreeStatistics.setExactMetricMatch(true);
			for (int row = 0; row < tableModel.getRowCount(); ++row) {
				if (!atLeastOneStatChosen(row)) continue;
				final String metric = (String) tableModel.getValueAt(row, 0);
				assignNodeValuesAsNeeded(tree, metric);
				SummaryStatistics summaryStatistics = getSummaryStatistics(tStats, metric);
				final String metricHeader = getMetricHeader(tStats, metric);
				if (summaryStatistics.getN() < 1) {
					table.set(metricHeader, treeRow, "Err");
				} else if (summaryStatistics.getN() == 1) {
					table.set(metricHeader + " [Single value]", treeRow, summaryStatistics.getSum());
				} else {
					processColumnsOfStatChoices(row, summaryStatistics, metricHeader, treeRow);
				}
			}
			tStats.dispose();
			return treeRow;
		}

		// Gets the row index for rowHeader, creating the row if not yet present
		private int getOrCreateRow(final String rowHeader) {
			final int idx = table.getRowIndex(rowHeader);
			return (idx == -1) ? table.insertRow(rowHeader) : idx;
		}

		private void assignNodeValuesAsNeeded(final Tree tree, final String metric) {
			if (TreeStatistics.VALUES.equals(metric) && plugin != null && plugin.accessToValidImageData()
					&& !tree.get(0).hasNodeValues()) {
				SNTUtils.log("Retrieving intensities using the default centerline setting...");
				new PathProfiler(tree, plugin.getDataset()).assignValues();
			}
		}

		private SummaryStatistics getSummaryStatistics(final TreeStatistics tStats, final String metric) {
			try {
				return tStats.getSummaryStats(metric);
			} catch (final IllegalArgumentException | IndexOutOfBoundsException | NullPointerException e) {
				SNTUtils.log(e.getMessage());
				final SummaryStatistics stats = new SummaryStatistics();
				stats.addValue(Double.NaN);
				return stats;
			}
		}

		private String getMetricHeader(final TreeStatistics tStats, final String metric) {
			final String unit = tStats.getUnit(metric);
			return unit.isEmpty() ? metric : metric + " (" + unit + ")";
		}

		private void processColumnsOfStatChoices(final int row, final SummaryStatistics summaryStatistics,
									final String metricHeader, final int treeRow) {
			for (int column = 1; column < tableModel.getColumnCount(); ++column) {
				final Object cell = tableModel.getValueAt(row, column);
				if (cell == null || !(boolean) cell) continue;

				final String measurement = tableModel.getColumnName(column);
				final double value = calculateStatisticalValue(summaryStatistics, measurement);
				table.set(metricHeader + " [" + measurement + "]", treeRow, value);
			}
		}

		private double calculateStatisticalValue(final SummaryStatistics summaryStatistics, final String measurement) {
			return switch (measurement) {
				case MIN -> summaryStatistics.getMin();
				case MAX -> summaryStatistics.getMax();
				case MEAN -> summaryStatistics.getMean();
				case STDDEV -> summaryStatistics.getStandardDeviation();
				case SUM -> summaryStatistics.getSum();
				case CV -> summaryStatistics.getStandardDeviation() / summaryStatistics.getMean();
				case N -> summaryStatistics.getN();
				default -> throw new IllegalArgumentException("[BUG] Unknown statistic: " + measurement);
			};
		}

		class BackgroundTask extends SwingWorker<Object, Integer> {

			private final AbstractButton button;

			BackgroundTask(final AbstractButton button) {
				this.button = button;
				button.setEnabled(false);
			}

			@Override
			protected Void doInBackground() {
				int counter = 0;
				for (final Tree tree : trees) {
					publish(counter++);
					SNTUtils.log("Processing #" + counter +" "+ tree.getLabel());
					final Set<Integer> compartments = tree.getSWCTypes(false);
					if (distinguishCompartments && compartments.size() > 1) {
						for (final int type : compartments)
							measureTree(tree.subTree(type));
					} else {
						final int treeRow = measureTree(tree);
						table.set("No. of compartments", treeRow, compartments.size());
					}
				}
				return null;
			}

			@Override
			protected void process(final List<Integer> chunks) {
				final int i = chunks.getLast();
				bar.setValue(i); // The last value is all we care about
				bar.setString(String.format("Measuring %d/%d reconstruction(s)", i+1, trees.size()));
			}

			@Override
			protected void done() {
				button.setEnabled(true);
				if (table.isEmpty() || (!distinguishCompartments && table.getColumnCount() < 2)) {
					guiUtils.error("Measurements table is empty. Please make sure your choices are valid.");
					return;
				}
				bar.setValue(0);
				bar.setString(String.format("Measuring %d reconstruction(s)", trees.size()));
				updateTable(true);
			}

		}

	}

	/**
	 * A TableCellRenderer that selects, deselects, or shows a mixed state for an entire Boolean
	 * column, using a tri-state checkbox: clicking always selects/deselects the whole column; the
	 * indeterminate ("-") state is shown automatically whenever the column's cells disagree, and is
	 * never itself reachable by clicking the header (see {@link #refreshState()}).
	 * <p>
	 * The checkbox is wrapped in a plain panel rather than being the renderer itself: FlatLaf's
	 * checkbox UI does not reliably honor a border/opaque background set directly on the checkbox
	 * (both get silently swallowed), so the panel -- not the checkbox -- owns the header cell's
	 * border and background, and the checkbox sits on top of it non-opaque.
	 */
	static class SelectAllHeader extends JPanel implements TableCellRenderer {

		@Serial
		private static final long serialVersionUID = 1L;
		private final JTable table;
		private final JTableHeader header;
		private final TableColumnModel tcm;
		private final int targetColumn;
		private final FlatTriStateCheckBox checkBox;

		public SelectAllHeader(final JTable table, final int targetColumn, final String label) {
			super(new FlowLayout(FlowLayout.CENTER, 4, 2));
			this.table = table;
			final TableModel tableModel = table.getModel();
			if (tableModel.getColumnClass(targetColumn) != Boolean.class) {
				throw new IllegalArgumentException("Boolean column required.");
			}
			this.targetColumn = targetColumn;
			this.header = table.getTableHeader();
			this.tcm = table.getColumnModel();
			this.checkBox = new FlatTriStateCheckBox(label);
			// Indeterminate is a display-only state driven by refreshState(): clicking the
			// header must only ever select or deselect the whole column
			checkBox.setAllowIndeterminate(false);
			checkBox.setOpaque(false);
			checkBox.putClientProperty(FlatClientProperties.STYLE_CLASS, "small");
			checkBox.addActionListener(new ActionHandler());
			add(checkBox);
			this.applyUI();
			header.addMouseListener(new MouseHandler());
			setToolTipText("Click to select/deselect entire column");
			refreshState();
		}

		@Override
		public Component getTableCellRendererComponent(final JTable table, final Object value, final boolean isSelected,
				final boolean hasFocus, final int row, final int column) {
			return this;
		}

		/** Recomputes and displays this header's checkbox state from the current column values. */
		void refreshState() {
			// Read row count and values through the model only (never table.getRowCount(), which
			// goes through the RowSorter and can be transiently stale: fireTableDataChanged()
			// notifies listeners in REVERSE registration order, so this listener can run before
			// the RowSorter has processed the same event)
			final TableModel model = table.getModel();
			boolean anyTrue = false;
			boolean anyFalse = false;
			for (int row = 0; row < model.getRowCount(); row++) {
				if (Boolean.TRUE.equals(model.getValueAt(row, targetColumn))) {
					anyTrue = true;
				} else {
					anyFalse = true;
				}
				if (anyTrue && anyFalse) break;
			}
			if (anyTrue && anyFalse) {
				checkBox.setState(FlatTriStateCheckBox.State.INDETERMINATE);
			} else if (anyTrue) {
				checkBox.setState(FlatTriStateCheckBox.State.SELECTED);
			} else {
				checkBox.setState(FlatTriStateCheckBox.State.UNSELECTED);
			}
		}

		private class ActionHandler implements ActionListener {

			@Override
			public void actionPerformed(final ActionEvent e) {
				final boolean select = checkBox.getState() == FlatTriStateCheckBox.State.SELECTED;
				final int[] modelRows = IntStream.range(0, table.getModel().getRowCount()).toArray();
				batchSetValues(table, modelRows, new int[] { targetColumn }, select);
			}
		}

		@Override
		public void updateUI() {
			super.updateUI();
			applyUI();
		}

		private void applyUI() {
			this.setOpaque(true);
			this.setBorder(UIManager.getBorder("TableHeader.cellBorder"));
			this.setBackground(UIManager.getColor("TableHeader.background"));
			this.setForeground(UIManager.getColor("TableHeader.foreground"));
			if (checkBox != null) {
				checkBox.setFont(UIManager.getFont("TableHeader.font"));
				checkBox.setForeground(UIManager.getColor("TableHeader.foreground"));
			}
		}

		private class MouseHandler extends MouseAdapter {

			@Override
			public void mouseClicked(final MouseEvent e) {
				final int viewColumn = header.columnAtPoint(e.getPoint());
				final int modelColumn = tcm.getColumn(viewColumn).getModelIndex();
				if (modelColumn == targetColumn) {
					checkBox.doClick();
				}
			}
		}
	}

	/* see https://stackoverflow.com/questions/16743427/ */
	static class TablePopupMenu extends JPopupMenu {
		@Serial
		private static final long serialVersionUID = 1L;
		private int columnAtClickPoint;
		private final JTable table;

		public TablePopupMenu(final JTable table) {
			super();
			this.table = table;
			addPopupMenuListener(new PopupMenuListener() {

				@Override
				public void popupMenuWillBecomeVisible(final PopupMenuEvent e) {
					SwingUtilities.invokeLater(() -> {
						final Point clickPoint = SwingUtilities.convertPoint(TablePopupMenu.this, new Point(0, 0),
								table);
						columnAtClickPoint = table.columnAtPoint(clickPoint);
						for (final MenuElement element : getSubElements()) {
							if (!(element instanceof JMenuItem))
								continue;
							if (((JMenuItem) element).getText().endsWith("Column")) {
								((JMenuItem) element).setEnabled(columnAtClickPoint > 0);
							}
							if (((JMenuItem) element).getText().endsWith("Row(s)")) {
								((JMenuItem) element).setEnabled(table.getSelectedRowCount() > 0);
							}
						}
					});
				}

				@Override
				public void popupMenuWillBecomeInvisible(final PopupMenuEvent e) {
				}

				@Override
				public void popupMenuCanceled(final PopupMenuEvent e) {
				}

			});
			GuiUtils.addSeparator(this, "Selection of Statistics:");
			JMenuItem mi = new JMenuItem("Select All");
			mi.addActionListener(e -> setAllState(true));
			add(mi);
			mi = new JMenuItem("Select None");
			mi.addActionListener(e -> setAllState(false));
			add(mi);
			addSeparator();
			mi = new JMenuItem("Select This Column");
			mi.addActionListener(e -> setColumnState(true));
			add(mi);
			mi = new JMenuItem("Deselect This Column");
			mi.addActionListener(e -> setColumnState(false));
			add(mi);
			addSeparator();
			mi = new JMenuItem("Select Highlighted Row(s)");
			mi.addActionListener(e -> setSelectedRowsState(true));
			add(mi);
			mi = new JMenuItem("Deselect Highlighted Row(s)");
			mi.addActionListener(e -> setSelectedRowsState(false));
			add(mi);
		}

		static void install(final JTable table, final JScrollPane sp) {
			final TablePopupMenu popup = new TablePopupMenu(table);
			table.setComponentPopupMenu(popup);
			sp.setComponentPopupMenu(popup);
		}

		private void setColumnState(final boolean state) {
			if (columnAtClickPoint == 0)
				// This is the metric String column, we don't want to change this
				return;
			final int modelColumn = table.convertColumnIndexToModel(columnAtClickPoint);
			// Model row count, not table.getRowCount() (the view, via the RowSorter): "all rows"
			// must mean every row that actually exists in the model
			final int[] modelRows = IntStream.range(0, table.getModel().getRowCount()).toArray();
			batchSetValues(table, modelRows, new int[] { modelColumn }, state);
		}

		private void setAllState(final boolean state) {
			// Model row count, not table.getRowCount() (the view, via the RowSorter): "all rows"
			// must mean every row that actually exists in the model
			final int[] modelRows = IntStream.range(0, table.getModel().getRowCount()).toArray();
			// Skip metric String column (col 0)
			final int[] modelColumns = IntStream.range(1, table.getColumnCount())
					.map(table::convertColumnIndexToModel).toArray();
			batchSetValues(table, modelRows, modelColumns, state);
		}

		private void setSelectedRowsState(final boolean state) {
			final int[] modelRows = Arrays.stream(table.getSelectedRows())
					.map(table::convertRowIndexToModel).toArray();
			// Skip metric String column (col 0)
			final int[] modelColumns = IntStream.range(1, table.getColumnCount())
					.map(table::convertColumnIndexToModel).toArray();
			batchSetValues(table, modelRows, modelColumns, state);
		}

	}

	public static void main(final String[] args) {
		final ImageJ ij = new ImageJ();
		ij.ui().showUI();
		GuiUtils.setLookAndFeel();
		final SNTService sntService = ij.get(SNTService.class);
//		final MeasureUI frame = new MeasureUI(Collections.singleton(sntService.demoTree()));
		final MeasureUI frame = new MeasureUI(sntService.demoTrees());
		SwingUtilities.invokeLater(() -> frame.setVisible(true));
	}

}
