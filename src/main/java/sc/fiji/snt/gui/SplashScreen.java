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

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.ActionListener;
import java.net.URL;

/* Large multichannel/timelapse images can take a while to load into SNT. This helps to maintain the GUI functional
 * Recycled code from https://stackoverflow.com/a/11935045 and net.imagej.launcher.SplashScreen
 * */
class SplashScreen extends JWindow {

	private static final long serialVersionUID = 1L;
	private static final int fontSizeRef = GuiUtils.getMenuItemHeight();
	private static final JProgressBar progressBar = new JProgressBar();
	private static int count = 1;
    private static final int PROGBAR_MAX = 100;
	private static Timer progressBarTimer;
	private final ActionListener al = evt -> {
		progressBar.setValue(count);
		if (PROGBAR_MAX == count)
			count = 0; // reset progress
		count++;
	};

	SplashScreen() {
		initAndDisplay();
	}

	void close() {
		progressBarTimer.stop();
		dispose();
	}

	static JLabel getIconAsLabel() {
		try {
			final ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
			URL logoURL = classLoader.getResource("misc/SNTLogo512x528.png");
			if (logoURL != null) {
				ImageIcon imageIcon = new ImageIcon(logoURL);
				final Image image = imageIcon.getImage();
				final Dimension dim = getScaledIconDimensions(512, 528);
				final Image newimg = image.getScaledInstance(dim.width, dim.height, Image.SCALE_AREA_AVERAGING);
				imageIcon = new ImageIcon(newimg);
				final JLabel logoImage = new JLabel(imageIcon);
				logoImage.setAlignmentX(JLabel.CENTER_ALIGNMENT);
				return logoImage;
			}
		} catch (final Exception ignored) {
			// do nothing
		}
		return new JLabel(new com.formdev.flatlaf.icons.FlatOptionPaneWarningIcon()); // non-null fallback
	}

	static Dimension getScaledIconDimensions(final int originalIconWidth, final int originalIconHeight) {
		final double ref = fontSizeRef * 8; // as tall as 8 lines of text
		final Dimension dim = new Dimension();
		if (originalIconHeight / ref < 1) {
			dim.setSize(originalIconWidth, originalIconHeight);
		} else {
			dim.setSize(originalIconWidth * ref / originalIconHeight, ref);
		}
		return dim;
	}

	static void assignStyle(final JLabel label, final int scalingFactor) {
		label.setFont(new Font(Font.DIALOG, Font.BOLD, fontSizeRef * scalingFactor));
		label.setAlignmentX(JLabel.CENTER_ALIGNMENT);
	}

	private void initAndDisplay() {
		final Container container = getContentPane();
		final JPanel panel = new JPanel();
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
		panel.setOpaque(false);
		container.add(panel, BorderLayout.CENTER);
		final JLabel logo = getIconAsLabel();
		if (logo != null) {
			logo.setBorder(new EmptyBorder(fontSizeRef, fontSizeRef, fontSizeRef / 2, fontSizeRef));
			panel.add(logo);
		}
		final JLabel label = new JLabel((logo == null) ? "Initializing SNT..." : "Initializing...");
		// set font size reasonably for hiDPI displays
		//FIXME: With jdk11 this should no longer be needed
		assignStyle(label, ((logo == null) ? 4 : 1) );
		panel.add(label);
		progressBar.setMaximum(PROGBAR_MAX);
		container.add(progressBar, BorderLayout.SOUTH);
		pack();
		setLocationRelativeTo(null);
		setAlwaysOnTop(true);
        int TIMER_PAUSE = 25;
        progressBarTimer = new Timer(TIMER_PAUSE, al);
		setVisible(true);
		progressBarTimer.start();
	}

	/* IDE Debug method */
	public static void main(final String[] args) {
		GuiUtils.setLookAndFeel();
		GuiUtils.initSplashScreen();
		GuiUtils.showAboutDialog();
	}}
