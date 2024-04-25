#@File(label="Input file:", description="Reconstruction file (.traces, .swc, json) to be analyzed") input_file
#@String(label="Restrict to :", choices={"Axon", "Dendrites", "All processes"}) subtree_choice
#@ImageJ ij


"""
file:       Strahler_Analysis.py
author:     Tiago Ferreira
version:    20220110
info:       Performs Strahler Analysis on a single reconstruction file.
            See Batch>Strahler_Bulk_Analysis for a batch processing alternative.
"""

from sc.fiji.snt import Tree
from sc.fiji.snt.plugin import StrahlerCmd


def main():

    # A single file may contain more than one cell, so by default,
    # we'll assume it contains a collection of Trees
    trees = Tree.listFromFile(input_file.getAbsolutePath())
    if not trees or trees.isEmpty():
        ij.ui().showDialog("File did not contain a valid reconstruction.", "Error")
        return
    else:
        msg = []
        for tree in trees:
            if not subtree_choice.startswith("All"):
                tree = tree.subTree(subtree_choice)
                if tree.isEmpty():
                    msg.append("%s does not contain %s." % (tree.getLabel(), subtree_choice))
                    continue
            sa = StrahlerCmd(tree)
            sa.setContext(ij.context())
            if not sa.validInput():
                msg.append("%s is not a valid structure!?" % tree.getLabel())
                continue
            sa.run()
    if msg:
        ij.ui().showDialog("<HTML>The following errors occurred:<br>%s" % '<br>'.join(msg), "Analysis Completed")

main()
