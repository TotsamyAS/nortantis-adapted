package nortantis.editor;

import nortantis.geom.Point;

import java.util.Objects;

/**
 * Immutable path node for rivers. The width level and noisy-edges seed stored on each node apply to the segment going from this node to the
 * next one in the river's path. On the last node of a path these "to-next" fields are unused.
 *
 * <p>
 * Storing seed and width per-segment (instead of one seed per river) means adding or removing a control point does not regenerate the
 * jaggedness of unrelated segments, and lets a single atomic swap of the node list update both the path and per-segment data together — no
 * race window between two parallel lists.
 *
 * <p>
 * {@link #getEdgeIndexToNext()} optionally records the {@link nortantis.graph.voronoi.Edge#index Voronoi edge index} that the segment from
 * this node lies on. Polygon-mode draws set it because every segment follows exactly one Voronoi edge; freehand draws leave it as
 * {@link #EDGE_INDEX_NONE}. Drawing and region-boundary code use it for an exact lookup ("is this graph edge a river segment?") instead of
 * matching control points to corner locations by distance.
 */
public final class RiverPathNode implements PathNode
{
	/** Sentinel for "this segment does not follow a Voronoi edge" (e.g. freehand-drawn rivers). */
	public static final int EDGE_INDEX_NONE = -1;

	private final Point loc;
	private final int widthLevelToNext;
	private final long seedToNext;
	private final int edgeIndexToNext;

	public RiverPathNode(Point loc, int widthLevelToNext, long seedToNext)
	{
		this(loc, widthLevelToNext, seedToNext, EDGE_INDEX_NONE);
	}

	public RiverPathNode(Point loc, int widthLevelToNext, long seedToNext, int edgeIndexToNext)
	{
		this.loc = loc;
		this.widthLevelToNext = widthLevelToNext;
		this.seedToNext = seedToNext;
		this.edgeIndexToNext = edgeIndexToNext;
	}

	@Override
	public Point getLoc()
	{
		return loc;
	}

	/** Width level for the segment from this node to the next. Unused on the last node of a path. */
	public int getWidthLevelToNext()
	{
		return widthLevelToNext;
	}

	/** Random seed for jagged-style rendering of the segment from this node to the next. Unused on the last node. */
	public long getSeedToNext()
	{
		return seedToNext;
	}

	/**
	 * Voronoi edge index for the segment from this node to the next, or {@link #EDGE_INDEX_NONE} if the segment does not follow a Voronoi
	 * edge. Unused on the last node of a path.
	 */
	public int getEdgeIndexToNext()
	{
		return edgeIndexToNext;
	}

	@Override
	public int hashCode()
	{
		return Objects.hash(loc, widthLevelToNext, seedToNext, edgeIndexToNext);
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
		RiverPathNode other = (RiverPathNode) obj;
		return widthLevelToNext == other.widthLevelToNext && seedToNext == other.seedToNext && edgeIndexToNext == other.edgeIndexToNext && Objects.equals(loc, other.loc);
	}

	@Override
	public String toString()
	{
		return "RiverPathNode[loc=" + loc + ", widthToNext=" + widthLevelToNext + ", seedToNext=" + seedToNext + ", edgeIndexToNext=" + edgeIndexToNext + "]";
	}
}
