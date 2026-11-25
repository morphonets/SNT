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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public
 * License along with this program. If not, see
 * <http://www.gnu.org/licenses/gpl-3.0.html>.
 * #L%
 */

package sc.fiji.snt.gui;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * A non-modal dialog that walks the user through a sequence of steps. Each step has a title, a text, optional
 * choices (radio buttons), and actions (buttons) that start the corresponding task in the application, so that the
 * user can interact with it between steps. Steps may be skipped depending on the state of the application, may hold
 * back the Next button until a condition is met, may show a live status line, and may be busy (indeterminate
 * progress bar). Their text may be updated at any time (e.g., once a background analysis completes). All methods
 * must be called on the EDT
 *
 * @author Tiago Ferreira
 */
public class StepWizard extends JDialog {

    private static final long serialVersionUID = 1L;
    private static final int BODY_WIDTH = 380;
    private static final int POLL_MS = 500;
    private final List<Step> steps;
    private final JLabel headerLabel = new JLabel();
    private final JLabel bodyLabel = new JLabel();
    private final JLabel statusLabel = new JLabel();
    private final JProgressBar progress = new JProgressBar();
    private final JPanel choicesPanel = new JPanel(new GridLayout(0, 1, 0, 0));
    private final JPanel actionsPanel = new JPanel();
    private final JPanel footerPanel = new JPanel(new BorderLayout());
    private final JButton backButton = new JButton("Back");
    private final JButton nextButton = new JButton("Next");
    private final Map<String, JButton> actionButtons = new HashMap<>();
	private final Timer timer;
	private final String nextText;
    private final javax.swing.Icon nextIcon;
    private int fixedWidth; // width of the widest step
    private Step current;

    /**
     * @param owner the owner window (may be null)
     * @param title the title of the dialog
     * @param steps the steps, in order (at least one)
     */
    public StepWizard(final Window owner, final String title, final List<Step> steps) {
        super(owner, title, ModalityType.MODELESS);
        if (steps == null || steps.isEmpty()) throw new IllegalArgumentException("At least one step is required");
        this.steps = new ArrayList<>(steps);

        initIconsAndLabels();
        nextText = nextButton.getText();
        nextIcon = nextButton.getIcon();

        this.steps.forEach(s -> s.owner = this);

        progress.setIndeterminate(true);
        progress.setVisible(false);
        actionsPanel.setLayout(new GridLayout(0, 1, 0, 4));
        backButton.addActionListener(e -> back());
        nextButton.addActionListener(e -> {
            if (current.finishTask != null) {
                final Consumer<StepWizard> task = current.finishTask;
                dispose();
                task.accept(this);
            } else {
                next();
            }
        });


        final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(backButton);
        buttons.add(nextButton);
        final JPanel middle = new JPanel(new BorderLayout(0, 8));
        middle.add(choicesPanel, BorderLayout.NORTH);
        middle.add(actionsPanel, BorderLayout.CENTER);
        final JPanel bottom = new JPanel(new BorderLayout(0, 4));
        bottom.add(statusLabel, BorderLayout.CENTER);
        bottom.add(progress, BorderLayout.NORTH);
        bottom.add(footerPanel, BorderLayout.SOUTH);

		final JPanel lower = new JPanel(new BorderLayout(0, 4));
        lower.add(middle, BorderLayout.NORTH);
        lower.add(bottom, BorderLayout.CENTER);

		final JPanel center = new JPanel(new BorderLayout(0, 4));
        center.add(bodyLabel, BorderLayout.NORTH);
        center.add(lower, BorderLayout.CENTER);

		final JPanel content = new JPanel(new BorderLayout());
        content.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        content.add(headerLabel, BorderLayout.NORTH);
        content.add(center, BorderLayout.CENTER);
        content.add(buttons, BorderLayout.SOUTH);
        setContentPane(content);

        // Style, positioning & closing behavior
        getRootPane().putClientProperty("Window.style", "small");
        setLocationRelativeTo(owner);
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        getRootPane().registerKeyboardAction(
                e -> {
                    if (current == null || !current.busy) dispose(); // not while training, selecting a region...
                },
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW
        );

        // Prep for display
        timer = new Timer(POLL_MS, e -> {
            if (current != null && current.poll != null) current.poll.accept(this);
            updateNavigation();
        });
        timer.start();
        // Size for the widest step, so that the dialog keeps its width throughout
        for (final Step s : this.steps) {
            current = s;
            render();
            fixedWidth = Math.max(fixedWidth, getWidth());
        }
        go(Math.max(nextApplicable(-1), 0));
    }

    private void initIconsAndLabels() {
        nextButton.setIcon(IconFactory.buttonIcon('\uf152', true, IconFactory.defaultColor()));
        nextButton.setDisabledIcon(IconFactory.buttonIcon('\uf152', true, IconFactory.disabledColor()));
        backButton.setIcon(IconFactory.buttonIcon('\uf191', true, IconFactory.defaultColor()));
        backButton.setDisabledIcon(IconFactory.buttonIcon('\uf191', true, IconFactory.disabledColor()));
        headerLabel.setFont(headerLabel.getFont().deriveFont(Font.BOLD));
        bodyLabel.setVerticalAlignment(SwingConstants.TOP);
    }

    @Override
    public void dispose() {
        timer.stop();
        super.dispose();
    }

    /**
     * Moves to the next step that applies, or closes the wizard if there is none
     */
    public void next() {
        final int k = nextApplicable(steps.indexOf(current));
        if (k < 0) dispose();
        else go(k);
    }

    /**
     * Moves to the previous step that applies, if any
     */
    public void back() {
        for (int k = steps.indexOf(current) - 1; k >= 0; k--) {
            if (steps.get(k).applicable.getAsBoolean()) {
                go(k);
                return;
            }
        }
    }

    private int nextApplicable(final int from) {
        for (int k = from + 1; k < steps.size(); k++) {
            if (steps.get(k).applicable.getAsBoolean()) return k;
        }
        return -1;
    }

    private void go(final int index) {
        current = steps.get(index);
        render();
        current.onEnter.accept(this);
    }

    private void refresh(final Step step) {
        if (step == current) render();
    }

    private void updateNavigation() {
        if (current == null) return;
        for (final var e : actionButtons.entrySet()) {
            final BooleanSupplier c = current.actionConditions.get(e.getKey());
            e.getValue().setEnabled(!current.busy && (c == null || c.getAsBoolean()));
        }
        final int index = steps.indexOf(current);
        boolean hasPrevious = false;
        for (int k = index - 1; k >= 0 && !hasPrevious; k--) hasPrevious = steps.get(k).applicable.getAsBoolean();
        backButton.setEnabled(hasPrevious && !current.busy);
        nextButton.setEnabled(!current.busy && current.canAdvance.getAsBoolean());
        if (current.finishTask != null) {
            nextButton.setText(current.finishLabel);
            nextButton.setIcon((current.finishIcon == null) ? null
                    : IconFactory.buttonIcon(current.finishIcon, null, 1f));
            nextButton.setVisible(true);
        } else {
            nextButton.setText(nextText);
            nextButton.setIcon(nextIcon);
            nextButton.setVisible(nextApplicable(index) >= 0);
        }
    }

    private void render() {
        // Steps that do not apply are not counted, unless it is the one being shown
        final List<Step> shown = new ArrayList<>();
        for (final Step s : steps) {
            if (s == current || s.applicable.getAsBoolean()) shown.add(s);
        }
        headerLabel.setText(String.format("Step %d of %d: %s", shown.indexOf(current) + 1, shown.size(),
                current.title));
        bodyLabel.setText("<html><div style='width:" + BODY_WIDTH + "px'>" + current.body + "</div></html>");
        statusLabel.setText(current.status.isEmpty() ? " " : "<html><div style='width:" + BODY_WIDTH + "px'>"
                + current.status + "</div></html>");
        progress.setVisible(current.busy);
        footerPanel.removeAll();
        if (current.footer != null) {
            footerPanel.add(GuiUtils.Panels.section(current.footerTitle, new JLabel("<html><div style='width:"
                    + (BODY_WIDTH - 20) + "px'>" + current.footer + "</div></html>")), BorderLayout.CENTER);
        }

        choicesPanel.removeAll();
        final ButtonGroup group = new ButtonGroup();
        for (int i = 0; i < current.choices.size(); i++) {
            final int index = i;
            final Step step = current;
            final JRadioButton rb = new JRadioButton("<html>" + step.choices.get(i) + "</html>", i == step.choice);
            rb.setEnabled(!step.busy);
            rb.addActionListener(e -> {
                step.choice = index;
                step.onChoice.accept(index);
                updateNavigation();
            });
            group.add(rb);
            choicesPanel.add(rb);
        }
        choicesPanel.setVisible(current.choicesVisible && !current.choices.isEmpty());

        actionsPanel.removeAll();
        actionButtons.clear();
        for (final Action a : current.actions) {
            final JButton b = new JButton(a.label());
            actionButtons.put(a.label(), b);
            GuiUtils.Buttons.makeRoundRect(b);
            if (a.icon != null) {
                b.setIcon(IconFactory.buttonIcon(a.icon, null, 1f));
            }
            b.setToolTipText(a.tooltip());
            b.setEnabled(!current.busy);
            b.addActionListener(e -> a.run().accept(this));
            actionsPanel.add(b);
        }
        updateNavigation();
        pack();
        // Constant width: avoids the dialog resizing between steps
        if (fixedWidth > 0) setSize(fixedWidth, getHeight());
    }

    /**
     * A button of a step
     *
     * @param label   the text of the button
     * @param tooltip the tooltip (may be null)
     * @param icon    the action glyph icon (may be null)
     * @param run     the task, which receives the wizard (e.g., to move to the next step when done)
     */
    public record Action(String label, String tooltip, IconFactory.GLYPH icon, Consumer<StepWizard> run) {
    }

    /**
     * A step of the wizard
     */
    public static final class Step {
        private final String title;
        private final List<Action> actions = new ArrayList<>();
        private final List<String> choices = new ArrayList<>();
        private final Map<String, BooleanSupplier> actionConditions = new HashMap<>();
        private String body = "";
        private String status = "";
        private boolean busy;
        private String footerTitle;
        private String footer;
        private int choice = 0;
        private boolean choicesVisible = true;
        private Consumer<Integer> onChoice = i -> {
        };
		private BooleanSupplier applicable = () -> true;
        private BooleanSupplier canAdvance = () -> true;
        private String finishLabel;
        private IconFactory.GLYPH finishIcon;
        private Consumer<StepWizard> finishTask;
        private Consumer<StepWizard> onEnter = w -> {
        };
        private Consumer<StepWizard> poll;
        private StepWizard owner;

        /**
         * @param title the title of the step
         */
        public Step(final String title) {
            this.title = title;
        }

        /**
         * Sets the text of the step. Displayed immediately if the step is the current one
         *
         * @param html the text (HTML, without enclosing tags)
         * @return this step
         */
        public Step body(final String html) {
            body = (html == null) ? "" : html;
            if (owner != null) owner.refresh(this);
            return this;
        }

        /**
         * Sets the status line (shown below the actions)
         *
         * @param html the text (HTML, without enclosing tags). Null or empty to clear
         * @return this step
         */
        public Step status(final String html) {
            final String s = (html == null) ? "" : html;
            if (s.equals(status)) return this; // polled: avoid needless redraws
            status = s;
            if (owner != null) owner.refresh(this);
            return this;
        }

        /**
         * Sets a boxed note, shown at the bottom of the step
         *
         * @param title the title of the box
         * @param html  the text (HTML, without enclosing tags)
         * @return this step
         */
        public Step footer(final String title, final String html) {
            footerTitle = title;
            footer = html;
            if (owner != null) owner.refresh(this);
            return this;
        }

        /**
         * Sets whether the step is busy: an indeterminate progress bar is shown, and Back/Next are disabled
         *
         * @param busy the busy state
         * @return this step
         */
        public Step busy(final boolean busy) {
            this.busy = busy;
            if (owner != null) owner.refresh(this);
            return this;
        }

        /**
         * @param label   the text of the button
         * @param tooltip the tooltip (may be null)
         * @param icon    the optional action icon (may be null)
         * @param run     the task. Receives the wizard
         * @return this step
         */
        public Step action(final String label, final String tooltip, final IconFactory.GLYPH icon, final Consumer<StepWizard> run) {
            actions.add(new Action(label, tooltip, icon, run));
            return this;
        }

        /**
         * @param label   the text of the button
         * @param tooltip the tooltip (may be null)
         * @param run     the task. Receives the wizard
         * @return this step
         */
        public Step action(final String label, final String tooltip, final Consumer<StepWizard> run) {
            return action(label, tooltip, null, run);
        }

        /**
         * Defines mutually exclusive choices (radio buttons)
         *
         * @param onChange run when the user picks a choice. Receives its index
         * @param labels   the labels (HTML allowed, without enclosing tags). The first is selected by default
         * @return this step
         */
        public Step choices(final Consumer<Integer> onChange, final String... labels) {
            choices.clear();
            choices.addAll(List.of(labels));
            onChoice = onChange;
            choice = 0;
            return this;
        }

        /**
         * Selects a choice programmatically (does not call the listener)
         *
         * @param index the index of the choice
         * @return this step
         */
        public Step select(final int index) {
            choice = index;
            if (owner != null) owner.refresh(this);
            return this;
        }

        /**
         * @param visible whether the choices are shown (true by default)
         * @return this step
         */
        public Step choicesVisible(final boolean visible) {
            choicesVisible = visible;
            if (owner != null) owner.refresh(this);
            return this;
        }

        /**
         * @return the index of the selected choice
         */
        public int selected() {
            return choice;
        }

        /**
         * @param condition evaluated whenever the wizard moves: the step is skipped if false
         * @return this step
         */
        public Step applicableIf(final BooleanSupplier condition) {
            applicable = condition;
            return this;
        }

        /**
         * Replaces the Next/Finish button of this step by a button that runs a task and closes the wizard
         *
         * @param label the text of the button
         * @param icon  the icon (may be null)
         * @param task  the task. Receives the wizard
         * @return this step
         */
        public Step finish(final String label, final IconFactory.GLYPH icon, final Consumer<StepWizard> task) {
            finishLabel = label;
            finishIcon = icon;
            finishTask = task;
            return this;
        }

        /**
         * @param label     the label of an action of this step
         * @param condition evaluated whenever the step is updated: the action is disabled while false
         * @return this step
         */
        public Step enableActionIf(final String label, final BooleanSupplier condition) {
            actionConditions.put(label, condition);
            return this;
        }

        /**
         * @param condition evaluated periodically: Next is disabled while false
         * @return this step
         */
        public Step advanceIf(final BooleanSupplier condition) {
            canAdvance = condition;
            return this;
        }

        /**
         * @param task run every time the step becomes the current one
         * @return this step
         */
        public Step onEnter(final Consumer<StepWizard> task) {
            onEnter = task;
            return this;
        }

        /**
         * @param task run periodically while the step is the current one, e.g., to update its status
         * @return this step
         */
        public Step poll(final Consumer<StepWizard> task) {
            poll = task;
            return this;
        }
    }

}
