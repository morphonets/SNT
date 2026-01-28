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
import sc.fiji.snt.plugin.BinaryTracerCmd;
import sc.fiji.snt.util.ImpUtils;

import javax.swing.*;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

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
		entries = List.of(demo0(), demo1(), demo2(), demo3(), demo4(), demo5(), demo6(), demo7(), demo8(), demo9(), demo10(),
				demo11(), demo12());
	}

	public DemoRunner(final Context context) {
		this.ui = null;
		this.snt = null;
		prefs = null;
		context.inject(this);
		priorUIState = -1;
		entries = List.of(demo0(), demo3(), demo7(), demo8());
	}

	private Demo demo0() {
		final Demo entry = new Demo(1, "DG Granule Cell (Reconstruction only)") {
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
		return entry;
	}

	private Demo demo1() {
		final Demo entry = new Demo(2, "Drosophila ddaC neuron (Autotrace demo)") {
			@Override
			public ImagePlus getImage() {
				final ImagePlus imp = sntService.demoImage("ddaC");
				if (imp != null)
					imp.setRoi(322, 383, 21, 24); // mark soma
				return imp;
			}

			@Override
			public void load() {
				super.load();
                runTracing();
			}

            private void runTracing() {
                final String txt = """
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
                        
                        Tip: Once tracing completes, press 'H' to
                             toggle paths visibility
                        """;
                SwingUtilities.invokeLater( () -> {
                    ui.getNotesPane().getEditor().setText(txt);
                    ui.selectTab("notes");
                });
                final HashMap<String, Object> inputs = new HashMap<>();
                inputs.put("useFileChoosers", false);
                inputs.put("maskImgChoice", "Image being traced (duplicate)");
                inputs.put("originalImgChoice", "None"); // does not matter: not used by nicking strategy
                inputs.put("rootChoice", BinaryTracerCmd.ROI_EDGE);
                inputs.put("roiPlane", false); // does not matter: 2D image
                inputs.put("loopSolvingChoice", "Peripheral segments (preserves backbone)");
                inputs.put("pruneByLength", true);
                inputs.put("lengthThreshold", 3);
                inputs.put("connectComponents", true);
                inputs.put("maxConnectDist", 6);
                inputs.put("cullSingleNodePaths", true);
                inputs.put("clearExisting", true);
                inputs.put("assignDistinctColors", true);
                inputs.put("editMode", false);
                inputs.put("debugMode", SNTUtils.isDebugMode());
                inputs.put("headless", true);
                ui.runCommand(BinaryTracerCmd.class, inputs);
            }
		};
		entry.summary = "Loads a binary (thresholded) image of a Drosophila space-filling neuron (ddaC) and "
				+ "displays autotracing options for automated reconstuction.";
		entry.data = "Image (2D mask, 581KB)";
		entry.online = false;
		entry.source = "PMID 24449841";
		return entry;
	}

	private Demo demo2() {
		final Demo entry = new Demo(3, "Drosophila ddaC neuron (Image only)") {
			@Override
			public ImagePlus getImage() {
				return sntService.demoImage("ddaC");
			}
		};
		entry.summary = "Same Demo 1 dataset but no autotracing operations are performed.";
		entry.data = "Image (2D mask, 581KB)";
		entry.online = false;
		entry.source = "PMID 24449841";
		return entry;
	}

	private Demo demo3() {
		final Demo entry = new Demo(4, "Drosophila OP neuron (Complete 3D reconstruction)") {
			@Override
			public ImagePlus getImage() {
				return sntService.demoImage("OP_1");
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
		return entry;
	}

	private Demo demo4() {
		final Demo entry = new Demo(5, "Hippocampal neuron (DIC timelapse)") {
			@Override
			public ImagePlus getImage() {
				return sntService.demoImage("cil701");
			}
		};
		entry.summary = "Downloads a timelapse video (19h) of a cultured hippocampal neuron in which neurites have been traced across time. " +
                "Suitable for testing Growth Analysis options";
		entry.data = "Image (2D; timelapse image, 52MB) and tracings (420KB)";
		entry.source = "Cell Image Library, doi:10.7295/W9CIL701";
		entry.online = true;
		entry.tracingsURL = "https://raw.githubusercontent.com/morphonets/misc/00369266e14f1a1ff333f99f0f72ef64077270da/dataset-demos/CIL_Dataset_%23701.traces";
		return entry;
	}

	private Demo demo5() {
		final Demo entry = new Demo(6, "Hippocampal neuron (Neuronal receptors)") {
			@Override
			public ImagePlus getImage() {
				return sntService.demoImage("Rat_Hippocampal_Neuron");
			}
		};
		entry.summary = "Downloads a cultured hippocampal neuron stained for nAChRs. No reconstructions included.";
		entry.data = "Image (2D; 5-channel confocal image, 2.5MB)";
		entry.source = "ImageJ sample image";
		entry.online = true;
		return entry;
	}

	private Demo demo6() {
		final Demo entry = new Demo(7, "Hippocampal neuron (Synaptic labeling)") {
			@Override
			public ImagePlus getImage() {
				return sntService.demoImage("cil810");
			}
		};
		entry.summary = "Downloads a cultured hippocampal neuron stained for pre- and post- synaptic markers. No reconstructions included.";
		entry.data = "Image (2D; 3-channel confocal image, 3.8MB)";
		entry.source = "Cell Image Library, doi:10.7295/W9CIL810";
		entry.online = true;
		return entry;
	}

	private Demo demo7() {
		final Demo entry = new Demo(8, "L-systems fractal (2D toy neuron)") {
			@Override
			public ImagePlus getImage() {
				return sntService.demoImage("fractal");
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
		return entry;
	}

	private Demo demo8() {
		final Demo entry = new Demo(9, "MouseLight dendrites (Reconstructions only)") {
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
		return entry;
	}

	private Demo demo9() {
		final Demo entry = new Demo(10, "Non-neuronal dividing cell (5D image)") {
			@Override
			public ImagePlus getImage() {
				final ImagePlus imp = ImpUtils.open("http://wsr.imagej.net/images/mitosis.tif");
				imp.setPosition(2, 4, 31); // k-fibers channel, mid Z-range, traced time point
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
		return entry;
	}

	private Demo demo10() {
		final Demo entry = new Demo(11, "NeuronJ dataset (2D neurites)") {
			@Override
			public ImagePlus getImage() {
				return  ImpUtils.open("https://github.com/morphonets/misc/raw/master/dataset-demos/NeuronJ/neurites.tif");
			}
			@Override
			public void load() {
				super.load();
				assert snt != null;
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
		return entry;
	}

	private Demo demo11() {
		final Demo entry = new Demo(12, "Segmented video (2D timelapse)") {
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
					final String scriptName = scriptFileName.substring(0, scriptFileName.indexOf(".")).replace("_", " ");
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
		entry.source = "https://forum.image.sc/t/snt-time-lapse-utilites/47974";
		entry.online = true;
		return entry;
	}

	private Demo demo12() {
		final Demo entry = new Demo(13, "Spot Spine dataset (spine decorated dendrite)") {
			@Override
			public ImagePlus getImage() {
				return  ImpUtils.open("https://github.com/morphonets/misc/raw/master/dataset-demos/SpotSpine/SpotSpine_ImageStack_Test.tif");
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
		return entry;
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
				"Which dataset?<br>NB: Remote data may take a while to download", "Load Demo Dataset", choices,
				descriptions, defChoice);
		if (choice == null)
			return null;
		if (prefs != null)
			prefs.setTemp("demo", choice);
		return entries.get(Arrays.asList(choices).indexOf(choice));
	}

	public void load(final int demoID) { // 1-based index to match GUI choice
		if (demoID < 1 || demoID > entries.size())
			throw new IllegalArgumentException("Invalid demo id. Must be between 1-" + entries.size());
		directLoading = true;
		entries.get(demoID-1).load();
		directLoading = false;
	}

	public class Demo {

		final String name;
		final int id;
		String tracingsURL;
		String data;
		String source;
		String summary;
		boolean online;

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

		public void load() {
			try {
				final ImagePlus imp = getImage();
				if (imp == null) {
					ui.error(
							"Image could not be retrieved. Perhaps an internet connection is required but you are offline?");
					ui.changeState(priorUIState);
					return;
				}
				resetPaths();
				snt.flushSecondaryData();
				snt.initialize(imp);
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

		protected boolean prepNonImgLoading() {
            assert snt != null;
            if ((snt.getPathAndFillManager().size() > 0 || snt.getImagePlus() != null)
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
				snt.flushSecondaryData();
				snt.closeAndResetAllPanes(); // closed early on so that spatial calibration reset
				snt.getPathAndFillManager().clear(); // will reset spatial calibration
				return true;
			} catch (final Throwable ex) {
				error(ex);
			}
			return false;
		}

		public String description() {
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
			return sb.toString();
		}

		public ImagePlus getImage() {
			return sntService.demoImage(name);
		}

		public Tree getTree() {
			return null; // default as several demos don't have associated tree(s)
		}

		public List<Tree> getTrees() {
			return null; // default as several demos don't have associated tree(s)
		}

		@Override
		public String toString() {
			return String.format("%02d. %s", id, name);
		}
	}
}
