/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2025 Fiji developers.
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

package sc.fiji.snt;

import ij.ImagePlus;
import ij.gui.Roi;
import ij.plugin.frame.RoiManager;
import sc.fiji.snt.analysis.*;
import sc.fiji.snt.annotation.BrainAnnotation;
import sc.fiji.snt.gui.GuiUtils;
import sc.fiji.snt.gui.IconFactory;
import sc.fiji.snt.util.ColorMaps;
import sc.fiji.snt.util.PointInImage;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Implements the <i>Delineation Analysis</i> pane.
 *
 * @author Tiago Ferreira
 */
public class DelineationsManager {

    static { net.imagej.patcher.LegacyInjector.preinit(); } // required for _every_ class that imports ij. classes

    private static final Color DEF_COLOR = Color.LIGHT_GRAY;
    private static final String UNAFFECTED_LABEL = "Unaffected paths";
    private static final String NON_DELINEATED_LABEL = "Non-delineated";
    private final SNTUI sntui;
    private final PathAndFillManager pafm;
    private final List<Delineation> delineations;
    private final JPanel delineationsPanel;
    private Color fallbackColor;
    private SNTTable table;

    public DelineationsManager(final SNTUI sntui) {
        this.sntui = sntui;
        pafm = sntui.plugin.getPathAndFillManager();
        fallbackColor = DEF_COLOR;
        final int n = 10;
        delineations = new ArrayList<>(n);
        delineationsPanel = new JPanel();
        delineationsPanel.setLayout(new BoxLayout(delineationsPanel, BoxLayout.Y_AXIS));
        final Color[] colors = defaultDelineationColors(n);
        for (int i = 1; i <= n; i++) {
            final Delineation delineation = new Delineation(i, colors[i - 1]);
            delineations.add(delineation);
            delineationsPanel.add(delineation.getWidget());
        }
    }

    private Color[] defaultDelineationColors(final int n) {
        return ColorMaps.glasbeyColorsAWT(n);
    }

    protected JPanel getPanel() {
        final JPanel container = SNTUI.InternalUtils.getTab();
        final GridBagConstraints gbc = GuiUtils.defaultGbc();
        gbc.fill = GridBagConstraints.BOTH;
        SNTUI.InternalUtils.addSeparatorWithURL(container, "Delineations:", true, gbc);
        gbc.gridy++;
        final String msg = """
                Delineations allow measuring proportions of paths within other structures defined by ROIs \
                or neuropil annotations (e.g., cortical layers, biomarkers, or counterstaining landmarks). \
                Delineations do not persist across sessions and must be exported manually.
                
                To create a delineation: Pause SNT, select an area ROI, and click on the "Assign" button. \
                Alternatively, use the import entries in the options menu.
                """;
        gbc.weighty = 0.1;
        container.add(GuiUtils.longSmallMsg(msg, container), gbc);
        gbc.weighty = 0;
        final JScrollPane sp = new JScrollPane(delineationsPanel);
        sp.setMinimumSize(delineationsPanel.getPreferredSize());
        sp.setBorder(null);
        gbc.gridy++;
        container.add(sp, gbc);
        gbc.gridy++;
        container.add(outsideColorAndAddMoreEntriesWidget(), gbc);
        gbc.insets = new Insets(5, 0, 5, 0);
        gbc.gridy++;
        container.add(bottomToolBar(), gbc);
        return container;
    }

    private JToolBar outsideColorAndAddMoreEntriesWidget() {
        final JToolBar toolbar = new JToolBar();
        final JButton addButton = new JButton(IconFactory.buttonIcon(IconFactory.GLYPH.PLUS, 1.1f));
        addButton.setToolTipText("Add new entries");
        addButton.addActionListener(e -> {
            final Double n = sntui.guiUtils.getDouble("Add how many new entries?",
                    "Add Delineation Entries", 1);
            if (n == null) return;
            if (Double.isNaN(n) || n < 1 || n > 2000) // arbitrary limit to avoid overloading the GUI
                sntui.guiUtils.error("Invalid number of entries. A value between 1 and 2000 is expected.");
            else
                addToCapacity(n.intValue());
        });
        toolbar.add(addButton);
        toolbar.addSeparator();
        final JLabel label = new JLabel(" Outside color:");
        label.setToolTipText("Fallback color for non-delineated sections");
        toolbar.add(label);
        final JButton fColorButton = new JButton(IconFactory.accentIcon(fallbackColor, true));
        fColorButton.addActionListener(e -> {
            final Color newColor = sntui.guiUtils.getColor("Non-delineated Color", fallbackColor, (String[]) null);
            if (newColor == null || newColor.equals(fallbackColor)) return;
            final Delineation delineation = getDelineation(newColor);
            if (delineation != null) {
                sntui.guiUtils.error("Color is already used by " + delineation.name + ".");
                return;
            }
            setAndApplyFallbackColor(newColor, fColorButton);
        });
        toolbar.add(fColorButton);
        final JButton fColorUndoButton = GuiUtils.Buttons.undo();
        fColorUndoButton.addActionListener(e -> {
            fallbackColor = DEF_COLOR;
            setAndApplyFallbackColor(fallbackColor, fColorButton);
        });
        toolbar.add(fColorUndoButton);
        return toolbar;
    }

    private void expandCapacityTo(final int n) {
        if (n > delineations.size()) addToCapacity(n - delineations.size());
    }

    private void addToCapacity(final int n) {
        final int currentN = delineations.size();
        final Color[] colors = defaultDelineationColors(Math.min(256, currentN + n));
        final long lastLabel = delineations.getLast().label;
        int colorIdx = 0;
        for (int i = 0; i < n; i++) {
            final Delineation del = new Delineation(lastLabel + i + 1, colors[colorIdx++]);
            delineations.add(del);
            delineationsPanel.add(del.getWidget());
            if (colorIdx > 255) colorIdx = 0;
        }
        delineationsPanel.revalidate();
        delineationsPanel.repaint();
    }

    private void setAndApplyFallbackColor(final Color newFallbackColor, final AbstractButton fallbackColorButton) {
        fallbackColor = newFallbackColor;
        fallbackColorButton.setIcon(IconFactory.accentIcon(fallbackColor, true));
        delineations.forEach(d -> d.colorChanged(false, false));
        sntui.plugin.updateAllViewers();
    }

    private List<Roi> getValidDelineationROIs() {
        return delineations.stream().map(d -> d.roi).filter(Objects::nonNull).toList();
    }

    private Map<String, List<Path>> getLabeledGroups(final List<Path> paths) {
        final Map<Long, List<Path>> pathGroups = new HashMap<>();
        final List<Path> nonDelineatedSections = new ArrayList<>();
        final List<Path> unaffectedPaths = new ArrayList<>();
        for (final Path p : paths) {
            if (!pathHasDelineationLabels(p)) {
                unaffectedPaths.add(p);
                continue;
            }
            final List<int[]> indices = findGroupIndices(p);
            for (final int[] index : indices) {
                final Path section = p.getSection(index[0], index[1]);
                final long label = (long) section.getNodeValue(0);
                if (label < 0) {
                    pathGroups.putIfAbsent(label, new ArrayList<>());
                    pathGroups.get(label).add(section);
                } else {
                    nonDelineatedSections.add(section);
                }
            }
        }
        final Map<String, List<Path>> result = new TreeMap<>(Comparator.naturalOrder());
        pathGroups.forEach((label, sections) -> {
            final Delineation del = getDelineation(label);
            final String header = (del == null) ? "Unknown" : del.name;
            result.put(header, sections);
        });
        if (!nonDelineatedSections.isEmpty())
            result.put(NON_DELINEATED_LABEL, nonDelineatedSections);
        if (!unaffectedPaths.isEmpty())
            result.put(UNAFFECTED_LABEL, unaffectedPaths);
        return result;
    }

    private void measure() {
        if (noAssignmentsExistError() || sntui.noPathsError()) return;
        final List<Path> paths = pafm.getPaths();
        final boolean newTableNeeded = table == null;
        if (newTableNeeded)
            table = new SNTTable();
        else
            table.clear();
        final Map<String, List<Path>> pathGroups = getLabeledGroups(paths);
        pathGroups.forEach((label, sections) -> {
            final double length = sections.stream().mapToDouble(Path::getLength).sum();
            final double nNodes = sections.stream().mapToDouble(Path::size).sum();
            table.set("Total length", label, length);
            table.set("No. of nodes", label, nNodes);
            if (UNAFFECTED_LABEL.equals(label)) {
                final int nJunctions = sections.stream().mapToInt(p -> p.getChildren().size()).sum();
                table.set("No. of junctions", label, nJunctions);
                table.set("No. of paths", label, sections.size());
            } else {
                table.set("No. of junctions", label, getUniqueJunctionsCount(sections));
                table.set("No. of path sections", label, sections.size());
            }
        });
        table.fillEmptyCells(Double.NaN);
        if (pathGroups.size() > 1) table.summarize();
        if (newTableNeeded)
            table.show("SNT Delineation Analysis");
        else
            table.createOrUpdateDisplay();
        nonDelineationMappingWarning();
    }

    private void plot(final String metric, final Map<String, List<Path>> pathGroups) {
        final boolean polar = metric.toLowerCase().contains("angle");
        final List<SNTChart> charts = new ArrayList<>();
        pathGroups.forEach((label, sections) -> {
            try {
                if (PathStatistics.VALUES.equals(metric) && sntui.plugin.accessToValidImageData())
                    new PathProfiler(new Tree(sections), sntui.plugin.getDataset()).assignValues();
                final PathStatistics stats = new PathStatistics(sections, label);
                final SNTChart chart = (polar) ? stats.getPolarHistogram(metric) : stats.getHistogram(metric);
                chart.setTitle(label);
                charts.add(chart);
            } catch (final Exception ex) {
                SNTUtils.error("Error computing distributions  " + label + ": " + sections.size() + " sections.", ex);
            }
        });
        SNTChart.combine(charts, true).show();
        if (charts.size() != pathGroups.size()) {
            sntui.guiUtils.error("Some distributions could not be computed. With debug mode active, check Console for details.");
        }
    }

    private int getUniqueJunctionsCount(final List<Path> sections) {
        final Set<PointInImage> uniqueJunctions = new HashSet<>();
        sections.forEach(section -> pafm.getPathFromID(section.getID()).getJunctionNodes().forEach(junction -> {
            if (section.getNodeIndex(junction) != -1) uniqueJunctions.add(junction);
        }));
        return uniqueJunctions.size();
    }

    private JToolBar bottomToolBar() {
        final JToolBar toolbar = new JToolBar();
        toolbar.add(GuiUtils.Buttons.help("https://imagej.net/plugins/snt/walkthroughs#delineation-analysis"));
        toolbar.add(Box.createHorizontalGlue());
        toolbar.add(optionsButton());
        toolbar.addSeparator();
        final JButton button2 = new JButton("Plot", IconFactory.buttonIcon(IconFactory.GLYPH.CHART, 1.1f));
        button2.addActionListener(e -> {
            if (noAssignmentsExistError() || sntui.noPathsError()) return;
            final List<Path> paths = pafm.getPaths();
            final String lastChoice = sntui.getPrefs().get("snt.delineationMetric", "Path length");
            final String choice = sntui.guiUtils.getChoice("", "Plot Distribution...", getApplicableMetrics(), lastChoice);
            if (choice == null) return;
            try {
                sntui.getPrefs().set("snt.delineationMetric", choice);
                plot(choice, getLabeledGroups(paths));
            } catch (final IllegalArgumentException ex) {
                sntui.guiUtils.error("It was not possible to retrieve valid histogram data. It is likely that '"
                        + choice + "' cannot be not be computed to current paths/delineations.");
                SNTUtils.error("Plotting error", ex);
            }
            nonDelineationMappingWarning();
        });
        toolbar.add(button2);
        toolbar.addSeparator();
        final JButton button3 = new JButton("Measure", IconFactory.buttonIcon(IconFactory.GLYPH.TABLE, 1.1f));
        button3.addActionListener(e -> measure());
        toolbar.add(button3);
        return toolbar;
    }

    private String[] getApplicableMetrics() {
        final List<String> choiceList = PathStatistics.getMetrics("safe");
        choiceList.remove(PathStatistics.INTER_NODE_DISTANCE_SQUARED); // redundant with internode distance
        choiceList.remove(PathStatistics.N_BRANCH_POINTS); // does not apply immediately to Path sections
        choiceList.remove(PathStatistics.N_SPINES); // does not apply immediately to Path sections
        choiceList.remove(PathStatistics.PATH_N_SPINES); // does not apply immediately to Path sections
        choiceList.remove(PathStatistics.PATH_SPINE_DENSITY); // does not apply immediately to Path sections
        if (!sntui.plugin.accessToValidImageData()) choiceList.remove(PathStatistics.VALUES);
        return choiceList.toArray(new String[0]);
    }

    private boolean noAssignmentPossible(final Delineation del) {
        if (sntui.noPathsError()) return true;
        if (sntui.plugin.getImagePlus() == null) {
            sntui.guiUtils.error("No image is available.");
            return true;
        }
        if (del.name == null || del.name.trim().isEmpty()) {
            sntui.guiUtils.error("Delineation name cannot be empty.");
            return true;
        }
        return false;
    }

    private Delineation getDelineation(final Roi roi) {
        if (roi == null || roi == Delineation.DUMMY_ROI) return null;
        for (final Delineation d : delineations)
            if (roi.equals(d.roi)) return d;
        return null;
    }

    private Delineation getDelineation(final long negativeLabel) {
        for (final Delineation d : delineations)
            if (negativeLabel == -d.label) return d;
        return null;
    }

    private Delineation getDelineation(final Color color) {
        if (color == null) return null;
        for (final Delineation d : delineations)
            if (color.equals(d.color)) return d;
        return null;
    }

    JButton optionsButton() {
        final JButton optionsButton = GuiUtils.Buttons.options();
        optionsButton.setFocusable(false);
        optionsButton.setToolTipText("Options");
        final JPopupMenu optionsMenu = new JPopupMenu();
        GuiUtils.addSeparator(optionsMenu, "Input/Output");
        JMenuItem jmi = new JMenuItem("Import Assignments from Atlas Annotations", IconFactory.menuIcon(IconFactory.GLYPH.ATLAS));
        jmi.setToolTipText("Import delineations from neuropil labels. Previous delineations will be overridden.");
        jmi.addActionListener(e -> delineateFromPrompt());
        optionsMenu.add(jmi);
        optionsMenu.addSeparator();
        jmi = new JMenuItem("Import Assignments from ROI Manager", IconFactory.menuIcon(IconFactory.GLYPH.IMPORT));
        jmi.addActionListener(e -> {
            final RoiManager rm = RoiManager.getInstance2();
            if (rm == null || rm.getCount() == 0) {
                sntui.guiUtils.error("The ROI Manager is either closed or empty.");
                return;
            }
            final List<Roi> rois = Arrays.stream(rm.getRoisAsArray()).filter(Roi::isArea).toList();
            if (rois.isEmpty()) {
                sntui.guiUtils.error("The ROI Manager does not contain area ROIs.");
                return;
            }
            if (!resetAutorizedByUser()) return;
            expandCapacityTo(rois.size());
            int outCounter = 0;
            for (int i = 0; i < rois.size(); i++) {
                final Roi roi = rois.get(i);
                final Collection<Path> paths = pafm.getPathsInROI(roi);
                if (paths.isEmpty()) outCounter++;
                final Delineation delineation = delineations.get(i);
                delineation.assignRoi(roi, paths);
                // if user assigned a label to the ROI being imported, use it
                if (roi.getName() != null && !roi.getName().isEmpty()) {
                    delineation.rename(roi.getName());
                }
                // if user assigned a color to the ROI being imported, use it
                Color roiColor = roi.getStrokeColor();
                if (roiColor == null) roiColor = roi.getFillColor();
                if (roiColor != null) {
                    delineation.color = roiColor;
                    delineation.colorChanged(true, false);
                }
            }
            delineations.forEach(Delineation::updateWidget);
            sntui.plugin.updateAllViewers();
            if (outCounter == 0) {
                sntui.guiUtils.centeredMsg(rois.size() + " assignment(s) imported from ROI Manager.",
                        "Delineations Imported");
            } else {
                sntui.guiUtils.centeredMsg(String.format("%d assignment(s) imported from ROI Manager. " +
                                "%d ROI(s) were not associated with any paths.", rois.size(), outCounter),
                        "Delineations Imported");
            }
        });
        optionsMenu.add(jmi);
        jmi = new JMenuItem("Export Assignments to ROI Manager", IconFactory.menuIcon(IconFactory.GLYPH.EXPORT));
        jmi.addActionListener(e -> toRoiManager());
        optionsMenu.add(jmi);
        GuiUtils.addSeparator(optionsMenu, "Edit");
        jmi = new JMenuItem("Color Scheme...", IconFactory.menuIcon(IconFactory.GLYPH.COLOR2));
        jmi.addActionListener(e -> {
            final String[] choices = new String[]{"Distinct", "Fire", "Ice", "Plasma", "Spectrum", "Viridis"};
            final String lastChoice = sntui.getPrefs().get("snt.dmColorScheme", "Distinct");
            final String choice = sntui.guiUtils.getChoice("", "Color Scheme...", choices, lastChoice);
            if (choice == null) return;
            final Color[] colors = (choices[0].equals(choice))
                    ? defaultDelineationColors(Math.min(256, delineations.size()))
                    : ColorMaps.discreteColorsAWT(ColorMaps.get(choice), Math.min(256, delineations.size()));
            int colorIdx = 0;
            for (final Delineation d : delineations) {
                if (colorIdx > 255) colorIdx = 0;
                d.color = colors[colorIdx++];
                d.colorChanged(true, false);
            }
            sntui.plugin.updateAllViewers();
            sntui.getPrefs().set("snt.dmColorScheme", choice);
        });
        optionsMenu.add(jmi);
        final JCheckBoxMenuItem jcmi = new JCheckBoxMenuItem("Direct Editing of Labels", IconFactory.menuIcon(IconFactory.GLYPH.PEN));
        jcmi.addItemListener(e -> delineations.forEach(d -> d.field.editButton.setSelected(jcmi.isSelected())));
        optionsMenu.add(jcmi);
        jmi = new JMenuItem("Reset Labels", IconFactory.menuIcon(IconFactory.GLYPH.UNDO));
        jmi.addActionListener(e -> {
            for (final Delineation delineation : delineations) {
                delineation.rename(delineation.field.defaultLabel());
                delineation.field.editButton.setSelected(false);
            }
            jcmi.setSelected(false);
        });
        optionsMenu.add(jmi);
        GuiUtils.addSeparator(optionsMenu, "Rendering of Delineated Paths");
        jmi = new JMenuItem("Restore Pre-Delineation Colors", IconFactory.menuIcon(IconFactory.GLYPH.UNDO));
        jmi.addActionListener(e -> removeDelineationLabelsFromAllPaths(false, true));
        optionsMenu.add(jmi);
        jmi = new JMenuItem("(Re)Apply Delineation Colors", IconFactory.menuIcon(IconFactory.GLYPH.REDO));
        jmi.addActionListener(e -> {
            delineations.forEach(d -> d.colorChanged(false,false));
            sntui.plugin.updateAllViewers();
        });
        optionsMenu.add(jmi);
        GuiUtils.addSeparator(optionsMenu, "Reset");
        jmi = new JMenuItem("Rebuild Assignments", IconFactory.menuIcon(IconFactory.GLYPH.FIRST_AID));
        jmi.addActionListener(e -> {
            if (delineations.stream().allMatch(d -> d.roi == null)) {
                reset();
                sntui.showStatus("Assignments rebuilt.", true);
                return;
            }
            if (noAssignmentsExistError()) return;
            delineations.forEach(d -> {
                if (d.roi != null) {
                    d.assignRoi(d.roi, pafm.getPathsInROI(d.roi));
                    d.updateWidget();
                }
            });
            sntui.plugin.updateAllViewers();
        });
        optionsMenu.add(jmi);
        jmi = new JMenuItem("Delete All Assignments...", IconFactory.menuIcon(IconFactory.GLYPH.TRASH));
        jmi.addActionListener(e -> {
            if (noAssignmentsExistError()) return;
            // Unless, e.g. the user deleted all assignments one by one, we'll only reset things after user confirmation
            if (sntui.guiUtils.getConfirmation("Are you sure you want to remove all delineations?", "Remove All?"))
                reset();
        });
        optionsMenu.add(jmi);
        optionsButton.addMouseListener(new MouseAdapter() {

            @Override
            public void mousePressed(final MouseEvent e) {
                optionsMenu.show(optionsButton, optionsButton.getWidth() / 2, optionsButton.getHeight() / 2);
            }
        });
        return optionsButton;
    }

    private boolean resetAutorizedByUser() {
        final boolean needed = delineations.stream().anyMatch(d -> d.roi != null);
        if (!needed) return true;
        if (sntui.guiUtils.getConfirmation("Existing assignments will be cleared. Proceed?",
                        "Remove All Assignments?")) {
            reset();
            return true;
        }
        return false;
    }

    /**
     * Deletes all delineations.
     */
    public void reset() {
        removeDelineationLabelsFromAllPaths(true, true);
        delineations.forEach(d -> {
            d.roi = null;
            d.updateWidget();
        });
    }

    private void toRoiManager() {
        if (noAssignmentsExistError()) return;
        final List<Roi> rois = getValidDelineationROIs();
        if (!rois.isEmpty()) {
            RoiManager rm = RoiManager.getInstance2();
            if (rm == null) rm = new RoiManager();
            for (final Roi roi : rois) rm.addRoi(roi);
        }
    }

    private void removeDelineationLabelsFromAllPaths(final boolean removeLabels, final boolean removeColors) {
        for (final Path p : pafm.getPaths()) {
            if (pathHasDelineationLabels(p)) {
                if (removeLabels) p.setNodeValues(null);
                if (removeColors) p.setNodeColors(null);
            }
        }
        sntui.plugin.updateAllViewers();
    }

    /**
     * Checks if the given trees have any delineation labels.
     *
     * @param trees the collection of trees to be inspected
     * @return true if any of the trees has delineation labels, false otherwise
     */
    public static boolean hasDelineationLabels(final Collection<Tree> trees) {
        return trees.stream().anyMatch(DelineationsManager::hasDelineationLabels);
    }

    /**
     * Checks if the given tree has any delineation labels.
     *
     * @param tree the collection of paths to be inspected
     * @return true if the tree has delineation labels, false otherwise
     */
    public static boolean hasDelineationLabels(final Tree tree) {
        return tree.list().stream().anyMatch(DelineationsManager::pathHasDelineationLabels);
    }

    private static boolean pathHasDelineationLabels(final Path p) {
        if (!p.hasNodeValues()) return false;
        for (int i = 0; i < p.size(); i++) {
            if (p.getNodeValue(i) < 0) return true;
        }
        return false;
    }

    private boolean noAssignmentsExistError() {
        final boolean noROIs = delineations.stream().noneMatch(d -> d.roi != null);
        final boolean noLabels = !hasDelineationLabels(pafm.getTrees());
        if (noLabels && noROIs) {
            sntui.guiUtils.error("No assignments exist.");
            return true;
        }
        return false;
    }

    private void nonDelineationMappingWarning() {
        final boolean nonDelineationMap = pafm.getPaths().stream().anyMatch( p -> {
            if (p.hasNodeValues()) {
                for (int i = 0; i < p.size(); i++) {
                    if (p.getNodeValue(i) > 0) return true;
                }
            }
            return false;
        });
        if (nonDelineationMap) {
            sntui.showMessage("Some paths have numeric annotations or non-delineation mappings. It may be " +
                    "necessary to remove them or run <i>Rebuild Assignments</i> to ensure that delineations " +
                    "are parsed accurately.", "Warning: Mixed Mappings Detected");
        }
    }

    private List<int[]> findGroupIndices(final Path p) {
        final List<int[]> result = new ArrayList<>();
        if (p.size() == 0 || !p.hasNodeValues()) {
            return result;
        }
        int start = 0;
        for (int i = 0; i < p.size(); i++) {
            final double nv1 = p.getNodeValue(start);
            final double nv2 = p.getNodeValue(i);
            if (!(nv1 < 0 || nv2 < 0)) continue;
            if (nv1 != nv2) {
                result.add(new int[]{start, i - 1});
                start = i;
            }
        }
        result.add(new int[]{start, p.size() - 1});
        return result;
    }

    private void delineateFromPrompt() {
        if (sntui.noPathsError()) return;
        final Collection<Tree> trees = sntui.getPathManager().getMultipleTrees();
        if (trees == null || trees.isEmpty()) {
            return;
        }
        final Set<BrainAnnotation> uniqueAnnotations = new TreeSet<>(Comparator.comparing(BrainAnnotation::toString));
        trees.forEach(tree -> uniqueAnnotations.addAll(new TreeStatistics(tree).getAnnotations()));
        if (uniqueAnnotations.isEmpty()) {
            sntui.guiUtils.error("None of the reconstructions contain annotations.");
            return;
        }
        final String[] uniqueAnnotationsArray = uniqueAnnotations.stream().map(BrainAnnotation::toString).toArray(String[]::new);
        final List<String> choices = sntui.guiUtils.getMultipleChoices("Select annotations to delineate",
                uniqueAnnotationsArray, null);
        if (choices == null || choices.isEmpty() || !resetAutorizedByUser()) return;
        final Set<BrainAnnotation> chosenAnnotations = uniqueAnnotations.stream().filter(a -> choices.contains(a.toString())).collect(Collectors.toSet());
        delineate(trees, chosenAnnotations);
    }

    void delineate(final Collection<Tree> trees, final Set<BrainAnnotation> annotations) {
        final int n = annotations.size();
        expandCapacityTo(n);
        final Map<Delineation, BrainAnnotation> map = new HashMap<>(n);
        final Iterator<BrainAnnotation> it = annotations.iterator();
        for (int i = 0; i < n; i++) {
            final Delineation delineation = delineations.get(i);
            delineation.roi = Delineation.DUMMY_ROI;
            final BrainAnnotation annotation = it.next();
            delineation.rename(annotation.acronym() + " [ID: " + annotation.id() + "]");
            map.put(delineation, annotation);
        }
        trees.forEach(tree -> map.forEach((delineation, annotation) -> tree.list().forEach(path -> {
            for (int i = 0; i < path.size(); i++) {
                if (annotation.equals(path.getNodeAnnotation(i))) {
                    path.setNodeValue(-delineation.label, i);
                    path.setNodeColor(delineation.color, i);
                } else {
                    final Delineation d = getDelineation((long) path.getNodeValue(i));
                    path.setNodeColor((d == null) ? fallbackColor : d.color, i);
                }
            }
        })));
        for (int i = 0; i < n; i++) delineations.get(i).updateWidget();
        sntui.plugin.updateAllViewers();
    }

    private class Delineation {
        static final int UNDEFINED_LABEL = -9999;
        static final Roi DUMMY_ROI = new Roi(0,0,0,0); // delineations that are not based in ROIs
        Color color;
        Roi roi;
        String name;
        final long label;
        final JTextFieldLabel field;
        final AbstractButton colorButton;
        final AbstractButton clearButton;
        final AbstractButton revealButton;
        final AbstractButton assignButton;

        Delineation(final long label, final Color color) {
            if (label < 1 || label == -UNDEFINED_LABEL) {
                // labels are applied as negative numbers, so we'll impose positive labels
                throw new IllegalArgumentException("Label must be greater than 0 and not " + (-UNDEFINED_LABEL));
            }
            this.color = color;
            this.label = label;
            name = "Delineation " + label;
            assignButton = assignFromRoiButton();
            clearButton = clearButton();
            colorButton = colorButton();
            revealButton = revealButton();
            field = new JTextFieldLabel(this);
        }

        void untagNodes(final Collection<Path> paths) {
            for (final Path p : paths) {
                for (int i = 0; i < p.size(); i++) {
                    if (p.getNodeValue(i) == -label) {
                        p.setNodeValue(UNDEFINED_LABEL, i);
                        p.setNodeColor(fallbackColor, i);
                    }
                }
            }
        }

        void assignRoi(final Roi roi, final Collection<Path> paths) {
            if (DUMMY_ROI == roi) {
                this.roi = DUMMY_ROI;
            } else {
                this.roi = (Roi) roi.clone();
                this.roi.setName(name);
                this.roi.setStrokeColor(color);
                colorPathNodesByLabel(paths);
            }
        }

        void colorPathNodesByLabel(final Collection<Path> paths) {
            for (final Path p : paths) {
                for (int i = 0; i < p.size(); i++) {
                    if (roi.contains(p.getXUnscaled(i), p.getYUnscaled(i))) {
                        p.setNodeValue(-label, i);
                        p.setNodeColor(color, i);
                    } else {
                        final Delineation d = getDelineation((long) p.getNodeValue(i));
                        p.setNodeColor((d == null) ? fallbackColor : d.color, i);
                    }
                }
            }
        }

        void rename(final String newName) {
            name = newName;
            field.setText(name);
            if (roi != null && roi != DUMMY_ROI) roi.setName(name);
        }

        JToolBar getWidget() {
            final JToolBar toolbar = new JToolBar();
            toolbar.add(clearButton);
            toolbar.add(Box.createHorizontalStrut(5));
            toolbar.add(revealButton);
            toolbar.add(Box.createHorizontalStrut(5));
            toolbar.add(colorButton);
            toolbar.add(Box.createHorizontalStrut(5));
            toolbar.add(field.editButton);
            toolbar.add(field);
            toolbar.addSeparator();
            toolbar.add(assignButton);
            updateWidget();
            return toolbar;
        }

        private JButton assignFromRoiButton() {
            final JButton b = new JButton("Assign");
            b.addActionListener(e -> {
                if (noAssignmentPossible(this)) return;
                final Roi roi = sntui.plugin.getImagePlus().getRoi();
                if (roi == null || !roi.isArea()) {
                    sntui.guiUtils.error("No area ROI is currently active.");
                    return;
                }
                final Delineation existingDelineation = getDelineation(roi);
                if (this.equals(existingDelineation)) {
                    sntui.guiUtils.error("ROI is already defining this delineation.");
                } else if (existingDelineation != null) {
                    sntui.guiUtils.error("ROI is already assigned to " + existingDelineation.name + ".");
                } else {
                    final Collection<Path> paths = pafm.getPathsInROI(roi);
                    if (paths.isEmpty() && !sntui.guiUtils.getConfirmation("No paths are associated with ROI. Assign it anyway?",
                            "No Paths in ROI")) {
                        return;
                    }
                    if (this.roi != null) untagNodes(paths);
                    assignRoi(roi, paths);
                    sntui.plugin.updateAllViewers();
                }
                updateWidget();
            });
            return b;
        }

        private JButton clearButton() {
            final JButton b = GuiUtils.Buttons.delete();
            b.setToolTipText("Deletes current assignment");
            b.addActionListener(e -> {
                if (roi != null) {
                    untagNodes(pafm.getPaths());
                    roi = null;
                    sntui.plugin.updateAllViewers();
                }
                updateWidget();
            });
            return b;
        }

        private JButton revealButton() {
            final JButton button = GuiUtils.Buttons.show(IconFactory.selectedColor());
            button.setToolTipText("Show/Hide assigned ROI");
            button.addActionListener(e -> {
                if (roi == null || roi == DUMMY_ROI) {
                    sntui.guiUtils.error("No ROI assignment has occurred.");
                    return;
                }
                final ImagePlus imp = sntui.plugin.getImagePlus();
                if (imp == null) {
                    sntui.guiUtils.error("No image is available.");
                    return;
                }
                final Roi roi = imp.getRoi();
                if (this.roi.equals(roi) && roi.isVisible()) {
                    imp.resetRoi(); // hide it
                } else {
                    imp.saveRoi();
                    imp.setRoi(this.roi); // show it
                }
            });
            return button;
        }

        private void colorChanged(final boolean updateIcon, final boolean notifyViewers) {
            if (updateIcon)
                colorButton.setIcon(IconFactory.accentIcon(color, true));
            if (roi == DUMMY_ROI) {
                colorPathNodesByLabel(pafm.getPaths());
            } else if (roi != null) {
                colorPathNodesByLabel(pafm.getPathsInROI(roi));
            }
            if (notifyViewers) sntui.plugin.updateAllViewers();
        }

        private JButton colorButton() {
            final JButton b = new JButton(IconFactory.accentIcon(color, true));
            b.setToolTipText("Delineation color");
            b.addActionListener(e -> {
                final Color newColor = sntui.guiUtils.getColor("Color for " + name, color, (String[]) null);
                if (newColor == null || newColor.equals(color)) return;
                color = newColor;
                colorChanged(true, true);
            });
            return b;
        }

        private void updateWidget() {
            final Color color = (roi == null) ? IconFactory.defaultColor() : IconFactory.selectedColor();
            clearButton.setForeground(color);
            clearButton.setEnabled(roi != null);
            revealButton.setForeground(color);
            revealButton.setEnabled(roi != null && roi != DUMMY_ROI);
            assignButton.setIcon(IconFactory.buttonIcon('\uf359', true, color));
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Delineation that = (Delineation) o;
            return label == that.label &&
                    Objects.equals(name, that.name) &&
                    Objects.equals(color, that.color) &&
                    Objects.equals(roi, that.roi);
        }

        @Override
        public int hashCode() {
            return Objects.hash(label, name, color, roi);
        }
    }


    private class JTextFieldLabel extends JTextField {

        final Delineation delineation;
        final JToggleButton editButton;

        JTextFieldLabel(final Delineation delineation) {
            super();
            setText(delineation.name);
            this.delineation = delineation;
            setEditable(false);
            setBorder(null);
            GuiUtils.addClearButton(this);
            GuiUtils.addPlaceholder(this, defaultLabel());
            editButton = GuiUtils.Buttons.edit();
            editButton.setSelected(false);
            editButton.setToolTipText("Rename Delineation " + delineation.label);
            editButton.addItemListener(e -> {
                if (editButton.isSelected()) {
                    setEditable(true);
                } else {
                    setEditable(false);
                    acceptInput();
                }
            });
            addActionListener(e -> editButton.setSelected(false) ); // triggered by Enter key
        }

        private void acceptInput() {
            delineation.rename((validInput()) ? getText() : defaultLabel());
        }

        private String defaultLabel() {
            return "Delineation " + delineation.label;
        }

        private boolean validInput() {
            final String text = getText();
            return text != null && !text.isEmpty() && delineations.stream().noneMatch(d -> d != delineation && d.name.equals(text));
        }

        @Override
        public void setEditable(boolean editable) {
            super.setEditable(editable);
            super.setFocusable(editable);
            if (editable) requestFocusInWindow();
        }
    }
}
