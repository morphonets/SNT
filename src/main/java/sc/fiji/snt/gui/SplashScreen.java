/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2021 Fiji developers.
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

import java.awt.*;
import java.awt.event.ActionListener;
import java.net.URL;

import javax.swing.*;
import javax.swing.border.*;

/* Large multichannel/timelapse images can take a while to load into SNT. This helps to maintain the GUI functional 
 * Recycled code from https://stackoverflow.com/a/11935045 and net.imagej.launcher.SplashScreen
 * */
class SplashScreen extends JWindow {

	private static final long serialVersionUID = 1L;
	private static JProgressBar progressBar = new JProgressBar();
	private static int count = 1, TIMER_PAUSE = 25, PROGBAR_MAX = 100;
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

	private void initAndDisplay() {
		final Container container = getContentPane();
		final JPanel panel = new JPanel();
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
		panel.setBorder(new EtchedBorder());
		panel.setOpaque(false);
		container.add(panel, BorderLayout.CENTER);
		URL logoURL;
		try {
			final ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
			logoURL = classLoader.getResource("misc/SNTLogo512.png");
			if (logoURL != null) {
				ImageIcon imageIcon = new ImageIcon(logoURL);
				final Image image = imageIcon.getImage();
				// Smooth the image a little bit for better aesthetics
				final Image newimg = image.getScaledInstance(384, 396, Image.SCALE_SMOOTH);
				imageIcon = new ImageIcon(newimg);
				final JLabel logoImage = new JLabel(imageIcon);
				logoImage.setAlignmentX(JLabel.CENTER_ALIGNMENT);
				logoImage.setBorder(new EmptyBorder(10, 10, 4, 10));
				panel.add(logoImage);
			}
		} catch (final Exception ignored) {
			logoURL = null;
		}
		final JLabel label = new JLabel((logoURL == null) ? "Initializing SNT..." : "Initializing...");
		label.setAlignmentX(JLabel.CENTER_ALIGNMENT);
		// set font size reasonably for hiDPI displays
		//FIXME: With jdk11 this should no longer be needed
		label.setFont(new Font(Font.DIALOG, Font.BOLD, GuiUtils.getMenuItemHeight() * ((logoURL == null) ? 4 : 1)));
		panel.add(label);
		progressBar.setMaximum(PROGBAR_MAX);
		container.add(progressBar, BorderLayout.SOUTH);
		pack();
		setLocationRelativeTo(null);
		setAlwaysOnTop(true);
		progressBarTimer = new Timer(TIMER_PAUSE, al);
		setVisible(true);
		progressBarTimer.start();
	}

}