package nortantis.editor;

import nortantis.PathOperations;
import nortantis.geom.Point;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;

public class River
{
	/**
	 * Path nodes in resolution-invariant coordinates. Each node carries the width level and noisy-edges seed for the segment
	 * <em>leaving</em> it; the last node's "to-next" fields are unused. Per-segment storage means adding a new control point does not
	 * regenerate the jaggedness of unrelated segments.
	 *
	 * <p>
	 * The field is reassigned for multi-step modifications (instead of clear+addAll on the existing list) so concurrent readers on the
	 * background draw thread always see a fully consistent path. Marked {@code volatile} so the reference swap is visible across threads.
	 */
	public volatile CopyOnWriteArrayList<RiverPathNode> nodes;

	public River(List<RiverPathNode> nodes)
	{
		this.nodes = new CopyOnWriteArrayList<>(PathOperations.deduplicateConsecutive(nodes));
	}

	public River(River other)
	{
		this(other.nodes);
	}

	public static River withUniformWidth(List<Point> locations, int widthLevel, Random seedSource)
	{
		List<RiverPathNode> nodes = new ArrayList<>(locations.size());
		for (int i = 0; i < locations.size(); i++)
		{
			boolean isLast = i == locations.size() - 1;
			int w = isLast ? 0 : widthLevel;
			long s = isLast ? 0L : seedSource.nextLong();
			nodes.add(new RiverPathNode(locations.get(i), w, s));
		}
		return new River(nodes);
	}

	/**
	 * Builds a river from a list of locations with matching per-segment width levels. The width at index {@code i} applies to the segment
	 * from {@code locations[i]} to {@code locations[i+1]}. Each segment gets a freshly generated seed.
	 */
	public static River fromLocationsAndWidths(List<Point> locations, List<Integer> widthLevels)
	{
		Random random = new Random();
		List<RiverPathNode> nodes = new ArrayList<>(locations.size());
		for (int i = 0; i < locations.size(); i++)
		{
			boolean isLast = i == locations.size() - 1;
			int w = isLast ? 0 : (i < widthLevels.size() ? widthLevels.get(i) : 0);
			long s = isLast ? 0L : random.nextLong();
			nodes.add(new RiverPathNode(locations.get(i), w, s));
		}
		return new River(nodes);
	}

	@Override
	public int hashCode()
	{
		return Objects.hash(nodes);
	}

	@Override
	public boolean equals(Object obj)
	{
		if (this == obj)
		{
			return true;
		}
		if (obj == null || getClass() != obj.getClass())
		{
			return false;
		}
		River other = (River) obj;
		return Objects.equals(nodes, other.nodes);
	}

	@Override
	public String toString()
	{
		return "River [nodes=" + nodes + "]";
	}
}
