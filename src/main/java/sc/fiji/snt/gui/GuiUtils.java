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

import java.awt.BorderLayout;
import java.awt.Canvas;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.ComponentEvent;
import java.awt.event.ComponentListener;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowEvent;
import java.awt.event.WindowListener;
import java.awt.geom.Rectangle2D;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.Stack;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.swing.AbstractAction;
import javax.swing.AbstractButton;
import javax.swing.Action;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.DefaultListCellRenderer;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JColorChooser;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JEditorPane;
import javax.swing.JFileChooser;
import javax.swing.JFormattedTextField;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSpinner.DefaultEditor;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JTextPane;
import javax.swing.JTree;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.LookAndFeel;
import javax.swing.MenuElement;
import javax.swing.SpinnerModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.WindowConstants;
import javax.swing.border.Border;
import javax.swing.border.EmptyBorder;
import javax.swing.colorchooser.AbstractColorChooserPanel;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.HyperlinkEvent;
import javax.swing.event.HyperlinkListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.text.NumberFormatter;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.StyleSheet;

import org.scijava.ui.DialogPrompt.Result;
import org.scijava.ui.awt.AWTWindows;
import org.scijava.ui.swing.SwingDialog;
import org.scijava.ui.swing.widget.SwingColorWidget;
import org.scijava.util.ColorRGB;
import org.scijava.util.PlatformUtils;
import org.scijava.util.Types;

import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.FlatDarculaLaf;
import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatIntelliJLaf;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatLightLaf;
import com.formdev.flatlaf.icons.FlatClearIcon;
import com.jidesoft.plaf.LookAndFeelFactory;
import com.jidesoft.popup.JidePopup;
import com.jidesoft.utils.ProductNames;

import ij.IJ;
import ij.ImageJ;
import sc.fiji.snt.SNTPrefs;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.analysis.SNTChart;
import sc.fiji.snt.gui.IconFactory.GLYPH;
import sc.fiji.snt.util.SNTColor;

/** Misc. utilities for SNT's GUI. */
public class GuiUtils {

	public static final String LAF_LIGHT = FlatLightLaf.NAME;
	public static final String LAF_LIGHT_INTJ = FlatIntelliJLaf.NAME;
	public static final String LAF_DARK = FlatDarkLaf.NAME;
	public static final String LAF_DARCULA = FlatDarculaLaf.NAME;
	public static final String LAF_DEFAULT  = "Default";

	/** The default sorting weight for the Plugins>Neuroanatomy> submenu */
	// define it here in case we need to change sorting priority again later on
	public static final double DEFAULT_MENU_WEIGHT = org.scijava.MenuEntry.DEFAULT_WEIGHT;

	private static SplashScreen splashScreen;
	private static LookAndFeel existingLaf;
	private Component parent;
	private JidePopup popup;
	private boolean popupExceptionTriggered;
	private int timeOut = 2500;

	public GuiUtils(final Component parent) {
		setParent(parent);
	}

	public GuiUtils() {
		this(null);
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
				final StringBuilder sb = new StringBuilder("<HTML>");
				sb.append("&nbsp;<b>SNT was updated!</b> Click here to browse release notes.");
				if (SNTUtils.VERSION.startsWith("4.2.")) {
					sb.append("<br>&nbsp;<b>This is the last version supporting java 8!</b>");
					sb.append("<br>&nbsp;To run newer SNT versions you will need a Fiji release supporting at least Java 11.");
				}
				showNotification(leftAlignedLabel(sb.toString(), releaseNotesURL(), true), true);
			}
		});
		timer.setRepeats(false);
		timer.start();
	}

	public void showNotification(final String msg, final Number msDelay) {
		String parsedMsg;
		if (!msg.startsWith("<HTML>")) {
			final String[] lines = msg.split("\n");
			parsedMsg = "<HTML><b>"+ lines[0] + "</b>";
			for (int i = 1; i < lines.length; i++) {
				parsedMsg += "<br>" + lines[i];
			}
		}
		else
			parsedMsg = msg;
		showNotification(new JLabel(parsedMsg), msDelay.intValue());
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
		SwingUtilities.invokeLater(() -> popup
				.showPopup((parent == null) ? SwingConstants.NORTH_EAST : SwingConstants.SOUTH_EAST, parent));
	}

	private static void applyRoundCorners(final JComponent component) {
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
		final HTMLDialog dialog = new GuiUtils().new HTMLDialog(msg, title, false);
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
		popup.setTransient(timeOut > 0);
		popup.setMovable(false);
		popup.setDefaultMoveOperation(JidePopup.HIDE_ON_MOVED);
		popup.setEnsureInOneScreen(true);
		popup.setTimeout(timeOut);
		return popup;
	}

	public void setTmpMsgTimeOut(final int mseconds) { // 0: no timeout, always visible
		timeOut = mseconds;
	}

	public int yesNoDialog(final String msg, final String title, final String yesButtonLabel, final String noButtonLabel) {
		return yesNoDialog(new Object[] { getLabel(msg) }, title, new String[] {yesButtonLabel, noButtonLabel});
	}

	public int yesNoDialog(final String msg, final String title) {
		return yesNoDialog(new Object[] { getLabel(msg) }, title, null);
	}

	public Result yesNoPrompt(final String message, final String title) {
		final int result = yesNoDialog(message, (title == null) ? SNTUtils.getReadableVersion() : title);
		switch (result) {
		case JOptionPane.YES_OPTION:
			return Result.YES_OPTION;
		case JOptionPane.NO_OPTION:
			return Result.NO_OPTION;
		case JOptionPane.CANCEL_OPTION:
			return Result.CANCEL_OPTION;
		default:
			return Result.CLOSED_OPTION;
		}
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
		// work around a bug in openjdk and MacOS in which prompts
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

	public String[] getChoiceWithInput(final String message, final String title, final String[] choices,
			final String defaultChoice, final String defaultInput) {
		final JComboBox<String> combo = new JComboBox<>(choices);
		combo.setSelectedItem(defaultChoice);
		final JTextField input = new JTextField(defaultInput);
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
		return result;
	}

	public String getChoice(final String message, final String title, final String[] choices,
			final String[] descriptions, final String defaultChoice) {
		final JTextArea ta = new JTextArea();
		final JScrollPane sp = getScrollPane(ta);
		ta.setRows(5);
		ta.setWrapStyleWord(true);
		ta.setLineWrap(true);
		ta.setEditable(false);
		ta.setFocusable(true); // allow text to be copied
		int defIdx = Arrays.asList(choices).indexOf(defaultChoice);
		if (defIdx < 0)
			defIdx = 0;
		final JList<String> list = getJList(choices, defaultChoice);
		if (choices.length < 11)
			list.setVisibleRowCount(choices.length);
		list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		list.addListSelectionListener(e -> {
			ta.setText(descriptions[list.getSelectedIndex()]);
			ta.setCaretPosition(0);
		});
		list.setSelectedIndex(defIdx);
		list.setCellRenderer(new DefaultListCellRenderer() {

			private static final long serialVersionUID = -810009340376431810L;

			@Override
			public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected,
					boolean cellHasFocus) {
				final Component c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
				if (isSelected)
					c.setFont(c.getFont().deriveFont(Font.BOLD));
				return c;

			}
		} );

		ta.setText(descriptions[defIdx]);
		ta.setCaretPosition(0);
		ta.setBackground(list.getBackground());
		final JPanel panel = new JPanel(new BorderLayout());
		panel.add(getLabel(message), BorderLayout.NORTH);
		panel.add(getScrollPane(list), BorderLayout.CENTER);
		final JPanel bottomPanel =  new JPanel(new BorderLayout());
		bottomPanel.add(new JLabel(" "), BorderLayout.NORTH); // spacer
		bottomPanel.add(sp, BorderLayout.CENTER);
		panel.add(bottomPanel, BorderLayout.SOUTH);
		final int promptResult = JOptionPane.showOptionDialog(parent, panel, title, JOptionPane.OK_CANCEL_OPTION,
				(choices.length < 6) ? JOptionPane.QUESTION_MESSAGE : JOptionPane.PLAIN_MESSAGE, null,
				new String[] { "OK", "Cancel" }, list);
		if (promptResult == JOptionPane.OK_OPTION || promptResult == 0)
			return list.getSelectedValue();
		return null;
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

	public String[] getStrings(final String message, final String promptTitle, final Map<String, List<String>> choicesMap,
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

	public Color getColor(final String title, final Color defaultValue) {
		return SwingColorWidget.showColorDialog(parent, title, defaultValue);
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
	public Color getColor(final String title, final Color defaultValue,
		final String... panes)
	{

		assert SwingUtilities.isEventDispatchThread();

		final JColorChooser chooser = new JColorChooser(defaultValue != null
			? defaultValue : Color.WHITE);

		// remove preview pane
		chooser.setPreviewPanel(new JPanel());

		// remove spurious panes
		List<String> allowedPanels;
		if (panes != null) {
			allowedPanels = Arrays.asList(panes);
			for (final AbstractColorChooserPanel accp : chooser.getChooserPanels()) {
				if (!allowedPanels.contains(accp.getDisplayName()) && chooser
					.getChooserPanels().length > 1) chooser.removeChooserPanel(accp);
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

		final ColorTracker ok = new ColorTracker(chooser);
		final JDialog dialog = JColorChooser.createDialog(parent, title, true,
			chooser, ok, null);
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

	public float[] getRange(final String promptMsg, final String promptTitle, final float[] defaultRange) {
		final String s = getString(promptMsg, promptTitle, SNTUtils.formatDouble(defaultRange[0], 3) + "-"
				+ SNTUtils.formatDouble(defaultRange[1], 3));
		if (s == null)
			return null; // user pressed cancel
		final float[] values = new float[2];
		try {
			// see https://stackoverflow.com/a/51283413
			final String regex = "([-+]?\\d*\\.?\\d*)\\s*-\\s*([-+]?\\d*\\.?\\d*)";
			final Pattern pattern = Pattern.compile(regex);
			final Matcher matcher = pattern.matcher(s);
			matcher.find();
			values[0] = Float.parseFloat(matcher.group(1));
			values[1] = Float.parseFloat(matcher.group(2));
			return values;
		} catch (final Exception ignored) {
			values[0] = Float.NaN;
			values[1] = Float.NaN;
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
		SNTPrefs.setLastknownDir(chosenFile); // null allowed
		if (focused)
			((Window) parent).toFront();
		return chosenFile;
	}

	public File[] getReconstructionFiles() {
		final JFileChooser fileChooser = getReconstructionFileChooser(null);
		return (File[])getOpenFileChooserResult(fileChooser);
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
		FileNameExtensionFilter filter;
		if ("swc".equals(extension))
			filter = new FileNameExtensionFilter("SWC files (.swc)", "swc");
		else if ("traces".equals(extension))
			filter = new FileNameExtensionFilter("SNT TRACES files (.traces)", "traces");
		else if ("json".equals(extension))
			filter = new FileNameExtensionFilter("JSON files (.json)", "json");
		else if ("ndf".equals(extension))
			filter = new FileNameExtensionFilter("NeuronJ NDF files (.ndf)", "ndf");
		else if ("labels".equals(extension))
			filter = new FileNameExtensionFilter("AmiraMesh labels (.labels)", "labels");
		else
			filter = null;
		final JFileChooser fileChooser = getReconstructionFileChooser(filter);
		fileChooser.setSelectedFile(file);
		if (extension == null)
			fileChooser.setDialogTitle("Choose Reconstruction File");
		else if ("labels".equals(extension))
			fileChooser.setDialogTitle("Choose LABELS File");
		else
			fileChooser.setDialogTitle("Choose Reconstrution (" + extension.toUpperCase() + ") File");
		fileChooser.setMultiSelectionEnabled(false);
		return (File)getOpenFileChooserResult(fileChooser);
	}

	private JFileChooser getReconstructionFileChooser(final FileNameExtensionFilter filter) {
		final JFileChooser fileChooser = GuiUtils.getDnDFileChooser();
		fileChooser.setDialogTitle("Choose Reconstruction File(s)");
		fileChooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
		fileChooser.setDialogType(JFileChooser.OPEN_DIALOG);
		if (filter == null) {
			fileChooser.addChoosableFileFilter(new FileNameExtensionFilter(
					"Reconstruction files (.traces, .swc, .json, .ndf)", "traces", "swc", "json", "ndf"));
		} else {
			fileChooser.addChoosableFileFilter(filter);
		}
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
				SNTPrefs.setLastknownDir(result[0]);
				return result;
			} else {
				final File result = fileChooser.getSelectedFile();
				SNTPrefs.setLastknownDir(result);
				return result;
			}
		}
		if (focused) ((Window) parent).toFront();
		return null;
	}

	private JFileChooser fileChooser(final String title, final File file, final int type, final int selectionMode) {
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

	public boolean[] getOptions(final String msg, final String[] options,
		final boolean[] defaults, final String title)
	{
		final JPanel panel = new JPanel(new GridLayout(options.length, 1));
		final JCheckBox[] checkboxes = new JCheckBox[options.length];
		for (int i = 0; i < options.length; i++) {
			panel.add(checkboxes[i] = new JCheckBox(options[i], defaults[i]));
		}
		final int result = JOptionPane.showConfirmDialog(parent, new Object[] { msg,
			panel }, title, JOptionPane.OK_CANCEL_OPTION);
		if (result == JOptionPane.CANCEL_OPTION) return null;
		final boolean[] answers = new boolean[options.length];
		for (int i = 0; i < options.length; i++) {
			answers[i] = checkboxes[i].isSelected();
		}
		return answers;
	}

	private int centeredDialog(final String msg, final String title,
		final int type)
	{
		/* if SwingDialogs could be centered, we could simply use */
		// final SwingDialog d = new SwingDialog(getLabel(msg), type, false);
		// if (parent != null) d.setParent(parent);
		// return d.show();
		final JOptionPane optionPane = new JOptionPane(getLabel(msg), type,
			JOptionPane.DEFAULT_OPTION);
		final JDialog d = optionPane.createDialog(title);
		if (parent != null) {
			AWTWindows.centerWindow(parent.getBounds(), d);
			// we could also use d.setLocationRelativeTo(parent);
		}
		makeVisible(d, true);
		final Object result = optionPane.getValue();
		if ((!(result instanceof Integer)))
			return SwingDialog.UNKNOWN_OPTION;
		return (Integer) result;
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
				if (cc instanceof JComponent && !(cc instanceof JButton))
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
		final int width = c.getFontMetrics(c.getFont()).stringWidth(text);
		final int max = (parent == null) ? 500 : parent.getWidth();
		return "<html><body><div style='width:" + Math.min(width, max) + ";'>" +
			text;
	}

	public void blinkingError(final JComponent blinkingComponent,
		final String msg)
	{
		final Color prevColor = blinkingComponent.getForeground();
		final Color flashColor = Color.RED;
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
			JOptionPane.PLAIN_MESSAGE) > Integer.MIN_VALUE)
		{ // Dialog
			// dismissed
			blinkTimer.stop();
		}
		blinkingComponent.setForeground(prevColor);
	}

	public static JDialog showAboutDialog() {
		final JPanel main = new JPanel();
		main.add(SplashScreen.getIconAsLabel());
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
		final JLabel ijDetails = leftAlignedLabel(
				"ImageJ " + ImageJ.VERSION + ImageJ.BUILD + "  |  Java " + System.getProperty("java.version"), "", true);
		ijDetails.setAlignmentX(JLabel.CENTER_ALIGNMENT);
		ijDetails.setToolTipText("Displays detailed System Information");
		ijDetails.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseClicked(final MouseEvent e) {
				if (IJ.getInstance() == null)
					new ij.plugin.JavaProperties().run("");
				else {
					//IJ.doCommand("ImageJ Properties");
					IJ.doCommand("System Information");
				}
			}

		});
		side.add(ijDetails);
		side.add(new JLabel(" ")); // spacer
		final JPanel urls = new JPanel();
		side.add(urls);
		JLabel url = leftAlignedLabel("Release Notes   ", releaseNotesURL(), true);
		urls.add(url);
		url = leftAlignedLabel("Documentation   ", "https://imagej.net/plugins/snt/", true);
		urls.add(url);
		url = leftAlignedLabel("Forum   ", "https://forum.image.sc/tag/snt", true);
		urls.add(url);
		url = leftAlignedLabel("GitHub   ", "https://github.com/morphonets/SNT/", true);
		urls.add(url);
		url = leftAlignedLabel("Manuscript", "http://dx.doi.org/10.1038/s41592-021-01105-7", true);
		urls.add(url);
		final JOptionPane optionPane = new JOptionPane(main, JOptionPane.PLAIN_MESSAGE, JOptionPane.DEFAULT_OPTION);
		final JDialog d = optionPane.createDialog("About SNT...");
		d.setLocationRelativeTo(null);
		d.setVisible(true);
		d.toFront();
		d.setAlwaysOnTop(!d.hasFocus()); // see makeVisible()
		return d;
	}

	public void showDirectory(final File file) {
		new FileChooser().reveal(file);
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
		// under FlatFlaf UI elements scale on HiDPI screens
		return new JLabel(" ").getFont().getSize2D();
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
		if (splashScreen != null) splashScreen.close();
		splashScreen = null;
	}

	public static void collapseAllTreeNodes(final JTree tree) {
		final int row1 = (tree.isRootVisible()) ? 1 : 0;
		for (int i = row1; i < tree.getRowCount(); i++)
			tree.collapseRow(i);
	}

	public static void expandAllTreeNodes(final JTree tree) {
		for (int i = 0; i < tree.getRowCount(); i++)
			tree.expandRow(i);
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
		final Font font = label.getFont();
		label.setFont(font.deriveFont((float) (font.getSize() * .85)));
		if (vgap) c.insets.top = component.getFontMetrics(font).getHeight();
		component.add(label, c);
		if (vgap) c.insets.top = previousTopGap;
	}

	public static void addSeparator(final JPopupMenu menu, final String header) {
		final JLabel label = leftAlignedLabel(header, false);
		if (menu.getComponentCount() > 1) menu.addSeparator();
		menu.add(label);
	}

	public static void addSeparator(final JMenu menu, final String header) {
		final JLabel label = leftAlignedLabel(header, false);
		if (menu.getComponentCount() > 1) menu.addSeparator();
		menu.add(label);
	}

	public static JButton menubarButton(final IconFactory.GLYPH glyphIcon, final Action action) {
		return new JButton(action) {
			private static final long serialVersionUID = 406126659895081426L;

			@Override
			public Dimension getMaximumSize() {
				final Dimension d1 = super.getMaximumSize();
				final Dimension d2 = super.getPreferredSize();
				d1.width = d2.width;
				return d1;
			}
			@Override
			public Icon getIcon() {
				return IconFactory.getMenuIcon(glyphIcon);
			}
			@Override
			public String getText() {
				return null;
			}
			@Override
			public Border getBorder() {
				return null;
			}
			@Override
			public boolean isBorderPainted() {
				return false;
			}
			@Override
			public boolean isBackgroundSet() {
				return false;
			}
			@Override
			public Color getBackground() {
				return null;
			}
			@Override
			public boolean isOpaque() {
				return false;
			}
			@Override
			public boolean isContentAreaFilled() {
				return false;
			}

		};
	}

	public static void equalize(final AbstractButton b1, final AbstractButton b2) {
		if (b1.getWidth() > b2.getWidth() || b1.getHeight() > b2.getHeight()) {
			b2.setSize(b1.getSize());
			b2.setMinimumSize(b1.getMinimumSize());
			b2.setPreferredSize(b1.getPreferredSize());
			b2.setMaximumSize(b1.getMaximumSize());
		}
		else if (b1.getWidth() < b2.getWidth() || b1.getHeight() < b2.getHeight()) {
			b1.setSize(b2.getSize());
			b1.setMinimumSize(b2.getMinimumSize());
			b1.setPreferredSize(b2.getPreferredSize());
			b1.setMaximumSize(b2.getMaximumSize());
		}
	}

	public static void equalizeHeight(final AbstractButton b1, final AbstractButton b2) {
		b1.setSize(new Dimension(b1.getSize().width, b2.getSize().height));
		b1.setMinimumSize(new Dimension(b1.getMinimumSize().width, b2.getMinimumSize().height));
		b1.setPreferredSize(new Dimension(b1.getPreferredSize().width, b2.getPreferredSize().height));
		b1.setMaximumSize(new Dimension(b1.getMaximumSize().width, b2.getMaximumSize().height));
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
		final Color fg = (enabled) ? label.getForeground() : getDisabledComponentColor(); // required
		label.setForeground(fg);														// for MACOS!?
		if (uri != null && Desktop.isDesktopSupported()) {
			//label.setIcon(IconFactory.getIcon(GLYPH.EXTERNAL_LINK, label.getFont().getSize2D() *.75f, label.getForeground()));
			label.addMouseListener(new MouseAdapter() {
				final int w = label.getFontMetrics(label.getFont()).stringWidth(label.getText());

				@Override
				public void mouseEntered(final MouseEvent e) {
					if (e.getX() <= w) {
						label.setForeground(new Color(0, 128, 255));
						label.setCursor(new Cursor(Cursor.HAND_CURSOR));
					}
				}

				@Override
				public void mouseExited(final MouseEvent e) {
					label.setForeground(fg);
					label.setCursor(new Cursor(Cursor.DEFAULT_CURSOR));
				}

				@Override
				public void mouseClicked(final MouseEvent e) {
					if (label.isEnabled() && e.getX() <= w) openURL(uri);
				}
			});
		}
		return label;
	}

	public void searchForum(final String query) {
			String url;
			try {
				url = "https://forum.image.sc/search?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8.toString());
			} catch (final Exception ignored) {
				url = query.trim().replace(" ", "%20");
			}
			openURL(url);
	}

	public static void openURL(final String uri) {
		try {
			Desktop.getDesktop().browse(new URI(uri));
		} catch (IOException | URISyntaxException ex) {
			if (uri != null && !uri.isEmpty()) {
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

	public static ImageIcon createIcon(final Color color, final int width,
		final int height)
	{
		if (color == null) return null;
		final BufferedImage image = new BufferedImage(width, height,
			java.awt.image.BufferedImage.TYPE_INT_ARGB);
		final Graphics2D graphics = image.createGraphics();
		graphics.setColor(color);
		graphics.fillRect(0, 0, width, height);
		graphics.setXORMode(getEnabledComponentColor());
		graphics.drawRect(0, 0, width - 1, height - 1);
		image.flush();
		return new ImageIcon(image);
	}

	public static int getMenuItemHeight() {
		Font font = UIManager.getDefaults().getFont("CheckBoxMenuItem.font");
		if (font == null) font = new Font(Font.SANS_SERIF, Font.PLAIN, 12);
		final Canvas c = new Canvas();
		return c.getFontMetrics(font).getHeight();
	}

	public static JMenuItem menuItemWithoutAccelerator() {
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

	public static String ctrlKey() {
		return (PlatformUtils.isMac()) ? "Cmd" : "Ctrl";
	}

	public static String modKey() {
		return (PlatformUtils.isMac()) ? "Alt" : "Ctrl";
	}

	public static Window getConsole() {
		for (final Window w : JFrame.getWindows()) {
			if (w instanceof JFrame && ("Console".equals(((JFrame) w).getTitle()))) {
				return w;
			}
		}
		return null;
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

	public static List<JMenuItem> getMenuItems(final JMenuBar menuBar) {
		final List<JMenuItem> list = new ArrayList<>();
		for (int i = 0; i < menuBar.getMenuCount(); i++) {
			final JMenu menu = menuBar.getMenu(i);
			if (menu != null) getMenuItems(menu, list);
		}
		return list;
	}

	public static List<JMenuItem> getMenuItems(final JPopupMenu popupMenu) {
		final List<JMenuItem> list = new ArrayList<>();
		for (final MenuElement me : popupMenu.getSubElements()) {
			if (me == null) {
				continue;
			} else if (me instanceof JMenuItem) {
				list.add((JMenuItem) me);
			} else if (me instanceof JMenu) {
				getMenuItems((JMenu) me, list);
			}
		}
		return list;
	}

	private static void getMenuItems(final JMenu menu, final List<JMenuItem> holdingList) {
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

	public static JTextField textField(final String placeholder) {
		return new TextFieldWithPlaceholder(placeholder);
	}

	public static void removeIcon(final Window window) {
		window.setIconImage(new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB_PRE));
	}

	public static JMenu helpMenu() {
		final JMenu helpMenu = new JMenu("Help");
		final String URL = "https://imagej.net/plugins/snt/";
		JMenuItem mi = menuItemTriggeringURL("Main Documentation Page", URL);
		mi.setIcon(IconFactory.getMenuIcon(GLYPH.HOME));
		helpMenu.add(mi);
		helpMenu.addSeparator();
		mi = menuItemTriggeringURL("User Manual", URL + "manual");
		mi.setIcon(IconFactory.getMenuIcon(GLYPH.BOOK_READER));
		helpMenu.add(mi);
		mi = menuItemTriggeringURL("Screencasts", URL + "screencasts");
		mi.setIcon(IconFactory.getMenuIcon(GLYPH.VIDEO));
		helpMenu.add(mi);
		mi = menuItemTriggeringURL("Step-by-step Instructions", URL + "step-by-step-instructions");
		mi.setIcon(IconFactory.getMenuIcon(GLYPH.FOOTPRINTS));
		helpMenu.add(mi);

		helpMenu.addSeparator();
		mi = menuItemTriggeringURL("Analysis", URL + "analysis");
		mi.setIcon(IconFactory.getMenuIcon(GLYPH.CHART));
		helpMenu.add(mi);
		mi = menuItemTriggeringURL("Reconstruction Viewer", URL + "reconstruction-viewer");
		mi.setIcon(IconFactory.getMenuIcon(GLYPH.CUBE));
		helpMenu.add(mi);

		helpMenu.addSeparator();
		mi = menuItemTriggeringURL("List of Shortcuts", URL + "key-shortcuts");
		mi.setIcon(IconFactory.getMenuIcon(GLYPH.KEYBOARD));
		helpMenu.add(mi);
		helpMenu.addSeparator();

		mi = menuItemTriggeringURL("Ask a Question", "https://forum.image.sc/tag/snt");
		mi.setIcon(IconFactory.getMenuIcon(GLYPH.COMMENTS));
		helpMenu.add(mi);
		mi = menuItemTriggeringURL("FAQs", URL + "faq");
		mi.setIcon(IconFactory.getMenuIcon(GLYPH.QUESTION));
		helpMenu.add(mi);
		mi = menuItemTriggeringURL("Known Issues", "https://github.com/morphonets/SNT/issues");
		mi.setIcon(IconFactory.getMenuIcon(GLYPH.BUG));
		helpMenu.add(mi);
		mi = menuItemTriggeringURL("Release Notes", releaseNotesURL());
		mi.setIcon(IconFactory.getMenuIcon(GLYPH.NEWSPAPER));
		helpMenu.add(mi);

		helpMenu.addSeparator();
		helpMenu.add(MenuItems.devResourceMain());
		helpMenu.add(MenuItems.devResourceNotebooks());
		helpMenu.add(MenuItems.devResourceAPI());
		helpMenu.addSeparator();

		mi = menuItemTriggeringURL("SNT's Algorithms", "https://github.com/morphonets/SNT/blob/master/NOTES.md#algorithms");
		mi.setIcon(IconFactory.getMenuIcon(GLYPH.COGS));
		helpMenu.add(mi);
		mi = menuItemTriggeringURL("SNT Manuscript", "http://dx.doi.org/10.1038/s41592-021-01105-7");
		mi.setIcon(IconFactory.getMenuIcon(GLYPH.FILE));
		helpMenu.add(mi);
		helpMenu.addSeparator();

		final JMenuItem about = new JMenuItem("About...");
		about.setIcon(IconFactory.getMenuIcon(GLYPH.INFO));
		about.addActionListener(e -> showAboutDialog());
		helpMenu.add(about);

		return helpMenu;
	}

	public static JMenuItem menuItemTriggeringURL(final String label, final String URL) {
		final JMenuItem mi = new JMenuItem(label);
		mi.addActionListener(e -> IJ.runPlugIn("ij.plugin.BrowserLauncher", URL));
		return mi;
	}

	public static JMenuItem menuItemTriggeringHelpURL(final String label, final String URL) {
		final JMenuItem mi = menuItemTriggeringURL(label, URL);
		mi.setIcon(IconFactory.getMenuIcon(IconFactory.GLYPH.QUESTION));
		return mi;
	}

	static class TextFieldWithPlaceholder extends JTextField {

		private static final long serialVersionUID = 1L;
		private String initialPlaceholder;
		private String placeholder;

		TextFieldWithPlaceholder(final String placeholder) {
			changePlaceholder(placeholder, true);
		}

		Font getPlaceholderFont() {
			return getFont().deriveFont(Font.ITALIC);
		}

		String getPlaceholder() {
			return placeholder;
		}

		void changePlaceholder(final String placeholder, final boolean overrideInitialPlaceholder) {
			this.placeholder = placeholder;
			if (overrideInitialPlaceholder) initialPlaceholder = placeholder;
			update(getGraphics());
		}

		void resetPlaceholder() {
			changePlaceholder(initialPlaceholder, false);
		}

		@Override
		protected void paintComponent(final java.awt.Graphics g) {
			super.paintComponent(g);
			if (getText().isEmpty()) {
				final Graphics2D g2 = (Graphics2D) g.create();
				g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
				g2.setColor(getDisabledTextColor());
				g2.setFont(getPlaceholderFont());
				final FontMetrics fm = g2.getFontMetrics();
				final Rectangle2D r = fm.getStringBounds(getPlaceholder(), g2);
				final int y = (getHeight() - (int) r.getHeight()) / 2 + fm.getAscent();
				g2.drawString(getPlaceholder(), getInsets().left, y);
				g2.dispose();
			}
		}
	}

	public static Color getDisabledComponentColor() {
		try {
			return UIManager.getColor("MenuItem.disabledForeground");
		}
		catch (final Exception ignored) {
			return Color.GRAY;
		}
	}

	private static Color getEnabledComponentColor() {
		try {
			return UIManager.getColor("MenuItem.foreground");
		}
		catch (final Exception ignored) {
			return Color.GRAY;
		}
	}

	public static JButton smallButton(final String text) {
		final double SCALE = .85;
		final JButton button = new JButton(text);
		final Font font = button.getFont();
		button.setFont(font.deriveFont((float) (font.getSize() * SCALE)));
		final Insets insets = button.getMargin();
		button.setMargin(new Insets((int) (insets.top * SCALE), (int) (insets.left *
			SCALE), (int) (insets.bottom * SCALE), (int) (insets.right * SCALE)));
		return button;
	}

	public static JSpinner integerSpinner(final int value, final int min,
		final int max, final int step, final boolean allowEditing)
	{
		final int maxDigits = Integer.toString(max).length();
		final SpinnerModel model = new SpinnerNumberModel(value, min, max, step);
		final JSpinner spinner = new JSpinner(model);
		final JFormattedTextField textfield = ((DefaultEditor) spinner.getEditor())
			.getTextField();
		textfield.setColumns(maxDigits);
		try {
			if (allowEditing) {
				((NumberFormatter) textfield.getFormatter()).setAllowsInvalid(false);
			}
			textfield.setEditable(allowEditing);
		} catch (final Exception ignored){
			textfield.setEditable(false);
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
//		textfield.addPropertyChangeListener(new PropertyChangeListener() {
//
//			@Override
//			public void propertyChange(final PropertyChangeEvent evt) {
//				if ("editValid".equals(evt.getPropertyName()) && Boolean.FALSE.equals(evt.getNewValue())) {
//
//					new GuiUtils(spinner).getPopup("Number must be between " + SNT.formatDouble(min, nDecimals)
//							+ " and " + SNT.formatDouble(max, nDecimals), spinner).showPopup();
//
//				}
//
//			}
//		});
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
		return "\u00B5m";
	}

	/**
	 * Returns a more human readable representation of a length in micrometers.
	 * <p>
	 * E.g., scaledMicrometer(0.01,1) returns "1.0nm"
	 * </p>
	 *
	 * @param umLength the length in micrometers
	 * @param digits the number of output decimals
	 * @return the scaled unit
	 */
	public static String scaledMicrometer(final double umLength,
		final int digits)
	{
		String symbol = "";
		double length = 0;
		if (umLength < 0.0001) {
			length = umLength * 10000;
			symbol = "\u00C5";
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
		else if (umLength > 1000000) {
			length = umLength / 1000000;
			symbol = "m";
		}
		else if (umLength > 1000000000) {
			length = umLength / 1000000000;
			symbol = "km";
		}
		return SNTUtils.formatDouble(length, digits) + symbol;
	}

	public static void errorPrompt(final String msg) {
		new GuiUtils().error(msg, "SNT v" + SNTUtils.VERSION);
	}

	public static String[] availableLookAndFeels() {
		return new String[] { LAF_DEFAULT, LAF_LIGHT, LAF_LIGHT_INTJ, LAF_DARK, LAF_DARCULA };
	}

	public static void setLookAndFeel() {
		storeExistingLookAndFeel();
		final String lafName = SNTPrefs.getLookAndFeel(); // never null
		// If SNT is not using FlatLaf but Fiji is, prefer Fiji choice
		if (existingLaf instanceof FlatLaf && !lafName.contains("FlatLaf"))
			return;
		// Otherwise apply SNT's L&F preference as long as it is valid
		if (existingLaf == null || !lafName.equals(existingLaf.getName()))
			setLookAndFeel(SNTPrefs.getLookAndFeel(), false);
	}

	public static AbstractButton getButton(final Container parent, final String label) {
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

	public static JFileChooser getDnDFileChooser() {
		@SuppressWarnings("serial")
		final JFileChooser fileChooser = new FileChooser() {
			@Override
			public File getCurrentDirectory() {
				// Workaround Linux bug where somehow directory always becomes root
				if (super.getCurrentDirectory() == null || super.getCurrentDirectory().toPath().getNameCount() == 0) {
					return SNTPrefs.lastknownDir();
				}
				return super.getCurrentDirectory();
			}
		};
		fileChooser.setCurrentDirectory(SNTPrefs.lastknownDir());
		new FileDrop(fileChooser, files -> {
			if (files.length == 0) { // Is this even possible?
				new GuiUtils(fileChooser).error("Dropped file(s) not recognized.");
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
					if (window == null)
						SwingUtilities.updateComponentTreeUI(component);
					else
						SwingUtilities.updateComponentTreeUI(window);
				} catch (final Exception ex) {
						SNTUtils.error("", ex);
					}
				}
			});
		}
		if (persistentChoice) {
			SNTPrefs.setLookAndFeel(lookAndFeelName);
		}
		return success;
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
		// make list immutable in case windowList is being modified programmatically (e.g., in a script)
		final List<? extends Window> wList = Collections.unmodifiableList(windowList);
		if (wList == null || wList.isEmpty()) return;
		// FIXME: This is all taken from ij1.
		final Rectangle screen = ij.gui.GUI.getMaxWindowBounds(ij.IJ.getApplet());
		final int XSTART = 4, YSTART = 94, GAP = 2;
		final int titlebarHeight = 40;
		int minWidth = Integer.MAX_VALUE;
		int minHeight = Integer.MAX_VALUE;
		double totalWidth = 0;
		double totalHeight = 0;
		for (final Window window : wList) {
			final Dimension d = window.getSize();
			final int w = d.width;
			final int h = d.height + titlebarHeight;
			if (w < minWidth)
				minWidth = w;
			if (h < minHeight)
				minHeight = h;
			totalWidth += w;
			totalHeight += h;
		}
		final int nPics = wList.size();
		final double averageWidth = totalWidth / nPics;
		final double averageHeight = totalHeight / nPics;
		int tileWidth = (int) averageWidth;
		int tileHeight = (int) averageHeight;
		final int hspace = screen.width - 2 * GAP;
		if (tileWidth > hspace)
			tileWidth = hspace;
		final int vspace = screen.height - YSTART;
		if (tileHeight > vspace)
			tileHeight = vspace;
		int hloc, vloc;
		boolean theyFit;
		do {
			hloc = XSTART;
			vloc = YSTART;
			theyFit = true;
			int i = 0;
			do {
				i++;
				if (hloc + tileWidth > screen.width) {
					hloc = XSTART;
					vloc = vloc + tileHeight;
					if (vloc + tileHeight > screen.height)
						theyFit = false;
				}
				hloc = hloc + tileWidth + GAP;
			} while (theyFit && (i < nPics));
			if (!theyFit) {
				tileWidth = (int) (tileWidth * 0.98 + 0.5);
				tileHeight = (int) (tileHeight * 0.98 + 0.5);
			}
		} while (!theyFit);
		hloc = XSTART;
		vloc = YSTART;
		for (final Window window : wList) {
			if (hloc + tileWidth > screen.width) {
				hloc = XSTART;
				vloc = vloc + tileHeight;
			}
			window.setLocation(hloc + screen.x, vloc + screen.y);
			window.toFront();
			hloc += tileWidth + GAP;
		}
	}

	public JDialog showHTMLDialog(final String msg, final String title, final boolean modal) {
		final JDialog dialog = new HTMLDialog(msg, title, modal);
		dialog.setVisible(true);
		return dialog;
	}

	public JMenuItem combineChartsMenuItem() {
		final JMenuItem jmi = new JMenuItem("Combine Charts Into Montage...", IconFactory.getMenuIcon(GLYPH.GRID));
		jmi.setToolTipText("Combines isolated SNT charts (plots, histograms, etc.) into a grid layout");
		jmi.addActionListener(e -> combineSNTChartPrompt());
		return jmi;
	}

	private void combineSNTChartPrompt() {

		class CombineGUI implements DocumentListener {

			final JTextField colField;
			final JTextField rowField;
			final JList<String> titles;
			final JCheckBox checkbox;
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
					nCols = Math.max(1, (int) Math.ceil(n / nRows));
					colField.setText("" + nCols);
				} else { // cols is being set: Adjust rows
					nCols = parseInt(colField);
					nRows = Math.max(1, (int) Math.ceil(n / nCols));
					rowField.setText("" + nRows);
				}
				pauseSyncFields = false;
			}

			void prompt() {
				nCols = Math.max(1, (int) Math.floor(Math.sqrt(charts.size())));
				nRows = Math.max(1, (int) Math.ceil(charts.size() / nCols));
				colField.setText("" + nCols);
				rowField.setText("" + nRows);
				final int result = JOptionPane.showConfirmDialog(parent, panel(), "Make SNTChart Montage",
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
					if (selection == null || selection.isEmpty()) {
						error("No charts selected from list.");
						return;
					}
					SNTChart.combine(selection, (int) rows, (int) cols, checkbox.isSelected()).show();
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

	public static JTabbedPane getTabbedPane() {
		/*
		 * TF: This is an effort at improving the tabbed interface. JIDE provides such
		 * functionality by default, but causes some weird looking L&F overrides (at
		 * least on macOS). Since I have no idea on how to stop JIDE from injecting such
		 * weirdness, we'll implement the customization ourselves.
		 */
		// final JideTabbedPane tabbedPane = new JideTabbedPane(JTabbedPane.TOP);
		// tabbedPane.setBoldActiveTab(true);
		// tabbedPane.setScrollSelectedTabOnWheel(true);
		// tabbedPane.setTabResizeMode(JideTabbedPane.RESIZE_MODE_NONE);
		final JTabbedPane tabbedPane = new JTabbedPane(JTabbedPane.TOP);
		tabbedPane.putClientProperty( "JTabbedPane.tabRotation", "auto" ); // flatlaf v3.3
		tabbedPane.addMouseWheelListener(e -> {
			// https://stackoverflow.com/a/38463104
			final JTabbedPane pane = (JTabbedPane) e.getSource();
			final int units = e.getWheelRotation();
			final int oldIndex = pane.getSelectedIndex();
			final int newIndex = oldIndex + units;
			if (newIndex < 0)
				pane.setSelectedIndex(0);
			else if (newIndex >= pane.getTabCount())
				pane.setSelectedIndex(pane.getTabCount() - 1);
			else
				pane.setSelectedIndex(newIndex);
		});
		final JPopupMenu popup = new JPopupMenu();
		tabbedPane.setComponentPopupMenu(popup);
		final ButtonGroup group = new ButtonGroup();
		for (final String pos : new String[] { "Top", "Bottom", "Left", "Right" }) {
			final JMenuItem jcbmi = new JCheckBoxMenuItem("Place on " + pos, "Top".equals(pos));
			jcbmi.addItemListener(e -> {
				switch (pos) {
				case "Bottom":
					tabbedPane.setTabPlacement(JTabbedPane.BOTTOM);
					break;
				case "Left":
					tabbedPane.setTabPlacement(JTabbedPane.LEFT);
					break;
				case "Right":
					tabbedPane.setTabPlacement(JTabbedPane.RIGHT);
					break;
				default:
					tabbedPane.setTabPlacement(JTabbedPane.TOP);
					break;
				}
			});
			group.add(jcbmi);
			popup.add(jcbmi);
		}
		return tabbedPane;
	}

	private static String releaseNotesURL() {
		return "https://github.com/morphonets/SNT/releases";
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
				if (editorPane.getSelectedText() == null || editorPane.getSelectedText().length() == 0)
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
					IJ.runPlugIn("ij.plugin.BrowserLauncher", url);
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
			setVisible(true);
			toFront();
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

		public static JMenuItem brainAreaAnalysis() {
			final JMenuItem jmi = new JMenuItem("Brain Area Analysis...", IconFactory.getMenuIcon(GLYPH.BRAIN));
			jmi.setToolTipText("Distribution analyisis of projection patterns across brain areas.");
			return jmi;
		}

		public static JMenuItem devResourceMain() {
			final JMenuItem jmi = menuItemTriggeringURL("Scripting Documentation", "https://imagej.net/plugins/snt/scripting");
			jmi.setIcon(IconFactory.getMenuIcon(GLYPH.CODE));
			return jmi;
		}

		public static JMenuItem devResourceAPI() {
			final JMenuItem jmi = menuItemTriggeringURL("API", "https://javadoc.scijava.org/SNT/");
			jmi.setIcon(IconFactory.getMenuIcon(GLYPH.CODE2));
			return jmi;
		}

		public static JMenuItem devResourceNotebooks() {
			final JMenuItem jmi = menuItemTriggeringURL("Jupyter Notebooks", "https://github.com/morphonets/SNT/tree/master/notebooks");
			jmi.setIcon(IconFactory.getMenuIcon(GLYPH.SCROLL));
			return jmi;
		}

		public static JMenuItem convexHull() {
			final JMenuItem jmi = new JMenuItem("Convex Hull...", IconFactory.getMenuIcon(GLYPH.DICE_20));
			jmi.setToolTipText(
					"2D/3D convex hull measurement(s).\nMetrics for estimation of dendritic or pre-synaptic fields");
			return jmi;
		}

		public static JMenuItem createDendrogram() {
			final JMenuItem jmi = new JMenuItem("Create Dendrogram", IconFactory.getMenuIcon(GLYPH.DIAGRAM));
			jmi.setToolTipText("Display reconstructions in Graph Viewer");
			return jmi;
		}

		public static JMenuItem createAnnotionGraph() {
			final JMenuItem jmi = new JMenuItem("Create Annotation Graph...", IconFactory.getMenuIcon(GLYPH.BRAIN));
			jmi.setToolTipText("Flow Plots and Ferris-Wheel diagrams from annoated trees");
			return jmi;
		}

		public static JMenuItem measureOptions() {
			final JMenuItem jmi = new JMenuItem("Measure...", IconFactory.getMenuIcon(GLYPH.TABLE));
			jmi.setToolTipText("<HTML>Compute detailed metrics from single cells");
			return jmi;
		}

		public static JMenuItem measureQuick() {
			final JMenuItem jmi = new JMenuItem("Quick Measurements", IconFactory.getMenuIcon(GLYPH.ROCKET));
			jmi.setToolTipText("Run simplified \"Measure...\" calls using commonly used metrics");
			return jmi;
		}

		public static JMenuItem saveTablesAndPlots(final GLYPH glyph) {
			final JMenuItem jmi = new JMenuItem("Save Tables & Analysis Plots...", IconFactory.getMenuIcon(glyph));
			jmi.setToolTipText("Save all tables, plots, and charts currently open");
			return jmi;
		}

		public static JMenuItem shollAnalysis() {
			final JMenuItem jmi = new JMenuItem("Sholl Analysis...", IconFactory.getMenuIcon(GLYPH.BULLSEYE));
			jmi.setToolTipText("Sholl analysis using pre-defined focal points");
			return jmi;
		}

		public static JMenuItem strahlerAnalysis() {
			final JMenuItem jmi = new JMenuItem("Strahler Analysis...", IconFactory.getMenuIcon(GLYPH.BRANCH_CODE));
			jmi.setToolTipText("Horton–Strahler measures of branching complexity");
			return jmi;
		}

		public static JMenuItem fromOpenImage() {
			return new JMenuItem("From Open Image...", IconFactory.getMenuIcon(GLYPH.IMAGE));
		}

		public static JMenuItem fromFileImage() {
			return new JMenuItem("From File...", IconFactory.getMenuIcon(GLYPH.FILE_IMAGE));
		}

	}

}
