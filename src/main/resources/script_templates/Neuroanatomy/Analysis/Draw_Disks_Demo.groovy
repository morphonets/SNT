#@ SNTService snt

/**
 * file: Draw_Disks_Demo.groovy
 * author: Cameron Arshadi
 * version: 20210913
 * info: A demo which illustrates iteration over SNT's custom cursors by drawing oriented
 *       disks in 3D along an axon.
 */

import net.imglib2.Point
import net.imglib2.util.LinAlgHelpers
import net.imglib2.algorithm.region.hypersphere.HyperSphere
import net.imglib2.img.display.imagej.ImageJFunctions
import sc.fiji.snt.*
import sc.fiji.snt.analysis.*
import sc.fiji.snt.analysis.graph.*
import sc.fiji.snt.analysis.sholl.*
import sc.fiji.snt.annotation.*
import sc.fiji.snt.io.*
import sc.fiji.snt.plugin.*
import sc.fiji.snt.util.*
import sc.fiji.snt.viewer.*
import ij3d.Image3DUniverse
import org.jogamp.vecmath.Color3f

// Documentation Resources: https://imagej.net/plugins/snt/scripting
// Latest SNT API: https://javadoc.scijava.org/SNT/

def drawDisks(path, img, min, max)
{
	pos = new long[img.numDimensions()]
	tangent = new double[3]
	for (i=0; i<path.size(); i++)
	{
		path.getTangent(i, 1, tangent)
		// check if the two points are in the same location, skipping if so
		if (Arrays.stream(tangent).allMatch(e -> e == 0))
			continue
		LinAlgHelpers.normalize(tangent)
		point = new Point(path.getXUnscaled(i), path.getYUnscaled(i), path.getZUnscaled(i))
		cursor = new DiskCursor3D(img, point, 10, tangent)
		while (cursor.hasNext())
		{
			cursor.fwd()
			cursor.localize(pos)
			// Don't attempt to access out-of-bounds points (it will throw an ArrayIndexOutOfBoundsException)
			if (outOfBounds(pos, min, max))
				continue
			cursor.get().set(255)
		}
	}
}

def outOfBounds(pos, min, max)
{
	for (d=0; d<pos.length; d++)
		if (pos[d] < min[d] || pos[d] > max[d])
			return true;
	return false;
}

def main()
{
	imp = snt.demoImage("OP")
	img = ImageJFunctions.wrapReal(imp)
	min = img.minAsLongArray()
	max = img.maxAsLongArray()
	tree = snt.demoTree("OP")
	tree.assignImage(imp)
	path = tree.get(0)
	path.downsample(0.6)
	drawDisks(path, img, min, max)
	Image3DUniverse univ = new Image3DUniverse();
	univ.show();
	univ.addVoltex(imp, new Color3f(0,255,0), "OP_1", 25, new boolean[] {true, true, true}, 1);
}

main()
return null // suppress unsupported output `ij3d.Content` warning
