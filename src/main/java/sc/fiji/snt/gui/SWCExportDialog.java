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
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Vector;
import java.util.stream.IntStream;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.RowSorter;
import javax.swing.RowSorter.SortKey;
import javax.swing.SortOrder;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableColumnModel;
import javax.swing.table.TableRowSorter;

import com.formdev.flatlaf.icons.FlatOptionPaneWarningIcon;

import ij.ImagePlus;
import sc.fiji.snt.SNTPrefs;
import sc.fiji.snt.SNTUI;
import sc.fiji.snt.SNTUtils;

/**
 * Implements a dialog for exporting SWC files.
 */
@SuppressWarnings("serial")
public class SWCExportDialog extends JDialog {

	static { net.imagej.patcher.LegacyInjector.preinit(); } // required for _every_ class that imports ij. classes

	private boolean succeeded = false;

	private final FilePicker filePicker;
	private final JTable metadataTable;
    private final ImagePlus imp;
	private boolean collapseFileHeader;
	private boolean collapseWarning;

	public SWCExportDialog(final SNTUI ui, final ImagePlus imp, final File suggestedFile) {
		this(ui, imp, suggestedFile, true);
	}

	public SWCExportDialog(final SNTUI ui, final ImagePlus imp, final File suggestedFile, final boolean setVisible) {

		super(ui, "Export To SWC...", true);
		setLocationRelativeTo(ui);
		setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
		this.imp = imp;
		this.loadPrefs(ui.getPrefs());
		filePicker = new FilePicker(FilePicker.SAVE_DIALOG,
				(suggestedFile == null) ? getLastExportFile(ui.getPrefs()) : suggestedFile, "swc");
        final JButton okButton = new JButton("Save");
		okButton.addActionListener(e -> {
			if (filePicker.invalidFileChoiceError())
				return;
			succeeded = true;
			savePrefs(ui.getPrefs());
			dispose();
		});
        final JButton cancelButton = new JButton("Cancel");
		cancelButton.addActionListener(e -> {
			succeeded = false;
			dispose();
		});
		metadataTable = getTable();

		final JPanel buttonsPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 3, 3));
		buttonsPanel.add(okButton);
		buttonsPanel.add(cancelButton);

		setLayout(new GridBagLayout());
		final GridBagConstraints c = new GridBagConstraints();
		c.insets = new Insets(10, 10, 10, 10);
		c.gridx = 0;
		c.gridy = 0;
		c.anchor = GridBagConstraints.LINE_START;
		c.fill = GridBagConstraints.BOTH;
		c.weightx = 1;
		c.weighty = 0;

		add(filePicker, c);
		c.insets.bottom = 20;

		c.gridy++;
		c.weighty = 1;
		add(fileHeaderPanel(), c);

		c.gridy++;
		c.weightx = 0;
		c.weighty = 0;
		add(warningPanel(), c);

		c.gridy++;
		c.weightx = 1;
		add(buttonsPanel, c);

		pack();
		setVisible(setVisible);
	}

	private void loadPrefs(final SNTPrefs prefs) {
		collapseFileHeader = prefs.getBoolean("swce.cheader", true);
		collapseWarning = prefs.getBoolean("swce.lastpath", false);
	}

	private File getLastExportFile(final SNTPrefs prefs) {
		final String pref = prefs.get("swce.lastpath", "");
		return (pref.isEmpty()) ? new File(SNTPrefs.lastknownDir(), "SNT.swc") : new File(pref);
	}

	private void savePrefs(final SNTPrefs prefs) {
		prefs.set("swce.cheader", collapseFileHeader);
		prefs.set("swce.cwarn", collapseWarning);
		prefs.set("swce.lastpath", getFile().getAbsolutePath());
	}

	private CollapsiblePanel fileHeaderPanel() {
		class FHP extends CollapsiblePanel {

			public FHP() {
				super("Detailed file header", new JScrollPane(metadataTable), collapseFileHeader);
			}

			@Override
			public void setCollapsed(final boolean collapse) {
				super.setCollapsed(collapse);
				collapseFileHeader = collapse;
			}
		}
		return new FHP();
	}

	private CollapsiblePanel warningPanel() {
		final JLabel label = GuiUtils.leftAlignedLabel("<HTML>The following data is not stored in SWC files:" //
				+ "<ul>" //
				+ "  <li>Image properties</li>"//
				+ "  <li><i>Fits</i>: Path refinements that do not override original nodes</li>"//
				+ "  <li><i>Fills</i>: All types</li>"//
				+ "  <li>Channel and frame position of path(s)</li>"//
				+ "  <li>Path metadata (tags, colors, etc.)</li>"//
				+ "  <li>Spine/varicosity counts</li>"//
				+ "</ul>", //
				"https://imagej.net/plugins/snt/faq#in-which-format-should-i-save-my-tracings-traces-or-swc", //
				true);
		label.setIcon(new FlatOptionPaneWarningIcon());
		class WP extends CollapsiblePanel {

			public WP() {
				super("Review data loss warnings", label, collapseWarning);
			}

			@Override
			public void setCollapsed(final boolean collapse) {
				super.setCollapsed(collapse);
				collapseWarning = collapse;
			}
		}
		return new WP();
	}

	private HashMap<String, String> getMapProperties() {
		final LinkedHashMap<String, String> props = new LinkedHashMap<>();
		// author details
		props.put("CONTRIBUTOR", System.getProperty("user.name"));
		props.put("REFERENCE", "");
		// sample details
		props.put("CREATURE", "");
		props.put("SEX", "");
		props.put("AGE", "");
		props.put("CONDITION", "");
		props.put("MICROSCOPY", "");
		props.put("SLICING", "");
		// cell details
		props.put("LABEL", "");
		props.put("REGION", "");
		props.put("CLASS", "");
		// image details
		props.put("ORIGINAL_IMAGE", getImageDetails());
		props.put("VOXEL_SIZE", getVoxelSize() + " " + getVoxelUnit());
		props.put("COORDINATE", getVoxelUnit());
		// other
		props.put("BRAIN_SPACE", "");
		props.put("ORIGINAL_SOURCE", "SNT v" + SNTUtils.getReadableVersion());
		props.put("VERSION_DATE",
				LocalDateTime.of(LocalDate.now(), LocalTime.now().truncatedTo(ChronoUnit.SECONDS)).toString());
		return props;
	}

	private String getKeyDescription(final String key) {
		switch (key) {
		// author details
		case "CONTRIBUTOR":
			return "Author(s), dataset, or lab of origin";
		case "REFERENCE":
			return "DOI or bibliographic citation for document describing data";
		// sample details
		case "CREATURE":
			return "Animal species and strain or genotype";
		case "SEX":
			return "sex of animal";
		case "AGE":
			return "Age of animal";
		case "CONDITION":
			return "The experimental group (e.g., WT control)";
		case "MICROSCOPY":
			return "Imaging modality, objective type, magnification, etc.";
		case "SLICING":
			return "Histological processing details. E.g., Slicing direction and thickness";
		// cell details
		case "LABEL":
			return "The labeling or staining";
		case "REGION":
			return "The anatomical region of the cell body";
		case "CLASS":
			return "Cell type or cell's identifying features";
		// image details
		case "ORIGINAL_IMAGE":
			return "Details on image associated with this reconstruction";
		case "VOXEL_SIZE":
			return "Physical dimensions of voxel size (W×H×D) of ORIGINAL_IMAGE";
		case "COORDINATE":
			return "Physical units of coordinates and radii";
		// other
		case "BRAIN_SPACE":
			return "Registration details to a reference brain";
		case "ORIGINAL_SOURCE":
			return "Details on the tracing software";
		case "VERSION_DATE":
			return "Date of export";
		default:
			return null;
		}
	}

	private String getVoxelSize() {
		return (imp == null) ? ""
				: SNTUtils.formatDouble(imp.getCalibration().pixelWidth, 3) + "×"
						+ SNTUtils.formatDouble(imp.getCalibration().pixelHeight, 3) + "×"
						+ SNTUtils.formatDouble(imp.getCalibration().pixelDepth, 3);
	}

	private String getVoxelUnit() {
		return (imp == null) ? "" : SNTUtils.getSanitizedUnit(imp.getCalibration().getUnit());
	}

	private String getImageDetails() {
		if (imp == null)
			return "";
		final StringBuilder sb = new StringBuilder(imp.getTitle());
		sb.append("; ").append(imp.getBitDepth()).append("-bit; ").append(imp.getWidth()).append("×")
				.append(imp.getHeight()).append("px, ").append(imp.getNSlices()).append(" Z-plane(s), ")
				.append(imp.getNChannels()).append(" channel(s), ").append(imp.getNFrames()).append(" frame(s)");
		return sb.toString();
	}

	private DefaultTableModel getModel() {
		final HashMap<String, String> props = getMapProperties();
		final Vector<Vector<String>> dataVector = new Vector<Vector<String>>(props.size());
		props.forEach((k, v) -> {
			final Vector<String> data = new Vector<>();
			data.add((String) k);
			data.add((String) v);
			dataVector.add(data);
		});
		final Vector<String> headers = new Vector<>(2);
		headers.add("Descriptor Tag");
		headers.add("Value/Information");
		final DefaultTableModel model = new DefaultTableModel(dataVector, headers);
		return model;
	}

	private DefaultTableCellRenderer getCellRenderer() {
		class CellRenderer extends DefaultTableCellRenderer {

			@Override
			public Component getTableCellRendererComponent(final JTable table, final Object value,
					final boolean isSelected, final boolean hasFocus, final int row, final int column) {
				final int r = table.getRowSorter().convertRowIndexToModel(row);
				setToolTipText(getKeyDescription((String) table.getModel().getValueAt(r, 0)));
				return super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
			}
		}
		return new CellRenderer();
	}

	private JTable getTable() {
		final DefaultTableModel model = getModel();
		final JTable table = new JTable(model);
		table.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
		table.setAutoCreateRowSorter(true);
		table.getTableHeader().setReorderingAllowed(false);
		table.setShowGrid(false);
		table.setShowHorizontalLines(true);

		// https://stackoverflow.com/a/29041101
		final TableRowSorter<DefaultTableModel> sorter = new TableRowSorter<>(model);
		table.setRowSorter(sorter);
		table.getTableHeader().addMouseListener(new MouseAdapter() {
			private SortOrder currentOrder = SortOrder.UNSORTED;
			private int lastCol = -1;

			@Override
			public void mouseClicked(final MouseEvent e) {
				int column = table.getTableHeader().columnAtPoint(e.getPoint());
				column = table.convertColumnIndexToModel(column);
				if (column != lastCol) {
					currentOrder = SortOrder.UNSORTED;
					lastCol = column;
				}
				final RowSorter<?> sorter = table.getRowSorter();
				final List<SortKey> sortKeys = new ArrayList<>();
				if (e.getButton() == MouseEvent.BUTTON1) {
					switch (currentOrder) {
					case UNSORTED:
						sortKeys.add(new RowSorter.SortKey(column, currentOrder = SortOrder.ASCENDING));
						break;
					case ASCENDING:
						sortKeys.add(new RowSorter.SortKey(column, currentOrder = SortOrder.DESCENDING));
						break;
					case DESCENDING:
						sortKeys.add(new RowSorter.SortKey(column, currentOrder = SortOrder.UNSORTED));
						break;
					}
					sorter.setSortKeys(sortKeys);
				}
			}
		});

		// freeze first column
		final TableColumnModel columnModel = table.getColumnModel();
		int width = 30;
		for (int row = 0; row < table.getRowCount(); row++) {
			final TableCellRenderer renderer = table.getCellRenderer(row, 0);
			final Component comp = table.prepareRenderer(renderer, row, 0);
			width = Math.max(comp.getPreferredSize().width + 1, width);
		}
		columnModel.getColumn(0).setPreferredWidth(width + 6);
		columnModel.getColumn(0).setMinWidth(width + 6);
		columnModel.getColumn(0).setMaxWidth(width + 6);

		// add tooltips
		table.setDefaultRenderer(Object.class, getCellRenderer());

		// add editing commands
		final JPopupMenu popupMenu = new JPopupMenu();
		table.setComponentPopupMenu(popupMenu);
		JMenuItem mi = new JMenuItem("Append New Entry");
		mi.addActionListener(e -> {
			((DefaultTableModel) table.getModel()).addRow(new String[] { "", "" });
			table.clearSelection();
			table.addRowSelectionInterval(table.getRowCount() - 1, table.getRowCount() - 1);
			table.scrollRectToVisible(table.getCellRect(table.getRowCount() - 1, 0, true));
		});
		popupMenu.add(mi);
		mi = new JMenuItem("Remove Selected Entries");
		mi.addActionListener(e -> { // https://stackoverflow.com/a/45210082
			final DefaultTableModel mdl = (DefaultTableModel) table.getModel();
			IntStream.of(table.getSelectedRows()).boxed().sorted(Collections.reverseOrder())
					.map(table::convertRowIndexToModel) // support for sorted table
					.forEach(mdl::removeRow);
		});
		popupMenu.add(mi);
		popupMenu.addSeparator();
		mi = new JMenuItem("Reset To Defaults");
		mi.addActionListener(e -> table.setModel(getModel()));
		popupMenu.add(mi);

		return table;
	}

	public boolean succeeded() {
		return succeeded;
	}

	public String getFileHeader() {
		if (collapseFileHeader)
			return null;
		final StringBuilder sb = new StringBuilder();
		for (int row = 0; row < metadataTable.getRowCount(); row++) {
			final String key = metadataTable.getModel().getValueAt(row, 0).toString();
			if (key == null || key.isBlank())
				continue;
			final String value = metadataTable.getModel().getValueAt(row, 1).toString().replace("\t", "");
			sb.append("# ").append(key.trim()).append(":");
			if (!value.isBlank())
				sb.append("\t").append(value);
			if (row < metadataTable.getRowCount() - 1)
				sb.append(System.lineSeparator());
		}
		return sb.toString();
	}

	public File getFile() {
		return filePicker.getFile();
	}

}
