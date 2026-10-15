#@ SNTService snt
#@ UIService ui
#@ File (label="Reconstruction to load (optional)", required=false, style="extensions:swc/traces/json") reconstruction
#@ int (label="Max paths to list", value=25, min=0) maxPaths
#@ File (label="Save report to (optional)", required=false, style="save") reportFile
#@ String (label="Output", choices={"Console", "Text window"}, description="Agents: use Console") output

"""
file:	Inspect_Session.groovy
info:   Reports the state of the SNT session this script runs in as JSON:
        versions, traced image, paths/trees, UI flags. Lets an external agent verify assumptions before (or after)
        running its own script. Optionally loads a reconstruction first. Run it in the Script Editor to inspect the
        live GUI session, or headlessly for a fresh one:
              fiji --headless --run Inspect_Session.groovy 'reconstruction="/path/cell.swc"'
        Headless runs cannot see a separate, already open Fiji instance.
        Agents: use output="Console" (default). "Text window" opens a window on the user's screen (ignored when headless)
"""

import groovy.json.JsonOutput
import sc.fiji.snt.SNTUtils
import sc.fiji.snt.Tree
import sc.fiji.snt.analysis.TreeStatistics

Map report = [:]

// Environment
report.environment = [
	snt_version : SNTUtils.VERSION,
	java_version: System.getProperty("java.version"),
	headless    : java.awt.GraphicsEnvironment.isHeadless(),
	debug_mode  : SNTUtils.isDebugMode()
]

// Optionally populate the session. Existing content is left untouched
if (reconstruction != null) {
	try {
		Tree t = new Tree(reconstruction.getAbsolutePath())
		report.loaded_file = [file: reconstruction.getName(), paths: t.size(), label: t.getLabel()]
		if (!snt.isActive()) snt.initialize(false) // no image, no UI
		snt.loadTree(t)
	} catch (Throwable ex) {
		report.load_error = ex.toString()
	}
}

// Session
boolean active = snt.isActive()
report.session = [snt_active: active, stream_mode: snt.isStreamMode()]

// Paths and trees
// SNTService accessors throw when SNT is not running
def pafm = active ? snt.getPathAndFillManager() : null
def paths = pafm?.getPaths() ?: []
def selected = active ? snt.getSelectedPaths() : []
def trees = pafm ? pafm.getTrees() : []
report.paths = [
	total   : paths.size(),
	selected: selected.size(),
	trees   : trees.size(),
	listed  : paths.take(maxPaths).collect { p ->
		[id: p.getID(), name: p.getName(), nodes: p.size(), length: p.getLength(),
		 swc_type: p.getSWCType(), order: p.getOrder(), channel: p.getChannel(), frame: p.getFrame()]
	}
]
if (paths.size() > maxPaths) report.paths.note = "Listing truncated to ${maxPaths} paths"

// Per-tree summary
report.trees = trees.collect { Tree t ->
	Map m = [label: t.getLabel(), paths: t.size()]
	try {
		TreeStatistics stats = new TreeStatistics(t)
		m.cable_length = stats.getMetric(TreeStatistics.LENGTH)
		m.n_branches = stats.getMetric(TreeStatistics.N_BRANCHES)
		m.n_tips = stats.getMetric(TreeStatistics.N_TIPS)
	} catch (Throwable ex) {
		m.stats_error = ex.toString()
	}
	return m
}

// Traced image (not available in stream mode or when SNT was not initialized)
def plugin = active ? snt.getInstance() : null
def imp = (plugin != null && !plugin.isStreamMode()) ? plugin.getImagePlus() : null
report.image = imp == null ? null : [
	title     : imp.getTitle(),
	dimensions: [x: imp.getWidth(), y: imp.getHeight(), channels: imp.getNChannels(), slices: imp.getNSlices(), frames: imp.getNFrames()],
	bit_depth : imp.getBitDepth(),
	spacing   : [x: imp.getCalibration().pixelWidth, y: imp.getCalibration().pixelHeight, z: imp.getCalibration().pixelDepth],
	unit      : imp.getCalibration().getUnit(),
	is_3d     : !plugin.is2D()
]
report.has_gui = active && snt.getUI() != null

String text = JsonOutput.prettyPrint(JsonOutput.toJson(report))
if (reportFile != null) reportFile.text = text
// Anything other than a window (or no GUI) falls back to the console
if (output?.trim()?.equalsIgnoreCase("Text window") && !java.awt.GraphicsEnvironment.isHeadless())
	ui.show("SNT Session", text)
else
	println text
return null // suppress 'ignoring unsupported output' warning
