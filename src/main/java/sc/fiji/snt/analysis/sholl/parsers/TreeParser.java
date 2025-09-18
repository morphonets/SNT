/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2025 Fiji developers.
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-3.0.html>.
 * #L%
 */
package sc.fiji.snt.analysis.sholl.parsers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.Calibration;
import ij.process.ShortProcessor;
import net.imagej.display.ColorTables;
import net.imglib2.display.ColorTable;
import sc.fiji.snt.Path;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.Tree;
import sc.fiji.snt.analysis.sholl.Profile;
import sc.fiji.snt.analysis.sholl.ProfileEntry;
import sc.fiji.snt.analysis.sholl.math.LinearProfileStats;
import sc.fiji.snt.analysis.sholl.math.ShollStats;
import sc.fiji.snt.util.ImpUtils;
import sc.fiji.snt.util.PointInImage;
import sc.fiji.snt.util.SNTPoint;
import sc.fiji.snt.util.ShollPoint;

/**
 * A {@link Parser} for extracting Sholl Profiles from a {@link Tree}.
 *
 * @author Tiago Ferreira
 */
public class TreeParser implements Parser {

	static { net.imagej.patcher.LegacyInjector.preinit(); } // required for _every_ class that imports ij. classes

	/**
	 * Flag for defining the profile center as the average position of root nodes
	 * of all primary Paths.
	 */
	public static final int ROOT_NODES_ANY = 0;

	/**
	 * Flag for defining the profile center as the average position of root nodes
	 * of Paths tagged as Apical Dendrite.
	 */
	public static final int ROOT_NODES_APICAL_DENDRITE = 1;

	/**
	 * Flag for defining the profile center as the average position of root nodes
	 * of Paths tagged as Axon.
	 */
	public static final int ROOT_NODES_AXON = 2;

	/**
	 * Flag for defining the profile center as the average position of root nodes
	 * of Paths tagged as Custom.
	 */
	public static final int ROOT_NODES_CUSTOM = 3;

	/**
	 * Flag for defining the profile center as the average position of root nodes
	 * of Paths tagged as (Basal) Dendrite
	 */
	public static final int ROOT_NODES_DENDRITE = 4;

	/**
	 * Flag for defining the profile center as the average position of root nodes
	 * of primary Paths tagged as Soma
	 */
	public static final int ROOT_NODES_SOMA = 5;

	/**
	 * Flag for defining the profile center as the average position of root nodes
	 * of _ANY_ Paths tagged as Soma, independently of connectivity
	 */
	public static final int ROOT_NODES_SOMA_ANY= 7;

	/**
	 * Flag for defining the profile center as the average position of root nodes
	 * of Paths tagged as Undefined
	 */
	public static final int ROOT_NODES_UNDEFINED = 6;

	private final Tree tree;
	private List<ComparableShollPoint> shollPointsList;
	private PointInImage center;
	private double stepSize = 0;
	private Profile profile;
	private volatile boolean running = true;
	private double[] squaredRangeStarts;
	private int[] crossingsPastEach;
	private boolean skipSomaticSegments;
    private boolean setIntersectedVolumeAsExtraMeasurement;

    /*
     * Cached intrinsic node-to-node spacing (median), computed on first use.
     * This is needed for length calculations when stepSize is 0.
     */
    private Double intrinsicScaleCache = null;

	/**
	 * Instantiates a new Tree Parser.
	 *
	 * @param tree the Tree to be profiled
	 */
	public TreeParser(final Tree tree) {
		this.tree = tree;
	}

	/**
	 * Computes the center of the Profile.
	 *
	 * @param choice the flag specifying the center (e.g.,
	 *          {@link #ROOT_NODES_SOMA}, {@link #ROOT_NODES_ANY}, etc.)
	 * @throws IllegalArgumentException if choice is not a recognized flag or if
	 *           no Paths in the Tree match the choice criteria
	 */
	public void setCenter(final int choice) throws IllegalArgumentException {
		switch (choice) {
			case ROOT_NODES_ANY:
				center = getCenter(-1, true);
				if (center == null && !tree.isEmpty())
					center = tree.list().getFirst().getNode(0);
				break;
			case ROOT_NODES_UNDEFINED:
				center = getCenter(Path.SWC_UNDEFINED, true);
				break;
			case ROOT_NODES_SOMA:
				center = getCenter(Path.SWC_SOMA, true);
				break;
			case ROOT_NODES_SOMA_ANY:
				center = getCenter(Path.SWC_SOMA, false);
				break;
			case ROOT_NODES_AXON:
				center = getCenter(Path.SWC_AXON, true);
				break;
			case ROOT_NODES_DENDRITE:
				center = getCenter(Path.SWC_DENDRITE, true);
				break;
			case ROOT_NODES_APICAL_DENDRITE:
				center = getCenter(Path.SWC_APICAL_DENDRITE, true);
				break;
			case ROOT_NODES_CUSTOM:
				center = getCenter(Path.SWC_CUSTOM, true);
				break;
			default:
				throw new IllegalArgumentException("Center choice was not understood");
		}
		if (center == null) throw new IllegalArgumentException(
			"Tree does not contain Paths matching specified choice");
	}

	/**
	 * Returns the center coordinates
	 *
	 * @return the point defining the center, or null if it has not yet been set.
	 */
	public PointInImage getCenter() {
		return (center == null) ? null : new ShollPoint(center.x, center.y, center.z);
	}

	private PointInImage getCenter(final int swcType, final boolean primaryPathsOnly) {
		final List<PointInImage> points = new ArrayList<>();
		for (final Path p : tree.list()) {
			if (primaryPathsOnly && !p.isPrimary()) continue;
			if (swcType < 0 || p.getSWCType() == swcType) {
				points.add(p.getNode(0));
			}
		}
		return (points.isEmpty()) ? null : SNTPoint.average(points);
	}

	/**
	 * Sets the center of the profile.
	 *
	 * @param center the focal point of the profile
	 */
	public void setCenter(final PointInImage center) {
		if (successful()) throw new UnsupportedOperationException("setCenter() must be called before parsing data");
		this.center = center;
	}

	/**
	 * Sets the center of the profile.
	 *
	 * @param coordinates the array holding the focal point coordinates of the profile
	 */
	public void setCenter(final double[] coordinates) {
		if (successful()) throw new UnsupportedOperationException("setCenter() must be called before parsing data");
		if (coordinates.length < 3)
			center = new PointInImage(coordinates[0], coordinates[1], 0);
		else
			center = new PointInImage(coordinates[0], coordinates[1], coordinates[2]);
	}

    /**
     * Enables storing the intersected arbor volume as a per-shell extra measurement in the resulting {@link Profile}.
     * <p>
     * When enabled, each {@link ProfileEntry} will include the volume of cable lying inside the current sampling shell,
     * expressed in calibrated units cubed (e.g., µm³). The value is computed by clipping each traced segment to the
     * shell and summing the volumes of the resulting truncated-cone (frustum) pieces. If only one endpoint radius is
     * available, that radius is used for both ends; if neither endpoint has a radius, the piece contributes no volume.
     * </p>
     * <p>
     * Shells are defined by the parser’s sampling mode: for discontinuous sampling (step size > 0), shells have
     * thickness equal to {@code stepSize}; for continuous sampling (step size = 0), a thin shell is used whose
     * thickness falls back to the median internode distance of the Tree.
     * </p>
     * 	<p><b>Important:</b> This method <em>before</em> {@link #parse()};
     * @param b whether
     */
    public void setIntersectedVolumeAsExtraMeasurement(final boolean b) {
        if (successful())
            throw new IllegalArgumentException("setIntersectedVolumeAsExtraMeasurement must be called before parsing data");
        this.setIntersectedVolumeAsExtraMeasurement = b;
    }

	/**
	 * Sets the radius step size.
	 *
	 * @param stepSize the radius step size
	 */
	public void setStepSize(final double stepSize) {
		if (successful()) throw new UnsupportedOperationException("setStepSize() must be called before parsing data");
		this.stepSize = (stepSize < 0) ? 0 : stepSize;
	}

	/* (non-Javadoc)
	 * @see sholl.parsers.Parser#parse()
	 */
	@Override
	public void parse() throws IllegalArgumentException {
		if (tree == null || tree.isEmpty()) {
			throw new IllegalArgumentException("Invalid tree.");
		}
		if (center == null) {
			throw new IllegalArgumentException("Data cannot be parsed unless a center is specified.");
		}
		profile = new Profile();
		if (tree.getLabel() != null) profile.setIdentifier(tree.getLabel());
		profile.setNDimensions((tree.is3D()) ? 3 : 2);
		profile.setCenter(new ShollPoint(center));
		if (tree.getBoundingBox(false) != null)
            profile.setSpatialCalibration(tree.getBoundingBox(false).getCalibration());
		profile.getProperties().setProperty(KEY_SOURCE, SRC_TRACES);
        assembleSortedShollPointList();
		assembleProfileAfterAssemblingSortedShollPointList();

        // If continuous sampling, log the effective step size
        final double drNominal = profile.stepSize();
        if (drNominal > 0d) {
            profile.getProperties().setProperty(Profile.KEY_EFFECTIVE_STEP_SIZE, Double.toString(drNominal));
        } else {
            final double drEff = intrinsicSegmentScale(); // median inter-node distance
            if (drEff > 0d) {
                profile.getProperties().setProperty(Profile.KEY_EFFECTIVE_STEP_SIZE, Double.toString(drEff));
            }
        }
        if (setIntersectedVolumeAsExtraMeasurement)
            profile.setExtraMeasurement("Volume", profile.spatialCalibration().getUnit() + "³");

    }

	/* (non-Javadoc)
	 * @see sholl.parsers.Parser#successful()
	 */
	@Override
	public boolean successful() {
		return profile != null && !profile.isEmpty();
	}

	/* (non-Javadoc)
	 * @see sholl.parsers.Parser#terminate()
	 */
	@Override
	public void terminate() {
		running = false;
	}

	/* (non-Javadoc)
	 * @see sholl.parsers.Parser#getProfile()
	 */
	@Override
	public Profile getProfile() {
        if (profile == null || profile.isEmpty()) parse();
		return profile;
	}

	private void assembleSortedShollPointList() {
		shollPointsList = new ArrayList<>();
		final PointInImage soma = tree.getRoot();
		final boolean skipFirstNode = isSkipSomaticSegments() && soma != null && soma.onPath.size() == 1
				&& soma.onPath.getSWCType() == Path.SWC_SOMA;
		tree.list().forEach(p -> {
			if (!running || p.size() == 0 || (skipFirstNode && p.equals(soma.onPath)))
				return;
			for (int i = (skipFirstNode) ? 1 : 0; i < p.size() - 1; ++i) {
				final PointInImage pim1 = p.getNode(i);
				final PointInImage pim2 = p.getNode(i + 1);
				final double distanceSquaredFirst = pim1.distanceSquaredTo(center);
				final double distanceSquaredSecond = pim2.distanceSquaredTo(center);
				final boolean nearer = distanceSquaredFirst < distanceSquaredSecond;
				shollPointsList.add(new ComparableShollPoint(distanceSquaredFirst, nearer));
				shollPointsList.add(new ComparableShollPoint(distanceSquaredSecond, !nearer));
			}
		});
        // Do not de-duplicate: multiple segments can share identical radii legitimately
		Collections.sort(shollPointsList);
	}

    private void assembleProfileAfterAssemblingSortedShollPointList() {
        final int n = shollPointsList.size();
        squaredRangeStarts = new double[n];
        crossingsPastEach = new int[n];
        int currentCrossings = 0;
        for (int i = 0; i < n; ++i) {
            final ComparableShollPoint p = shollPointsList.get(i);
            if (p.nearer) ++currentCrossings;
            else --currentCrossings;
            squaredRangeStarts[i] = p.distanceSquared;
            crossingsPastEach[i] = currentCrossings;
        }
        if (!running) return;
        if (stepSize > 0) { // Discontinuous sampling
            final double minDistance = Math.sqrt(squaredRangeStarts[0]);
            final double maxDistance = Math.sqrt(squaredRangeStarts[n - 1]);
            final int nShells = (int) Math.ceil(maxDistance / stepSize);
            for (int i = 0; i < nShells; ++i) {
                final double x = i * stepSize;
                if (x >= minDistance) {
                    // Use geometric intersections (same source as length) to count components
                    final Set<ShollPoint> intersectionPoints =
                            getIntersectionPointsInShell(x, x + stepSize);
                    final double y = intersectionPoints.size();
                    final double length = cableLengthInShell(x, x + stepSize);
                    final ProfileEntry entry = new ProfileEntry(x, y, length, intersectionPoints);
                    if (setIntersectedVolumeAsExtraMeasurement) {
                        entry.extra = intersectedVolumeInShell(x, x + stepSize);
                    }
                    profile.add(entry);
                }
            }
        } else { // Continuous sampling
            for (double squaredRangeStart : squaredRangeStarts) {
                final double x = Math.sqrt(squaredRangeStart);
                // Count exact intersections at this radius; matches length sampling strategy
                final Set<ShollPoint> intersectionPoints = getIntersectionPointsAtRadius(x);
                final double y = intersectionPoints.size();
                final double length = cableLengthAtRadius(x);
                final ProfileEntry entry = new ProfileEntry(x, y, length, intersectionPoints);
                if (setIntersectedVolumeAsExtraMeasurement) {
                    entry.extra = intersectedVolumeAtRadius(x);
                }
                profile.add(entry);
            }
        }
    }

	private int crossingsAtDistanceSquared(final double distanceSquared) {
		int minIndex = 0;
		int maxIndex = squaredRangeStarts.length - 1;
        if (distanceSquared < squaredRangeStarts[minIndex]) return 0;
		else if (distanceSquared > squaredRangeStarts[maxIndex]) return 0;
		while (maxIndex - minIndex > 1) {
			final int midPoint = (maxIndex + minIndex) / 2;
			if (distanceSquared < squaredRangeStarts[midPoint]) maxIndex = midPoint;
			else minIndex = midPoint;
		}
		return crossingsPastEach[minIndex];
	}

	/**
	 * Calculates the total cable length within a spherical shell.
	 *
	 * @param innerRadius the inner radius of the shell
	 * @param outerRadius the outer radius of the shell
	 * @return the total cable length within the shell
	 */
	private double cableLengthInShell(final double innerRadius, final double outerRadius) {
		double totalLength = 0.0;
		final PointInImage soma = tree.getRoot();
		final boolean skipFirstNode = isSkipSomaticSegments() && soma != null && soma.onPath.size() == 1
				&& soma.onPath.getSWCType() == Path.SWC_SOMA;

		for (final Path path : tree.list()) {
			if (!running || path.size() == 0 || (skipFirstNode && path.equals(soma.onPath)))
				continue;

			for (int i = (skipFirstNode) ? 1 : 0; i < path.size() - 1; ++i) {
				final PointInImage node1 = path.getNode(i);
				final PointInImage node2 = path.getNode(i + 1);
				
				final double dist1 = node1.distanceTo(center);
				final double dist2 = node2.distanceTo(center);
				
				// Check if this segment intersects with the shell
				final double minDist = Math.min(dist1, dist2);
				final double maxDist = Math.max(dist1, dist2);
				
				if (maxDist >= innerRadius && minDist <= outerRadius) {
					// Calculate the portion of the segment within the shell
					final double segmentLength = node1.distanceTo(node2);
					
					if (minDist >= innerRadius && maxDist <= outerRadius) {
						// Entire segment is within the shell
						totalLength += segmentLength;
					} else {
						// Partial segment - calculate intersection points and length
						final double lengthInShell = calculateSegmentLengthInShell(
							node1, node2, center, innerRadius, outerRadius);
						totalLength += lengthInShell;
					}
				}
			}
		}
		return totalLength;
	}

	/**
	 * Calculates cable length at a specific radius (for continuous sampling).
	 * This approximates the length by looking at segments that cross the radius.
	 *
	 * @param radius the radius at which to calculate length
	 * @return the approximate cable length at this radius
	 */
    private double cableLengthAtRadius(final double radius) {
        // Approximate length using a thin shell centered at 'radius'.
        final double shellThickness;
        if (stepSize > 0) {
            shellThickness = stepSize; // match discontinuous sampling shell thickness
        } else {
            final double s = intrinsicSegmentScale();
            shellThickness = Math.max(radius * 0.01, (s > 0.0 ? s : 1e-9));
        }
        return cableLengthInShell(radius - shellThickness / 2.0, radius + shellThickness / 2.0);
    }

	/**
	 * Calculates the length of a segment that lies within a spherical shell.
	 *
	 * @param node1 first endpoint of the segment
	 * @param node2 second endpoint of the segment  
	 * @param center center of the spherical shells
	 * @param innerRadius inner radius of the shell
	 * @param outerRadius outer radius of the shell
	 * @return the length of the segment portion within the shell
	 */
	private double calculateSegmentLengthInShell(final PointInImage node1, final PointInImage node2,
			final PointInImage center, final double innerRadius, final double outerRadius) {
		
		final double dist1 = node1.distanceTo(center);
		final double dist2 = node2.distanceTo(center);
		final double segmentLength = node1.distanceTo(node2);
		
		// If segment is entirely within shell
		if (dist1 >= innerRadius && dist1 <= outerRadius && 
			dist2 >= innerRadius && dist2 <= outerRadius) {
			return segmentLength;
		}
		
		// Calculate intersection parameters for inner and outer spheres
		final double[] intersections = findSphereIntersections(node1, node2, center, innerRadius, outerRadius);
		
		if (intersections.length == 0) {
			return 0.0; // No intersection with shell
		}
		
		// Calculate the portion of the segment within the shell
		double t1 = intersections[0];
		double t2 = intersections[intersections.length - 1];
		if (intersections.length > 1) {
			t1 = Math.min(t1, t2);
			t2 = Math.max(intersections[0], intersections[intersections.length - 1]);
		}
		t1 = Math.max(0.0, t1);
		t2 = Math.min(1.0, t2);
		
		// Ensure we're within the shell boundaries
		if (t1 > t2) return 0.0;
		
		return segmentLength * (t2 - t1);
	}

	/**
	 * Finds intersection parameters for a line segment with inner and outer spheres.
	 *
	 * @param p1 first point of segment
	 * @param p2 second point of segment
	 * @param center center of spheres
	 * @param innerRadius inner sphere radius
	 * @param outerRadius outer sphere radius
	 * @return array of intersection parameters t where intersection = p1 + t*(p2-p1)
	 */
	private double[] findSphereIntersections(final PointInImage p1, final PointInImage p2,
			final PointInImage center, final double innerRadius, final double outerRadius) {
		
		final List<Double> intersections = new ArrayList<>();
		
		// Check intersections with both spheres
		for (final double radius : new double[]{innerRadius, outerRadius}) {
			final double[] ts = findLineSphereIntersection(p1, p2, center, radius);
			for (final double t : ts) {
				if (t >= 0.0 && t <= 1.0) {
					intersections.add(t);
				}
			}
		}
		
		// Add endpoints if they're within the shell
		final double dist1 = p1.distanceTo(center);
		final double dist2 = p2.distanceTo(center);
		
		if (dist1 >= innerRadius && dist1 <= outerRadius) {
			intersections.add(0.0);
		}
		if (dist2 >= innerRadius && dist2 <= outerRadius) {
			intersections.add(1.0);
		}
		
		return intersections.stream().mapToDouble(Double::doubleValue).toArray();
	}

	/**
	 * Finds intersection parameters for a line segment with a sphere.
	 *
	 * @param p1 first point of segment
	 * @param p2 second point of segment
	 * @param center center of sphere
	 * @param radius sphere radius
	 * @return array of intersection parameters (0, 1, or 2 values)
	 */
	private double[] findLineSphereIntersection(final PointInImage p1, final PointInImage p2,
			final PointInImage center, final double radius) {
		
		// Vector from p1 to p2
		final double dx = p2.x - p1.x;
		final double dy = p2.y - p1.y;
		final double dz = p2.z - p1.z;
		
		// Vector from p1 to center
		final double fx = p1.x - center.x;
		final double fy = p1.y - center.y;
		final double fz = p1.z - center.z;
		
		// Quadratic equation coefficients: at² + bt + c = 0
		final double a = dx*dx + dy*dy + dz*dz;
		final double b = 2*(fx*dx + fy*dy + fz*dz);
		final double c = fx*fx + fy*fy + fz*fz - radius*radius;
		
		final double discriminant = b*b - 4*a*c;
		
		if (discriminant < 0) {
			return new double[0]; // No intersection
		} else if (discriminant == 0) {
			return new double[]{-b / (2*a)}; // One intersection
		} else {
			final double sqrtD = Math.sqrt(discriminant);
			return new double[]{
				(-b - sqrtD) / (2*a),
				(-b + sqrtD) / (2*a)
			}; // Two intersections
		}
	}

	/**
	 * Gets intersection points within a spherical shell, grouped as connected components.
	 *
	 * @param innerRadius the inner radius of the shell
	 * @param outerRadius the outer radius of the shell
	 * @return set of ShollPoints representing centroids of connected components
	 */
	private Set<ShollPoint> getIntersectionPointsInShell(final double innerRadius, final double outerRadius) {
		final List<ShollPoint> allIntersections = new ArrayList<>();
		final PointInImage soma = tree.getRoot();
		final boolean skipFirstNode = isSkipSomaticSegments() && soma != null && soma.onPath.size() == 1
				&& soma.onPath.getSWCType() == Path.SWC_SOMA;

		// Collect all intersection points within the shell
		for (final Path path : tree.list()) {
			if (!running || path.size() == 0 || (skipFirstNode && path.equals(soma.onPath)))
				continue;

			for (int i = (skipFirstNode) ? 1 : 0; i < path.size() - 1; ++i) {
				final PointInImage node1 = path.getNode(i);
				final PointInImage node2 = path.getNode(i + 1);
				
				// Find intersections with both spheres
				final List<PointInImage> segmentIntersections = findSegmentSphereIntersections(
					node1, node2, center, innerRadius, outerRadius);
				
				// Convert to ShollPoints and add to collection
				for (final PointInImage intersection : segmentIntersections) {
					allIntersections.add(new ShollPoint(intersection.x, intersection.y, intersection.z));
				}
			}
		}

		// Group intersections into connected components and return centroids
		return groupIntersectionsIntoComponents(allIntersections);
	}

    /**
     * Returns an intrinsic spatial scale of the reconstruction, independent of
     * image calibration. It is computed as the median distance between consecutive
     * nodes across all Paths (respecting skipSomaticSegments). If no segments exist, returns 0.0.
     */
    private double intrinsicSegmentScale() {
        if (intrinsicScaleCache != null && intrinsicScaleCache > 0) return intrinsicScaleCache;
        final List<Double> lengths = new ArrayList<>();
        final PointInImage soma = tree.getRoot();
        final boolean skipFirstNode = isSkipSomaticSegments() && soma != null && soma.onPath.size() == 1
                && soma.onPath.getSWCType() == Path.SWC_SOMA;
        for (final Path path : tree.list()) {
            if (path.size() == 0 || (skipFirstNode && path.equals(soma.onPath))) continue;
            for (int i = (skipFirstNode) ? 1 : 0; i < path.size() - 1; ++i) {
                final PointInImage a = path.getNode(i);
                final PointInImage b = path.getNode(i + 1);
                lengths.add(a.distanceSquaredTo(b));
            }
        }
        if (lengths.isEmpty()) {
            intrinsicScaleCache = 0.0;
            return 0.0;
        }
        Collections.sort(lengths);
        final int m = lengths.size() / 2;
        final double median = (lengths.size() % 2 == 0)
                ? 0.5 * (lengths.get(m - 1) + lengths.get(m))
                : lengths.get(m);
        intrinsicScaleCache = Math.sqrt(median);
        return intrinsicScaleCache;
    }

	/**
	 * Gets intersection points at a specific radius (for continuous sampling).
	 *
	 * @param radius the radius at which to find intersections
	 * @return set of ShollPoints representing intersection locations
	 */
	private Set<ShollPoint> getIntersectionPointsAtRadius(final double radius) {
		final List<ShollPoint> intersections = new ArrayList<>();
		final PointInImage soma = tree.getRoot();
		final boolean skipFirstNode = isSkipSomaticSegments() && soma != null && soma.onPath.size() == 1
				&& soma.onPath.getSWCType() == Path.SWC_SOMA;

		// Find all intersections with the sphere at this radius
		for (final Path path : tree.list()) {
			if (!running || path.size() == 0 || (skipFirstNode && path.equals(soma.onPath)))
				continue;

			for (int i = (skipFirstNode) ? 1 : 0; i < path.size() - 1; ++i) {
				final PointInImage node1 = path.getNode(i);
				final PointInImage node2 = path.getNode(i + 1);
				
				// Find intersections with the sphere
				final double[] ts = findLineSphereIntersection(node1, node2, center, radius);
				for (final double t : ts) {
					if (t >= 0.0 && t <= 1.0) {
						// Calculate intersection point
						final double x = node1.x + t * (node2.x - node1.x);
						final double y = node1.y + t * (node2.y - node1.y);
						final double z = node1.z + t * (node2.z - node1.z);
						intersections.add(new ShollPoint(x, y, z));
					}
				}
			}
		}

		return new LinkedHashSet<>(intersections);
	}

	/**
	 * Finds intersection points between a line segment and spherical shell.
	 *
	 * @param p1 first endpoint of segment
	 * @param p2 second endpoint of segment
	 * @param center center of spheres
	 * @param innerRadius inner sphere radius
	 * @param outerRadius outer sphere radius
	 * @return list of intersection points within the shell
	 */
	private List<PointInImage> findSegmentSphereIntersections(final PointInImage p1, final PointInImage p2,
			final PointInImage center, final double innerRadius, final double outerRadius) {
		
		final List<PointInImage> intersections = new ArrayList<>();
		
		// Find intersections with both spheres
		for (final double radius : new double[]{innerRadius, outerRadius}) {
			final double[] ts = findLineSphereIntersection(p1, p2, center, radius);
			
			for (final double t : ts) {
				if (t >= 0.0 && t <= 1.0) {
					// Calculate intersection point
					final double x = p1.x + t * (p2.x - p1.x);
					final double y = p1.y + t * (p2.y - p1.y);
					final double z = p1.z + t * (p2.z - p1.z);
					
					// Verify the point is within the shell
					final double dist = Math.sqrt((x - center.x)*(x - center.x) + 
												  (y - center.y)*(y - center.y) + 
												  (z - center.z)*(z - center.z));
					if (dist >= innerRadius && dist <= outerRadius) {
						intersections.add(new PointInImage(x, y, z));
					}
				}
			}
		}
		
		return intersections;
	}

	/**
	 * Groups intersection points into connected components and returns their centroids.
	 * Points are considered connected if they are within a threshold distance of each other.
	 *
	 * @param intersections list of all intersection points
	 * @return set of centroids representing connected components
	 */
	private Set<ShollPoint> groupIntersectionsIntoComponents(final List<ShollPoint> intersections) {
		if (intersections.isEmpty()) {
			return new LinkedHashSet<>();
		}

		final Set<ShollPoint> centroids = new LinkedHashSet<>();
		final List<List<ShollPoint>> components = new ArrayList<>();
		final boolean[] visited = new boolean[intersections.size()];

        final double connectionThresholdSquared;
        if (stepSize > 0) {
            // Discontinuous mode: use full shell thickness so inner/outer intersections connect
            connectionThresholdSquared = stepSize * stepSize;
        } else {
            // Continuous mode: derive tolerance without voxel calibration.
            // Use max(1% of mean radius, intrinsic median segment length).
            final double rBarSquared = intersections.stream()
                    .mapToDouble(p -> p.distanceSquaredTo(center))
                    .average().orElse(0.0);
            final double s = intrinsicSegmentScale();
            final double thr = Math.max(0.01 * Math.sqrt(rBarSquared), (s > 0.0 ? s : 1e-9));
            connectionThresholdSquared = thr * thr;
        }

		for (int i = 0; i < intersections.size(); i++) {
			if (visited[i]) continue;
			
			// Start a new component
			final List<ShollPoint> component = new ArrayList<>();
			final List<Integer> toVisit = new ArrayList<>();
			toVisit.add(i);
			
			while (!toVisit.isEmpty()) {
				final int currentIdx = toVisit.removeLast();
				if (visited[currentIdx]) continue;
				
				visited[currentIdx] = true;
				final ShollPoint currentPoint = intersections.get(currentIdx);
				component.add(currentPoint);
				
				// Find nearby unvisited points
				for (int j = 0; j < intersections.size(); j++) {
					if (!visited[j]) {
						final ShollPoint otherPoint = intersections.get(j);
						final double distance = currentPoint.distanceSquaredTo(otherPoint);
						if (distance <= connectionThresholdSquared) {
							toVisit.add(j);
						}
					}
				}
			}
			
			components.add(component);
		}
		
		// Calculate centroid for each component
		for (final List<ShollPoint> component : components) {
			if (!component.isEmpty()) {
				double sumX = 0, sumY = 0, sumZ = 0;
				for (final ShollPoint point : component) {
					sumX += point.x;
					sumY += point.y;
					sumZ += point.z;
				}
				final int size = component.size();
				centroids.add(new ShollPoint(sumX / size, sumY / size, sumZ / size));
			}
		}
		
		return centroids;
	}

    /**
     * Computes the total arbor volume within a spherical shell [innerRadius, outerRadius)
     * as a sum of truncated-cone frusta contributed by each segment portion lying in the shell.
     * Segment radii are assumed to vary linearly between node endpoints. If either endpoint
     * radius is missing (NaN), the available radius is used for both ends; if both are missing,
     * the segment contributes no volume.
     */
    private double intersectedVolumeInShell(final double innerRadius, final double outerRadius) {
        if (outerRadius <= innerRadius) return 0.0;
        final double inner2 = innerRadius * innerRadius;
        final double outer2 = outerRadius * outerRadius;
        final double cx = center.x, cy = center.y, cz = center.z;
        double totalVol = 0.0;

        final PointInImage soma = tree.getRoot();
        final boolean skipFirstNode = isSkipSomaticSegments() && soma != null && soma.onPath.size() == 1
                && soma.onPath.getSWCType() == Path.SWC_SOMA;

        for (final Path path : tree.list()) {
            if (!running || path.size() == 0 || (skipFirstNode && path.equals(soma.onPath)))
                continue;

            final int startIdx = skipFirstNode ? 1 : 0;
            for (int i = startIdx; i < path.size() - 1; ++i) {
                final Path.PathNode a = path.getNode(i);
                final Path.PathNode b = path.getNode(i + 1);
                final double ax = a.x, ay = a.y, az = a.z;
                final double bx = b.x, by = b.y, bz = b.z;
                final double ux = bx - ax, uy = by - ay, uz = bz - az;
                final double segLen = a.distanceTo(b);
                if (segLen == 0.0) continue;

                final double rA = a.getRadius();
                final double rB = b.getRadius();
                final double dist1 = a.distanceTo(center);
                final double dist2 = b.distanceTo(center);
                final double minD = Math.min(dist1, dist2);
                final double maxD = Math.max(dist1, dist2);

                if (maxD >= innerRadius && minD <= outerRadius) {
                    if (minD >= innerRadius && maxD <= outerRadius) {
                        // Whole segment inside: single frustum with radii rA,rB
                        final double[] rr = effectiveRadii(rA, rB, 0.0, 1.0);
                        if (!Double.isNaN(rr[0]) && !Double.isNaN(rr[1])) {
                            totalVol += frustumVolume(segLen, rr[0], rr[1]);
                        }
                    } else {
                        // Partial: split by sphere intersections and keep sub-intervals whose mid-point lies in shell
                        final List<Double> tB = new ArrayList<>(8);
                        tB.add(0.0); tB.add(1.0);
                        // Intersections with inner and outer spheres
                        addTsForSphere(a, b, center, innerRadius, tB);
                        addTsForSphere(a, b, center, outerRadius, tB);
                        tB.sort(Double::compare);
                        // Deduplicate and clamp to [0,1]
                        final List<Double> T = new ArrayList<>(tB.size());
                        double last = Double.NaN;
                        for (double t : tB) {
                            final double tc = Math.max(0.0, Math.min(1.0, t));
                            if (T.isEmpty() || Math.abs(tc - last) > 1e-12) {
                                T.add(tc); last = tc;
                            }
                        }
                        for (int k = 0; k < T.size() - 1; k++) {
                            final double t0 = T.get(k), t1 = T.get(k + 1);
                            if (t1 <= t0) continue;
                            final double tm = 0.5 * (t0 + t1);
                            // squared radius of the mid-point relative to center
                            final double mx = (ax + tm*ux) - cx;
                            final double my = (ay + tm*uy) - cy;
                            final double mz = (az + tm*uz) - cz;
                            final double r2m = mx*mx + my*my + mz*mz;
                            if (r2m < inner2 || r2m > outer2) continue; // outside shell
                            final double h = segLen * (t1 - t0);
                            if (h <= 0.0) continue;
                            final double[] rr = effectiveRadii(rA, rB, t0, t1);
                            if (!Double.isNaN(rr[0]) && !Double.isNaN(rr[1])) {
                                totalVol += frustumVolume(h, rr[0], rr[1]);
                            }
                        }
                    }
                }
            }
        }
        return totalVol;
    }

    /**
     * Adds parameter values t in [0,1] where the segment (a→b) intersects the sphere of radius r around center.
     */
    private static void addTsForSphere(final Path.PathNode a, final Path.PathNode b,
                                       final PointInImage center, final double r, final List<Double> out) {
        if (r <= 0) return;
        final double ux = b.x - a.x, uy = b.y - a.y, uz = b.z - a.z;
        final double vx = a.x - center.x, vy = a.y - center.y, vz = a.z - center.z;
        final double A = ux * ux + uy * uy + uz * uz;
        if (A == 0.0) return;
        final double B = 2.0 * (ux * vx + uy * vy + uz * vz);
        final double C = vx * vx + vy * vy + vz * vz - r * r;
        double disc = B * B - 4 * A * C;
        if (disc < 0) {
            if (disc > -1e-12) disc = 0;
            else return;
        }
        final double sqrt = Math.sqrt(disc);
        final double inv = 1.0 / (2.0 * A);
        final double t1 = (-B - sqrt) * inv;
        final double t2 = (-B + sqrt) * inv;
        if (!Double.isNaN(t1)) out.add(t1);
        if (!Double.isNaN(t2)) out.add(t2);
    }

    /**
     * Returns effective radii at the two ends of a segment piece [t0,t1], handling missing values
     */
    private static double[] effectiveRadii(final double rA, final double rB, final double t0, final double t1) {
        double r0, r1;
        final boolean aNaN = Double.isNaN(rA), bNaN = Double.isNaN(rB);
        if (aNaN && bNaN) return new double[]{ Double.NaN, Double.NaN };
        if (aNaN) {
            r0 = rB;
            r1 = rB;
        } else if (bNaN) {
            r0 = rA;
            r1 = rA;
        } else {
            r0 = rA + (rB - rA) * t0;
            r1 = rA + (rB - rA) * t1;
        }
        return new double[]{r0, r1};
    }

    /**
     * Volume of a frustum (truncated cone): V = (pi * h / 3) * (r0^2 + r0*r1 + r1^2)
     */
    private static double frustumVolume(final double h, final double r0, final double r1) {
        return (Math.PI * h / 3.0) * (r0 * r0 + r0 * r1 + r1 * r1);
    }

    /**
     * Thin-shell version of {@link #intersectedVolumeInShell(double, double)} centered at a radius
     */
    private double intersectedVolumeAtRadius(final double radius) {
        final double shellThickness;
        if (stepSize > 0) shellThickness = stepSize;
        else {
            final double s = intrinsicSegmentScale();
            shellThickness = Math.max(radius * 0.01, (s > 0.0 ? s : 1e-9));
        }
        final double inner = Math.max(0.0, radius - shellThickness / 2.0);
        final double outer = radius + shellThickness / 2.0;
        return intersectedVolumeInShell(inner, outer);
    }

    private record ComparableShollPoint(double distanceSquared,
                                        boolean nearer) implements Comparable<ComparableShollPoint> {

        @Override
        public int compareTo(final ComparableShollPoint other) {
            if (this.distanceSquared < other.distanceSquared) return -1;
            else if (other.distanceSquared < this.distanceSquared) return 1;
            return 0;
        }

        @Override
        public boolean equals(Object o) {
            if (o == null || getClass() != o.getClass()) return false;
            final ComparableShollPoint other = (ComparableShollPoint) o;
            return nearer == other.nearer && Double.compare(distanceSquared, other.distanceSquared) == 0;
        }

        @Override
        public int hashCode() {
            int h = Double.hashCode(distanceSquared);
            return 31 * h + (nearer ? 1 : 0);
        }
    }

	/**
	 * Gets the labels image.
	 *
	 * @param templateImg the template img
	 * @param cTable the c table
	 * @return the labels image
	 */
	public ImagePlus getLabelsImage(final ImagePlus templateImg,
		final ColorTable cTable)
	{
		if (templateImg == null) throw new IllegalArgumentException(
			"Template image cannot be null");
		if (!successful() || crossingsPastEach == null ||
			squaredRangeStarts == null || center == null)
			throw new UnsupportedOperationException("Data has not been parsed");
		final int width = templateImg.getWidth();
		final int height = templateImg.getHeight();
		final int depth = templateImg.getNSlices();
		final Calibration c = templateImg.getCalibration();
		double x_spacing = 1;
		double y_spacing = 1;
		double z_spacing = 1;
		if (c != null) {
			x_spacing = c.pixelWidth;
			y_spacing = c.pixelHeight;
			z_spacing = c.pixelDepth;
		}
		final ImageStack stack = new ImageStack(width, height);
		for (int z = 0; z < depth; ++z) {
			final short[] pixels = new short[width * height];
			for (int y = 0; y < height; ++y) {
				for (int x = 0; x < width; ++x) {
					final PointInImage point = new PointInImage(x_spacing * x, y_spacing *
						y, z_spacing * z);
					pixels[y * width + x] = (short) crossingsAtDistanceSquared(point
						.distanceSquaredTo(center));
				}
			}
			final ShortProcessor sp = new ShortProcessor(width, height);
			sp.setPixels(pixels);
			stack.addSlice("", sp);
		}
		final ImagePlus result = new ImagePlus("Labels Image", stack);
		ImpUtils.applyColorTable(result, (cTable == null) ? ColorTables.ICE : cTable);
		result.setDisplayRange(0, new LinearProfileStats(profile, ShollStats.DataMode.INTERSECTIONS).getMax());
		result.setCalibration(templateImg.getCalibration());
		return result;
	}

	/* IDE debug method */
	public static void main(final String... args) {
		final Tree tree = new Tree(SNTUtils.randomPaths());
		final TreeParser parser = new TreeParser(tree);
		parser.setCenter(ROOT_NODES_ANY);
		parser.parse();
		parser.getProfile().plot().show();
	}

	/**
	 * Returns the tree associated with this parser
	 * 
	 * @return the profiled tree
	 */
	public Tree getTree() {
		return tree;
	}

	/** @see #setSkipSomaticSegments(boolean) */
	public boolean isSkipSomaticSegments() {
		return skipSomaticSegments;
	}

	/**
	 * If primary paths start far-away from the soma (expected to be defined by a
	 * single-point (soma centroid)), should segments between the soma and neurite
	 * be ignored. Useful when soma is large relatively to the length of arbor, as
	 * in microglia cells. See e.g.,
	 * <a href="https://forum.image.sc/t//51707/">this forum thread</a>.
	 * 
	 * @param skipSomaticSegments the skipFirsNode to set
	 */
	public void setSkipSomaticSegments(boolean skipSomaticSegments) {
		this.skipSomaticSegments = skipSomaticSegments;
	}

}
