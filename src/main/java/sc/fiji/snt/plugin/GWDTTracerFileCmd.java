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

package sc.fiji.snt.plugin;

import org.scijava.command.Command;
import org.scijava.plugin.Plugin;

/**
 * Non-interactive command for file-based GWDT autotracing. Used by
 * {@link sc.fiji.snt.SNTUI SNTUI}'s ImportAction to process images that may
 * be too large to fit into memory. Unlike {@link GWDTTracerCmd}, this command
 * does not implement {@code Interactive} — the standard SciJava OK button
 * triggers {@link #run()}, which executes the tracing directly.
 *
 * @author Tiago Ferreira
 * @see GWDTTracerCommonCmd
 * @see GWDTTracerCmd
 */
@Plugin(type = Command.class, initializer = "init")
public class GWDTTracerFileCmd extends GWDTTracerCommonCmd {

    @SuppressWarnings("unused")
    private void init() {
        initForFile();
    }

    @Override
    protected boolean isFileMode() {
        return true;
    }

    @Override
    public void run() {
        if (isCanceled()) return;
        runCommand();
    }
}
