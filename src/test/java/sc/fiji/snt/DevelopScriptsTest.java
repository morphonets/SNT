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

package sc.fiji.snt;

import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeNotNull;

import java.io.File;
import java.io.StringWriter;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.scijava.Context;
import org.scijava.module.ModuleService;
import org.scijava.script.ScriptInfo;
import org.scijava.script.ScriptModule;
import org.scijava.script.ScriptService;
import org.scijava.ui.UIService;

/**
 * Tests the helper scripts in {@code script_templates/Neuroanatomy/Develop} for agentic scripting.
 * Skipped if no Groovy engine is available in the test classpath
 *
 * @author Tiago Ferreira
 */
public class DevelopScriptsTest {

	static { net.imagej.patcher.LegacyInjector.preinit(); } // required for _every_ class that imports ij. classes

	private static final String DIR = "script_templates/Neuroanatomy/Develop/";
	private Context context;
	private ModuleService moduleService;

	@Before
	public void setUp() {
		context = new Context(ScriptService.class, ModuleService.class, SNTService.class, UIService.class);
		moduleService = context.getService(ModuleService.class);
		final ScriptService scriptService = context.getService(ScriptService.class);
		assumeNotNull(scriptService.getLanguageByName("Groovy"));
	}

	@After
	public void tearDown() {
		if (context != null) context.getService(SNTService.class).dispose();
		if (context != null) context.dispose();
	}

	/** Runs a bundled script and returns what it printed (stdout + stderr) */
	private String run(final String script, final Map<String, Object> inputs) throws Exception {
		final URL url = getClass().getClassLoader().getResource(DIR + script);
		assumeNotNull(url);
		final ScriptInfo info = new ScriptInfo(context, url, script);
		final ScriptModule module = info.createModule();
		context.inject(module);
		final StringWriter out = new StringWriter();
		module.setOutputWriter(out);
		module.setErrorWriter(out);
		moduleService.run(module, true, inputs).get();
		return out.toString();
	}

	private static Map<String, Object> lookup(final String mode, final String query) {
		final Map<String, Object> map = new HashMap<>();
		map.put("mode", mode);
		map.put("query", query);
		map.put("includeDeprecated", false);
		map.put("output", "JSON");
		return map;
	}

	@Test
	public void testApiLookupPackages() throws Exception {
		final String out = run("API_Lookup.groovy", lookup("packages", ""));
		assertTrue(out, out.contains("\"sc.fiji.snt.analysis\""));
	}

	@Test
	public void testApiLookupClass() throws Exception {
		final String out = run("API_Lookup.groovy", lookup("class", "Tree"));
		assertTrue(out, out.contains("\"class\": \"sc.fiji.snt.Tree\""));
		assertTrue(out, out.contains("\"methods\""));
		assertTrue(out, out.contains("getRoot()"));
	}

	@Test
	public void testApiLookupSearch() throws Exception {
		final String out = run("API_Lookup.groovy", lookup("search", "BinaryTracer"));
		assertTrue(out, out.contains("sc.fiji.snt.tracing.auto.BinaryTracer"));
	}

	@Test
	public void testApiLookupUnknownClassSuggestsAlternatives() throws Exception {
		final String out = run("API_Lookup.groovy", lookup("class", "TreeStatisticz"));
		assertTrue(out, out.contains("\"error\""));
		assertTrue(out, out.contains("TreeStatistics")); // in did_you_mean
	}

	@Test
	@Ignore
	public void testInspectSessionWithoutReconstruction() throws Exception {
		final Map<String, Object> inputs = new HashMap<>();
		inputs.put("maxPaths", 5);
		inputs.put("output", "Console");
		final String out = run("Inspect_Session.groovy", inputs);
		assertTrue(out, out.contains("\"snt_version\""));
		assertTrue(out, out.contains("\"snt_active\": false"));
	}

	@Test
	@Ignore
	public void testInspectSessionLoadsReconstruction() throws Exception {
		final Tree demo = context.getService(SNTService.class).demoTree("TreeV");
		assumeNotNull(demo);
		final File swc = File.createTempFile("snt_inspect_", ".swc");
		swc.deleteOnExit();
		assertTrue("Demo tree saved", demo.saveAsSWC(swc.getAbsolutePath()));
		final Map<String, Object> inputs = new HashMap<>();
		inputs.put("reconstruction", swc);
		inputs.put("maxPaths", 5);
		inputs.put("output", "Console");
		final String out = run("Inspect_Session.groovy", inputs);
		assertTrue(out, out.contains("\"loaded_file\""));
		assertTrue(out, out.contains("\"snt_active\": true"));
		assertTrue(out, out.contains("\"cable_length\""));
	}

}
