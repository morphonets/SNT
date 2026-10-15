#@ String (label="Mode", choices={"search", "class", "classes", "packages"}, value="search", description="search: keyword in class/member names\nclass: public API of one class\nclasses: classes of a package\n packages: list packages") mode
#@ String (label="Query", value="Tree", description="Keyword, class name (simple or fully qualified)\nor package name, depending on mode") query
#@ boolean (label="Include deprecated API", value=false) includeDeprecated
#@ String (label="Output", choices={"Console", "JSON", "Text window"}, description="Agents: use Console or JSON") output
#@ UIService ui

"""
file:	API_Lookup.groovy
info:   Reflection-based lookup of the public SNT API (sc.fiji.snt.*) as
        installed in THIS Fiji. Ground truth for class/method names: use it
        instead of guessing. Works headlessly. Examples:
        fiji --headless --run API_Lookup.groovy 'mode="search",query="skeleton"'
        fiji --headless --run API_Lookup.groovy 'mode="class",query="TreeStatistics"'
        fiji --headless --run API_Lookup.groovy 'mode="classes",query="sc.fiji.snt.analysis"'
        fiji --headless --run API_Lookup.groovy 'mode="packages",query="",output="JSON"'
        Agents: use output="Console" (default) or output="JSON". "Text window" opens a
        window on the user's screen (ignored when headless)
"""

import groovy.json.JsonOutput
import java.lang.reflect.Modifier
import java.util.jar.JarFile
import sc.fiji.snt.SNTUtils

/** Locate the SNT binaries (jar when installed, directory when run from an IDE) */
def List<String> findClassNames() {
	def loc = SNTUtils.class.getProtectionDomain().getCodeSource()?.location
	if (loc == null) throw new IllegalStateException("Cannot locate SNT binaries")
	File f = new File(loc.toURI())
	List<String> names = []
	if (f.isFile()) {
		new JarFile(f).withCloseable { jar ->
			jar.entries().each { e ->
				String n = e.getName()
				if (n.startsWith("sc/fiji/snt/") && n.endsWith(".class"))
					names << n.substring(0, n.length() - 6).replace('/', '.')
			}
		}
	} else {
		String base = f.absolutePath + File.separator
		f.eachFileRecurse { File c ->
			if (c.getName().endsWith(".class")) {
				String rel = c.absolutePath.substring(base.length())
				names << rel.substring(0, rel.length() - 6).replace(File.separator, '.')
			}
		}
	}
	// Top-level classes only. Nested ones are reported under their parent
	return names.findAll { !it.contains('$') && !it.endsWith("module-info") && !it.endsWith("package-info") }.sort()
}

def Class load(String name) {
	try {
		return Class.forName(name, false, SNTUtils.class.getClassLoader())
	} catch (Throwable ignored) { // optional dependencies may be absent
		return null
	}
}


def boolean isDeprecated(def o) {
	return o.isAnnotationPresent(Deprecated)
}

def String kind(Class c) {
	if (c.isAnnotation()) return "annotation"
	if (c.isInterface()) return "interface"
	if (c.isEnum()) return "enum"
	if (c.isRecord()) return "record"
	return Modifier.isAbstract(c.getModifiers()) ? "abstract class" : "class"
}

def String sig(def m) {
	String params = m.getParameters().collect { p ->
		p.isNamePresent() ? "${p.getType().getSimpleName()} ${p.getName()}" : p.getType().getSimpleName()
	}.join(", ")
	return m instanceof java.lang.reflect.Constructor ?
		"${m.getDeclaringClass().getSimpleName()}(${params})" :
		"${m.getReturnType().getSimpleName()} ${m.getName()}(${params})"
}

def Map describe(Class c, boolean withDeprecated) {
	def keep = { m -> Modifier.isPublic(m.getModifiers()) && !m.isSynthetic() && (withDeprecated || !isDeprecated(m)) }
	def methods = c.getDeclaredMethods().findAll(keep).sort { it.getName() }.collect {
		[sig: sig(it), static: Modifier.isStatic(it.getModifiers()), deprecated: isDeprecated(it)]
	}
	def ctors = c.getDeclaredConstructors().findAll(keep).collect { [sig: sig(it)] }
	def fields = c.getDeclaredFields().findAll(keep).collect {
		[name: it.getName(), type: it.getType().getSimpleName(), static: Modifier.isStatic(it.getModifiers()), deprecated: isDeprecated(it)]
	}
	def nested = c.getDeclaredClasses().findAll { Modifier.isPublic(it.getModifiers()) }.collect { it.getSimpleName() }
	return [
		"class"      : c.getName(),
		kind         : kind(c),
		deprecated   : isDeprecated(c),
		extends      : c.getSuperclass() && c.getSuperclass() != Object ? c.getSuperclass().getName() : null,
		implements   : c.getInterfaces().collect { it.getName() },
		javadoc      : "https://morphonets.github.io/SNT/" + c.getName().replace('.', '/') + ".html",
		constructors : ctors,
		methods      : methods,
		fields       : fields,
		nestedClasses: nested
	]
}

/** Fallback edit distance, only used to suggest names when a lookup fails */
def int distance(String a, String b) {
	int[] prev = (0..b.length()) as int[]
	for (int i = 1; i <= a.length(); i++) {
		int[] cur = new int[b.length() + 1]
		cur[0] = i
		for (int j = 1; j <= b.length(); j++) {
			cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1),
					prev[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1))
		}
		prev = cur
	}
	return prev[b.length()]
}

def List<String> suggest(String q, List<String> names) {
	String lq = q.toLowerCase()
	return names.sort { distance(lq, it.substring(it.lastIndexOf('.') + 1).toLowerCase()) }.take(5)
}

def String pkgOf(String fqn) { return fqn.substring(0, fqn.lastIndexOf('.')) }

/** Looks up the SNT API and returns the report as text */
def String main(String mode, String query, boolean includeDeprecated, boolean json) {
	final String ROOT = "sc.fiji.snt"
	final int MAX_HITS = 60
	List<String> all = findClassNames()
	Map out = [snt_version: SNTUtils.VERSION, mode: mode, query: query]
	String q = (query ?: "").trim()

	// Case-insensitive lookups: exact match first, then ignoring case
	def ci = { List<String> names, String key, Closure<String> toKey ->
		names.find { toKey(it) == key } ?: names.find { toKey(it).equalsIgnoreCase(key) }
	}

	switch ((mode ?: "").trim().toLowerCase()) {
		case "packages":
			out.packages = all.groupBy { pkgOf(it) }.collectEntries { k, v -> [(k): v.size()] }.sort()
			break

		case "classes":
			String pkg = q.isEmpty() ? ROOT : q
			def inPkg = all.findAll { pkgOf(it).equalsIgnoreCase(pkg) }
			if (inPkg.isEmpty())
				out.error = "No classes in '${pkg}'. Known packages: " + all.collect { pkgOf(it) }.unique().join(", ")
			else
				out.classes = inPkg.collect { load(it) }.findAll { it && Modifier.isPublic(it.getModifiers()) && (includeDeprecated || !isDeprecated(it)) }
						.collect { [name: it.getSimpleName(), kind: kind(it), deprecated: isDeprecated(it)] }
			break

		case "class":
			String fqn = q.contains('.') ? ci(all, q) { it } : ci(all, q) { it.substring(it.lastIndexOf('.') + 1) }
			Class c = fqn ? load(fqn) : null
			if (c == null) {
				out.error = "Class '${q}' not found in ${ROOT}.*. It may be renamed/moved (see SKILL.md)"
				out.did_you_mean = suggest(q, all)
			} else {
				out.putAll(describe(c, includeDeprecated))
			}
			break

		case "search":
			String lq = q.toLowerCase()
			def hits = []
			all.each { n ->
				Class c = load(n)
				if (c == null || !Modifier.isPublic(c.getModifiers()) || (!includeDeprecated && isDeprecated(c))) return
				if (n.substring(n.lastIndexOf('.') + 1).toLowerCase().contains(lq))
					hits << [type: "class", name: n, kind: kind(c)]
				try {
					c.getDeclaredMethods().findAll { Modifier.isPublic(it.getModifiers()) && !it.isSynthetic() && (includeDeprecated || !isDeprecated(it)) && it.getName().toLowerCase().contains(lq) }
							.each { hits << [type: "method", name: "${n}#${sig(it)}"] }
				} catch (Throwable ignored) { /* unresolvable signature types */ }
			}
			out.total_hits = hits.size()
			out.hits = hits.take(MAX_HITS)
			if (hits.size() > MAX_HITS) out.note = "Truncated to ${MAX_HITS} hits. Refine the query or use mode=class"
			if (hits.isEmpty()) out.did_you_mean = suggest(q, all)
			break

		default:
			out.error = "Unknown mode '${mode}'. Use: search, class, classes or packages"
	}

	String text
	if (json) {
		text = JsonOutput.prettyPrint(JsonOutput.toJson(out))
	} else {
		StringBuilder sb = new StringBuilder()
		out.each { k, v ->
			if (v instanceof Map) {
				sb << "${k}:\n"
				v.each { kk, vv -> sb << "  ${kk}: ${vv}\n" }
			} else if (v instanceof Collection) {
				sb << "${k}:\n"
				v.each { sb << "  " + (it instanceof Map ? it.values().join("  ") : it) + "\n" }
			} else if (v != null) {
				sb << "${k}: ${v}\n"
			}
		}
		text = sb.toString()
	}

	return text
}


String text = main(mode, query, includeDeprecated, output?.trim()?.equalsIgnoreCase("JSON") ?: false)
// Anything other than a window (or no GUI) falls back to the console
if (output?.trim()?.equalsIgnoreCase("Text window") && !java.awt.GraphicsEnvironment.isHeadless() && ui != null)
	ui.show("API Lookup: '${query}'", text)
else
	println text
return null // suppress 'ignoring unsupported output' warning
