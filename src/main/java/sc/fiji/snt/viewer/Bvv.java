/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2025 Fiji developers.
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

package sc.fiji.snt.viewer;

import bdv.tools.HelpDialog;
import bdv.util.AxisOrder;
import bvv.core.BigVolumeViewer;
import bvv.core.VolumeViewerFrame;
import bvv.vistools.*;
import ij.ImagePlus;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.type.numeric.ARGBType;
import net.imglib2.type.numeric.RealType;
import sc.fiji.snt.SNT;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.gui.GuiUtils;
import sc.fiji.snt.gui.IconFactory;

import javax.swing.*;
import javax.swing.event.ChangeListener;
import java.awt.*;
import java.io.File;

/**
 * Experimental support for Big Volume Viewer
 **/
public class Bvv {

    static { net.imagej.patcher.LegacyInjector.preinit(); } // required for _every_ class that imports ij. classes

    private final SNT snt;
    private final BvvOptions options;


    /**
     * @param snt the snt instance from which data is extracted
     */
    public Bvv(final SNT snt) {
        this.snt = snt;
        options = bvv.vistools.Bvv.options();
        options.preferredSize(1024, 1024);
        options.frameTitle("SNT BVV");
        options.cacheBlockSize(32); // GPU cache tile size
        options.maxCacheSizeInMB(300); // GPU cache size (in MB)
        options.ditherWidth(1); // dither window. 1 = full resolution; 8 = coarsest resolution
        options.numDitherSamples(8); // no. of nearest neighbors to interpolate from when dithering
        //options.maxAllowedStepInVoxels(1); // FIXME: function?
    }

    @SuppressWarnings("UnusedReturnValue")
    public BvvSource showLoadedData() {
        return displayData(false);
    }

    @SuppressWarnings("UnusedReturnValue")
    public BvvSource showSecondaryData() {
        return displayData(true);
    }

    private <T extends RealType<T>> BvvSource displayData(final boolean secondary) {
        if (!snt.accessToValidImageData()) throw new IllegalArgumentException("No valid image data available");
        final RandomAccessibleInterval<T> data = (secondary) ? snt.getSecondaryData() : snt.getLoadedData();
        final String label = String.format("Tracing Data (%s): C%d, T%d",
                (secondary) ? "Secondary layer" : "Main image", snt.getChannel(), snt.getFrame());
        final BvvSource source = BvvFunctions.show(data, label,
                options.sourceTransform(snt.getPixelWidth(), snt.getPixelHeight(), snt.getPixelDepth()));
        if (secondary && snt.getStatsSecondary().max > 0) {
            source.setDisplayRange(snt.getStatsSecondary().min, snt.getStatsSecondary().max);
        } else if (snt.getStats().max > 0) {
            source.setDisplayRange(snt.getStats().min, snt.getStats().max);
        }
        attachControlPanel(source);
        return source;
    }

    @SuppressWarnings("UnusedReturnValue")
    public BvvSource showImagePlus(final ImagePlus imp) {
        final BvvOptions opt = options.axisOrder(getAxisOrder(imp)).sourceTransform(
                imp.getCalibration().pixelWidth, imp.getCalibration().pixelHeight, imp.getCalibration().pixelDepth);
        final BvvStackSource<?> source = switch (imp.getType()) {
            case ImagePlus.COLOR_256 -> throw new IllegalArgumentException("Unsupported image type (COLOR_256).");
            case ImagePlus.GRAY8 -> BvvFunctions.show(ImageJFunctions.wrapByte(imp), imp.getTitle(), opt);
            case ImagePlus.GRAY16 -> BvvFunctions.show(ImageJFunctions.wrapShort(imp), imp.getTitle(), opt);
            case ImagePlus.GRAY32 -> BvvFunctions.show(ImageJFunctions.wrapFloat(imp), imp.getTitle(), opt);
            default -> BvvFunctions.show(ImageJFunctions.wrapRGBA(imp), imp.getTitle(), opt);
        };
        if (imp.getLuts().length == imp.getNChannels()) {
            for (int i = 0; i < imp.getNChannels(); i++) {
                final int rgb = imp.getLuts()[i].getRGB(255);
                source.getConverterSetups().get(i).setColor(new ARGBType(rgb));
                source.getConverterSetups().get(i).setDisplayRange(imp.getLuts()[i].min, imp.getLuts()[i].max);
            }
        }
        attachControlPanel(source);
        return source;
    }

    private static AxisOrder getAxisOrder(final ImagePlus imp) {
        if (imp.getNSlices() == 1 && imp.getNChannels() == 1 && imp.getNFrames() == 1) {
            return AxisOrder.XY;
        } else if (imp.getNSlices() > 1 && imp.getNChannels() == 1 && imp.getNFrames() == 1) {
            return AxisOrder.XYZ;
        } else if (imp.getNSlices() == 1 && imp.getNChannels() > 1 && imp.getNFrames() == 1) {
            return AxisOrder.XYC;
        } else if (imp.getNSlices() == 1 && imp.getNChannels() == 1 && imp.getNFrames() > 1) {
            return AxisOrder.XYT;
        } else if (imp.getNSlices() == 1 && imp.getNChannels() > 1 && imp.getNFrames() > 1) {
            return AxisOrder.XYCT;
        } else if (imp.getNSlices() > 1 && imp.getNChannels() == 1 && imp.getNFrames() > 1) {
            return AxisOrder.XYZT;
        } else {
            return AxisOrder.XYZCT;
        }
    }

    private void attachControlPanel(final BvvSource source) {
        final BigVolumeViewer bvv = ((BvvHandleFrame) source.getBvvHandle()).getBigVolumeViewer();
        final VolumeViewerFrame bvvFrame = bvv.getViewerFrame();
        bvvFrame.getCardPanel().addCard("Camera Control", cameraPanel(bvvFrame), true);
        bvvFrame.getCardPanel().addCard("SNT Controls", sntToolbar(bvv), false);
        SwingUtilities.invokeLater(bvv::expandAndFocusCardPanel);
    }

    private JToolBar sntToolbar(final BigVolumeViewer bvv) {
        final JButton oButton = new JButton(IconFactory.dropdownMenuIcon(IconFactory.GLYPH.OPTIONS));
        final JPopupMenu oMenu = optionsMenu(bvv);
        oButton.addActionListener(e -> oMenu.show(oButton, oButton.getWidth() / 2, oButton.getHeight() / 2));
        final JToolBar toolbar = new JToolBar();
        toolbar.add(Box.createHorizontalGlue());
        toolbar.addSeparator();
        toolbar.add(oButton);
        toolbar.addSeparator();
        toolbar.add(shortcutsButton(bvv));
        return toolbar;
    }

    private JPopupMenu optionsMenu(final BigVolumeViewer bvv) {
        final JPopupMenu menu = new JPopupMenu();
        GuiUtils.addSeparator(menu, "Settings");
        JMenuItem jmi = new JMenuItem("Load...", IconFactory.menuIcon(IconFactory.GLYPH.IMPORT));
        menu.add(jmi);
        final GuiUtils gUtils = new GuiUtils(bvv.getViewerFrame());
        jmi.addActionListener( e -> {
            final File f = gUtils.getFile(snt.getPrefs().getRecentDir(), "xml");
            if (SNTUtils.fileAvailable(f)) {
                try {
                    bvv.loadSettings(f.getAbsolutePath());
                    bvv.getViewer().showMessage(String.format("%s loaded", f.getName()));
                } catch (final Exception ex) {
                    gUtils.error(ex.getMessage());
                }
            }
        });
        jmi = new JMenuItem("Save...", IconFactory.menuIcon(IconFactory.GLYPH.EXPORT));
        menu.add(jmi);
        jmi.addActionListener( e -> {
            final File f = gUtils.getSaveFile("Save BVV Settings...", snt.getPrefs().getRecentDir(), "xml");
            if (SNTUtils.fileAvailable(f)) {
                try {
                    bvv.saveSettings(f.getAbsolutePath());
                    bvv.getViewer().showMessage(String.format("%s saved", f.getName()));
                } catch (final Exception ex) {
                    gUtils.error(ex.getMessage());
                }
            }
        });
        return menu;
    }

    private AbstractButton shortcutsButton(final BigVolumeViewer bvv) {
        final JButton button = new JButton(IconFactory.buttonIcon('\uf11c', true));
        button.addActionListener(e -> {
            final HelpDialog hDialog = new HelpDialog(bvv.getViewerFrame());
            hDialog.setPreferredSize(bvv.getViewerFrame().getCardPanel().getComponent().getPreferredSize());
            hDialog.setLocationRelativeTo(bvv.getViewerFrame());
            SwingUtilities.invokeLater(() -> hDialog.setVisible(true));
        });
        return button;
    }

    private JPanel cameraPanel(final VolumeViewerFrame bvvFrame) {

        final double def_dCam = 2000; // default in BvvOptions.Values
        final double def_dClipNear = 1000; // default in BvvOptions.Values
        final double def_dClipFar = 1000; // default in BvvOptions.Values

        final JSpinner dCamSpinner = GuiUtils.doubleSpinner(def_dCam, def_dCam / 5, def_dCam * 5, def_dCam / 4, 0);
        final JButton dCamReset = GuiUtils.Buttons.undo();
        final JSpinner nearSpinner = GuiUtils.doubleSpinner(def_dClipNear, def_dClipNear / 5, def_dClipNear * 5, def_dClipNear / 4, 0);
        final JButton nearReset = GuiUtils.Buttons.undo();
        final JSpinner farSpinner = GuiUtils.doubleSpinner(def_dClipFar, def_dClipFar / 5, def_dClipFar * 5, def_dClipFar / 4, 0);
        final JButton farReset = GuiUtils.Buttons.undo();
        final ChangeListener spinnerListener = e -> {
            bvvFrame.getViewerPanel().setCamParams((double) dCamSpinner.getValue(), (double) nearSpinner.getValue(), (double) farSpinner.getValue());
            bvvFrame.getViewerPanel().requestRepaint();
        };
        dCamSpinner.addChangeListener(spinnerListener);
        nearSpinner.addChangeListener(spinnerListener);
        farSpinner.addChangeListener(spinnerListener);
        dCamReset.addActionListener(e -> {
            dCamSpinner.setValue(def_dCam);
            bvvFrame.getViewerPanel().setCamParams(def_dCam, (double) nearSpinner.getValue(), (double) farSpinner.getValue());
            bvvFrame.getViewerPanel().requestRepaint();
        });
        nearReset.addActionListener(e -> {
            nearSpinner.setValue(def_dClipNear);
            bvvFrame.getViewerPanel().setCamParams((double) dCamSpinner.getValue(), def_dClipNear, (double) farSpinner.getValue());
            bvvFrame.getViewerPanel().requestRepaint();
        });
        farReset.addActionListener(e -> {
            farSpinner.setValue(def_dClipFar);
            bvvFrame.getViewerPanel().setCamParams((double) dCamSpinner.getValue(), (double) nearSpinner.getValue(), def_dClipFar);
            bvvFrame.getViewerPanel().requestRepaint();
        });

        dCamSpinner.setToolTipText("Distance from camera to z=0 plane in physical units");
        nearSpinner.setToolTipText("Near clipping plane in physical units");
        farSpinner.setToolTipText("Distant clipping plane in physical units");

        final JPanel cameraPanel = new JPanel(new FlowLayout(FlowLayout.LEADING, 4, dCamSpinner.getFont().getSize()/2));
        cameraPanel.add(new JLabel(IconFactory.buttonIcon('\uf1e5', true)));
        cameraPanel.add(dCamSpinner);
        cameraPanel.add(dCamReset);
        cameraPanel.add(new JLabel(IconFactory.buttonIcon('\ue4b8', true)));
        cameraPanel.add(nearSpinner);
        cameraPanel.add(nearReset);
        cameraPanel.add(new JLabel(IconFactory.buttonIcon('\ue4c2', true)));
        cameraPanel.add(farSpinner);
        cameraPanel.add(farReset);
        return cameraPanel;
    }

}
