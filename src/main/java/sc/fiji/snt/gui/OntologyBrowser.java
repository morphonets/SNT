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

import com.jidesoft.swing.CheckBoxTree;
import com.jidesoft.swing.TreeSearchable;
import org.scijava.util.ColorRGB;
import sc.fiji.snt.annotation.AllenCompartment;
import sc.fiji.snt.annotation.AllenUtils;
import sc.fiji.snt.annotation.BrainAnnotation;
import sc.fiji.snt.annotation.DrosophilaUtils;

import javax.swing.*;
import javax.swing.text.Position;
import javax.swing.tree.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.KeyEvent;
import java.io.Serial;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * A reusable panel that presents one or more hierarchical ontologies as
 * searchable checkbox trees. Users pick terms and selections are serialized
 * as {@value #SEPARATOR}-delimited hierarchical strings (e.g.,
 * {@code "Isocortex::Visual areas::VISp"}).
 * <p>
 * Ontology tabs can be added from existing {@link DefaultTreeModel}s (e.g.,
 * Allen CCF via {@link AllenUtils#getTreeModel(boolean)}), or from simple
 * nested {@link Map}s for user-defined term hierarchies.
 * </p>
 *
 * @author Tiago Ferreira
 */
public class OntologyBrowser extends JPanel {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Separator used for serializing hierarchical paths.
     */
    public static final String SEPARATOR = "::";

    private static Point lastDialogLocation;

    /**
     * When {@code true}, typing in the search bar collapses the tree and
     * expands only the paths that lead to matching nodes (filter-like UX).
     * When {@code false}, the default JIDE behavior is used (scroll to match).
     * This is a global flag so it can be toggled easily if behavior becomes
     * problematic.
     */
    static boolean FILTER_SEARCH = true;

    private final JTabbedPane tabbedPane;
    private final List<OntologyTab> tabs;
    private final List<Consumer<List<String>>> selectionListeners;
    private final boolean singleSelection;
    private Function<TreePath, String> pathFormatter;

    /**
     * Creates an empty OntologyBrowser with multi-selection enabled.
     * Add ontology tabs via {@link #addOntology(String, DefaultTreeModel)}
     * or convenience methods.
     */
    public OntologyBrowser() {
        this(false);
    }

    /**
     * Creates an empty OntologyBrowser.
     *
     * @param singleSelection if {@code true}, only one term can be checked
     *                        at a time across all tabs (radio-group behavior)
     */
    public OntologyBrowser(final boolean singleSelection) {
        super(new BorderLayout());
        this.singleSelection = singleSelection;
        tabbedPane = GuiUtils.getTabbedPane();
        tabbedPane.setTabPlacement(JTabbedPane.BOTTOM);
        tabs = new ArrayList<>();
        selectionListeners = new ArrayList<>();
        add(tabbedPane, BorderLayout.CENTER);
    }

    /**
     * Adds an ontology tab backed by an existing {@link DefaultTreeModel}.
     *
     * @param tabLabel the tab title
     * @param model    the tree model (nodes should have meaningful
     *                 {@code toString()} on their user objects)
     * @return the created {@link OntologyTab} for further customization
     */
    public OntologyTab addOntology(final String tabLabel, final DefaultTreeModel model) {
        final OntologyTab tab = new OntologyTab(model);
        tabs.add(tab);
        tabbedPane.addTab(tabLabel, tab);
        // Hide tab strip when only one ontology is loaded
        tabbedPane.putClientProperty("JTabbedPane.tabsVisible", tabs.size() > 1);
        // Install default context menu; callers can replace or extend via
        // tab.createDefaultPopupMenu() + tab.setPopupMenu()
        tab.setPopupMenu(tab.createDefaultPopupMenu());
        return tab;
    }

    /**
     * Convenience method to add the Allen CCF ontology as a tab, including
     * all compartments.
     *
     * @return the created {@link OntologyTab}
     */
    public OntologyTab addAllenCCFOntology() {
        return addAllenCCFOntology(false);
    }

    /**
     * Adds the Allen CCF ontology as a tab.
     *
     * @param meshesOnly if {@code true}, only compartments with available
     *                   meshes are included in the tree
     * @return the created {@link OntologyTab}
     */
    public OntologyTab addAllenCCFOntology(final boolean meshesOnly) {
        final OntologyTab tab = addOntology("Allen CCFv" + AllenUtils.VERSION, AllenUtils.getTreeModel(meshesOnly));
        tab.setCellRenderer(new AllenCCFRenderer());
        tab.setStatusLabelPlaceholder("CCFv" + AllenUtils.VERSION);
        // Extend default popup with Allen-specific atlas links
        final JPopupMenu pMenu = tab.createDefaultPopupMenu();
        pMenu.addSeparator();
        pMenu.add(GuiUtils.MenuItems.openHelpURL("Online 2D Atlas Viewer", "https://atlas.brain-map.org/atlas?atlas=602630314"));
        pMenu.add(GuiUtils.MenuItems.openHelpURL("Online 3D Atlas Viewer", "https://connectivity.brain-map.org/3d-viewer"));
        tab.setPopupMenu(pMenu);
        return tab;
    }

    /**
     * Adds all the default ontologies as tabs.
     */
    public void addAllOntologies() {
        addAllenCCFOntology();
        addDrosophilaOntology();
    }

    /**
     * Adds the Drosophila adult brain ontology (FBbt) as a tab.
     *
     * @return the created {@link OntologyTab}
     */
    public OntologyTab addDrosophilaOntology() {
        final OntologyTab tab = addOntology("Drosophila FBbt", DrosophilaUtils.getTreeModel());
        tab.setCellRenderer(new NoIconRenderer());
        tab.setStatusLabelPlaceholder("FBbt");
        final JPopupMenu pMenu = tab.createDefaultPopupMenu();
        pMenu.addSeparator();
        pMenu.add(GuiUtils.MenuItems.openHelpURL("Online VFB Viewer",
                "https://v2.virtualflybrain.org/org.geppetto.frontend/geppetto?id=VFB_00101567&i=VFB_00101567"));
        tab.setPopupMenu(pMenu);
        return tab;
    }

    /**
     * Adds a custom ontology tab from a nested map. Each key is a parent term
     * and the value is the list of child terms. A {@code null} or empty list
     * means the key is a leaf. The root of the tree is not shown.
     * <p>
     * Example:
     * <pre>
     * Map&lt;String, List&lt;String&gt;&gt; map = new LinkedHashMap&lt;&gt;();
     * map.put("Cortex", List.of("L1", "L2/3", "L4", "L5", "L6"));
     * map.put("White Matter", List.of("cc", "ic"));
     * browser.addOntology("Histological Landmarks", map);
     * </pre>
     *
     * @param tabLabel  the tab title
     * @param hierarchy a map of parent term to child terms (single-level)
     * @return the created {@link OntologyTab}
     */
    public OntologyTab addOntology(final String tabLabel, final Map<String, List<String>> hierarchy) {
        final DefaultMutableTreeNode root = new DefaultMutableTreeNode("Root");
        hierarchy.forEach((parent, children) -> {
            final DefaultMutableTreeNode parentNode = new DefaultMutableTreeNode(parent);
            if (children != null) {
                children.forEach(child -> parentNode.add(new DefaultMutableTreeNode(child)));
            }
            root.add(parentNode);
        });
        return addOntology(tabLabel, new DefaultTreeModel(root));
    }

    /**
     * Adds a custom ontology tab built from a deeply nested map. Each key maps
     * to either another map (subtree) or {@code null} (leaf).
     *
     * @param tabLabel the tab title
     * @param tree     a recursively nested map representing the ontology
     * @return the created {@link OntologyTab}
     */
    public OntologyTab addDeepOntology(final String tabLabel, final Map<String, Object> tree) {
        final DefaultMutableTreeNode root = new DefaultMutableTreeNode("Root");
        buildDeepTree(root, tree);
        return addOntology(tabLabel, new DefaultTreeModel(root));
    }

    @SuppressWarnings("unchecked")
    private void buildDeepTree(final DefaultMutableTreeNode parent, final Map<String, Object> map) {
        map.forEach((key, value) -> {
            final DefaultMutableTreeNode node = new DefaultMutableTreeNode(key);
            if (value instanceof Map) {
                buildDeepTree(node, (Map<String, Object>) value);
            }
            parent.add(node);
        });
    }

    /**
     * Returns the checked selections from all tabs as {@value #SEPARATOR}-
     * delimited hierarchical paths. The root node is excluded from the path.
     *
     * @return list of serialized paths, e.g.,
     * {@code ["Isocortex::Visual areas::VISp"]}
     */
    public List<String> getSelectedPaths() {
        final List<String> paths = new ArrayList<>();
        for (final OntologyTab tab : tabs) {
            paths.addAll(tab.getSelectedPaths());
        }
        return paths;
    }

    /**
     * Returns only the leaf-level names of checked selections (without
     * ancestor prefixes).
     *
     * @return list of leaf names
     */
    public List<String> getSelectedLeaves() {
        final List<String> leaves = new ArrayList<>();
        for (final OntologyTab tab : tabs) {
            final TreePath[] treePaths = tab.tree.getCheckBoxTreeSelectionModel().getSelectionPaths();
            if (treePaths == null) continue;
            for (final TreePath tp : treePaths) {
                final DefaultMutableTreeNode node = (DefaultMutableTreeNode) tp.getLastPathComponent();
                leaves.add(node.getUserObject().toString());
            }
        }
        return leaves;
    }

    /**
     * Returns the checked user objects from all tabs, cast to the given type.
     *
     * @param <T>  the expected user-object type
     * @param type the class to cast to
     * @return list of checked user objects
     */
    public <T> List<T> getCheckedUserObjects(final Class<T> type) {
        final List<T> result = new ArrayList<>();
        for (final OntologyTab tab : tabs) {
            final TreePath[] treePaths = tab.tree.getCheckBoxTreeSelectionModel().getSelectionPaths();
            if (treePaths == null) continue;
            for (final TreePath tp : treePaths) {
                final DefaultMutableTreeNode node = (DefaultMutableTreeNode) tp.getLastPathComponent();
                final Object obj = node.getUserObject();
                if (type.isInstance(obj)) {
                    result.add(type.cast(obj));
                }
            }
        }
        return result;
    }

    /**
     * Registers a listener that is notified whenever the checked selection
     * changes in any tab.
     *
     * @param listener the selection listener
     */
    public void addSelectionListener(final Consumer<List<String>> listener) {
        selectionListeners.add(listener);
    }

    private void fireSelectionChanged() {
        if (selectionListeners.isEmpty()) return;
        final List<String> paths = getSelectedPaths();
        selectionListeners.forEach(l -> l.accept(paths));
    }

    /**
     * Clears all checked selections across all tabs.
     */
    public void clearSelection() {
        tabs.forEach(tab -> tab.tree.getCheckBoxTreeSelectionModel().clearSelection());
    }

    /**
     * Sets a custom formatter for converting checked {@link TreePath}s into
     * strings. By default, paths are serialized as {@value #SEPARATOR}-
     * delimited hierarchical strings via {@link #serializePath(TreePath)}.
     *
     * @param formatter the function to apply to each checked tree path
     * @see #LEAF_ONLY_FORMATTER
     */
    public void setPathFormatter(final Function<TreePath, String> formatter) {
        this.pathFormatter = formatter;
    }

    /**
     * A predefined path formatter that returns only the leaf node's name.
     * For {@link BrainAnnotation} user objects, returns the acronym (if
     * available and different from the name) prefixed to the name, e.g.,
     * {@code "PB: protocerebral bridge"}. Otherwise falls back to
     * {@code toString()}. Useful when the full hierarchical path would
     * be too long for display.
     */
    public static final Function<TreePath, String> LEAF_ONLY_FORMATTER = tp -> {
        final DefaultMutableTreeNode node = (DefaultMutableTreeNode) tp.getLastPathComponent();
        final Object obj = node.getUserObject();
        if (obj instanceof BrainAnnotation ba) {
            final String name = ba.name();
            final String acr = ba.acronym();
            if (acr != null && !acr.isEmpty() && !acr.equalsIgnoreCase(name)) {
                return acr + ": " + name;
            }
            return name;
        }
        return obj.toString();
    };

    /**
     * A predefined path formatter that builds a {@value #SEPARATOR}-delimited
     * string using only acronyms. For each node in the path (excluding the
     * root), the {@link BrainAnnotation#acronym()} is used if available,
     * otherwise the name. E.g., {@code "CX::PB"} instead of
     * {@code "adult central complex (CX)::protocerebral bridge (PB)  (3669)"}.
     */
    public static final Function<TreePath, String> ACRONYM_ONLY_FORMATTER = tp -> {
        final Object[] nodes = tp.getPath();
        final StringBuilder sb = new StringBuilder();
        for (int i = 1; i < nodes.length; i++) { // skip root
            if (i > 1) sb.append(SEPARATOR);
            final Object obj = ((DefaultMutableTreeNode) nodes[i]).getUserObject();
            if (obj instanceof BrainAnnotation ba) {
                final String acr = ba.acronym();
                sb.append(acr != null && !acr.isEmpty() ? acr : ba.name());
            } else {
                sb.append(obj.toString());
            }
        }
        return sb.toString();
    };

    /**
     * Returns the number of ontology tabs.
     */
    public int getTabCount() {
        return tabs.size();
    }

    /**
     * Returns the tab at the given index.
     *
     * @param index the tab index
     * @return the ontology tab
     */
    public OntologyTab getTab(final int index) {
        return tabs.get(index);
    }

    /**
     * Shows this browser in a modal dialog and returns the selected paths,
     * or {@code null} if the user cancelled.
     *
     * @param parent the parent component for dialog positioning
     * @param title  the dialog title
     * @return the selected hierarchical paths, or {@code null} if cancelled
     */
    public List<String> showDialog(final Component parent, final String title) {
        final Window window = (parent == null) ? null : SwingUtilities.getWindowAncestor(parent);
        final JDialog dialog = new JDialog(window, title, Dialog.ModalityType.APPLICATION_MODAL);
        dialog.getRootPane().putClientProperty("Window.style", "small");
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);

        final boolean[] accepted = {false};
        final JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.TRAILING, 0, 0));
        final JButton cancelButton = new JButton("Cancel");
        cancelButton.addActionListener(e -> dialog.dispose());
        final JButton okButton = new JButton("OK");
        okButton.addActionListener(e -> {
            accepted[0] = true;
            dialog.dispose();
        });
        buttonPanel.add(cancelButton);
        buttonPanel.add(okButton);
        dialog.getRootPane().setDefaultButton(okButton);

        // Escape key closes the dialog
        dialog.getRootPane().registerKeyboardAction(
                e -> dialog.dispose(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);

        // Place buttons below the tabbed pane (below the tab strip)
        final JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.add(this, BorderLayout.CENTER);
        wrapper.add(buttonPanel, BorderLayout.SOUTH);
        dialog.setContentPane(wrapper);

        dialog.pack();
        if (lastDialogLocation != null) {
            dialog.setLocation(lastDialogLocation);
        } else {
            dialog.setLocationRelativeTo(parent);
        }
        dialog.setVisible(true);
        lastDialogLocation = dialog.getLocation();

        if (accepted[0]) {
            final List<String> paths = getSelectedPaths();
            return paths.isEmpty() ? null : paths;
        }
        return null;
    }

    /**
     * Shows this browser in a modeless (non-modal) dialog for reference
     * browsing. The dialog stays open while the user works in other windows.
     * Includes an info button and a copy-to-clipboard button.
     *
     * @param parent the parent component for initial positioning
     * @param title  the dialog title
     * @return the modeless {@link JDialog}
     */
    public JDialog showModelessDialog(final Component parent, final String title) {
        final Window window = (parent == null) ? null : SwingUtilities.getWindowAncestor(parent);
        final JDialog dialog = new JDialog(window, title);
        dialog.getRootPane().putClientProperty("Window.style", "small");
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        final JPanel buttonPanel = new JPanel(new GridLayout(1, 2));
        final JButton infoButton = new JButton(IconFactory.buttonIcon(IconFactory.GLYPH.INFO, 1f));
        infoButton.setToolTipText("Info on checked compartments");
        infoButton.addActionListener(e -> showSelectionInfo(dialog));
        buttonPanel.add(infoButton);
        final JButton copyButton = new JButton(IconFactory.buttonIcon(IconFactory.GLYPH.COPY, 1f));
        copyButton.setToolTipText("Copy checked terms to clipboard");
        copyButton.addActionListener(e -> copySelectionToClipboard());
        buttonPanel.add(copyButton);

        // Escape key closes the dialog
        dialog.getRootPane().registerKeyboardAction(
                e -> dialog.dispose(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);

        final JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.add(this, BorderLayout.CENTER);
        wrapper.add(buttonPanel, BorderLayout.SOUTH);
        dialog.setContentPane(wrapper);

        dialog.setPreferredSize(new java.awt.Dimension(350, 550));
        dialog.pack();
        if (lastDialogLocation != null) {
            dialog.setLocation(lastDialogLocation);
        } else {
            dialog.setLocationRelativeTo(parent);
        }
        dialog.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosed(final java.awt.event.WindowEvent e) {
                lastDialogLocation = dialog.getLocation();
            }
        });
        dialog.setVisible(true);
        return dialog;
    }

    /**
     * Displays an HTML info table for the currently checked compartments.
     * Works with any {@link BrainAnnotation} user objects (Allen CCF,
     * Drosophila FBbt, etc.).
     *
     * @param parent the parent component for the info dialog
     */
    public void showSelectionInfo(final Component parent) {
        final List<BrainAnnotation> annotations =
                getCheckedUserObjects(BrainAnnotation.class);
        if (annotations.isEmpty()) {
            new GuiUtils(parent).error("There are no checked ontologies.");
            return;
        }
        annotations.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        final StringBuilder sb = new StringBuilder("<header>");
        sb.append(" <style>");
        sb.append("  tr:nth-of-type(odd) {background-color:#ccc;}");
        sb.append(" </style>");
        sb.append("</header>");
        sb.append("<table>");
        sb.append("<tr>");
        sb.append("<th>Name</th><th>Acronym</th><th>Id</th>")
                .append("<th>Parent</th><th>Ontology depth</th><th>Alias(es)</th>");
        sb.append("</tr>");
        for (final BrainAnnotation a : annotations) {
            sb.append("<tr>");
            sb.append("<td style='text-align:left'>").append(a.name()).append("</td>");
            sb.append("<td style='text-align:center'>").append(a.acronym()).append("</td>");
            sb.append("<td style='text-align:center'>").append(a.id()).append("</td>");
            final BrainAnnotation par = a.getParent();
            sb.append("<td style='text-align:center'>")
                    .append(par == null ? "-" : par.toString()).append("</td>");
            sb.append("<td style='text-align:center'>")
                    .append(a.getOntologyDepth()).append("</td>");
            final String[] aliases = a.aliases();
            sb.append("<td style='text-align:center'>")
                    .append(aliases == null ? "" : String.join(", ", aliases)).append("</td>");
            sb.append("</tr>");
        }
        sb.append("</table>");
        new GuiUtils(parent).showHTMLDialog(sb.toString(),
                "Info On Selected Compartments", false);
    }

    /**
     * Copies the checked terms to the system clipboard. Each term is placed
     * on its own line, formatted as {@code "acronym (id): name"}.
     */
    public void copySelectionToClipboard() {
        final List<BrainAnnotation> annotations =
                getCheckedUserObjects(BrainAnnotation.class);
        if (annotations.isEmpty()) {
            // Fall back to string-based checked leaves for non-BrainAnnotation trees
            final List<String> leaves = getSelectedLeaves();
            if (leaves.isEmpty()) return;
            final String text = String.join("\n", leaves);
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
            return;
        }
        final StringBuilder sb = new StringBuilder();
        for (final BrainAnnotation a : annotations) {
            if (!sb.isEmpty()) sb.append("\n");
            final String acr = a.acronym();
            if (acr != null && !acr.isEmpty()) {
                sb.append(acr).append(" (").append(a.id()).append("): ");
            }
            sb.append(a.name());
        }
        Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(sb.toString()), null);
    }

    /**
     * Serializes a {@link TreePath} as a {@value #SEPARATOR}-delimited string,
     * excluding the root node.
     *
     * @param treePath the tree path
     * @return the serialized hierarchical string
     */
    public static String serializePath(final TreePath treePath) {
        final Object[] nodes = treePath.getPath();
        final StringBuilder sb = new StringBuilder();
        for (int i = 1; i < nodes.length; i++) { // skip root
            if (i > 1) sb.append(SEPARATOR);
            sb.append(((DefaultMutableTreeNode) nodes[i])
                    .getUserObject().toString());
        }
        return sb.toString();
    }

    /**
     * Represents a single ontology tab containing a searchable checkbox tree.
     * Provides methods for customizing the tree's appearance and behavior.
     */
    public class OntologyTab extends JPanel {

        @Serial
        private static final long serialVersionUID = 1L;
        final BrowserTree tree;
        final SNTSearchableBar searchableBar;

        OntologyTab(final DefaultTreeModel model) {
            super(new BorderLayout());
            tree = new BrowserTree(model);
            tree.setRootVisible(false);
            tree.setShowsRootHandles(true);
            tree.setDigIn(false);
            tree.setClickInCheckBoxOnly(true);
            tree.setEditable(false);
            tree.setExpandsSelectedPaths(true);

            // Search bar
            searchableBar = new SNTSearchableBar(new TreeSearchable(tree));
            searchableBar.setHighlightAll(true);
            searchableBar.setShowMatchCount(true);
            searchableBar.setVisibleButtons(SNTSearchableBar.SHOW_NAVIGATION | SNTSearchableBar.SHOW_HIGHLIGHTS |
                    SNTSearchableBar.SHOW_STATUS);

            // Filter-search: collapse all, then expand only paths to matches.
            // Listener is always installed; the FILTER_SEARCH flag is checked
            // at runtime so the gear-menu toggle takes effect immediately.
            searchableBar.getSearchField().getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
                @Override
                public void insertUpdate(final javax.swing.event.DocumentEvent e) { filterTree(); }
                @Override
                public void removeUpdate(final javax.swing.event.DocumentEvent e) { filterTree(); }
                @Override
                public void changedUpdate(final javax.swing.event.DocumentEvent e) { filterTree(); }
                private void filterTree() {
                    if (!FILTER_SEARCH) return;
                    // Run after JIDE's TreeSearchable handler to avoid
                    // it re-expanding nodes we just collapsed
                    SwingUtilities.invokeLater(() -> {
                        final String text = searchableBar.getSearchField().getText();
                        if (text == null || text.isBlank()) {
                            // Restore default expansion
                            GuiUtils.JTrees.collapseAllNodes(tree);
                            GuiUtils.JTrees.expandToLevel(tree, 3);
                        } else {
                            revealMatchingNodes(text);
                        }
                    });
                }
            });
            final JCheckBoxMenuItem filterSearchItem = new JCheckBoxMenuItem("Filter Tree on Search", FILTER_SEARCH);
            filterSearchItem.setToolTipText("Collapse non-matching branches while searching");
            filterSearchItem.addItemListener(e -> FILTER_SEARCH = filterSearchItem.isSelected());
            searchableBar.addOptionsMenuItem(filterSearchItem);

            // Single-selection: deselect previous choice across all tabs
            if (singleSelection) {
                tree.getCheckBoxTreeSelectionModel().setSelectionMode(
                        TreeSelectionModel.SINGLE_TREE_SELECTION);
                tree.getCheckBoxTreeSelectionModel().addTreeSelectionListener(e -> {
                    if (e.isAddedPath()) {
                        for (final OntologyTab other : tabs) {
                            if (other != this) {
                                other.tree.getCheckBoxTreeSelectionModel()
                                        .clearSelection();
                            }
                        }
                    }
                });
            }

            // Selection listener to propagate changes
            tree.getCheckBoxTreeSelectionModel().addTreeSelectionListener(e -> fireSelectionChanged());

            // Layout
            final JScrollPane scrollPane = new JScrollPane(tree);
            scrollPane.setWheelScrollingEnabled(true);
            add(searchableBar, BorderLayout.NORTH);
            add(scrollPane, BorderLayout.CENTER);

            // Expand a few levels by default
            GuiUtils.JTrees.expandToLevel(tree, 3);
        }

        /**
         * Sets a custom cell renderer for the tree.
         *
         * @param renderer the cell renderer
         */
        public void setCellRenderer(final TreeCellRenderer renderer) {
            tree.setCellRenderer(renderer);
        }

        /**
         * Sets a predicate that controls which nodes have enabled checkboxes.
         * Nodes for which the predicate returns {@code false} will have their
         * checkboxes disabled (grayed out, non-clickable).
         *
         * @param predicate a predicate tested against each node's user object;
         *                  return {@code true} to enable the checkbox
         */
        public void setCheckBoxEnabledPredicate(final Predicate<Object> predicate) {
            tree.checkBoxEnabledPredicate = predicate;
        }

        /**
         * Sets a popup menu on the tree.
         *
         * @param popupMenu the popup menu
         */
        public void setPopupMenu(final JPopupMenu popupMenu) {
            tree.setComponentPopupMenu(popupMenu);
        }

        /**
         * Sets the status label placeholder text on the search bar.
         *
         * @param text the placeholder text (e.g., "CCF v3.1")
         */
        public void setStatusLabelPlaceholder(final String text) {
            searchableBar.setStatusLabelPlaceholder(text);
        }

        /**
         * Returns the search bar for additional configuration.
         *
         * @return the searchable bar
         */
        public SNTSearchableBar getSearchableBar() {
            return searchableBar;
        }

        /**
         * Returns the underlying checkbox tree.
         *
         * @return the checkbox tree
         */
        public CheckBoxTree getTree() {
            return tree;
        }

        /**
         * Sets whether the root node is visible.
         *
         * @param visible {@code true} to show the root
         */
        public void setRootVisible(final boolean visible) {
            tree.setRootVisible(visible);
        }

        /**
         * Sets whether checking a parent automatically checks all children.
         *
         * @param digIn {@code true} to enable dig-in selection
         */
        public void setDigIn(final boolean digIn) {
            tree.setDigIn(digIn);
        }

        /**
         * Expands the tree to the given depth level.
         *
         * @param level the depth to expand to
         */
        public void expandToLevel(final int level) {
            GuiUtils.JTrees.expandToLevel(tree, level);
        }

        /**
         * Adds a bottom panel (e.g., buttons) below the tree.
         *
         * @param panel the panel to add
         */
        public void setBottomPanel(final JPanel panel) {
            add(panel, BorderLayout.SOUTH);
        }

        /**
         * Programmatically checks the node whose user object's
         * {@code toString()} matches the given label.
         *
         * @param nodeLabel the label to match
         * @param selected  {@code true} to check, {@code false} to uncheck
         */
        public void setCheckboxSelected(final String nodeLabel,
                                        final boolean selected) {
            final DefaultMutableTreeNode node = findNode(nodeLabel);
            if (node == null) return;
            final TreePath path = new TreePath(node.getPath());
            if (selected)
                tree.getCheckBoxTreeSelectionModel().addSelectionPath(path);
            else
                tree.getCheckBoxTreeSelectionModel().removeSelectionPath(path);
        }

        /**
         * Finds a node by matching its user object's {@code toString()} against
         * the given label (depth-first search).
         *
         * @param label the label to find
         * @return the matching node, or {@code null}
         */
        public DefaultMutableTreeNode findNode(final String label) {
            final DefaultMutableTreeNode root =
                    (DefaultMutableTreeNode) tree.getModel().getRoot();
            final Enumeration<TreeNode> e = root.depthFirstEnumeration();
            while (e.hasMoreElements()) {
                final DefaultMutableTreeNode node =
                        (DefaultMutableTreeNode) e.nextElement();
                if (label.equals(node.getUserObject().toString())) {
                    return node;
                }
            }
            return null;
        }

        /**
         * Triggers a repaint on the tree (e.g., after external state changes
         * that affect the cell renderer).
         */
        public void repaintTree() {
            tree.repaint();
        }

        /**
         * Collapses the entire tree, then expands only the ancestor paths of
         * nodes whose {@code toString()} contains the search text
         * (case-insensitive). Called from the filter-search DocumentListener.
         */
        private void revealMatchingNodes(final String text) {
            final String lower = text.toLowerCase();
            final DefaultMutableTreeNode root =
                    (DefaultMutableTreeNode) tree.getModel().getRoot();
            // Collect ancestor paths of matching leaves
            final List<TreePath> pathsToExpand = new ArrayList<>();
            final Enumeration<TreeNode> e = root.depthFirstEnumeration();
            while (e.hasMoreElements()) {
                final DefaultMutableTreeNode node =
                        (DefaultMutableTreeNode) e.nextElement();
                if (node.getUserObject().toString().toLowerCase().contains(lower)) {
                    // Expand up to the parent so the matching node is visible
                    final TreeNode[] ancestors = node.getPath();
                    if (ancestors.length > 1) {
                        pathsToExpand.add(new TreePath(
                                ((DefaultMutableTreeNode) ancestors[ancestors.length - 2]).getPath()));
                    }
                }
            }
            GuiUtils.JTrees.collapseAllNodes(tree);
            for (final TreePath p : pathsToExpand) {
                tree.expandPath(p);
            }
            // Scroll to first match if any
            if (!pathsToExpand.isEmpty()) {
                tree.scrollPathToVisible(pathsToExpand.getFirst());
            }
        }

        /**
         * Creates the default popup menu with generic tree navigation actions:
         * Deselect All, Uncheck All, Collapse/Expand All, Collapse/Expand
         * Selected Level, and Auto-select Children toggle. Callers can append tab-specific items
         * to the returned menu before installing it via {@link #setPopupMenu(JPopupMenu)}.
         *
         * @return a new popup menu with standard tree controls
         */
        public JPopupMenu createDefaultPopupMenu() {
            final JPopupMenu pMenu = new JPopupMenu();
            JMenuItem jmi = new JMenuItem("Deselect All");
            jmi.addActionListener(e -> tree.clearSelection());
            pMenu.add(jmi);
            jmi = new JMenuItem("Uncheck All");
            jmi.addActionListener(e -> tree.getCheckBoxTreeSelectionModel().clearSelection());
            pMenu.add(jmi);
            pMenu.addSeparator();
            jmi = new JMenuItem("Collapse All");
            jmi.addActionListener(e -> GuiUtils.JTrees.collapseAllNodes(tree));
            pMenu.add(jmi);
            jmi = new JMenuItem("Collapse Selected Level");
            jmi.addActionListener(e -> {
                final TreePath selectedPath = tree.getSelectionPath();
                if (selectedPath != null)
                    GuiUtils.JTrees.collapseNodesOfSameLevel(tree, selectedPath);
            });
            pMenu.add(jmi);
            pMenu.addSeparator();
            jmi = new JMenuItem("Expand All");
            jmi.addActionListener(e -> {
                GuiUtils.JTrees.expandAllNodes(tree);
                if (!searchableBar.getSearchField().getText().isEmpty())
                    searchableBar.getSearchField().setText(
                            searchableBar.getSearchField().getText());
            });
            pMenu.add(jmi);
            jmi = new JMenuItem("Expand Selected Level");
            jmi.addActionListener(e -> {
                final TreePath selectedPath = tree.getSelectionPath();
                if (selectedPath != null)
                    GuiUtils.JTrees.expandNodesOfSameLevel(tree, selectedPath);
            });
            pMenu.add(jmi);
            pMenu.addSeparator();
            final JCheckBoxMenuItem jcmi = new JCheckBoxMenuItem("Auto-select Children",
                    tree.getCheckBoxTreeSelectionModel().isDigIn());
            jcmi.addItemListener(e -> tree.getCheckBoxTreeSelectionModel().setDigIn(jcmi.isSelected()));
            pMenu.add(jcmi);
            return pMenu;
        }

        List<String> getSelectedPaths() {
            final List<String> paths = new ArrayList<>();
            final TreePath[] treePaths = tree
                    .getCheckBoxTreeSelectionModel().getSelectionPaths();
            if (treePaths == null) return paths;
            final Function<TreePath, String> fmt = pathFormatter;
            for (final TreePath tp : treePaths) {
                paths.add(fmt != null ? fmt.apply(tp) : serializePath(tp));
            }
            return paths;
        }
    }

    /**
     * Default cell renderer for Allen CCF ontology trees. Displays a colored
     * square icon for each compartment based on its ontological color.
     * Subclasses can override to add additional behavior (e.g., disabling
     * nodes without available meshes).
     */
    public static class AllenCCFRenderer extends DefaultTreeCellRenderer {

        @Serial
        private static final long serialVersionUID = 1L;

        @Override
        public Component getTreeCellRendererComponent(final JTree tree,
                                                      final Object value, final boolean sel, final boolean expanded,
                                                      final boolean leaf, final int row, final boolean hasFocus) {
            final Component c = super.getTreeCellRendererComponent(tree,
                    value, sel, expanded, leaf, row, hasFocus);
            final DefaultMutableTreeNode node = (DefaultMutableTreeNode) value;
            final Object userObject = node.getUserObject();
            if (userObject instanceof AllenCompartment ac) {
                if (ac.id() == AllenUtils.BRAIN_ROOT_ID) {
                    setIcon(null);
                } else {
                    final ColorRGB color = ac.color();
                    if (color != null)
                        setIcon(IconFactory.nodeIcon(
                                new java.awt.Color(color.getARGB())));
                }
            }
            return c;
        }
    }

    /**
     * Cell renderer that hides file/folder icons, suitable for ontologies
     * without per-node color information.
     */
    static class NoIconRenderer extends DefaultTreeCellRenderer {

        @Serial
        private static final long serialVersionUID = 1L;

        @Override
        public Component getTreeCellRendererComponent(final JTree tree,
                                                      final Object value, final boolean sel, final boolean expanded,
                                                      final boolean leaf, final int row, final boolean hasFocus) {
            final Component c = super.getTreeCellRendererComponent(tree,
                    value, sel, expanded, leaf, row, hasFocus);
            setIcon(null);
            return c;
        }
    }

    static class BrowserTree extends CheckBoxTree {

        @Serial
        private static final long serialVersionUID = 1L;
        Predicate<Object> checkBoxEnabledPredicate;

        BrowserTree(final DefaultTreeModel model) {
            super(model);
            setLargeModel(true);
        }

        @Override
        public TreePath getNextMatch(final String prefix,
                                     final int startingRow,
                                     final Position.Bias bias) {
            return null; // avoid conflict with search bar
        }

        @Override
        public boolean isCheckBoxEnabled(final TreePath treePath) {
            if (checkBoxEnabledPredicate == null) return true;
            final DefaultMutableTreeNode node =
                    (DefaultMutableTreeNode) treePath.getLastPathComponent();
            return checkBoxEnabledPredicate.test(node.getUserObject());
        }
    }
}
