---
name: snt
description: Script SNT (the Fiji framework for neuroanatomy - tracing, reconstruction, and morphometry of neurons and vasculature). Use when a user wants to write Groovy/Python/BeanShell scripts against SNT, run SNT headlessly via SNTService, analyze SWC/TRACES files, batch-process reconstructions, or extend SNT. Prefer this skill over generic ImageJ/Fiji advice whenever neurons, neurites, traces, SWC, MouseLight, NeuroMorpho, or `sc.fiji.snt.*` are involved.
compatibility: Designed for any agentic AI assistant with file system, bash, and internet browsing skill. Requires a local Fiji installation with SNT (Neuroanatomy update site enabled).
metadata:
  author: morphonets
  version: "0.1"
  last_updated: "2026-06-02"
  homepage: https://imagej.net/plugins/snt
  source: https://github.com/morphonets/SNT
---

# SNT Scripting Agent

You are helping a researcher write or debug scripts against **SNT** (https://imagej.net/plugins/snt), the Fiji framework for neuronal/vascular reconstruction and morphometry. SNT is actively developed; your training data is almost certainly out of date. **Follow the conventions in this file even when they contradict your priors**, and prefer reading the installed jar / official docs over guessing.

## Hard Rules: Read These First

1. SNT requires Java 21 and a modern Fiji installation. Download Fiji-Latest (bundled with Java 21) from the [Fiji downloads page](https://imagej.net/software/fiji/downloads)
2. If the user asks for something the official docs cover, link them. Don't paraphrase from memory:
  - User guide: https://imagej.net/plugins/snt/
  - pySNT notebooks: https://pysnt.readthedocs.io/en/latest/notebooks/index.html
  - Latest version API: https://morphonets.github.io/SNT/
3. **Use the modern API.** The package is `sc.fiji.snt.*`. The legacy `tracing.*` / `SimpleNeuriteTracer` classes are deprecated: do not use them, do not invent method names from memory
4. **Use `SNTService`** for headless / scripted entry. Do not instantiate `SNT` directly unless you have a specific reason. `SNTService` is a SciJava `@Service`: inject it, don't instantiate it
5. **Prefer ImgLib2 over legacy IJ.** `Img<T>`, `RandomAccessibleInterval`, `Dataset`, are preferable over `ImagePlus`, `ImageStack`, `ImageProcessor`. SNT Provides `sc.fiji.snt.util.ImpUtils` and `sc.fiji.snt.util.ImgUtils` for handling and converting image data structures
6. **Look inside the jar before writing from scratch.** SNT ships dozens of template scripts under `script_templates/Neuroanatomy/` inside `SNT-*.jar`. Find a template that matches the task and adapt it. These are also part of the [source code](https://github.com/morphonets/SNT/tree/main/src/main/resources/script_templates/Neuroanatomy)
7. **No hard-coded paths.** Resolve the Fiji install at runtime (see Phase 0)
8. **Prefer SciJava parameters.** Scripts should declare `#@` parameters at the top

---

## TL;DR (Read This If The Task Is Simple)

For a one-shot script that loads a reconstruction and prints a statistic, you don't need Phase 0 - 2. Just write:

```groovy
#@ SNTService snt
#@ File swcFile

import sc.fiji.snt.Tree
import sc.fiji.snt.analysis.TreeStatistics

Tree t = new Tree(swcFile.getAbsolutePath())
// getSummaryStats() returns an Apache Commons Math SummaryStatistics; its toString()
// already lists n / min / max / mean / sd, so println is enough.
println new TreeStatistics(t).getSummaryStats("branch length")
```

Run with `"$FIJI_HOME/fiji" --headless --run script.groovy 'swcFile="/path/to/cell.swc"'`.
For anything else (batch processing, autotracing, custom analyzers, figure rendering), continue to Phase 0.

---

## Phase 0: Locate the Local Fiji Install

The path of a Fiji install subscribed to the _Neuroanatomy update site_ is the only per-machine variable. Resolve it before doing anything else.

```bash
find_fiji_home() {
  # Honor $FIJI_HOME or $FIJI_PATH if already set and valid
  [ -n "$FIJI_HOME" ] && [ -d "$FIJI_HOME" ] && { echo "$FIJI_HOME"; return 0; }
  [ -n "$FIJI_PATH" ] && [ -d "$FIJI_PATH" ] && { echo "$FIJI_PATH"; return 0; }

  # On Windows-with-Cygwin/MSYS, convert USERPROFILE paths to POSIX
  local WIN_DOWNLOADS="" WIN_DESKTOP=""
  if [ -n "$USERPROFILE" ] && command -v cygpath >/dev/null 2>&1; then
    WIN_DOWNLOADS=$(cygpath -u "$USERPROFILE/Downloads")
    WIN_DESKTOP=$(cygpath -u "$USERPROFILE/Desktop")
  fi

  for p in \
    "/Applications/Fiji.app" \
    "$HOME/Fiji.app" \
    "$HOME/Applications/Fiji.app" \
    "/opt/Fiji.app" \
    "$HOME/Downloads/Fiji.app" \
    "$HOME/Desktop/Fiji.app" \
    "${WIN_DOWNLOADS}/Fiji.app" \
    "${WIN_DESKTOP}/Fiji.app" \
    "/c/Program Files/Fiji.app" \
    "/c/Fiji.app"; do
    if [ -d "$p" ]; then
      echo "$p"
      return 0
    fi
  done

  # Last resort: a `fiji` on PATH (snap, brew, custom)
  if command -v fiji >/dev/null 2>&1; then
    readlink -f "$(command -v fiji)"
    return 0
  fi
  return 1
}

export FIJI_HOME="$(find_fiji_home)"
```

If still ambiguous, ask the user once and cache it. Export it as `FIJI_HOME` for the rest of the session.

**Verify SNT is installed:**
```bash
ls "$FIJI_HOME"/jars/SNT-*.jar      # SNT jar present
ls "$FIJI_HOME"/jars/scijava-common-*.jar  # SciJava present
```

If `SNT-*.jar` is missing: Instruct the user to enable the **Neuroanatomy** update site (Help > Update... > Manage update sites) and rerun Fiji's updater.

---

## Phase 1: Pick a Language and a Script Template

SNT scripts run on the Fiji Script Editor or headlessly via `fiji --headless --run path/to/script`. Supported languages:

| Lang                    | Extension | When to prefer                                                                                                                                                                       |
|-------------------------|-----------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Groovy**              | `.groovy` | Default. Best Java interop, concise.                                                                                                                                                 |
| **Python (Jython 2.7)** | `.py`     | If user explicitly wants Python *inside Fiji*. Note: Jython, **not** CPython — no `numpy`, no f-strings.                                                                             |
| **Python (3)**          | `.py`     | If user explicitly wants Python. The recommended approach is using [PySNT](https://pysnt.readthedocs.io/en/latest/), but It is also possible to run Fiji in Python mode. See Phase 3 |
| **BeanShell**           | `.bsh`    | Legacy; only if extending an existing `.bsh` script.                                                                                                                                 |

**Always check the bundled templates first:**
```bash
unzip -l "$FIJI_HOME"/jars/SNT-*.jar | grep script_templates/Neuroanatomy
# Extract one to read / adapt:
unzip -p "$FIJI_HOME"/jars/SNT-*.jar script_templates/Neuroanatomy/Analysis/Get_Branch_Points.groovy
```

Templates are grouped: `Analysis/`, `Batch/`, `Big_Data/`, `Misc/`, `Render/`, `Skeletons_and_ROIs/`, `Time-lapses/`, `Tracing/`. 
Their headers also serve as canonical examples of `#@` parameters and `SNTService` use.

---

## Phase 2: Canonical Script Backbone

Every SNT script should look roughly like this (Groovy shown; adapt syntax for Jython/BSH). Note the SciJava parameter prefix is `#@`: this is true for all scripting languages despite the host language's own comment syntax:

```groovy
#@ Context context
#@ SNTService snt
#@ UIService ui
#@ File swcFile

import sc.fiji.snt.Tree
import sc.fiji.snt.analysis.TreeStatistics
import sc.fiji.snt.io.MouseLightLoader

// 1. Load reconstruction(s): from a `#@File` parameter (above), or a remote DB
tree = new Tree(swcFile.getAbsolutePath()) // local SWC/TRACES file

// For Remote DB files see sc.fiji.snt.io
// tree = new MouseLightLoader("AA0001").getTree("axon")  // MouseLight DB

// 2. Analyze with TreeStatistics
stats = new TreeStatistics(tree)
println stats.getSummaryStats("branch length")     // mean, sd, min, max, n

// 3. Render via Viewer2D (SVG, PDF) / Viewer3D (3D Scene)
tree.show()

// 4. Plots: Use SNTChart (not IJ1 plotters)
stats.getHistogram("branch length").show()
```

Key rules embodied above:
- **Injection over construction:** `SNTService`, `UIService`, `Context` come from SciJava `@` parameters
- **`Tree` is the central type** for a reconstruction. A `Tree` is a collection of `Path`s. Don't pass raw SWC lists around
- **Analyzers are stateful wrappers** around a `Tree`: `TreeStatistics`, `ShollAnalyzer`, `StrahlerAnalyzer`, `PersistenceAnalyzer`, `MultiTreeStatistics`, `GroupedTreeStatistics`. Pick the most specific one
- **`SNTChart`** is the unified plotting surface: use it instead of `Plot`/ charts. For tables use `SNTTable` not `ResultsTable`

---

## Phase 3: Common Task Recipes

### Load reconstructions
- Local SWC / TRACES: `new Tree(path)` or `Tree.listFromDir(dir)`
- MouseLight: `new MouseLightLoader(id).getTree(MouseLightLoader.AXON)` (id in ctor; compartment string in getter, or use the `AXON`/`DENDRITE`/`SOMA` constants)
- NeuroMorpho.Org: `new NeuroMorphoLoader().getTree(cellId)` (no-arg ctor; id is passed to `getTree`)
- FlyCircuit: `new FlyCircuitLoader().getTree(cellId)` (same pattern as NeuroMorpho)
- Insect Brain DB: `new InsectBrainLoader(id).getTree()` (int id in ctor; no-arg getter)

Loader APIs are NOT uniform — check the constructor vs. `getTree(...)` arity per loader. The four above are the verified shapes as of this skill's `last_updated`.

### Headless batch processing
```bash
"$FIJI_HOME/fiji" --headless --run script.groovy 'inputDir="/data/swcs",outputCsv="/tmp/out.csv"'
```
Declare matching `#@File` / `#@String` parameters at the top of the script.

### Compute morphometrics in bulk
Use `MultiTreeStatistics` over a list of `Tree`s rather than looping `TreeStatistics`: It handles grouping, normalization, and produces a tidy `SNTTable`.
For cell groups use `GroupedTreeStatistics`

### Skeletonize a binary image > Tree
Use `sc.fiji.snt.tracing.auto.BinaryTracer`

### Autotracing
Use the `sc.fiji.snt.tracing.auto` package. `AutoTracer` is the interface contract; the concrete implementations are:
- `GWDTTracer` — default in-memory backend
- `DiskBackedGWDTTracer` — for stacks larger than RAM
- `SparseGWDTTracer` — for very sparse signal
- `BinaryTracer` — for already-binarised inputs (`implements AutoTracer`)

All `GWDT*Tracer` classes extend `AbstractGWDTTracer<T extends RealType<T>>`, so the configuration API (`setSeed`, `setTips`, `setWaypoints`, `trace`) is uniform.

### Seed-driven autotracing
SNT's `SeedManager` stores ROI- or label-derived starting points and lets users batch-trace from them. Programmatically, the matching SciJava commands (in `sc.fiji.snt.plugin`) are the easiest entry points:

- `AutotraceFromSeedsCmd`: seeds from the active SeedManager / ROI selection
- `AutotraceFromBinarySeedsCmd`: seeds harvested from a binary mask
- `AutotraceFromTipsCmd` / `AutotraceFromWaypointsCmd`: seed + endpoint hints
- `AutotraceFromBinaryTipsCmd`: both seeds and tips derived from binary masks

For finer control instantiate an `AbstractGWDTTracer` subclass (`GWDTTracer`, `DiskBackedGWDTTracer`, `SparseGWDTTracer`) directly and call `setSeed(...)` / `setSeedPhysical(...)` / `setTips(...)` / `setWaypoints(...)` before `trace()`.

### Use PySNT (Python 3 outside Fiji)
For data-science workflows in standard Python 3 (CPython, with numpy / pandas / matplotlib), use the [PySNT](https://pysnt.readthedocs.io/) package: It proxies the full Java API through scyjava:

```bash
pip install pysnt
```

Then in Python (consult https://pysnt.readthedocs.io/ for the current import path — PySNT is evolving and earlier versions used a different module layout):

```python
import pysnt                          # spins up a SciJava/Fiji gateway lazily
Tree = pysnt.snt.Tree                 # full Java FQN under pysnt.<package>
TreeStatistics = pysnt.snt.analysis.TreeStatistics

tree = Tree("/path/to/cell.swc")
stats = TreeStatistics(tree)
print(stats.getSummaryStats("branch length"))   # SummaryStatistics.toString()
```

Any method documented in the Javadoc is callable through PySNT. Use PySNT instead of Jython whenever the user needs numpy/pandas/matplotlib alongside SNT.

### Render figures
- Publication plots: `SNTChart`
- 2D: `Viewer2D` or `MultiViewer2D` for Viewer2D montages
- 3D: `Viewer3D`: Supports brain meshes (Allen, InsectBrainDB, MouseLight, VirtualFlyBrain, mapZebrain), color-by-feature, animation. `MultiViewer3D` can be used for `Viewer3D` montages. `Image3DUniverse` is considered deprecated.
- `FigCreatorCmd.render(Collection<Tree>, String)` is a one-call utility that returns the rendered viewer (`Viewer2D`/`Viewer3D`/`MultiViewer2D`/`MultiViewer3D`/`ImagePlus`). Example (Groovy):
  ```groovy
  def viewer = FigCreatorCmd.render(trees,
          "montage,2d-raster,xy,zero-origin,upright-geodesic,show")
  // viewer.saveAsPNG(...) / viewer.saveSnapshot(...) etc. depending on the
  // concrete return type. See FigCreatorCmd Javadoc for the full flag list.
  ```

### Compare reconstructions
`MultiTreeStatistics.getGroupStats(...)`, `GroupedTreeStatistics.getGroupStats(...)`

### Convert to graph
`tree.getGraph()` returns a `DirectedWeightedGraph` (JGraphT): Use this for any custom topology work instead of manually walking `Path` parents

### Save outputs
SNT has dedicated, format-aware save methods on its result types. Don't hand-roll CSV writers or use `ChartUtilities`:
- Reconstructions: `tree.saveAsSWC(path)` (SWC) / `tree.save(path)` (TRACES — XML, compressed)
- Tables: `SNTTable.save(path)` (writes CSV by extension; round-trips through `new SNTTable(path)`)
- Plots: `SNTChart.saveAsPNG(path)` / `saveAsSVG(path)` / `saveAsPDF(path)` (also `chart.save(path)` for format-by-extension)
- 3D scenes: `Viewer3D.saveSnapshot(path)` for PNG snapshots of the current view
- Generic figures via `FigCreatorCmd.render(trees, options)` returns an `ImagePlus` / `Viewer2D` / `Viewer3D` / `MultiViewer2D` / `MultiViewer3D` — capture it and call the appropriate save method on the returned object. There is no `save=` flag in the option string.

---

## Phase 4: Anti-patterns (Things Agents Do Wrong)

| Don't                                | Do instead                                                    |
|--------------------------------------|---------------------------------------------------------------|
| `import tracing.SimpleNeuriteTracer`         | `import sc.fiji.snt.SNTService` (inject it)                                |
| `new SNT(...)` from a script                 | `@SNTService snt; snt.initialize(true)`                                    |
| Iterate pixels via `ImageProcessor`          | Iterate via `Cursor<T>` on a `RandomAccessibleInterval`                    |
| Parse SWC manually                           | `new Tree(path)`                                                           |
| Call `IJ.run("3D Viewer", ...)`              | `new Viewer3D()`                                                           |
| `ResultsTable` for SNT outputs               | `SNTTable` (subclass with persistence helpers)                             |
| Mix length units silently                    | Always work in calibrated units; check `tree.getProperties()`              |
| `ChartUtilities.saveChartAsPNG(...)`         | `chart.saveAsPNG(path)` on `SNTChart`                                      |
| `double[] xyz` (or three loose doubles)      | `PointInImage` (world coords) / `PointInCanvas` (display coords)           |
| Walk `Path.getStartJoins()` parents manually | `tree.getGraph()` then use JGraphT's iterators / BFS / DFS                 |
| Share one `SNT` instance across threads      | Treat `SNT` as single-threaded; spawn one per worker, or use `SNTService`  |
| `ImageProcessor` → ad-hoc pixel arrays       | `ImpUtils.toRAI(imp)` / `ImageJFunctions.wrap(imp)` to get a `RAI`/`Img`   |

---

## Phase 5: Verifying a Script Works

Before handing a script back to the user:

1. **Sanity-run headlessly with synthetic input** if the script doesn't need a GUI. Fiji's CLI has no real `--dry-run`, so the next-best thing is to drive the script with one of SNT's built-in demos (`SNTService.demoTree()`, `SNTService.demoTrees()`, `SNTService.demoImage(name)`) instead of the user's real data, then inspect the log:
   ```bash
   "$FIJI_HOME/fiji" --headless --run script.groovy 'useDemo=true'
   ```
2. **Inspect the SNT log**: `SNTUtils.log(...)` output goes to Fiji's console but _only_ after calling `SNTUtils.setDebugMode(true)`
3. **Cite the API version** in your reply: `println SNTUtils.VERSION` and include it so the user knows which API surface you targeted

---

## Phase 6: When Stuck

1. **Read the jar.** `unzip -l "$FIJI_HOME"/jars/SNT-*.jar` is the ground truth for what classes/templates exist *on this machine*
2. **Open the javadoc** for the specific class: `https://morphonets.github.io/SNT/sc/fiji/snt/<ClassName>.html`
3. **Check the notebooks repo** for curated working examples: https://github.com/morphonets/SNT/tree/main/notebooks
4. **Forum, not StackOverflow.** Point users to https://forum.image.sc/tag/snt for help that needs a human
5. **GitHub Issues for bugs**, not the forum: https://github.com/morphonets/SNT/issues
6. **Demos for quick sanity checks**: `SNTService.demoTree()`, `SNTService.demoTrees()`, `SNTService.demoImage(name)` return ready-to-use objects with zero external dependencies

---

## Reference Index

| File / URL                                                    | When to Consult                               |
|---------------------------------------------------------------|-----------------------------------------------|
| `$FIJI_HOME/jars/SNT-*.jar :: script_templates/Neuroanatomy/` | Always first: copy a template, adapt it       |
| https://morphonets.github.io/SNT/                             | Javadoc: authoritative method signatures      |
| https://imagej.net/plugins/snt/scripting                      | Scripting overview & getting-started snippets |
| https://github.com/morphonets/SNT/tree/main/notebooks         | Curate Jupyter/Python examples                |
| https://forum.image.sc/tag/snt                                | Community Q&A                                 |
