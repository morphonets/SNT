/*
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
package sc.fiji.snt.util;

import org.scijava.Context;
import org.scijava.log.LogService;
import org.scijava.plugin.Parameter;
import org.scijava.prefs.PrefService;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.plugin.ShollAnalysisPrefsCmd;

/**
 * The Logger class provides functionality for logging messages with different levels such as
 * info, debug, and warn.
 *
 * Relies on external services, such as LogService and PrefService, injected via
 * the provided context.
 */
public class Logger {

	@Parameter
	private LogService logService;

	@Parameter
	private PrefService prefService;

	private boolean debug;
	private final String callerIdentifier;

    public Logger(final Class<?> clazz) {
        this(SNTUtils.getContext(), clazz.getSimpleName());
    }

    /**
     * Constructs a new Logger with the specified context and caller identifier.
     *
     * @param context the SciJava context for dependency injection
     * @param callerIdentifier the identifier for the calling class/component
     */
	public Logger(final Context context, final String callerIdentifier) {
		context.inject(this);
		debug = SNTUtils.isDebugMode() || prefService.getBoolean(ShollAnalysisPrefsCmd.class, "debugMode",
				ShollAnalysisPrefsCmd.DEF_DEBUG_MODE);
		setDebug(debug || logService.isDebug());
		this.callerIdentifier = callerIdentifier;
	}

	/**
	 * Logs an informational message.
	 *
	 * @param msg the message to log
	 */
	public void info(final Object msg) {
		logService.info(callerIdentifier + ": " + msg );
	}

	/**
	 * Logs a debug message (only if debug mode is enabled).
	 *
	 * @param msg the debug message to log
	 */
	public void debug(final Object msg) {
		if (debug) logService.info(callerIdentifier + ": " + msg);
	}

	/**
	 * Logs a warning message.
	 *
	 * @param string the warning message to log
	 */
	public void warn(final String string) {
		logService.warn(callerIdentifier + ": " + string);
	}

	/**
	 * Checks if debug mode is enabled.
	 *
	 * @return true if debug mode is enabled, false otherwise
	 */
	public boolean isDebug() {
		return debug;
	}

	/**
	 * Sets the debug mode state.
	 *
	 * @param debug true to enable debug mode, false to disable
	 */
	public void setDebug(final boolean debug) {
		this.debug = debug;
	}

}
