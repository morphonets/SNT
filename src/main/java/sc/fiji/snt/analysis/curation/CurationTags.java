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

package sc.fiji.snt.analysis.curation;

import sc.fiji.snt.Path;
import sc.fiji.snt.Tree;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Reserved {@code cur:*} path tags used to record a path's review status
 * after it has been examined in the Curation Assistant.
 * <p>
 * SNT's custom tags are encoded as <code>{tag}</code> suffixes on a path's
 * name string (see {@code PathManagerUI#applyCustomTags}), which means they
 * get stored in {@code .traces} files and show up in the Path Manager's
 * tag-filter dropdown. This utility provides typed helpers for the
 * curation-specific subset so callers (scripts, training-data exporters)
 * don't have parse path names.
 * <p>
 * Two states are supported:
 * <ul>
 *   <li>{@link #POSITIVE}: the path was reviewed and accepted; suitable
 *       as a positive training example.</li>
 *   <li>{@link #NEGATIVE}: the path was reviewed and rejected; suitable
 *       as a negative example for QC-classifier training.</li>
 *   <li>{@link #UNSURE}: the path was reviewed but tagged for follow-up review</li>
 * </ul>
 *
 * @author Tiago Ferreira
 */
public final class CurationTags {

    /**
     * Tag value applied to paths accepted during review.
     */
    public static final String POSITIVE = "cur:accept";
    /**
     * Tag value applied to paths rejected during review.
     */
    public static final String NEGATIVE = "cur:reject";
    /**
     * Tag value applied to paths flagged for follow-up review.
     * Useful for batch workflows where some paths need a second pass
     * before being committed to a training set.
     */
    public static final String UNSURE = "cur:unsure";

    /**
     * Matches any {@code cur:*} suffix in a path name, including the leading
     * space that {@code applyCustomTags} inserts. Used by {@link #clearReview}
     * and by every {@code mark*} call to dedupe before reapplying.
     */
    private static final Pattern CUR_TAG_PATTERN = Pattern.compile(" ?\\{cur:[^}]*}");

    private CurationTags() {
    }

    /**
     * Tags {@code path} as a positive (accepted) review example.
     */
    public static void markPositive(final Path path) {
        applyTag(path, POSITIVE);
    }

    /**
     * Tags {@code path} as a negative (rejected) review example.
     */
    public static void markNegative(final Path path) {
        applyTag(path, NEGATIVE);
    }

    /**
     * Tags {@code path} as needing follow-up review ("not sure").
     */
    public static void markUnsure(final Path path) {
        applyTag(path, UNSURE);
    }

    /**
     * Removes every {@code cur:*} suffix from {@code path}'s name. Does
     * not touch other custom tags. Idempotent on already-clean paths.
     */
    public static void clearReview(final Path path) {
        if (path == null) return;
        final String name = path.getName();
        if (name == null) return;
        final String cleaned = CUR_TAG_PATTERN.matcher(name).replaceAll("");
        if (!cleaned.equals(name)) path.setName(cleaned);
    }

    /**
     * @return {@code true} if {@code path} carries the positive review tag.
     */
    public static boolean isPositive(final Path path) {
        return contains(path, POSITIVE);
    }

    /**
     * @return {@code true} if {@code path} carries the negative review tag.
     */
    public static boolean isNegative(final Path path) {
        return contains(path, NEGATIVE);
    }

    /**
     * @return {@code true} if {@code path} carries the "not sure" review tag.
     */
    public static boolean isUnsure(final Path path) {
        return contains(path, UNSURE);
    }

    /**
     * @return {@code true} if {@code path} carries any {@code cur:*} review
     * tag (positive, negative, or a future variant).
     */
    public static boolean isReviewed(final Path path) {
        if (path == null) return false;
        final String name = path.getName();
        return name != null && CUR_TAG_PATTERN.matcher(name).find();
    }

    private static void applyTag(final Path path, final String tag) {
        if (path == null) return;
        // Drop any prior cur:* tag first so that a path can't end up carrying both POSITIVE and NEGATIVE
        clearReview(path);
        path.setName(path.getName() + " {" + tag + "}");
    }

    private static boolean contains(final Path path, final String tag) {
        if (path == null) return false;
        final String name = path.getName();
        return name != null && name.contains("{" + tag + "}");
    }

    /**
     * Splits a {@link Tree}'s paths into four buckets by review status:
     * positive, negative, unsure, and unreviewed (no {@code cur:*} tag).
     * Buckets are returned in the iteration order of the source tree, so
     * downstream exporters can rely on stable ordering.
     * <p>
     * Typical use from a script:
     * <pre>
     *   Partition p = CurationTags.partitionByReviewStatus(tree);
     *   exportAsTrainingPositives(p.positive());
     *   exportAsTrainingNegatives(p.negative());
     *   ui.warn(p.unsure().size() + " path(s) still need review.");
     * </pre>
     *
     * @param tree the source tree; {@code null} or empty yields an empty partition.
     * @return a {@link Partition} record with one list per status bucket.
     */
    public static Partition partitionByReviewStatus(final Tree tree) {
        return partitionByReviewStatus((tree == null) ? null : tree.list());
    }

    /**
     * Sibling of {@link #partitionByReviewStatus(Tree)} that accepts any
     * iterable of paths.
     */
    public static Partition partitionByReviewStatus(final Collection<Path> paths) {
        final List<Path> positive = new ArrayList<>();
        final List<Path> negative = new ArrayList<>();
        final List<Path> unsure = new ArrayList<>();
        final List<Path> unreviewed = new ArrayList<>();
        if (paths != null) {
            for (final Path p : paths) {
                if (p == null) continue;
                if (isPositive(p)) positive.add(p);
                else if (isNegative(p)) negative.add(p);
                else if (isUnsure(p)) unsure.add(p);
                else unreviewed.add(p);
            }
        }
        return new Partition(positive, negative, unsure, unreviewed);
    }

    /**
     * Four-bucket breakdown returned by
     * {@link CurationTags#partitionByReviewStatus(Tree)}. Lists are
     * never {@code null}; they may be empty.
     */
    public record Partition(
            List<Path> positive,
            List<Path> negative,
            List<Path> unsure,
            List<Path> unreviewed
    ) {
        /**
         * Total path count across all four buckets.
         */
        public int total() {
            return positive.size() + negative.size() + unsure.size() + unreviewed.size();
        }

        /**
         * Paths with any review status (i.e. any {@code cur:*} tag).
         */
        public int reviewed() {
            return positive.size() + negative.size() + unsure.size();
        }
    }
}
