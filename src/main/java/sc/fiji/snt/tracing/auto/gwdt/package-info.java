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
 * Storage backends for the Gray-Weighted Distance Transform (GWDT) tracing
 * algorithm.
 * <p>
 * Provides pluggable storage strategies for GWDT and Fast Marching data
 * structures, including in-memory ({@link sc.fiji.snt.tracing.auto.gwdt.ArrayStorageBackend}), disk-backed
 * ({@link sc.fiji.snt.tracing.auto.gwdt.DiskBackedStorageBackend}), and sparse
 * ({@link sc.fiji.snt.tracing.auto.gwdt.SparseStorageBackend})
 * implementations. These backends manage distance values, parent pointers, and
 * voxel state arrays used during geodesic distance computation.
 * </p>
 *
 * @see sc.fiji.snt.tracing.auto.GWDTTracer
 * @see sc.fiji.snt.tracing.auto.gwdt.StorageBackend
 */
package sc.fiji.snt.tracing.auto.gwdt;
