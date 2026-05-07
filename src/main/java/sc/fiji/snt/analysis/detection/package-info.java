/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2026 Fiji developers.
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

/**
 * Feature detectors for traced paths. Contains detectors that find
 * morphological features along or around neuronal paths:
 * <ul>
 *   <li>{@link sc.fiji.snt.analysis.detection.PeripathDetector}: Detects
 *       intensity maxima in perpendicular cross-sections around paths
 *       (spines, synaptic puncta)</li>
 *   <li>{@link sc.fiji.snt.analysis.detection.AlongPathDetector}: Detects
 *       swellings (boutons, varicosities) along paths using longitudinal
 *       radius and intensity profiles</li>
 * </ul>
 * Shared utilities are in {@link sc.fiji.snt.analysis.detection.DetectorUtils}
 * and the common result type is {@link sc.fiji.snt.analysis.detection.Detection}.
 */
package sc.fiji.snt.analysis.detection;
