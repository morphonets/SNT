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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public
 * License along with this program. If not, see
 * <http://www.gnu.org/licenses/gpl-3.0.html>.
 * #L%
 */

package sc.fiji.snt.viewer;

import bdv.tools.brightness.ConverterSetup;
import bdv.viewer.SourceAndConverter;

/**
 * Viewer-agnostic view of the channels of a single image displayed in a BDV/BVV
 * viewer: all that features operating on the channels of an image (e.g.,
 * {@link ChannelUnmixingCard}) need to know about the underlying sources.
 */
interface ChannelGroup {

    /** @return the number of channels */
    int size();

    /** @return the (first) source of the i-th channel */
    SourceAndConverter<?> channelSource(int i);

    /** @return the converter setup (display range, color) of the i-th channel */
    ConverterSetup channelSetup(int i);

    /** Shows or hides all channels of the group */
    void setActive(boolean active);
}
