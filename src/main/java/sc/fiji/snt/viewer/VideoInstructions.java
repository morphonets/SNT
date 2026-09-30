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

package sc.fiji.snt.viewer;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/**
 * Writes the instructions on how to assemble a recorded image sequence into a video using ffmpeg.
 * Shared by {@link Viewer3D} and the {@link Bvv}/{@link Bdv} keyframe recorder.
 */
final class VideoInstructions {

    static final String FILE_NAME = "-build-video.txt";

    private VideoInstructions() {
    }

    /**
     * Writes {@value #FILE_NAME} into the folder of the image sequence. If the file cannot be written,
     * the instructions are printed to the console instead.
     *
     * @param dir          the folder holding the image sequence
     * @param fps          the frame rate of the video
     * @param inputPattern the ffmpeg input pattern of the frames, e.g., {@code frame_%05d.png}
     */
    static void write(final File dir, final int fps, final String inputPattern) {
        final String text = build(dir, fps, inputPattern);
        try {
            Files.writeString(new File(dir, FILE_NAME).toPath(), text);
        } catch (final IOException e) {
            System.out.println(text);
        }
    }

    static String build(final File dir, final int fps, final String inputPattern) {
        final StringBuilder sb = new StringBuilder(
                "The image sequence can be converted into a video using ffmpeg (www.ffmpeg.org):\n");
        sb.append("  cd \"").append(dir).append("\"\n");
        sb.append("  ffmpeg -framerate ").append(fps).append(" -i ").append(inputPattern)
                .append(" -vf \"crop=trunc(iw/2)*2:trunc(ih/2)*2,format=yuv420p\" video.mp4\n\n");
        sb.append("- The crop filter trims at most 1 pixel so that width and height are even, as required\n");
        sb.append("  by yuv420p. Remove it if your frames already have even dimensions\n\n");
        sb.append("- Parameters that can be added in front of crop in the comma-separated list of -vf \"\" options:\n");
        sb.append("  hflip          flip sequence horizontally\n");
        sb.append("  vflip          flip sequence vertically\n");
        sb.append("  transpose=0    90 degrees counterclockwise and vertical flip\n");
        sb.append("  transpose=1    90 degrees clockwise\n");
        sb.append("  transpose=2    90 degrees counterclockwise\n");
        sb.append("  transpose=3    90 degrees clockwise and vertical flip\n\n");
        sb.append("- To use all images in a folder use e.g.:\n");
        sb.append("  ffmpeg -framerate ").append(fps)
                .append(" -pattern_type glob -i \"*.png\" -vf \"(...)\" video.mp4\n\n");
        sb.append("Alternatively, ImageJ built-in commands can also be used, e.g.:\n");
        sb.append("\"File>Import>Image Sequence...\", followed by \"File>Save As>AVI...\"");
        return sb.toString();
    }
}
