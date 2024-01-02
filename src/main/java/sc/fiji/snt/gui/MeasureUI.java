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

import java.awt.*;
import java.awt.event.*;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.List;

import javax.swing.*;
import javax.swing.event.*;
import javax.swing.table.*;

import org.apache.commons.math3.stat.descriptive.SummaryStatistics;
import org.scijava.Context;
import org.scijava.plugin.Parameter;
import org.scijava.prefs.PrefService;

import com.jidesoft.swing.CheckBoxList;
import com.jidesoft.swing.ListSearchable;

import net.imagej.ImageJ;
import sc.fiji.snt.SNT;
import sc.fiji.snt.SNTService;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.Tree;
import sc.fiji.snt.analysis.PathProfiler;
import sc.fiji.snt.analysis.SNTTable;
import sc.fiji.snt.analysis.TreeStatistics;


/**
 * @author Cameron Arshadi
 * @author Tiago Ferreira
 */
public class MeasureUI extends JFrame {

	private static final long serialVersionUID = 6565638887510865592L;
	private static final String MIN = "Min";
	private static final String MAX = "Max";
	private static final String MEAN = "Mean";
	private static final String STDDEV = "SD";
	private static final String SUM = "Sum";
	private static final String N = "N";
	private static final String[] allFlags = new String[] { MIN, MAX, MEAN, STDDEV, SUM, N };
	private static final Class<?>[] columnClasses = new Class<?>[] { String.class, Boolean.class, Boolean.class,
			Boolean.class, Boolean.class, Boolean.class, Boolean.class };
	public static final List<MeasureUI> instances = new ArrayList<>();

	@Parameter
	private PrefService prefService;
	@Parameter
	private SNTService sntService;

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
		if (plugin.getUI() != null) {
			//FIXME API limitation: it should be possible to access 
			// SNTUI#getTable() without the need of sntService. This
			// may cause problems if multiple instances are running
			setTable(sntService.getTable());
		}
	}

	private MeasureUI(final Context context, final Collection<Tree> trees) {
		super("SNT Measurements");
		context.inject(this);
		guiUtils = new GuiUtils(this);
		final MeasurePanel panel = new MeasurePanel(trees);
		add(panel);
		pack();
		instances.add(this);
		setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
		addWindowListener(new WindowAdapter() {
			@Override
			public void windowClosing(final WindowEvent e) {
				final boolean exit = backgroundTask == null || backgroundTask.isDone() || guiUtils.getConfirmation(
						"Measurements are still being retrieved. Interrupt nevertheless?", "Interrupt?");
				if (exit) {
					panel.savePreferences();
					instances.remove(MeasureUI.this);
					if (backgroundTask != null && !backgroundTask.isDone()) {
						backgroundTask.cancel(true);
						backgroundTask.done();
					}
					dispose();
				}
			}
		});
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

	private void saveTable() {
		if (table == null || table.isEmpty()) {
			guiUtils.error("Measurements table is empty.");
			return;
		}
		final File dir = (lastDirPath == null) ? new File(System.getProperty("user.home")) : new File(lastDirPath);
		final File out = guiUtils.getSaveFile("Save Table", new File(dir, "SNT_Measurements.csv"), "csv");
		if (out == null)
			return;
		try {
			table.save(out);
		} catch (final IOException e1) {
			guiUtils.error(e1.getMessage());
			e1.printStackTrace();
		}
		lastDirPath = out.getParent();
	}

	class MeasurePanel extends JPanel {

		private static final long serialVersionUID = 1L;
		private final CheckBoxList metricList;
		private final DefaultTableModel statsTableModel;

		@SuppressWarnings("unchecked")
		MeasurePanel(final Collection<Tree> trees) {

			// Stats table
			final JTable statsTable = new JTable(new DefaultTableModel() {

				private static final long serialVersionUID = 1L;

				@Override
				public Class<?> getColumnClass(final int column) {
					return columnClasses[column];
				}

				@Override
				public boolean isCellEditable(final int row, final int column) {
					return column != 0;
				}
			});

			// initialize table mode.
			statsTableModel = (DefaultTableModel) statsTable.getModel();
			statsTableModel.addColumn("Chosen Metric");

			// tweak table
			statsTable.setAutoCreateRowSorter(true);
			statsTable.setAutoResizeMode(JTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS);
			for (final String metric : allFlags) {
				statsTableModel.addColumn(metric);
			}
			for (int i = 1; i < statsTable.getColumnCount(); ++i) {
				statsTable.getColumnModel().getColumn(i)
						.setHeaderRenderer(new SelectAllHeader(statsTable, i, statsTable.getColumnName(i)));
			}
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
					// https://stackoverflow.com/a/5741867
					statsTable.scrollRectToVisible(statsTable.getCellRect(statsTable.getRowCount() - 1, 0, true));
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
				// FIXME: this is slow, but fast enough for a reasonable no. of metrics!?
				if (!e.getValueIsAdjusting()) {
					final List<Object> selectedMetrics = new ArrayList<>(
							Arrays.asList(metricList.getCheckBoxListSelectedValues()));
					addMetricsToStatsTableModel(selectedMetrics);
				}
			});

			// searchable
			final SNTSearchableBar searchableBar = new SNTSearchableBar(new ListSearchable(metricList),
					" Find... (" + allMetrics.size() + " metrics)");
			searchableBar.setVisibleButtons(SNTSearchableBar.SHOW_SEARCH_OPTIONS | SNTSearchableBar.SHOW_HIGHLIGHTS
					| SNTSearchableBar.SHOW_NAVIGATION);
			searchableBar.setVisible(true);
			searchableBar.setHighlightAll(true);
			searchableBar.setGuiUtils(guiUtils);

			// progress bar
			bar = new JProgressBar();
			bar.setVisible(false);
			bar.setFocusable(false);

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
			add(new JScrollPane(metricList), c);

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

			final JButton runButton = new JButton("Measure " + trees.size() + " Reconstruction(s)");
			runButton.addActionListener(new GenerateTableAction(trees, statsTableModel));
			final JButton optionsButton = optionsButton(trees);
			GuiUtils.equalizeHeight(runButton, optionsButton);
			final JPanel buttonPanel = new JPanel();
			buttonPanel.setLayout(new BoxLayout(buttonPanel, BoxLayout.LINE_AXIS));
			//bar.getPreferredSize().height = runButton.getPreferredSize().height;
			buttonPanel.add(Box.createHorizontalGlue());
			buttonPanel.add(bar);
			buttonPanel.add(Box.createHorizontalGlue());
			buttonPanel.add(optionsButton);
			buttonPanel.add(runButton);
			c.gridx = 1;
			c.gridy = 1;
			c.gridwidth = 1;
			c.weighty = 0.0; // do not allow panel to fill height when resizing pane
			add(buttonPanel, c);
		}

		private JButton optionsButton(final Collection<Tree> trees) {
			final JButton optionsButton = new JButton(IconFactory.getButtonIcon(IconFactory.GLYPH.OPTIONS, 1.1f));
			optionsButton.setToolTipText("Options & Utilities");
			final JPopupMenu optionsMenu = new JPopupMenu();
			GuiUtils.addSeparator(optionsMenu, "Options:");
			final JCheckBoxMenuItem jcmi1 = new JCheckBoxMenuItem("Distinguish Compartments",
					distinguishCompartments);
			jcmi1.addActionListener(e -> distinguishCompartments = jcmi1.isSelected());
			jcmi1.setToolTipText("Whether measurements should be grouped by cellular\n"
					+ "compartment (e.g., \"axon\", \"dendrites\", etc.)");
			optionsMenu.add(jcmi1);
			final JCheckBoxMenuItem jcmi4  = new JCheckBoxMenuItem("Debug mode", SNTUtils.isDebugMode());
			jcmi4.addActionListener(e -> {
				SNTUtils.setDebugMode(jcmi4.isSelected());
				if (plugin != null && plugin.getUI() != null) {
					plugin.getUI().setEnableDebugMode(jcmi4.isSelected());
				}
			});
			optionsMenu.add(jcmi4);
			GuiUtils.addSeparator(optionsMenu, "Measurements Table:");
			JMenuItem jmi = new JMenuItem("Clear Measurements");
			jmi.addActionListener(e -> wipeTable());
			optionsMenu.add(jmi);
			jmi = new JMenuItem("Save Measurements...");
			jmi.addActionListener(e -> saveTable());
			jmi.setToolTipText("Save measurements table. Note that tables can always\n"
					+ "be saved using the 'Save Tables and Analysis Plot' command");
			optionsMenu.add(jmi);
			jmi = new JMenuItem("Summarize Existing Results");
			jmi.addActionListener(e -> {
				if (table == null || table.isEmpty()) {
					guiUtils.error("Measurements table is empty.");
					return;
				}
				table.removeSummary();
				table.summarize();
				updateTable(false);
			});
			jmi.setToolTipText("Computes Mean, SD, Sum, etc. for existing measurements");
			optionsMenu.add(jmi);
			GuiUtils.addSeparator(optionsMenu, "Utilities:");
			jmi = new JMenuItem("List Cell(s) Being Measured...");
			jmi.addActionListener(e -> showDetails(trees));
			optionsMenu.add(jmi);
			jmi = new JMenuItem("Render Cell(s) Being Measured...");
			jmi.addActionListener(e -> {
				final String[] choices = { //
						"2D montage", //
						"2D scene", //
						"2D scene (centered positions)", //
						"2D skeletons (centered positions)", //
						"3D scene", //
						"3D scene (centered positions)" };
				final String[] desc = { //
						"Multipanel montage (1 cell per panel) displayed in Reconstruction Plotter", //
						"Reconstruction Plotter scene displaying cells at their absolute coordinates", //
						"Reconstruction Plotter scene in which all reconstructions are displayed from a common coordinate", //
						"Raster image in which all reconstructions are displayed from a common coordinate", //
						"Reconstruction Viewer scene displaying cells at their absolute coordinates", //
						"Reconstruction Viewer scene in which all reconstructions are displayed from a common coordinate" };
				final String defChoice = (plugin == null) ? choices[2]
						: plugin.getPrefs().getTemp("measure-render", choices[2]);
				final String choice = guiUtils.getChoice("How would you like to render the cell(s) being measured?",
						"Render Options", choices, desc, defChoice);
				if (choice == null)
					return;
				if (plugin != null)
					plugin.getPrefs().setTemp("measure-render", choice);
				SNTUtils.render(trees, choice);
			});
			optionsMenu.add(jmi);
			GuiUtils.addSeparator(optionsMenu, "Help:");
			jmi = new JMenuItem("Quick Guide...");
			jmi.addActionListener(e -> showHelp());
			optionsMenu.add(jmi);
			optionsMenu.add(
					GuiUtils.menuItemTriggeringURL("Definition of Metrics", "https://imagej.net/plugins/snt/metrics"));
			optionsButton.addMouseListener(new MouseAdapter() {

				@Override
				public void mousePressed(final MouseEvent e) {
					optionsMenu.show(optionsButton, optionsButton.getWidth() / 2, optionsButton.getHeight() / 2);
				}
			});
			return optionsButton;
		}

		private void showDetails(final Collection<Tree> trees) {
			final StringBuilder sb = new StringBuilder("<HMTL><div align='center'>");
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
			GuiUtils.showHTMLDialog(sb.toString(), "" + trees.size() + " Tree(s) Being Measured");
		}

		private void showHelp() {
			GuiUtils.showHTMLDialog("<p><b>How to Measure Reconstructions:</b></p>"//
					+ "<ol>"//
					+ "<li>Select metrics on the left panel. Use the search box to highlight items and "//
					+ "options in the contextual menu to apply selections</li>"//
					+ "<li>Select statistics on the right panel (NB: clicking on a column header selects "//
					+ "all of its rows)</li>"//
					+ "<li>Adjust options in the gear menu</li>"//
					+ "<li>Press 'Measure'</li>"//
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
					+ "un-fitted paths because paths are fitted independently from one another and "//
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
					else
						stats.add((statsTableModel.getValueAt(row, column)).toString());
				}
			}
			prefService.put(getClass(), "metrics", metrics);
			prefService.put(getClass(), "stats", stats);
		}

		private void addMetricsToStatsTableModel(final List<?> metrics) {
			final List<Integer> metricIndicesToRemove = new ArrayList<>();
			final List<Object> existingMetrics = new ArrayList<>();
			for (int i = 0; i < statsTableModel.getRowCount(); ++i) {
				final Object existingMetric = statsTableModel.getValueAt(i, 0);
				if (!metrics.contains(existingMetric)) {
					metricIndicesToRemove.add(i);
				} else {
					existingMetrics.add(existingMetric);
				}
			}
			removeRows(statsTableModel, metricIndicesToRemove);
			metrics.removeAll(existingMetrics);
			final boolean[] defChoices = getLastChosenStats();
			for (final Object metric : metrics)
				statsTableModel.addRow(new Object[] { metric, defChoices[0], defChoices[1], defChoices[2],
						defChoices[3], defChoices[4], defChoices[5] });
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

		private void removeRows(final DefaultTableModel model, final List<Integer> indices) {
			Collections.sort(indices);
			for (int i = indices.size() - 1; i >= 0; i--) {
				model.removeRow(indices.get(i));
			}
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
			mi = new JMenuItem("Define Highlighted Metric...", IconFactory.getMenuIcon(IconFactory.GLYPH.QUESTION));
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
			for (int i = 0; i < indices.length; i++) {
				if (select)
					metricList.addCheckBoxListSelectedIndex(indices[i]);
				else
					metricList.removeCheckBoxListSelectedIndex(indices[i]);
			}
			metricList.setValueIsAdjusting(false);
		}

		private void getHelpOnHighlightedMetric() {
			final int idx = metricList.getSelectedIndex();
			if (idx < 0) {
				UIManager.getLookAndFeel().provideErrorFeedback(this);
				return;
			}
			String metric = metricList.getModel().getElementAt(idx).toString();
			switch (metric) {
			case TreeStatistics.X_COORDINATES:
			case TreeStatistics.Y_COORDINATES:
			case TreeStatistics.Z_COORDINATES:
				metric = "xyz-coordinates";
				break;
			default:
				metric = metric.replace(".", "").replace(":", "")//
						.replace("(", "").replace(")", "") //
						.replace("[", "").replace("]", "") //
						.replace("/", "").replace("\\", "") //
						.replace(" ", "-");
			}
			GuiUtils.openURL("https://imagej.net/plugins/snt/metrics#" + metric.toLowerCase());
		}

	}

	private class GenerateTableAction extends AbstractAction {

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

		private void measureTree(final Tree tree) {

			final TreeStatistics tStats = new TreeStatistics(tree);
			TreeStatistics.setExactMetricMatch(true);

			for (int row = 0; row < tableModel.getRowCount(); ++row) {

				if (!atLeastOneStatChosen(row))
					continue;

				final String metric = (String) tableModel.getValueAt(row, 0);

				if (TreeStatistics.VALUES.equals(metric) && plugin != null && plugin.accessToValidImageData()
						&& !tree.get(0).hasNodeValues()) {
					SNTUtils.log("Retrieving intensities using the default centerline setting...");
					new PathProfiler(tree, plugin.getLoadedDataAsImp()).assignValues();
				}

				SummaryStatistics summaryStatistics;
				try {
					summaryStatistics  = tStats.getSummaryStats(metric);
				} catch (final IllegalArgumentException | IndexOutOfBoundsException | NullPointerException e) {
					// e.g. Node values cannot be assigned or a convex hull of a single-path tree
					summaryStatistics = new SummaryStatistics();
					summaryStatistics.addValue(Double.NaN);
					SNTUtils.log(e.getMessage());
				}
				final String unit = tStats.getUnit(metric);
				final String metricHeader = (unit.length() > 1) ? metric + " (" + unit + ")" : metric;
				if (summaryStatistics.getN() == 1 ) {
					table.set(metricHeader + " [Single value]", tree.getLabel(), summaryStatistics.getSum());
					continue;
				}
				for (int column = 1; column < tableModel.getColumnCount(); ++column) {
					final Object cell = tableModel.getValueAt(row, column);
					if (cell == null || !(boolean) cell)
						continue;
					final String measurement = tableModel.getColumnName(column);
					final double value;
					switch (measurement) {
					case MIN:
						value = summaryStatistics.getMin();
						break;
					case MAX:
						value = summaryStatistics.getMax();
						break;
					case MEAN:
						value = summaryStatistics.getMean();
						break;
					case STDDEV:
						value = summaryStatistics.getStandardDeviation();
						break;
					case SUM:
						value = summaryStatistics.getSum();
						break;
					case N:
						value = summaryStatistics.getN();
						break;
					default:
						throw new IllegalArgumentException("[BUG] Unknown statistic: " + measurement);
					}
					table.set(metricHeader + " [" + measurement + "]", tree.getLabel(), value);
				}
			}
		}


		class BackgroundTask extends SwingWorker<Object, Integer> {

			private final AbstractButton button;

			BackgroundTask(final AbstractButton button) {
				this.button = button;
				button.setEnabled(false);
				bar.setMinimum(0);
				bar.setMaximum(trees.size());
				bar.setVisible(true);
			}

			@Override
			protected Void doInBackground() throws Exception {
				int counter = 0;
				for (final Tree tree : trees) {
					publish(counter++);
					SNTUtils.log("Processing #" + counter +" "+ tree.getLabel());
					final Set<Integer> compartments = tree.getSWCTypes(false);
					if (distinguishCompartments && compartments.size() > 1) {
						for (final int type : compartments)
							measureTree(tree.subTree(type));
					} else {
						measureTree(tree);
						table.set("No. of compartments", tree.getLabel(), compartments.size());
					}
				}
				return null;
			}

			@Override
			protected void process(final List<Integer> chunks) {
				final int i = chunks.get(chunks.size() - 1);
				bar.setValue(i); // The last value is all we care about
			}

			@Override
			protected void done() {
				button.setEnabled(true);
				bar.setVisible(false);
				if (table.isEmpty() || (!distinguishCompartments && table.getColumnCount() < 2)) {
					guiUtils.error("Measurements table is empty. Please make sure your choices are valid.");
					return;
				}
				updateTable(true);
			}

		}
	 
	}

	/**
	 * A TableCellRenderer that selects all or none of a Boolean column.
	 * <p>
	 * Adapted from https://stackoverflow.com/a/7137801
	 */
	static class SelectAllHeader extends JToggleButton implements TableCellRenderer {

		private static final long serialVersionUID = 1L;
		private static final String ALL_SELECTED = "✓ ";
		private final String label;
		private final JTable table;
		private final TableModel tableModel;
		private final JTableHeader header;
		private final TableColumnModel tcm;
		private final int targetColumn;
		private int viewColumn;

		public SelectAllHeader(final JTable table, final int targetColumn, final String label) {
			super(label);
			this.label = label;
			this.table = table;
			this.tableModel = table.getModel();
			if (tableModel.getColumnClass(targetColumn) != Boolean.class) {
				throw new IllegalArgumentException("Boolean column required.");
			}
			this.targetColumn = targetColumn;
			this.header = table.getTableHeader();
			this.tcm = table.getColumnModel();
			this.applyUI();
			this.addItemListener(new ItemHandler());
			header.addMouseListener(new MouseHandler());
			setToolTipText("Click to toggle entire column");

			// FIXME: This does not appear to work when the table
			// has multiple of these listeners. Multiple columns get
			// selected.

			// tableModel.addTableModelListener(new ModelHandler());
		}

		@Override
		public Component getTableCellRendererComponent(final JTable table, final Object value, final boolean isSelected,
				final boolean hasFocus, final int row, final int column) {
			return this;
		}

		private class ItemHandler implements ItemListener {

			@Override
			public void itemStateChanged(final ItemEvent e) {
				final boolean state = e.getStateChange() == ItemEvent.SELECTED;
				setText((state) ? ALL_SELECTED + label : label);
				for (int r = 0; r < table.getRowCount(); r++) {
					table.setValueAt(state, r, viewColumn);
				}
			}
		}

		@Override
		public void updateUI() {
			super.updateUI();
			applyUI();
		}

		private void applyUI() {
			this.setFont(UIManager.getFont("TableHeader.font"));
			this.setBorder(UIManager.getBorder("TableHeader.cellBorder"));
			this.setBackground(UIManager.getColor("TableHeader.background"));
			this.setForeground(UIManager.getColor("TableHeader.foreground"));
		}

		private class MouseHandler extends MouseAdapter {

			@Override
			public void mouseClicked(final MouseEvent e) {
				viewColumn = header.columnAtPoint(e.getPoint());
				final int modelColumn = tcm.getColumn(viewColumn).getModelIndex();
				if (modelColumn == targetColumn) {
					doClick();
				}
			}
		}
	}

	/* see https://stackoverflow.com/questions/16743427/ */
	static class TablePopupMenu extends JPopupMenu {
		private static final long serialVersionUID = 1L;
		private int rowAtClickPoint;
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
						rowAtClickPoint = table.rowAtPoint(clickPoint);
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
			for (int i = 0; i < table.getRowCount(); i++) {
				table.setValueAt(state, i, columnAtClickPoint);
			}
		}

		@SuppressWarnings("unused")
		private void setRowState(final boolean state) {
			// Boolean columns start at idx == 1
			for (int i = 1; i < table.getColumnCount(); i++) {
				table.setValueAt(state, rowAtClickPoint, i);
			}
		}

		private void setAllState(final boolean state) {
			for (int row = 0; row < table.getRowCount(); row++) {
				for (int col = 1; col < table.getColumnCount(); col++) { // Skip metric String column
					table.setValueAt(state, row, col);
				}
			}
		}

		private void setSelectedRowsState(final boolean state) {
			final int[] selectedIndices = table.getSelectedRows();
			for (int idx = 0; idx < selectedIndices.length; idx++) {
				for (int col = 1; col < table.getColumnCount(); col++) { // Skip metric String column
					table.setValueAt(state, selectedIndices[idx], col);
				}
			}
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
