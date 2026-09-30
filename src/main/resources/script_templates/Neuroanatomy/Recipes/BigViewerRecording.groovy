/**
 * file:  BigViewerRecording.groovy
 * info:  Exemplifies how to capture BVV/BDV scene states and render scripted fly-through movies.
 *
 * # Workflow
 * 1. Open your data in SNT's BVV or BDV
 * 2. Navigate to a view you like and press 'K' to capture a keyframe.
 *    Each press appends a line to a running 'kfs' block that is printed to the console and
 *    copied to the clipboard, e.g.:
 *      def kfs = [
 *        new Keyframe("transform=...|cam=...|visible=...")
 *          .frames(60).accel("symmetric"),
 *      ]
 * 3. Repeat for each viewpoint (rotate, zoom, toggle channels, adjust slab, annotations, etc.)
 *    Press Shift+K to discard the captured keyframes and start over
 * 4. Paste the block into this script (replacing the 'kfs' definition below), adjust the frames
 *    and accel of each keyframe, set the output directory, and run it.
 *
 * # Keyframes and Keyframe Options
 *
 * A keyframe stores viewer transform (rotation/zoom/pan), camera depth and slab clipping (BVV only),
 * timepoint, display levels and LUT colors (both interpolated between keyframes), visible
 * channels/paths/annotations, easing curve, and the number of frames for the transition into that
 * keyframe. A captured keyframe looks like this:
 *   new Keyframe("transform=0.8,-0.6,0,100,0.6,0.8,0,-200,0,0,1.0,-300|cam=2000,800,800|visible=vol:Ch1;vol:Ch2;paths")
 *       .frames(90).accel("slow_start")
 *
 * Edit the trailing calls to adjust the transition into each keyframe:
 *
 *   .frames(90) // transition length (default 60: 2s at 30fps)
 *   .accel("slow_end") // see acceleration modes
 *
 * Acceleration modes (cosine-based easing curves):
 *   "symmetric"        Ease in and out equally (smooth start and stop)
 *   "slow_start"       Gradual departure, fast arrival (camera lingers then rushes)
 *   "slow_end"         Fast departure, gentle arrival (camera rushes then settles)
 *   "soft_symmetric"   Double-cosine: nearly linear in the middle, very gentle at both ends
 *   "soft_slow_start"  Extra-gentle departure, crisp arrival
 *   "soft_slow_end"    Crisp departure, extra-gentle arrival
 *
 * Tips:
 *  - "slow_end" may work well for approaching a structure; "slow_start" for pulling away.
 *  - "symmetric" is likely the safest default for most transitions
 *  - The 'frames' value of the first Keyframe is ignored since it is the starting pose
 *  - See the 'Recipes' section below for functions that edit recorded keyframes
 */


// Retrieve the most recently created Bvv (or Bdv) instance
def viewer = Bvv.getInstance() ?: Bdv.getInstance()

if (viewer == null) {
	println "ERROR: There is no active Bvv or Bdv instance"
	return
}

// Paste the keyframes block copied by the K key here (in playback order):
def kfs = [
	// new Keyframe("...")
	//     .frames(60).accel("symmetric"),
	// new Keyframe("...")
	//     .frames(60).accel("symmetric"),
]

// Optional edits of the keyframes above (see Recipes below), to be applied before playback/rendering:
// kfs.each { flipY(it, viewer.getViewerHeight()) } // flip vertically

if (kfs.isEmpty()) {
	println "ERROR: No keyframes defined. Press K in the viewer to capture keyframes,"
	println "  then paste the copied block above."
	return
}

def dryRun = true // Set to false to save animation sequence instead of live preview
if (dryRun) {

	// Live preview playback (no files saved). The viewer
	// displays the current keyframe transition during playback
	viewer.playback(kfs)

	// To preview only a specific transition, use index range or Groovy slicing:
	// viewer.playback(kfs, 1, 3)  // play keyframes 1 > 2 > 3 only
	// viewer.playback(kfs[1..3])  // equivalent using Groovy list slicing

} else {

	// Render to image sequence. Defining first the output directory
	// (replace it with your preferred file path)
	def outputDir = new File(System.properties['user.home'], "Desktop/snapshots").tap { mkdirs() }
	viewer.renderFrames(kfs, outputDir.absolutePath) // For long recordings, monitor progress in the console
	println "Frames saved. Instructions on how to combine them into a video are in ${outputDir}/-build-video.txt"
}

// Recipes below

/**
 * Flip the movie vertically. A plain Y mirror cannot be interpolated between keyframes, so this rotates 180 degrees
 * about the screen X axis instead (same result for a 2D slice). Call it on each keyframe before
 * playback/rendering (see 'kfs.each' above)
 */
def flipY(kf, fallbackHeight) {
	def height = kf.height > 0 ? kf.height : fallbackHeight // height of the canvas at capture
	def f = new AffineTransform3D()
	f.set(1, 0, 0, 0,
		0, -1, 0, height, // y' = -y + height (mirror about the canvas center)
		0, 0, -1, 0) // z' = -z keeps the transform a proper rotation
	kf.transform.preConcatenate(f)
}


// Imports below
import sc.fiji.snt.viewer.Bvv
import sc.fiji.snt.viewer.Bdv
import sc.fiji.snt.viewer.Keyframe
import net.imglib2.realtransform.AffineTransform3D
