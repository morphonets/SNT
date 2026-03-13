/**
 * file:  BvvRecording.groovy
 * info:  Exemplifies how to capture BVV's scene states and render scripted fly-through movies.
 *
 * # Workflow
 * 1. Open your data in SNT's BVV
 * 2. Navigate to a view you like and press 'K' to capture a keyframe.
 *    Each press prints a line to the console, e.g.:
 *      kf00 = new Bvv.Keyframe("transform=...|cam=...|visible=...|accel=symmetric|frames=60")
 *    The line is also copied to the clipboard.
 * 3. Repeat for each viewpoint (rotate, zoom, toggle channels, adjust slab, annotations, etc.)
 * 4. Paste all captured keyframes into this script, set the output directory, and run it.
 *
 * # Keyframes and Keyframe Options
 *
 * A keyframe stores viewer transform (rotation/zoom/pan), camera depth and slab clipping, visible
 * channels/paths/annotations, easing curve, and the number of frames for the transition into that
 * keyframe. A serialized keyframe looks like this:
 *   kf01 = new Bvv.Keyframe("transform=0.8,-0.6,0,100,0.6,0.8,0,-200,0,0,1.0,-300|cam=2000,800,800|visible=vol:Ch1;vol:Ch2;paths|accel=slow_start|frames=90")
 *
 * You can adjust per-keyframe properties after capture:
 *
 *   kf.frames = 90          // transition length (default 60: 2s at 30fps)
 *   kf.setAccel("slow_end") // see acceleration mode
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
 *     "symmetric" is likely the safest default for most transitions
 *  - The 'frames' value of the first Keyframe is ignored since it is the starting pose
 */


// Retrieve the most recently created Bvv instance
def bvv = Bvv.getInstance()

if (bvv == null) {
    println "ERROR: There is no active Bvv instance"
    return
}

// Paste your captured keyframes here (press K in the viewer to capture each one):
// kf00 = new Bvv.Keyframe("...")
// kf01 = new Bvv.Keyframe("...")
// kf02 = new Bvv.Keyframe("...")

// Then list them in playback order:
def kfs = [
    // kf00,
    // kf01,
    // kf02,
]

if (kfs.isEmpty()) {
    println "ERROR: No keyframes defined. Press K in the viewer to capture keyframes,"
    println "  then paste them above and add them to the kfs list."
    return
}


def dryRun = true // Set to false to save animation sequence instead of live preview
if (dryRun) {

	// Live preview playback (no files saved). The viewer
	// displays the current keyframe transition during playback
	bvv.playback(kfs)

	// To preview only a specific transition, use index range or Groovy slicing:
	// bvv.playback(kfs, 1, 3)  // play keyframes 1 > 2 > 3 only
	// bvv.playback(kfs[1..3])  // equivalent using Groovy list slicing

} else {
	
	// Render to image sequence. Defining first the output directory
	// (replace it with your preferred file path)
	def outputDir = new File(System.properties['user.home'], "Desktop/snapshots").tap { mkdirs() }
	bvv.renderFrames(kfs, outputDir.absolutePath) // For long recordings, monitor progress in the console
	println "Frames saved. Combine into a video with e.g.:"
	println "  ffmpeg -framerate 30 -i ${outputDir}/frame_%05d.png -c:v libx264 -pix_fmt yuv420p movie.mp4"
}


// Imports below
import sc.fiji.snt.viewer.Bvv
