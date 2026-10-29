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

package sc.fiji.snt.gui.cmds;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.net.URI;
import java.util.Map;

import org.junit.Test;

/**
 * Tests {@link SNTLink}
 *
 * @author Tiago Ferreira
 */
public class SNTLinkTest {

	@Test
	public void testQueryKeepsEncodedDelimitersInValues() {
		final String source = "https://host/data.zarr?a=1&b=2";
		final String link = SNTLink.locationLink(source, null, true, 1, 2, 3, 4);
		final Map<String, String> query = SNTLink.query(URI.create(link));
		assertEquals(source, query.get("image"));
		assertEquals("1.000,2.000,3.000,4", query.get("view"));
		assertEquals(2, query.size());
	}

	@Test
	public void testQueryKeepsLiteralPlus() {
		final Map<String, String> query = SNTLink.query(URI.create(
			"fiji://snt/open?image=a+b&flag"));
		assertEquals("a+b", query.get("image"));
		assertNull(query.get("flag"));
		assertEquals(2, query.size());
	}

	@Test
	public void testQueryWithoutQuery() {
		assertEquals(0, SNTLink.query(URI.create("fiji://snt/open")).size());
	}

	@Test
	public void testParseView() {
		assertArrayEquals(new double[] { 1, 2.5, -3, -1 }, SNTLink.parseView(
			"1,2.5,-3"), 0);
		assertArrayEquals(new double[] { 1, 2, 3, 4 }, SNTLink.parseView(
			"1,2,3,4"), 0);
		assertNull(SNTLink.parseView("1,2"));
		assertNull(SNTLink.parseView("1,2,x"));
		assertNull(SNTLink.parseView("1,2,3,0"));
		assertNull(SNTLink.parseView("1,2,NaN"));
	}

}
