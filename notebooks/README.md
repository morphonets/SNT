# SNT Notebooks

This collection of notebooks demonstrates how to access the [SNT][] [API][] from a
Python environment.


> :information_source: **These instructions were last tested on Ubuntu 22.04 (clean install) on 2022-05-06**



## Download
To run these notebooks from your local machine, download all the files as a
[ZIP archive](https://kinolien.github.io/gitzip/?download=https://github.com/morphonets/SNT/tree/master/notebooks)
and unzip its contents to a local directory. You can also download the files
manually from [GitHub](https://github.com/morphonets/SNT/tree/master/notebooks).
Alternatively, you can clone/fork the entire repository. This may be more advantageous, as you will be able to keep your cloned/forked repository always up-to-date.


## Requirements
The core requirement are [pyimagej] and [Fiji](https://imagej.net/Fiji).

### To Install Fiji:

1. [Download](https://imagej.net/Fiji/Downloads) the program.

### To install pyimagej:

1. Install [conda](https://www.anaconda.com/distribution/). See [documentation][pyimagejdocs]
   for details.

2. Activate the [conda-forge](https://conda-forge.org/) channel and set it default:

  ```bash
  conda config --add channels conda-forge
  conda config --set channel_priority strict
  ```

3. Install [pyimagej][pyimagejdocs] into a new conda environment named `pyimagej`:

  ```bash
  conda create -n pyimagej pyimagej openjdk
  ```

  At this point, it is convenient to make the new `pyimagej` environment available
  in the graphical notebook interface:

  ```bash
  conda install -n pyimagej ipykernel
  conda activate pyimagej
  python -m ipykernel install --user --name=pyimagej
  ```

  Now, when you now start jupyter, it will show `pyimagej` in the list of
  registered kernels. Selecting it, makes all the packages installed in the
  `pyimagej` environment available.


## Setup
Before running the notebooks, there are three more things to take care of:

1. Make sure your Fij installation is up-to-date and subscribed to the
   [NeuroAnatomy update site](https://imagej.net/SNT#install)

2. Make the notebooks aware of your local `Fij.app` location. You can do so by
   editing [ijfinder.py](./ijfinder.py):

  ```python
  local_fiji_dir = r'/path/to/your/local/Fiji.app'
  ```
  (replacing `/path/to/your/local/Fiji.app` with the actual path to `Fiji.app`).
  If you skip this step, you will be prompted to choose the Fiji directory
  every-time `ijfinder.py` is called.

3. If you haven't done so, install the packages required to run these notebooks
   in the `pyimagej` environment. The majority requires popular packages
   available from the default anaconda channel, e.g.:

  ```bash
  conda activate pyimagej
  conda install pyqt jupyterlab matplotlib pandas seaborn scikit-learn
  ```

  However, some notebooks require other packages only in `conda-forge`:

  ```bash
  conda activate pyimagej
  conda install --channel=conda-forge trimesh
  ```
  Some functionality may require [blender](https://www.blender.org/download/).


## Running
Activate the `pyimagej` environment (if you have not registered it in `ipykernel`)
and start jupyter from the _notebooks_ [directory](./):

```bash
cd /path/to/notebooks/directory
conda activate pyimagej
jupyter notebook
```

(replacing `/path/to/notebooks/directory` with the path to the actual directory
where you unzipped the _notebooks_ directory).


> :information_source: Some users have reported a `jupyter-notebook not found` error when calling jupyter notebook from the pyimagej environment. A quick workaround is to use jupyter-lab instead (see details below)


If you prefer JupyterLab (or the 'regular' jupyter-notebook is failing from the pyimagej environment, replace
`jupyter notebook` with `jupyter-lab`. If not present, you may need to install it on the `pyimagej` environment:

```bash
conda activate pyimagej
conda install jupyterlab
jupyter-lab
```

## Troubleshooting

> :warning: Running SNT from python is pretty much a bleeding edge experience. Things are being actively developed and somethings may break. That being said, it is certainly possible (hundreds of people use it frequently). Your feedback is key! Please do [reach out](https://forum.image.sc/) if you run into issues.


### Installation
If your are are having problems setting up your conda environment, it may be useful
to ensure your conda is up-to-date:

```
conda update -n base -c defaults conda
```

There have been confusing reports of errors related to missing `libjvm.so` files
with java 8 (on Ubuntu 19.10). Adopting a newer openjdk (and pyjnius) seems to
fix this:

```bash
conda activate pyimagej
conda install openjdk=11
```

Note that installing packages from multiple channels may lead to conflicts.
Packages served by e.g., `conda-forge` and the regular `defaults` channel may
not be 1000% compatible. You can impose a preference for `conda-forge` by having
it listed on the top of your `.condarc` file, and by specifying the priority
policy, so that packages install from `conda-forge` by default:

```bash
conda config --add channels conda-forge
conda config --set channel_priority strict
```

Otherwise, you can also use the `-c` flag to specify a package from a specific
channel. E.g., You can install matplotlib from the `defaults` channel:

```bash
conda activate pyimagej
conda install -c defaults matplotlib
```
or from `conda-forge`:

```bash
conda activate pyimagej
conda install -c conda-forge matplotlib
```

#### Converting to/from Java
Certain Java objects returned by SNT may need to be converted to the equivalent Python
representation, and vice versa. This is achieved by calling pyimagej's `ij.py.from_java()` 
and `ij.py.to_java()` methods. However, for basic data types such as a Java `List`, it is possible to
cast to a Python `list` by calling `list()`. Generally, it is only necessary to use these conversion methods if you run
into an error when passing Python data types to Java methods and vice versa. Since pyimagej uses [JPype](https://github.com/jpype-project/jpype) internally, it is often possible to use certain Java objects as if
they were Python objects. For example:

```python
>>> from scyjava import jimport
>>> ArrayList = jimport('java.util.ArrayList')
>>> arrayList = ArrayList([1,2,3,4,5])
# Basic slicing is supported
>>> arrayList[:-1]
<java object 'java.util.ArrayList.SubList'>
>>> print(arrayList[:-1])
[1, 2, 3, 4]
# As are certain Python list methods such as append() and extend()
>>> arrayList.extend([6,7])
>>> print(arrayList)
[1, 2, 3, 4, 5, 6, 7]
# However, if we try to stride...
>>> arrayList[::-1]
Traceback (most recent call last):
  File "<stdin>", line 1, in <module>
  File "C:\Users\cam\miniconda3\envs\pyimagej-1.0\lib\site-packages\jpype\_jcollection.py", line 91, in __getitem__
    ndx = _sliceAdjust(ndx, self.size())
  File "C:\Users\cam\miniconda3\envs\pyimagej-1.0\lib\site-packages\jpype\_jcollection.py", line 65, in _sliceAdjust
    raise TypeError("Stride not supported")
TypeError: Stride not supported
# Nonetheless, we can simply cast to a Python list with list()
>>> list(arrayList)[::-1]
[7, 6, 5, 4, 3, 2, 1]
```

For more details have a look at the [SNT](./1_overview.ipynb) and
[pyimagej][pyimagej_intro] introductory notebooks.



## Resources


| SNT                                               | pyimagej                                               |
|---------------------------------------------------|--------------------------------------------------------|
| [Documentation][snt]                              | [Documentation][pyimagejdocs]                              |
| [API]                                             | [Getting Started][pyimagej_intro]                      |
| [Source code](https://github.com/morphonets/SNT)  | [Source code](https://github.com/imagej/pyimagej)      |
| [Image.sc Forum](https://forum.image.sc/tag/snt/) | [Image.sc Forum](https://forum.image.sc/tag/pyimagej/) |


[snt]: https://imagej.net/SNT
[api]: https://morphonets.github.io/SNT
[pyimagej]: https://github.com/imagej/pyimagej
[pyimagejdocs]: https://pyimagej.readthedocs.io/en/latest/
[pyimagej_intro]: https://nbviewer.jupyter.org/github/imagej/tutorials/blob/master/notebooks/1-Using-ImageJ/6-ImageJ-with-Python-Kernel.ipynb
