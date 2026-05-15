package nortantis.editor;

import nortantis.geom.Point;

import java.util.Objects;

/**
 * Immutable path node for roads. Roads have no per-segment metadata, just locations.
 */
public final class RoadPathNode implements PathNode
{
	private final Point loc;

	public RoadPathNode(Point loc)
	{
		this.loc = loc;
	}

	@Override
	public Point getLoc()
	{
		return loc;
	}

	@Override
	public int hashCode()
	{
		return Objects.hash(loc);
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
		return Objects.equals(loc, ((RoadPathNode) obj).loc);
	}

	@Override
	public String toString()
	{
		return "RoadPathNode[" + loc + "]";
	}
}
