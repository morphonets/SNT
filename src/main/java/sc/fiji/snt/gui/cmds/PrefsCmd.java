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

package sc.fiji.snt.gui.cmds;


import javax.swing.JOptionPane;

import org.scijava.command.Command;
import org.scijava.command.ContextCommand;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.prefs.PrefService;
import org.scijava.widget.Button;

import net.imagej.ImageJ;
import sc.fiji.snt.SNT;
import sc.fiji.snt.SNTPrefs;
import sc.fiji.snt.SNTService;
import sc.fiji.snt.analysis.PathProfiler;
import sc.fiji.snt.gui.GuiUtils;
import sc.fiji.snt.gui.MeasureUI;
import sc.fiji.snt.gui.SaveMeasurementsCmd;
import sc.fiji.snt.plugin.AnalyzerCmd;
import sc.fiji.snt.plugin.BrainAnnotationCmd;
import sc.fiji.snt.plugin.ConvexHullCmd;
import sc.fiji.snt.plugin.GraphAdapterMapperCmd;
import sc.fiji.snt.plugin.GroupAnalyzerCmd;
import sc.fiji.snt.plugin.LocalThicknessCmd;
import sc.fiji.snt.plugin.MultiTreeMapperCmd;
import sc.fiji.snt.plugin.PathAnalyzerCmd;
import sc.fiji.snt.plugin.PathMatcherCmd;
import sc.fiji.snt.plugin.PathOrderAnalysisCmd;
import sc.fiji.snt.plugin.PathSpineAnalysisCmd;
import sc.fiji.snt.plugin.PathTimeAnalysisCmd;
import sc.fiji.snt.plugin.PlotterCmd;
import sc.fiji.snt.plugin.ROIExporterCmd;
import sc.fiji.snt.plugin.ShollAnalysisBulkTreeCmd;
import sc.fiji.snt.plugin.ShollAnalysisImgCmd;
import sc.fiji.snt.plugin.ShollAnalysisPrefsCmd;
import sc.fiji.snt.plugin.ShollAnalysisTreeCmd;
import sc.fiji.snt.plugin.SkeletonConverterCmd;
import sc.fiji.snt.plugin.SkeletonizerCmd;
import sc.fiji.snt.plugin.SpineExtractorCmd;
import sc.fiji.snt.plugin.StrahlerCmd;
import sc.fiji.snt.plugin.TreeMapperCmd;
import sc.fiji.snt.plugin.ij1.CallIJ1LegacyCmd;

/**
 * Command for (re)setting SNT Preferences.
 *
 * @author Tiago Ferreira
 */
@Plugin(type = Command.class, visible = false, initializer="init", label="SNT Preferences")
public class PrefsCmd extends ContextCommand {

	@Parameter
	private PrefService prefService;

	@Parameter
	protected SNTService sntService;

	@Parameter(label = "Look and feel (L&F)", required = false, persist = false,
			description = "How should SNT look? NB: This may also affect other Swing-based dialogs in Fiji.", choices = {
			GuiUtils.LAF_DEFAULT, GuiUtils.LAF_LIGHT, GuiUtils.LAF_LIGHT_INTJ, GuiUtils.LAF_DARK, GuiUtils.LAF_DARCULA })
	private String laf;

	@Parameter(label="Managing Themes...", callback="lafHelp")
	private Button lafHelpButton;

	@Parameter(label="Remember window locations", description="Whether position of dialogs should be preserved across restarts")
	private boolean persistentWinLoc;

	@Parameter(label="Prefer 2D display canvases", description="When no valid image exists, adopt 2D or 3D canvases?")
	private boolean force2DDisplayCanvas;

	@Parameter(label="Use compression when saving traces", description="Wheter Gzip compression should be use when saving .traces files")
	private boolean compressTraces;

	@Parameter(label="No. parallel threads",
			description="<HTML><div WIDTH=500>The max. no. of parallel threads to be used by SNT, as specified in "
					+ "IJ's Edit>Options>Memory &amp; Threads... Set it to 0 to use the available processors on your computer")
	private int nThreads;

	@Parameter(label="Reset All Preferences...", callback="reset")
	private Button resetButton;

	private SNT snt;

	/*
	 * (non-Javadoc)
	 *
	 * @see java.lang.Runnable#run()
	 */
	public void run() {
		snt.getPrefs().setSaveWinLocations(persistentWinLoc);
		snt.getPrefs().setSaveCompressedTraces(compressTraces);
		snt.getPrefs().set2DDisplayCanvas(force2DDisplayCanvas);
		SNTPrefs.setThreads(Math.max(0, nThreads));
		final String existingLaf = SNTPrefs.getLookAndFeel();
		SNTPrefs.setLookAndFeel(laf);
		if (!existingLaf.equals(laf) && snt.getUI() != null) {
			final int ans = new GuiUtils(snt.getUI()).yesNoDialog("It is recommended that you restart SNT for changes to take effect. "
							+ "Alternatively, you can attempt to apply the new Look and Feel now, but some widgets/icons may not display properly. "
							+ "Do you want to try nevertheless?", "Restart Suggested", "Yes. Apply now.", "No. I will restart.");
			if (ans == JOptionPane.YES_OPTION)
				snt.getUI().setLookAndFeel(laf);
		}
	}

	private void init() {
		try {
			snt = sntService.getPlugin();
			persistentWinLoc = snt.getPrefs().isSaveWinLocations();
			force2DDisplayCanvas = snt.getPrefs().is2DDisplayCanvas();
			compressTraces = snt.getPrefs().isSaveCompressedTraces();
			nThreads = SNTPrefs.getThreads();
			laf = GuiUtils.LAF_DEFAULT;
		} catch (final NullPointerException npe) {
			cancel("SNT is not running.");
		}
	}

	@SuppressWarnings("unused")
	private void reset() {
		final boolean confirm = new GuiUtils().getConfirmation(
			"Reset preferences to defaults? (a restart may be required)", "Reset?");
		if (confirm) {
			clearAll();
			init(); // update prompt;
			new GuiUtils().centeredMsg("Preferences reset. You should now restart"
					+ " SNT for changes to take effect.", "Restart Required");
		}
	}

	@SuppressWarnings("unused")
	private void lafHelp() {
		laf = GuiUtils.LAF_DEFAULT;
		new GuiUtils().showHTMLDialog("<HTML>"
				+ "This option is now outdated. SNT's <i>Look and Feel</i> (L&F) preference has been integrated into Fiji. "
				+ "Please set SNT's L&F to 'Default' and use Fiji's <i>Edit>Look and Feel...</i> prompt instead.<br><br>"
				+ "Note that setting a L&F does not affect AWT widgets. Thus, while a dark theme can be applied "
				+ "to SNT (and other Fiji components like the Script Editor), it is currently not possible to "
				+ "apply a dark theme to ImageJ's built-in dialogs, macro prompts, and dialogs of certain legacy plugins.",
				"Managing Themes", true);
	}

	/** Clears all of SNT preferences. */
	@SuppressWarnings("deprecation")
	public void clearAll() {

		prefService.clear(AddTextAnnotationCmd.class);
		prefService.clear(AnalyzerCmd.class);
		prefService.clear(AnnotationGraphRecViewerCmd.class);
		prefService.clear(BrainAnnotationCmd.class);
		prefService.clear(CallIJ1LegacyCmd.class);
		prefService.clear(ChooseDatasetCmd.class);
		prefService.clear(ColorMapReconstructionCmd.class);
		prefService.clear(CompareFilesCmd.class);
		prefService.clear(ComputeSecondaryImg.class);
		prefService.clear(ConvexHullCmd.class);
		prefService.clear(CustomizeLegendCmd.class);
		prefService.clear(CustomizeObjCmd.class);
		prefService.clear(CustomizeTreeCmd.class);
		prefService.clear(DistributionBPCmd.class);
		prefService.clear(DistributionCPCmd.class);
		prefService.clear(DuplicateCmd.class);
		prefService.clear(GraphAdapterMapperCmd.class);
		prefService.clear(GraphGeneratorCmd.class);
		prefService.clear(GroupAnalyzerCmd.class);
		prefService.clear(InsectBrainImporterCmd.class);
		prefService.clear(JSONImporterCmd.class);
		prefService.clear(LoadObjCmd.class);
		prefService.clear(LoadReconstructionCmd.class);
		prefService.clear(LocalThicknessCmd.class);
		prefService.clear(MeasureUI.class);
		prefService.clear(MLImporterCmd.class);
		prefService.clear(MultiSWCImporterCmd.class);
		prefService.clear(MultiTreeMapperCmd.class);
		prefService.clear(NDFImporterCmd.class);
		prefService.clear(OpenDatasetCmd.class);
		prefService.clear(PathAnalyzerCmd.class);
		prefService.clear(PathFitterCmd.class);
		prefService.clear(PathMatcherCmd.class);
		prefService.clear(PathOrderAnalysisCmd.class);
		prefService.clear(PathProfiler.class);
		prefService.clear(PathSpineAnalysisCmd.class);
		prefService.clear(PathTimeAnalysisCmd.class);
		prefService.clear(PlotterCmd.class);
		prefService.clear(ReconstructionViewerCmd.class);
		prefService.clear(RecViewerPrefsCmd.class);
		prefService.clear(RemoteSWCImporterCmd.class);
		prefService.clear(ROIExporterCmd.class);
		prefService.clear(SaveMeasurementsCmd.class);
		prefService.clear(ShollAnalysisBulkTreeCmd.class);
		prefService.clear(ShollAnalysisImgCmd.class);
		prefService.clear(ShollAnalysisPrefsCmd.class);
		prefService.clear(ShollAnalysisTreeCmd.class);
		prefService.clear(ShowCorrespondencesCmd.class);
		prefService.clear(SkeletonizerCmd.class);
		prefService.clear(SpineExtractorCmd.class);
		prefService.clear(SkeletonConverterCmd.class);
		prefService.clear(SNTLoaderCmd.class);
		prefService.clear(StrahlerCmd.class);
		prefService.clear(SWCTypeFilterCmd.class);
		prefService.clear(SWCTypeOptionsCmd.class);
		prefService.clear(TranslateReconstructionsCmd.class);
		prefService.clear(TreeGraphRecViewerCmd.class);
		prefService.clear(TreeMapperCmd.class);

		// Legacy (IJ1-based) preferences
		SNTPrefs.clearAll();
	}


	/* IDE debug method **/
	public static void main(final String[] args) {
		GuiUtils.setLookAndFeel();
		final ImageJ ij = new ImageJ();
		ij.ui().showUI();
		ij.command().run(PrefsCmd.class, true);
	}

}
