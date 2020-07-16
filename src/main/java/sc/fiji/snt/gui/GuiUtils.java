/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2019 Fiji developers.
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

import com.jidesoft.plaf.LookAndFeelFactory;
import com.jidesoft.popup.JidePopup;
import com.jidesoft.utils.ProductNames;

import ij.gui.HTMLDialog;

import java.awt.Canvas;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Desktop;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.ComponentEvent;
import java.awt.event.ComponentListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowEvent;
import java.awt.event.WindowListener;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.URI;
import java.net.URISyntaxException;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.swing.*;
import javax.swing.JSpinner.DefaultEditor;
import javax.swing.border.EmptyBorder;
import javax.swing.colorchooser.AbstractColorChooserPanel;
import javax.swing.filechooser.FileFilter;
import javax.swing.plaf.FontUIResource;
import javax.swing.text.NumberFormatter;

import org.scijava.ui.awt.AWTWindows;
import org.scijava.ui.swing.SwingDialog;
import org.scijava.ui.swing.widget.SwingColorWidget;
import org.scijava.util.ColorRGB;
import org.scijava.util.PlatformUtils;
import org.scijava.util.Types;

import sc.fiji.snt.SNTUtils;

/** Misc. utilities for SNT's GUI. */
public class GuiUtils {

	final private Component parent;
	private JidePopup popup;
	private boolean popupExceptionTriggered;
	private int timeOut = 2500;
	private Color background = Color.WHITE;
	private Color foreground = Color.BLACK;

	public GuiUtils(final Component parent) {
		this.parent = parent;
		if (parent != null) {
			background = parent.getBackground();
			foreground = parent.getForeground();
		}
	}

	public GuiUtils() {
		this(null);
	}

	public void error(final String msg) {
		error(msg, "SNT v" + SNTUtils.VERSION);
	}

	public void error(final String msg, final String title) {
		centeredDialog(msg, title, JOptionPane.ERROR_MESSAGE);
	}

	public JDialog floatingMsg(final String msg, final boolean autodismiss) {
		final JDialog dialog = new FloatingDialog(msg);
		if (autodismiss) GuiUtils.setAutoDismiss(dialog);
		makeVisible(dialog);
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
				if (location < 0) {
					popup.showPopup();
				} else {
					popup.showPopup(location);
				}
				popup.showPopup();
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
					cleanedMsg = cleanedMsg.replaceAll("&amp;", "&");
					System.out.println("[INFO] [SNT] " + cleanedMsg.trim());
				} else {
					System.out.println("[INFO] [SNT] " + msg);
				}
			}
		});
	}

	public static void showHTMLDialog(final String msg, final String title) {
		SwingUtilities.invokeLater(() -> {
			new HTMLDialog(title, msg, false);
		});
	}

	public static boolean isLegacy3DViewerAvailable() {
		return Types.load("ij3d.Image3DUniverse") != null;
	}

	private JidePopup getPopup(final String msg) {
		final JLabel label = getLabel(msg);
		label.setHorizontalAlignment(SwingConstants.CENTER);
		final JidePopup popup = new JidePopup();
		popup.getContentPane().add(label);
		label.setBackground(background);
		label.setForeground(foreground);
		popup.getContentPane().setBackground(background);
		popup.setBackground(foreground);
		if (parent != null) {
			popup.setOwner(parent);
			popup.setMaximumSize(parent.getSize());
		}
		popup.setFocusable(false);
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

	private int yesNoDialog(final Object[] components, final String title,
		final String[] buttonLabels)
	{
		final JOptionPane optionPane = new JOptionPane(components,
			JOptionPane.QUESTION_MESSAGE, JOptionPane.YES_NO_OPTION, null,
			buttonLabels);
		final JDialog d = optionPane.createDialog(parent, title);
		makeVisible(d);
		d.dispose();
		final Object result = optionPane.getValue();
		if (result instanceof Integer) {
			return (Integer) result;
		}
		else if (buttonLabels != null &&
				result instanceof String)
		{
			return result.equals(buttonLabels[0]) ? JOptionPane.YES_OPTION
				: JOptionPane.NO_OPTION;
		}
		else {
			return SwingDialog.UNKNOWN_OPTION;
		}
	}

	private void makeVisible(final JDialog dialog) {
		// work around a bug in openjdk and MacOS in which prompts
		// are not frontmost if the component hierarchy is > 3
		dialog.setVisible(true);
		dialog.toFront();
		if (!dialog.hasFocus() && parent != null) parent.transferFocusUpCycle();
	}

	public boolean getConfirmation(final String msg, final String title) {
		return (yesNoDialog(msg, title) == JOptionPane.YES_OPTION);
	}

	public void error(final String msg, final String title, final String helpURI) {
		final JOptionPane optionPane = new JOptionPane(getLabel(msg), JOptionPane.ERROR_MESSAGE,
				JOptionPane.YES_NO_OPTION, null, new String[] { "Online Help", "OK" });
		final JDialog d = optionPane.createDialog(parent, title);
		makeVisible(d);
		d.dispose();
		if ("Online Help".equals(optionPane.getValue()))
			openURL(helpURI);
	}

	public boolean getConfirmation(final String msg, final String title, final String yesLabel, final String noLabel) {
		return (yesNoDialog(msg, title, yesLabel, noLabel) == JOptionPane.YES_OPTION);
	}

	public String getChoice(final String message, final String title, final String[] choices,
			final String defaultChoice) {
		final String selectedValue = (String) JOptionPane.showInputDialog(parent, //
				message, title, JOptionPane.QUESTION_MESSAGE, null, choices,
				(defaultChoice == null) ? choices[0] : defaultChoice);
		return selectedValue;
	}

	public List<String> getMultipleChoices(final String message, final String title, final String[] choices) {
		final JList list = new JList(choices);
		JOptionPane.showMessageDialog(
				parent, new JScrollPane(list), title, JOptionPane.QUESTION_MESSAGE);
		return list.getSelectedValuesList();
	}

	public boolean[] getPersistentConfirmation(final String msg,
		final String title)
	{
		final JCheckBox checkbox = new JCheckBox();
		checkbox.setText(getWrappedText(checkbox,
			"Remember my choice and do not prompt me again"));
		final Object[] params = { getLabel(msg), checkbox };
		final boolean result = yesNoDialog(params, title, null) == JOptionPane.YES_OPTION;
		return new boolean[] { result, checkbox.isSelected() };
	}

	public String getString(final String promptMsg, final String promptTitle,
		final String defaultValue)
	{
		return (String) getObj(promptMsg, promptTitle, defaultValue);
	}

	public Color getColor(final String title, final Color defaultValue) {
		return SwingColorWidget.showColorDialog(parent, title, defaultValue);
	}

	public ColorRGB getColorRGB(final String title, final Color defaultValue,
		final String... panes)
	{
		final Color color = getColor(title, defaultValue, panes);
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
	 *          default GtkColorChooser pane
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
		List<String> allowedPanels = new ArrayList<>();
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
		makeVisible(dialog);
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

	public File saveFile(final String title, final File file,
		final List<String> allowedExtensions)
	{
		File chosenFile = null;
		final JFileChooser chooser = fileChooser(title, file, JFileChooser.FILES_ONLY, allowedExtensions);
		if (chooser.showSaveDialog(parent) == JFileChooser.APPROVE_OPTION) {
			chosenFile = chooser.getSelectedFile();
			if (chosenFile != null && allowedExtensions != null && allowedExtensions.size() == 1) {
				final String path = chosenFile.getAbsolutePath();
				final String extension = allowedExtensions.get(0);
				if (!path.endsWith(extension))
					chosenFile = new File(path + extension);
			}
			if (chosenFile.exists()
					&& !getConfirmation(chosenFile.getAbsolutePath() + " already exists. Do you want to replace it?",
							"Override File?")) {
				return null;
			}
		}
		return chosenFile;
	}

	
	@SuppressWarnings("unused")
	private File openFile(final String title, final File file,
		final List<String> allowedExtensions)
	{
		final JFileChooser chooser = fileChooser(title, file,
			JFileChooser.FILES_ONLY, allowedExtensions);
		if (chooser.showOpenDialog(parent) == JFileChooser.APPROVE_OPTION)
			return chooser.getSelectedFile();
		return null;
	}

	@SuppressWarnings("unused")
	private File chooseDirectory(final String title, final File file) {
		final JFileChooser chooser = fileChooser(title, file,
			JFileChooser.DIRECTORIES_ONLY, null);
		if (chooser.showOpenDialog(parent) == JFileChooser.APPROVE_OPTION)
			return chooser.getSelectedFile();
		return null;
	}

	private JFileChooser fileChooser(final String title, final File file,
		final int type, final List<String> allowedExtensions)
	{
		final JFileChooser chooser = new JFileChooser(file);
		if (file != null) {
			if (file.exists()) {
				chooser.setSelectedFile(file);
			} else {
				chooser.setCurrentDirectory(file.getParentFile());
			}
		}
		chooser.setDialogTitle(title);
		chooser.setFileSelectionMode(type);
		chooser.setDragEnabled(true);
		if (allowedExtensions != null && !allowedExtensions.isEmpty()) {
			chooser.setFileFilter(new FileFilter() {

				@Override
				public String getDescription() {
					return String.join(",", allowedExtensions);
				}

				@Override
				public boolean accept(final File f) {
					if (f.isDirectory()) {
						return true;
					}
					else {
						final String filename = f.getName().toLowerCase();
						for (final String ext : allowedExtensions) {
							if (filename.endsWith(ext)) return true;
						}
						return false;
					}
				}
			});
		}
		return chooser;
	}

	private Object getObj(final String promptMsg, final String promptTitle,
		final Object defaultValue)
	{
		return JOptionPane.showInputDialog(parent, promptMsg, promptTitle,
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
		final boolean[] defaults, String title)
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
		makeVisible(d);
		final Object result = optionPane.getValue();
		if ((!(result instanceof Integer)))
			return SwingDialog.UNKNOWN_OPTION;
		return (Integer) result;
	}

	public void addTooltip(final JComponent c, final String text) {
		final int length = c.getFontMetrics(c.getFont()).stringWidth(text);
		c.setToolTipText("<html>" + ((length > 500) ? "<body><div style='width:500;'>" : "") + text);
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
			private final int maxCount = 100;
			private boolean on = false;

			@Override
			public void actionPerformed(final ActionEvent e) {
				if (count >= maxCount) {
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

	/* Static methods */

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
		if (vgap) c.insets.top = (int) (component.getFontMetrics(font).getHeight() *
			1.5);
		component.add(label, c);
		if (vgap) c.insets.top = previousTopGap;
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
			label.addMouseListener(new MouseAdapter() {
				final int w = label.getFontMetrics(label.getFont()).stringWidth(label.getText());

				@Override
				public void mouseEntered(final MouseEvent e) {
					if (e.getX() <= w) {
						label.setForeground(Color.BLUE);
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

	private static void openURL(final String uri) {
		try {
			Desktop.getDesktop().browse(new URI(uri));
		} catch (IOException | URISyntaxException ex) {
			SNTUtils.log("Could not open " + uri);
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
		graphics.setXORMode(Color.DARK_GRAY);
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

	public static String ctrlKey() {
		return (PlatformUtils.isMac()) ? "Cmd" : "Ctrl";
	}

	public static String modKey() {
		return (PlatformUtils.isMac()) ? "Alt" : "Ctrl";
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
			getMenuItems(menu, list);
		}
		return list;
	}

	public static List<JMenuItem> getMenuItems(final JPopupMenu menuBar) {
		final List<JMenuItem> list = new ArrayList<>();
		for (final MenuElement me : menuBar.getSubElements()) {
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

	public JTextField textField(final String placeholder) {
		return new TextFieldWithPlaceholder(placeholder);
	}

	static class TextFieldWithPlaceholder extends JTextField {

		private static final long serialVersionUID = 1L;
		private String initialPlaceholder;
		private String placeholder;

		TextFieldWithPlaceholder(final String placeholder) {
			changePlaceholder(placeholder, true);
			setBorder(null);
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
			if (getText().isEmpty() && !(FocusManager.getCurrentKeyboardFocusManager().getFocusOwner() == this)) {
				final Graphics2D g2 = (Graphics2D) g.create();
				g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
						RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
				g2.setColor(Color.GRAY);
				g2.setFont(getFont().deriveFont(Font.ITALIC));
				g2.drawString(placeholder, 4, g2.getFontMetrics().getHeight());
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
		final int max, final int step)
	{
		final int maxDigits = Integer.toString(max).length();
		final SpinnerModel model = new SpinnerNumberModel(value, min, max, step);
		final JSpinner spinner = new JSpinner(model);
		final JFormattedTextField textfield = ((DefaultEditor) spinner.getEditor())
			.getTextField();
		textfield.setColumns(maxDigits);
		textfield.setEditable(false);
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
		StringBuilder decString = new StringBuilder();
		while (decString.length() <= nDecimals)
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

	public static void setSystemLookAndFeel() {
		try {
			// With Ubuntu and java 8 we need to ensure we're using
			// GTK+ L&F otherwise no scaling occurs with hiDPI screens
			UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
			LookAndFeelFactory.installDefaultLookAndFeelAndExtension();
			LookAndFeelFactory.setProductsUsed(ProductNames.PRODUCT_COMMON);
		//	checkGTKLookAndFeel();
		}
		catch (final Error | Exception ignored) {
			// move on
		}
	}

	/** HACK Font too big on ubuntu: https://stackoverflow.com/a/31345102 */
	@SuppressWarnings("unused")
	private static void checkGTKLookAndFeel() throws Exception {
		final LookAndFeel look = UIManager.getLookAndFeel();
		if (!look.getID().equals("GTK")) return;
		final int dpi = Toolkit.getDefaultToolkit().getScreenResolution();
		if (dpi <= 72) return;
		final float scaleFont = dpi / 72;
		new JFrame();
		new JButton();
		new JComboBox<>();
		new JRadioButton();
		new JCheckBox();
		new JTextArea();
		new JTextField();
		new JTable();
		new JToggleButton();
		new JSpinner();
		new JSlider();
		new JTabbedPane();
		new JMenu();
		new JMenuBar();
		new JMenuItem();

		Object styleFactory;
		final Field styleFactoryField = look.getClass().getDeclaredField(
			"styleFactory");
		styleFactoryField.setAccessible(true);
		styleFactory = styleFactoryField.get(look);

		final Field defaultFontField = styleFactory.getClass().getDeclaredField(
			"defaultFont");
		defaultFontField.setAccessible(true);
		final Font defaultFont = (Font) defaultFontField.get(styleFactory);
		FontUIResource newFontUI;
		newFontUI = new FontUIResource(defaultFont.deriveFont(defaultFont
			.getSize() - scaleFont));
		defaultFontField.set(styleFactory, newFontUI);

		final Field stylesCacheField = styleFactory.getClass().getDeclaredField(
			"stylesCache");
		stylesCacheField.setAccessible(true);
		final Object stylesCache = stylesCacheField.get(styleFactory);
		final Map<?, ?> stylesMap = (Map<?, ?>) stylesCache;
		for (final Object mo : stylesMap.values()) {
			final Field f = mo.getClass().getDeclaredField("font");
			f.setAccessible(true);
			final Font fo = (Font) f.get(mo);
			f.set(mo, fo.deriveFont(fo.getSize() - scaleFont));
		}
	}

	public static void setAutoDismiss(final JDialog dialog) {
		final int DELAY = 2500;
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
			getContentPane().setBackground(background);
			setBackground(background);
			final JLabel label = getLabel(msg);
			label.setHorizontalAlignment(SwingConstants.CENTER);
			label.setBorder(new EmptyBorder(10, 10, 10, 10));
			label.setBackground(background);
			label.setForeground(foreground);
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

}
