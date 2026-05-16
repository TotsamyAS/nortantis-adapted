package nortantis.editor;

import nortantis.PathOperations;
import nortantis.geom.Point;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

public class Road
{
	/**
	 * Path nodes in resolution-invariant coordinates. The field is reassigned for multi-step modifications (instead of clear+addAll on the
	 * existing list) so concurrent readers on the background draw thread always see a fully consistent path. Marked {@code volatile} so the
	 * reference swap is visible across threads.
	 */
	public volatile CopyOnWriteArrayList<RoadPathNode> nodes;

	public Road(List<RoadPathNode> nodes)
	{
		this.nodes = new CopyOnWriteArrayList<>(PathOperations.deduplicateConsecutive(nodes));
	}

	public Road(Road other)
	{
		this(other.nodes);
	}

	/** Convenience constructor for callers that have only locations. */
	public static Road fromLocations(List<Point> locations)
	{
		List<RoadPathNode> built = new ArrayList<>(locations.size());
		for (Point p : locations)
		{
			built.add(new RoadPathNode(p));
		}
		return new Road(built);
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
		Road other = (Road) obj;
		return Objects.equals(nodes, other.nodes);
	}

	@Override
	public String toString()
	{
		return "Road [nodes=" + nodes + "]";
	}
}
