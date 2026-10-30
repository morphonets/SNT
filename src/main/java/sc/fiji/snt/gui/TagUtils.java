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

package sc.fiji.snt.gui;

import org.scijava.util.ColorRGB;

import java.util.Collection;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Utilities for handling custom tags appended to entries as {@code " {tag1, tag2}"}
 *
 * @author Tiago Ferreira
 */
public final class TagUtils {

	private static final Pattern COMMA = Pattern.compile(",\\s*");

	private TagUtils() {}

	public static String getCleansedTag(final String candidate) {
		return candidate.replace("{", "").replace("}", "");
	}

	public static String applyColor(final String entry, final ColorRGB color) {
		final String hex = color.toHTMLColor();
		final int open = entry.indexOf(" {");
		final int close = entry.lastIndexOf('}');
		if (open >= 0 && close > open) {
			// replace the first color tag, only within the tag block
			final String[] tags = COMMA.split(entry.substring(open + 2, close));
			for (int i = 0; i < tags.length; i++) {
				if (ColorRGB.fromHTMLColor(tags[i]) != null) {
					tags[i] = hex;
					return entry.substring(0, open) + " {" + String.join(", ", tags) + "}" + entry.substring(close + 1);
				}
			}
		}
		return appendTag(entry, hex);
	}

	public static String applyTag(final String entry, final String tag) {
		final String cleansedTag = getCleansedTag(tag).trim();
		return (cleansedTag.isEmpty()) ? entry : appendTag(entry, cleansedTag);
	}

	private static String appendTag(final String entry, final String tag) {
		final int open = entry.indexOf(" {");
		final int close = entry.lastIndexOf('}');
		if (open < 0 || close <= open) return entry + " {" + tag + "}";
		final boolean emptyBlock = entry.substring(open + 2, close).trim().isEmpty();
		return entry.substring(0, close) + (emptyBlock ? "" : ", ") + tag + entry.substring(close);
	}

	public static String removeAllTags(final String entry) {
		return getUntaggedEntry(entry);
	}

	public static String[] getTagsFromEntry(final String entry) {
		final String tagString = getTagStringFromEntry(entry);
		if (tagString.isEmpty()) return new String[] {};
		return tagString.split("\\s*([,\\s])\\s*");
	}

	public static String getTagStringFromEntry(final String entry) {
		final int openingDlm = entry.indexOf(" {");
		final int closingDlm = entry.lastIndexOf('}');
		if (openingDlm >= 0 && closingDlm > openingDlm) {
			return entry.substring(openingDlm + 2, closingDlm);
		}
		return "";
	}

	public static String getUntaggedEntry(final String entry) {
		final int openingDlm = entry.indexOf(" {");
		return (openingDlm == -1) ? entry : entry.substring(0, openingDlm);
	}

	public static String[] getUntaggedAndTaggedLabels(final String entry) {
		final int openingDlm = entry.indexOf(" {");
		if (openingDlm == -1) {
			return new String[] { entry, entry };
		}
		return new String[] { entry.substring(0, openingDlm), entry };
	}

	/**
	 * Gets the sorted set of unique custom tags found in a collection of entries
	 * containing "{tag1, tag2}" blocks (e.g., list entries, whose tag block is
	 * delimited by " {")
	 */
	public static Set<String> getUniqueTags(final Collection<String> entries) {
		return getUniqueTags(entries, true);
	}

	/**
	 * Gets the sorted set of unique custom tags found in a collection of entries
	 *
	 * @param entries        the entries (e.g., list entries or Path names)
	 * @param spaceDelimited if true, tag blocks are delimited by " {" (list entries). If false the opening
	 *                       brace may directly follow the text (Path names)
	 * @see TagChips#parseCustom(String, boolean)
	 */
	public static Set<String> getUniqueTags(final Collection<String> entries, final boolean spaceDelimited) {
		final TreeSet<String> unique = new TreeSet<>();
		for (final String entry : entries) {
			final String[] tags = TagChips.parseCustom(entry, spaceDelimited);
			if (tags != null) unique.addAll(java.util.Arrays.asList(tags));
		}
		return unique;
	}

	/**
	 * Replaces all custom tags of an entry. An empty collection removes all tags
	 */
	public static String setTags(final String entry, final Collection<String> tags) {
		final String untagged = getUntaggedEntry(entry);
		final java.util.List<String> cleansed = new java.util.ArrayList<>();
		for (final String tag : tags) {
			final String t = getCleansedTag(tag).trim();
			if (!t.isEmpty()) cleansed.add(t);
		}
		return cleansed.isEmpty() ? untagged : untagged + " {" + String.join(", ", cleansed) + "}";
	}

}
