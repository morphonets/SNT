#@SNTService snt # scijava parameter

snt.requireVersion("5.0.23") # SNT version required to run this script
"""
This script 'plays' a walkthrough through selected paths in a Stream mode session
(BDV/BVV): the view travels along each path at constant speed, using
sc.fiji.snt.viewer.PathPlayer. The playback can be aborted by clicking on the display.
For classic (ImagePlus-based) sessions, use Tracing > Path_Player.py instead.

File: Path_Player_(Stream_Mode).py
Documentation Resources: https://imagej.net/plugins/snt/scripting
Latest SNT API: https://javadoc.scijava.org/SNT/
"""

# Travel speed (image units per second, <= 0 for the default of 50)
SPEED = 50

# Width of the field of view (image units, <= 0 to keep the current zoom)
FOV = 0


def play_paths():
    """Play selected paths (or all if none is selected) in the active viewer."""
    if not snt.isStreamMode():
        snt.getUI().error(
            "This script requires Stream mode.<br>"
            "Use Tracing > Path Player for classic sessions."
        )
        return

    paths = snt.getSelectedPaths()
    if not paths:
        paths = snt.getPaths()
    if not paths:
        snt.getUI().error("No paths exist.")
        return

    viewer = snt.getUI().getActiveBigViewer()
    if viewer is None:
        snt.getUI().error("No active BDV/BVV viewer found.")
        return

    # Non-blocking: the viewer keeps running while the paths are played
    player = viewer.playPaths(paths, SPEED, FOV)
    if player is None:
        snt.getUI().error("Viewer is not ready for playback.")
        return
    player.onFinished(lambda: snt.getUI().showStatus(player.getSummary(), False))


if __name__ == "__main__":
    play_paths()
