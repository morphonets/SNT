#@ String (label="Image (URL or absolute path)", description="Streamed datasets: N5, OME-Zarr, IMS or BDV XML") image
#@ String (label="Reconstruction(s) (optional)", required=false, description="URL or absolute path of a TRACES, SWC or JSON file") tracings
#@ String (label="Session type", choices={"Traditional", "SNT Stream: BDV", "SNT Stream: BVV", "Standalone BDV", "Standalone BVV"}, style="radioButtonVertical") session
#@ String (label="Location in stream (optional)", required=false, description="x,y,z[,t]: world coordinates and 1-based timepoint to fly to. Streamed data only") view
#@ UIService ui

/**
 * file:    Create_SNT_Link.groovy
 * author:  Tiago Ferreira
 * info:    Assembles a fiji://snt/ link that opens SNT with the specified data
 *          and copies it to the clipboard.
 */

import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

if (image == null || image.isBlank()) {
    ui.showDialog("An image is required.", "Missing Image")
    return
}

// Percent-encodes a link value (spaces as %20, not +)
encode = { String s -> URLEncoder.encode(s, "UTF-8").replace("+", "%20") }

// Local paths become file: URIs. Anything with a scheme is assumed to be a URL
toSource = { String s ->
    s = s.trim()
    (s ==~ /(?i)[a-z][a-z0-9+.-]+:.*/) ? s : new File(s).toURI().toString()
}

streamed = session != "Traditional"
if (!streamed && view?.trim()) {
    ui.showDialog("Locations are only supported for streamed data.", "Invalid Location")
    return
}

// The session type is always spelled out. Other defaults (bvv, tracer) are omitted
params = [image: toSource(image), stream: streamed.toString()]
if (tracings?.trim()) params.tracings = toSource(tracings)
if (session.endsWith("BDV")) params.viewer = "bdv"
if (session.startsWith("Standalone")) params.tracer = "false"
if (view?.trim()) {
    view = view.replaceAll(/\s/, "")
    if (!(view ==~ /-?\d+(\.\d+)?(,-?\d+(\.\d+)?){2}(,[1-9]\d*)?/)) {
        ui.showDialog("Location must be specified as x,y,z or x,y,z,t (t >= 1).", "Invalid Location")
        return
    }
    params.view = view // commas are legal in query values: no need to encode
}

link = "fiji://snt/open?" + params.collect { k, v -> k + "=" + (k == "view" ? v : encode(v)) }.join("&")

shown = (link.length() > 100) ? link.take(100) + "..." : link
msg = "Link copied to clipboard:\n\n" + shown
if (link.contains("file%3A")) msg += "\n\nNB: It contains local paths and will only work on this machine"
try {
    Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(link), null)
} catch (java.awt.HeadlessException ignored) {
    msg = "Link:\n\n" + link
}
println(link)
ui.showDialog(msg, "SNT Link Created")
