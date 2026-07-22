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

import com.formdev.flatlaf.extras.FlatSVGIcon;
import com.formdev.flatlaf.ui.FlatUIUtils;
import com.formdev.flatlaf.util.Animator;
import com.formdev.flatlaf.util.CubicBezierEasing;
import com.formdev.flatlaf.util.UIScale;
import sc.fiji.snt.SNTUtils;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.AffineTransform;
import java.util.concurrent.atomic.AtomicInteger;

/* Large multichannel/timelapse images can take a while to load into SNT. This helps to maintain the GUI functional
 * Recycled code from https://stackoverflow.com/a/11935045 and net.imagej.launcher.SplashScreen
 * */
class SplashScreen extends JWindow {

	private static final long serialVersionUID = 1L;
	private static final int fontSizeRef = GuiUtils.MenuItems.defaultHeight();

	SplashScreen() {
		initAndDisplay();
	}

	static Icon getIcon() {
		try {
			final Dimension dim = getScaledIconDimensions(512, 528);
			final String iconPath = (SNTUtils.getInstance() != null && SNTUtils.getInstance().getUI() != null && SNTUtils.getInstance().getUI().isStreamMode())
					? "gui/SNTStreamLogo.svg" : "gui/SNTLogo.svg";
			return new FlatSVGIcon(iconPath, dim.width, dim.height);
		} catch (final Exception ignored) {
			// do nothing
		}
		return new com.formdev.flatlaf.icons.FlatOptionPaneWarningIcon(); // non-null fallback
	}

	static JLabel getIconAsLabel() {
		final JLabel logoImage = new JLabel(getIcon());
		logoImage.setAlignmentX(JLabel.CENTER_ALIGNMENT);
		return logoImage;
	}

	static JLabel getIconAsAnimatedLabel() {

		final Dimension dim = getScaledIconDimensions(512, 528);
		final FlatSVGIcon icon1 = new FlatSVGIcon("gui/SNTLogo.svg", dim.width, dim.height);
		final FlatSVGIcon icon2 = new FlatSVGIcon("gui/SNTStreamLogo.svg", dim.width, dim.height);
		final FlatSVGIcon icon3 = new FlatSVGIcon("gui/PySNTLogo.svg", dim.width, dim.height);
		final FlatSVGIcon[] iconRotator = { icon1, icon2, icon3 };

		final FlatLafSvgAnimatedLabel animatedLabel = new FlatLafSvgAnimatedLabel(icon1);
		animatedLabel.setAlignmentX(JLabel.CENTER_ALIGNMENT);

		final AtomicInteger iconIndex = new AtomicInteger(0);
		final Timer loopTimer = new Timer(3000, e -> {
			// Atomically advance the index and wrap around (0 -> 1 -> 2 -> 0...)
			final int nextIndex = iconIndex.updateAndGet(i -> (i + 1) % iconRotator.length);
			animatedLabel.transitionToIcon(iconRotator[nextIndex]);
		});

		loopTimer.setRepeats(true);
		loopTimer.setInitialDelay(0);
		loopTimer.start();

		return animatedLabel;
	}

	private static Dimension getScaledIconDimensions(final int originalIconWidth, final int originalIconHeight) {
		final double ref = UIScale.unscale(fontSizeRef * 10); // logical pixels on all platforms
		final Dimension dim = new Dimension();
		if (originalIconHeight / ref < 1) {
			dim.setSize(originalIconWidth, originalIconHeight);
		} else {
			dim.setSize((int)(originalIconWidth * ref / originalIconHeight), (int) ref);
		}
		return dim;
	}

	static void assignStyle(final JLabel label, final int scalingFactor) {
		label.setFont(new Font(Font.DIALOG, Font.BOLD, fontSizeRef * scalingFactor));
		label.setAlignmentX(JLabel.CENTER_ALIGNMENT);
	}

	private void initAndDisplay() {
		final JProgressBar progressBar = new JProgressBar();
		progressBar.setIndeterminate(true);
		progressBar.setStringPainted(true);
		progressBar.setString("Initializing...");
		progressBar.setBackground(getContentPane().getBackground());
		setLayout(new BorderLayout(4,4));
		final JLabel logo = getIconAsLabel();
        logo.setBorder(new EmptyBorder(fontSizeRef, fontSizeRef, fontSizeRef / 2, fontSizeRef));
        add(logo, BorderLayout.CENTER);
		add(progressBar, BorderLayout.SOUTH);
		pack();
		setLocationRelativeTo(null);
		setAlwaysOnTop(true);
		SwingUtilities.invokeLater(() -> setVisible(true));
	}

	private static class FlatLafSvgAnimatedLabel extends JLabel {
		private FlatSVGIcon currentIcon;
		private FlatSVGIcon nextIcon;
		// alpha == progress towards nextIcon: 0 -> currentIcon fully opaque, 1 -> nextIcon fully opaque.
		// NB: this must default/reset to 0f, not 1f: leaving it at 1f makes currentIcon invisible
		private float alpha = 0.0f;
		private Animator animator;

		public FlatLafSvgAnimatedLabel(final FlatSVGIcon initialIcon) {
			this.currentIcon = initialIcon;
			if (initialIcon != null) setIcon(initialIcon);
			setOpaque(true);
		}

		public void transitionToIcon(final FlatSVGIcon newIcon) {
			if (animator != null && animator.isRunning()) {
				animator.stop();
			}
			if (currentIcon == newIcon) {
				return; // Skip if it's already displaying this icon
			}
			nextIcon = newIcon;
			alpha = 0.0f;

			animator = new Animator(4000, new Animator.TimingTarget() {
				@Override
				public void timingEvent(float fraction) {
					alpha = fraction; // Automatically sweeps from 0.0f to 1.0f
					repaint();
				}

				@Override
				public void end() {
					// alpha = 0f (not 1f): currentIcon is about to become the settled/idle icon, and
					// paintComponent() renders currentIcon at opacity (1 - alpha), so it must be 0 here
					// for the now-settled icon to render fully opaque instead of vanishing
					alpha = 0.0f;
					currentIcon = nextIcon;
					setIcon(currentIcon);
					nextIcon = null;
					repaint();
				}
			});

			animator.setInterpolator(CubicBezierEasing.EASE_IN_OUT);
			animator.start();
		}

		@Override
		protected void paintComponent(Graphics g) {
			if (currentIcon == null) {
				super.paintComponent(g);
				return;
			}

			final Graphics2D g2d = (Graphics2D) g.create();
			FlatUIUtils.setRenderingHints(g2d);

			final int width = getWidth();
			final int height = getHeight();

			// Clear the canvas first: without this, each frame's semi-transparent icon(s) blend on top
			// of whatever pixels the *previous* repaint left behind (accumulated smearing/ghosting)
			// instead of a clean background, since these are SVGs with transparent margins around the
			// drawn shapes and paintComponent() never calls super.paintComponent() to do this for us.
			// setOpaque(true) alone does NOT paint anything -- it's only a hint to the repaint manager.
			g2d.setColor(getBackground());
			g2d.fillRect(0, 0, width, height);

			// Center calculation based on the current icon boundaries
			final int x = (width - currentIcon.getIconWidth()) / 2;
			final int y = (height - currentIcon.getIconHeight()) / 2;

			// Draw old vector icon fading out
			g2d.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 1.0f - alpha));
			currentIcon.paintIcon(this, g2d, x, y);

			// Draw new vector icon fading in
			if (nextIcon != null) {
				final int nextX = (width - nextIcon.getIconWidth()) / 2;
				final int nextY = (height - nextIcon.getIconHeight()) / 2;
				g2d.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
				nextIcon.paintIcon(this, g2d, nextX, nextY);
			}

			g2d.dispose();
		}
	}

	@SuppressWarnings("unused")
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

	/* IDE Debug method */
	public static void main(final String[] args) {
		GuiUtils.setLookAndFeel();
		GuiUtils.initSplashScreen();
		GuiUtils.showAboutDialog();
	}}
