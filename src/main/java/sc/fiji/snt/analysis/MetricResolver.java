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

package sc.fiji.snt.analysis;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.apache.commons.text.similarity.LevenshteinDistance;

import sc.fiji.snt.SNTUtils;

/**
 * Resolves metric names specified by users (or scripts) to the canonical names
 * a given class supports. Resolution is attempted in stages:
 * <ol>
 * <li>exact match</li>
 * <li>match ignoring case, whitespace and punctuation</li>
 * <li>(optional) a class-specific heuristic that maps loose phrases, e.g.,
 * "# bps", to a canonical name</li>
 * </ol>
 * The result of the heuristic is always validated against the supported
 * metrics, so that a resolver can never return a metric its owner cannot
 * compute. Anything else triggers an {@link UnknownMetricException} listing the
 * closest supported metrics.
 *
 * @author Tiago Ferreira
 */
final class MetricResolver {

	private final String kind;
	private final Set<String> metrics;
	private final Map<String, String> byKey;
	private final Function<Words, String> heuristic;

	/**
	 * @param kind      short description of the domain, used in messages
	 * @param metrics   the canonical metric names. When two names share the same
	 *                  {@link #key(String) key}, the first one prevails
	 * @param heuristic maps loose phrases to a canonical name. May be null, and
	 *                  may return null when it has no match
	 */
	MetricResolver(final String kind, final Collection<String> metrics, final Function<Words, String> heuristic) {
		this.kind = kind;
		this.metrics = new LinkedHashSet<>(metrics);
		this.byKey = new LinkedHashMap<>();
		for (final String m : this.metrics)
			byKey.putIfAbsent(key(m), m);
		this.heuristic = heuristic;
	}

	/** Exact match or match ignoring case/punctuation. @return null if no match */
	String resolveStrict(final String input) {
		if (input == null) return null;
		if (metrics.contains(input)) return input;
		return byKey.get(key(input));
	}

	/** @return the canonical name, or null if input cannot be resolved */
	String resolveOrNull(final String input) {
		final String strict = resolveStrict(input);
		if (strict != null || input == null || heuristic == null) return strict;
		final String guess = heuristic.apply(new Words(input));
		if (guess != null && metrics.contains(guess)) {
			SNTUtils.log("\"" + guess + "\" assumed for \"" + input + "\"");
			return guess;
		}
		return null;
	}

	/**
	 * @param input  the metric specified by the user
	 * @param strict if true, the heuristic is skipped
	 * @return the canonical name
	 * @throws UnknownMetricException if input cannot be resolved
	 */
	String resolve(final String input, final boolean strict) throws UnknownMetricException {
		final String result = (strict) ? resolveStrict(input) : resolveOrNull(input);
		if (result == null) throw unknown(input);
		return result;
	}

	String resolve(final String input) throws UnknownMetricException {
		return resolve(input, false);
	}

	boolean isSupported(final String canonical) {
		return metrics.contains(canonical);
	}

	Set<String> metrics() {
		return metrics;
	}

	private UnknownMetricException unknown(final String input) {
		final String msg = "Unrecognized " + kind + " metric: \"" + input + "\"";
		final List<String> closest = closest(input, 3);
		return new UnknownMetricException((closest.isEmpty()) ? msg : msg + ". Did you mean: " + closest + "?");
	}

	private List<String> closest(final String input, final int n) {
		if (input == null) return List.of();
		final String k = key(input);
		final List<String> sorted = new ArrayList<>(metrics);
		final Map<String, Integer> score = new LinkedHashMap<>();
		for (final String m : sorted) {
			final String mk = key(m);
			// a metric containing (or contained by) the input is a better hit
			int d = (!k.isEmpty() && (mk.contains(k) || k.contains(mk))) ? 0 : distance(k, mk);
			// a typo in a single word is closer than the full name suggests
			for (final String word : m.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
				if (word.length() > 2) d = Math.min(d, distance(k, word));
			}
			score.put(m, d);
		}
		sorted.sort(Comparator.comparingInt(score::get));
		return new ArrayList<>(sorted.subList(0, Math.min(n, sorted.size())));
	}

	/**
	 * Normalizes a metric name for comparisons: lower case with only letters and
	 * digits retained
	 *
	 * @param s the string to be normalized
	 * @return the normalized key
	 */
	static String key(final String s) {
		if (s == null) return "";
		final StringBuilder sb = new StringBuilder(s.length());
		for (int i = 0; i < s.length(); i++) {
			final char c = s.charAt(i);
			if (Character.isLetterOrDigit(c)) sb.append(Character.toLowerCase(c));
		}
		return sb.toString();
	}

	private static int distance(final String a, final String b) {
		return LevenshteinDistance.getDefaultInstance().apply(a, b);
	}

	/**
	 * The words of a loosely specified metric. Matching is done on word stems
	 * (i.e., words <i>starting</i> with a stem), which, unlike substring tests,
	 * does not trigger in the middle of unrelated words (e.g., "int" in "internode").
	 */
	static final class Words {

		private final List<String> words;

		Words(final String s) {
			final List<String> list = new ArrayList<>();
			for (final String w : s.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
				if (!w.isEmpty()) list.add(w);
			}
			words = list;
		}

		/** @return true if any word starts with any of the stems */
		boolean any(final String... stems) {
			for (final String w : words) {
				for (final String stem : stems) {
					if (w.startsWith(stem)) return true;
				}
			}
			return false;
		}

		/** @return true if any word is equal to any of the specified words */
		boolean is(final String... exact) {
			for (final String w : words) {
				if (Arrays.asList(exact).contains(w)) return true;
			}
			return false;
		}

		@Override
		public String toString() {
			return String.join(" ", words);
		}
	}
}
