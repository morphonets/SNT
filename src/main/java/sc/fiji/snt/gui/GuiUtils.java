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

import com.formdev.flatlaf.*;
import com.formdev.flatlaf.icons.FlatClearIcon;
import com.jidesoft.plaf.LookAndFeelFactory;
import com.jidesoft.popup.JidePopup;
import com.jidesoft.swing.ListSearchable;
import com.jidesoft.utils.ProductNames;
import org.apache.commons.lang3.StringUtils;
import org.scijava.command.CommandService;
import org.scijava.ui.DialogPrompt.Result;
import org.scijava.ui.awt.AWTWindows;
import org.scijava.ui.swing.SwingDialog;
import org.scijava.util.ColorRGB;
import org.scijava.util.PlatformUtils;
import org.scijava.util.Types;
import sc.fiji.snt.SNTPrefs;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.analysis.SNTChart;
import sc.fiji.snt.gui.IconFactory.GLYPH;
import sc.fiji.snt.util.SNTColor;
import sc.fiji.snt.util.SNTPoint;

import javax.swing.*;
import javax.swing.Timer;
import javax.swing.JSpinner.DefaultEditor;
import javax.swing.border.EmptyBorder;
import javax.swing.colorchooser.AbstractColorChooserPanel;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.HyperlinkEvent;
import javax.swing.event.HyperlinkListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.text.NumberFormatter;
import javax.swing.text.Position;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.StyleSheet;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreeNode;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.AffineTransform;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.text.ParseException;
import java.util.List;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Misc. utilities for SNT's GUI. */
public class GuiUtils {

	public static final String LAF_LIGHT = FlatLightLaf.NAME;
	public static final String LAF_LIGHT_INTJ = FlatIntelliJLaf.NAME;
	public static final String LAF_DARK = FlatDarkLaf.NAME;
	public static final String LAF_DARCULA = FlatDarculaLaf.NAME;
	public static final String LAF_DEFAULT  = LAF_LIGHT;

	private static SplashScreen splashScreen;
	private static LookAndFeel existingLaf;
	private Component parent;
	private JidePopup popup;
	private boolean popupExceptionTriggered;
	private static JColorChooser colorChooser;
	private static Color disabledColor;

	public GuiUtils(final Component parent) {
		setParent(parent);
	}

	/**
	 * Create a new GuiUtils instance using active (focused) window as parent.
	 */
	public GuiUtils() {
		setParentToActiveWindow();
	}

	public void setParentToActiveWindow() {
		setParent(KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow());
	}

	public void setParent(final Component parent) {
		if (parent == null) {
			this.parent = null;
		} else {
			this.parent = (parent instanceof Container) ? parent : parent.getParent();
		}
	}

	public void error(final String msg) {
		error(msg, "SNT v" + SNTUtils.VERSION);
	}

	public void error(final String msg, final String title) {
		centeredDialog(msg, title, JOptionPane.ERROR_MESSAGE);
	}

	public void notifyIfNewVersion(final int delay) {
		final Timer timer = new Timer(delay, e -> {
			if (SNTPrefs.firstRunAfterUpdate()) {
				final String s = """
						<HTML>
						&nbsp;<b>SNT was updated: Click this notification to find out what is new!</b>
						<br>&nbsp;Tip: You may want to run <i>File › Reset and Restart...</i> to clear outdated settings.
						""";
				showNotification(leftAlignedLabel(s, MenuItems.releaseNotesURL(), true), true);
			}
		});
		timer.setRepeats(false);
		timer.start();
	}

	public void showNotification(final String msg, final Number msDelay) {
		StringBuilder parsedMsg;
		if (!msg.startsWith("<HTML>")) {
			final String[] lines = msg.split("\n");
			parsedMsg = new StringBuilder("<HTML><b>" + lines[0] + "</b>");
			for (int i = 1; i < lines.length; i++) {
				parsedMsg.append("<br>").append(lines[i]);
			}
		}
		else
			parsedMsg = new StringBuilder(msg);
		showNotification(new JLabel(parsedMsg.toString()), msDelay.intValue());
	}

	public void showNotification(final JLabel msg, final int msDelay) {
		final Timer timer = new Timer(msDelay, e -> showNotification(msg, false));
		timer.setRepeats(false);
		timer.start();
	}

	private void showNotification(final JLabel msg, final boolean disposeOnClick) {

		final JidePopup popup = new JidePopup();
		popup.setOwner(parent);
		popup.setAttachable(false);
		popup.setMovable(true);
		popup.setReturnFocusToOwner(true);
		popup.setTransient(false);
		popup.setResizable(false);
		popup.setDefaultMoveOperation(JidePopup.DO_NOTHING_ON_MOVED);
		popup.setTimeout(180000); // 3 minutes
		popup.setFocusable(false);
		popup.setEnsureInOneScreen(true);

		final JButton button = new JButton(new FlatClearIcon());//new FlatTabbedPaneCloseIcon());
		button.setMargin(new Insets(0, 0, 0, 0));
		button.setBorder(null);
		button.setBorderPainted(false);
		button.setContentAreaFilled(false);
		button.addActionListener(ae -> popup.hidePopup());
		popup.addExcludedComponent(button);

		final JPanel panel = new JPanel();
		applyRoundCorners(msg);
		panel.add(button);
		panel.add(msg);
		popup.add(panel);

		if (disposeOnClick) {
			msg.addMouseListener(new MouseAdapter() {
				@Override
				public void mouseClicked(MouseEvent e) {
					popup.hidePopupImmediately();
				}
			});
		}
		popup.DISTANCE_TO_SCREEN_BORDER = 100; // workaround dock on the left side on macOS
		SwingUtilities.invokeLater(() -> {
			if (parent == null)
				popup.showPopup(SwingConstants.NORTH_EAST);
			else
				popup.showPopup(SwingConstants.SOUTH_WEST, parent);
		});
	}

	public static void applyRoundCorners(final JComponent component) {
		component.putClientProperty(FlatClientProperties.STYLE, //
				"[light]background: tint(@background,50%);" //
						+ "[dark]background: shade(@background,15%);" //
						+ "[light]border: 16,16,16,16,shade(@background,10%),,8;" //
						+ "[dark]border: 16,16,16,16,tint(@background,10%),,8");
	}

	public JDialog floatingMsg(final String msg, final boolean autodismiss) {
		final JDialog dialog = new FloatingDialog(msg);
		if (autodismiss) GuiUtils.setAutoDismiss(dialog);
		makeVisible(dialog, false);
		return dialog;
	}

	public void tempMsg(final String msg) {
		tempMsg(msg, -1);
	}

	public void tempMsg(final String msg, final int location) {
		SwingUtilities.invokeLater(() -> {
			try {
				if (popup != null && popup.isVisible())
					popup.hidePopupImmediately();
				popup = getPopup(msg);
				popup.showPopup((location < 0) ? SwingConstants.SOUTH_WEST : location, parent);
			} catch (final Error ignored) {
				if (!popupExceptionTriggered) {
					errorPrompt("<HTML><body><div style='width:500;'>Notification mechanism "
							+ "failed when notifying of:<br>\"<i>"+ msg +"</i>\".<br>"
							+ "All future notifications will be displayed in Console.");
					popupExceptionTriggered = true;
				}
				if (msg.startsWith("<")) { //HTML formatted
					// https://stackoverflow.com/a/3608319
					String cleanedMsg = msg.replaceAll("(?s)<[^>]*>(\\s*<[^>]*>)*", " ");
					cleanedMsg = cleanedMsg.replace("&amp;", "&");
					System.out.println("[INFO] [SNT] " + cleanedMsg.trim());
				} else {
					System.out.println("[INFO] [SNT] " + msg);
				}
			}
		});
	}

	public static void showHTMLDialog(final String msg, final String title) {
		final HTMLDialog dialog = new GuiUtils(null).new HTMLDialog(msg, title, false);
		SwingUtilities.invokeLater(() -> dialog.setVisible(true));
	}

	public static boolean isLegacy3DViewerAvailable() {
		return Types.load("ij3d.Image3DUniverse") != null;
	}

	private JidePopup getPopup(final String msg) {
		final JLabel label = getLabel(msg);
		final int mrgn = (int) (uiFontSize()/4);
		label.setBorder(BorderFactory.createEmptyBorder(mrgn, mrgn, mrgn, mrgn));
		//label.setHorizontalAlignment(SwingConstants.CENTER);
		final JidePopup popup = new JidePopup();
		popup.add(label);
		if (parent != null) {
			popup.setOwner(parent);
			popup.setMaximumSize(parent.getSize());
		}
		popup.setFocusable(false);
		popup.setReturnFocusToOwner(true);
		popup.setTransient(true);
		popup.setMovable(false);
		popup.setDefaultMoveOperation(JidePopup.HIDE_ON_MOVED);
		popup.setEnsureInOneScreen(true);
		popup.setTimeout(2500);
		return popup;
	}

	public int yesNoDialog(final String msg, final String title, final String yesButtonLabel, final String noButtonLabel) {
		return yesNoDialog(new Object[] { getLabel(msg) }, title, new String[] {yesButtonLabel, noButtonLabel});
	}

	public int yesNoDialog(final String msg, final String title) {
		return yesNoDialog(new Object[] { getLabel(msg) }, title, null);
	}

	public Result yesNoPrompt(final String message, final String title) {
		final int result = yesNoDialog(message, (title == null) ? SNTUtils.getReadableVersion() : title);
        return switch (result) {
            case JOptionPane.YES_OPTION -> Result.YES_OPTION;
            case JOptionPane.NO_OPTION -> Result.NO_OPTION;
            case JOptionPane.CANCEL_OPTION -> Result.CANCEL_OPTION;
            default -> Result.CLOSED_OPTION;
        };
	}

	private int yesNoDialog(final Object[] components, final String title, final String[] buttonLabels) {
		final JOptionPane optionPane = new JOptionPane(components, JOptionPane.QUESTION_MESSAGE,
				JOptionPane.YES_NO_OPTION, null, buttonLabels);
		final JDialog d = optionPane.createDialog(parent, title);
		makeVisible(d, true);
		d.dispose();
		final Object result = optionPane.getValue();
		if (result instanceof Integer) {
			return (Integer) result;
		} else if (buttonLabels != null && result instanceof String) {
			return result.equals(buttonLabels[0]) ? JOptionPane.YES_OPTION : JOptionPane.NO_OPTION;
		} else {
			return SwingDialog.UNKNOWN_OPTION;
		}
	}

	private void makeVisible(final JDialog dialog, final boolean forceBringToFront) {
		// work around a bug in openjdk and macOS in which prompts
		// are not frontmost if the component hierarchy is > 3
		dialog.setAlwaysOnTop(forceBringToFront && PlatformUtils.isMac());
		dialog.setVisible(true);
		dialog.toFront();
	}

	public boolean getConfirmation(final String msg, final String title) {
		return (yesNoDialog(msg, title) == JOptionPane.YES_OPTION);
	}

	public void error(final String msg, final String title, final String helpURI) {
		final JOptionPane optionPane = new JOptionPane(getLabel(msg), JOptionPane.ERROR_MESSAGE,
				JOptionPane.YES_NO_OPTION, null, new String[] { "Online Help", "OK" });
		final JDialog d = optionPane.createDialog(parent, title);
		makeVisible(d, true);
		d.dispose();
		if ("Online Help".equals(optionPane.getValue()))
			openURL(helpURI);
	}

	public boolean getConfirmation(final String msg, final String title, final String yesLabel, final String noLabel) {
		final Boolean result = getConfirmation2(msg, title, yesLabel, noLabel);
		return result != null && result;
	}

	public Boolean getConfirmation2(final String msg, final String title, final String yesLabel, final String noLabel) {
		final int result = yesNoDialog(msg, title, yesLabel, noLabel);
		if (result == SwingDialog.UNKNOWN_OPTION)
			return null;
		return result == JOptionPane.YES_OPTION;
	}

	public String getChoice(final String message, final String title, final String[] choices,
			final String defaultChoice) {
		return (String) JOptionPane.showInputDialog(parent, //
				getWrappedText(new JLabel(), message), title, JOptionPane.QUESTION_MESSAGE, null, choices,
				(defaultChoice == null) ? choices[0] : defaultChoice);
	}

	public String[] getTwoChoices(final String title,
								  final String choice1Label, final String[] choices1, final String defaultChoice1,
								  final String choice2Label, final String[] choices2,  final String defaultChoice2) {
		final JComboBox<String> combo1 = new JComboBox<>(choices1);
		final JComboBox<String> combo2 = new JComboBox<>(choices2);
		combo1.setSelectedItem(defaultChoice1);
		combo2.setSelectedItem(defaultChoice2);
		final JComponent[] inputs = new JComponent[] { new JLabel(choice1Label), combo1, new JLabel(choice2Label), combo2 };
		final int result = JOptionPane.showConfirmDialog(null, inputs, title, JOptionPane.OK_CANCEL_OPTION,
				JOptionPane.QUESTION_MESSAGE);
		if (result == JOptionPane.OK_OPTION) {
			return new String[] { (String) combo1.getSelectedItem(),  (String) combo2.getSelectedItem() };
		}
		return null;
	}

	public String[] getChoiceWithInput(final String message, final String title, final String[] choices,
			final String defaultChoice, final String defaultInput) {
		final JComboBox<String> combo = new JComboBox<>(choices);
		combo.setSelectedItem(defaultChoice);
		final JTextField input = new JTextField(defaultInput);
		addClearButton(input);
		final JComponent[] inputs = new JComponent[] { new JLabel(message), combo, input };
		final int result = JOptionPane.showConfirmDialog(null, inputs, title, JOptionPane.OK_CANCEL_OPTION,
				JOptionPane.QUESTION_MESSAGE);
		if (result == JOptionPane.OK_OPTION) {
			return new String[] { (String) combo.getSelectedItem(), input.getText() };
		}
		return null;
	}

	public Object[] getChoiceAndDouble(final String message, final String title, final String[] choices,
			final String defaultChoice, final Double defaultValue) {
		final String[] result = getChoiceWithInput(message, title, choices, defaultChoice,
				String.valueOf(defaultValue));
		if (result != null) {
			try {
				final NumberFormat nf = NumberFormat.getInstance(Locale.US);
				final Number number = nf.parse(result[1]);
				return new Object[] { result[0], number.doubleValue() };
			} catch (final ParseException ignored) {
				return new Object[] { result[0], Double.NaN };
			}
		}
		return null;
	}

	public String getChoice(final String message, final String title, final String[] choices,
			final String[] descriptions, final String defaultChoice) {
		final JTextArea ta = new JTextArea();
		ta.setRows(6);
		ta.setWrapStyleWord(true);
		ta.setLineWrap(true);
		ta.setEditable(false);
		ta.setFocusable(true); // allow text to be copied
		int defIdx = Arrays.asList(choices).indexOf(defaultChoice);
		if (defIdx < 0)
			defIdx = 0;

		final DefaultListModel<String> listModel = new DefaultListModel<>();
		listModel.addAll(List.of(choices));
		final JList<String> list = new JList<>(listModel) {
				@Override // see ListSearchable.java
				public int getNextMatch(final String prefix, final int startIndex, final Position.Bias bias) {
					return -1;
			}
		};
		list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		if (choices.length < 15)
			list.setVisibleRowCount(choices.length);
		list.addListSelectionListener(e -> {
			ta.setText(descriptions[list.getSelectedIndex()]);
			ta.setCaretPosition(0);
		});
		list.setSelectedIndex(defIdx);
		list.ensureIndexIsVisible(defIdx);
		ta.setText(descriptions[defIdx]);
		ta.setCaretPosition(0);
		ta.setBackground(list.getBackground());

		final GridBagLayout layout = new GridBagLayout();
		final GridBagConstraints gbc = defaultGbc();
		final JPanel panel = new JPanel(layout);
		panel.add(getLabel(message),gbc);
		gbc.gridy++;
		panel.add(getScrollPane(list), gbc);
		gbc.gridy++;
		panel.add(new JLabel("<HTML>&nbsp;"), gbc); // spacer
		gbc.gridy++;
		panel.add(getScrollPane(ta), gbc);
		if (choices.length >  4) {
			final ListSearchable searchable = new ListSearchable(list) {
				@Override
				protected String convertElementToString(Object object) {
					final int idx = listModel.indexOf(object);
					return (choices[idx] + " " + descriptions[idx]).replaceAll("\\r?\\n", " ");
				}
			};
			final SNTSearchableBar searchableBar = new SNTSearchableBar(searchable);
			searchableBar.setVisibleButtons(SNTSearchableBar.SHOW_NAVIGATION | SNTSearchableBar.SHOW_STATUS);
			searchableBar.setHighlightAll(false); // only one item can be selected in the choice list
			searchableBar.setStatusLabelPlaceholder(String.format("%d choice(s) available...", listModel.size())); // cannot be empty
			searchableBar.setGuiUtils(this);
			gbc.gridy++;
			panel.add(new JLabel("<HTML>&nbsp;"), gbc); // spacer
			gbc.gridy++;
			panel.add(searchableBar, gbc);
		}

		final int promptResult = JOptionPane.showOptionDialog(parent, panel, title, JOptionPane.OK_CANCEL_OPTION,
				(choices.length < 6) ? JOptionPane.QUESTION_MESSAGE : JOptionPane.PLAIN_MESSAGE, null,
				new String[] { "OK", "Cancel" }, list);
		return (promptResult == JOptionPane.OK_OPTION) ? list.getSelectedValue() : null;
	}

	private JList<String> getJList(final String[] choices, final String defaultChoice) {
		final JList<String> list = new JList<>(choices);
		list.setSelectedValue(defaultChoice, true);
		list.addKeyListener(new KeyAdapter() {
			private static final int DELAY = 300; // 300ms delay between searches
			private String typedString;
			private long lastKeyTyped;

			@Override
			public void keyTyped(final KeyEvent e) {
				final char ch = e.getKeyChar();
				if (!Character.isLetterOrDigit(ch)) {
					return;
				}
				if (lastKeyTyped + DELAY < System.currentTimeMillis()) {
					typedString = "";
				}
				lastKeyTyped = System.currentTimeMillis();
				typedString += Character.toLowerCase(ch);
				for (int i = 0; i < list.getModel().getSize(); i++) {
					final String str = list.getModel().getElementAt(i).toLowerCase();
					if (str.startsWith(typedString)) {
						list.setSelectedIndex(i);
						list.ensureIndexIsVisible(i);
						break;
					}
				}
			}
		});
		return list;
	}

	private JScrollPane getScrollPane(final Component c) {
		final JScrollPane sp = new JScrollPane(c);
		sp.setWheelScrollingEnabled(true);
		return sp;
	}

	public List<String> getMultipleChoices(final String title, final String[] choices, final String defaultChoice) {
		final JList<String> list = getJList(choices, defaultChoice);
		if (JOptionPane.showConfirmDialog(parent, getScrollPane(list), title,
				JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE) == JOptionPane.OK_OPTION)
			return list.getSelectedValuesList();
		return null;
	}

	public Object[] getMultipleChoicesAndChoice(final String title,
			final String[] choices1, final String defaultChoice1, final boolean multipleSelectionAllowed1,
			final String[] choices2, final String defaultChoice2) {
		final JList<String> list1 = getJList(choices1, defaultChoice1);
		list1.setSelectionMode((multipleSelectionAllowed1) ? ListSelectionModel.MULTIPLE_INTERVAL_SELECTION : ListSelectionModel.SINGLE_SELECTION);
		final JComboBox<String> combo = new JComboBox<>(choices2);
		combo.setSelectedItem(defaultChoice2);
		final JPanel panel = new JPanel();
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
		panel.add(getScrollPane(list1));
		panel.add(combo);
		if (JOptionPane.showConfirmDialog(parent, panel, title,
				JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE) == JOptionPane.OK_OPTION) {
			return new Object[] { list1.getSelectedValuesList(), combo.getSelectedItem() };
		}
		return null;
	}

	public boolean[] getPersistentConfirmation(final String msg, final String title) {
		return getConfirmationAndOption(msg, title, "Remember my choice and do not prompt me again", false);
	}

	public boolean[] getConfirmationAndOption(final String msg, final String title, final String checkboxLabel,
			final boolean checkboxDefault, final String[] yesNoButtonLabels) {
		final JCheckBox checkbox = new JCheckBox();
		checkbox.setText(getWrappedText(checkbox, checkboxLabel));
		checkbox.setSelected(checkboxDefault);
		final Object[] params = { getLabel(msg), checkbox };
		final int dialog = yesNoDialog(params, title, yesNoButtonLabels);
		final boolean result = dialog == JOptionPane.YES_OPTION;
		return new boolean[] { result, checkbox.isSelected() };
	}

	public boolean[] getConfirmationAndOption(final String msg, final String title, final String checkboxLabel,
			final boolean checkboxDefault) {
		return getConfirmationAndOption(msg, title, checkboxLabel, checkboxDefault, null);

	}

    /**
     * Displays a dialog with radio button choices, optional info text, and an optional checkbox.
     * Useful for operations that require a selection with additional context and options.
     *
     * @param title          the dialog title
     * @param message        the message/prompt shown at the top (can be HTML)
     * @param choices        array of choices to display as radio buttons
     * @param defaultChoice  the initially selected choice (must be in choices array)
     * @param infoText       optional informational text shown below the choices (null to hide)
     * @param checkboxLabel  optional checkbox label (null to hide checkbox)
     * @param checkboxDefault default state of checkbox (ignored if checkboxLabel is null)
     * @return Object array: {selectedChoice (String), checkboxSelected (Boolean)},
     *         or null if cancelled. checkboxSelected is false if checkbox was hidden.
     */
    public Object[] getChoiceWithOptionAndInfo(final String title, final String message,
                                               final String[] choices, final String defaultChoice,
                                               final String infoText, final String checkboxLabel, final boolean checkboxDefault) {

        // Build the panel
        final JPanel panel = new JPanel(new GridBagLayout());
        final GridBagConstraints gbc = new GridBagConstraints();
        gbc.anchor = GridBagConstraints.LINE_START;
        gbc.gridwidth = GridBagConstraints.REMAINDER;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(2, 0, 2, 0);
        gbc.weightx = 1.0;
        gbc.gridx = 0;
        gbc.gridy = 0;

        // Message label
        if (message != null && !message.isEmpty()) {
            panel.add(getLabel(message), gbc);
            gbc.gridy++;
            gbc.insets = new Insets(8, 0, 2, 0); // Add some space after message
        }

        // Radio buttons for choices
        final ButtonGroup buttonGroup = new ButtonGroup();
        final JRadioButton[] radioButtons = new JRadioButton[choices.length];

        int defaultIndex = 0;
        for (int i = 0; i < choices.length; i++) {
            if (choices[i].equals(defaultChoice)) {
                defaultIndex = i;
            }
            radioButtons[i] = new JRadioButton(choices[i]);
            buttonGroup.add(radioButtons[i]);
            gbc.insets = new Insets(2, 10, 2, 0); // Indent radio buttons slightly
            panel.add(radioButtons[i], gbc);
            gbc.gridy++;
        }
        radioButtons[defaultIndex].setSelected(true);

        // Info text (optional)
        if (infoText != null && !infoText.isEmpty()) {
            gbc.insets = new Insets(10, 0, 2, 0); // Add space before info
            final JLabel infoLabel = getLabel(infoText);
            infoLabel.setEnabled(false); // Dimmed appearance for secondary info
            panel.add(infoLabel, gbc);
            gbc.gridy++;
        }

        // Checkbox (optional)
        final JCheckBox checkbox;
        if (checkboxLabel != null && !checkboxLabel.isEmpty()) {
            checkbox = new JCheckBox();
            checkbox.setText(getWrappedText(checkbox, checkboxLabel));
            checkbox.setSelected(checkboxDefault);
            gbc.insets = new Insets(10, 0, 2, 0); // Add space before checkbox
            panel.add(checkbox, gbc);
            gbc.gridy++;
        } else {
            checkbox = null;
        }

        // Show dialog
        final int result = JOptionPane.showConfirmDialog(parent, panel, title,
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);

        if (result == JOptionPane.OK_OPTION) {
            // Find selected choice
            String selectedChoice = choices[0];
            for (int i = 0; i < radioButtons.length; i++) {
                if (radioButtons[i].isSelected()) {
                    selectedChoice = choices[i];
                    break;
                }
            }
            final boolean checkboxSelected = checkbox != null && checkbox.isSelected();
            return new Object[] { selectedChoice, checkboxSelected };
        }
        return null;
    }

    /* returns true if user does not want to be warned again */
	public Boolean getPersistentWarning(final String msg, final String title) {
		return getPersistentDialog(msg, title, JOptionPane.WARNING_MESSAGE);
	}

	private Boolean getPersistentDialog(final String msg, final String title, final int type) {
		final JPanel msgPanel = new JPanel();
		msgPanel.setLayout(new BorderLayout());
		msgPanel.add(getLabel(msg), BorderLayout.CENTER);
		final JCheckBox checkbox = new JCheckBox();
		checkbox.setText(getWrappedText(checkbox, "Do not remind me again"));
		msgPanel.add(checkbox, BorderLayout.SOUTH);
		if (JOptionPane.showConfirmDialog(parent, msgPanel, title, JOptionPane.DEFAULT_OPTION,
				type) != JOptionPane.OK_OPTION)
			return null;
		else
			return checkbox.isSelected();
	}

	public String getString(final String promptMsg, final String promptTitle,
		final String defaultValue)
	{
		return (String) getObj(promptMsg, promptTitle, defaultValue);
	}

	public String[] getStrings(final String promptTitle, final String[] labels, final String[] defaultValues) {
		final JTextField[] fields = new JTextField[labels.length];
		final JPanel panel = new JPanel(new GridBagLayout());
		final GridBagConstraints c = new GridBagConstraints();
		for (int i = 0; i < labels.length; i++) {
			c.gridy = i;
			c.gridx = 0;
			c.fill = 0;
			c.anchor = GridBagConstraints.EAST;
			panel.add(new JLabel(labels[i]), c);
			c.gridx = 1;
			c.fill = 1;
			c.anchor = GridBagConstraints.WEST;
			fields[i] = new JTextField(20);
			addClearButton(fields[i]);
			fields[i].setText(defaultValues[i]);
			panel.add(fields[i], c);
		}
		final int result = JOptionPane.showConfirmDialog(parent, panel, promptTitle, JOptionPane.OK_CANCEL_OPTION,
				JOptionPane.PLAIN_MESSAGE);
		if (result == JOptionPane.OK_OPTION) {
			final String[] values = new String[labels.length];
			for (int i = 0; i < labels.length; i++)
				values[i] = fields[i].getText();
			return values;
		}
		return null;
	}

	public String[] getStrings(final String promptTitle, final Map<String, List<String>> choicesMap,
			final String... defaultChoices) {
		final List<JComboBox<String>> fields = new ArrayList<>(choicesMap.size());
		final JPanel panel = new JPanel(new GridBagLayout());
		final GridBagConstraints c = new GridBagConstraints();
		final int[] index = { 0 };
		choicesMap.forEach((k, v) -> {
			c.gridy = index[0];
			c.gridx = 0;
			c.fill = 0;
			c.anchor = GridBagConstraints.EAST;
			panel.add(new JLabel(k), c);
			c.gridx = 1;
			c.fill = 1;
			c.anchor = GridBagConstraints.WEST;
			final JComboBox<String> field = new JComboBox<>(v.toArray(new String[0]));
			fields.add(field);
			if (index[0] < defaultChoices.length)
				field.setSelectedItem(defaultChoices[index[0]]);
			panel.add(field, c);
			index[0]++;
		});
		final int result = JOptionPane.showConfirmDialog(parent, panel, promptTitle, JOptionPane.OK_CANCEL_OPTION,
				JOptionPane.PLAIN_MESSAGE);
		if (result == JOptionPane.OK_OPTION) {
			final String[] values = new String[fields.size()];
			for (int i = 0; i < fields.size(); i++)
				values[i] = (String) fields.get(i).getSelectedItem();
			return values;
		}
		return null;
	}

	public Set<String> getStringSet(final String promptMsg, final String promptTitle,
			final Collection<String> defaultValues) {
		final String userString = getString(promptMsg, promptTitle, toString(defaultValues));
		if (userString == null)
			return null;
		final TreeSet<String> uniqueWords = new TreeSet<>(Arrays.asList(userString.split(",\\s*")));
		uniqueWords.remove("");
		return uniqueWords;
	}

	/**
	 * Simplified color chooser for ColorRGB.
	 *
	 * @see #getColor(String, Color, String...)
	 */
	public ColorRGB getColorRGB(final String title, final ColorRGB defaultValue,
		final String... panes)
	{
		final Color def = (defaultValue == null) ? null : new Color(defaultValue.getRed(), defaultValue.getGreen(),
				defaultValue.getBlue());
		final Color color = getColor(title, def, panes);
		if (color == null) return null;
		return new ColorRGB(color.getRed(), color.getGreen(), color.getBlue());
	}

	/**
	 * Simplified color chooser.
	 *
	 * @param title the title of the chooser dialog
	 * @param defaultValue the initial color set in the chooser
	 * @param panes the panes a list of strings specifying which tabs should be
	 *          displayed. In most platforms this includes: "Swatches", "HSB" and
	 *          "RGB". Note that e.g., the GTK L&amp;F may only include the
	 *          default GtkColorChooser pane. Set to null to include all available
	 *          panes.
	 * @return the color
	 */
	public Color getColor(final String title, final Color defaultValue, final String... panes) {
		assert SwingUtilities.isEventDispatchThread();
        colorChooser(defaultValue);

        // remove spurious panes
		List<String> allowedPanels;
		if (panes != null) {
			allowedPanels = Arrays.asList(panes);
			for (final AbstractColorChooserPanel accp : colorChooser.getChooserPanels()) {
				if (!allowedPanels.contains(accp.getDisplayName()) && colorChooser
					.getChooserPanels().length > 1) colorChooser.removeChooserPanel(accp);
			}
		}

		class ColorTracker implements ActionListener {

			private final JColorChooser chooser;
			private Color color;

			public ColorTracker(final JColorChooser c) {
				chooser = c;
			}

			@Override
			public void actionPerformed(final ActionEvent e) {
				color = chooser.getColor();
			}

			public Color getColor() {
				return color;
			}
		}

		final ColorTracker ok = new ColorTracker(colorChooser);
		final JDialog dialog = JColorChooser.createDialog(parent, title, true, colorChooser, ok, null);
		makeVisible(dialog, true);
		return ok.getColor();
	}

    public Double getDouble(final String promptMsg, final String promptTitle,
		final Number defaultValue)
	{
		try {
			final NumberFormat nf = NumberFormat.getInstance(Locale.US);
			final Number number = nf.parse((String) getObj(promptMsg, promptTitle,
				defaultValue));
			return number.doubleValue();
		}
		catch (final NullPointerException ignored) {
			return null; // user pressed cancel
		}
		catch (final ParseException ignored) {
			return Double.NaN; // invalid user input
		}
	}

    public Integer getPercentage(final String promptMsg, final String promptTitle,
                          final int defaultValue) {
        return getInt(promptMsg, promptTitle, defaultValue, 0, 100);
    }

    public Integer getInt(final String promptMsg, final String promptTitle,
                                 final int defaultValue, final int min, final int max) {
        final JSlider slider = new JSlider(min, max, defaultValue);
        slider.setPaintLabels(true);
        slider.setPaintTicks(true);
        slider.setMajorTickSpacing( (max - min) / 5);
        slider.setMinorTickSpacing( (max - min) / 5 / 2);
        final JComponent[] inputs = new JComponent[] { getLabel(promptMsg), slider };
        final int result = JOptionPane.showConfirmDialog(null, inputs, promptTitle, JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE);
        if (result == JOptionPane.OK_OPTION) {
            return slider.getValue();
        }
        return null;
    }

    public Number[] getTwoNumbers(final String promptMsg, final String promptTitle,
                                  final Number[] defaultValues, final String[] labels, final int decimalPlaces) {
        if (labels.length != 2 && defaultValues.length != 2) {
            throw new IllegalArgumentException("length of defaultValues/labels is not 2");
        }
        final SNTPoint result = getCoordinatesInternal(promptMsg, promptTitle,
                SNTPoint.of(defaultValues[0], defaultValues[1], -1), labels, decimalPlaces);
        return (result == null) ? null : new Number[]{result.getX(), result.getY()};
    }

    public Number[] getThreeNumbers(final String promptMsg, final String promptTitle,
                                    final Number[] defaultValues, final String[] labels, final int decimalPlaces) {
        if (labels.length != 3 && defaultValues.length != 3) {
            throw new IllegalArgumentException("length of defaultValues/labels is not 3");
        }
        final SNTPoint result = getCoordinatesInternal(promptMsg, promptTitle,
                SNTPoint.of(defaultValues), labels, decimalPlaces);
        return (result == null) ? null : new Number[]{result.getX(), result.getY(), result.getZ()};
    }

    public SNTPoint getCoordinates(final String promptMsg, final String promptTitle,
                                   final SNTPoint defaultValue, final int decimalPlaces) {
        return getCoordinatesInternal(promptMsg, promptTitle,
                defaultValue, new String[]{"X", "Y", "Z"}, decimalPlaces);
    }

    public SNTPoint getCoordinatesInternal(final String promptMsg, final String promptTitle, final SNTPoint defaultValue,
                                           String[] labels, final int decimalPlaces) {
        if (labels.length < 2 || labels.length > 3) {
            throw new IllegalArgumentException("Only 2 or 3 values can be retrieved");
        }
        final JPanel panel = new JPanel(new FlowLayout());
        final SNTPoint def = (defaultValue == null) ? SNTPoint.of(0, 0, 0) : defaultValue;
        panel.add(new JLabel(promptMsg));
        final boolean twoD = labels.length == 2;
        final double[] values = (twoD) ? new double[]{def.getX(), def.getY()} : new double[]{def.getX(), def.getY(), def.getZ()};
        final JSpinner[] spinners = new JSpinner[(twoD) ? 2 : 3];
        for (int i = 0; i < spinners.length; i++) {
            panel.add(new JLabel(labels[i]));
            spinners[i] = (decimalPlaces == 0) ?
                    GuiUtils.integerSpinner((int) values[i], -10000, 10000, 10, true) :
                    GuiUtils.doubleSpinner(values[i], -10000, 10000, 10, decimalPlaces);
            panel.add(spinners[i]);
        }
        final int result = JOptionPane.showConfirmDialog(null, panel, promptTitle, JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE);
        if (result == JOptionPane.OK_OPTION) {
            return (twoD) ?
                    SNTPoint.of((Number) spinners[0].getValue(), (Number) spinners[1].getValue(), -1)
                    : SNTPoint.of((Number) spinners[0].getValue(), (Number) spinners[1].getValue(), (Number) spinners[2].getValue());
        }
        return null;
    }

    public float[] getRange(final String promptMsg, final String promptTitle, final float[] defaultRange) {
        final String s = getString(promptMsg, promptTitle, SNTUtils.formatDouble(defaultRange[0], 3) + "-"
                + SNTUtils.formatDouble(defaultRange[1], 3));
        if (s == null)
            return null; // user pressed cancel
        final float[] values = { Float.NaN, Float.NaN };
        try {
            // Regex that handles scientific notation (e.g., 1.5e-10, 2E+5, -3.14e2)
            // Pattern breakdown:
            //   [-+]?           - optional sign
            //   \\d*\\.?\\d+    - digits with optional decimal point (at least one digit required)
            //   (?:[eE][-+]?\\d+)?  - optional exponent part (e or E followed by optional sign and digits)
            final String numberPattern = "[-+]?\\d*\\.?\\d+(?:[eE][-+]?\\d+)?";
            final String regex = "(" + numberPattern + ")\\s*-\\s*(" + numberPattern + ")";
            final Pattern pattern = Pattern.compile(regex);
            final Matcher matcher = pattern.matcher(s.trim());
            if (matcher.find()) {
                values[0] = Float.parseFloat(matcher.group(1));
                values[1] = Float.parseFloat(matcher.group(2));
            }
        } catch (final NumberFormatException ignored) {
            // Return NaN values which will be handled by caller
        }
        return values;
    }

	public void adjustComponentThroughPrompt(final Container container) {

		class DimensionFields implements DocumentListener {

			final JTextField wField;
			final JTextField hField;
			final JCheckBox lockBox;
			final int[] newDims;
			int origWidth, origHeight;
			boolean lockRatio = true;
			boolean pauseSyncFields;

			DimensionFields() {
				newDims = new int[2];
				wField = intField();
				hField = intField();
				lockBox = new JCheckBox("Constrain aspect ratio", lockRatio);
				lockBox.addItemListener(e -> lockRatio = lockBox.isSelected());
			}

			JTextField intField() {
				final NumberFormat format = NumberFormat.getIntegerInstance();
				format.setGroupingUsed(false);
				final NumberFormatter formatter = new NumberFormatter(format);
				formatter.setMinimum(0);
				formatter.setMaximum(Integer.MAX_VALUE);
				formatter.setAllowsInvalid(false);
				final JFormattedTextField field = new JFormattedTextField(formatter);
				field.setColumns(5);
				field.getDocument().addDocumentListener(this);
				return field;
			}

			JPanel panel() {
				final JPanel panel = new JPanel();
				panel.add(new JLabel("Width:"));
				panel.add(wField);
				panel.add(new JLabel("Height:"));
				panel.add(hField);
				panel.add(lockBox);
				return panel;
			}

			int parseInt(final JTextField field) {
				try {
					return Integer.parseInt(field.getText());
				} catch (final NumberFormatException e) {
					return 500;// NumberFormatter ensures this never happens
				}
			}

			void forceAspectRatio(final DocumentEvent e) {
				pauseSyncFields = true;
				if (e.getDocument() == hField.getDocument()) { // height is being set: Adjust width
					newDims[1] = parseInt(hField);
					newDims[0] = Math.round(((float) (newDims[1] * origWidth) / origHeight));
					wField.setText("" + newDims[0]);
				} else { // width is being set: Adjust height
					newDims[0] = parseInt(wField);
					newDims[1] = Math.round(((float) (newDims[0] * origHeight) / origWidth));
					hField.setText("" + newDims[1]);
				}
				pauseSyncFields = false;

			}

			void prompt() {
				origWidth = container.getWidth();
				origHeight = container.getHeight();
				wField.setText("" + origWidth);
				hField.setText("" + origHeight);
				final int result = JOptionPane.showConfirmDialog(parent, panel(), "New Dimensions",
						JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
				if (result == JOptionPane.OK_OPTION) {
					final double w = GuiUtils.extractDouble(hField);
					final double h = GuiUtils.extractDouble(hField);
					if (Double.isNaN(w) || w <= 0 || Double.isNaN(h) || h <= 0) {
						GuiUtils.errorPrompt("Width and Height must > 0.");
						return;
					}
					container.setSize((int) w, (int) h);
				}
			}

			@Override
			public void insertUpdate(final DocumentEvent e) {
				if (!pauseSyncFields && lockRatio)
					forceAspectRatio(e);

			}

			@Override
			public void removeUpdate(final DocumentEvent e) {
				if (!pauseSyncFields && lockRatio)
					forceAspectRatio(e);
			}

			@Override
			public void changedUpdate(final DocumentEvent e) {
				if (!pauseSyncFields && lockRatio)
					forceAspectRatio(e);
			}
		}

		new DimensionFields().prompt();
	}

	public File getOpenFile(final String title, final File file, final String... allowedExtensions) {
		final JFileChooser chooser = fileChooser(title, file, JFileChooser.OPEN_DIALOG, JFileChooser.FILES_ONLY);
		if (allowedExtensions != null && allowedExtensions.length > 0) {
			chooser.addChoosableFileFilter(new FileNameExtensionFilter(
					"Reconstruction files (" + String.join(",", allowedExtensions) + ")", allowedExtensions));
		}
		return (File) getOpenFileChooserResult(chooser);
	}

	public File getSaveFile(final String title, final File file, final String... allowedExtensions) {
		File chosenFile = null;
		final JFileChooser chooser = fileChooser(title, file, JFileChooser.SAVE_DIALOG, JFileChooser.FILES_ONLY);
		if (allowedExtensions != null && allowedExtensions.length > 0) {
			chooser.addChoosableFileFilter(new FileNameExtensionFilter(
					"Files of type " + String.join(",", allowedExtensions).toUpperCase(), allowedExtensions));
		}
		chooser.setFileFilter(chooser.getAcceptAllFileFilter());
		// HACK: On macOS this seems to help to ensure prompt is displayed as frontmost
		final boolean focused = parent instanceof Window && parent.hasFocus();
		if (focused)
			((Window) parent).toBack();
		if (chooser.showSaveDialog(parent) == JFileChooser.APPROVE_OPTION) {
			chosenFile = chooser.getSelectedFile();
		}
		if (chosenFile != null && allowedExtensions != null && allowedExtensions.length == 1) {
			final String path = chosenFile.getAbsolutePath();
			final String extension = allowedExtensions[0];
			if (!path.endsWith(extension))
				chosenFile = new File(path + extension);
		}
		SNTPrefs.setLastKnownDir(chosenFile); // null allowed
		if (focused)
			((Window) parent).toFront();
		return chosenFile;
	}

	public File[] getReconstructionFiles(final File selectedFile) {
		final JFileChooser fileChooser = getReconstructionFileChooser(null);
        if (selectedFile != null) fileChooser.setSelectedFile(selectedFile);
		return (File[])getOpenFileChooserResult(fileChooser);
	}

	public File getFile(final File file, final String extensionWithoutPeriod) {
		final JFileChooser fileChooser = GuiUtils.getDnDFileChooser();
		fileChooser.setDialogTitle("Choose " + extensionWithoutPeriod.toUpperCase() + " File");
		fileChooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
		fileChooser.setDialogType(JFileChooser.OPEN_DIALOG);
		fileChooser.setMultiSelectionEnabled(false);
		fileChooser.addChoosableFileFilter(
				new FileNameExtensionFilter(extensionWithoutPeriod.toUpperCase() , extensionWithoutPeriod));
		if (file != null)
			fileChooser.setSelectedFile(file);
		fileChooser.setMultiSelectionEnabled(false);
		return (File) getOpenFileChooserResult(fileChooser);
	}

	public File getImageFile(final File file) {
		final JFileChooser fileChooser = GuiUtils.getDnDFileChooser();
		fileChooser.setDialogTitle("Choose Image File");
		fileChooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
		fileChooser.setDialogType(JFileChooser.OPEN_DIALOG);
		fileChooser.addChoosableFileFilter(
				new FileNameExtensionFilter("IJ 'native' formats (.tif, .png, .raw, .zip, etc.)", "tif", "tiff", "dcm",
						"avi", "fits", "pgm", "jpg", "gif", "bmp", "zip", "png", "raw"));
		fileChooser.setMultiSelectionEnabled(false);
		if (file != null)
			fileChooser.setSelectedFile(file);
		fileChooser.setMultiSelectionEnabled(false);
		return (File) getOpenFileChooserResult(fileChooser);
	}

	public File getReconstructionFile(final File file, final String extension) {
		FileNameExtensionFilter filter = switch (extension) {
            case "swc" -> new FileNameExtensionFilter("SWC files (.swc)", "swc");
            case "traces" -> new FileNameExtensionFilter("SNT TRACES files (.traces)", "traces");
            case "json" -> new FileNameExtensionFilter("JSON files (.json)", "json");
            case "ndf" -> new FileNameExtensionFilter("NeuronJ NDF files (.ndf)", "ndf");
            case "labels" -> new FileNameExtensionFilter("AmiraMesh labels (.labels)", "labels");
            case null, default -> null;
        };
        final JFileChooser fileChooser = getReconstructionFileChooser(filter);
		fileChooser.setSelectedFile(file);
		if (extension == null)
			fileChooser.setDialogTitle("Choose Reconstruction File");
		else if ("labels".equals(extension))
			fileChooser.setDialogTitle("Choose LABELS File");
		else
			fileChooser.setDialogTitle("Choose Reconstruction (" + extension.toUpperCase() + ") File");
		fileChooser.setMultiSelectionEnabled(false);
		return (File)getOpenFileChooserResult(fileChooser);
	}

	private JFileChooser getReconstructionFileChooser(final FileNameExtensionFilter filter) {
		final JFileChooser fileChooser = GuiUtils.getDnDFileChooser();
		fileChooser.setDialogTitle("Choose Reconstruction File(s)");
		fileChooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
		fileChooser.setDialogType(JFileChooser.OPEN_DIALOG);
        fileChooser.addChoosableFileFilter(Objects.requireNonNullElseGet(filter, () -> new FileNameExtensionFilter(
                "Reconstruction files (.traces, .swc, .json, .ndf)", "traces", "swc", "json", "ndf")));
		fileChooser.setMultiSelectionEnabled(true);
		return fileChooser;
	}

	private Object getOpenFileChooserResult(final JFileChooser fileChooser) {
		// HACK: On macOS this seems to help to ensure prompt is displayed as frontmost
		final boolean focused = parent instanceof Window && parent.hasFocus();
		if (focused) ((Window) parent).toBack();
		if (fileChooser.showOpenDialog(parent) == JFileChooser.APPROVE_OPTION) {
			if (fileChooser.isMultiSelectionEnabled()) {
				final File[] result = fileChooser.getSelectedFiles();
				SNTPrefs.setLastKnownDir(result[0]);
				return result;
			} else {
				final File result = fileChooser.getSelectedFile();
				SNTPrefs.setLastKnownDir(result);
				return result;
			}
		}
		if (focused) ((Window) parent).toFront();
		return null;
	}

	public static JFileChooser fileChooser(final String title, final File file, final int type, final int selectionMode) {
		final JFileChooser chooser = getDnDFileChooser();
		chooser.setDialogTitle(title);
		chooser.setFileSelectionMode(selectionMode);
		chooser.setDialogType(type);
		if (file != null) {
			if (selectionMode == JFileChooser.FILES_ONLY) {
				if (file.isDirectory()) {
					chooser.setCurrentDirectory(file);
				} else {
					chooser.setCurrentDirectory(file.getParentFile());
				}
			}
			chooser.setSelectedFile(file);
		}
		return chooser;
	}

	private Object getObj(final String promptMsg, final String promptTitle,
		final Object defaultValue)
	{
		final String wrappedText = (parent == null) ? promptMsg : getWrappedText(new JLabel(), promptMsg);
		return JOptionPane.showInputDialog(parent, wrappedText, promptTitle,
			JOptionPane.PLAIN_MESSAGE, null, null, defaultValue);
	}

	public void centeredMsg(final String msg, final String title) {
		centeredDialog(msg, title, JOptionPane.PLAIN_MESSAGE);
	}

	public void centeredMsg(final String msg, final String title, final String buttonLabel) {
		if (buttonLabel == null) {
			centeredMsg(msg, title);
		} else {
			final String defaultButtonLabel = UIManager.getString("OptionPane.okButtonText");
			UIManager.put("OptionPane.okButtonText", buttonLabel);
			centeredMsg(msg, title);
			UIManager.put("OptionPane.okButtonText", defaultButtonLabel);
		}
	}

	public JDialog dialog(final String msg, final JComponent component,
		final String title)
	{
		final Object[] params = { getLabel(msg), component };
		final JOptionPane optionPane = new JOptionPane(params,
			JOptionPane.PLAIN_MESSAGE, JOptionPane.DEFAULT_OPTION);
		final JDialog dialog = optionPane.createDialog(title);
		if (parent != null) dialog.setLocationRelativeTo(parent);
		return dialog;
	}

	private int centeredDialog(final String msg, final String title, final int type) {
		final JOptionPane optionPane = new JOptionPane(getLabel(msg), type, JOptionPane.DEFAULT_OPTION);
		final JDialog d = optionPane.createDialog(title);
		if (parent != null) {
			AWTWindows.centerWindow(parent.getBounds(), d); // we could also use d.setLocationRelativeTo(parent);
		}
		makeVisible(d, true);
		final Object result = optionPane.getValue();
		return (result instanceof Integer) ? (Integer) result : SwingDialog.UNKNOWN_OPTION;
	}

	public static void displayBanner(final String msg, final Color background, final Component parent) {
		final JLabel label = new JLabel(msg);
		label.setHorizontalAlignment(SwingConstants.CENTER);
		if (!msg.startsWith("<HTML>"))
			label.setFont(label.getFont().deriveFont(label.getFont().getSize2D() * 2.5f));
		final int margin = label.getFontMetrics(label.getFont()).stringWidth("XX");
		label.setBorder(BorderFactory.createEmptyBorder(margin, margin, margin, margin));
		final JidePopup popup = new JidePopup();
		popup.add(label);
		final MouseAdapter adapter = new MouseAdapter() {
			@Override
			public void mouseMoved(final MouseEvent e) {
				dismiss();
			}
			@Override
			public void mouseClicked(final MouseEvent e) {
				dismiss();
			}
			@Override
			public void mouseDragged(final MouseEvent e) {
				dismiss();
			}
			@Override
			public void mousePressed(final MouseEvent e) {
				dismiss();
			}
			void dismiss() {
				popup.removeMouseListener(this);
				parent.removeMouseListener(this);
				popup.hidePopup(true);
				parent.requestFocusInWindow();
			}
		};
		popup.addMouseListener(adapter);
		if (parent != null) {
			popup.setOwner(parent);
			parent.addMouseListener(adapter);
		}
		label.setForeground(SNTColor.contrastColor(background));
		label.setBackground(background);
		popup.setBackground(background);
		popup.getContentPane().setBackground(background);
		popup.setReturnFocusToOwner(true);
		popup.setTransient(true);
		popup.setMovable(false);
		popup.setDefaultMoveOperation(JidePopup.HIDE_ON_MOVED);
		popup.setTimeout(4000);
		SwingUtilities.invokeLater(() -> popup.showPopup(SwingConstants.CENTER, parent));
	}

	public static void addTooltip(final JComponent c, final String text) {
		final int length = c.getFontMetrics(c.getFont()).stringWidth(text);
		final String tooltipText = "<html>" + ((length > 500) ? "<body><div style='width:500;'>" : "") + text;
		if (c instanceof JPanel) {
			for (final Component cc : c.getComponents()) {
				if (cc instanceof JComponent)
					((JComponent) cc).setToolTipText(tooltipText);
			}
		} else {
			c.setToolTipText(tooltipText);
		}
	}

	private JLabel getLabel(final String text) {
		if (text == null || text.startsWith("<")) {
			return new JLabel(text);
		}
		else {
			final JLabel label = new JLabel();
			label.setText(getWrappedText(label, text));
			return label;
		}
	}

	private String getWrappedText(final JComponent c, final String text) {
        if (text.startsWith("<")) return text; // <HTML>
		final int width = c.getFontMetrics(c.getFont()).stringWidth(text);
		final int max = (parent == null) ? 600 : parent.getWidth();
		return "<html><body><div style='width:" + Math.min(width, max) + ";'>" +
			text;
	}

	public void blinkingError(final JComponent blinkingComponent,
		final String msg)
	{
		final Color prevColor = blinkingComponent.getForeground();
		final Color flashColor = errorColor();
		final Timer blinkTimer = new Timer(400, new ActionListener() {

			private int count = 0;
			private static final int MAX_COUNT = 100;
			private boolean on = false;

			@Override
			public void actionPerformed(final ActionEvent e) {
				if (count >= MAX_COUNT) {
					blinkingComponent.setForeground(prevColor);
					((Timer) e.getSource()).stop();
				}
				else {
					blinkingComponent.setForeground(on ? flashColor : prevColor);
					on = !on;
					count++;
				}
			}
		});
		blinkTimer.start();
		if (centeredDialog(msg, "Ongoing Operation",
			JOptionPane.WARNING_MESSAGE) > Integer.MIN_VALUE)
		{ // Dialog
			// dismissed
			blinkTimer.stop();
        }
        blinkingComponent.setForeground(prevColor);
    }

    private static final Color LINK_COLOR = new Color(0, 128, 255);
    private static final Color ERROR_COLOR = new Color(229, 62, 77);
    private static final Color WARNING_COLOR = new Color(254, 210, 132);

    public static Color errorColor() {
        return ERROR_COLOR;
    }

    public static Color warningColor() {
        return WARNING_COLOR;
    }

    public static JLabel shortSmallMsg(final String msg) {
		final JLabel label = new JLabel(msg);
		label.putClientProperty(FlatClientProperties.STYLE_CLASS, "small");
		return label;
	}

    public static JTextArea longSmallMsg(final String msg, final Component parent) {
        final JTextArea ta = new JTextArea();
        ta.setBackground(parent.getBackground());
        ta.setEditable(false);
        ta.setMargin(null);
        ta.setBorder(null);
        ta.setLineWrap(true);
        ta.setWrapStyleWord(true);
        ta.setFocusable(false);
        ta.setEnabled(false);
        ta.putClientProperty(FlatClientProperties.STYLE_CLASS, "small");
        ta.setText(msg);
        return ta;
    }

    private static class SpinningIconLabel extends JLabel {
		double angle = 0;
		double scale = 1.0;
		final Timer timer;

		SpinningIconLabel(final Icon icon) {
			super(icon);
			timer = new Timer(16, e -> {
                angle += 0.1;
                scale -= 0.01;
                if (scale <= 0) {
                    angle = 0;
                    scale = 1.0;
                }
                repaint();
            });
			addMouseListener(new MouseAdapter() {
				@Override
				public void mouseClicked(final MouseEvent e) {
					if (timer.isRunning()) {
						timer.stop();
						angle = 0;
						scale = 1.0;
						repaint();
					} else {
						timer.start();
					}
				}
			});
		}

		@Override
		protected void paintComponent(final Graphics g) {
			final Graphics2D g2d = (Graphics2D) g;
			final AffineTransform originalTransform = g2d.getTransform();
			final int x = getWidth() / 2;
			final int y = getHeight() / 2;
			g2d.translate(x, y);
			g2d.rotate(angle);
			g2d.scale(scale, scale);
			g2d.translate(-x, -y);
			super.paintComponent(g);
			g2d.setTransform(originalTransform);
		}
	}

	private static String getImageJVersion() {
		try {
			return "ImageJ " + SNTUtils.getContext().getService(org.scijava.app.AppService.class).getApp().getVersion();
		} catch (final Throwable ignored) {
			return "ImageJ " + ij.ImageJ.VERSION + ij.ImageJ.BUILD;
		}
	}

	public static JDialog showAboutDialog() {
		final JPanel main = new JPanel();
		main.add(new SpinningIconLabel(SplashScreen.getIcon()));
		final JPanel side = new JPanel();
		main.add(side);
		side.setLayout(new BoxLayout(side, BoxLayout.Y_AXIS));
		final JLabel title = new JLabel(SNTUtils.getReadableVersion());
		SplashScreen.assignStyle(title, 2);
		side.add(title);
		final JLabel subTitle = new JLabel("The ImageJ Framework for Neuroanatomy");
		SplashScreen.assignStyle(subTitle, 1);
		side.add(subTitle);
		side.add(new JLabel(" ")); // spacer
		final String details = getImageJVersion() + "  |  Java " + System.getProperty("java.version");
		final JLabel ijDetails = leftAlignedLabel(details, "", true);
		ijDetails.setAlignmentX(JLabel.CENTER_ALIGNMENT);
		ijDetails.setToolTipText("Displays detailed System Information");
		side.add(ijDetails);
		side.add(new JLabel(" ")); // spacer
		final JPanel urls = new JPanel();
		side.add(urls);
		JLabel url = leftAlignedLabel("Release Notes   ", MenuItems.releaseNotesURL(), true);
		urls.add(url);
		url = leftAlignedLabel("Documentation   ", "https://imagej.net/plugins/snt/", true);
		urls.add(url);
		url = leftAlignedLabel("Forum   ", "https://forum.image.sc/tag/snt", true);
		urls.add(url);
		url = leftAlignedLabel("Source   ", "https://github.com/morphonets/SNT/", true);
		urls.add(url);
		url = leftAlignedLabel("Manuscript", "http://dx.doi.org/10.1038/s41592-021-01105-7", true);
		urls.add(url);
		final JOptionPane optionPane = new JOptionPane(main, JOptionPane.PLAIN_MESSAGE, JOptionPane.DEFAULT_OPTION);
		final JDialog d = optionPane.createDialog("About SNT...");
		ijDetails.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseClicked(final MouseEvent me) {
				Timer timer;
				if (Types.load("org.scijava.plugins.commands.debug.SystemInformation") == null) {
					ijDetails.setText("<HTML><b>Gathering system information... This may take a while.");
					SNTUtils.getContext().getService(CommandService.class).run("org.scijava.plugins.commands.debug.SystemInformation", true);
					timer = new Timer(3000, e -> d.dispose());
				} else {
					ijDetails.setText("<HTML>System Information command not found!?");
					timer = new Timer(3000, e -> ijDetails.setText(details));
				}
				timer.setRepeats(false);
				timer.start();
			}
		});
		d.setLocationRelativeTo(null);
		d.setVisible(true);
		d.toFront();
		d.setAlwaysOnTop(!d.hasFocus()); // see makeVisible()
		return d;
	}

	public void showDirectory(final File file) {
		try {
			FileChooser.reveal(file);
		} catch (final Exception ignored) {
			error((file == null || file.getAbsolutePath().isEmpty()) ? "Directory does not seem to be accessible." : "Could not access\n" + file.getAbsolutePath());
		}
	}

	/* Static methods */
	public static <T> String toString(final Iterable<T> iterable) {
		if (iterable == null) {
			return "";
		}
		final Iterator<T> i = iterable.iterator();
		if (!i.hasNext()) {
			return "";
		}
		final StringBuilder sb = new StringBuilder();
		while (true) {
			final T t = i.next();
			sb.append(t);
			if (!i.hasNext()) {
				return sb.toString();
			}
			sb.append(", ");
		}
	}

	public static float uiFontSize() {
		try {
			return UIManager.getFont("defaultFont").getSize2D();
		} catch (final Exception e) {
			return 16;
		}
	}

	public static void initSplashScreen() {
		splashScreen = new SplashScreen();
		splashScreen.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseClicked(final MouseEvent e) {
				closeSplashScreen();
			}
		});
	}

	public static void closeSplashScreen() {
		if (splashScreen != null) splashScreen.dispose();
		splashScreen = null;
	}

	public static void addSeparator(final JComponent component,
			final String heading, final boolean vgap, final GridBagConstraints c)
		{
			addSeparator(component, leftAlignedLabel(heading, null, true), vgap, c);
		}

	public static void addSeparator(final JComponent component,
		final JLabel label, final boolean vgap, final GridBagConstraints c)
	{
		final int previousTopGap = c.insets.top;
		label.putClientProperty(FlatClientProperties.STYLE_CLASS, "small");
		if (vgap) c.insets.top = component.getFontMetrics(label.getFont()).getHeight();
		final int prevAnchor = c.anchor;
		final int prevFill = c.fill;
		c.anchor = GridBagConstraints.WEST;
		c.fill = GridBagConstraints.NONE;
		component.add(label, c);
		c.anchor = prevAnchor;
		c.fill = prevFill;
		if (vgap) c.insets.top = previousTopGap;
	}

	public static void addSeparator(final JPopupMenu menu, final String header) {
		final JLabel label = leftAlignedLabel(header, false);
		if (menu.getComponentCount() > 1) menu.addSeparator();
		menu.add(label);
	}

	public static void addSeparator(final JMenu menu, final String header) {
		final JLabel label = leftAlignedLabel(header, false);
		if (menu.getMenuComponents().length > 1) menu.addSeparator();
		menu.add(label);
	}

	public static int renderedWidth(final String text) {
		final JLabel l = new JLabel();
		return l.getFontMetrics(l.getFont()).stringWidth(text);
	}

	public static JLabel leftAlignedLabel(final String text, final boolean enabled) {
		return leftAlignedLabel(text, null, enabled);
	}

	public static JLabel leftAlignedLabel(final String text, final String uri,
		final boolean enabled)
	{
		final JLabel label = new JLabel(text);
		label.setHorizontalAlignment(SwingConstants.LEFT);
		label.setEnabled(enabled);
		if (uri != null && Desktop.isDesktopSupported()) {
            label.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(final MouseEvent e) {
                    label.setForeground(LINK_COLOR);
                    label.setCursor(new Cursor(Cursor.HAND_CURSOR));
                }

				@Override
				public void mouseExited(final MouseEvent e) {
					label.setForeground(UIManager.getColor("Label.foreground")); // same effect as null?
					label.setCursor(new Cursor(Cursor.DEFAULT_CURSOR));
				}

				@Override
				public void mouseClicked(final MouseEvent e) {
					if (label.isEnabled()) openURL(uri);
				}
			});
		}
		return label;
	}

	public void searchForum(final String query) {
			String url;
			try {
				url = "https://forum.image.sc/search?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8);
			} catch (final Exception ignored) {
				url = query.trim().replace(" ", "%20");
			}
			openURL(url);
	}

	public static void openURL(final String uri) {
		try {
			Desktop.getDesktop().browse(new URI(uri));
		} catch (IOException | URISyntaxException ex) {
			if (!uri.isEmpty()) {
				final JTextPane f = new JTextPane(); // Error message with selectable text
				f.setContentType("text/html");
				f.setText("<HTML>Web page could not be open. Please visit<br>" + uri + "<br>using your web browser.");
				f.setEditable(false);
				f.setBackground(null);
				f.setBorder(null);
				JOptionPane.showMessageDialog(null, f, "Error", JOptionPane.ERROR_MESSAGE);
			}
		}
	}

	public static String ctrlKey() {
		return (PlatformUtils.isMac()) ? "Cmd" : "Ctrl";
	}

	public static Window getConsole() {
		for (final Window w : JFrame.getWindows()) {
			if (w instanceof JFrame && ("Console".equals(((JFrame) w).getTitle()))) {
				return w;
			}
		}
		return null;
	}

    public static JColorChooser colorChooser(final Color defaultValue) {
        if (colorChooser == null) {
            colorChooser = new JColorChooser(defaultValue != null ? defaultValue : Color.WHITE);
            colorChooser.setPreviewPanel(new JPanel()); // remove preview pane
        }
        return colorChooser;
    }

	public static void setRenderingHints(final Graphics2D g2 ) {
		g2.setRenderingHint( RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON );
		g2.setRenderingHint( RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_NORMALIZE );
		g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
	}

    public static GridBagConstraints defaultGbc() {
		final GridBagConstraints cp = new GridBagConstraints();
		cp.anchor = GridBagConstraints.LINE_START;
		cp.gridwidth = GridBagConstraints.REMAINDER;
		cp.fill = GridBagConstraints.HORIZONTAL;
		cp.insets = new Insets(0, 0, 0, 0);
		cp.weightx = 1.0;
		cp.gridx = 0;
		cp.gridy = 0;
		return cp;
	}

	public static void recolorTracks(final JScrollPane scrollPane, final Color color, final boolean updateUI) {
		final Component c = scrollPane.getCorner(JScrollPane.LOWER_TRAILING_CORNER);
		if (c != null) {
			c.setBackground(color);
		} else {
			final JPanel dummy = new JPanel();
			dummy.setBackground(color);
			scrollPane.setCorner(JScrollPane.LOWER_TRAILING_CORNER, dummy);
		}
		final Map<String, Object> style = new HashMap<>();
		style.put("track", color);
		style.put("hoverTrackColor", color);
		scrollPane.getHorizontalScrollBar().putClientProperty(FlatClientProperties.STYLE, style);
		scrollPane.getVerticalScrollBar().putClientProperty(FlatClientProperties.STYLE, style);
		if (updateUI) scrollPane.updateUI();
	}

	public static void addClearButton(final JTextField textField) {
		textField.putClientProperty(FlatClientProperties.TEXT_FIELD_SHOW_CLEAR_BUTTON, true);
	}

	public static void addPlaceholder(final JTextField textField, final String placeholder) {
		textField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, placeholder);
	}

	public static void removeIcon(final Object rootPaneContainerOrWindow) {
		if (rootPaneContainerOrWindow instanceof RootPaneContainer)
			((RootPaneContainer) rootPaneContainerOrWindow).getRootPane().putClientProperty(FlatClientProperties.TITLE_BAR_SHOW_ICON, false);
        else if (rootPaneContainerOrWindow instanceof Window)
            ((Window)rootPaneContainerOrWindow).setIconImage(new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB_PRE));
    }

    private static final Color FALLBACK_SELECTION_COLOR = new Color(75, 110, 175);

    public static Color getSelectionColor() {
        final Color c = UIManager.getColor("Tree.selectionBackground");
        return (c != null) ? c : FALLBACK_SELECTION_COLOR;
    }

    public static Color getDisabledComponentColor() {
		if (disabledColor == null) {
            try {
                disabledColor = UIManager.getColor("MenuItem.disabledForeground");
            } catch (final Exception ignored) {
                disabledColor = Color.GRAY; // e.g. headless mode
            }
        }
        return disabledColor;
	}

	public static JSpinner integerSpinner(final int value, final int min,
		final int max, final int step, final boolean allowEditing)
	{
		final int maxDigits = Integer.toString(max).length();
		final SpinnerModel model = new SpinnerNumberModel(value, min, max, step);
		final JSpinner spinner = new JSpinner(model);
		final JFormattedTextField textField = ((JSpinner.NumberEditor) spinner.getEditor()).getTextField();
		textField.setColumns(maxDigits);
		textField.setEditable(allowEditing);
		if (allowEditing) {
			// ((NumberFormatter) textField.getFormatter()).setAllowsInvalid(false); // This disables editing completely on Ubuntu!?
			final Color c = textField.getForeground();
			textField.addPropertyChangeListener(evt -> {
				if ("editValid".equals(evt.getPropertyName())) {
					textField.setForeground((Boolean.FALSE.equals(evt.getNewValue())) ? errorColor() : c);
				}
			});
		}
		return spinner;
	}

	public static JSpinner doubleSpinner(final double value, final double min,
		final double max, final double step, final int nDecimals)
	{
		final int maxDigits = SNTUtils.formatDouble(max, nDecimals).length();
		final SpinnerModel model = new SpinnerNumberModel(value, min, max, step);
		final JSpinner spinner = new JSpinner(model);
		final JFormattedTextField textfield = ((DefaultEditor) spinner.getEditor())
			.getTextField();
		textfield.setColumns(maxDigits);
		final NumberFormatter formatter = (NumberFormatter) textfield
			.getFormatter();
		final StringBuilder decString = new StringBuilder();
		while (decString.length() < nDecimals)
			decString.append("0");
		final DecimalFormat decimalFormat = new DecimalFormat("0." + decString);
		formatter.setFormat(decimalFormat);
		formatter.setAllowsInvalid(false);
		return spinner;
	}

	public static double extractDouble(final JTextField textfield) {
		try {
			final NumberFormat nf = NumberFormat.getInstance(Locale.US);
			final Number number = nf.parse(textfield.getText());
			return number.doubleValue();
		}
		catch (final NullPointerException | ParseException ignored) {
			return Double.NaN; // invalid user input
		}
	}

	public static void enableComponents(final java.awt.Container container,
		final boolean enable)
	{
		final Component[] components = container.getComponents();
		for (final Component component : components) {
			if (!(component instanceof JPanel)) component.setEnabled(enable); // otherwise
																																				// JPanel
																																				// background
																																				// will
																																				// change
			if (component instanceof java.awt.Container) {
				enableComponents((java.awt.Container) component, enable);
			}
		}
	}

	public static String micrometer() {
		return "µm";
	}

	/**
	 * Returns a more human-readable representation of a length in micrometers.
	 * <p>
	 * E.g., scaledMicrometer(0.01,1) returns "1.0nm"
	 * </p>
	 *
	 * @param umLength the length in micrometers
	 * @param digits the number of output decimals
	 * @return the scaled unit
	 */
	@SuppressWarnings("unused")
	public static String scaledMicrometer(final double umLength, final int digits) {
		String symbol = "";
		double length = 0;
		if (umLength < 0.0001) {
			length = umLength * 10000;
			symbol = "Å";
		}
		if (umLength < 1) {
			length = umLength * 1000;
			symbol = "nm";
		}
		else if (umLength < 1000) {
			length = umLength;
			symbol = micrometer();
		}
		else if (umLength > 1000 && umLength < 10000) {
			length = umLength / 1000;
			symbol = "mm";
		}
		else if (umLength > 10000 && umLength < 1000000) {
			length = umLength / 10000;
			symbol = "cm";
		}
		else if (umLength > 1000000000) {
			length = umLength / 1000000000;
			symbol = "km";
		}
		else if (umLength > 1000000) {
			length = umLength / 1000000;
			symbol = "m";
		}
		return SNTUtils.formatDouble(length, digits) + symbol;
	}

	public static void errorPrompt(final String msg) {
		new GuiUtils().error(msg, "SNT v" + SNTUtils.VERSION);
	}

	public static String[] availableLookAndFeels() {
		return new String[] { LAF_LIGHT, LAF_LIGHT_INTJ, LAF_DARK, LAF_DARCULA };
	}

	public static void setLookAndFeel() {
		storeExistingLookAndFeel();
		// If SNT is not using FlatLaf but Fiji is, prefer Fiji choice
		if (existingLaf instanceof FlatLaf)
			return;
		// Otherwise apply SNT's L&F preference as long as it is valid
		final String lafName = SNTPrefs.getLookAndFeel(); // never null
		if (existingLaf == null || !lafName.equals(existingLaf.getName()))
			setLookAndFeel(SNTPrefs.getLookAndFeel(), false);
	}

	public static JFileChooser getDnDFileChooser() {
		final FileChooser fileChooser = new FileChooser();
		fileChooser.setAcceptAllFileFilterUsed(true);
		fileChooser.setCurrentDirectory(SNTPrefs.lastknownDir());
		new FileDrop(fileChooser, files -> {
			if (files.length == 0) { // Is this even possible?
				fileChooser.error("Dropped file(s) not recognized.");
				return;
			}
			// see ij.io.DragAndDropHandler
			final File firstFile = files[0];
			if (fileChooser.isMultiSelectionEnabled()) {
				final File dir = (firstFile.isDirectory()) ? firstFile : firstFile.getParentFile();
				fileChooser.setCurrentDirectory(dir);
				fileChooser.setSelectedFiles(files);
			} else {
				if (fileChooser.getFileSelectionMode() == JFileChooser.DIRECTORIES_ONLY && !firstFile.isDirectory())
					fileChooser.setCurrentDirectory(firstFile.getParentFile());
				if (fileChooser.getDialogType() == JFileChooser.SAVE_DIALOG && firstFile.isDirectory())
					fileChooser.setCurrentDirectory(firstFile);
				else
					fileChooser.setSelectedFile(firstFile);
			}
			fileChooser.rescanCurrentDirectory();
		});
		return fileChooser;
	}

	private static void storeExistingLookAndFeel() {
		existingLaf = UIManager.getLookAndFeel();
	}

	public static void restoreLookAndFeel() {
		try {
			if (existingLaf != null) UIManager.setLookAndFeel(existingLaf);
		} catch (final Error | Exception ignored) {
			// do nothing
		}
	}

	private static boolean setSystemLookAndFeel() {
		try {
			// With Ubuntu and java 8 we need to ensure we're using
			// GTK+ L&F otherwise no scaling occurs with hiDPI screens
			UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
			LookAndFeelFactory.installDefaultLookAndFeelAndExtension();
			LookAndFeelFactory.setProductsUsed(ProductNames.PRODUCT_COMMON);
			return true;
			// checkGTKLookAndFeel();
		} catch (final Error | Exception ignored) {
			return false;
		}
	}

	public static boolean setLookAndFeel(final String lookAndFeelName, final boolean persistentChoice) {
		return setLookAndFeel(lookAndFeelName, persistentChoice, (Component[])null);
	}

	public static boolean setLookAndFeel(final String lookAndFeelName, final boolean persistentChoice, final Component... componentsToUpdate) {
		boolean success;
		storeExistingLookAndFeel();
		switch (lookAndFeelName) {
		case (LAF_LIGHT):
			success = FlatLightLaf.setup();
			break;
		case (LAF_LIGHT_INTJ):
			success = FlatIntelliJLaf.setup();
			break;
		case (LAF_DARK):
			success = FlatDarkLaf.setup();
			break;
		case (LAF_DARCULA):
			success = FlatDarculaLaf.setup();
			break;
		default:
			success = setSystemLookAndFeel();
			if (!success) existingLaf = null;
			break;
		}
		if (!success) return false;
		if (componentsToUpdate == null) {
			FlatLaf.updateUI();
		} else {
			SwingUtilities.invokeLater(() -> {
				for (final Component component : componentsToUpdate) {
					if (component == null)
						continue;
				final Window window = (component instanceof Window) ? (Window) component
						: SwingUtilities.windowForComponent(component);
				try {
                    SwingUtilities.updateComponentTreeUI(Objects.requireNonNullElse(window, component));
				} catch (final Exception ex) {
						SNTUtils.error("", ex);
					}
				}
			});
		}
		if (persistentChoice) {
			SNTPrefs.setLookAndFeel(lookAndFeelName);
		}
		return true;
	}

	public static void setAutoDismiss(final JDialog dialog) {
		final int DELAY = 3000;
		final Timer timer = new Timer(DELAY, e -> dialog.dispose());
		timer.setRepeats(false);
		dialog.addMouseListener(new MouseAdapter() {

			private long lastUpdate;

			@Override
			public void mouseClicked(final MouseEvent e) {
				dialog.dispose();
			}

			@Override
			public void mouseExited(final MouseEvent e) {
				if (System.currentTimeMillis() - lastUpdate > DELAY) dialog.dispose();
				else timer.start();
			}

			@Override
			public void mouseEntered(final MouseEvent e) {
				lastUpdate = System.currentTimeMillis();
				timer.stop();
			}

		});
		timer.start();
	}

    public static void tile(final List<? extends Window> windowList) {
        if (windowList == null || windowList.isEmpty()) return;

        final List<? extends Window> windows = List.copyOf(windowList);
        final int n = windows.size();

        // Get screen bounds
        final GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
        final Rectangle screen = ge.getMaximumWindowBounds();

        // Calculate grid dimensions
        final int cols = (int) Math.ceil(Math.sqrt(n));
        final int rows = (int) Math.ceil((double) n / cols);

        // Calculate tile size with small gap
        final int gap = 2;
        final int tileWidth = (screen.width - (cols + 1) * gap) / cols;
        final int tileHeight = (screen.height - (rows + 1) * gap) / rows;

        // Position windows in grid
        for (int i = 0; i < n; i++) {
            final int col = i % cols;
            final int row = i / cols;
            final int x = screen.x + gap + col * (tileWidth + gap);
            final int y = screen.y + gap + row * (tileHeight + gap);

            final Window window = windows.get(i);
            window.setBounds(x, y, tileWidth, tileHeight);
            window.toFront();
        }

        SwingUtilities.invokeLater(() -> windows.forEach(w -> w.setVisible(true)));
    }


    public JDialog showHTMLDialog(final String msg, final String title, final boolean modal) {
        final JDialog dialog = new HTMLDialog(msg, title, modal);
        dialog.setVisible(true);
		return dialog;
	}

	public void combineSNTChartPrompt() {

		class CombineGUI implements DocumentListener {

			final JTextField colField;
			final JTextField rowField;
			final JList<String> titles;
			final JCheckBox checkbox;
            final JTextField titleField;
            final Map<String, SNTChart> charts;

			boolean pauseSyncFields;
			private int nRows;
			private int nCols;

			CombineGUI(final Collection<SNTChart> inputCharts) {
				charts = new TreeMap<>((a, b) -> {
					try {
						return Integer.valueOf(a.split(". ")[0]).compareTo(Integer.valueOf(b.split(". ")[0]));
					} catch (final Exception ignored) {
						return a.compareTo(b);
					}
				});
				int idx = 1;
				for (final SNTChart c : inputCharts) {
					if (!c.isCombined()) {
						// index ensures charts with repeated titles are not excluded
						charts.put(String.format("%d. %s", idx++, c.getTitle()), c);
					}
				}
				colField = intField();
				rowField = intField();
				titles = getJList(charts.keySet().toArray(new String[0]), null);
				checkbox = new JCheckBox("Label panels", false);
                titleField = new JTextField();
                GuiUtils.addClearButton(titleField);
                GuiUtils.addPlaceholder(titleField, "Combined Charts");
			}

			JTextField intField() {
				final NumberFormat format = NumberFormat.getIntegerInstance();
				format.setGroupingUsed(false);
				final NumberFormatter formatter = new NumberFormatter(format);
				formatter.setMinimum(1);
				formatter.setMaximum(charts.size());
				//formatter.setAllowsInvalid(false);
				final JFormattedTextField field = new JFormattedTextField(formatter);
				field.setColumns(5);
				field.getDocument().addDocumentListener(this);
				return field;
			}

			JPanel panel() {
				final JPanel contentPane = new JPanel(new GridBagLayout());
				final GridBagConstraints c = defaultGbc();
				c.fill = GridBagConstraints.HORIZONTAL;
				c.gridwidth = 1;
                contentPane.add(leftAlignedLabel("Title for montage:", true), c);
                ++c.gridy;
                contentPane.add(titleField, c);
                ++c.gridy;
                contentPane.add(leftAlignedLabel("Charts to be combined:", true), c);
				++c.gridy;
				contentPane.add(getScrollPane(titles), c);
				++c.gridy;
				contentPane.add(leftAlignedLabel("Montage Layout:", true), c);
				++c.gridy;
				final JPanel panel1 = new JPanel();
				panel1.add(new JLabel("No. columns:"));
				panel1.add(colField);
				panel1.add(new JLabel("No rows:"));
				panel1.add(rowField);
				contentPane.add(panel1, c);
				++c.gridy;
				contentPane.add(checkbox, c);
				return contentPane;
			}

			int parseInt(final JTextField field) {
				try {
					return Math.max(1, Integer.parseInt(field.getText()));
				} catch (final NumberFormatException e) {
					return 1;// NumberFormatter ensures this never happens
				}
			}

			void updateFields(final DocumentEvent e) {
				pauseSyncFields = true;
				int n = titles.getSelectedIndices().length;
				if (n == 0)
					n = charts.size();
				if (e.getDocument() == rowField.getDocument()) { // rows is being set: Adjust cols
					nRows = parseInt(rowField);
					nCols = Math.max(1, (int) Math.ceil((double) n / nRows));
					colField.setText("" + nCols);
				} else { // cols are being set: Adjust rows
					nCols = parseInt(colField);
					nRows = Math.max(1, (int) Math.ceil((double) n / nCols));
					rowField.setText("" + nRows);
				}
				pauseSyncFields = false;
			}

			void prompt() {
				nCols = Math.max(1, (int) Math.floor(Math.sqrt(charts.size())));
				nRows = Math.max(1, (int) Math.ceil((double) charts.size() / nCols));
				colField.setText("" + nCols);
				rowField.setText("" + nRows);
				final int result = JOptionPane.showConfirmDialog(parent, panel(), "Make Chart Montage",
						JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
				if (result == JOptionPane.OK_OPTION) {
					final double rows = GuiUtils.extractDouble(rowField);
					final double cols = GuiUtils.extractDouble(rowField);
					if (Double.isNaN(rows) || rows <= 0 || Double.isNaN(cols) || cols <= 0) {
						error("No. of columns and no. of rows must > 0.");
						return;
					}
					final Collection<SNTChart> selection = (titles.getSelectedIndices().length == 0) ? charts.values()
							: charts.entrySet().stream()
									.filter(entry -> titles.getSelectedValuesList().contains(entry.getKey()))
									.map(Map.Entry::getValue).collect(Collectors.toList());
					if (selection.isEmpty()) {
						error("No charts selected from list.");
						return;
					}
					SNTChart.combine(selection, titleField.getText(), (int) rows, (int) cols, checkbox.isSelected()).show();
				}
			}

			@Override
			public void insertUpdate(final DocumentEvent e) {
				if (!pauseSyncFields)
					updateFields(e);
			}

			@Override
			public void removeUpdate(final DocumentEvent e) {
				if (!pauseSyncFields)
					updateFields(e);
			}

			@Override
			public void changedUpdate(final DocumentEvent e) {
				if (!pauseSyncFields)
					updateFields(e);
			}
		}

		final List<SNTChart> charts = SNTChart.openCharts().stream().filter(c -> !c.isCombined())
				.collect(Collectors.toList());
		if (charts.size() < 2) {
			error("No charts available: Either no other charts are currently open,"
					+ " or displayed ones cannot be merged. Make sure that at  least"
					+ " two single charts (histogram, plot, etc.) are open and retry.");
		} else {
			new CombineGUI(charts).prompt();
		}
	}

	public static void drawDragAndDropPlaceHolder(final Component component, final Graphics2D g) {
		final Font font = g.getFont().deriveFont(g.getFont().getSize2D() * 1.2f);
		final int HEIGHT = component.getFontMetrics(font).getHeight() * 5; // 5 lines
		final int GAP = component.getFontMetrics(font).stringWidth("W");
		if (component.getHeight() < HEIGHT || component.getWidth() < HEIGHT) return;
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(getDisabledComponentColor());
		g.setStroke(new BasicStroke(1.0f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, GAP/2.5f, new float[] { GAP/2.5f },
				0.0f));
		final RoundRectangle2D.Double rect = new RoundRectangle2D.Double(GAP, component.getHeight() - GAP * 4 - HEIGHT,
				component.getWidth() - 1 - GAP * 2, HEIGHT, GAP * 2, GAP * 2);
		g.draw(rect);
		final String text = "Drag Files Here";
		final FontMetrics metrics = g.getFontMetrics(font);
		final double x = rect.getX() + (rect.getWidth() - metrics.stringWidth(text)) / 2;
		final double y = rect.getY() + ((rect.getHeight() - metrics.getHeight()) / 2) + metrics.getAscent();
		g.setFont(font);
		g.drawString(text, (int) x, (int) y);
	}

	public static JTabbedPane getTabbedPane() {
		final JTabbedPane tabbedPane = new JTabbedPane(JTabbedPane.TOP);
		tabbedPane.putClientProperty("JTabbedPane.tabRotation", "auto");
		tabbedPane.putClientProperty("JTabbedPane.tabWidthMode", "compact");
		tabbedPane.putClientProperty("JTabbedPane.tabAreaAlignment", "fill");
		tabbedPane.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
		final JPopupMenu popup = new JPopupMenu();
		tabbedPane.setComponentPopupMenu(popup);

		// layout
		addSeparator(popup, "Tab Layout:");
		ButtonGroup group = new ButtonGroup();
		for (final String option : new String[]{"Compact", "Relaxed"}) {
			final JCheckBoxMenuItem jcbmi = new JCheckBoxMenuItem(option, "Compact".equals(option));
			jcbmi.addItemListener(e -> {
				if ("Relaxed".equals(option)) {
                    tabbedPane.putClientProperty("JTabbedPane.tabIconPlacement", SwingConstants.TOP);
                } else {
                    tabbedPane.putClientProperty("JTabbedPane.tabIconPlacement", SwingConstants.LEFT);
                }
			});
			group.add(jcbmi);
			popup.add(jcbmi);
		}
		// Contrast
		addSeparator(popup, "Tab Style:");
		group = new ButtonGroup();
		for (final String option : new String[]{"card", "underlined"}) {
			final JCheckBoxMenuItem jcbmi = new JCheckBoxMenuItem(StringUtils.capitalize(option), "underlined".equals(option));
			jcbmi.addItemListener(e -> tabbedPane.putClientProperty("JTabbedPane.tabType", option));
			group.add(jcbmi);
			popup.add(jcbmi);
		}
		// Placement
		addSeparator(popup, "Tab Placement:");
		group = new ButtonGroup();
		for (final String pos : new String[]{"Top", "Left", "Bottom", "Right"}) {
			final JCheckBoxMenuItem jcbmi2 = new JCheckBoxMenuItem(pos, "Top".equals(pos));
			jcbmi2.addItemListener(e -> {
				switch (pos) {
					case "Left":
						tabbedPane.setTabPlacement(JTabbedPane.LEFT);
						tabbedPane.putClientProperty("JTabbedPane.tabWidthMode", "preferred");
						break;
					case "Bottom":
						tabbedPane.putClientProperty("JTabbedPane.tabWidthMode", "compact");
						tabbedPane.setTabPlacement(JTabbedPane.BOTTOM);
						break;
					case "Right":
						tabbedPane.setTabPlacement(JTabbedPane.RIGHT);
						tabbedPane.putClientProperty("JTabbedPane.tabWidthMode", "preferred");
						break;
					default:
						tabbedPane.putClientProperty("JTabbedPane.tabWidthMode", "compact");
						tabbedPane.setTabPlacement(JTabbedPane.TOP);
						break;
				}
			});
			group.add(jcbmi2);
			popup.add(jcbmi2);
		}
		return tabbedPane;
	}

	public static void centerWindow(final Window dialogToCenter, final Window... windows) {
		if (dialogToCenter == null) {
			throw new IllegalArgumentException("dialogToCenter must not be null");
		}
		if (windows == null || windows.length == 0) {
			AWTWindows.centerWindow(dialogToCenter);
			return;
		}
		// the bounding rectangle that encompasses all windows
		Rectangle boundingRect = new Rectangle(windows[0].getBounds());
		for (final Window w : windows) {
			if (w != null) boundingRect = boundingRect.union(w.getBounds());
		}
		AWTWindows.centerWindow(boundingRect, dialogToCenter);
	}

	/** Tweaked version of ij.gui.HTMLDialog that is aware of parent */
	private class HTMLDialog extends JDialog implements ActionListener, KeyListener, HyperlinkListener {

		private static final long serialVersionUID = 1L;
		private JEditorPane editorPane;

		public HTMLDialog(final String message, final String title, final boolean modal) {
			super();
			setModal(modal);
			setTitle(title);
			init(message);
		}

		@Override
		public void setVisible(final boolean b) {
			if (parent != null)
				setLocationRelativeTo(parent);
			else
				AWTWindows.centerWindow(this);
			super.setVisible(b);
		}

		private void init(String message) {
			getContentPane().setLayout(new BorderLayout());
			if (message == null)
				message = "";
			editorPane = new JEditorPane("text/html", "");
			editorPane.setEditable(false);
			final HTMLEditorKit kit = new HTMLEditorKit();
			editorPane.setEditorKit(kit);
			final StyleSheet styleSheet = kit.getStyleSheet();
			styleSheet.addRule("body{font-family:Verdana,sans-serif; font-size:11.5pt; margin:5px 10px 5px 10px;}"); // top
																														// right
																														// bottom
																														// left
			styleSheet.addRule("h1{font-size:18pt;}");
			styleSheet.addRule("h2{font-size:15pt;}");
			styleSheet.addRule("dl dt{font-face:bold;}");
			editorPane.setText(message); // display the html text with the above style
			editorPane.getActionMap().put("insert-break", new AbstractAction() {
				private static final long serialVersionUID = 1L;

				public void actionPerformed(final ActionEvent e) {
				}
			}); // suppress beep on <ENTER> key
			final JScrollPane scrollPane = getScrollPane(editorPane);
			getContentPane().add(scrollPane);
			final JButton button = new JButton("OK");
			button.addActionListener(this);
			button.addKeyListener(this);
			editorPane.addKeyListener(this);
			editorPane.addHyperlinkListener(this);
			editorPane.setCaretPosition(0); // scroll to top;
			final JPanel panel = new JPanel();
			panel.add(button);
			getContentPane().add(panel, "South");
			setForeground(Color.black);
			pack();
			final Dimension screenD = Toolkit.getDefaultToolkit().getScreenSize();
			final Dimension dialogD = getPreferredSize();
			final int maxWidth = (int) (Math.min(0.70 * screenD.width, 800)); // max 70% of screen width, but not more
																				// then 800 pxl
			if (maxWidth > 400 && dialogD.width > maxWidth)
				dialogD.width = maxWidth;
			if (dialogD.height > 0.80 * screenD.height && screenD.height > 400) // max 80% of screen height
				dialogD.height = (int) (0.80 * screenD.height);
			final int minHeight = editorPane.getFontMetrics(editorPane.getFont()).getHeight() * 10 + panel.getPreferredSize().height;
			if (dialogD.height < minHeight)
				dialogD.height = minHeight;
			setPreferredSize(dialogD);
			pack(); // Important! or preferred dimensions won't apply
		}

		public void actionPerformed(final ActionEvent e) {
			dispose();
		}

		public void keyPressed(final KeyEvent e) {
			final int keyCode = e.getKeyCode();
			if (keyCode == KeyEvent.VK_C) {
				if (editorPane.getSelectedText() == null || editorPane.getSelectedText().isEmpty())
					editorPane.selectAll();
				editorPane.copy();
				editorPane.select(0, 0);
			} else if (keyCode == KeyEvent.VK_ENTER || keyCode == KeyEvent.VK_W || keyCode == KeyEvent.VK_ESCAPE)
				dispose();
		}

		public void hyperlinkUpdate(final HyperlinkEvent e) {
			if (e.getEventType() == HyperlinkEvent.EventType.ACTIVATED) {
				final String url = e.getDescription(); // getURL does not work for relative links within document such
														// as "#top"
				if (url == null)
					return;
				if (url.startsWith("#"))
					editorPane.scrollToReference(url.substring(1));
				else {
					ij.IJ.runPlugIn("ij.plugin.BrowserLauncher", url);
				}
			}
		}

		@Override
		public void keyReleased(final KeyEvent arg0) {
			// DO nothing
		}

		@Override
		public void keyTyped(final KeyEvent arg0) {
			// DO nothing
		}

	}

	private class FloatingDialog extends JDialog implements ComponentListener,
		WindowListener
	{

		private static final long serialVersionUID = 1L;

		public FloatingDialog(final String msg) {
			super();
			setUndecorated(true);
			setModal(false);
			setResizable(false);
			setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
			setAlwaysOnTop(true);
			final JLabel label = getLabel(msg);
			label.setHorizontalAlignment(SwingConstants.CENTER);
			getRootPane().setBorder(new EmptyBorder(5, 5, 5, 5));
			applyRoundCorners(label);
			add(label);
			pack();
			centerOnParent();
			if (parent != null) parent.addComponentListener(this);
		}

		@Override
		public void dispose() {
			if (parent != null) parent.removeComponentListener(this);
			super.dispose();
		}

		private void centerOnParent() {
			if (parent == null) return;
			final Point p = new Point(parent.getWidth() / 2 - getWidth() / 2, parent
				.getHeight() / 2 - getHeight() / 2);
			setLocation(p.x + parent.getX(), p.y + parent.getY());
		}

		private void recenter() {
			assert SwingUtilities.isEventDispatchThread();
			// setVisible(false);
			centerOnParent();
			// setVisible(true);
		}

		@Override
		public void componentResized(final ComponentEvent e) {
			recenter();
		}

		@Override
		public void componentMoved(final ComponentEvent e) {
			recenter();
		}

		@Override
		public void componentShown(final ComponentEvent e) {
			setVisible(true);
			toFront();
		}

		@Override
		public void componentHidden(final ComponentEvent e) {
			setVisible(false);
		}

		@Override
		public void windowClosing(final WindowEvent e) {
			setVisible(false);
		}

		@Override
		public void windowIconified(final WindowEvent e) {
			setVisible(false);
		}

		@Override
		public void windowDeiconified(final WindowEvent e) {
			setVisible(true);
			toFront();
		}

		@Override
		public void windowOpened(final WindowEvent e) {
			// do nothing
		}

		@Override
		public void windowClosed(final WindowEvent e) {
			setVisible(false);
		}

		@Override
		public void windowActivated(final WindowEvent e) {
			// do nothing
		}

		@Override
		public void windowDeactivated(final WindowEvent e) {
			// do nothing
		}

	}

	public static class MenuItems {

		private MenuItems() {}

		public static JMenuItem combineCharts() {
			final JMenuItem jmi = new JMenuItem("Combine Charts Into Montage...", IconFactory.menuIcon(GLYPH.GRID));
			jmi.setToolTipText("Combines isolated SNT charts (plots, histograms, etc.) into a grid layout");
			return jmi;
		}

		public static JMenuItem persistenceAnalysis() {
			final JMenuItem jmi = new JMenuItem("Persistence Homology...", IconFactory.menuIcon(GLYPH.BARCODE));
			jmi.setToolTipText("TMD, TMD variants, and Persistence landscapes");
			return jmi;
		}

		public static JMenuItem brainAreaAnalysis() {
			final JMenuItem jmi = new JMenuItem("Brain Area Frequencies...", IconFactory.menuIcon(GLYPH.BRAIN));
			jmi.setToolTipText("Distribution analysis of projection patterns across brain areas");
			return jmi;
		}

		public static JMenuItem devResourceMain() {
			final JMenuItem jmi = openURL("Scripting Documentation", "https://imagej.net/plugins/snt/scripting");
			jmi.setIcon(IconFactory.menuIcon(GLYPH.CODE));
			return jmi;
		}

		public static JMenuItem devResourceJavaAPI() {
			final JMenuItem jmi = openURL("Java API", "https://javadoc.scijava.org/SNT/");
			jmi.setIcon(IconFactory.menuIcon(GLYPH.CODE2));
			return jmi;
		}

		public static JMenuItem devResourcePythonAPI() {
			final JMenuItem jmi = openURL("Python API (PySNT)", "https://pysnt.readthedocs.io/en/latest/");
            jmi.setIcon(IconFactory.menuIcon(GLYPH.CODE2));
			return jmi;
		}

		public static JMenuItem convexHull() {
			final JMenuItem jmi = new JMenuItem("Convex Hull...", IconFactory.menuIcon(GLYPH.DICE_20));
			jmi.setToolTipText(
					"2D/3D convex hull measurement(s).\nMetrics for estimation of dendritic or pre-synaptic fields");
			return jmi;
		}

		public static JMenuItem createDendrogram() {
			final JMenuItem jmi = new JMenuItem("Create Dendrogram", IconFactory.menuIcon(GLYPH.DIAGRAM));
			jmi.setToolTipText("Display reconstructions in Graph Viewer");
			return jmi;
		}

		public static JMenuItem createAnnotationGraph() {
			final JMenuItem jmi = new JMenuItem("Annotation Graph...", IconFactory.menuIcon(GLYPH.BRAIN));
			jmi.setToolTipText("Flow Plots and Ferris-Wheel diagrams from annotated trees");
			return jmi;
		}

		public static JMenuItem measureOptions() {
			final JMenuItem jmi = new JMenuItem("Measure...", IconFactory.menuIcon(GLYPH.TABLE));
			jmi.setToolTipText("Compute detailed metrics & statistics from single cells");
			return jmi;
		}

		public static JMenuItem measureQuick() {
			final JMenuItem jmi = new JMenuItem("Quick Measurements", IconFactory.menuIcon(GLYPH.ROCKET));
			jmi.setToolTipText("Run simplified \"Measure...\" calls using commonly used metrics");
			return jmi;
		}

		public static JMenuItem renderQuick() {
			final JMenuItem jmi = new JMenuItem("Create Figure...", IconFactory.menuIcon(IconFactory.GLYPH.PERSON_CHALKBOARD));
			jmi.setToolTipText("Creates multi-panel figures and illustrations of selected reconstructions");
			return jmi;
		}

		public static JMenuItem rootAngleAnalysis() {
			final JMenuItem jmi = new JMenuItem("Root Angle Analysis...", IconFactory.menuIcon(GLYPH.ANGLE_DOWN));
			jmi.setToolTipText("Root angle, mean direction, and centripetal bias analyses");
			return jmi;
		}

		public static JMenuItem saveTablesAndPlots(final GLYPH glyph) {
			final JMenuItem jmi = new JMenuItem("Save Tables & Analysis Plots...", IconFactory.menuIcon(glyph));
			jmi.setToolTipText("Save all tables, plots, and charts currently open");
			return jmi;
		}

		public static JMenuItem shollAnalysis() {
			final JMenuItem jmi = new JMenuItem("Sholl Analysis...", IconFactory.menuIcon(GLYPH.BULLSEYE));
			jmi.setToolTipText("Sholl analysis using pre-defined focal points");
			return jmi;
		}

		public static JMenuItem strahlerAnalysis() {
			final JMenuItem jmi = new JMenuItem("Strahler Analysis...", IconFactory.menuIcon(GLYPH.BRANCH_CODE));
			jmi.setToolTipText("Horton–Strahler measures of branching complexity");
			return jmi;
		}

		public static JMenuItem fromOpenImage() {
			return new JMenuItem("From Open Image...", IconFactory.menuIcon(GLYPH.IMAGE));
		}

		public static JMenuItem fromFileImage() {
			return new JMenuItem("From File...", IconFactory.menuIcon(GLYPH.FILE_IMAGE));
		}

		public static int defaultHeight() {
			Font font = UIManager.getDefaults().getFont("CheckBoxMenuItem.font");
			if (font == null) font = new Font(Font.SANS_SERIF, Font.PLAIN, 12);
			final Canvas c = new Canvas();
			return c.getFontMetrics(font).getHeight();
		}

		public static void contrastOptions(final JComponent menuOrPopupMenu, final JComponent component, final boolean includeSeparator) {
			if (includeSeparator)
				menuOrPopupMenu.add(GuiUtils.leftAlignedLabel(" Contrast Options:", false));
			final ButtonGroup bGroup = new ButtonGroup();
			final List<String> options = List.of("None", "Lighter", "Darker");
			options.forEach( option -> {
				final JRadioButtonMenuItem rbmi = new JRadioButtonMenuItem(option, "None".equals(option));
				bGroup.add(rbmi);
				menuOrPopupMenu.add(rbmi);
				rbmi.addActionListener(e -> {
					switch (option) {
						case "Lighter":
							component.putClientProperty(FlatClientProperties.STYLE, "background: lighten($Panel.background,5%)");
							break;
						case "Darker":
							component.putClientProperty(FlatClientProperties.STYLE, "background: darken($Panel.background,5%)");
							break;
						default:
							component.putClientProperty(FlatClientProperties.STYLE, null);
							break;
					}
				});
			});
		}

		public static JMenuItem itemWithoutAccelerator() {
			class JMenuItemAcc extends JMenuItem {
				// https://stackoverflow.com/a/1719250
				private static final long serialVersionUID = 1L;

				@Override
				public void setAccelerator(final KeyStroke keyStroke) {
					super.setAccelerator(keyStroke);
					getInputMap(WHEN_IN_FOCUSED_WINDOW).put(keyStroke, "none");
				}
			}
			return new JMenuItemAcc();
		}

		public static JMenu helpMenu(final SNTCommandFinder cmdFinder) {
			final JMenu helpMenu = new JMenu("Help");
			if (cmdFinder != null) {
				final AbstractButton mi = cmdFinder.getMenuItem(false);
				mi.setText("Find Command/Action...");
				mi.getAction().putValue(Action.ACCELERATOR_KEY, null);
				mi.addActionListener(e -> new GuiUtils().centeredMsg("You can search for commands "
                        + "and actions from the Command Palette, by using the global shortcut "
						+ cmdFinder.getAcceleratorString() + ".", "Tip: Shortcut to Remember"));
				helpMenu.add(mi);
				helpMenu.addSeparator();
			}
			final String URL = "https://imagej.net/plugins/snt/";
			JMenuItem mi = openURL("Main Documentation Page", URL);
			mi.setIcon(IconFactory.menuIcon(GLYPH.HOME));
			helpMenu.add(mi);
			mi = openURL("User Manual", URL + "manual");
			mi.setIcon(IconFactory.menuIcon(GLYPH.BOOK_READER));
			helpMenu.add(mi);
			mi = openURL("Screencasts", URL + "screencasts");
			mi.setIcon(IconFactory.menuIcon(GLYPH.VIDEO));
			helpMenu.add(mi);
			mi = openURL("Walkthroughs", URL + "walkthroughs");
			mi.setIcon(IconFactory.menuIcon(GLYPH.FOOTPRINTS));
			helpMenu.add(mi);

			helpMenu.addSeparator();
			mi = openURL("Analysis", URL + "analysis");
			mi.setIcon(IconFactory.menuIcon(GLYPH.CHART));
			helpMenu.add(mi);
			mi = openURL("Reconstruction Viewer", URL + "reconstruction-viewer");
			mi.setIcon(IconFactory.menuIcon(GLYPH.CUBE));
			helpMenu.add(mi);

			helpMenu.addSeparator();
			mi = openURL("List of Shortcuts", URL + "key-shortcuts");
			mi.setIcon(IconFactory.menuIcon(GLYPH.KEYBOARD));
			helpMenu.add(mi);
			helpMenu.addSeparator();

			mi = openURL("Ask a Question", "https://forum.image.sc/tag/snt");
			mi.setIcon(IconFactory.menuIcon(GLYPH.COMMENTS));
			helpMenu.add(mi);
			mi = openURL("FAQs", URL + "faq");
			mi.setIcon(IconFactory.menuIcon(GLYPH.QUESTION));
			helpMenu.add(mi);
			mi = openURL("Known Issues", "https://github.com/morphonets/SNT/issues");
			mi.setIcon(IconFactory.menuIcon(GLYPH.BUG));
			helpMenu.add(mi);
			mi = openURL("Release Notes", releaseNotesURL());
			mi.setIcon(IconFactory.menuIcon(GLYPH.NEWSPAPER));
			helpMenu.add(mi);

			helpMenu.addSeparator();
			helpMenu.add(devResourceJavaAPI());
			helpMenu.add(devResourcePythonAPI());
            helpMenu.add(devResourceMain());
            helpMenu.addSeparator();

			mi = openURL("Complementary Tools",  URL + "comp-tools");
			mi.setIcon(IconFactory.menuIcon(GLYPH.COGS));
			helpMenu.add(mi);
			mi = openURL("Implemented Algorithms", "https://github.com/morphonets/SNT/blob/master/NOTES.md#algorithms");
			mi.setIcon(IconFactory.menuIcon(GLYPH.MATH));
			helpMenu.add(mi);
			mi = openURL("SNT Manuscript", "http://dx.doi.org/10.1038/s41592-021-01105-7");
			mi.setIcon(IconFactory.menuIcon(GLYPH.FILE));
			helpMenu.add(mi);
			helpMenu.addSeparator();

			final JMenuItem about = new JMenuItem("About...");
			about.setIcon(IconFactory.menuIcon(GLYPH.INFO));
			about.addActionListener(e -> showAboutDialog());
			helpMenu.add(about);

			return helpMenu;
		}

		public static JMenuItem openURL(final String label, final String URL) {
			final JMenuItem mi = new JMenuItem(label);
			mi.addActionListener(e -> ij.IJ.runPlugIn("ij.plugin.BrowserLauncher", URL));
			return mi;
		}

		public static JMenuItem openHelpURL(final String label, final String URL) {
			final JMenuItem mi = openURL(label, URL);
			mi.setIcon(IconFactory.menuIcon(GLYPH.QUESTION));
			return mi;
		}

		private static String releaseNotesURL() {
			return "https://github.com/morphonets/SNT/releases";
		}
	}

	public static class JTrees {

		private JTrees() {}

		public static void collapseAllNodes(final javax.swing.JTree tree) {
			final int row1 = (tree.isRootVisible()) ? 1 : 0;
			for (int i = row1; i < tree.getRowCount(); i++)
				tree.collapseRow(i);
		}

		public static void expandAllNodes(final javax.swing.JTree tree) {
			for (int i = 0; i < tree.getRowCount(); i++) tree.expandRow(i);
		}

		public static void collapseNodesOfSameLevel(final javax.swing.JTree tree, final TreePath path) {
			final DefaultMutableTreeNode root = (DefaultMutableTreeNode)tree.getModel().getRoot();
			final DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
			collapseNodesByLevel(tree, new TreePath(root), node.getLevel());
		}

		public static void expandNodesOfSameLevel(final javax.swing.JTree tree, final TreePath path) {
			final DefaultMutableTreeNode root = (DefaultMutableTreeNode)tree.getModel().getRoot();
			final DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
			expandNodesByLevel(tree, new TreePath(root), node.getLevel());
		}

		private static void collapseNodesByLevel(final JTree tree, final TreePath path, int level) {
			final DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
			if (node.getLevel() == level) {
				tree.collapsePath(path);
			}
			if (node.getChildCount() >= 0) {
				final Enumeration<? extends TreeNode> e = node.children();
				while (e.hasMoreElements()) {
					final DefaultMutableTreeNode childNode = (DefaultMutableTreeNode) e.nextElement();
					collapseNodesByLevel(tree, path.pathByAddingChild(childNode), level);
				}
			}
		}

		private static void expandNodesByLevel(final JTree tree, final TreePath path, int level) {
			final DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
			if (node.getDepth() == level) {
				tree.expandPath(path);
			}
			if (node.getChildCount() >= 0) {
				final Enumeration<? extends TreeNode> e = node.children();
				while (e.hasMoreElements()) {
					final DefaultMutableTreeNode childNode = (DefaultMutableTreeNode) e.nextElement();
					expandNodesByLevel(tree, path.pathByAddingChild(childNode), level);
				}
			}
		}

	}

	public static class Buttons {

		private Buttons() {}

        public static OptionsButton OptionsButton(final IconFactory.GLYPH glyph, final float scalingFactor, final JPopupMenu menu) {
            return new OptionsButton(IconFactory.dropdownMenuIcon(glyph, scalingFactor), menu);
        }

        public static class OptionsButton extends JButton {
            public final JPopupMenu popupMenu;

            private OptionsButton(final Icon icon, final JPopupMenu popupMenu) {
                super(icon);
                this.popupMenu = popupMenu;
                addActionListener(e -> popupMenu.show(this, this.getWidth() / 2, this.getHeight() / 2));
            }
        }

		public static JButton help(final String url) {
			final JButton button = new JButton();
			button.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_HELP);
			if (url != null) button.addActionListener(e -> GuiUtils.openURL(url));
			return button;
		}

		public static JButton undo() {
			return undo(null);
		}

        public static JButton undo(final Action action) {
            final JButton button = (action == null) ? new JButton() : new JButton(action);
            makeSmallBorderless(button, GLYPH.UNDO, UIManager.getColor("Spinner.buttonArrowColor"));
            return button;
        }

		public static JButton show(final Color color) {
			final JButton button = new JButton();
			makeSmallBorderless(button, GLYPH.EYE, color);
			return button;
		}

		public static JButton delete() {
			final JButton button = new JButton();
			makeSmallBorderless(button, GLYPH.TRASH, UIManager.getColor("Spinner.buttonArrowColor"));
			return button;
		}

		public static JToggleButton edit() {
			final JToggleButton button = new JToggleButton();
			makeSmallBorderless(button, GLYPH.PEN, UIManager.getColor("Spinner.buttonArrowColor"));
			return button;
		}

		public static JButton options() {
			return new JButton(IconFactory.dropdownMenuIcon(GLYPH.OPTIONS, 1f));
		}

		private static void makeSmallBorderless(final AbstractButton b, final IconFactory.GLYPH glyph, final Color color) {
			IconFactory.assignIcon(b, glyph, color, .8f);
			b.setFocusable(false);
			makeBorderless(b);
		}

		public static void makeBorderless(final AbstractButton... buttons) {
			for (final AbstractButton button: buttons) {
				if (button != null) button.putClientProperty("JButton.buttonType", "borderless");
			}
		}

		public static JButton smallButton(final String text) {
			final float SCALE = .85f;
			final JButton button = new JButton(text);
			final Font font = button.getFont();
			button.setFont(font.deriveFont(font.getSize() * SCALE));
			final Insets insets = button.getMargin();
			button.setMargin(new Insets((int) (insets.top * SCALE), (int) (insets.left *
					SCALE), (int) (insets.bottom * SCALE), (int) (insets.right * SCALE)));
			return button;
		}

		public static JButton toolbarButton(final String badgeName) {
			final JButton button = new JButton(badgeName);
			button.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON);
			button.setFont(button.getFont().deriveFont(button.getFont().getSize2D()*.85f));
			return button;
		}

        public static JButton toolbarButton(final Action action, final String tooltipText) {
            final JButton button = new JButton(action);
            button.setText(null);
            button.setToolTipText(tooltipText);
            button.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON);
            return button;
        }

        public static JToggleButton toolbarToggleButton(final Action action, final String tooltipText,
                                                        final IconFactory.GLYPH glyph1, final IconFactory.GLYPH glyph2) {
            final JToggleButton button = new JToggleButton(action);
            IconFactory.assignIcon(button, glyph1, glyph2);
            button.setText(null);
            button.setToolTipText(tooltipText);
            button.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON);
            return button;
        }

		public static JButton toolbarButton(final IconFactory.GLYPH glyph, final Color color, final float scalingFactor) {
			final JButton button = toolbarButton("");
			IconFactory.assignIcon(button, glyph, color, scalingFactor);
			return button;
		}

		public static AbstractButton get(final Container parent, final String label) {
			final Stack<Component> stack = new Stack<>();
			stack.push(parent);
			while (!stack.isEmpty()) {
				final Component current = stack.pop();
				if (current instanceof AbstractButton && label.equals(((AbstractButton) current).getText())) {
					return (AbstractButton) current;
				}
				else if (current instanceof Container) {
					stack.addAll(Arrays.asList(((Container) current).getComponents()));
				}
			}
			return null;
		}
	}
}
