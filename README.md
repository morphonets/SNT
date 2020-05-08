[![](https://travis-ci.org/morphonets/SNT.svg?branch=master)](https://travis-ci.org/fiji/SNT)

## SNT

The [ImageJ](https://imagej.net/) framework for semi-automated tracing of neurons
and other tube-like structures. It is part of the [Fiji distribution](https://imagej.net/Fiji)
of ImageJ. For details, please see https://imagej.net/SNT.

Because the ongoing work does not guaranty full backwards compatibility with previous versions, the latest pre-releases
are not yet made available through Fiji's main update site but through the [Neuroanatomy](https://imagej.net/Neuroanatomy)
update site. Bugs are expected during this period, please report them [here](https://github.com/fiji/Simple_Neurite_Tracer/issues)
below is a list of some of the latest SNT features:

### Features

#### Tracing
* Support for multidimensional images (including multichannel, and those with a time axis).
  While tracing, visibility of non-traced channels can be toggled at will
* Precise placement of nodes is aided by a local search that automatically snaps the cursor to
  to brightest voxels in a 3D neighborhood and automatically brings the cursor to the focal plane
* A* search can be performed on a second, non-displayed image.
  This allows for e.g., tracing on a pre-process (filtered) image while interacting with the unfiltered image (or vice-versa).
  If enough RAM is available toggling between the two data sources is immediate
* Tracing can be interleaved with image processing routines (see SNT's "Pause" mode)
* Blending of automated workflows with manual curation steps
* Automated tracing using a list of source/target locations
* Paths can now be edited, i.e., a path can be merged into a existing one, or split into two
  Nodes can be moved, deleted, or inserted (see SNT's "Edit mode")
* Semi-automated tracing can be toggled at will (see SNT's "Manual mode")
* Improved support for sub-pixel accuracy
* XY, ZY, and XZ tracing canvases with improved synchronization

#### Analysis
* Traced paths can be tagged, searched, grouped and filtered by morphometric properties (length, radius, etc.)
  Analyses can be performed on all paths, or filtered subsets
* Direct access to public databases, including [MouseLight](https://ml-neuronbrowser.janelia.org/), [FlyCircuit](http://www.flycircuit.tw) and [NeuroMorpho](http://neuromorpho.org/)
* Paths can be color-coded by morphometric properties: e.g., compartment, length, radii or depth
* Paths can be converted to ROIs and voxel intensities profiled
* Suite of commands for calculating morphometric properties of traced arbors and retrieval of descriptive statistics, including plots and histograms
* Detailed Sholl and Horton-Strahler analyses
* Graph Analysis

#### Volume Reconstruction
* Since the first release, the 3D volume of the traced structured can be reconstructed
  using either 1) Dijkstra's filling algorithm (see SNT's "FIll Manager") or 2) a 'fitting'
  algorithm that fits circular cross-sections around the fluorescent signal of a path.
  Both approaches have been improved and the latter can now be used to perform sub-pixel
  refinement of node positioning

#### Scripting
* `SNTService`: Programmatic access to the full SNT API *during* an interactive tracing session.
* It is now possible to run scripts (in any of the IJ2 supported languages) directly from within SNT.
  See examples on either the _Templates_ menu of the Script Editor or SNT's _Scripts_ menu
* GUI and headless scripts for batch and advanced analyses of multiple files

#### Reconstruction Viewer
* The Reconstruction Viewer is a powerful OpenGL 3D visualization tool for both surface meshes and reconstructions
* It can be used as a standalone program or from withing SNT
* Replaces most of the functionality of the outdated legacy 3D Viewer, with extended features:
  * Advanced rendering supporting axes, transparency, color interpolation and path smoothing
  * Interactive scenes (controlled rotations, panning, zoom, scaling, animation, "dark"/"light" mode)
  * Tools for management and customization of scene elements
  * Ability to render both local and remote files on the same scene
  * Loading of surface meshes of several template brains (Drosophila and Allen CCF (Allen Mouse Brain Atlas))

#### SciView Integration
* SciView and Cx3D integration

#### Reconstruction Plotter
* The plotter allows reconstructions to be rendered in an interactive plot capable of vector graphics export (SVG/PDF)
* It can be used as a standalone program or from withing SNT

#### Performance
* Multi-threading improvements
* Migration to Scijava allows most commands to run headless
* More responsive tabbed GUI with extensive options and shortcuts

