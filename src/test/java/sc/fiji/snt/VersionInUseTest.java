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
package sc.fiji.snt;

import static org.junit.Assert.*;

import java.util.List;

import org.junit.Before;
import org.junit.Test;

import sc.fiji.snt.util.PointInImage;

/**
 * Tests the "version in use" rule across Path, Tree and PathAndFillManager.
 */
public class VersionInUseTest {

	private Path original;
	private Path fitted;

	@Before
	public void setUp() {
		original = new Path(1.0, 1.0, 1.0, "um");
		original.addNode(new PointInImage(0, 0, 0));
		original.addNode(new PointInImage(10, 0, 0));
		fitted = new Path(1.0, 1.0, 1.0, "um");
		fitted.addNode(new PointInImage(0, 1, 0));
		fitted.addNode(new PointInImage(10, 1, 0));
		original.setFitted(fitted);
	}

	@Test
	public void testPathRule() {
		assertSame(original, original.getVersionInUse());
		original.setUseFitted(true);
		assertSame(fitted, original.getVersionInUse());
		// a fitted copy has no fitted flavor of its own
		assertSame(fitted, fitted.getVersionInUse());
	}

	@Test
	public void testTreeOriginalOnly() {
		original.setUseFitted(true);
		assertEquals(List.of(fitted), new Tree(List.of(original)).list());
		original.setUseFitted(false);
		assertEquals(List.of(original), new Tree(List.of(original)).list());
	}

	@Test
	public void testTreeFittedCopyOnly() {
		original.setUseFitted(true);
		assertEquals(List.of(fitted), new Tree(List.of(fitted)).list());
	}

	@Test
	public void testTreeBothFlavorsNotDuplicated() {
		original.setUseFitted(true);
		assertEquals(List.of(fitted), new Tree(List.of(original, fitted)).list());
		assertEquals(List.of(fitted), new Tree(List.of(fitted, original)).list());
		original.setUseFitted(false);
		assertEquals(List.of(original), new Tree(List.of(fitted, original)).list());
	}

	@Test
	public void testManagerResolvesBothFlavorsToSameVersion() {
		final PathAndFillManager pafm = new PathAndFillManager();
		pafm.addPath(original);
		assertNull(pafm.getVersionInUse(null));
		assertSame(original, pafm.getVersionInUse(original));
		assertSame(original, pafm.getVersionInUse(fitted));
		original.setUseFitted(true);
		assertSame(fitted, pafm.getVersionInUse(original));
		assertSame(fitted, pafm.getVersionInUse(fitted));
	}
}
