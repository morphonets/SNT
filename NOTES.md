<p align="center"><img src="https://imagej.net/_images/5/5d/SNTLogo512.png" alt="SNT" width="150"></p>
<h1 align="center">Notes</h1>

SNT is both a scripting library and GUI program. More formally, it is a collection of [SciJava](https://scijava.org/) commands (plugins), organized around a common API. The GUI is written in Swing.



## Projects

SNT has incorporated several projects that were previously scattered across the Fiji ecosystem of plugins. Notably:

* Sholl Analysis: Originally hosted at  https://github.com/tferr/ASA ([Project summary](https://github.com/tferr/ASA#sholl-analysis)), _Sholl Analysis_ is now part of SNT. Its dedicated documentation page is at https://imagej.net/Sholl
* Simple Neurite Tracer: The founding ImageJ1 plugin released in 2010. SNT stems from its rewrite. Originally hosted at 

An overview of SNT's history is also provided in the [FAQs](https://imagej.net/SNT:_FAQ).



## Publications

SNT is associated with several publications. Please cite the appropriate manuscripts when you use this software in your own research:

The *SNT* framework is described in:

- Arshadi C, Günther U, Eddison M, Kyle I. S. Harrington KIS, Ferreira T. [SNT: A Unifying Toolbox for Quantification of Neuronal Anatomy](https://doi.org/10.1101/2020.07.13.179325). **bioRxiv** 2020.07.13.179325; doi: https://doi.org/10.1101/2020.07.13.179325 

The _Sholl Analysis_ plugin is described in:

- Ferreira T, Blackman A, Oyrer J, Jayabal A, Chung A, Watt A, Sjöström J, van Meyel D. [Neuronal morphometry directly from bitmap images](http://www.nature.com/nmeth/journal/v11/n10/full/nmeth.3125.html), **Nature Methods** 11(10): 982–984, 2014

The tracer based on *Tubular Geodesics* is described in:

- Türetken E, Benmansour F, Fua P. [Automated Reconstruction of Tree Structures using Path Classifiers and Mixed Integer Programming](https://infoscience.epfl.ch/record/176222/files/turetken_et_al_2012.pdf?version=1), ***IEEE Conference on Computer Vision and Pattern Recognition***, Providence, Rhode Island, 2012

The *Cx3D* simulation engine is described in:

- Zubler, F. & Douglas, R. [A framework for modeling the growth and development of neurons and networks](https://doi.org/10.3389/neuro.10.025.2009). **Front. Comput. Neurosci**. 3, 25 (2009)

Simple Neurite Tracer is described in:

- Longair MH, Baker DA, Armstrong JD. [Simple Neurite Tracer: Open Source software for reconstruction, visualization and analysis of neuronal processes](http://bioinformatics.oxfordjournals.org/content/early/2011/07/04/bioinformatics.btr390.long). **Bioinformatics** 2011




### Dependencies

[SNT](https://imagej.net/SNT) relies heavily on several [SciJava](https://scijava.org/), [SciView](https://imagej.net/SciView), and [Fiji](https://imagej.net/Fiji) libraries. It also relies on other packages developed under the [morphonets](https://github.com/morphonets) umbrella and other external open-source packages. Below is a non-exhaustive list of external libraries on top of which SNT is built:

| Libraries                                                    | Scope/Usage                                           |
| ------------------------------------------------------------ | :---------------------------------------------------- |
| [AnalyzeSkeleton](https://imagej.net/AnalyzeSkeleton), [Skeletonize3D](https://imagej.net/Skeletonize3D) | Handling of skeletonized images                       |
| [Apache Commons](https://commons.apache.org/)                | Misc. utilities                                       |
| [Apache XML Graphics](https://xmlgraphics.apache.org/)       | SVG/PDF export                                        |
| [ImageJ1](https://github.com/imagej/imagej1)                 | ImagePlus and ROI handling                            |
| [imagej-plot-service](https://github.com/maarzt/scijava-plot), [jfreechart](https://xmlgraphics.apache.org/) | Histograms and plots including Reconstruction Plotter |
| [JIDE common layer](https://github.com/jidesoft/jide-oss), [font awesome](https://fontawesome.com/) | GUI customizations                                    |
| [JGraphT](https://jgrapht.org/)                              | Graph theory -based analyses                          |
| [JSON-Java](https://github.com/stleary/JSON-java), [okhttp](https://square.github.io/okhttp/) | Access/query of online databases                      |
| [Jzy3D](http://www.jzy3d.org/)                               | Reconstruction Viewer                                 |
