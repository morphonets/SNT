/*
 * #%L
 * Sholl Analysis plugin for ImageJ.
 * %%
 * Copyright (C) 2005 - 2020 Tiago Ferreira.
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
package sc.fiji.snt.util;

import org.scijava.Context;
import org.scijava.log.LogService;
import org.scijava.plugin.Parameter;
import org.scijava.prefs.PrefService;

import sc.fiji.snt.plugin.ShollAnalysisPrefsCmd;

public class Logger {

	@Parameter
	private LogService logService;

	@Parameter
	private PrefService prefService;

	private boolean debug;
	private final String callerIdentifier;

	public Logger(final Context context, final String callerIdentifier) {
		context.inject(this);
		debug = prefService.getBoolean(ShollAnalysisPrefsCmd.class, "debugMode", ShollAnalysisPrefsCmd.DEF_DEBUG_MODE);
		setDebug(debug || logService.isDebug());
		this.callerIdentifier = callerIdentifier;
	}

	public void info(final Object msg) {
		logService.info(callerIdentifier + ": " + msg );
	}

	public void debug(final Object msg) {
		if (debug) logService.info(callerIdentifier + ": " + msg);
	}

	public void warn(final String string) {
		logService.warn(callerIdentifier + ": " + string);
	}

	public boolean isDebug() {
		return debug;
	}

	public void setDebug(final boolean debug) {
		this.debug = debug;
	}

}
