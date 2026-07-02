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
import org.apache.commons.text.WordUtils;
import org.apache.commons.text.similarity.LevenshteinDistance;
import org.scijava.util.PlatformUtils;
import sc.fiji.snt.SNTUI;
import sc.fiji.snt.TracerCanvas;
import sc.fiji.snt.util.SNTColor;
import sc.fiji.snt.viewer.Viewer3D;

import javax.swing.Timer;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;
import java.awt.event.*;
import java.util.List;
import java.util.*;

/**
 * Implements SNT's Command Palette. An older version of this code is used by Fiji's Script
 * Editor. In the future, should move to a common library to avoid this kind of duplication.
 */
public class SNTCommandFinder {

    private static final String NAME = "Command Palette";
    private static final LevenshteinDistance UNIT_LEVENSHTEIN = LevenshteinDistance.getDefaultInstance();
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
    private Point lastLocation; // last on-screen position, restored on re-open
    // Bumped whenever caseSensitive or ignoreWhiteSpace changes; CmdAction caches pre-normalized haystacks
    // keyed to this generation, so they are rebuilt only once per settings change rather than every keystroke
    private int normGeneration = 0;
    private boolean reveal;

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
        cmdScrapper.getCmdMap().forEach((key, cmdAction) -> {
            if (cmdAction.hotkey != null && !cmdAction.hotkey.isEmpty())
                result.put(cmdAction.id, cmdAction.hotkey);
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
        if (frame != null) {
            lastLocation = frame.getLocation(); // remember for next open
            frame.setVisible(false);
        }
    }

    private void assemblePalette() {
        frame = new Palette();
        frame.setLayout(new BorderLayout());
        initSearchField();
        frame.add(new TitleBar().getToolBar(), BorderLayout.NORTH);
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
        enableDrag(frame.getRootPane()); // always undecorated, so always need drag support
    }

    private static void enableDrag(final JComponent component) {
        final Point[] dragStart = {null};
        component.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(final MouseEvent e) {
                dragStart[0] = e.getPoint();
            }
            @Override
            public void mouseReleased(final MouseEvent e) {
                dragStart[0] = null;
            }
        });
        component.addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseDragged(final MouseEvent e) {
                if (dragStart[0] != null) {
                    final Window w = SwingUtilities.getWindowAncestor(component);
                    final Point loc = w.getLocation();
                    w.setLocation(loc.x + e.getX() - dragStart[0].x,
                                  loc.y + e.getY() - dragStart[0].y);
                }
            }
        });
    }

    private void initSearchField() {
        searchField = new SearchField("Search for actions and commands (e.g., Sholl)",
                SearchField.OPTIONS_MENU + SearchField.CASE_BUTTON + SearchField.REGEX_BUTTON);
        searchField.enlarge(1.25f);
        final int PADDING = (int) (searchField.getFont().getSize() * .7);
        final Color c = SNTColor.alphaColor(ToolbarButtons.COLOR, 50);
        searchField.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 1, 0, c),
                BorderFactory.createEmptyBorder(PADDING, PADDING, PADDING, PADDING)));
        // options button
        final JMenuItem rebuildIndex = new JMenuItem("Rebuild Index...", IconFactory.menuIcon(IconFactory.GLYPH.UNDO));
        rebuildIndex.addActionListener(e1 -> rebuildIndex());
        searchField.getOptionsMenu().add(rebuildIndex);
        // case button
        searchField.caseButton().setSelected(caseSensitive);
        searchField.caseButton().addItemListener(e -> {
            caseSensitive = searchField.caseButton().isSelected();
            normGeneration++; // invalidate cached haystacks
            searchField.setText(searchField.getText());
        });
        // use regex button for whitespace options
        searchField.regexButton().setText("· ␣");
        searchField.regexButton().setSelected(!ignoreWhiteSpace);
        searchField.regexButton().setToolTipText("Match Whitespace");
        searchField.regexButton().addItemListener(e -> {
            ignoreWhiteSpace = !searchField.regexButton().isSelected();
            normGeneration++; // invalidate cached haystacks
            searchField.setText(searchField.getText());
        });
    }

    void rebuildIndex() {
        resetScrapper();
        displayTempMsg(String.format("Index Rebuilt: %d commands/actions available...", cmdScrapper.getCmdMap().size()), false);
    }

    private void displayTempMsg(final String msg, final boolean warning) {
        final String existingPlaceholder = searchField.getClientProperty(FlatClientProperties.PLACEHOLDER_TEXT).toString();
        final String existingText = searchField.getText();
        searchField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, msg);
        searchField.setText(null);
        if (warning) searchField.setBackground(GuiUtils.warningColor());
        final Timer timer = new Timer((warning) ? 3000 : 6000, e -> {
            searchField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, existingPlaceholder);
            searchField.setText(existingText);
            if (warning) searchField.setBackground(table.getBackground());
        });
        timer.setRepeats(false);
        SwingUtilities.invokeLater( () -> {
            frame.setVisible(true);
            timer.start();
        });
    }

    private void populateList(final String matchingSubstring) {
        final List<CmdAction> list = new ArrayList<>();
        if (cmdScrapper.scrapeFailed())
            cmdScrapper.scrape();
        cmdScrapper.getCmdMap().forEach((key, cmd) -> {
            if (cmd.matches(matchingSubstring)) {
                list.add(cmd);
            }
        });
        final boolean noHits = list.isEmpty();
        if (noHits) list.add(noHitsCmd);
        table.getInternalModel().setData(list);
        if (searchField != null) {
            searchField.setBackground((noHits) ? GuiUtils.warningColor() : table.getBackground());
            //searchField.setWarningOutlineEnabled(noHits); // this won't work because we are overriding the margin
            searchField.requestFocus();
        }
    }

    private void runCmd(final CmdAction cmd) {
        if (cmd != null) {
            if (reveal) {
                revealCmd(cmd);
            } else {
                if (!scriptCall) autoHide(); // hide before running, in case command opens a dialog
                executeCmd(cmd);
            }
        }
    }

    private void executeCmd(final CmdAction cmd) {
        if (cmd.hasButton()) {
            cmd.button.doClick();
        } else if (cmd.action != null) {
            cmd.action.actionPerformed(new ActionEvent(this, ActionEvent.ACTION_PERFORMED, cmd.id));
        }
    }

    private void revealCmd(final CmdAction cmd) {
        if (cmd == null) {
            displayTempMsg("Command cannot be revealed.", true);
            return;
        }
        if (cmd.button == null && cmd.action != null || cmd.button != null && cmd.button.getParent() == null) {
            // special cases: proxy buttons or proxy actions: executing IS the reveal
            executeCmd(cmd);
        } else if (cmd.button instanceof JMenuItem jmi)
            revealMenuItem(jmi);
        else
            revealButton(cmd);
    }

    // Open the menu/popup, arm the item, leave it to the user
    private void revealMenuItem(final JMenuItem jmi) {
        final MenuElement[] path = GuiUtils.MenuItems.getMenuPath(jmi);
        if (path.length > 1) {
            // If path[0] is a JMenu (not a JMenuBar), getMenuPath stripped the root standalone
            // popup to avoid a FlatLaf Linux NPE. We must show that popup first so the submenu
            // chain has a visual anchor; path[0].getParent() is the stripped popup
            if (path[0] instanceof JMenu rootMenu
                    && rootMenu.getParent() instanceof JPopupMenu rootPopup) {
                if (!showStandalonePopup(rootPopup, jmi)) return;
                // popup.show() just set the invoker and internally called setSelectedPath([rootPopup]). If we pass
                // the stripped path as-is, the manager de-arms rootPopup (removes it from selection) -> it hides ->
                // flicker. Prepend rootPopup so it stays armed throughout the transition
                final MenuElement[] fullPath = new MenuElement[path.length + 1];
                fullPath[0] = rootPopup;
                System.arraycopy(path, 0, fullPath, 1, path.length);
                try {
                    MenuSelectionManager.defaultManager().setSelectedPath(fullPath);
                    jmi.setArmed(true);
                } catch (final NullPointerException npe) {
                    displayTempMsg("Menu item is not available!", true);
                }
            } else {
                try {
                    MenuSelectionManager.defaultManager().setSelectedPath(path);
                    jmi.setArmed(true);
                } catch (final NullPointerException npe) {
                    displayTempMsg("Menu item is not available!", true);
                }
            }
        } else if (jmi.getParent() instanceof JPopupMenu popup) {
            // Root-level item in a standalone popup (path reduced to [jmi] by getMenuPath)
            if (showStandalonePopup(popup, jmi))
                jmi.setArmed(true);
        }
    }

    // Resolves the invoker of a standalone JPopupMenu, reveals its parent tab if needed,
    // and shows the popup. Returns false (with an error message) if the popup cannot be shown
    private boolean showStandalonePopup(final JPopupMenu popup, final JMenuItem jmi) {
        Component invoker = popup.getInvoker();
        if (invoker == null) {
            final Object owner = popup.getClientProperty("owner");
            if (owner instanceof Component c) invoker = c;
        }
        if (invoker == null) {
            displayTempMsg(String.format("%s is not available.", jmi.getText()), true);
            return false;
        }
        if (invoker instanceof Container c) revealParent(c);
        if (!invoker.isDisplayable()) {
            displayTempMsg(
                    (invoker instanceof TracerCanvas) ? "Image contextual menu is not available!" :
                            String.format("%s is not available.", jmi.getText()), true);
            return false;
        }
        popup.show(invoker, invoker.getWidth() / 2, invoker.getHeight() / 2);
        return true;
    }

    // Blink the button to indicate where it is, then stop (no auto-execute in reveal mode)
    private void revealButton(final CmdAction cmd) {
        final Color originalBackground = cmd.button.getBackground();
        final boolean originalOpaque = cmd.button.isOpaque();
        final boolean originalContentFilled = cmd.button.isContentAreaFilled();

        final Timer timer = new Timer(500, null);
        timer.addActionListener(new ActionListener() {
            int currentTick = 0;
            boolean isBlinkingOn = false;
            final int MAX_TICKS = 4; // 2 on/off cycle

            @Override
            public void actionPerformed(final ActionEvent e) {
                currentTick++;
                if (currentTick > MAX_TICKS) {
                    timer.stop();
                    cmd.button.setBackground(originalBackground);
                    cmd.button.setOpaque(originalOpaque);
                    cmd.button.setContentAreaFilled(originalContentFilled);
                    cmd.button.repaint();
                    return;
                }
                if (currentTick == 1) revealParent(cmd.button);
                isBlinkingOn = !isBlinkingOn;
                if (isBlinkingOn) {
                    cmd.button.setBackground(GuiUtils.getSelectionColor());
                    cmd.button.setOpaque(true);
                    cmd.button.setContentAreaFilled(true);
                } else {
                    cmd.button.setBackground(originalBackground);
                    cmd.button.setOpaque(originalOpaque);
                    cmd.button.setContentAreaFilled(originalContentFilled);
                }
                cmd.button.repaint();
            }
        });
        timer.start();
    }

    private void revealParent(Container prev) {
        Container p = prev.getParent();
        label:
        while (p != null) {
            switch (p) {
                case JScrollPane scp:
                    scp.scrollRectToVisible(SwingUtilities.convertRectangle(
                            prev.getParent(), prev.getBounds(), scp.getViewport()));
                    break label;
                case JTabbedPane tbp:
                    final int idx = tbp.indexOfComponent(prev);
                    if (idx >= 0) tbp.setSelectedIndex(idx);
                    break;
                case JToolBar toolBar:
                    if (!toolBar.isVisible()) toolBar.setVisible(true);
                    break;
                default:
                    break;
            }
            prev = p;
            p = p.getParent();
        }
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

    /**
     * Registers a pseudo entry that isn't backed by any UI button. Useful for surfacing tabs, panels, or actions that
     * have no immediate clickable action.
     * <p>
     * The entry survives subsequent {@link CmdScrapper#scrape() index rebuilds}  and matches against the supplied
     * {@code keywords}, {@code label}, and {@code path}.
     * </p>
     *
     * @param label    primary entry name (matched against id; also displayed in the palette)
     * @param path     parent-path description shown in the palette, e.g. {@code List.of("PM Tabs")}
     * @param keywords additional searchable terms; any match brings up the entry
     *                 (e.g., synonyms, related concepts, or names of nested features)
     * @param action   what to run when the entry is invoked
     */
    public void registerKeywords(final String label, final List<String> path,
                                 final List<String> keywords, final Runnable action) {
        if (label == null || action == null)
            throw new IllegalArgumentException("label and action cannot be null");
        final CmdAction entry = new CmdAction(label);
        entry.path = (path == null) ? new ArrayList<>() : new ArrayList<>(path);
        entry.setKeywords(keywords);
        entry.action = new AbstractAction(label) {
            private static final long serialVersionUID = 1L;
            @Override
            public void actionPerformed(final ActionEvent e) { action.run(); }
        };
        cmdScrapper.extras.add(entry);
        // If the scrapper has already populated the index, splice the entry
        // in now so it's findable without waiting for the next rebuild
        if (!cmdScrapper.scrapeFailed()) {
            cmdScrapper.cmdMap.put(CmdScrapper.compositeKey(entry.id, entry.path), entry);
            cmdScrapper.combinedCache = null; // cmdMap changed; invalidate merge cache
        }
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
            if (searchField.getText().isBlank())
                table.clearSelection();
            else
                searchField.selectAll();
            restoreOrCenterLocation();
            frame.setVisible(true);
            searchField.requestFocus();
        }
    }

    private void restoreOrCenterLocation() {
        // Restore the last user position when available and the screen is still reachable.
        // This mimics Spotlight/Raycast behavior: the palette stays where the user put it.
        if (lastLocation != null && isOnScreen(lastLocation)) {
            frame.setLocation(lastLocation);
            return;
        }
        // First show (or after a monitor is disconnected): center relative to the focused
        // window. setLocationRelativeTo() accounts for system insets (Dock, taskbar) so
        // the palette is never placed behind them.
        final Window focused = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusedWindow();
        if (focused != null && focused != frame) {
            frame.setLocationRelativeTo(focused);
        } else if (sntui != null) {
            GuiUtils.centerWindow(frame, sntui, sntui.getPathManager());
        } else if (viewer3D != null) {
            GuiUtils.centerWindow(frame, viewer3D.getFrame());
        }
    }

/** Returns true when the given point is within any screen's usable bounds. */
    private static boolean isOnScreen(final Point p) {
        for (final GraphicsDevice gd : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            if (gd.getDefaultConfiguration().getBounds().contains(p))
                return true;
        }
        return false;
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
        final CmdAction cmd = cmdScrapper.getCmdAction(label);
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

    private static class ToolbarButtons {
        static final float SIZE = UIManager.getFont("Button.font").getSize() * .9f;
        static final Color COLOR = SearchField.iconColor();

        static void addSpacer(final JToolBar toolbar) {
            final int spacer = (int)(SIZE/2);
            toolbar.add(Box.createHorizontalStrut(spacer));
            toolbar.addSeparator();
            toolbar.add(Box.createHorizontalStrut(spacer));
        }

        static JToggleButton initButton(final IconFactory.GLYPH glyph, final boolean initialState) {
            final JToggleButton button = new JToggleButton();
            button.setSelected(initialState);
            button.setForeground(SNTColor.contrastHueColor(COLOR, button.getBackground()));
            IconFactory.assignIcon(button, glyph, COLOR, .9f);
            button.setFont(button.getFont().deriveFont(SIZE));
            button.setFocusable(false);
            return button;
        }

        static JLabel initLabel(final String text) {
            final JLabel label = new JLabel(text);
            label.setFont(label.getFont().deriveFont(SIZE));
            label.setForeground(SNTColor.contrastHueColor(COLOR, label.getBackground()));
            label.setFocusable(false);
            return label;
        }
    }


    private static class CmdTableModel extends AbstractTableModel {
        private static final long serialVersionUID = 1L;
        private static final int COLUMNS = 2;
        List<CmdAction> list = new ArrayList<>();

        void setData(final List<CmdAction> list) {
            this.list = list;
            fireTableDataChanged();
        }

        CmdAction getCommand(final int row) {
            if (row < 0 || row >= list.size())
                return null;
            return list.get(row);
        }

        @Override
        public int getColumnCount() {
            return COLUMNS;
        }

        @Override
        public Object getValueAt(final int row, final int column) {
            if (row >= list.size() || column >= COLUMNS)
                return null;
            final CmdAction ca = list.get(row);
            return column == 0 ? ca.id : ca.description();
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

    private class TitleBar {

        JToolBar getToolBar() {
            final JToolBar toolbar = new JToolBar();
            toolbar.setBackground(searchField.getBackground());
            toolbar.setFocusable(false);
            toolbar.setFloatable(false);

            // section 1: window controls
            toolbar.add(autoHideButton());
            toolbar.add(alwaysOnTopButton());
            // spacer
            ToolbarButtons.addSpacer(toolbar);
            // section 2: actions
            toolbar.add(revealButton());
            //toolbar.add(initRecordButton()); // UX pollution: disable recording command
            // spacer
            //ToolbarButtons.addSpacer(toolbar);
            // section 3: startup tip
            toolbar.add(Box.createHorizontalGlue());
            toolbar.add(proHint());

            if (frame.isUndecorated())
                new ComponentMover(frame, toolbar); // make frame draggable through toolbar
            return toolbar;
        }

        JToggleButton revealButton() {
            final JToggleButton button = ToolbarButtons.initButton(IconFactory.GLYPH.ARROWS_TO_EYE, reveal);
            button.setText("Reveal");
            button.setToolTipText("Reveal location of command/action instead of running it");
            button.addItemListener(e -> reveal = button.isSelected());
            return button;
        }

        JToggleButton autoHideButton() {
            final JToggleButton button = ToolbarButtons.initButton(IconFactory.GLYPH.CIRCLE_XMARK, autoHide);
            button.setText("Auto-Hide");
            button.setToolTipText("Dismiss palette after running a command/action");
            button.addItemListener(e -> autoHide = button.isSelected());
            return button;
        }

        JToggleButton alwaysOnTopButton() {
            final JToggleButton button = ToolbarButtons.initButton(IconFactory.GLYPH.MAP_PIN, alwaysOnTop);
            button.setToolTipText("Display palette above all other windows");
            button.setText("Pin");
            button.addItemListener(e -> frame.setAlwaysOnTop(alwaysOnTop = button.isSelected()));
            return button;
        }

        JToggleButton initRecordButton() {
            recButton = ToolbarButtons.initButton(IconFactory.GLYPH.DOTCIRCLE, false);
            recButton.setToolTipText("Record executed actions in Script Recorder");
            recButton.setText("Record");
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

        JLabel proHint() {
            final JLabel label = ToolbarButtons.initLabel("HINT: ↑↓ to navigate, ↵ to run, Esc to close ");
            final Timer timer = new Timer(5000, e -> {
                label.setVisible(false);
                ((Timer)e.getSource()).stop();
            });
            timer.setRepeats(false);
            timer.start();
            return label;
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
            // Return case-adjusted but space-preserved: matches() does its own
            // normalization so that fuzzyMatches() always sees the original tokens
            return (caseSensitive) ? text : text.toLowerCase();
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
                    // run the selected row, or fall back to the first result
                    final int sel = table.getSelectedRow();
                    runCmd(table.getInternalModel().getCommand(Math.max(sel, 0)));
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
            // Undecorated on all platforms: removes the title bar (and its close/min/max
            // buttons) everywhere. addNotify() applies FlatLaf rounded corners + shadow.
            setUndecorated(true);
            guiUtils = new GuiUtils(Palette.this);
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
        private static final Color selectionColor = SNTColor.alphaColor(GuiUtils.getSelectionColor(), 50);


        @Override
        public Component getTableCellRendererComponent(final JTable table, final Object value, final boolean isSelected,
                                                       final boolean hasFocus, final int row, final int column) {
            final Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            setBackground((isSelected)? selectionColor: table.getBackground());
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
            return (int) (col0Font.getSize() * 1.85f);
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
        /** Extra searchable terms (case-insensitive); null when not provided. */
        List<String> keywords;
        /** Raw concatenation of keywords; built once, re-used across normGeneration changes. */
        private String keywordsHaystack;
        /** Cached result of {@link #tooltip()} -- avoids repeated HTML strip on each match call. */
        private String tooltipCache;
        /** Pre-normalized haystacks for fast containment checks in {@link #matches}. */
        private String normId, normPath, normTooltip, normKeywords;
        /** normGeneration value when the norm* fields were last computed. */
        private int normGen = -1;

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

        /** Sets the additional searchable keywords for this command. */
        void setKeywords(final List<String> keywords) {
            this.keywords = keywords;
            this.keywordsHaystack = null; // recompute lazily
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
            if (!hasButton()) return "";
            if (tooltipCache == null) {
                final String t = button.getToolTipText();
                tooltipCache = (t == null) ? "" : t.replaceAll("<[^>]*>", ""); // strip HTML once
            }
            return tooltipCache;
        }

        boolean matches(final String rawQuery) {
            // rawQuery is case-adjusted but space-preserved (getQueryFromSearchField no longer strips)
            // Normalize here for haystack containment checks; pass raw to fuzzyMatches so its
            // token splitter sees word boundaries regardless of the ignoreWhiteSpace setting
            ensureNormalized();
            final String query = normalize(rawQuery);
            return normId.contains(query) || normPath.contains(query)
                    || normTooltip.contains(query) || normKeywords.contains(query)
                    || fuzzyMatches(rawQuery);
        }

        // Fuzzy fallback: each query token must fuzzy-match at least one id token.
        // Tokenizing both sides means threshold is computed per-word (short token = tight
        // threshold; long token = slightly more slack), which should handle multi-word
        // queries with a typo in a single word. Matching is always case-insensitive.
        // Tokens shorter than 2 chars are skipped to avoid false positives on articles
        // and prepositions ("a", "of", etc.).
        private boolean fuzzyMatches(final String query) {
            if (query.length() < 2) return false;
            final String[] queryTokens = query.toLowerCase().split("\\s+");
            final String[] idTokens = id.toLowerCase().split("\\W+");
            for (final String qt : queryTokens) {
                if (qt.length() < 2) continue; // skip very short tokens
                final int threshold = Math.max(1, qt.length() / 3); // 1 error per 3 chars
                boolean matched = false;
                for (final String it : idTokens) {
                    if (it.length() < qt.length() - threshold) continue; // too short to match
                    if (it.contains(qt) || UNIT_LEVENSHTEIN.apply(qt, it) <= threshold) {
                        matched = true;
                        break;
                    }
                }
                if (!matched) return false; // all query tokens must match
            }
            return true;
        }

        private void ensureNormalized() {
            if (normGen == normGeneration) return;
            normId = normalize(id);
            normPath = normalize(pathDescription());
            normTooltip = normalize(tooltip());
            if (keywords == null || keywords.isEmpty()) {
                normKeywords = "";
            } else {
                if (keywordsHaystack == null) {
                    // Build "kw1 kw2 kw3" once; normalize() handles case/whitespace below
                    final StringBuilder sb = new StringBuilder();
                    for (final String kw : keywords) {
                        if (kw == null || kw.isEmpty()) continue;
                        if (!sb.isEmpty()) sb.append(' ');
                        sb.append(kw);
                    }
                    keywordsHaystack = sb.toString();
                }
                normKeywords = normalize(keywordsHaystack);
            }
            normGen = normGeneration;
        }

        // Normalizes a haystack string once per normGeneration change.
        private String normalize(final String s) {
            if (s == null || s.isEmpty()) return "";
            final String r = caseSensitive ? s : s.toLowerCase();
            return ignoreWhiteSpace ? r.replace(" ", "") : r;
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
        /** Cached merge of cmdMap + otherMap; null means stale and must be rebuilt. */
        private TreeMap<String, CmdAction> combinedCache;
        /** Synthetic entries that survive scrape() cycles (see {@link SNTCommandFinder#registerKeywords}). */
        private final List<CmdAction> extras = new ArrayList<>();

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
                components.add(new AnnotatedComponent(sntui.getPathManager().getProofReadingToolBar(), "PM Proofread Bar"));
                components.add(new AnnotatedComponent(sntui.getPathManager().getFilteringToolBar(), "PM Filtering Bar"));
            }
            return components; // recViewer commands are all registered in otherMap
        }

        TreeMap<String, CmdAction> getCmdMap() {
            if (otherMap == null || otherMap.isEmpty())
                return cmdMap;
            if (combinedCache == null) {
                combinedCache = new TreeMap<>(cmdMap);
                combinedCache.putAll(otherMap);
            }
            return combinedCache;
        }

        /**
         * Looks up a command by label. If the label matches a composite key
         * directly, that entry is returned. Otherwise, the first entry whose
         * {@code id} matches is returned (for backward compatibility with
         * {@link #runCommand(String)}).
         */
        CmdAction getCmdAction(final String identifier) {
            final TreeMap<String, CmdAction> map = getCmdMap();
            final CmdAction direct = map.get(identifier);
            if (direct != null)
                return direct;
            // Fallback: match by id (label) for script compatibility
            for (final CmdAction ca : map.values()) {
                if (ca.id.equalsIgnoreCase(identifier))
                    return ca;
            }
            return null;
        }

        boolean scrapeFailed() {
            return cmdMap.isEmpty();
        }

        void scrape() {
            cmdMap.clear();
            combinedCache = null; // force merge rebuild on next getCmdMap() call
            for (final AnnotatedComponent ac : getComponents()) {
                if (ac != null) scrapeComponent(ac);
            }
            // Re-merge synthetic entries that aren't backed by any scraped UI component
            for (final CmdAction extra : extras) {
                cmdMap.put(compositeKey(extra.id, extra.path), extra);
            }
        }

        private void scrapeComponent(final AnnotatedComponent ac) {
            if (ac.component instanceof JMenuBar menuBar) {
                final int topLevelMenus = menuBar.getMenuCount();
                for (int i = 0; i < topLevelMenus; ++i) {
                    final JMenu topLevelMenu = menuBar.getMenu(i);
                    if (topLevelMenu != null && topLevelMenu.getText() != null) {
                        parseMenu(topLevelMenu, List.of(topLevelMenu.getText()));
                    }
                }
            } else if (ac.component instanceof JPopupMenu popup) {
                getMenuItems(popup).forEach(mi -> registerMenuItem(mi, List.of(ac.annotation)));
            } else if (ac.component instanceof JToolBar toolBar) {
                for (final Component c : toolBar.getComponents()) {
                    if (c instanceof AbstractButton b) {
                        try {
                            registerMain(b, List.of(ac.annotation));
                        } catch (final Exception ignored) {
                            // do nothing
                        }
                    } else if (c instanceof Container container) {
                        // special cases of toolbar inside toolbar, e.g., PathManagerUISearchableBar
                        scrapeComponent(new AnnotatedComponent(container, ac.annotation));
                    }
                }
                if (toolBar.getComponentPopupMenu() != null)
                    getMenuItems(toolBar.getComponentPopupMenu()).forEach(mi -> registerMenuItem(mi, List.of(ac.annotation, "Menu")));
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
            if (button.getClientProperty("cmdFinder-ignore") != null) return;
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
                otherMap = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            register(otherMap, button, path);
            combinedCache = null; // new entry added; invalidate merge cache
        }

        /**
         * Builds the composite map key from label and path, so that entries
         * with the same label but different paths coexist naturally.
         */
        private static String compositeKey(final String label, final List<String> path) {
            return label + "\0" + String.join("\0", path);
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
            final String key = compositeKey(label, path);
            final CmdAction existing = map.get(key);
            final boolean isMenuItem = button instanceof JMenuItem;
            final KeyStroke accelerator = (isMenuItem) ? ((JMenuItem) button).getAccelerator() : null;
            if (existing != null) {
                // Same label + same path: merge accelerator if available
                if (accelerator != null)
                    existing.setKeyString(accelerator);
                return;
            }
            final CmdAction ca = new CmdAction(label, button);
            ca.path = path;
            if (accelerator != null)
                ca.setKeyString(accelerator);
            map.put(key, ca);
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
