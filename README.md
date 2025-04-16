<p align="center"><img src="https://imagej.net/media/icons/snt.png" alt="SNT" width="150"></p>
<h2 align="center">The ImageJ framework for quantification of neuronal anatomy</h2>
<div align="center">

 <!-- rdcu.be -->
  <a href="https://rdcu.be/c59MD">
    <img alt="Publication (Publisher)" src="https://img.shields.io/badge/Publication-Pub.-teal.svg">
  </a>
 <!-- BioRiv -->
  <a href="https://doi.org/10.1101/2020.07.13.179325">
    <img alt="Publication" src="https://img.shields.io/badge/Publication-BioRiv-red.svg">
  </a>
  <!-- Zenodo -->
  <a href="https://zenodo.org/badge/latestdoi/221831995">
    <img alt="Zenodo DOI" src="https://zenodo.org/badge/221831995.svg">
  </a>
  <!-- License -->
  <a href="https://github.com/morphonets/SNT/blob/master/LICENSE.txt">
    <img alt="GitHub license" src="https://img.shields.io/github/license/morphonets/SNT">
  </a>
<br>
  <!-- Build Status -->
  <a href="https://github.com/morphonets/SNT/actions/workflows/build.yml">
    <img alt="Build status" src="https://github.com/morphonets/SNT/actions/workflows/build.yml/badge.svg">
  </a>
  <!-- Gitpod -->
  <a href="https://gitpod.io/#https://github.com/fiji/SNT">
    <img alt="Gitpod ready-to-code" src="https://img.shields.io/badge/Gitpod-ready--to--code-blue?logo=gitpod">
  </a>
  <!-- codefactor -->
  <a href="https://www.codefactor.io/repository/github/morphonets/snt"><img src="https://www.codefactor.io/repository/github/morphonets/snt/badge" alt="CodeFactor" /
></a>
<br>
  <!-- Forum -->
  <a href="https://forum.image.sc/tags/snt">
    <img alt="Forum.sc topics" src="https://img.shields.io/badge/dynamic/json.svg?label=forum&url=https%3A%2F%2Fforum.image.sc%2Ftag%2Fsnt.json&query=%24.topic_list.tags.0.topic_count&suffix=%20topics">
  </a>
  <!-- Issues -->
  <a href="https://github.com/morphonets/SNT/issues">
    <img alt="Open issues" src="https://img.shields.io/github/issues/morphonets/SNT">
  </a>
  <a href="https://github.com/morphonets/SNT/issues">
    <img alt="Closed issues" src="https://img.shields.io/github/issues-closed/morphonets/SNT">
  </a>
</div>
<div align="center">
  <h3>
    <a href="https://github.com/morphonets/SNT#features">
      Features
    </a>
    <span style="margin:.5em">|</span>
    <a href="https://github.com/morphonets/SNT#installation">
      Installation
    </a>
    <span style="margin:.5em">|</span>
    <a href="https://github.com/morphonets/SNT#contributing">
      Contributing
    </a>
    <span style="margin:.5em">|</span>
    <a href="https://imagej.net/SNT">
       Documentation
    </a>
    <span style="margin:.5em">|</span>
    <a href="https://morphonets.github.io/SNT/">
      API
    </a>
    <span style="margin:.5em">|</span>
    <a href="https://forum.image.sc/tag/SNT">
      Support
    </a>
  </h3>
</div>

SNT is [ImageJ](https://imagej.net/)'s framework for semi-automated tracing, visualization, quantitative analyses and modeling of neuronal morphology. For tracing, SNT supports modern multidimensional microscopy data, and highly-customizable routines. For data analysis, SNT features advanced visualization tools, access to all major morphology databases, and support for whole-brain circuitry data.

SNT can be used as a regular application or as a scripting library. Python (through [pyimagej](https://github.com/imagej/pyimagej)) and  all of SciJava's scripting languages are supported. It is distributed with [Fiji](https://imagej.net/Fiji) and supersedes the original [Simple Neurite Tracer](#backwards-compatibility) plug-in. It also incorporates several other neuroanatomy-related Fiji plugins. **See  [SNT's publication](https://doi.org/10.1038/s41592-021-01105-7)  and [techical notes](./NOTES.md) for details**.

## Overview

[![Overview](https://user-images.githubusercontent.com/2439948/167173119-2e4bea60-38e6-437f-82a9-205700f83ae8.png)](https://www.nature.com/articles/s41592-021-01105-7)

## Features
For an overview of SNT capabilities have a look at the [showcase gallery](https://imagej.net/plugins/snt/#overview).

<details>
  <summary><b>Detailed List</b></summary>

### Tracing

* Semi-automated Tracing:
  
  * Support for up to 5D multidimensional images, including multichannel, and timelapse sequences
  * Support for both ImageJ1 and [ImgLib2](https://imagej.net/libs/imglib2/) data structures
  * Several bidirectional search algorithms (A\*, NBA\*, Fast marching) with adjustable cost functions allow for efficient computation of curvatures for a wide range of imagery, that are <u>up to 20x faster</u> relatively to the original _Simple Neurite Tracer_ plugin
  * Tracing in "secondary layers". This allows for paths to be computed on "enhanced" (pre-processed) images while interacting with the unfiltered, original image (or vice-versa). Toggling between the two data sources is immediate
  * Precise placement of nodes is aided by a local search that automatically snaps the cursor to neurites wihin a 3D neighborhood

* Auto-tracing:

  * Generation of traces from thresholded/filtered images
  * Machine learning: Built-in routines for training random forest classifiers on previously traced paths ([LabKit](https://github.com/juglab/labkit-ui)/[Trainable Weka segmentation](https://github.com/fiji/Trainable_Segmentation) bridges)

* Tracing can be interleaved with image processing routines

* Tracing is scriptable. Interactive scripts allow for real-time inspection of results

* Paths can be tagged, searched, grouped and filtered by morphometric properties (length, radius, etc.)

* Paths can be edited, i.e., linked, merged, or split. Nodes can be moved, deleted, or inserted

* Post-hoc refinement of node positioning and radii by 'fitting' traces to the fluorescent signal associated with a path
  
### Analysis

* Extensive repertoire of [metrics](https://imagej.net/plugins/snt/metrics). Metrics can be collected from groups of cells, single cells, or parts thereof

* Analysis based on neuropil annotations for whole-brain data such as [MouseLight](https://ml-neuronbrowser.janelia.org/)

* Direct access to public databases, including [FlyCircuit](http://www.flycircuit.tw), [Insect Brain Database](https://insectbraindb.org/app/), [MouseLight](https://ml-neuronbrowser.janelia.org/), [NeuroMorpho](http://neuromorpho.org/), and [Virtual Fly Brain](https://v2.virtualflybrain.org/)

* Built-in commands for immediate retrieval of statistical reports, including summary statistics, tests (two-sample _t_-test/one-way ANOVA), comparison plots and histograms

* [Convex hull](https://imagej.net/plugins/snt/analysis#convex-hull-analysis) analyses

* [Delineation analyses](https://imagej.net/plugins/snt/walkthroughs#delineation-analysis)

* [Graph theory-based analyses](https://imagej.net/plugins/snt/analysis#graph-based-analysis)

* [Persistent homology-based analyses](https://imagej.net/plugins/snt/analysis#persistence-homology)

* [Root angle analysis](https://imagej.net/plugins/snt/analysis#root-angle-analysis)

* [Sholl](./NOTES.md) and [Horton-Strahler](https://imagej.net/plugins/snt/analysis#strahler-analysis) analyses

* Image processing workflows: Reconstructions can be converted to masks and ROIs. Voxel intensities can be profiled around (or across) traced paths

* Labkit and TWS integration ([Semantic Segmentation](https://imagej.net/plugins/snt/machine-learning))
  
### Visualization

* Quantitative visualizations: Display neurons color coded by morphometric traits, or neuropil annotations. 

* Publication-quality visualizations:  Neuronal reconstructions, diagrams, plots and histograms can be exported as vector graphics

* [Reconstruction Viewer](https://imagej.net/SNT:_Reconstruction_Viewer): Standalone hardware-accelerated 3D visualization tool for both meshes and reconstructions.
  
  * Interactive and programmatic scenes (controlled rotations, panning, zoom, scaling, animation,  "dark/light mode", etc.)
  * Customizable views: Interactive management of scene elements, controls for transparency, color interpolation, lightning, path smoothing, etc. Ability to render both local and remote files on the same scene
  * Built-in support for several template brains: Drosophila, [zebrafish](https://fishatlas.neuro.mpg.de/), and Allen CCF (Allen Mouse Brain Atlas)

* [sciview](https://github.com/scenerygraphics/sciview) integration

* Graph Viewer: A dedicated viewer for graph-theory-based diagrams
  
  * Display reconstructions as dendrograms
  * Quantitative connectivity graphs for single cells and groups of cells
  
### Scripting

* Almost every aspect of the program can be scripted in any of the IJ2 supported languages, or from Python through [pyimagej](https://github.com/imagej/pyimagej)

* Detailed [documentation](https://imagej.net/SNT:_Scripting) and examples, including Python [notebooks](https://github.com/morphonets/SNT/tree/master/notebooks), and [end-to-end examples](https://github.com/morphonets/SNTmanuscript)

* Headless scripts supported

* (Experimental) Script Recorder
  
### Modeling

* Biophysical modeling of neuronal growth is performed through [Cortex3D (Cx3D)](https://github.com/morphonets/cx3d) and [sciview](https://docs.scenery.graphics/sciview/ "SciView"), in which a modified version of [Cx3D](https://github.com/morphonets/cx3d) grows neuronal processes with [sciview](https://docs.scenery.graphics/sciview/)’s data structures.
  
### Compatibility

* Support for multiple file formats including SWC, TRACES, JSON (MouseLight specification), and NDF (NeuronJ data file)

* Backwards compatibility: Special effort was put into backwards compatibility with  [Simple Neurite Tracer](https://github.com/fiji/SNT)  (including [TrakEM2](https://github.com/trakem2/TrakEM2) and [ITK](https://imagej.net/SNT:_Tubular_Geodesics) interaction)
  
* Aggregation of [legacy plugins](./NOTES.md)
  

</details>

## Installation

SNT is a [Fiji](https://imagej.net/Fiji) plugin, currently distributed through the *Neuroanatomy* [update site](https://imagej.net/Update_Sites).

The first time you start SNT from Fiji's menu structure *(Plugins>Neuroanatomy>SNT...*) you should be prompted for automatic subscription and download of required dependencies. If not:

1. Run the Fiji Updater (*Help › Update...*, the penultimate entry in the  *Help ›*  menu)
2. Click *Manage update sites*
3. Select the *Neuroanatomy* checkbox
4. Optionally, select the *sciview* checkbox. This is only required for extra [sciview](https://docs.scenery.graphics/sciview/) functionality
5. Click *Apply changes* and Restart Fiji. SNT commands are registered under _Plugins>Neuroanatomy>_ in the main menu and SNT scripts under _Templates>Neuroanatomy>_ in Fiji's Script Editor.

Problems? Have a look at the full [documentation](https://imagej.net/SNT).

## Developing

### On the cloud

Use this button to open the project on the cloud using [Gitpod](https://gitpod.io). No local installation necessary (although project may take a while to load).

[![Open in Gitpod](https://gitpod.io/button/open-in-gitpod.svg)](https://gitpod.io/#https://github.com/morphonets/SNT) 

### Locally

1. Clone the main branch of this repository (use the green _code_ button above the list of files) 

2. Import the project into an IDE such as [Eclipse](https://www.eclipse.org/downloads/packages/)/[IntelliJ](https://www.jetbrains.com/idea/download/)/[NetBeans](https://netbeans.apache.org/download/index.html):
   - In Eclipse: Run _Import> Existing Maven Projects_ and specify the path to the downloaded `SNT` folder in _Root Directory_
   - In IntelliJ: In the _Welcome Prompt_, choose _Open or Import_ and specify the path to the downloaded `SNT` folder
   - In NetBeans: Run _File> Open Project..._, select the downloaded `SNT` directory, and click on _Open Project_

3. Wait for all the dependencies to be downloaded, and locate the [StartImageJAndSNTDemo](./src/test/java/sc/fiji/snt/demo/StartImageJAndSNTDemo.java) class in the tests folder.

4. Java 21 is recommended to run SNT, so you should specify it as the project JDK. However using Java17+ or newer **requires the following VM arguments to be specified:** `--add-opens java.base/java.lang=ALL-UNNAMED`. To do so:
   - In Eclipse: Run -> Run Configurations..., Arguments tab
   - In IntelliJ: Run -> Edit Configurations..., Add VM Options (Alt+V)

![image](https://github.com/user-attachments/assets/1679e954-dc89-4061-bfa2-b4a10da4f0da)

5. Run [StartImageJAndSNTDemo.main()](./src/test/java/sc/fiji/snt/demo/StartImageJAndSNTDemo.java)

### Useful Resources to Start Hacking SNT

From a Java IDE:
- [Test demos](./src/test/java/sc/fiji/snt/demo/)
- _main()_ methods found on most classes: Frequently, these showcase the class's functionality
- [JUnit tests](./src/test/java/sc/fiji/snt/)

From Fiji's Script Editor:
- Scripts in the _Templates>Neuroanatomy>_ menu. These are part of the source code and can also be accessed from [Script templates](./src/main/resources/script_templates/Neuroanatomy/) 

From python:
- [Jupyter notebooks](./notebooks)

Snippets and code templates:
- [Jupyter notebooks](./notebooks)
- [Script templates](./src/main/resources/script_templates/Neuroanatomy/)
- [Examples from the SNT manuscript](https://github.com/morphonets/SNTmanuscript)
- [Examples from I2K tutorials](https://github.com/morphonets/i2k2020)
- [Snippets across the forum](https://forum.image.sc/tag/snt)

## Contributing

Want to contribute? Please, please do! We welcome [issues](https://github.com/morphonets/SNT/issues) and [pull requests](https://github.com/morphonets/SNT/pulls) any time. You can also report bugs and propose improvements using the [forum](https://forum.image.sc/tag/snt). Please tag your post using `snt` so that it does not go unnoticed. 

## Thanks To All Contributors

Thanks a lot for spending your time helping SNT!

<a href="https://github.com/morphonets/SNT/graphs/contributors">
  <img src="https://contributors-img.web.app/image?repo=morphonets/SNT" alt="Contributors"/>
</a>
