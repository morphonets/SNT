/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2023 Fiji developers.
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

package sc.fiji.snt;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import org.scijava.Priority;
import org.scijava.log.LogService;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.script.ScriptService;
import org.scijava.service.AbstractService;
import org.scijava.service.Service;
import org.scijava.util.ColorRGB;
import org.scijava.util.FileUtils;

import ij.CompositeImage;
import ij.IJ;
import ij.ImagePlus;
import ij.gui.NewImage;
import ij.io.Opener;
import ij.plugin.ZProjector;
import ij.process.ColorProcessor;
import net.imagej.ImageJService;
import sc.fiji.snt.analysis.PathProfiler;
import sc.fiji.snt.analysis.SNTTable;
import sc.fiji.snt.analysis.TreeAnalyzer;
import sc.fiji.snt.analysis.TreeStatistics;
import sc.fiji.snt.analysis.graph.DirectedWeightedGraph;
import sc.fiji.snt.event.SNTEvent;
import sc.fiji.snt.gui.GuiUtils;
import sc.fiji.snt.hyperpanes.MultiDThreePanes;
import sc.fiji.snt.io.MouseLightLoader;
import sc.fiji.snt.util.SWCPoint;
import sc.fiji.snt.viewer.Viewer3D;

/**
 * Service for accessing and scripting the active instance of
 * {@link SNT}.
 *
 * @author Tiago Ferreira
 * @author Kyle Harrington
 *
 */
@Plugin(type = Service.class, priority = Priority.NORMAL)
public class SNTService extends AbstractService implements ImageJService {

	@Parameter
	private ScriptService scriptService;

	@Parameter
	private LogService logService;

	private SNT plugin;


	private void accessActiveInstance(final boolean createInstanceIfNull) {
		plugin = SNTUtils.getPluginInstance();
		if (createInstanceIfNull && plugin == null) {
			plugin = new SNT(getContext(), new PathAndFillManager());
		} else if (plugin == null) {
			throw new UnsupportedOperationException("SNT is not running");
		}
	}

	// @Override
	// public void initialize() {
	// scriptService.addAlias(this.getClass());
	// }

	/**
	 * Gets whether SNT is running.
	 *
	 * @return true if this {@code SNTService} is active, tied to the active
	 *         instance of SNT
	 */
	public boolean isActive() {
		return SNTUtils.getPluginInstance() != null;
	}

	/**
	 * Assigns pixel intensities at each Path node, storing them as Path values.
	 * Assigned intensities are those of the channel and time point currently being
	 * traced. Assumes SNT has been initialized with a valid image.
	 *
	 * @param selectedPathsOnly If true, only selected paths will be assigned
	 *                          values, otherwise voxel intensities will be assigned
	 *                          to all paths
	 * @throws UnsupportedOperationException if SNT is not running
	 * @throws IllegalArgumentException      If valid imaged data is not available
	 * @see PathProfiler
	 * @see Path#setNodeValues(double[])
	 */
	public void assignValues(final boolean selectedPathsOnly) throws UnsupportedOperationException, IllegalArgumentException{
		accessActiveInstance(false);
		if (!plugin.accessToValidImageData()) {
			throw new IllegalArgumentException("Valid image data is not available");
		}
		final PathProfiler profiler = new PathProfiler(getTree(selectedPathsOnly),
			plugin.getLoadedDataAsImp());
		profiler.assignValues();
	}

	/**
	 * Initializes SNT. Since no image is specified, tracing functions are disabled.
	 *
	 * @param startUI Whether SNT's UI should also be initialized;
	 * @return the SNT instance.
	 */
	public SNT initialize(final boolean startUI) {
		accessActiveInstance(true);
		if (startUI && plugin.getUI() == null) {
			plugin.initialize(true, 1, 1);
			plugin.startUI();
		}
		return plugin;
	}

	/**
	 * Initializes SNT.
	 *
	 * @param imagePath the image to be traced. If "demo" (case-insensitive), SNT is
	 *                  initialized using the {@link #demoTreeImage}. If empty or
	 *                  null and SNT's UI is available an "Open" dialog prompt is
	 *                  displayed. URL's supported.
	 * @param startUI   Whether SNT's UI should also be initialized;
	 * @return the SNT instance.
	 */
	public SNT initialize(final String imagePath, final boolean startUI) {
		if ("demo".equalsIgnoreCase(imagePath)) {
			return initialize(demoTreeImage(), startUI);
		}
		if (imagePath == null || imagePath.isEmpty() && (!startUI || getUI() == null)) {
			throw new IllegalArgumentException("Invalid imagePath " + imagePath);
		}
		return initialize(IJ.openImage(imagePath), startUI);
	}

	/**
	 * Initializes SNT.
	 *
	 * @param imp the image to be traced (null not allowed)
	 * @param startUI Whether SNT's UI should also be initialized;
	 * @return the SNT instance.
	 */
	public SNT initialize(final ImagePlus imp, final boolean startUI) {
		if (plugin == null) {
			plugin = new SNT(getContext(), imp);
			plugin.initialize(true, 1, 1);
		} else {
			plugin.initialize(imp);
		}
		if (startUI && plugin.getUI() == null) plugin.startUI();
		return plugin;
	}

	/**
	 * @deprecated use {@link #getInstance()} instead
	 */
	@Deprecated
	public SNT getPlugin() {
		return getInstance();
	}

	/**
	 * Returns a reference to the active {@link SNT} instance.
	 *
	 * @return the {@link SNT} instance
	 */
	public SNT getInstance() {
		accessActiveInstance(true);
		return plugin;
	}

	/**
	 * Loads the specified tracings file.
	 *
	 * @param filePathOrURL either a "SWC", "TRACES" or "JSON" file path. URLs
	 *                      defining remote files also supported. Null not allowed.
	 * @throws UnsupportedOperationException if SNT is not running
	 * @throws IOException                   if data cannot be imported
	 */
	public void loadTracings(String filePathOrURL) throws UnsupportedOperationException, IOException {
		accessActiveInstance(false);
		plugin.getPathAndFillManager().loadGuessingType(filePathOrURL);
	}

	/**
	 * Loads the specified tree. Note that if SNT has not been properly initialized,
	 * spatial calibration mismatches may occur. In that case, assign the spatial
	 * calibration of the image to {#@code Tree} using
	 * {@link Tree#assignImage(ImagePlus)}, before loading it.
	 *
	 * @param tree the {@link Tree} to be loaded (null not allowed).
	 * @throws UnsupportedOperationException if SNT is not running
	 */
	public void loadTree(final Tree tree) throws UnsupportedOperationException {
		accessActiveInstance(false);
		plugin.getPathAndFillManager().addTree(tree);
	}


	public void loadGraph(final DirectedWeightedGraph graph) throws UnsupportedOperationException {
		accessActiveInstance(false);
		final Map<String, TreeSet<SWCPoint>> inMap = new HashMap<>();
		graph.updateVertexProperties();
		inMap.put("graph", new TreeSet<>(graph.vertexSet()));
		plugin.getPathAndFillManager().importNeurons(inMap, null, "");
	}

	/**
	 * Saves all the existing paths to a file.
	 *
	 * @param filePath the saving output file path. If {@code filePath} ends in
	 *                 ".swc" (case-insensitive), an SWC file is created, otherwise
	 *                 a "traces" file is created. If empty and a GUI exists, a save
	 *                 prompt is displayed.
	 * @return true, if paths exist and file was successfully written.
	 * @throws UnsupportedOperationException if SNT is not running
	 */
	public boolean save(final String filePath) {
		accessActiveInstance(false);
		if (getPathAndFillManager().size() == 0)
			return false;
		File saveFile;
		if (filePath == null || filePath.trim().isEmpty() && getUI() != null) {
			saveFile = getUI().saveFile("Save As Traces...", null, "traces");
		} else {
			saveFile = new File(filePath);
		}
		if (saveFile == null)
			return false;
		final boolean asSWC = "swc".equalsIgnoreCase(FileUtils.getExtension(saveFile));
		if (getUI() != null) {
			if (asSWC) {
				return getUI().saveAllPathsToSwc(saveFile.getAbsolutePath());
			}
			getUI().saveToXML(saveFile);
		} else {
			if (asSWC) {
				return getPathAndFillManager().exportAllPathsAsSWC(SNTUtils.stripExtension(saveFile.getAbsolutePath()));
			}
			try {
				getPathAndFillManager().writeXML(saveFile.getAbsolutePath(),
						plugin.getPrefs().isSaveCompressedTraces());
			} catch (final IOException ioe) {
				ioe.printStackTrace();
				return false;
			}
		}
		return true;
	}

	/**
	 * Gets the paths currently selected in the Path Manager list.
	 *
	 * @return the paths currently selected, or null if no selection exists
	 * @throws UnsupportedOperationException if SNT is not running
	 * @see #getTree(boolean)
	 */
	public Collection<Path> getSelectedPaths() {
		accessActiveInstance(false);
		return plugin.getSelectedPaths();
	}

	/**
	 * Gets the paths currently listed in the Path Manager
	 *
	 * @return all the listed paths, or null if the Path Manager is empty
	 * @throws UnsupportedOperationException if SNT is not running
	 * @see #getTree(boolean)
	 */
	public List<Path> getPaths() {
		accessActiveInstance(false);
		return plugin.getPathAndFillManager().getPathsFiltered();
	}

	/**
	 * Gets the collection of paths listed in the Path Manager as a {@link Tree}
	 * object.
	 *
	 * @param selectedPathsOnly If true, only selected paths are retrieved
	 * @return the Tree holding the Path collection
	 * @throws UnsupportedOperationException if SNT is not running
	 */
	public Tree getTree(final boolean selectedPathsOnly) {
		final Tree tree = new Tree((selectedPathsOnly) ? getSelectedPaths()
			: getPaths());
		tree.setLabel((selectedPathsOnly) ? "Selected Paths" : "All Paths");
		return tree;
	}

	/**
	 * Gets the collection of paths listed in the Path Manager as a {@link Tree}
	 * object.
	 *
	 * @return the Tree holding the Path collection
	 * @throws UnsupportedOperationException if SNT is not running
	 */
	public Tree getTree() {
		return getTree(false);
	}

	/**
	 * Returns a {@link TreeAnalyzer} instance constructed from current Paths.
	 *
	 * @param selectedPathsOnly If true only selected paths will be considered
	 * @return the TreeAnalyzer instance
	 * @throws UnsupportedOperationException if SNT is not running
	 */
	public TreeAnalyzer getAnalyzer(final boolean selectedPathsOnly) {
		accessActiveInstance(false);
		final TreeAnalyzer tAnalyzer = new TreeAnalyzer(getTree(selectedPathsOnly));
		tAnalyzer.setContext(getContext());
		tAnalyzer.setTable(getTable(), PathManagerUI.TABLE_TITLE);
		return tAnalyzer;
	}

	/**
	 * Returns a {@link TreeStatistics} instance constructed from current Paths.
	 *
	 * @param selectedPathsOnly If true only selected paths will be considered
	 * @return the TreeStatistics instance
	 * @throws UnsupportedOperationException if SNT is not running
	 */
	public TreeStatistics getStatistics(final boolean selectedPathsOnly) {
		final TreeStatistics tStats = new TreeStatistics(getTree(
			selectedPathsOnly));
		tStats.setContext(getContext());
		return tStats;
	}

	/**
	 * Returns the {@link PathAndFillManager} associated with the current SNT
	 * instance.
	 *
	 * @return the PathAndFillManager instance
	 * @throws UnsupportedOperationException if no SNT instance exists.
	 */
	public PathAndFillManager getPathAndFillManager() {
		accessActiveInstance(false);
		return plugin.getPathAndFillManager();
	}

	/**
	 * Script-friendly method for updating (refreshing) all viewers currently in use
	 * by SNT. Does nothing if no SNT instance exists.
	 */
	public void updateViewers() {
		accessActiveInstance(false);
		if (plugin != null) plugin.updateAllViewers();
	}

	/**
	 * Returns a reference to SNT's UI.
	 *
	 * @return the {@link SNTUI} window, or null if SNT is not running, or is
	 *         running without GUI
	 */
	public SNTUI getUI() {
		plugin = SNTUtils.getPluginInstance();
		return (plugin==null) ? null : plugin.getUI();
	}

	/**
	 * Returns a reference to the active Reconstruction Viewer (either stand-alone
	 * or SNT-associated instance). A new instance is retrieved if none exists.
	 *
	 * @return The active {@link Viewer3D} instance. For stand-alone viewers, this
	 *         is typically the viewer that is frontmost or the last initiated viewer.
	 */
	public Viewer3D getRecViewer() {
		if (getUI() != null) {
			return getUI().getReconstructionViewer(true);
		}
		if (plugin != null) {
			final Viewer3D viewer = getInstanceViewer();
			if (viewer == null) {
				class SNTViewer3D extends Viewer3D {
					private SNTViewer3D() {
						super(plugin);
					}

					@Override
					public void dispose() {
						super.dispose();
						if (getUI() != null) getUI().setReconstructionViewer(null);
					}
				}
				return new SNTViewer3D();
			}
			return viewer;
		}
		final HashMap<Integer, Viewer3D> viewerMap = SNTUtils.getViewers();
		if (viewerMap == null || viewerMap.isEmpty()) {
			return newRecViewer(true);
		}
		for (final Viewer3D viewer : viewerMap.values()) {
			if (viewer.isActive()) return viewer;
		}
		return viewerMap.get(Collections.max(viewerMap.keySet()));
	}

	private Viewer3D getInstanceViewer() {
		final HashMap<Integer, Viewer3D> viewerMap = SNTUtils.getViewers();
		if (viewerMap == null || viewerMap.isEmpty()) {
			return null;
		}
		for (final Viewer3D viewer : viewerMap.values()) {
			if (viewer.isSNTInstance()) {
				return viewer;
			}
		}
		return null;
	}

	/**
	 * Returns a reference to an opened Reconstruction Viewer (standalone instance).	 *
	 *
	 * @param id the unique numeric ID of the Reconstruction Viewer to be retrieved
	 *           (as used by the "Script This Viewer" command, and typically
	 *           displayed in the Viewer's window title)
	 * @return The standalone {@link Viewer3D} instance, or null if id was not
	 *         recognized
	 */
	public Viewer3D getRecViewer(final int id) {
		final HashMap<Integer, Viewer3D> viewerMap = SNTUtils.getViewers();
		return (viewerMap == null || viewerMap.isEmpty()) ? null : viewerMap.get(id);
	}

	/**
	 * Instantiates a new standalone Reconstruction Viewer.
	 *
	 * @return The standalone {@link Viewer3D} instance
	 */
	public Viewer3D newRecViewer(final boolean guiControls) {
		return (guiControls) ? new Viewer3D(getContext()) : new Viewer3D();
	}

	public SciViewSNT getOrCreateSciViewSNT() throws NoClassDefFoundError {
		if (SNTUtils.getPluginInstance() == null) {
			return new SciViewSNT(getContext());
		}
		return getSciViewSNT();
	}

	public SciViewSNT getSciViewSNT() throws UnsupportedOperationException, NoClassDefFoundError {
		accessActiveInstance(false);
		if (getUI() != null && getUI().sciViewSNT != null)
			return getUI().sciViewSNT;
		return new SciViewSNT(plugin);
	}

	/**
	 * Returns a reference to SNT's main table of measurements.
	 *
	 * @return SNT measurements table (a {@code DefaultGenericTable})
	 * @throws UnsupportedOperationException if SNT is not running
	 */
	public SNTTable getTable() {
		accessActiveInstance(false);
		return (getUI() == null) ? null : getUI().getPathManager().getTable();
	}

	/**
	 * Returns a toy reconstruction (fractal tree).
	 *
	 * @return a reference to the loaded tree, or null if data could not be retrieved
	 * @see #demoTreeImage()
	 */
	@Deprecated
	public Tree demoTree() {
		return getResourceSWCTree("TreeV", "tests/TreeV.swc");
	}

	/**
	 * Returns a demo tree.
	 *
	 * @param tree a string describing the type of demo tree. Either 'fractal' for
	 *             the L-system toy neuron, 'pyramidal' for the dendritic arbor of
	 *             mouse pyramidal cell (MouseLight's cell AA0001), or 'OP1'for the
	 *             DIADEM OP_1 reconstruction.
	 * @see #demoImage(String)
	 * @see #demoTrees()
	 */
	public Tree demoTree(final String tree) {
		if (tree == null)
			return getResourceSWCTree("TreeV", "tests/TreeV.swc");
		final String nTree = tree.toLowerCase().trim();
		if (nTree.contains("fractal") || nTree.contains("toy") || nTree.contains("l-sys"))
			return getResourceSWCTree("TreeV", "tests/TreeV.swc");
		else if (nTree.contains("op") || nTree.contains("olfactory projection") || nTree.contains("diadem"))
			return getResourceSWCTree("OP_1", "tests/OP_1-gs.swc");
		else
			return getResourceSWCTree("AA0001", "ml/demo-trees/AA0001.swc");
	}

	private Tree getResourceSWCTree(final String treeLabel, final String resourcePath) {
		final ClassLoader classloader = Thread.currentThread().getContextClassLoader();
		final InputStream is = classloader.getResourceAsStream(resourcePath);
		final PathAndFillManager pafm = new PathAndFillManager(1, 1, 1, GuiUtils.micrometer());
		pafm.setHeadless(true);
		Tree tree;
		try {
			final int idx1stPath = pafm.size();
			final BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
			if (pafm.importSWC(br, treeLabel, false, 0, 0, 0, 1, 1, 1, false)) {
				tree = new Tree();
				for (int i = idx1stPath; i < pafm.size(); i++) {
					final Path p = pafm.getPath(i);
					tree.add(p);
				}
				tree.setLabel(treeLabel);
				tree.getProperties().setProperty(Tree.KEY_SPATIAL_UNIT, GuiUtils.micrometer());
				tree.getProperties().setProperty(Tree.KEY_SOURCE, "SNT Demo");
			} else {
				return null;
			}
			br.close();
		} catch (final IOException e) {
			tree = null;
			SNTUtils.error("UnsupportedEncodingException", e);
		}
		return tree;
	}

	/**
	 * Returns the image associated with the demo (fractal) tree.
	 *
	 * @return a reference to the image tree, or null if data could not be retrieved
	 * @see #demoTree()
	 */
	@Deprecated
	public ImagePlus demoTreeImage() {
		return demoImage("fractal");
	}

	/**
	 * Returns one of the demo images bundled with SNT image associated with the
	 * demo (fractal) tree.
	 *
	 * @param img a string describing the type of demo image. Options include:
	 *            'fractal' for the L-system toy neuron; 'ddaC' for the C4 ddaC
	 *            drosophila neuron (demo image initially distributed with the Sholl
	 *            plugin); 'OP1'/'OP_1' for the DIADEM OP_1 dataset; 'cil701' and
	 *            'cil810' for the respective Cell Image Library entries
	 * @return the demo image, or null if data could no be retrieved
	 * @see #demoTree(String)
	 */
	public ImagePlus demoImage(final String img) {
		if (img == null)
			return demoImageInternal("tests/TreeV.tif", "TreeV.tif");
		final String nImg = img.toLowerCase().trim();
		if (nImg.contains("dda") || nImg.contains("c4") || nImg.contains("sholl")) {
			return demoImageInternal("tests/ddaC.tif", "Drosophila_ddaC_Neuron.tif");
		} else if (nImg.contains("op")) {
			return ij.IJ.openImage(
					"https://github.com/morphonets/SNT/raw/0b3451b8e62464a270c9aab372b4f651c4cf9af7/src/test/resources/OP_1.tif");
		} else if (nImg.equalsIgnoreCase("rat_hippocampal_neuron") || (nImg.contains("hip") && nImg.contains("multichannel"))) {
			return ij.IJ.openImage("http://wsr.imagej.net/images/Rat_Hippocampal_Neuron.zip");
		} else if (nImg.contains("4d") || nImg.contains("timelapse") || nImg.contains("701")) {
			return cil701();
		} else if (nImg.contains("multipolar") || nImg.contains("810")) {
			return cil810();
		}
		return demoImageInternal("tests/TreeV.tif", "TreeV.tif");
	}

	private ImagePlus cil701() {
		final ImagePlus imp = IJ.openImage("https://cildata.crbs.ucsd.edu/media/images/701/701.tif");
		if (imp != null) {
			imp.setDimensions(1, 1, imp.getNSlices());
			imp.getCalibration().setUnit("um");
			imp.getCalibration().pixelWidth = 0.169;
			imp.getCalibration().pixelHeight = 0.169;
			imp.getCalibration().frameInterval = 3000;
			imp.getCalibration().setTimeUnit("s");
			imp.setTitle("CIL_Dataset_#701.tif");
		}
		return imp;
	}

	private ImagePlus cil810() {
		ImagePlus imp = IJ.openImage("https://cildata.crbs.ucsd.edu/media/images/810/810.tif");
		if (imp != null) {
			imp.setDimensions(imp.getNSlices(), 1, 1);
			imp.getStack().setSliceLabel("N-cadherin", 1);
			imp.getStack().setSliceLabel("V-glut 1/2", 2);
			imp.getStack().setSliceLabel("NMDAR", 3);
			imp.getCalibration().setUnit("um");
			imp.getCalibration().pixelWidth = 0.113;
			imp.getCalibration().pixelHeight = 0.113;
			imp.setTitle("CIL_Dataset_#810.tif");
			imp = new CompositeImage(imp, CompositeImage.COMPOSITE);
		}
		return imp;
	}

	private ImagePlus demoImageInternal(final String path, final String displayTitle) {
		final ClassLoader classloader = Thread.currentThread().getContextClassLoader();
		final InputStream is = classloader.getResourceAsStream(path);
		final boolean redirecting = IJ.redirectingErrorMessages();
		IJ.redirectErrorMessages(true);
		final ImagePlus imp = new Opener().openTiff(is, displayTitle);
		IJ.redirectErrorMessages(redirecting);
		return imp;
	}

	/**
	 * Returns a collection of four demo reconstructions (dendrites from pyramidal
	 * cells from the MouseLight database). NB: Data is cached locally. No internet
	 * connection required.
	 *
	 * @return the list of {@link Tree}s, corresponding to the dendritic arbors of
	 *         cells "AA0001", "AA0002", "AA0003", "AA0004" (MouseLight database
	 *         IDs).
	 */
	public List<Tree> demoTrees() {
		final ClassLoader classloader = Thread.currentThread().getContextClassLoader();
		final InputStream is = classloader.getResourceAsStream("ml/demo-trees/AA0001-4.json");
		final Map<String, Tree> result = MouseLightLoader.extractTrees(is, "dendrites");
		if (result.values().stream().anyMatch(tree -> tree == null || tree.isEmpty())) {
			return null;
		}
		return new ArrayList<>(result.values());
	}

	protected List<Tree> demoTreesSWC() {
		final String[] cells = {"AA0001", "AA0002", "AA0003", "AA0004"};
		final List<Tree> trees = new ArrayList<>();
		for (final String cell : cells) {
			final Tree tree = getResourceSWCTree(cell, "ml/demo-trees/" + cell + ".swc");
			if (tree != null) tree.setLabel(cell);
			trees.add(tree);
		}
		return trees;
	}

	/**
	 * Retrieves a WYSIWYG 'snapshot' of a tracing canvas.
	 *
	 * @param view A case-insensitive string specifying the canvas to be captured.
	 *          Either "xy" (or "main"), "xz", "zy" or "3d" (for legacy's 3D
	 *          Viewer).
	 * @param project whether the snapshot of 3D image stacks should include its
	 *          projection (MIP), or just the current plane
	 * @return the snapshot capture of the canvas as an RGB image
	 * @throws UnsupportedOperationException if SNT is not running
	 * @throws IllegalArgumentException if view is not a recognized option
	 */
	@SuppressWarnings("deprecation")
	public ImagePlus captureView(final String view, final boolean project) {
		accessActiveInstance(false);
		if (view == null || view.trim().isEmpty())
			throw new IllegalArgumentException("Invalid view");

		if (view.toLowerCase().contains("3d")) {
			if (plugin.get3DUniverse() == null || plugin.get3DUniverse().getWindow() == null)
				throw new IllegalArgumentException("Legacy 3D viewer is not available");
			//plugin.get3DUniverse().getWindow().setBackground(background);
			return plugin.get3DUniverse().takeSnapshot();
		}

		final int viewPlane = getView(view);
		final ImagePlus imp = plugin.getImagePlus(viewPlane);
		if (imp == null) throw new IllegalArgumentException(
			"view is not available");

		ImagePlus holdingView;
		if (plugin.accessToValidImageData()) {
			holdingView = ZProjector
					.run(imp, "max", (project) ? 1 : imp.getZ(), (project) ? imp.getNSlices() : imp.getZ()).flatten();
		} else {
			holdingView = NewImage.createByteImage("Holding view", imp.getWidth(), imp.getHeight(), 1, NewImage.FILL_BLACK);
		}
		holdingView.copyScale(imp);
		return captureView(holdingView, view, viewPlane);
	}

	/**
	 * Retrieves a WYSIWYG 'snapshot' of a tracing canvas without voxel data.
	 *
	 * @param view            A case-insensitive string specifying the canvas to be
	 *                        captured. Either "xy" (or "main"), "xz", "zy" or "3d"
	 *                        (for legacy's 3D Viewer).
	 * @param backgroundColor the background color of the canvas (string, hex, or
	 *                        html)
	 * @return the snapshot capture of the canvas as an RGB image
	 * @throws UnsupportedOperationException if SNT is not running
	 * @throws IllegalArgumentException      if {@code view} or
	 *                                       {@code backgroundColor} are not
	 *                                       recognized
	 */
	@SuppressWarnings("deprecation")
	public ImagePlus captureView(final String view, final ColorRGB backgroundColor) throws IllegalArgumentException {
		accessActiveInstance(false);
		if (view == null || view.trim().isEmpty())
			throw new IllegalArgumentException("Invalid view");
		if (backgroundColor == null)
			throw new IllegalArgumentException("Invalid backgroundColor");

		final Color backgroundColorAWT = new Color(backgroundColor.getRed(), backgroundColor.getGreen(),
				backgroundColor.getBlue(), 255);
		if (view.toLowerCase().contains("3d")) {
			if (plugin.get3DUniverse() == null || plugin.get3DUniverse().getWindow() == null)
				throw new IllegalArgumentException("Legacy 3D viewer is not available");
			final Color existingBackground = plugin.get3DUniverse().getWindow().getBackground();
			plugin.get3DUniverse().getWindow().setBackground(backgroundColorAWT);
			final ImagePlus imp = plugin.get3DUniverse().takeSnapshot();
			plugin.get3DUniverse().getWindow().setBackground(existingBackground);
			return imp;
		}

		final int viewPlane = getView(view);
		final ImagePlus imp = plugin.getImagePlus(viewPlane);
		if (imp == null) throw new IllegalArgumentException(
			"view is not available");
		final ColorProcessor ip = new ColorProcessor(imp.getWidth(), imp.getHeight());
		ip.setColor(backgroundColorAWT);
		ip.fill();
		final ImagePlus holdingView = new ImagePlus("Holder", ip);
		holdingView.copyScale(imp);
		return captureView(holdingView, view, viewPlane);
	}

	private ImagePlus captureView(final ImagePlus holdingImp, final String viewDescription, final int viewPlane) {
		// NB: overlay will be flattened but not active ROI
		final TracerCanvas canvas = new TracerCanvas(holdingImp, plugin, viewPlane, plugin
			.getPathAndFillManager());
		if (plugin.getXYCanvas() != null)
			canvas.setNodeDiameter(plugin.getXYCanvas().nodeDiameter());
		final BufferedImage bi = new BufferedImage(holdingImp.getWidth(), holdingImp
			.getHeight(), BufferedImage.TYPE_INT_ARGB);
		final Graphics2D g = canvas.getGraphics2D(bi.getGraphics());
		g.drawImage(holdingImp.getImage(), 0, 0, null);
		for (final Path p : getPaths()) {
			p.drawPathAsPoints(g, canvas, plugin);
		}
		// this is taken from ImagePlus.flatten()
		final ImagePlus result = new ImagePlus(viewDescription + " view snapshot",
			new ColorProcessor(bi));
		result.copyScale(holdingImp);
		result.setProperty("Info", holdingImp.getProperty("Info"));
		return result;
	}

	private int getView(final String view) {
		switch (view.toLowerCase()) {
			case "xy":
			case "main":
				return MultiDThreePanes.XY_PLANE;
			case "xz":
				return MultiDThreePanes.XZ_PLANE;
			case "zy":
				return MultiDThreePanes.ZY_PLANE;
			default:
				throw new IllegalArgumentException("Unrecognized view");
		}
	}

	/**
	 * Quits SNT. Does nothing if SNT is currently not running.
	 */
	@Override
	public void dispose() {
		if (plugin == null) return;
		if (getUI() == null) {
			try {
			SNTUtils.log("Disposing resources..");
			plugin.cancelSearch(true);
			plugin.notifyListeners(new SNTEvent(SNTEvent.QUIT));
			plugin.getPrefs().savePluginPrefs(true);
			if (getInstanceViewer() != null) getRecViewer().dispose();
			plugin.closeAndResetAllPanes();
			if (plugin.getImagePlus() != null) plugin.getImagePlus().close();
			SNTUtils.setPlugin(null);
			SNTUtils.setContext(null);
			plugin = null;
			} catch (final NullPointerException ignored) {
				// do nothing
			}
		} else {
			getUI().exitRequested();
		}
	}

}
