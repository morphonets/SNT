"""
This is a convenience wrapper to apply Fiji's Correct_3D_drift to the image being
traced in SNT. It re-uses the original code verbatim (forked from [1]) since,
currently, there is no immediate way to call the original script without
disrupting the tracing session. No registration code was altered.
Changes (at the bottom of the file) are restricted to:

log(msg):
  print IJ.log() messages to Fiji Console (as per SNT's built-in commands)
get_options_without_prompt(imp):
  bypasses GenericDialog and populates options with (hopefully sensible) defaults
run_snt
  simplified copy of the original run() that loads corrected time-points into SNT

TODO: Duplication of code at this scale is silly. Check if original script can
      be patched for easier programmatic calls. Others may also have this need!?

[1] 20240707, https://github.com/fiji/Correct_3D_Drift/blob/d2908ba6ae9154424b5cb7bf3dc0f9d676e5cb3f/src/main/resources/scripts/Plugins/Registration/Correct_3D_drift.py
"""

#### begin of original script (without header) ####
from ij import VirtualStack, IJ, CompositeImage, ImageStack, ImagePlus
from ij.process import ColorProcessor
from ij.plugin import HyperStackConverter, ZProjector
from ij.io import DirectoryChooser, FileSaver, SaveDialog
from ij.gui import GenericDialog, YesNoCancelDialog, Roi
from mpicbg.imglib.image import ImagePlusAdapter
from mpicbg.imglib.algorithm.fft import PhaseCorrelation
from org.scijava.vecmath import Point3i  #from javax.vecmath import Point3i # java6
from org.scijava.vecmath import Point3f  #from javax.vecmath import Point3f # java6
from java.io import File, FilenameFilter
from java.lang import Integer
import math, os, os.path

# sub-pixel translation using imglib2
from net.imagej.axis import Axes
from net.imglib2.img.display.imagej import ImageJFunctions
from net.imglib2.realtransform import RealViews, Translation3D, Translation2D
from net.imglib2.view import Views
from net.imglib2.img.imageplus import ImagePlusImgs
from net.imglib2.converter import Converters
from net.imglib2.converter.readwrite import RealFloatSamplerConverter
from net.imglib2.interpolation.randomaccess import NLinearInterpolatorFactory

def translate_single_stack_using_imglib2(imp, dx, dy, dz):
  # wrap into a float imglib2 and translate
  #   conversion into float is necessary due to "overflow of n-linear interpolation due to accuracy limits of unsigned bytes"
  #   see: https://github.com/fiji/fiji/issues/136#issuecomment-173831951
  img = ImagePlusImgs.from(imp.duplicate())
  extended = Views.extendZero(img)
  converted = Converters.convert(extended, RealFloatSamplerConverter())
  interpolant = Views.interpolate(converted, NLinearInterpolatorFactory())
  
  # translate
  if imp.getNDimensions()==3:
    transformed = RealViews.affine(interpolant, Translation3D(dx, dy, dz))
  elif imp.getNDimensions()==2:
    transformed = RealViews.affine(interpolant, Translation2D(dx, dy))
  else:
    log("Can only work on 2D or 3D stacks")
    return None
  
  cropped = Views.interval(transformed, img)
  # wrap back into bit depth of input image and return
  bd = imp.getBitDepth()
  if bd==8:
    return(ImageJFunctions.wrapUnsignedByte(cropped,"imglib2"))
  elif bd == 16:
    return(ImageJFunctions.wrapUnsignedShort(cropped,"imglib2"))
  elif bd == 32:
    return(ImageJFunctions.wrapFloat(cropped,"imglib2"))
  else:
    return None    

'''
def translate_single_stack_using_imagescience(imp, dx, dy, dz):
  translator = Translate()
  output = translator.run(Image.wrap(imp), dx, dy, dz, Translate.LINEAR)
  return output.imageplus()
'''

def compute_shift(imp1, imp2):
  """ Compute a Point3i that expressed the translation of imp2 relative to imp1."""
  phc = PhaseCorrelation(ImagePlusAdapter.wrap(imp1), ImagePlusAdapter.wrap(imp2), 5, True)
  phc.process()
  p = phc.getShift().getPosition()
  if len(p)==3: # 3D data
    p3 = p
  elif len(p)==2: # 2D data: add zero shift for z
    p3 = [p[0],p[1],0]
  return Point3i(p3)

def extract_frame(imp, frame, channel, z_min, z_max):
  """ From a VirtualStack that is a hyperstack, contained in imp,
  extract the timepoint frame as an ImageStack, and return it.
  It will do so only for the given channel. """
  stack = imp.getStack() # multi-time point virtual stack
  stack2 = ImageStack(imp.width, imp.height, None)
  for s in range(int(z_min), int(z_max)+1):
    i = imp.getStackIndex(channel, s, frame)  
    stack2.addSlice(str(s), stack.getProcessor(i))
  return stack2


def extract_frame_process_roi(imp, frame, roi, options):
  # extract frame and channel 
  imp_frame = ImagePlus("", extract_frame(imp, frame, options['channel'], options['z_min'], options['z_max'])).duplicate()
  # check for roi and crop
  if roi != None:
    #print roi.getBounds()
    imp_frame.setRoi(roi)
    IJ.run(imp_frame, "Crop", "")
  # subtract background  
  if options['background'] > 0:
    #log("Subtracting "+str(background));
    IJ.run(imp_frame, "Subtract...", "value="+str(options['background'])+" stack");
  # enhance edges  
  if options['process']:
    IJ.run(imp_frame, "Mean 3D...", "x=1 y=1 z=0");
    IJ.run(imp_frame, "Find Edges", "stack");
  # project into 2D if we only want to correct the drift in x and y
  if imp_frame.getNSlices() > 1:
    if options['correct_only_xy']:
      imp_frame = ZProjector.run(imp_frame, "avg");
  # return
  return imp_frame


def add_Point3f(p1, p2):
  p3 = Point3f(0,0,0)
  p3.x = p1.x + p2.x
  p3.y = p1.y + p2.y
  p3.z = p1.z + p2.z
  return p3


def subtract_Point3f(p1, p2):
  p3 = Point3f(0,0,0)
  p3.x = p1.x - p2.x
  p3.y = p1.y - p2.y
  p3.z = p1.z - p2.z
  return p3


def get_Point3i(point, dimension):
  if dimension == 0:
    return point.x
  if dimension == 1:
    return point.y
  if dimension == 2:
    return point.z
  else:
    log("Tried to get Point3f at coordinate " + str( dimension ))


def set_Point3i(point, dimension, value):
  if dimension == 0:
    point.x = int(value)
    return
  if dimension == 1:
    point.y = int(value)
    return
  if dimension == 2:
    point.z = int(value)
    return
  else:
    log("Tried to set Point3f at coordinate " + str( dimension ))


def shift_between_rois(roi2, roi1):
  """ computes the relative xy shift between two rois 
  """ 
  dr = Point3f(0,0,0)
  dr.x = roi2.getBounds().x - roi1.getBounds().x
  dr.y = roi2.getBounds().y - roi1.getBounds().y
  dr.z = 0
  return dr


def shift_roi(imp, roi, dr):
  """ shifts a roi in x,y by dr.x and dr.y
  if the shift would cause the roi to be outside the imp,
  it only shifts as much as possible maintaining the width and height
  of the input roi
  """ 
  if roi == None:
    return roi
  else:
    r = roi.getBounds()
    # init x,y coordinates of new shifted roi
    sx = 0
    sy = 0
    # x shift
    if (r.x + dr.x) < 0:
      sx = 0
    elif (r.x + dr.x + r.width) > imp.width: 
      sx = int(imp.width-r.width)
    else:
      sx = r.x + int(dr.x)
    # y shift
    if (r.y + dr.y) < 0:
      sy = 0
    elif (r.y + dr.y + r.height) > imp.height: 
      sy = int(imp.height-r.height)
    else:
      sy = r.y + int(dr.y)
    # return shifted roi
    shifted_roi = Roi(sx, sy, r.width, r.height)
    return shifted_roi   


def compute_and_update_frame_translations_dt(imp, dt, options, shifts = None):
  """ imp contains a hyper virtual stack, and we want to compute
  the X,Y,Z translation between every t and t+dt time points in it
  using the given preferred channel. 
  if shifts were already determined at other (lower) dt 
  they will be used and updated.
  """
  nt = imp.getNFrames()
  # get roi (could be None)
  roi = imp.getRoi()
  #if roi:
  #  print "ROI is at", roi.getBounds()   
  # init shifts
  if shifts == None:
    shifts = []
    for t in range(nt):
      shifts.append(Point3f(0,0,0))
  # compute shifts
  IJ.showProgress(0)
  max_shifts = options['max_shifts']
  for t in range(dt, nt+dt, dt):
    if t > nt-1: # together with above range till nt+dt this ensures that the last data points are not missed out
      t = nt-1 # nt-1 is the last shift (0-based)
    log("      between frames "+str(t-dt+1)+" and "+str(t+1))      
    # get (cropped and processed) image at t-dt
    roi1 = shift_roi(imp, roi, shifts[t-dt])
    imp1 = extract_frame_process_roi(imp, t+1-dt, roi1, options)
    # get (cropped and processed) image at t-dt
    roi2 = shift_roi(imp, roi, shifts[t])
    imp2 = extract_frame_process_roi(imp, t+1, roi2, options)
    #if roi:
    #  print "ROI at frame",t-dt+1,"is",roi1.getBounds()   
    #  print "ROI at frame",t+1,"is",roi2.getBounds()   
    # compute shift
    local_new_shift = compute_shift(imp2, imp1)
    limit_shifts_to_maximal_shifts(local_new_shift, max_shifts)

    if roi: # total shift is shift of rois plus measured drift
      #print "correcting measured drift of",local_new_shift,"for roi shift:",shift_between_rois(roi2, roi1)
      local_new_shift = add_Point3f(local_new_shift, shift_between_rois(roi2, roi1))
    # determine the shift that we knew alrady
    local_shift = subtract_Point3f(shifts[t],shifts[t-dt])
    # compute difference between new and old measurement (which come from different dt)   
    add_shift = subtract_Point3f(local_new_shift,local_shift)
    #print "++ old shift between %s and %s: dx=%s, dy=%s, dz=%s" % (int(t-dt+1),int(t+1),local_shift.x,local_shift.y,local_shift.z)
    #print "++ add shift between %s and %s: dx=%s, dy=%s, dz=%s" % (int(t-dt+1),int(t+1),add_shift.x,add_shift.y,add_shift.z)
    # update shifts from t-dt to the end (assuming that the measured local shift will presist till the end)
    for i,tt in enumerate(range(t-dt,nt)):
      # for i>dt below expression basically is a linear drift predicition for the frames at tt>t
      # this is only important for predicting the best shift of the ROI 
      # the drifts for i>dt will be corrected by the next measurements
      shifts[tt].x += 1.0*i/dt * add_shift.x
      shifts[tt].y += 1.0*i/dt * add_shift.y
      shifts[tt].z += 1.0*i/dt * add_shift.z
      #print "updated shift till frame",tt+1,"is",shifts[tt].x,shifts[tt].y,shifts[tt].z
    IJ.showProgress(1.0*t/(nt+1))
  
  IJ.showProgress(1)
  return shifts


def limit_shifts_to_maximal_shifts(local_new_shift, max_shifts):
  for d in range(3):
    shift = get_Point3i(local_new_shift, d)
    if shift > max_shifts[d]:
      log("Too large drift along dimension " + str(d)
         + ":  " + str(shift)
         + "; restricting to " + str(int(max_shifts[d])))
      set_Point3i(local_new_shift, d, int(max_shifts[d]))
      continue
    if shift < -1 * max_shifts[d]:
      log("Too large drift along dimension " + str(d)
         + ":  " + str(shift)
         + "; restricting to " + str(int(-1 * max_shifts[d])))
      set_Point3i(local_new_shift, d, int(-1 * max_shifts[d]))
      continue


def convert_shifts_to_integer(shifts):
  int_shifts = []
  for shift in shifts: 
    int_shifts.append(Point3i(int(round(shift.x)),int(round(shift.y)),int(round(shift.z)))) 
  return int_shifts


def compute_min_max(shifts):
  """ Find out the top left up corner, and the right bottom down corner,
  namely the bounds of the new virtual stack to create.
  Expects absolute shifts. """
  minx = Integer.MAX_VALUE
  miny = Integer.MAX_VALUE
  minz = Integer.MAX_VALUE
  maxx = -Integer.MAX_VALUE
  maxy = -Integer.MAX_VALUE
  maxz = -Integer.MAX_VALUE
  for shift in shifts:
    minx = min(minx, shift.x)
    miny = min(miny, shift.y)
    minz = min(minz, shift.z)
    maxx = max(maxx, shift.x)
    maxy = max(maxy, shift.y)
    maxz = max(maxz, shift.z)  
  return minx, miny, minz, maxx, maxy, maxz


def zero_pad(num, digits):
  """ for 34, 4 --> '0034' """
  str_num = str(num)
  while (len(str_num) < digits):
    str_num = '0' + str_num
  return str_num


def invert_shifts(shifts):
  """ invert shifts such that they can be used for correction.
  """
  for shift in shifts:
    shift.x *= -1
    shift.y *= -1
    shift.z *= -1
  return shifts


def register_hyperstack(imp, shifts, target_folder, virtual):
  """ Applies the shifts to all channels in the hyperstack."""
  # Compute bounds of the new volume,
  # which accounts for all translations:
  minx, miny, minz, maxx, maxy, maxz = compute_min_max(shifts)
  # Make shifts relative to new canvas dimensions
  # so that the min values become 0,0,0
  for shift in shifts:
    shift.x -= minx
    shift.y -= miny
    shift.z -= minz
  #print "shifts relative to new dimensions:"
  #for s in shifts:
  #  print s.x, s.y, s.z
  # new canvas dimensions:r
  width = imp.width + maxx - minx
  height = maxy - miny + imp.height
  slices = maxz - minz + imp.getNSlices()

  print "New dimensions:", width, height, slices
  # Prepare empty slice to pad in Z when necessary
  empty = imp.getProcessor().createProcessor(width, height)

  # if it's RGB, fill the empty slice with blackness
  if isinstance(empty, ColorProcessor):
    empty.setValue(0)
    empty.fill()
  # Write all slices to files:
  stack = imp.getStack()

  if virtual is False:
    registeredstack = ImageStack(width, height, imp.getProcessor().getColorModel())
  names = []
  
  for frame in range(1, imp.getNFrames()+1):
 
    shift = shifts[frame-1]
    
    #print "frame",frame,"correcting drift",-shift.x-minx,-shift.y-miny,-shift.z-minz
    log("    frame "+str(frame)+" correcting drift "+str(-shift.x-minx)+","+str(-shift.y-miny)+","+str(-shift.z-minz))
    
    fr = "t" + zero_pad(frame, len(str(imp.getNFrames())))
    # Pad with empty slices before reaching the first slice
    for s in range(shift.z):
      ss = "_z" + zero_pad(s + 1, len(str(slices))) # slices start at 1
      for ch in range(1, imp.getNChannels()+1):
        name = fr + ss + "_c" + zero_pad(ch, len(str(imp.getNChannels()))) +".tif"
        names.append(name)

        if virtual is True:
          currentslice = ImagePlus("", empty)
          currentslice.setCalibration(imp.getCalibration().copy())
          currentslice.setProperty("Info", imp.getProperty("Info"))
          FileSaver(currentslice).saveAsTiff(target_folder + "/" + name)
        else:
          empty = imp.getProcessor().createProcessor(width, height)
          registeredstack.addSlice(str(name), empty)
    
    
    # Add all proper slices
    stack = imp.getStack()
    for s in range(1, imp.getNSlices()+1):
      ss = "_z" + zero_pad(s + shift.z, len(str(slices)))
      for ch in range(1, imp.getNChannels()+1):
         ip = stack.getProcessor(imp.getStackIndex(ch, s, frame))
         ip2 = ip.createProcessor(width, height) # potentially larger
         ip2.insert(ip, shift.x, shift.y)
         name = fr + ss + "_c" + zero_pad(ch, len(str(imp.getNChannels()))) +".tif"
         names.append(name)

         if virtual is True:
           currentslice = ImagePlus("", ip2)
           currentslice.setCalibration(imp.getCalibration().copy())
           currentslice.setProperty("Info", imp.getProperty("Info"));
           FileSaver(currentslice).saveAsTiff(target_folder + "/" + name)
         else:
           registeredstack.addSlice(str(name), ip2)

    # Pad the end
    for s in range(shift.z + imp.getNSlices(), slices):
      ss = "_z" + zero_pad(s + 1, len(str(slices)))
      for ch in range(1, imp.getNChannels()+1):
        name = fr + ss + "_c" + zero_pad(ch, len(str(imp.getNChannels()))) +".tif"
        names.append(name)

        if virtual is True:
          currentslice = ImagePlus("", empty)
          currentslice.setCalibration(imp.getCalibration().copy())
          currentslice.setProperty("Info", imp.getProperty("Info"))
          FileSaver(currentslice).saveAsTiff(target_folder + "/" + name)
        else:
          registeredstack.addSlice(str(name), empty)
 
  if virtual is True:
    # Create virtual hyper stack
    registeredstack = VirtualStack(width, height, None, target_folder)
    for name in names:
      registeredstack.addSlice(name)
  
  registeredstack_imp = ImagePlus("registered time points", registeredstack)
  registeredstack_imp.setCalibration(imp.getCalibration().copy())
  registeredstack_imp.setProperty("Info", imp.getProperty("Info"))
  registeredstack_imp = HyperStackConverter.toHyperStack(registeredstack_imp, imp.getNChannels(), len(names) / (imp.getNChannels() * imp.getNFrames()), imp.getNFrames(), "xyczt", "Composite");    
  
  return registeredstack_imp

  
def register_hyperstack_subpixel(imp, channel, shifts, target_folder, virtual):
  """ Takes the imp, determines the x,y,z drift for each pair of time points, using the preferred given channel,
  and outputs as a hyperstack.
  The shifted image is computed using TransformJ allowing for sub-pixel shifts using interpolation.
  This is quite a bit slower than just shifting the image by full pixels as done in above function register_hyperstack().
  However it significantly improves the result by removing pixel jitter.
  """
  # Compute bounds of the new volume,
  # which accounts for all translations:
  minx, miny, minz, maxx, maxy, maxz = compute_min_max(shifts)
  # Make shifts relative to new canvas dimensions
  # so that the min values become 0,0,0
  for shift in shifts:
    shift.x -= minx
    shift.y -= miny
    shift.z -= minz
  # new canvas dimensions:
  width = int(imp.width + maxx - minx)
  height = int(maxy - miny + imp.height)
  slices = int(maxz - minz + imp.getNSlices())

  #print "New dimensions:", width, height, slices
    
  # prepare stack for final results
  stack = imp.getStack()
  if virtual is True: 
    names = []
  else:
    registeredstack = ImageStack(width, height, imp.getProcessor().getColorModel())
  
  # prepare empty slice for padding
  empty = imp.getProcessor().createProcessor(width, height)

  IJ.showProgress(0)

  # get raw data as stack
  stack = imp.getStack()

  # loop across frames
  for frame in range(1, imp.getNFrames()+1):
      
    IJ.showProgress(frame / float(imp.getNFrames()+1))
    fr = "t" + zero_pad(frame, len(str(imp.getNFrames()))) # for saving files in a virtual stack
    
    # get and report current shift
    shift = shifts[frame-1]
    #print "frame",frame,"correcting drift",-shift.x-minx,-shift.y-miny,-shift.z-minz
    log("    frame "+str(frame)+" correcting drift "+str(round(-shift.x-minx,2))+","+str(round(-shift.y-miny,2))+","+str(round(-shift.z-minz,2)))

    # loop across channels
    for ch in range(1, imp.getNChannels()+1):      
      
      tmpstack = ImageStack(width, height, imp.getProcessor().getColorModel())

      # get all slices of this channel and frame
      for s in range(1, imp.getNSlices()+1):
        ip = stack.getProcessor(imp.getStackIndex(ch, s, frame))
        ip2 = ip.createProcessor(width, height) # potentially larger
        ip2.insert(ip, 0, 0)
        tmpstack.addSlice("", ip2)

      # Pad the end (in z) of this channel and frame
      for s in range(imp.getNSlices(), slices):
        tmpstack.addSlice("", empty)

      # subpixel translation
      imp_tmpstack = ImagePlus("", tmpstack)
      imp_translated = translate_single_stack_using_imglib2(imp_tmpstack, shift.x, shift.y, shift.z)
      
      # add translated stack to final time-series
      translated_stack = imp_translated.getStack()
      for s in range(1, translated_stack.getSize()+1):
        ss = "_z" + zero_pad(s, len(str(slices)))
        ip = translated_stack.getProcessor(s).duplicate() # duplicate is important as otherwise it will only be a reference that can change its content  
        if virtual is True:
          name = fr + ss + "_c" + zero_pad(ch, len(str(imp.getNChannels()))) +".tif"
          names.append(name)
          currentslice = ImagePlus("", ip)
          currentslice.setCalibration(imp.getCalibration().copy())
          currentslice.setProperty("Info", imp.getProperty("Info"));
          FileSaver(currentslice).saveAsTiff(target_folder + "/" + name)
        else:
          registeredstack.addSlice("", ip)    

  IJ.showProgress(1)
    
  if virtual is True:
    # Create virtual hyper stack
    registeredstack = VirtualStack(width, height, None, target_folder)
    for name in names:
      registeredstack.addSlice(name)
  
  registeredstack_imp = ImagePlus("registered time points", registeredstack)
  registeredstack_imp.setCalibration(imp.getCalibration().copy())
  registeredstack_imp.setProperty("Info", imp.getProperty("Info"))
  registeredstack_imp = HyperStackConverter.toHyperStack(registeredstack_imp, imp.getNChannels(), slices, imp.getNFrames(), "xyzct", "Composite");    
  
  return registeredstack_imp
  

class Filter(FilenameFilter):
  def accept(self, folder, name):
    return not File(folder.getAbsolutePath() + "/" + name).isHidden()

def validate(target_folder):
  f = File(target_folder)
  if len(File(target_folder).list(Filter())) > 0:
    yn = YesNoCancelDialog(IJ.getInstance(), "Warning!", "Target folder is not empty! May overwrite files! Continue?")
    if yn.yesPressed():
      return True
    else:
      return False
  return True

def getOptions(imp):
  gd = GenericDialog("Correct 2D/3D Drift Options")
  channels = []
  for ch in range(1, imp.getNChannels()+1 ):
    channels.append(str(ch))
  gd.addChoice("Channel for registration:", channels, channels[0])
  gd.addCheckbox("Correct only x & y (for 3D data):", False)
  gd.addCheckbox("Multi_time_scale computation for enhanced detection of slow drifts?", False)
  gd.addCheckbox("Sub_pixel drift correction (possibly needed for slow drifts)?", False)
  gd.addCheckbox("Edge_enhance images for possibly improved drift detection?", False)
  gd.addNumericField("Only consider pixels with values larger than:", 0, 0)
  gd.addNumericField("Lowest z plane to take into account:", 1, 0)
  gd.addNumericField("Highest z plane to take into account:", imp.getNSlices(), 0)
  gd.addNumericField("Max_shift_x [pixels]:", 10, imp.getWidth())
  gd.addNumericField("Max_shift_y [pixels]:", 10, imp.getHeight())
  gd.addNumericField("Max_shift_z [pixels]:", 10, imp.getNSlices())
  gd.addCheckbox("Use virtualstack for saving the results to disk to save RAM?", False)
  gd.addCheckbox("Only compute drift vectors?", False)
  gd.addMessage("If you put a ROI, drift will only be computed in this region;\n the ROI will be moved along with the drift to follow your structure of interest.")
  gd.showDialog()
  if gd.wasCanceled():
    return
  options = {}
  options['channel'] = gd.getNextChoiceIndex() + 1  # zero-based
  options['correct_only_xy'] = gd.getNextBoolean()
  options['multi_time_scale'] = gd.getNextBoolean()
  options['subpixel'] = gd.getNextBoolean()
  options['process'] = gd.getNextBoolean()
  options['background'] = gd.getNextNumber()
  options['z_min'] = gd.getNextNumber()
  options['z_max'] = gd.getNextNumber()
  max_shifts = [0,0,0]
  max_shifts[0] = gd.getNextNumber()
  max_shifts[1] = gd.getNextNumber()
  max_shifts[2] = gd.getNextNumber()
  options['max_shifts'] = max_shifts
  options['virtual'] = gd.getNextBoolean()
  options['only_compute'] = gd.getNextBoolean()
  return options


def save_shifts(shifts, roi):
  sd = SaveDialog('please select shift file for saving', 'shifts', '.txt')
  fp = os.path.join(sd.getDirectory(),sd.getFileName())
  f = open(fp, 'w')
  txt = []
  txt.append("ROI zero-based")
  txt.append("\nx_min\ty_min\tz_min\tx_max\ty_max\tz_max")
  txt.append("\n"+str(roi[0])+"\t"+str(roi[1])+"\t"+str(roi[2])+"\t"+str(roi[3])+"\t"+str(roi[4])+"\t"+str(roi[5]))
  txt.append("\nShifts")
  txt.append("\ndx\tdy\tdz")  
  for shift in shifts:
    txt.append("\n"+str(shift.x)+"\t"+str(shift.y)+"\t"+str(shift.z))
  f.writelines(txt)
  f.close()


def run():

  log("Correct_3D_Drift")

  imp = IJ.getImage()
  if imp is None:
    return
  if 1 == imp.getNFrames():
    IJ.showMessage("Cannot register because there is only one time frame.\nPlease check [Image > Properties...].")
    return

  options = getOptions(imp)
  if options is None:
    return # user pressed Cancel

  if options['z_min'] < 1:
    IJ.showMessage("The minimal z plane must be >=1.")
    return
    
  if options['z_max'] > imp.getNSlices():
    IJ.showMessage("Your image only has "+str(imp.getNSlices())+" z-planes, please adapt your z-range.")
    return
  
  if options['virtual'] is True:
    dc = DirectoryChooser("Choose target folder to save image sequence")
    target_folder = dc.getDirectory()
    if target_folder is None:
      return # user canceled the dialog
    if not validate(target_folder):
      return
  else:
    target_folder = None 

  #
  # compute drift
  #
  log("  computing drift...");

  log("    at frame shifts of 1"); 
  dt = 1; shifts = compute_and_update_frame_translations_dt(imp, dt, options)
  
  # multi-time-scale computation
  if options['multi_time_scale'] is True:
    dt_max = imp.getNFrames()-1
    # computing drifts on exponentially increasing time scales 3^i up to 3^6
    # ..one could also do this with 2^i or 4^i
    # ..maybe make this a user choice? did not do this to keep it simple.
    dts = [3,9,27,81,243,729,dt_max] 
    for dt in dts:
      if dt < dt_max:
        log("    at frame shifts of "+str(dt)) 
        shifts = compute_and_update_frame_translations_dt(imp, dt, options, shifts)
      else: 
        log("    at frame shifts of "+str(dt_max));
        shifts = compute_and_update_frame_translations_dt(imp, dt_max, options, shifts)
        break

  # invert measured shifts to make them the correction
  shifts = invert_shifts(shifts)
  #print(shifts)
  
  
  # apply shifts
  if not options['only_compute']:
    
    log("  applying shifts..."); #print("\nAPPLYING SHIFTS:")
    
    if options['subpixel']:
      registered_imp = register_hyperstack_subpixel(imp, options['channel'], shifts, target_folder, options['virtual'])
    else:
      shifts = convert_shifts_to_integer(shifts)
      registered_imp = register_hyperstack(imp, shifts, target_folder, options['virtual'])
      
    if options['virtual'] is True:
      if 1 == imp.getNChannels():
        ip=imp.getProcessor()
        ip2=registered_imp.getProcessor()
        ip2.setColorModel(ip.getCurrentColorModel())
      else:
        registered_imp.copyLuts(imp)
    else:
      if imp.getNChannels() > 1:
        registered_imp.copyLuts(imp)
  
    registered_imp.show()
  
  else:
   
    if imp.getRoi(): 
      xmin = imp.getRoi().getBounds().x
      ymin = imp.getRoi().getBounds().y
      zmin = 0
      xmax = xmin + imp.getRoi().getBounds().width - 1
      ymax = ymin + imp.getRoi().getBounds().height - 1
      zmax = imp.getNSlices()-1  
    else:
      xmin = 0
      ymin = 0
      zmin = 0
      xmax = imp.getWidth() - 1
      ymax = imp.getHeight() - 1
      zmax = imp.getNSlices() - 1  
    
    save_shifts(shifts, [xmin, ymin, zmin, xmax, ymax, zmax])
    log("  saving shifts...")

def log(msg):
  IJ.log(msg)

#### start of SNT mods ####

#@String(value="This is a convenience wrapper for Fiji's <i>Plugins>Registration>Correct 3D Drift</i>.<br>It will replace the time-lapse being traced with a motion-corrected copy.<br>Correction shifts will also be applied to existing paths. Please refer to the<br>original script for full functionality on time-series registration.", visibility="MESSAGE") msg1
#@Integer(label="Registration channel", min=1, description="The reference channel for registration") channel
#@Double(label="Dimmest intensity (approx.)",min=-1,description = "<HTML>Only pixel intensities above this value are considered for registration.<br>Use -1 to adopt the default cutoff value (half of image max).<br>Use 0 to disable this option.") background
#@Integer(label="Max. X displacement (in pixels)",min=10,description="The max. displacement along the X axis to be considered") maxX
#@Integer(label="Max. Y displacement (in pixels)",min=10,description="The max. displacement along the Y axis to be considered") maxY
#@Integer(label="Max. Z displacement (in pixels)",min=10,description="The max. displacement along the Z axis to be considered") maxZ
#@String(value="NB:<br>- Time-lapse being traced must be saved locally to ensure no data is lost<br>- Progress is logged to Console<br>- If a ROI exists, drift will only be computed in its region<br>- The most demanding options of <i>Correct 3D Drift</i> (subpixel accuracy, multi-<br>&nbsp;&nbsp;&nbsp;time scales, edge detection, etc.) will be used. It may be wiser to parse<br>&nbsp;&nbsp;&nbsp;large time-lapse sequences through the original script instead", visibility="MESSAGE") msg2
#@SNTService sntService
#@UIService uiService
#@LogService logService


def log(msg):
  logService.log(logService.INFO, msg) # replaces IJ.log() messages in original script
 
def get_options_without_prompt(imp):
  from ij.process import ImageStatistics
  global background, channel, maxX, maxY, maxZ
  options = {}
  options['channel'] = channel # Channel for registration
  options['correct_only_xy'] = False # Correct only x & y (for 3D data)?
  options['multi_time_scale'] = True # Multi time scale computation for enhanced detection of slow drifts?
  options['subpixel'] = True # Subpixel drift correction (possibly needed for slow drifts)?
  options['process'] = True # Edge_enhance images for possibly improved drift detection?
  max = imp.getStatistics(ImageStatistics.MIN_MAX).max if background < 0 else background
  options['background'] = max # Only consider pixels with values larger than this value? (ignored when <=0)
  options['z_min'] = 1 # Lowest z plane to take into account
  options['z_max'] = imp.getNSlices() # Highest z plane to take into account
  options['max_shifts'] = [maxX, maxY, maxZ] # Max shifts in X,Y,Z [pixels]
  options['virtual'] = False # Use virtual stack for saving the results to disk to minimize RAM?
  options['only_compute'] = False # Only compute drift vectors (saved to a text file)?
  options['target_folder'] = None # Target directory for saving operations (virtual stack/saved imaged, etc.)
  if imp.getOriginalFileInfo():
    options['target_folder'] = imp.getOriginalFileInfo().directory
  return options

def shift_paths(paths, inverted_shifts):
  from sc.fiji.snt.util import PointInImage
  for idx, shift in enumerate(inverted_shifts): # one per frame
    for path in paths:
      cal = path.getCalibration()
      xshift_cal = cal.getX(shift.getX()) # x,y,z- shifts in physical distances
      yshift_cal = cal.getY(shift.getY())
      zshift_cal = cal.getZ(shift.getZ())
      if path.getFrame() == idx+1:
        for node_idx in range(0, path.size()):
          node = path.getNode(node_idx)
          path.moveNode(node_idx, PointInImage(node.x + xshift_cal, node.y + yshift_cal, node.z + zshift_cal))


def run_snt():

  snt = sntService.getInstance()
  ui = sntService.getUI()
  if snt is None or ui is None:
    uiService.showDialog("SNT does not seem to be running.", "Error")
    return
  imp = snt.getImagePlus()
  if imp is None:
    ui.error("No time-lapse is currently loaded.")
    return
  if 1 == imp.getNFrames():
    ui.error("Image being traced is not a time-lapse. Please check Image > Properties....")
    return
  err = "<HTML>"
  if channel < 1 or channel > imp.getNChannels():
    err = "<p>Channel out-of-range. Valid choice must be an integer between [1-{0}].</p>".format(imp.getNChannels())
  if maxX < 1 and maxY < 1 and maxZ < 1:
    err += "<p>Maximal shifts are set to zero in all dimensions. No corrections can be performed.</p>"

  options = get_options_without_prompt(imp)
  if not os.path.exists(options["target_folder"]):
    err += "<p>Image is not saved or the directory from which it was loaded cannot be reached. Please save image and retry.</p>"
  if not err == "<HTML>":
    ui.error(err)
    return

  if options['virtual'] or options['only_compute']:
    ui.error("'virtual' and 'only_compute' options are currently not supported in SNT.")
    return
  
  log("Starting Correct_3D_drift with options")
  for k in sorted(options):
    log("    {0}={1}".format(k, options[k]))
  log("  Computing drift. This may take a while...");
  log("    at frame shifts of 1");
  dt = 1; shifts = compute_and_update_frame_translations_dt(imp, dt, options)
  
  # multi-time-scale computation
  if options['multi_time_scale'] is True:
    dt_max = imp.getNFrames()-1
    # computing drifts on exponentially increasing time scales 3^i up to 3^6
    # ..one could also do this with 2^i or 4^i
    # ..maybe make this a user choice? did not do this to keep it simple.
    dts = [3,9,27,81,243,729,dt_max] 
    for dt in dts:
      if dt < dt_max:
        log("    at frame shifts of "+str(dt)) 
        shifts = compute_and_update_frame_translations_dt(imp, dt, options, shifts)
      else: 
        log("    at frame shifts of "+str(dt_max));
        shifts = compute_and_update_frame_translations_dt(imp, dt_max, options, shifts)
        break

  # invert measured shifts to make them the correction
  shifts = invert_shifts(shifts)
  #print(shifts)

  # apply shifts
  log("  applying shifts..."); #print("\nAPPLYING SHIFTS:")
  
  if options['subpixel']:
    registered_imp = register_hyperstack_subpixel(imp, options['channel'], shifts, None, False)
  else:
    shifts = convert_shifts_to_integer(shifts)
    registered_imp = register_hyperstack(imp, shifts, None, False)
  
  if imp.getNChannels() > 1:
    registered_imp.copyLuts(imp)
  
  ### replace input image with registered in SNT
  registered_imp.setPosition(imp.getC(), imp.getZ(), imp.getT())
  outfile = os.path.join(options["target_folder"], "Reg_{0}".format(imp.getTitle()))
  if IJ.saveAsTiff(registered_imp, outfile):
    log("Result saved to {0}".format(outfile))
    imp.close()
  else:
    registered_imp.changes = True
  log("Loading registered time-points...")
  snt.initialize(registered_imp)
  snt.reloadImage(snt.getChannel(), snt.getFrame())
  registered_imp.show()
  registered_imp.resetDisplayRange();
  if not snt.getSinglePane():
    snt.rebuildZYXZpanes()
    
  log("Shifting paths...")
  shift_paths(sntService.getPaths(), shifts)
  snt.updateViewers()
  log("Done.")

run_snt() # Replace with run() to call original script
