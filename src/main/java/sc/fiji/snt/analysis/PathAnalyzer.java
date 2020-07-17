package sc.fiji.snt.analysis;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import sc.fiji.snt.Path;

/*
 * A flavor of TreeAnalyzer that does not do graph conversions, ensuring paths
 * can me measured independently of their connectivity.
 */
public class PathAnalyzer extends TreeAnalyzer {

	public PathAnalyzer(Collection<Path> paths, String label) {
		super(paths, label);
	}

	@Override
	public List<Path> getBranches() {
		return tree.list();
	}

	@Override
	public int getNBranches() {
		return tree.size();
	}

	@Override
	public List<Path> getPrimaryBranches() {
		final ArrayList<Path> paths = new ArrayList<>();
		for (final Path path : tree.list()) {
			if (path.isPrimary()) paths.add(path);
		}
		return paths;
	}

	@Override
	public double getPrimaryLength() {
		return getPrimaryBranches().stream().mapToDouble(p -> p.getLength()).sum();
	}

	@Override
	public List<Path> getInnerBranches() {
		return getPrimaryBranches();
	}

	@Override
	public double getInnerLength() {
		return getPrimaryLength();
	}

}