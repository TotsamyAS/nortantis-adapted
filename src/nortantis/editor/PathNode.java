package nortantis.editor;

import nortantis.geom.Point;

/**
 * A single control point in a road or river path. Implementations are immutable and may carry per-segment metadata (e.g. width and seed for
 * the segment going from this node to the next one). The "to-next" data on the last node of a path is unused; callers must skip it.
 */
public interface PathNode
{
	Point getLoc();
}
