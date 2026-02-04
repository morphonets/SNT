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

import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.ui.FlatPopupFactory;
import com.formdev.flatlaf.util.SystemInfo;
import org.apache.commons.text.WordUtils;
import org.scijava.util.PlatformUtils;
import sc.fiji.snt.SNTUI;
import sc.fiji.snt.viewer.Viewer3D;

import javax.swing.Timer;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;
import java.awt.event.*;
import java.lang.reflect.Method;
import java.util.List;
import java.util.*;

/**
 * Implements SNT's Command Palette. An older version of this code is used by Fiji's Script
 * Editor. In the future, should move to a common library to avoid this kind of duplication.
 */
public class SNTCommandFinder {

    private static final String NAME = "Command Palette";

    // Settings. Ought to become adjustable some day
    private static final KeyStroke ACCELERATOR = KeyStroke.getKeyStroke(KeyEvent.VK_P,
            java.awt.Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx() | KeyEvent.SHIFT_DOWN_MASK);
    private static final int TABLE_ROWS = 6; // no. of commands to be displayed
    private static final Font REF_FONT = refFont();
    private final CmdAction noHitsCmd;
    private final CmdScrapper cmdScrapper;
    private final List<String> keyWordsToIgnoreInMenuPaths; // menus to be ignored
    private final int maxPath; // No. of submenus to be included in path description
    private final String widestCmd; // String defining the width of first column of the palette list
    private final SNTUI sntui;
    private final Viewer3D viewer3D;
    private boolean alwaysOnTop; // Always on top of other windows?
    private boolean caseSensitive; // Case-sensitive matching?
    private boolean autoHide = true; // Hide after command is executed?
    private boolean ignoreWhiteSpace = true; // Ignore white spaces while matching?

    private Palette frame;
    private ScriptRecorder recorder;
    private JToggleButton recButton;
    private SearchField searchField;
    private CmdTable table;
    private boolean scriptCall;

    /**
     * Constructs a new SNTCommandFinder for the specified SNTUI instance.
     *
     * @param sntui the SNTUI instance to associate with this command finder
     */
    public SNTCommandFinder(final SNTUI sntui) {
        this.sntui = sntui;
        this.viewer3D = null;
        noHitsCmd = new SearchWebCmd();
        cmdScrapper = new CmdScrapper();
        maxPath = 2;
        keyWordsToIgnoreInMenuPaths = Arrays.asList("Full List", "Batch Scripts"); // alias menus listing cmds elsewhere
        widestCmd = "Path Visibility Filter: 3. Only Active Channel/Frame  ";
    }

    /**
     * Constructs a new SNTCommandFinder for the specified Viewer3D instance.
     *
     * @param viewer3D the Viewer3D instance to associate with this command finder
     */
    public SNTCommandFinder(final Viewer3D viewer3D) {
        this.sntui = null;
        this.viewer3D = viewer3D;
        noHitsCmd = new SearchWebCmd();
        cmdScrapper = new CmdScrapper(); // recViewer commands are all registered in cmdScrapper.otherMap
        maxPath = 1;
        keyWordsToIgnoreInMenuPaths = Collections.singletonList("Select"); // None, All, Trees, etc.: hard to interpreter w/o context
        widestCmd = "Rebuild Index of Actions/Commands.. ";
    }

    static Font refFont() {
        try {
            return UIManager.getFont("TextField.font");
        } catch (final Exception ignored) {
            return new JTextField().getFont();
        }
    }

    @SuppressWarnings("unused")
    Map<String, String> getShortcuts() {
        if (cmdScrapper.scrapeFailed())
            cmdScrapper.scrape();
        final TreeMap<String, String> result = new TreeMap<>();
        cmdScrapper.getCmdMap().forEach((id, cmdAction) -> {
            if (cmdAction.hotkey != null && !cmdAction.hotkey.isEmpty())
                result.put(id, cmdAction.hotkey);
        });
        return result;
    }

    private Action getAction() {
        final Action action = new AbstractAction(NAME) {

            private static final long serialVersionUID = -7030359886427866104L;
            @SuppressWarnings("deprecation")
            @Override
            public void actionPerformed(final ActionEvent e) {
                if (frame != null && (e.getModifiers() & InputEvent.SHIFT_MASK) != 0) { //InputEvent.SHIFT_DOWN_MASK never triggered!?
                    frame.setLocationRelativeTo(null); // fallback if frame is lost on a second screen
                }
                if (!autoHide && frame != null && frame.isVisible()) {
                    frame.toFront();
                    searchField.requestFocus();
                } else
                    toggleVisibility();
            }

        };
        action.putValue(Action.ACCELERATOR_KEY, ACCELERATOR);
        return action;
    }

    /**
     * Disposes of this command finder and its associated resources.
     */
    public void dispose() {
        if (frame != null)
            frame.dispose();
        frame = null;
    }

    private void autoHide() {
        if (autoHide) hideWindow();
    }

    private void hideWindow() {
        if (frame != null) frame.setVisible(false);
    }

    private void assemblePalette() {
        frame = new Palette();
        frame.setLayout(new BorderLayout());
        initSearchField();
        final Object menuBarEmbedded = frame.getRootPane().getClientProperty("JRootPane.menuBarEmbedded");
        if (menuBarEmbedded instanceof Boolean && (boolean) menuBarEmbedded)
            frame.setJMenuBar(new ToolBarOrMenuBar().getMenuBar());
        else
            frame.add(new ToolBarOrMenuBar().getToolBar(), BorderLayout.NORTH);
        frame.add(searchField, BorderLayout.CENTER);
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
        frame.getContentPane().setBackground(searchField.getBackground());
        frame.add(table.getScrollPane(), BorderLayout.SOUTH);
        frame.pack();
        if (sntui != null) {
            GuiUtils.centerWindow(frame, sntui, sntui.getPathManager());
        } else {
            GuiUtils.centerWindow(frame, viewer3D.getFrame(), viewer3D.getManagerPanel().getWindow());
        }
    }

    private void initSearchField() {
        searchField = new SearchField("Search for commands and actions (e.g., Sholl)",
                SearchField.OPTIONS_MENU + SearchField.CASE_BUTTON + SearchField.REGEX_BUTTON);
        searchField.enlarge(1.2f);
        searchField.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, GuiUtils.getDisabledComponentColor()),
                searchField.getBorder()));
        // options button
        final JMenuItem rebuildIndex = new JMenuItem("Rebuild Index...");
        rebuildIndex.addActionListener(e1 -> rebuildIndex());
        searchField.getOptionsMenu().add(rebuildIndex);
        // case button
        searchField.caseButton().setSelected(caseSensitive);
        searchField.caseButton().addItemListener(e -> {
            caseSensitive = searchField.caseButton().isSelected();
            searchField.setText(searchField.getText());
        });
        // use regex button for whitespace options
        searchField.regexButton().setText("· ␣");
        searchField.regexButton().setSelected(!ignoreWhiteSpace);
        searchField.regexButton().setToolTipText("Match Whitespace");
        searchField.regexButton().addItemListener(e -> {
            ignoreWhiteSpace = ! searchField.regexButton().isSelected();
            searchField.setText(searchField.getText());
        });
    }

    void rebuildIndex() {
        resetScrapper();
        final String placeholder = searchField.getClientProperty(FlatClientProperties.PLACEHOLDER_TEXT).toString();
        searchField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, String.format("Index Rebuilt: %d commands/actions available...",
                (cmdScrapper.cmdMap.size() + cmdScrapper.otherMap.size())));
        final Timer timer = new Timer(3000, e -> searchField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, placeholder));
        timer.setRepeats(false);
        timer.start();
    }

    private String[] makeRow(final CmdAction ca) {
        return new String[]{ca.id, ca.description()};
    }

    private void populateList(final String matchingSubstring) {
        final ArrayList<String[]> list = new ArrayList<>();
        if (cmdScrapper.scrapeFailed())
            cmdScrapper.scrape();
        cmdScrapper.getCmdMap().forEach((id, cmd) -> {
            if (cmd.matches(matchingSubstring)) {
                list.add(makeRow(cmd));
            }
        });
        final boolean noHits = list.isEmpty();
        if (noHits) list.add(makeRow(noHitsCmd));
        table.getInternalModel().setData(list);
        if (searchField != null) {
            searchField.setWarningOutlineEnabled(noHits);
            searchField.requestFocus();
        }
    }

    private void runCmd(final String command) {
        SwingUtilities.invokeLater(() -> {
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
            if (cmd.hasButton() && (!cmd.button.isEnabled()
                    || (cmd.button instanceof JMenuItem && !cmd.button.getParent().isEnabled()))) {
                error();
                return;
            }
            if (!scriptCall)
                autoHide(); // hide before running, in case command opens a dialog
            if (cmd.hasButton()) {
                cmd.button.doClick();
            } else if (cmd.action != null) {
                cmd.action.actionPerformed(new ActionEvent(this, ActionEvent.ACTION_PERFORMED, cmd.id));
            }
        }
    }

    private void error() {
        assert frame != null;
        final String MSG = "<HTML>Command is currently disabled. Either execution "
                + "requirements<br>are unmet or it cannot run in current state.";
        if (autoHide) {
            // frame.setVisible(false);
            frame.guiUtils.error(MSG);
            return;
        }
        class FloatingError extends JDialog {
            public FloatingError() {
                super(frame);
                setUndecorated(true);
                setModal(true);
                setResizable(false);
                final JLabel label = new JLabel(MSG);
                label.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
                GuiUtils.applyRoundCorners(label);
                add(label);
                pack();
                GuiUtils.centerWindow(this, frame);
                setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
                setFocusable(true);
                addKeyListener(new KeyAdapter() {
                    @Override
                    public void keyPressed(final KeyEvent e) {
                        if (e.getKeyCode() == KeyEvent.VK_BACK_SPACE || e.getKeyCode() == KeyEvent.VK_ESCAPE) {
                            dispose();
                        }
                    }
                });
                addMouseListener(new MouseAdapter() {
                    @Override
                    public void mouseClicked(final MouseEvent e) {
                        dispose();
                    }

                    @Override
                    public void mousePressed(final MouseEvent e) {
                        dispose();
                    }
                });
                addFocusListener(new FocusAdapter() {
                    @Override
                    public void focusLost(final FocusEvent e) {
                        dispose();
                    }
                });
            }
            @Override
            public void dispose() {
                super.dispose();
                frame.setAlwaysOnTop(alwaysOnTop);
                frame.toFront();
                searchField.requestFocus();
            }
        }
        frame.setAlwaysOnTop(true);
        final FloatingError d = new FloatingError();
        d.setVisible(true);
        d.toFront();
    }

    private void recordCommand(final CmdAction cmdAction) {
        if (recorder == null)
            return;
        if (cmdAction.id.startsWith("<HTML>") || cmdAction.description().startsWith("Scripts")) {
            recordComment("Non-recordable command... [id: " + cmdAction.id + "]");
            return;
        }
        if (recordPresetAPICall(cmdAction))
            return;
        if (sntui != null)
            recordCmdSNTUI(cmdAction);
        else
            recordSNTViewerCmd(cmdAction);
    }

    private boolean recordPresetAPICall(final CmdAction cmdAction) {
        if (cmdAction.hasButton()) {
            final String cmd = ScriptRecorder.getRecordingCall(cmdAction.button);
            if (cmd != null) {
                if (!ScriptRecorder.IGNORED_CMD.equals(cmd))
                    recorder.recordCmd(cmd);
                return true;
            }
        }
        return false;
    }

    private void recordCmdSNTUI(final CmdAction cmdAction) {
        final StringBuilder sb = new StringBuilder();
        final boolean pmCmd = cmdAction.pathDescription().startsWith("PM") || cmdAction.pathDescription().startsWith("Tag")
                || cmdAction.pathDescription().startsWith("Edit") || cmdAction.pathDescription().startsWith("Refine")
                || cmdAction.pathDescription().startsWith("Fill") || cmdAction.pathDescription().startsWith("Analyze");
        final boolean promptCmd = cmdAction.id.endsWith("...");
        if (pmCmd) {
            sb.append("snt.getUI().getPathManager().runCommand(\"").append(cmdAction.id);
        } else {
            sb.append("snt.getUI().runCommand(\"").append(cmdAction.id);
        }
        if (promptCmd) {
            switch (cmdAction.id) {
                // FIXME: This should be moved to the executing command
                case "Path-based Distributions...":
                case "Branch-based Distributions...":
                    sb.append("\", \"metric 1 chosen in prompt\", \"[true or false (default) for polar histogram]\", \"[metric 2]\", \"[...]\")");
                    break;
                case "Convert to ROIs...":
                    sb.append("\", \"ROI type chosen in prompt\", \"[optional view (default is 'XY')]\")");
                    break;
                case "Branch-based Color Mapping...":
                case "Path-based Color Mapping...":
                case "Color Code Path(s)...": // backwards compatibility
                case "Color Code Cell(s)...": // backwards compatibility
                    sb.append("\", \"metric chosen in prompt\", \"LUT chosen in prompt\")");
                    break;
                default:
                    noOptionsRecorderComment();
                    sb.append("\", \"comma-separated\", \"options\", \"here\", \"(if any)\")");
                    break;
            }
        } else {
            sb.append("\")");
        }
        recorder.recordCmd(sb.toString());
    }

    private void noOptionsRecorderComment() {
        recorder.recordComment("NB: Next line may rely on non-recorded prompt options. See documentation for examples");
    }

    private void recordSNTViewerCmd(final CmdAction cmdAction) {
        if (cmdAction.id.endsWith("..."))
            noOptionsRecorderComment();
        recorder.recordCmd("viewer.runCommand(\"" + cmdAction.id + "\")");
    }

    /**
     * Sets the script recorder for this command finder.
     *
     * @param recorder the ScriptRecorder instance to use for recording commands
     */
    public void setRecorder(final ScriptRecorder recorder) {
        this.recorder = recorder;
        if (cmdScrapper.scrapeFailed())
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
        SwingUtilities.invokeLater(() -> runCmd(cmdAction));
        scriptCall = false;
    }

    private void recordComment(final String string) {
        if (recorder == null)
            System.out.println(">> " + string);
        else
            recorder.recordComment(string);
    }

    /**
     * Toggles the visibility of the command finder window.
     */
    public void toggleVisibility() {
        if (frame == null || table == null) {
            assemblePalette();
        }
        if (frame.isVisible()) {
            autoHide();
        } else {
            table.clearSelection();
            frame.setVisible(true);
            searchField.requestFocus();
        }
    }

    public void attach(final JDialog dialog) {
        final int condition = JPanel.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT; //JPanel.WHEN_IN_FOCUSED_WINDOW;
        final InputMap inputMap = ((JPanel) dialog.getContentPane()).getInputMap(condition);
        final ActionMap actionMap = ((JPanel) dialog.getContentPane()).getActionMap();
        inputMap.put(ACCELERATOR, NAME);
        actionMap.put(NAME, getAction());
    }

    public JButton getButton(final float scaleFactor) {
        final JButton button = new JButton(getAction());
        button.setIcon(IconFactory.buttonIcon(IconFactory.GLYPH.SEARCH, scaleFactor));
        button.getInputMap().put(ACCELERATOR, NAME);
        button.setToolTipText(NAME + "  " + getAcceleratorString());
        button.setText(null);
        return button;
    }

    public AbstractButton getMenuItem(final boolean asButton) {
        if (asButton) {
            final JButton button = new JButton(getAction());
            button.setIcon(IconFactory.menuIcon(IconFactory.GLYPH.SEARCH));
            button.setText(null);
            button.setFocusable(false);
            GuiUtils.Buttons.makeBorderless(button);
            button.setToolTipText(NAME + "  " + getAcceleratorString());
            return button;
        }
        final JMenuItem jmi = new JMenuItem(getAction());
        jmi.setToolTipText("Find Command/Action...");
        jmi.setIcon(IconFactory.menuIcon(IconFactory.GLYPH.SEARCH));
        return jmi;
    }

    public void register(final AbstractButton button, final String... description) {
        register(button, new ArrayList<>(Arrays.asList(description)));
    }

    public void register(final AbstractButton button, final List<String> pathDescription) {
        cmdScrapper.registerOther(button, pathDescription);
    }

    public void register(final JPopupMenu menu, final List<String> path) {
        for (final Component component : menu.getComponents()) {
            if (component instanceof JMenu) {
                final List<String> newPath = new ArrayList<>(path);
                final String menuText = ((JMenu) component).getText();
                if (menuText != null) newPath.add(menuText);
                registerMenu((JMenu) component, newPath);
            } else if (component instanceof AbstractButton)
                register((AbstractButton) component, path);
        }
    }

    public AbstractButton getRegisteredComponent(final String label) {
        final CmdAction cmd = cmdScrapper.getCmdMap().get(label);
        return (cmd == null) ? null : cmd.button;
    }

    private void registerMenu(final JMenu menu, final List<String> path) {
        for (final Component component : menu.getMenuComponents()) {
            if (component instanceof JMenu) {
                final List<String> newPath = new ArrayList<>(path);
                final String menuText = ((JMenu) component).getText();
                if (menuText != null) newPath.add(menuText);
                registerMenu((JMenu) component, newPath);
            } else if (component instanceof AbstractButton) {
                register((AbstractButton) component, path);
            }
        }
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
            if (sntui == null)
                recorder = viewer3D.getRecorder(true);
            else
                recorder = sntui.getRecorder(true);
            recorder.addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosing(final WindowEvent e) {
                    if (recButton != null) recButton.setSelected(false);
                    recorder = null;
                }
            });
        }
    }

    private static class ToolbarIcons {
        static final float SIZE = UIManager.getFont("Button.font").getSize() * .9f;
        static final Color COLOR = UIManager.getColor("SearchField.searchIconColor");

        static Icon lockClosed() { return IconFactory.get(IconFactory.GLYPH.LOCK, SIZE, COLOR); }

        static Icon lockOpen() {
            return IconFactory.get(IconFactory.GLYPH.LOCK_OPEN, SIZE, COLOR);
        }

        static Icon pin() { return IconFactory.get(IconFactory.GLYPH.MAP_PIN, SIZE, COLOR); }

        static Icon record() { return IconFactory.get(IconFactory.GLYPH.DOTCIRCLE, SIZE, COLOR); }
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
            final String[] strings = list.get(row);
            return strings[column];
        }

        @Override
        public int getRowCount() {
            return list.size();
        }

    }

    private record AnnotatedComponent(Component component, String annotation) {

        AnnotatedComponent(final Component component) {
                this(component, "");
            }
        }

    private class ToolBarOrMenuBar {

        JToolBar getToolBar() {
            final JToolBar toolbar = new JToolBar();
            toolbar.setFocusable(false);
            toolbar.setFloatable(false);
            toolbar.add(Box.createHorizontalGlue());
            toolbar.add(proHint());
            toolbar.add(Box.createHorizontalGlue());
            toolbar.add(pinButton());
            toolbar.add(lockButton());
            toolbar.addSeparator();
            toolbar.add(initRecordButton());
            if (frame.isUndecorated())
                new ComponentMover(frame, toolbar); // make frame draggable through toolbar
            return toolbar;
        }

        JMenuBar getMenuBar() {
            final JMenuBar menuBar = new JMenuBar();
            menuBar.setFocusable(false);
            List.of(pinButton(), lockButton(), initRecordButton()).forEach( b-> {
                if (menuBar.getComponentCount() > 0)
                    menuBar.add(Box.createHorizontalStrut(5));
                GuiUtils.Buttons.makeBorderless(b);
                menuBar.add(b);
            });
            menuBar.add(Box.createHorizontalGlue());
            menuBar.add(proHint());
            if (frame.isUndecorated())
                new ComponentMover(frame, menuBar); // make frame draggable through menuBar
            return menuBar;
        }

        JToggleButton lockButton() {
            final JToggleButton button = new JToggleButton(ToolbarIcons.lockOpen(), !autoHide);
            button.setSelectedIcon(ToolbarIcons.lockClosed());
            button.setToolTipText("Keep " + NAME + " open at all times");
            button.setFocusable(false);
            button.addItemListener(e -> autoHide = !button.isSelected());
            return button;
        }

        JToggleButton pinButton() {
            final JToggleButton button = new JToggleButton(ToolbarIcons.pin(), alwaysOnTop);
            button.setToolTipText("Keep " + NAME + " above all other windows");
            button.setFocusable(false);
            button.addItemListener(e -> frame.setAlwaysOnTop(alwaysOnTop = button.isSelected()));
            return button;
        }

        JLabel proHint() {
            final JLabel label = new JLabel("HINT: Arrows to navigate, Enter to select, Esc to close");
            label.setFont(label.getFont().deriveFont((float) (label.getFont().getSize() * .85)));
            label.setForeground(ToolbarIcons.COLOR);
            label.setFocusable(false);
            final Timer timer = new Timer(5000, e -> {
                label.setVisible(false);
                ((Timer)e.getSource()).stop();
            });
            timer.setRepeats(false);
            timer.start();
            return label;
        }

        JToggleButton initRecordButton() {
            recButton = new JToggleButton(ToolbarIcons.record());
            recButton.setToolTipText("Record executed actions in Script Recorder");
            recButton.setFocusable(false);
            recButton.addItemListener(e -> {
                if (recButton.isSelected()) {
                    initRecorder();
                    recorder.setVisible(true);
                    frame.setVisible(true);
                } else if (!autoHide && recorder != null && recorder.isVisible()) {
                    final boolean currentAlwaysOnTop = alwaysOnTop;
                    frame.setAlwaysOnTop(false);
                    if (frame.guiUtils.getConfirmation("Close Recorder?", "Dismiss Recorder")) {
                        recorder.dispose();
                        recorder = null;
                    }
                    frame.setAlwaysOnTop(currentAlwaysOnTop);
                }
            });
            return recButton;
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
            final String caseSensitiveQuery = (caseSensitive) ? text : text.toLowerCase();
            return (ignoreWhiteSpace) ? caseSensitiveQuery.replaceAll("\\s+", "") : caseSensitiveQuery;
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
            if (autoHide && (key == KeyEvent.VK_ESCAPE || (key == KeyEvent.VK_W && meta) || (key == KeyEvent.VK_P && meta))) {
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

    private class Palette extends JDialog {
        private static final long serialVersionUID = 1L;
        private final GuiUtils guiUtils;

        Palette() {
            super();
            setModal(false);
            setResizable(false);
            setAlwaysOnTop(alwaysOnTop);
            //setOpacity(OPACITY);
            final boolean builtinDecorationSupported = SystemInfo.isWindows_10_orLater || SystemInfo.isMacFullWindowContentSupported;
            if(builtinDecorationSupported) {
                getRootPane().putClientProperty("JRootPane.useWindowDecorations", true);
                getRootPane().putClientProperty("JRootPane.titleBarShowIcon", false);
                getRootPane().putClientProperty("JRootPane.titleBarShowTitle", false);
                getRootPane().putClientProperty("JRootPane.titleBarShowIconify", false);
                getRootPane().putClientProperty("JRootPane.titleBarShowMaximize", false);
                getRootPane().putClientProperty("JRootPane.titleBarShowClose", false);
                getRootPane().putClientProperty("FlatLaf.fullWindowContent", true);
                if (SystemInfo.isWindows) {
                    getRootPane().putClientProperty("JRootPane.menuBarEmbedded", true);
                } else if (SystemInfo.isMacOS) {
                    getRootPane().putClientProperty( "apple.awt.windowTitleVisible", false );
                    getRootPane().putClientProperty( "apple.awt.fullWindowContent", true );
                    getRootPane().putClientProperty( "apple.awt.transparentTitleBar", true );
                }
            } else {
                // We are left with linux, or something else not supporting client properties.
                // We'll remove all decorations provide a custom border and draggable ability
                setUndecorated(true);
            }

            guiUtils = new GuiUtils(Palette.this);
            // it should NOT be possible to minimize this component, but just to
            // be safe, we'll ensure the component is never in an awkward state
            addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosing(final WindowEvent e) {
                    autoHide();
                }

                @Override
                public void windowIconified(final WindowEvent e) {
                    autoHide();
                }

                @Override
                public void windowDeactivated(final WindowEvent e) {
                    autoHide();
                }
            });
        }

        @Override
        public void addNotify() {
            super.addNotify();
            if (isUndecorated()) {
                try {
                    final Method method = FlatPopupFactory.class.getDeclaredMethod("setupRoundedBorder", Window.class, Component.class, Component.class);
                    method.setAccessible(true);
                    method.invoke(null, this, this, getRootPane());
                } catch (final Exception ignored) {
                    // do nothing
                }
            }
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
            final int col0Width = renderer.maxWidth(0);
            final int col1Width = renderer.maxWidth(1);
            setDefaultRenderer(Object.class, renderer);
            getColumnModel().getColumn(0).setMaxWidth(col0Width);
            getColumnModel().getColumn(1).setMaxWidth(col1Width);
            setRowHeight(renderer.rowHeight());
            int height = TABLE_ROWS * getRowHeight();
            if (getRowMargin() > 0)
                height *= getRowMargin();
            setPreferredScrollableViewportSize(new Dimension(col0Width + col1Width, height));
            setFillsViewportHeight(true);
            setBackground(searchField.getBackground());
        }

        private JScrollPane getScrollPane() {
            final JScrollPane scrollPane = new JScrollPane(this, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                    JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED) {

                @Override
                public void updateUI() {
                    GuiUtils.recolorTracks(this, searchField.getBackground(), false);
                    super.updateUI();
                }
            };
            scrollPane.setWheelScrollingEnabled(true);
            scrollPane.setBorder(BorderFactory.createEmptyBorder(5,5,0,0));
            return scrollPane;
        }

        CmdTableModel getInternalModel() {
            return (CmdTableModel) getModel();
        }

    }

    private class CmdTableRenderer extends DefaultTableCellRenderer {

        private static final long serialVersionUID = 1L;
        private static final Font col0Font = REF_FONT.deriveFont(REF_FONT.getSize() * 1f);
        private static final Font col1Font = REF_FONT.deriveFont(REF_FONT.getSize() * .9f);
        private static final Color mainColor = IconFactory.defaultColor();
        private static final Color secColor = IconFactory.secondaryColor();

        @Override
        public Component getTableCellRendererComponent(final JTable table, final Object value, final boolean isSelected,
                                                       final boolean hasFocus, final int row, final int column) {
            final Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            if (column == 1) {
                setHorizontalAlignment(JLabel.RIGHT);
                setFont(col1Font);
                setForeground(secColor);
            } else {
                setHorizontalAlignment(JLabel.LEFT);
                setFont(col0Font);
                setForeground(mainColor);
            }
            return c;
        }

        int rowHeight() {
            return (int) (col0Font.getSize() * 1.8f);
        }

        int maxWidth(final int columnIndex) {
            if (columnIndex == 1)
                return SwingUtilities.computeStringWidth(getFontMetrics(col1Font),
                        "A really long menu>With long submenus>");
            return SwingUtilities.computeStringWidth(getFontMetrics(col0Font), widestCmd);
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

        boolean hasButton() {
            return button != null;
        }

        String pathDescription() {
            if (pathDescription == null) {
                final List<String> tail = path.subList(Math.max(path.size() - maxPath, 0), path.size());
                final StringBuilder sb = new StringBuilder();
                final Iterator<String> it = tail.iterator();
                if (it.hasNext()) {
                    sb.append(it.next());
                    while (it.hasNext()) {
                        sb.append(" › ").append(it.next());
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

        String tooltip() {
            if (!hasButton()) return ""; // e.g., REBUILD_ID CmdAction
            final String tooltip = button.getToolTipText();
            return (tooltip == null) ? "" : tooltip.replaceAll("<[^>]*>", ""); // Strip common HTML
        }

        boolean matches(final String query) {
            final String q = (ignoreWhiteSpace) ? query.replaceAll("\\s+","") : query;
            return caseAndWhitespaceSensitiveId().contains(q) || caseAndWhitespaceSensitivePathDescription().contains(q)
                    || caseAndWhitespaceSensitiveTooltip().contains(q);
        }

        String caseAndWhitespaceSensitiveId() {
            final String result = (caseSensitive) ? id : id.toLowerCase();
            return (ignoreWhiteSpace) ? result.replaceAll("\\s+", "") : result;
        }

        String caseAndWhitespaceSensitivePathDescription() {
            final String result = (caseSensitive) ? pathDescription() : pathDescription().toLowerCase();
            return (ignoreWhiteSpace) ? result.replaceAll("\\s+", "") : result;
        }

        String caseAndWhitespaceSensitiveTooltip() {
            final String result = (caseSensitive) ? tooltip() : tooltip().toLowerCase();
            return (ignoreWhiteSpace) ? result.replaceAll("\\s+", "") : result;
        }

        void setKeyString(final KeyStroke key) {
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
            return WordUtils.capitalize(string);
        }

    }

    private class CmdScrapper {
        private final TreeMap<String, CmdAction> cmdMap;
        private TreeMap<String, CmdAction> otherMap;

        CmdScrapper() {
            cmdMap = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        }

        List<AnnotatedComponent> getComponents() {
            final List<AnnotatedComponent> components = new ArrayList<>();
            if (sntui != null) {
                components.add(new AnnotatedComponent(
                        sntui.getTracingCanvasPopupMenu(), "Image Contextual Menu"));
                components.add(new AnnotatedComponent(sntui.getJMenuBar()));
                components.add(new AnnotatedComponent(
                        sntui.getPathManager().getJTree().getComponentPopupMenu(), "PM Contextual Menu")); // before PM's menu bar
                components.add(new AnnotatedComponent(sntui.getPathManager().getJMenuBar()));
                components.add(new AnnotatedComponent(sntui.getPathManager().getNavigationToolBar(), "PM Nav. Bar"));
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

        boolean scrapeFailed() {
            return cmdMap.isEmpty();
        }

        void scrape() {
            cmdMap.clear();
            for (final AnnotatedComponent ac : getComponents()) {
                if (ac == null)
                    continue;
                if (ac.component instanceof JMenuBar menuBar) {
                    final int topLevelMenus = menuBar.getMenuCount();
                    for (int i = 0; i < topLevelMenus; ++i) {
                        final JMenu topLevelMenu = menuBar.getMenu(i);
                        if (topLevelMenu != null && topLevelMenu.getText() != null) {
                            parseMenu(topLevelMenu, List.of(topLevelMenu.getText()));
                        }
                    }
                }
                else if (ac.component instanceof JPopupMenu popup) {
                    getMenuItems(popup).forEach(mi -> registerMenuItem(mi, List.of(ac.annotation)));
                }
                else if (ac.component instanceof JToolBar toolBar) {
                    for (final Component c : toolBar.getComponents()) {
                        if (c instanceof AbstractButton b)
                            try {
                                registerMain(b, List.of(ac.annotation));
                            } catch (final Exception ignored) {
                                // do nothing
                            }
                    }
                    if (toolBar.getComponentPopupMenu() != null)
                        getMenuItems(toolBar.getComponentPopupMenu()).forEach(mi -> registerMenuItem(mi, List.of(ac.annotation, "Menu")));

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
                if (m instanceof JMenu subMenu) {
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
                switch (me) {
                    case JMenu jMenu -> getMenuItems(jMenu, list);
                    case JMenuItem jMenuItem -> list.add(jMenuItem);
                    case null, default -> {
                    }
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
            return label == null || label.startsWith("<HTML>Help") || label.isBlank();
        }

        private void removeRecordActions(TreeMap<String, CmdAction> map) {
            if (map == null)
                return;
            map.values().forEach(cmdAction -> {
                if (cmdAction.hasButton()) {
                    for (ActionListener listener : cmdAction.button.getActionListeners()) {
                        if (listener instanceof RecordAction) {
                            cmdAction.button.removeActionListener(listener);
                        }
                    }
                }
            });
        }

        private void appendRecordActions(final TreeMap<String, CmdAction> map) {
            if (map == null)
                return;
            map.values().forEach(cmdAction -> {
                if (cmdAction.hasButton() && !hasRecordActionAppended(cmdAction.button))
                    cmdAction.button.addActionListener(new RecordAction(cmdAction));
            });
        }

        private boolean hasRecordActionAppended(final AbstractButton button) {
            for (final ActionListener l : button.getActionListeners()) {
                if (l instanceof RecordAction)
                    return true;
            }
            return false;
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
            if (button instanceof GuiUtils.Buttons.OptionsButton optionButton) {
                final String buttonPath = (String) (button.getClientProperty("cmdFinder"));
                final List<String> modPath = new ArrayList<>(path);
                if (buttonPath != null) modPath.add(buttonPath);
                SNTCommandFinder.this.register(optionButton.popupMenu, modPath);
            } else
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
            final CmdAction registeredAction = map.get(label);
            final KeyStroke accelerator = (isMenuItem) ? ((JMenuItem) button).getAccelerator() : null;
            if (registeredAction != null && accelerator != null) {
                registeredAction.setKeyString(accelerator);
            } else {
                final CmdAction ca = new CmdAction(label, button);
                ca.path = path;
                if (accelerator != null)
                    ca.setKeyString(accelerator);
                map.put(ca.id, ca);
            }
        }
    }

    private class RecordAction extends AbstractAction {
        static final String ID = "SNT_REC_ACTION";
        private static final long serialVersionUID = 6898683194911491963L;
        private final transient CmdAction cmdAction;

        public RecordAction(final CmdAction cmdAction) {
            super(ID);
            this.cmdAction = cmdAction;
        }

        @Override
        public void actionPerformed(final ActionEvent e) {
            if (recorder != null && !scriptCall)
                recordCommand(cmdAction);
        }
    }

    private class SearchWebCmd extends CmdAction {
        SearchWebCmd() {
            super("Search forum.image.sc");
            action = new AbstractAction() {
                @Override
                public void actionPerformed(final ActionEvent e) {
                    frame.guiUtils.searchForum(searchField.getText());
                }
            };
        }

        @Override
        String description() {
            return "|Unmatched action|";
        }
    }

    public String getAcceleratorString() {
        return (prettifiedKey(KeyStroke.getKeyStroke(ACCELERATOR.getKeyCode(), ACCELERATOR.getModifiers()))
                ).replace(" ", "");
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
        return switch (keyCode) {
            case KeyEvent.VK_COMMA, KeyEvent.VK_COLON -> ",";
            case KeyEvent.VK_PERIOD -> ".";
            case KeyEvent.VK_SLASH -> "/";
            case KeyEvent.VK_SEMICOLON -> ";";
            case KeyEvent.VK_EQUALS -> "=";
            case KeyEvent.VK_OPEN_BRACKET -> "[";
            case KeyEvent.VK_BACK_SLASH -> "\\";
            case KeyEvent.VK_CLOSE_BRACKET -> "]";
            case KeyEvent.VK_ENTER -> "↵";
            case KeyEvent.VK_BACK_SPACE -> "⌫";
            case KeyEvent.VK_TAB -> "↹";
            case KeyEvent.VK_CANCEL -> "Cancel";
            case KeyEvent.VK_CLEAR -> "Clear";
            case KeyEvent.VK_PAUSE -> "Pause";
            case KeyEvent.VK_CAPS_LOCK -> "⇪";
            case KeyEvent.VK_ESCAPE -> "Esc";
            case KeyEvent.VK_SPACE -> "Space";
            case KeyEvent.VK_PAGE_UP -> "⇞";
            case KeyEvent.VK_PAGE_DOWN -> "⇟";
            case KeyEvent.VK_END -> "END";
            case KeyEvent.VK_HOME -> "Home"; // "⌂";
            case KeyEvent.VK_LEFT -> "←";
            case KeyEvent.VK_UP -> "↑";
            case KeyEvent.VK_RIGHT -> "→";
            case KeyEvent.VK_DOWN -> "↓";
            case KeyEvent.VK_MULTIPLY -> "[Num ×]";
            case KeyEvent.VK_ADD -> "[Num +]";
            case KeyEvent.VK_SUBTRACT -> "[Num −]";
            case KeyEvent.VK_DIVIDE -> "[Num /]";
            case KeyEvent.VK_DELETE -> "⌦";
            case KeyEvent.VK_INSERT -> "Ins";
            case KeyEvent.VK_BACK_QUOTE -> "`";
            case KeyEvent.VK_QUOTE -> "'";
            case KeyEvent.VK_AMPERSAND -> "&";
            case KeyEvent.VK_ASTERISK -> "*";
            case KeyEvent.VK_QUOTEDBL -> "\"";
            case KeyEvent.VK_LESS -> "<";
            case KeyEvent.VK_GREATER -> ">";
            case KeyEvent.VK_BRACELEFT -> "{";
            case KeyEvent.VK_BRACERIGHT -> "}";
            case KeyEvent.VK_CIRCUMFLEX -> "^";
            case KeyEvent.VK_DEAD_TILDE -> "~";
            case KeyEvent.VK_DOLLAR -> "$";
            case KeyEvent.VK_EXCLAMATION_MARK -> "!";
            case KeyEvent.VK_LEFT_PARENTHESIS -> "(";
            case KeyEvent.VK_MINUS -> "-";
            case KeyEvent.VK_PLUS -> "+";
            case KeyEvent.VK_RIGHT_PARENTHESIS -> ")";
            case KeyEvent.VK_UNDERSCORE -> "_";
            default -> KeyEvent.getKeyText(keyCode);
        };
    }
}
