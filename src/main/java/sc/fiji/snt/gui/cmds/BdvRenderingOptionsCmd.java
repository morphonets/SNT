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

import org.scijava.command.Command;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import sc.fiji.snt.viewer.AbstractBigViewer;
import sc.fiji.snt.viewer.Bdv;

/**
 * Command providing a GUI for configuring {@link AbstractBigViewer.PathRenderingOptions} in
 * a {@link Bdv} viewer.
 *
 * @author Tiago Ferreira
 * @see BvvRenderingOptionsCmd
 * @see BigViewerRenderingOptionsCmd
 */
@Plugin(type = Command.class, label = "Annotations: Rendering Options", initializer = "init")
public class BdvRenderingOptionsCmd extends BigViewerRenderingOptionsCmd {

    @Parameter
    private Bdv bdv;

    @Override
    protected AbstractBigViewer getViewer() {
        return bdv;
    }
}
