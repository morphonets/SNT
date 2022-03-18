# @String(value="This script measures all SWC/TRACES files in a directory.<br>Measurements are specified in a subsequent prompt",visibility="MESSAGE") msg
# @File(label="Input directory", style="directory") inputDir
# @String(label="Consider only filenames containing",description="Clear field for no filtering",value="") nameFilter
# @UIService uiservice

import sc.fiji.snt.Tree
import sc.fiji.snt.gui.MeasureUI

trees = Tree.listFromDir(inputDir.getAbsolutePath(), nameFilter)
if (trees)
	new MeasureUI(trees).setVisible(true)
else
	uiservice.showDialog('No files matched the specified criteria','Error')
println("Exiting script")
