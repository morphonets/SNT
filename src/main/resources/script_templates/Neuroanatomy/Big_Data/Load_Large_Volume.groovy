#@File(label="Main file to render (required)", description="Pyramid image to open in SNT's Bvv (.ims and BDV .xml files supported)") file1
#@File(label="Secondary file to render (optional)", required=false, description="Pyramid image to open in SNT's Bvv (.ims and BDV .xml files supported)") file2
#@UIService ui

/**
 * Exemplifies how to open a large (out-of-core) volumetric image in SNT's BVV
 * (BigVolumeViewer). GPU rendering and out-of-core data streaming are handled
 * entirely by BigVolumeViewer. SNT wraps BVV to automate format detection,
 * calibration, and channel setup, and adds support for neuroanatomical
 * annotations (traced paths, annotations, bookmarks), reducing the boilerplate
 * to a single call.
 *
 * Supported formats
 * - Imaris (.ims): BVV's pyramid-aware GPU cache manager streams tiles on demand,
 *   so the full volume is never loaded into RAM. On first open, a BDV XML sidecar
 *   file is created next to the .ims file (e.g. dataset.xml) and reused on
 *   subsequent opens. If the directory is read-only, create the XML manually via
 *   Plugins > BigDataViewer > Create XML for Imaris file, then pass the .xml path.
 * - BDV XML/HDF5 (.xml): loaded directly using BVV's cache manager. This covers
 *   BDV HDF5, BDV N5, and OME-Zarr datasets that have an accompanying BDV XML.
 * - Regular images (.tif, etc.): opened via ImgUtils and displayed as a flat
 *   volume. Note that BVV requires 3D images (Z > 1) and has a per-channel voxel
 *   limit of ~1 Gvox: flat volumes exceeding this will fail.
 *
 * Usage
 * Run the script and select a file. Use the Groups panel to toggle channel
 * visibility, the Source Transforms panel for manual registration between
 * multiple loaded volumes, and the Camera Controls panel to adjust rendering
 * quality and clipping planes.
 *
 * See also
 * - Bvv.open(String), Bvv.setChannelColors(), Bvv.setDisplayRange()
 * - https://imagej.net/SNT/Scripting
 *
 * TF 20260224
 */

import sc.fiji.snt.viewer.Bvv

try {
    def paths = [file1, file2].findAll { it != null }.collect { it.getAbsolutePath() }
    bvv = Bvv.open(paths as String[])
} catch (Exception e) {
    ui.showDialog("An error occurred: " + e.getMessage(), "Error")
    e.printStackTrace()
}

// The following lines showcase the Bvv scripting API
// ============================================================

// # Channel colors and display range

// Set channel colors by name (CSS/HTML names and hex strings accepted)
// Colors are assigned sequentially across all channels in load order
//bvv.setChannelColors("cyan", "magenta")

// Target a specific source group (useful when multiple images are loaded):
//def group = bvv.getMultiSources().get(0)
//bvv.setChannelColors(group, "green", "magenta")

// Set the min/max display range for a specific group:
//bvv.setDisplayRange(group, 200, 4000)

// # Loading multiple images

// Open multiple files into the same viewer window:
//bvv2 = Bvv.open(img1, img2)

// # Path/tree overlays

// Overlay all traced paths from the active SNT session (snt-aware instance only):
//bvv3 = Bvv.open(snt)          // open with SNT's loaded image
//bvv3.syncPathManagerList()    // push current Path Manager contents to the overlay

// Add a Tree object:
//bvv.add(tree)

// Remove a tree by its label:
//bvv.removeTree("My Neuron")

// Clear all path overlays:
//bvv.clearAllTrees()

// # Annotation overlays

// Add point annotations (spheres) at SNTPoint coordinates:
//bvv.annotations().addAnnotation(point, 2.5f, java.awt.Color.YELLOW)

// Rendering options (tube thickness, transparency, etc.)

//bvv.getRenderingOptions().setThicknessMultiplier(2.0f)
//bvv.getRenderingOptions().setTransparency(0.8f)  // 1.0 = opaque, 0.0 = transparent

// Viewport offset (shift overlays without moving data)

//bvv.setCanvasOffset(0, 0, 5.0)   // offset in calibrated units

// #Screenshots
//imp = bvv.snapshot() // current scene
//imp = bvv.snapshot("xy") // specific view: "xy", "xz", "yz", "default" (fit-to-viewport), or "current" (scene as-is)
//imp.show()
//bvv.saveSnapshot("/path/to/output.png") // save current scene to a file

// # Viewer access

// Access the underlying BigVolumeViewer, VolumeViewerPanel, or VolumeViewerFrame
//bvv.getViewer()
//bvv.getViewerPanel()
//bvv.getViewerFrame()

