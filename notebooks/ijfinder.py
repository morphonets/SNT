#!/usr/bin/env python
# -*- coding: utf-8 -*-

import os
from os.path import expanduser
from sys import executable, argv
from subprocess import check_output
from PyQt5.QtCore import QDir
from PyQt5.QtWidgets import QFileDialog, QApplication

# The path to your local Fiji.app installation
local_fiji_dir = r"C:\Users\cam\Desktop\Fiji.app"

def getpath(directory=expanduser("~")):
    # see https://stackoverflow.com/a/46814297
    # run this exact file in a separate process, and grab the result
    file = check_output([executable, __file__, directory])
    return file.strip()

if __name__ == "__main__":
    if os.path.isdir(local_fiji_dir):
        print(local_fiji_dir)
    else:
        directory = argv[1]
        app = QApplication([directory])
        fname = QFileDialog.getExistingDirectory(None,
                            'Choose the Fiji.app directory of your local '
                            'Fiji installation subscribed to the NeuroAnatomy '
                            'Update site', directory)
        if fname:
            fname = QDir.toNativeSeparators(fname)
        print(fname)
