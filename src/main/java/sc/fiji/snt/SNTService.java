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

package sc.fiji.snt;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationTargetException;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import org.scijava.Context;
import org.scijava.Priority;
import org.scijava.log.LogService;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.script.ScriptService;
import org.scijava.service.AbstractService;
import org.scijava.service.Service;
import org.scijava.util.FileUtils;

import ij.ImagePlus;
import sc.fiji.snt.analysis.PathProfiler;
import sc.fiji.snt.analysis.SNTTable;
import sc.fiji.snt.analysis.TreeStatistics;
import sc.fiji.snt.analysis.graph.DirectedWeightedGraph;
import sc.fiji.snt.gui.GuiUtils;
import sc.fiji.snt.io.MouseLightLoader;
import sc.fiji.snt.util.ImpUtils;
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
public class SNTService extends AbstractService {

	static { net.imagej.patcher.LegacyInjector.preinit(); } // required for _every_ class that imports ij. classes

	@Parameter
	private ScriptService scriptService;

	@Parameter
	private LogService logService;

	private SNT plugin;


	private void accessActiveInstance(final boolean createInstanceIfNull) {
		plugin = SNTUtils.getInstance();
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
		return SNTUtils.getInstance() != null;
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
			plugin.getDataset());
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
	 * @param imagePath the image to be traced. If starting with "demo:" followed by
	 *                  the name of a demo dataset, SNT is initialized using the
	 *                  corresponding {@link #demoImage(String)} image. If empty or
	 *                  null and SNT's UI is available an "Open" dialog prompt is
	 *                  displayed. URL's supported.
	 * @param startUI   Whether SNT's UI should also be initialized;
	 * @return the SNT instance.
	 */
	public SNT initialize(final String imagePath, final boolean startUI) throws InterruptedException, InvocationTargetException {
		if ("demo".equalsIgnoreCase(imagePath)) { // legacy
			return initialize(demoImage("fractal"), startUI);
		}
		if (imagePath == null || imagePath.isEmpty() && (!startUI || getUI() == null)) {
			throw new IllegalArgumentException("Invalid imagePath " + imagePath);
		}
		if (imagePath.startsWith("demo:")) {
			return initialize(demoImage(imagePath.substring(5).trim()), startUI);
		}
		return initialize(ImpUtils.open(new File(imagePath)), startUI);
	}

	/**
	 * Initializes SNT.
	 *
	 * @param imp the image to be traced (null not allowed)
	 * @param startUI Whether SNT's UI should also be initialized;
	 * @return the SNT instance.
	 */
	public SNT initialize(final ImagePlus imp, final boolean startUI) throws InterruptedException, InvocationTargetException {
		final boolean noInstance = plugin == null;
		if (noInstance)
			plugin = new SNT(getContext(), imp);
		if (!imp.isVisible()) {
			// Then a batch script of sorts is likely running. 
			// Temporarily suppress the 'auto-tracing' prompt
			plugin.getPrefs().setTemp("autotracing-prompt-armed", false);
		}
		if (noInstance) {
			plugin.initialize(true, 1, 1);
		} else {
			plugin.initialize(imp);
		}
		if (startUI && plugin.getUI() == null)
			javax.swing.SwingUtilities.invokeAndWait(() -> plugin.startUI());
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
	 * @throws URISyntaxException 
	 */
	public void loadTracings(String filePathOrURL) throws UnsupportedOperationException, IOException, URISyntaxException {
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
	 * @deprecated Use {@link #getStatistics(boolean)} instead
	 */
	@Deprecated
	public TreeStatistics getAnalyzer(final boolean selectedPathsOnly) {
		return getStatistics(selectedPathsOnly);
	}

	/**
	 * Returns a {@link TreeStatistics} instance constructed from current Paths.
	 *
	 * @param selectedPathsOnly If true only selected paths will be considered
	 * @return the TreeStatistics instance
	 * @throws UnsupportedOperationException if SNT is not running
	 */
	public TreeStatistics getStatistics(final boolean selectedPathsOnly) {
		accessActiveInstance(false);
		final TreeStatistics tStats = new TreeStatistics(getTree(selectedPathsOnly));
		tStats.setContext(getContext());
		tStats.setTable(getTable(), PathManagerUI.TABLE_TITLE);
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
		plugin = SNTUtils.getInstance();
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
		if (SNTUtils.getInstance() == null) {
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
	 *             mouse pyramidal cell (MouseLight's cell AA0001), 'OP1'for the
	 *             DIADEM OP_1 reconstruction, or 'DG' for the dentate gyrus granule cell
	 *             (Neuromorpho's Beining archive)
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
		else if (nTree.contains("dg") || nTree.contains("dentate") || nTree.contains("granule"))
			return getResourceSWCTree("21dpi_contra_infra_01", "tests/21dpi_contra_infra_01.swc");
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
			if (pafm.importSWC(br, treeLabel, false, 0, 0, 0, 1, 1, 1, 1, false)) {
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
		} finally {
			pafm.dispose();
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
	 *            'cil810' for the respective Cell Image Library entries, and
	 *            'binary timelapse' for a small 4-frame sequence of neurite growth
	 * @return the demo image, or null if data could no be retrieved
	 * @see #demoTree(String)
	 */
	public ImagePlus demoImage(final String img) {
		return ImpUtils.demo(img);
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
		return MouseLightLoader.demoTrees();
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
	 * Quits SNT. Does nothing if SNT is currently not running.
	 */
	@Override
	public void dispose() {
		if (getInstanceViewer() != null) getInstanceViewer().dispose();
		if (plugin == null) return;
		if (getUI() == null) {
			try {
				SNTUtils.log("Disposing resources..");
				plugin.dispose();
				if (plugin.getImagePlus() != null) plugin.getImagePlus().close();
				plugin = null;
			} catch (final NullPointerException ignored) {
				// do nothing
			}
		} else {
			getUI().exitRequested();
		}
	}

	@Override
	public Context getContext() {
		return (super.getContext() == null) ? SNTUtils.getContext() : super.getContext();
	}

}
