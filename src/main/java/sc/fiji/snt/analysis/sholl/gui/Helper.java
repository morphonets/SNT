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
package sc.fiji.snt.analysis.sholl.gui;

import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import net.imagej.legacy.LegacyService;

import org.scijava.Context;
import org.scijava.app.StatusService;
import org.scijava.command.Command;
import org.scijava.command.CommandService;
import org.scijava.log.LogService;
import org.scijava.module.MethodCallException;
import org.scijava.module.Module;
import org.scijava.module.ModuleService;
import org.scijava.plugin.Parameter;
import org.scijava.prefs.PrefService;
import org.scijava.ui.DialogPrompt.MessageType;
import org.scijava.ui.DialogPrompt.OptionType;
import org.scijava.ui.DialogPrompt.Result;
import org.scijava.ui.UIService;
import org.scijava.util.VersionUtils;


public class Helper {

	@Parameter
	private CommandService cmdService;

	@Parameter
	private ModuleService moduleService;

	@Parameter
	private PrefService prefService;

	@Parameter
	private StatusService statusService;

	@Parameter
	private UIService uiService;

	private final String VERSION;

	@Deprecated
	public Helper() {
		this(new Context(LegacyService.class, PrefService.class, LogService.class, StatusService.class,
				UIService.class));
	}

	public Helper(final Context context) {
		context.inject(this);
		VERSION = VersionUtils.getVersion(sholl.gui.Helper.class);
	}

	public Result errorPrompt(final String message, final String title) {
		return uiService.showDialog(message, (title == null) ? "Sholl v" + VERSION + " Error" : title,
				MessageType.ERROR_MESSAGE, OptionType.DEFAULT_OPTION);
	}

	public void error(final String message, final String title) {
		uiService.showDialog(message, (title == null) ? "Sholl v" + VERSION + " Error" : title,
				MessageType.ERROR_MESSAGE);
	}

	public void infoMsg(final String message, final String title) {
		uiService.showDialog(message, (title == null) ? "Sholl v" + VERSION : title,
				MessageType.INFORMATION_MESSAGE);
	}

	public Result yesNoPrompt(final String message, final String title) {
		return uiService.showDialog(message, (title == null) ? "Sholl v" + VERSION : title,
				MessageType.QUESTION_MESSAGE, OptionType.YES_NO_OPTION);
	}

	public Result yesNoCancelPrompt(final String message, final String title) {
		return uiService.showDialog(message, (title == null) ? "Sholl v" + VERSION : title,
				MessageType.QUESTION_MESSAGE, OptionType.YES_NO_CANCEL_OPTION);
	}

	public <C extends Command> Module executeCommand(final Class<C> cmdClass, final Map<String, Object> inputs) {
		final Module module = moduleService.createModule(cmdService.getCommand(cmdClass));
		try {
			module.initialize();
		} catch (final MethodCallException ex) {
			ex.printStackTrace();
		}
		if (inputs != null) {
			inputs.forEach((k, v) -> {
				module.setInput(k, v);
				module.resolveInput(k);
			});
		}
		cmdService.run(cmdClass, true, inputs);
		module.run();
		final Future<Module> run = moduleService.run(module, true, inputs);
		try {
			run.get();
		} catch (final InterruptedException ex) {
			ex.printStackTrace();
		} catch (final ExecutionException ex) {
			ex.printStackTrace();
		}
		return module;
	}

}
