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

import ij.ImagePlus;
import org.scijava.Context;
import org.scijava.command.CommandService;
import org.scijava.plugin.Parameter;
import sc.fiji.snt.*;
import sc.fiji.snt.gui.cmds.SpotSpineLoaderCmd;
import sc.fiji.snt.util.BoundingBox;
import sc.fiji.snt.util.SNTPoint;
import sc.fiji.snt.viewer.AbstractBigViewer;
import sc.fiji.snt.viewer.Bvv;
import sc.fiji.snt.util.ImpUtils;

import javax.swing.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.Consumer;
import java.util.Scanner;

/**
 * Manages loading and running of demonstration datasets and reconstructions in SNT.
 * Provides a collection of predefined demo entries with associated images, reconstructions,
 * and metadata for educational and testing purposes.
 */
public class DemoRunner {

	static { net.imagej.patcher.LegacyInjector.preinit(); } // required for _every_ class that imports ij. classes

	private final SNTUI ui;
	private final SNT snt;
	private final SNTPrefs prefs;
	private final List<Demo> entries;
	private final int priorUIState;

	@Parameter
	private SNTService sntService;
	private boolean directLoading;

	public DemoRunner(final SNTUI ui, final SNT snt) {
		this.ui = ui;
		this.snt = snt;
		prefs = snt.getPrefs();
		snt.getContext().inject(this);
		priorUIState = ui.getState();
		ui.changeState(SNTUI.LOADING);
		ui.showStatus("Retrieving Demo data. Please wait...", false);
		final List<Demo> unsorted = new ArrayList<>(List.of(demo01(), demo02(), demo03(), demo04(), demo05(),
				demo06(), demo07(), demo08(), demo09(), demo10(), demo11(), demo12(), demo13(), demo14(), demo15()));
		// grouped by Category for display/discovery; each Demo's own id (below) never changes with this
		// order, so reshuffling categories/adding demos can never renumber (or break macros recorded
		// against) an existing one
		unsorted.sort(Comparator.comparingInt((Demo d) -> d.category.ordinal()).thenComparingInt(d -> d.id));
		entries = List.copyOf(unsorted);
	}

	public DemoRunner(final Context context) {
		this.ui = null;
		this.snt = null;
		prefs = null;
		context.inject(this);
		priorUIState = -1;
		entries = List.of(demo02(), demo04(), demo10());
	}

	private Demo demo01() {
		final Demo entry = new Demo(1, "Brainbow Zebrafish Larva (2D Image)") {
			@Override
			public ImagePlus getImage() {
				return ImpUtils.demo("brainbow");
			}
		};
		entry.summary = "Downloads a 'Brainbow' zebrafish larva (Danio rerio, 5 dpf). No reconstructions included.";
		entry.data = "Image (2D; 3-channel confocal image, 5.5MB)";
		entry.source = "Cell Image Library, doi:10.7295/W9CIL41458";
		entry.online = true;
		entry.keywords = List.of("multicolor", "spectral refinement");
		entry.category = Category.IMAGE_ONLY;
		return entry;
	}

	private Demo demo02() {
		final Demo entry = new Demo(2, "DG Granule Cell (3D Reconstruction)") {
			{ hasImage = false; hasTree = true; }
			@Override
			public ImagePlus getImage() {
				return null;
			}

			@Override
			public void load() {
				if (!prepNonImgLoading())
					exit();
				try {
					assert snt != null;
					snt.getPathAndFillManager().addTree(getTree());
					snt.setSinglePane(true);
					snt.rebuildDisplayCanvases();
					snt.updateAllViewers();
				} catch (final Throwable ex) {
					error(ex);
				} finally {
					exit();
				}
			}

			@Override
			public Tree getTree() {
				return sntService.demoTree("DG");
			}

			@Override
			public List<Tree> getTrees() {
				return List.of(getTree());
			}

		};
		entry.summary = "Reconstructed dendrites of an adult born dentate granule cell in the hippocampal dentate gyrus (rat).";
		entry.data = "SWC (92KB)";
		entry.source = """
				Beining et al. 2017 (PMID 27514866),
				NeuroMorpho.org ID: 21dpi_contra_infra_01, Source version (Beining archive)""";
		entry.online = false;
		entry.keywords = List.of("Dentate gyrus", "root angle analysis");
		entry.category = Category.RECONSTRUCTION;
		return entry;
	}

	private Demo demo03() {
		final Demo entry = new Demo(3, "Drosophila ddaC Neuron (Autotrace Demo)") {
			@Override
			public ImagePlus getImage() {
				final ImagePlus imp = ImpUtils.demo("ddaC");
				if (imp != null)
					imp.setRoi(322, 383, 21, 24); // mark soma
				return imp;
			}

			@Override
			public void load() {
				super.load();
				if (!imageLoaded) return;
				snt.getPrefs().setTemp("demo-running", true);
				ui.changeState(SNTUI.RUNNING_CMD);
				setNotes("""
						The Drosophila ddaC neuron autotrace demo
						runs _Auto-trace → Segmented Image..._
						with these parameters:

						```
						Intensity img:           None
						Roi strategy:            ROI edge
						Loop strategy:           Peripheral seg.
						Prune small components:  Yes, < 3µm
						Bridge gaps:             Yes, within 6µm
						Prune single-node paths: Yes
						```

						Tip: Once tracing completes, press 'H'
						     to toggle paths visibility; '1'
						     to display only selected paths
						""");
				runDemoScript("demo_ddaC_autotrace.groovy");
			}
		};
		entry.summary = "Loads a binary (thresholded) image of a Drosophila space-filling neuron (ddaC) and "
				+ "displays autotracing options for automated reconstruction.";
		entry.data = "Image (2D mask, 581KB)";
		entry.online = false;
		entry.source = "PMID 24449841";
		entry.keywords = List.of("space-filling", "tracing-free", "confocal", "projection", "Sholl", "auto-tracing");
		entry.category = Category.TUTORIAL_SCRIPT;
		return entry;
	}

	private Demo demo04() {
		final Demo entry = new Demo(4, "Drosophila OP Neuron (Complete 3D Dataset)") {
			{ hasTree = true; }
			@Override
			public ImagePlus getImage() {
				final ImagePlus imp = ImpUtils.demo("OP_1");
				tagForQuickDisposal(imp);
				return imp;
			}

			@Override
			public Tree getTree() {
				return sntService.demoTree("OP_1");
			}

			@Override
			public List<Tree> getTrees() {
				return List.of(getTree());
			}

		};
		entry.summary = "Downloads a Drosophila olfactory projection neuron and respective ground truth 3D reconstruction (radii included).";
		entry.data = "Image (3D; 1-channel confocal image, 15MB) and SWC reconstruction (78KB)";
		entry.source = "DIADEM dataset, PMID 17382886";
		// entry.tracingsURL =
		// "https://raw.githubusercontent.com/morphonets/SNT/0b3451b8e62464a270c9aab372b4f651c4cf9af7/src/test/resources/OP_1-gs.swc";
		entry.online = true;
		entry.keywords = List.of("DIADEM", "confocal", "antennal lobe", "PNs", "auto-tracing");
		entry.category = Category.RECONSTRUCTION;
		return entry;
	}

	private Demo demo05() {
		final Demo entry = new Demo(5, "Hippocampal Neuron (DIC Timelapse)") {
			{ hasTree = true; }
			@Override
			public ImagePlus getImage() {
				final ImagePlus imp = ImpUtils.demo("cil701");
				tagForQuickDisposal(imp);
				return imp;
			}
		};
		entry.summary = "Downloads a timelapse video (19h) of a cultured hippocampal neuron in which neurites have been traced across time. " +
                "Suitable for testing Growth Analysis options";
		entry.data = "Image (2D; timelapse image, 52MB) and tracings (420KB)";
		entry.source = "Cell Image Library, doi:10.7295/W9CIL701";
		entry.online = true;
		entry.tracingsURL = "https://raw.githubusercontent.com/morphonets/misc/00369266e14f1a1ff333f99f0f72ef64077270da/dataset-demos/CIL_Dataset_%23701.traces";
		entry.keywords = List.of("In vitro", "brightfield", "unlabeled", "growth analysis");
		entry.category = Category.GROWTH_ANALYSIS;
		return entry;
	}

	private Demo demo06() {
		final Demo entry = new Demo(6, "Hippocampal Neuron (Neuronal receptors)") {
			@Override
			public ImagePlus getImage() {
				final ImagePlus imp =  ImpUtils.demo("Rat_Hippocampal_Neuron");
				tagForQuickDisposal(imp);
				return imp;
			}
		};
		entry.summary = "Downloads a cultured hippocampal neuron stained for nAChRs. No reconstructions included.";
		entry.data = "Image (2D; 5-channel confocal image, 2.5MB)";
		entry.source = "ImageJ sample image";
		entry.online = true;
		entry.keywords = List.of("In vitro", "membrane", "synapses", "neurotransmitter", "profile");
		entry.category = Category.IMAGE_ONLY;
		return entry;
	}

	private Demo demo07() {
		final Demo entry = new Demo(7, "Hippocampal Neuron (Synaptic Labeling)") {
			@Override
			public ImagePlus getImage() {
				final ImagePlus imp = sntService.demoImage("cil810");
				tagForQuickDisposal(imp);
				return imp;
			}
		};
		entry.summary = "Downloads a cultured hippocampal neuron stained for pre- and post- synaptic markers. No reconstructions included.";
		entry.data = "Image (2D; 3-channel confocal image, 3.8MB)";
		entry.source = "Cell Image Library, doi:10.7295/W9CIL810";
		entry.online = true;
		entry.keywords = List.of("In vitro", "membrane", "synapses", "neurotransmitter", "profile");
		entry.category = Category.IMAGE_ONLY;
		return entry;
	}

	private Demo demo08() {
		final Demo entry = new Demo(8, "L-systems Fractal (2D Toy Neuron)") {
			{ hasTree = true; }
			@Override
			public ImagePlus getImage() {
				final ImagePlus imp = ImpUtils.demo("fractal");
				tagForQuickDisposal(imp);
				return imp;
			}

			@Override
			public Tree getTree() {
				return sntService.demoTree("fractal");
			}

			@Override
			public List<Tree> getTrees() {
				return List.of(getTree());
			}
		};
		entry.summary = "An L-systems fractal image and respective reconstruction. Multipoint ROIs have been added to emulate markers for dendritic spines.";
		entry.data = "Image (2D; mask, 23KB), tracings, and ROIs (25KB)";
		entry.source = "SNT script";
		entry.online = false;
		entry.keywords = List.of("synthetic", "Strahler");
		entry.category = Category.RECONSTRUCTION;
		return entry;
	}

	private Demo demo09() {
		final Demo entry = new Demo(9, "Microglia Cells (Autotrace Demo)") {
			@Override
			public ImagePlus getImage() {
				final ImagePlus imp = ImpUtils.demo("microglia");
				tagForQuickDisposal(imp);
				return imp;
			}

			@Override
			public void load() {
				super.load();
				if (!imageLoaded) return;
				snt.getPrefs().setTemp("demo-running", true);
				ui.changeState(SNTUI.RUNNING_CMD);
				setNotes("""
						The microglia cells autotrace demo
						runs _Auto-trace → Grayscale Image (Multiple Cells)..._
						with these parameters:

						```
						Background threshold:     Auto (-1)
						Score map filter:         Tubeness
						Smooth window:            3
						Min. soma radius:         14
						Min. inter-soma distance: 400
						Territory reach:          Disabled (-1)
						All other parameters:     Set to defaults
						```

						Tip: Once tracing completes, press 'H'
						     to toggle paths visibility; '1'
						     to display only selected paths
						""");
				runDemoScript("demo_microglia_multisoma.groovy");
			}
		};
		entry.summary = """
				2D Maximum Intensity Projection of microglia cells in the
				mouse retina, provided by the Wai T. Wong lab at NEI/NIH.""";
		entry.data = "Image (2D; grayscale, 1.4MB)";
		entry.online = true;
		entry.source = "PMID 29750189";
		entry.keywords = List.of("glia", "multi-cell", "auto-tracing");
		entry.category = Category.TUTORIAL_SCRIPT;
		return entry;
	}

	private Demo demo10() {
		final Demo entry = new Demo(10, "MouseLight Dendrites (CCF Annotated)") {
			{ hasImage = false; hasTree = true; }
			@Override
			public ImagePlus getImage() {
				return null;
			}

			@Override
			public void load() {
				if (!prepNonImgLoading())
					exit();
				try {
					assert snt != null;
					snt.getPathAndFillManager().addTrees(sntService.demoTrees());
					snt.setSinglePane(true);
					snt.rebuildDisplayCanvases();
					snt.updateAllViewers();
				} catch (final Throwable ex) {
					error(ex);
				} finally {
					exit();
				}
			}

			@Override
			public List<Tree> getTrees() {
				return sntService.demoTrees();
			}

		};
		entry.summary = "Dendrites of 4 pyramidal neurons in the mouse primary motor and somatosensory cortex. Reconstructions contain neuropil annotations, allowing for brain area analyses.";
		entry.data = "JSON (654KB)";
		entry.source = "MouseLight database (AA0001-AA0004)";
		entry.online = false;
		entry.keywords = List.of("whole-brain", "neuropil annotations", "CCF", "delineation analysis");
		entry.category = Category.RECONSTRUCTION;
		return entry;
	}

	private Demo demo11() {
		final Demo entry = new Demo(11, "Non-neuronal Dividing Cell (5D Image)") {
			{ hasTree = true; }
			@Override
			public ImagePlus getImage() {
				final ImagePlus imp = ImpUtils.open("https://wsr.imagej.net/images/mitosis.tif");
				if (imp != null) {
					imp.setPosition(2, 4, 31); // k-fibers channel, mid Z-range, traced time point
					tagForQuickDisposal(imp);
				}
				return imp;
			}

			@Override
			public void load() {
				super.load();
				assert snt != null;
				if (snt.getPathAndFillManager().size() > 1) { // apply tags
					assert ui != null;
					ui.getPathManager().applyDefaultTags("Traced Channel");
					ui.getPathManager().applyDefaultTags("Traced Frame");
				}
			}
		};
		entry.summary = "Downloads a Drosophila S2 cell undergoing mitosis in which K-fibers were traced during anaphase.";
		entry.data = "Image (5D; 2-channel, 3D timelapse, 33MB)";
		entry.source = "ImageJ sample image, PMID 19720876";
		entry.online = true;
		entry.tracingsURL = "https://raw.githubusercontent.com/morphonets/SNTmanuscript/718e4b90fb4bb61f382edcf467173b53045b25e0/FigS3_5D-Tracing/traces/mitosis.traces";
		entry.keywords = List.of("Chromosome", "in-vitro", "not-a-neuron", "time-lapse");
		entry.category = Category.ADVANCED;
		return entry;
	}

	private Demo demo12() {
		final Demo entry = new Demo(12, "NeuronJ Dataset (2D Neurites)") {
			{ hasTree = true; }
			@Override
			public ImagePlus getImage() {
				return ImpUtils.open("https://github.com/morphonets/misc/raw/master/dataset-demos/NeuronJ/neurites.tif");
			}
			@Override
			public void load() {
				super.load();
				if (!imageLoaded) return;
				snt.enableSnapCursor(true);
				snt.getUI().setRenderingScale(6.0);
				snt.enableAstar(true);
			}

		};
		entry.summary = "Downloads the test dataset (image and traced neurites) of the NeuronJ legacy plugin.";
		entry.data = "Image (2D; 314KB) and tracings (2.2KB)";
		entry.source = "NeuronJ, https://imagej.net/plugins/neuronj";
		entry.online = true;
		entry.tracingsURL = "https://raw.githubusercontent.com/morphonets/misc/master/dataset-demos/NeuronJ/neurites.ndf";
		entry.keywords = List.of("2D", "in-vitro", "rubber-band", "live-tracing");
		entry.category = Category.RECONSTRUCTION;
		return entry;
	}

	private Demo demo13() {
		final Demo entry = new Demo(13, "Segmented Video (2D Timelapse)") {
			@Override
			public ImagePlus getImage() {
				return null;
			}

			@Override
			public void load() {
				if (!prepNonImgLoading())
					exit();
				try {
                    assert snt != null;
                    final ScriptInstaller si = new ScriptInstaller(snt.getContext(), ui);
					final String scriptFileName = "Fully_Automated_Tracing_Timelapse_Demo.groovy";
					final String scriptName = scriptFileName.substring(0, scriptFileName.indexOf('.')).replace("_", " ");
					si.runScript("Tracing", scriptName);
					si.openScript("Tracing", scriptName);
				} catch (final Throwable ex) {
					error(ex);
				} finally {
					exit();
				}
			}
		};
		entry.summary = "Downloads a small video of segmented neurites extending in culture, and runs automated "
				+ "tracing on each frame through a script.";
		entry.data = "Image (2D timelapse, 0.9MB)";
		entry.source = "Stephanie Sarbanes (NIH/NINDS)";
		entry.online = true;
		entry.keywords = List.of("growth analysis", "in-vitro", "script", "auto-tracing", "binary");
		entry.category = Category.TUTORIAL_SCRIPT;
		return entry;
	}

	private Demo demo14() {
		final Demo entry = new Demo(14, "Spot Spine Dataset (Spine Decorated Dendrite)") {
			{ hasTree = true; }
			@Override
			public ImagePlus getImage() {
				return ImpUtils.open("https://github.com/morphonets/misc/raw/master/dataset-demos/SpotSpine/SpotSpine_ImageStack_Test.tif");
			}

			@Override
			public void load() {
				super.load();
				if (snt.getPathAndFillManager().size() > 1) {
					snt.getImagePlus().setPosition(1, 20, 1);
					snt.enableSnapCursor(false);
					snt.getUI().runCommand("Display/Rebuild ZY/XZ Views");
					if (!snt.getDrawDiameters())
						snt.getUI().runCommand("Toggle Draw diameters");
					snt.getContext().getService(CommandService.class).run(SpotSpineLoaderCmd.class, true, new HashMap<>());
					snt.getUI().setVisibilityFilter("Z-slices", true);
				}
			}
		};

		entry.summary = "Downloads the test dataset of the Spot Spine software (image stack and traced dendrite).";
		entry.data = "Image (3D; 0.7MB) and tracings (160KB)";
		entry.source = "Spot Spine (https://imagej.net/plugins/spot-spine) manuscript, doi:10.12688/f1000research.146327.2";
		entry.online = true;
		entry.tracingsURL = "https://raw.githubusercontent.com/morphonets/misc/master/dataset-demos/SpotSpine/SpotSpine_ImageStack_Test.swc";
		entry.keywords = List.of("spine morphology", "SNT add-on", "synapse", "dendrite");
		entry.category = Category.ADVANCED;
		return entry;
	}

	private Demo demo15() {
		final Demo entry = new Demo(15, " Guided Tracing (Interactive)") {
			@Override
			public ImagePlus getImage() {
				final ImagePlus imp = ImpUtils.demo("OP_1");
				tagForQuickDisposal(imp);
				return imp;
			}

			@Override
			public void load() {
				if (!ui.resetUI()) return; // user did not resolve an unsaved-changes prompt; abort
				super.load();
				if (!imageLoaded) return;
				startTutorial(Bvv.open(snt));
			}
		};
		entry.summary = "Interactive, step-by-step walkthrough tutorial of semi-automated tracing in Bvv/Stream mode: "
				+ "Start a path, finish it, inspect a branch point location, and fork a child path.";
		entry.data = "Image (3D; 1-channel confocal image, 15MB)";
		entry.source = "DIADEM dataset, PMID 17382886";
		entry.online = true;
		entry.keywords = List.of("tutorial", "onboarding", "semi-automated tracing", "Bvv", "3D");
		entry.category = Category.TUTORIAL_INTERACTIVE;
		return entry;
	}

	// Builds and starts the interactive tutorial for demo15
	private void startTutorial(final Bvv bvv) {
		final PathAndFillManager pafm = snt.getPathAndFillManager();
		// If the user taps spacebar out of curiosity, or a stray press while reading a callout, tracing stays silently
		// disabled and every click-based step from then on can never be satisfied, leaving the tutorial stuck retrying
		// the same step with no obvious explanation why. We'll re-arming semi-automated tracing on entry to each step
		final Consumer<AbstractBigViewer> reenableTracing = v -> v.enableTracing(false);
		final List<GuidedTutorial.Step> steps = List.of(
				GuidedTutorial.Step.of(
						"Click at this location to start a path.",
						new BoundingBox(List.of(SNTPoint.of(10.212, 141.432, 0))),
						bvv::isPathInProgress, // the path is not added to the manager until finished (next step)
						reenableTracing),
				GuidedTutorial.Step.of(
						"Double-click at this location to finish the path.",
						new BoundingBox(List.of(SNTPoint.of(146.674, 56.604, 34.745))),
						() -> pafm.size() >= 1,
						reenableTracing),
				GuidedTutorial.Step.of(
						"Press 'G' to Grab (select) the path you just finished.",
						new BoundingBox(List.of(SNTPoint.of(59.997, 81.753, 38.953))), // aprox. path mid point
						() -> pafm.size() >= 1 && pafm.anySelected(),
						reenableTracing),
				GuidedTutorial.Step.of(
						"Now let's inspect this location. Hold 'H' to Hide the annotated path.",
						new BoundingBox(List.of(
								SNTPoint.of(138.72, 85.897, 30.820),
								SNTPoint.of(098.72, 45.897, 38.820))), // zoom out box around fork point
						() -> true, // nothing to validate
						GuidedTutorial.rotate(0, -45, 500).andThen(reenableTracing)), // orbit, then re-arm tracing
				GuidedTutorial.Step.of(
						String.format("Hold Alt%s and click on the node under the crosshair to fork a child path.",
								(snt.getPrefs().getRequireShiftToFork()) ? "+Shift" : ""),
						new BoundingBox(List.of(SNTPoint.of(118.72, 65.897, 34.820))),
						bvv::isPathInProgress,
						reenableTracing),
				new GuidedTutorial.Step(
						"Double-click here to finish the child path.",
						new BoundingBox(List.of(SNTPoint.of(141.68, 77.303, 17.843))),
						() -> pafm.getPaths().stream().anyMatch(p -> !p.isPrimary()),
						(bvv.isPathInProgress()) ? -1 : 4, // retry from the fork step if no child path was created
						reenableTracing, // re-arm tracing on entry (see comment above); last click-based step
						null), // default (canvas-center) callout anchor
				GuidedTutorial.Step.of(
								"Press space bar to toggle between navigation mode and tracing modes.",
								new BoundingBox(
										List.of(SNTPoint.of(0, 0, 0), SNTPoint.of(200, 200, 60))), // image bounds
								() -> true) // no validation
						.pointingAt((viewer) -> {
							final bvv.core.VolumeViewerFrame viewerFrame = (bvv.core.VolumeViewerFrame) viewer.getViewerFrame();
							try {
								final int idx = viewerFrame.getCardPanel().indexOf("SNT Controls");
								return viewerFrame.getCardPanel().getComponent().getComponent(idx); // the SNT controls card
							} catch (final Exception ignored) {} // do nothing. best effort
							return viewer.getViewerCanvas();
						}),
				GuidedTutorial.Step.of(
						"Explore the scene by rotating (left-click + drag),<br>" +
								"zooming (scroll), or panning (right-click + drag).<br><br>" +
								"Double-click anywhere to re-center on that point.<br>" +
								"While tracing, hold Space first, or the double-click<br>" +
								"will finish the path instead.",
						new BoundingBox(
								List.of(SNTPoint.of(0, 0, 0), // image corners
										SNTPoint.of(200, 200, 60))),
						() -> true,
						GuidedTutorial.rotate(1, 45, 900))); // orbit 45 deg around the vertical axis, animated

		final boolean canvasAutoActivationWasEnabled = snt.getPrefs().isCanvasAutoActivationEnabled();
		new GuidedTutorial(bvv, steps)
				.setPreAction(() -> {
					snt.getUI().setVisibilityFilter("z-slices", true); // distinct visuals between ImagePlus vs Bvv
					snt.getUI().runCommand("Arrange Dialogs");
					snt.getPrefs().setCanvasAutoActivation(false); // could hijack cursor before/during tutorial
					bvv.getViewerFrame().toFront();
					bvv.enableTracing(false); // enable semi-automated (A*) tracing
				}, 500) // let "Arrange Dialogs"' window resizing/repositioning settle before step 1 reads canvas geometry
				.setPostAction(() -> {
					snt.getPrefs().setCanvasAutoActivation(canvasAutoActivationWasEnabled);
					snt.getUI().getPathManager().runCommand("Expand All");
					snt.getUI().getPathManager().clearSelection();
					bvv.resetView();
					new GuiUtils(bvv.getViewerFrame()).infoMsg(
							"All done! You are now a tracing expert! ☺ These tracing operations are common to all viewers," +
									"including traditional images and streamed data. Feel free to keep exploring, or run other tutorials.",
							"Tutorial Complete");
				})
				.start();
	}

	private void error(final Throwable ex) {
		ui.error("Loading of data failed (" + ex.getMessage() + " error). See Console for details.");
		ex.printStackTrace();
	}

	private void exit() {
        if (priorUIState == SNTUI.TRACING_PAUSED && ui.getState() != SNTUI.TRACING_PAUSED) {
            return; // Don't restore TRACING_PAUSED if we're no longer in that state
        }
        ui.changeState(priorUIState);
        ui.showStatus(null, true);
	}

	public Demo getChoice() {
		final String[] choices = new String[entries.size()];
		final String[] descriptions = new String[entries.size()];
		int idx = 0;
		for (final Demo entry : entries) {
			choices[idx] = entry.toString();
			descriptions[idx++] = entry.description();
		}
		final String defChoice = (prefs == null) ? choices[0] : prefs.getTemp("demo", choices[0]);
		final String choice = new GuiUtils(ui).getChoice(
				"Which dataset/tutorial?<br>NB: Remote data may take a while to download", "Load Demo Dataset/Tutorial", choices,
				descriptions, defChoice, (list, index) -> entries.get(index).icon(list));
		if (choice == null)
			return null;
		if (prefs != null)
			prefs.setTemp("demo", choice);
		return entries.get(Arrays.asList(choices).indexOf(choice));
	}

	public void load(final int demoID) { // matches a Demo's own fixed id, not its position in entries
		final Demo demo = entries.stream().filter(d -> d.id == demoID).findFirst()
				.orElseThrow(() -> new IllegalArgumentException("Invalid demo id: " + demoID));
		directLoading = true;
		demo.load();
		directLoading = false;
	}

	/**
	 * Loads the {@link Demo} matching {@code recorded}: either its exact display label (see {@link Demo#toString()}),
	 * or a bare/legacy id (for scripts or macros recorded before the id moved out of the label, into
	 * {@link Demo#description()}). Tries the exact label first, so an unrelated digit elsewhere in a label (e.g. "3D"
	 * in a demo's name) is never mistaken for an id.
	 *
	 * @param recorded the recorded/typed argument, e.g. {@code "Guided Tracing (Interactive)"}, or a bare id such as
	 *                 {@code "15"}
	 * @throws IllegalArgumentException if {@code recorded} matches neither a label nor a valid id
	 */
	public void load(final String recorded) {
		for (final Demo d : entries) {
			if (d.toString().equals(recorded)) {
				load(d.id);
				return;
			}
		}
		try (final Scanner scanner = new Scanner(recorded)) {
			load(scanner.useDelimiter("\\D+").nextInt());
		} catch (final NoSuchElementException | IllegalStateException ex) {
			throw new IllegalArgumentException("Invalid recorded option: " + recorded, ex);
		}
	}

	/**
	 * Coarse grouping used to order/discover {@link Demo} entries in {@link #getChoice()}; declaration
	 * order here is display order (see the sort in {@link #DemoRunner(SNTUI, SNT)}), independent of each
	 * Demo's own fixed {@code id}.
	 */
	enum Category {
		TUTORIAL_INTERACTIVE("Interactive Tutorial"),
		TUTORIAL_SCRIPT("Scripted Tutorial"),
		RECONSTRUCTION("Reconstruction"),
		GROWTH_ANALYSIS("Growth Analysis"),
		ADVANCED("Advanced"),
		IMAGE_ONLY("Image Only");

		final String label;

		Category(final String label) {
			this.label = label;
		}
	}

	public class Demo {

		final String name;
		final int id;
		String tracingsURL;
		String data;
		String source;
		String summary;
		boolean online;
		boolean imageLoaded;
		List<String> keywords;
		Category category = Category.IMAGE_ONLY;
		boolean hasImage = true; // false only for demos with no image at all (see Category.RECONSTRUCTION below)
		boolean hasTree = false; // true only for demos that also bundle a ready-made reconstruction (getTree()/
		                          // getTrees()/tracingsURL), whether alongside an image or (with hasImage=false) alone

		private Demo(final int id, final String name) {
			this.id = id;
			this.name = name;
		}

		private void resetPaths() {
			if (directLoading || (snt.getPathAndFillManager().size() > 0
					&& new GuiUtils(ui).getConfirmation("Clear Existing Path(s)?", "Delete Existing Path(s)?"))) {
				snt.getPathAndFillManager().clear();
			}
		}

		void tagForQuickDisposal(final ImagePlus imp) {
			if (imp != null) snt.getPrefs().setTemp("ignore-close-" + imp.getID(), true);
		}

		/**
		 * Displays text in the Notes pane and selects the Notes tab. Useful for
		 * showing demo descriptions, parameters, and usage tips during demos.
		 *
		 * @param text the text to display (supports basic markdown formatting)
		 */
		void setNotes(final String text) {
			if (ui == null) return;
			SwingUtilities.invokeLater(() -> {
				ui.getNotesPane().getEditor().setText(text);
				ui.selectTab("notes");
			});
		}

		/**
		 * Runs a Groovy script from the {@code demorunner/} resource directory.
		 * Scripts in this directory are not discoverable by scijava's script
		 * service, keeping them internal to DemoRunner. The script receives the
		 * provided input bindings and runs asynchronously.
		 *
		 * @param scriptName the script filename (e.g., "demo_microglia.groovy")
		 * @param inputs     variable bindings passed to the script (may be empty)
		 * @return whether the script was found and launched
		 */
		boolean runDemoScript(final String scriptName, final Map<String, Object> inputs) {
			try {
				return ScriptInstaller.runScript("demorunner", scriptName, inputs);
			} catch (final IllegalArgumentException ex) {
				if (ui != null)
					ui.error("Demo script not found: " + scriptName);
				else
					SNTUtils.log("Demo script not found: " + scriptName);
				return false;
			}
		}

		/**
		 * Convenience overload that runs a demo script with no input bindings.
		 *
		 * @param scriptName the script filename
		 * @return whether the script was found and launched
		 * @see #runDemoScript(String, Map)
		 */
		boolean runDemoScript(final String scriptName) {
			return runDemoScript(scriptName, Map.of());
		}

		public void load() {
			assert snt != null;
			assert ui != null;
			imageLoaded = false;
			try {
				final ImagePlus imp = getImage();
				if (imp == null) {
					ui.error(
							"Image could not be retrieved. Perhaps an internet connection is required but you are offline?");
					ui.changeState(priorUIState);
					return;
				}
				tagForQuickDisposal(imp);
				ui.closeBigViewers(); // a Bvv/Bdv left open from a previous demo is about to be replaced/orphaned
				resetPaths();
				snt.initialize(imp);
				imageLoaded = true;
				if (tracingsURL != null) {
					snt.getPathAndFillManager().loadGuessingType(tracingsURL);
				} else {
					final Tree tree = getTree();
					if (tree != null) {
						snt.getPathAndFillManager().addTree(tree);
						snt.getPathAndFillManager().assignSpatialSettings(imp);
					}
				}
				snt.updateAllViewers();
				if (imp.getNChannels() > 1 && imp instanceof ij.CompositeImage)
					ij.IJ.doCommand("Channels Tool...");
			} catch (final Throwable ex) {
				error(ex);
			} finally {
				exit();
			}
		}

		boolean prepNonImgLoading() {
            assert snt != null;
            if ((snt.getPathAndFillManager().size() > 0 || snt.accessToValidImageData())
					&& !directLoading && !new GuiUtils(ui).getConfirmation(
							"Any loaded image will be disposed and any existing paths will be deleted. Proceed?",
							"Dispose Existing Data?"))
				return false;
			try {
				if (snt.getImagePlus() != null)
					snt.getImagePlus().close();
				if (snt.getImagePlus() != null) { // Presumably user did not resolve 'Save Changes?' prompt
					new GuiUtils(ui).error("Loading of demo aborted. Please resolve any unsaved changes and retry.");
					return false;
				}
				ui.closeBigViewers(); // a Bvv/Bdv left open from a previous demo is about to be replaced/orphaned
				snt.closeAndResetAllPanes(); // closed early on so that spatial calibration reset
				snt.getPathAndFillManager().clear(); // will reset spatial calibration
				return true;
			} catch (final Throwable ex) {
				error(ex);
			}
			return false;
		}

		String description() {
			final StringBuilder sb = new StringBuilder();
			sb.append(summary);
			sb.append("\n\n");
			sb.append("Data: ").append(data);
			sb.append("\n");
			sb.append("Internet required: ").append((online) ? "Yes" : "No");
			if (source != null) {
				sb.append("\n");
				sb.append("Source: ").append(source);
			}
			sb.append("\n");
			sb.append("Demo ID: ").append(String.format("%02d", id));
			if (keywords != null && !keywords.isEmpty()) {
				sb.append("\n");
				sb.append("Keywords: ").append(String.join(", ", keywords));
			}
			return sb.toString();
		}

		ImagePlus getImage() {
			 final ImagePlus imp = ImpUtils.demo(name);
			 tagForQuickDisposal(imp);
			 return imp;
		}

		Tree getTree() {
			return null; // default as several demos don't have associated tree(s)
		}

		public List<Tree> getTrees() {
			return null; // default as several demos don't have associated tree(s)
		}

		@Override
		public String toString() {
			return name; //String.format("[%s] %s", category.label, name);
		}

		// Never derived from getImage()/getTree(): both can trigger a network download for online demos
		Icon icon(final JList<?> list) {
			return switch (category) {
				case TUTORIAL_INTERACTIVE -> IconFactory.listIcon(list, IconFactory.GLYPH.GRADUATION_CAP,
						IconFactory.GLYPH.COMPUTER_MOUSE, IconFactory.secondaryColor());
				case TUTORIAL_SCRIPT -> IconFactory.listIcon(list, IconFactory.GLYPH.GRADUATION_CAP,
						IconFactory.GLYPH.CODE, IconFactory.secondaryColor());
				default -> {
					if (hasImage && hasTree)
						yield IconFactory.listIcon(list, IconFactory.GLYPH.IMAGE, IconFactory.GLYPH.TREE,
								IconFactory.secondaryColor());
					yield IconFactory.listIcon(list, hasTree ? IconFactory.GLYPH.TREE : IconFactory.GLYPH.IMAGE,
							null, IconFactory.secondaryColor());
				}
			};
		}
	}
}
