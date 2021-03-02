/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2021 Fiji developers.
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

package sc.fiji.snt;

import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.Assert;

import java.io.File;
import java.util.ArrayList;

import org.scijava.Context;
import org.scijava.MenuPath;
import org.scijava.script.ScriptFinder;
import org.scijava.script.ScriptInfo;
import org.scijava.script.ScriptModule;
import org.scijava.script.ScriptService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Scripting Tests
 * 
 * @author Cameron Arshadi
 * @author Tiago Ferreira
 */
public class ScriptTemplatesTest {

	private static final Logger LOGGER = LoggerFactory.getLogger(ScriptTemplatesTest.class);
	private final String scriptTemplatesResource = "script_templates/Neuroanatomy";
	private ScriptService scriptService;
	private ArrayList<ScriptInfo> scripts;

	@Before
	public void setUp() throws Exception {
		File scriptTemplatesBaseDirectory = new File(getClass().getClassLoader().getResource(scriptTemplatesResource).getFile());
		Assert.assertTrue(scriptTemplatesBaseDirectory.exists());
		Context context = new Context(ScriptService.class);
		scriptService = context.getService(ScriptService.class);
		File[] subDirectories = scriptTemplatesBaseDirectory.listFiles(File::isDirectory);
		for (File subDirectory : subDirectories) {
			scriptService.addScriptDirectory(subDirectory, new MenuPath(subDirectory.getName()));
		}
		ScriptFinder scriptFinder = new ScriptFinder(scriptService.context());
		scripts = new ArrayList<>();
		scriptFinder.findScripts(scripts);
	}

	@Ignore // Cannot be run yet fully headless. Skip for now
	@Test
	public void testScriptTemplates() throws Exception {
		//TODO: Allow headless execution
		for (ScriptInfo script : scripts) {
			LOGGER.info("##### Testing: " + script);
			// Some scripts require very specific inputs. For now we are just going to skip those
			if (script.getMenuPath().get(0).getName().contains("Batch")) {
				LOGGER.info("\tSkipping...: " + script);
				continue;
			}
			ScriptModule module = scriptService.run(script, true).get(); // calling get() waits for execution to finish			
			module.getOutputs().values().forEach(output -> {
				LOGGER.info("\tOutputs: " + output);
			});
		}
	}

}
