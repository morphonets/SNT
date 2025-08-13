#@SNTService snt # scijava parameter
"""
This script plays through selected paths in SNT, centering and zooming on each
path node sequentially. The playback can be aborted by clicking on the image.

File: Path_Player.py
Documentation Resources: https://imagej.net/plugins/snt/scripting
Latest SNT API: https://javadoc.scijava.org/SNT/
"""

# Imports
from java.awt.event import MouseAdapter
from ij.plugin import Zoom
from sc.fiji.snt import Path

# Define the zoom level of the playback (4=400%, 6=600%, etc.)
ZOOM_LEVEL = 4


class AbortMouseListener(MouseAdapter):
    """Mouse listener that sets abort flag when clicked or pressed."""
    
    def __init__(self):
        self.abort = False # Initialize with abort flag set to False.
    
    def mouseClicked(self, event):
        self.abort = True
    
    def mousePressed(self, event):
        self.abort = True


def get_selected_paths_and_image():
    """
    Get selected paths and current image from SNT.
    Returns:
        tuple: (paths, imp) where paths is list of selected paths
               and imp is the ImagePlus instance, or (None, None) if invalid
    """
    try:
        # Retrieve image and paths. Select all paths if none selected
        imp = snt.getInstance().getImagePlus()
        paths = snt.getSelectedPaths()
        if not paths:
            paths = snt.getPaths()
        if not imp or not paths:
            snt.getUI().error(
                "Either no paths exist or no image is available.<br>"
   	            "If you would like to demo this script, choose the 'Drosophila "
                "OP' neuron from File > Load Demo Dataset... prompt and rerun."
            )
            return None, None
            
        return paths, imp
    except Exception as e:
        snt.getUI().error("Error getting paths and image: {}".format(str(e)))
        return None, None


def play_path(path, imp, abort_listener):
    """
    Play through a single path, moving viewport to each node.
    Args:
        path: SNT Path object to play
        imp: ImagePlus instance
        abort_listener: AbortMouseListener to check for user abortion
    """
    for index in range(path.size()):
        # Check for user abortion
        if abort_listener.abort:
            snt.getInstance().setCanvasLabelAllPanes("Aborting...")
            break
        
        # Display current path name
        snt.getInstance().setCanvasLabelAllPanes(path.getName())
        
        # Get world coordinates and convert to image coordinates
        x = path.getXUnscaled(index)
        y = path.getYUnscaled(index)
        z = path.getZUnscaled(index)
        
        # Set the image position (channel, slice, frame)
        imp.setPosition(path.getChannel(), z, path.getFrame())
        
        # Center image on current location with zoom
        Zoom.set(imp, ZOOM_LEVEL, x, y)


def play_selected_paths():
    """
    Main function to play through all selected paths sequentially.
    Playback can be aborted at any time by clicking on the image.
    """
    # Get paths and image
    paths, imp = get_selected_paths_and_image()
    if not paths or not imp:
        return
    
    # Ensure SNT remains paused during playback
    snt.getUI().pauseTracing(True)
    
    # Adjust viewing options (obtained from Script Recorder)
    snt.getUI().setVisibilityFilter("selected", True)
    snt.getUI().setVisibilityFilter("z-slices", True)
    
    # Create abort mouse listener
    listener = AbortMouseListener()
    
    # Add mouse listener to the image canvas
    imp.getCanvas().addMouseListener(listener)
    
    # Bring image window to front for interaction
    imp.getWindow().toFront()
    
    try:
        # Play through each selected path
        for path in paths:
            if listener.abort:
                break
            snt.getUI().getPathManager().select(path)
            play_path(path, imp, listener)
            
    except Exception as e:
        snt.getUI().error("Error during playback: {}".format(str(e)))
        
    finally:
    	
        # Restore SNT's state and options
        snt.getUI().pauseTracing(True)
        snt.getUI().setVisibilityFilter("selected", False)

        # Remove the mouse listener
        imp.getCanvas().removeMouseListener(listener)
        print("Playback completed")


if __name__ == "__main__":
    """Entry point for the script."""
    play_selected_paths()
