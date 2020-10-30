# @String(visibility="MESSAGE",value="<html>This script merges Sholl profiles from multiple files into a single table. It<br>assumes that files share the same structure and profiles the same <i>starting<br> radius</i> and <i>radius step size</i>.<br>E.g., for a set of profiles stored in files <i>Cell1.csv, Cell2.csv, ..., CellN.csv</i>,<br>the script would generate the following tables:<table align='center><tr align='center'><th valign='bottom'>&nbsp;</th><th colspan='3' valign='bottom'><i>Inters.</th><th colspan='2' valign='bottom'>&nbsp;</th><th colspan='2' valign='bottom'><i>Inters.</th></tr><tr align='center'><th><tt>Radius</th><th><tt>Cell1</th><th><tt>...</th><th><tt>CellN</th><th><tt>&nbsp;</th><th><tt>Radius</th><th><tt>Mean Cell<sub>1..N</th><th><tt>SD Cell<sub>1..N</th></tr><tr align='center'><td><tt>x1</td><td><tt>row1</td><td><tt>...</td><td><tt>row1</td><td><tt>&nbsp;</td><td><tt>x1</td><td><tt>row1 mean</td><td><tt>row1 SD</td></tr><tr align='center'><td><tt>x2</td><td><tt>row2</td><td><tt>...</td><td><tt>row2</td><td><tt>&nbsp;</td><td><tt>x2</td><td><tt>row2 mean</td><td><tt>row2 SD</td></tr><tr align='center'><td><tt>...</td><td><tt>...</td><td><tt>...</td><td><tt>...</td><td><tt>&nbsp;</td><td><tt>...</td><td><tt>...</td><td><tt>...</td></tr><tr align='center'><td><tt>xN</td><td><tt>rowN</td><td><tt>...</td><td><tt>rowN</td><td><tt>&nbsp;</td><td><tt>xN</td><td><tt>rowN mean</td><td><tt>rowN SD</td></tr></table><br><p>Tip: Subscribe to the BAR update site to use <i>BAR>Data Analysis></i><br>&emsp;&emsp; routines to plot processed data.<br>&nbsp;") MSG
# @File(label="Input directory", style="directory", description="The directory containing the files to be parsed") dir
# @String(label="Filename contains", value="", description="<html>Only files containing this string will be considered.<br>Leave blank to consider all files. Glob patterns accepted.") pattern
# @String(label="File extension", choices={".csv",".txt",".xls",".ods", "any extension"}, description="<html>The extension of the files to be parsed.") extension
# @Integer(label="X-values column", min=1, value="1", description="<html>The position of the column containing the distances shared by all profiles.<br>It will be retrieved from the <b>first valid file</b> found in the directory.") xcol_idx1based
# @Integer(label="Y-values column", min=1,value="2", description="<html>The position of the column containing the Y-values to be agregated.<br>It will be extracted from all files.") ycol_idx1based
# @String(label="Output Table(s)", choices={"Merged data", "Row statistics", "Both"}, style="radioButtonHorizontal", description="<html><i>Merged data</i>: Places each file's Y-column into a common file.<br><i>Row statistics:</i> Retrieves mean±SD for each X-value. A plot is also obtained") output_type
# @Boolean(label="Log progress", value=false, description="<html>Display warnings and errors in Console") verbose
# @UIService uiservice
# @LogService lservice

from __future__ import with_statement
import csv, glob, os
from collections import defaultdict

from ij.gui import Plot
from sholl.gui import EnhancedResultsTable


def log(msg, level = "info"):
    if verbose:
        if "warn" in level:
            lservice.warn(msg)
        elif "error" in level:
            lservice.error(msg)
        else:
            lservice.info(msg)


def error(msg):
    """ Displays an error message """
    uiservice.showDialog(msg, "Error")


def mean(data):
    """ Returns the arithmetic mean of a list """
    return sum(data)/float(len(data))


def ss(data):
    """ Returns the sum of square deviations
       (see http://stackoverflow.com/a/27758326)
    """
    c = mean(data)
    ss = sum((x-c)**2 for x in data)
    return ss


def stdev(data):
    """Calculates the (population) standard deviation"""
    n = len(data)
    if n < 2:
        return float('nan')
    ssd = ss(data)
    svar = ssd/(n)
    return svar**0.5


def tofloat(v):
    try:
        return float(v)
    except ValueError:
        #log("Non-numeric entry: %s" % v, "warn")
        return v


def newtable(header, values):
    """Returns an IJ1 table populated with the specified column"""
    table = EnhancedResultsTable()
    table.setNaNEmptyCells(False)
    for v in values:
        table.incrementCounter()
        table.addValue(header, v)
    return table


def main():

    ext = "" if "any" in extension else extension
    glob_pattern = "*%s*%s" % (pattern, ext)
    files = sorted(glob.glob(os.path.join(str(dir), glob_pattern)))

    if not files:
        error("The directory %s\ndoes not contain files matching the specified"\
              " pattern (or it does not exist)." % dir)
        return

    if verbose:
        uiservice.getDefaultUI().getConsolePane().show()
    log("Parsing %s for files matching \"%s\"" % (dir, glob_pattern))

    xcolumn_idx = xcol_idx1based - 1
    ycolumn_idx = ycol_idx1based - 1
    reference_xheader, reference_yheader = "", ""
    xvalues, all_ydata = [], []
    first_valid_idx = 0
    last_row = -1

    for f_idx, f in enumerate(files):

        filename = os.path.basename(f)
        log("Parsing file %s: %s" % (f_idx+1, filename))
        if os.path.isdir(f):
            log("Skipping... file is directory.", "warn")
            continue

        with open(f, 'rU') as inf:

            try:
                # Guess file properties
                sample = inf.read(1024)
                dialect = csv.Sniffer().sniff(sample, delimiters=";,\t")
                has_header = csv.Sniffer().has_header(sample)
                inf.seek(0)
                incsv = csv.reader(inf, dialect)
            except csv.Error, reason:  #Jython 3: except csv.Error as reason:
                log("Skipping... %s" % reason, "error")
                first_valid_idx += 1
                continue

            if has_header:
                header_row = incsv.next()
                y_header = header_row[ycolumn_idx]
                if (f_idx == first_valid_idx):
                    reference_xheader = header_row[xcolumn_idx]
                    reference_yheader = y_header
                    log("Setting X-data reference: column %s heading: '%s'" \
                        % (xcol_idx1based, reference_xheader))
                    log("Setting Y-data reference: column %s heading: '%s'" \
                        % (ycol_idx1based, reference_yheader))
                else:
                    if (reference_yheader != y_header):
                        log("Y-data heading mismatch: Found '%s' expected '%s'"
                            % (y_header, reference_yheader), "warn")
            else:
                log("File has no column headings...")

            for row_idx, row in enumerate(incsv):
                all_ydata.append((filename, row_idx, tofloat(row[ycolumn_idx])))
                if row_idx > last_row:
                    xvalues.append(tofloat(row[xcolumn_idx]))
                    last_row = row_idx

    if not all_ydata:
        error("%s files were parsed but no valid data existed.\nPlease revise"\
              " settings or check the Console for details." % len(files))
        return

    data_identifier = "Col#%s_%s" % (ycol_idx1based, pattern)

    if output_type in "Merged data Both":
        log("Building table with merged Y-data...")
        table = newtable(reference_xheader, xvalues)
        for filename, row, row_value in all_ydata:
            table.setValue(filename, row, row_value)
        table.show("MergedFiles_%s" % data_identifier)

    if output_type in "Row statistics Both":
        log("Retrieving statistics for merged Y-data...")
        list_of_rows = defaultdict(list)
        for data in all_ydata:
            list_of_rows[data[1]].append(data[2])

        row_stats = {}
        for row_key, row_values in list_of_rows.iteritems():
            row_stats[row_key] = (mean(row_values), stdev(row_values), len(row_values))

        table = newtable(reference_xheader, xvalues)
        for key, value in row_stats.iteritems():
            table.setValue("Mean", int(key), value[0])
            table.setValue("StdDev", int(key), value[1])
            table.setValue("N", int(key), value[2])
        table.show("Stats_%s" % data_identifier)

        plot = Plot("Mean Sholl Plot [%s]" % data_identifier, reference_xheader, "N. of intersections")
        plot.setLegend("Mean"+ u'\u00B1' +"SD", Plot.LEGEND_TRANSPARENT + Plot.AUTO_POSITION)
        plot.setColor("cyan", "blue")
        plot.addPoints(table.getColumn(0), table.getColumn(1),
                       table.getColumn(2), Plot.CONNECTED_CIRCLES, data_identifier)
        plot.show()

    log("Parsing concluded.")


main()
