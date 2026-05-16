package nortantis;

import nortantis.editor.PathNode;
import nortantis.geom.Point;
import nortantis.geom.Rectangle;
import nortantis.util.OrderlessPair;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Generic helpers shared by road and river path handling. All methods operate on {@code List<? extends PathNode>} — only
 * {@link PathNode#getLoc()} is required from the node type, so anything that has to preserve per-segment metadata (river widths/seeds
 * during reverse or cross-endpoint merge) lives in {@link NodeMetadataOps} and is passed in by the caller.
 */
public final class PathOperations
{
	private PathOperations()
	{
	}

	/**
	 * Strategy for preserving per-segment "to-next" metadata when the path is reversed or two paths are stitched together at a shared
	 * endpoint.
	 *
	 * <p>
	 * Road nodes have no per-segment metadata, so {@link RoadDrawer#ROAD_OPS} returns the target unchanged. River nodes carry width and
	 * seed for the segment leaving them, so {@link RiverDrawer#RIVER_OPS} produces new nodes that move that metadata to the right place
	 * when the path direction or shape changes.
	 */
	public interface NodeMetadataOps<T extends PathNode>
	{
		/** Returns a node at {@code original}'s location with cleared "to-next" metadata (last-node convention). */
		T withClearedMetadata(T original);

		/** Returns a node at {@code target}'s location with "to-next" metadata copied from {@code donor}. */
		T withMetadataFrom(T target, T donor);
	}

	/**
	 * Reverses {@code path} while shifting each node's "to-next" metadata so it still describes the correct segment in the new direction.
	 * The last node of the result has cleared metadata.
	 */
	public static <T extends PathNode> List<T> reverseWithMetadata(List<T> path, NodeMetadataOps<T> ops)
	{
		int n = path.size();
		List<T> result = new ArrayList<>(n);
		for (int i = 0; i < n; i++)
		{
			T origNode = path.get(n - 1 - i);
			if (i < n - 1)
			{
				// The segment leaving the reversed-position-i node corresponds to the original
				// segment between old positions n-1-i and n-2-i. Its "to-next" metadata was stored
				// on the lower-index node in the original, which is at old position n-2-i.
				T donor = path.get(n - 2 - i);
				result.add(ops.withMetadataFrom(origNode, donor));
			}
			else
			{
				result.add(ops.withClearedMetadata(origNode));
			}
		}
		return result;
	}

	/**
	 * Splits {@code path} at any node whose location is in {@code splitLocs}, dropping segments where both endpoints are split locations
	 * and keeping every other adjacent segment intact. Returned sub-paths each contain at least 2 nodes.
	 *
	 * <p>
	 * Mirrors the existing road/river erase semantics: a split point ends one sub-path and starts the next, so the segments touching a
	 * single split point are preserved on both sides; only a segment whose <em>both</em> endpoints are in {@code splitLocs} is actually
	 * removed.
	 */
	public static <T extends PathNode> List<List<T>> splitAtLocations(List<T> path, Set<Point> splitLocs)
	{
		List<List<T>> result = new ArrayList<>();
		if (path.size() < 2)
		{
			return result;
		}

		List<T> current = new ArrayList<>();
		for (int i = 0; i < path.size(); i++)
		{
			T node = path.get(i);
			current.add(node);
			if (splitLocs.contains(node.getLoc()))
			{
				if (current.size() > 1)
				{
					result.add(current);
				}
				current = new ArrayList<>();
				// Only start the next sub-path at this node if the segment leaving it is kept
				// (i.e. the next node is not also a split point — otherwise that whole segment goes).
				if (i + 1 < path.size() && !splitLocs.contains(path.get(i + 1).getLoc()))
				{
					current.add(node);
				}
			}
		}
		if (current.size() > 1)
		{
			result.add(current);
		}
		return result;
	}

	/** Aggregates orderless pairs of consecutive node locations across a collection of paths. */
	public static Set<OrderlessPair<Point>> collectAllConnections(Iterable<? extends List<? extends PathNode>> paths)
	{
		Set<OrderlessPair<Point>> result = new HashSet<>();
		for (List<? extends PathNode> path : paths)
		{
			for (int i = 0; i < path.size() - 1; i++)
			{
				result.add(new OrderlessPair<>(path.get(i).getLoc(), path.get(i + 1).getLoc()));
			}
		}
		return result;
	}

	/** Indexed accessor over a collection of existing paths, used by {@link #tryConnectToExistingPath}. */
	public interface ExistingPathAccessor<T extends PathNode>
	{
		int count();

		List<T> get();
	}

	/**
	 * Try to merge {@code pathToAdd} into one of the paths reachable through {@code existing} by matching one of its endpoint locations to
	 * an existing endpoint. Returns the merged node list if a match was found, or {@code null} if no endpoint match exists.
	 *
	 * <p>
	 * The matched node from {@code pathToAdd} is dropped; if its segment metadata needs to be preserved across the join (the "append" /
	 * "reverse-and-append" cases), {@code ops} is used to transfer the "to-next" metadata to the surviving node so the resulting path is
	 * width/seed-consistent.
	 *
	 * @param existing
	 *            Accessor that exposes the current snapshot of each existing path. {@code null} or empty entries are skipped.
	 */
	public static <T extends PathNode> Match<T> tryConnectToExistingPath(List<T> pathToAdd, ExistingPathAccessor<T> existing, NodeMetadataOps<T> ops)
	{
		if (pathToAdd == null || pathToAdd.size() < 2)
		{
			return null;
		}
		for (int i = 0; i < existing.count(); i++)
		{
			List<T> other = existing.get();
			if (other == null || other.isEmpty() || other == pathToAdd)
			{
				continue;
			}

			Point otherStart = other.get(0).getLoc();
			Point otherEnd = other.get(other.size() - 1).getLoc();
			Point addStart = pathToAdd.get(0).getLoc();
			Point addEnd = pathToAdd.get(pathToAdd.size() - 1).getLoc();

			if (otherStart.isCloseEnough(addStart))
			{
				List<T> merged = mergeReverseAndPrepend(other, pathToAdd, ops);
				return new Match<>(merged);
			}
			if (otherStart.isCloseEnough(addEnd))
			{
				List<T> merged = mergePrepend(other, pathToAdd);
				return new Match<>(merged);
			}
			if (otherEnd.isCloseEnough(addStart))
			{
				List<T> merged = mergeAppend(other, pathToAdd, ops);
				return new Match<>(merged);
			}
			if (otherEnd.isCloseEnough(addEnd))
			{
				List<T> merged = mergeReverseAndAppend(other, pathToAdd, ops);
				return new Match<>(merged);
			}
		}
		return null;
	}

	/** Result of {@link #tryConnectToExistingPath}: index of the matched existing path and the merged node list. */
	public static final class Match<T extends PathNode>
	{
		public final List<T> mergedNodes;

		public Match(List<T> mergedNodes)
		{
			this.mergedNodes = mergedNodes;
		}
	}

	// existingStart == addStart: reverse pathToAdd, drop its now-last (matching) node, prepend to existing.
	private static <T extends PathNode> List<T> mergeReverseAndPrepend(List<T> existing, List<T> pathToAdd, NodeMetadataOps<T> ops)
	{
		List<T> reversed = reverseWithMetadata(pathToAdd, ops);
		List<T> dropped = reversed.subList(0, reversed.size() - 1);
		List<T> merged = new ArrayList<>(dropped.size() + existing.size());
		merged.addAll(dropped);
		merged.addAll(existing);
		return merged;
	}

	// existingStart == addEnd: drop pathToAdd's last (matching) node and prepend.
	private static <T extends PathNode> List<T> mergePrepend(List<T> existing, List<T> pathToAdd)
	{
		List<T> dropped = pathToAdd.subList(0, pathToAdd.size() - 1);
		List<T> merged = new ArrayList<>(dropped.size() + existing.size());
		merged.addAll(dropped);
		merged.addAll(existing);
		return merged;
	}

	// existingEnd == addStart: drop pathToAdd's first (matching) node, transferring its to-next
	// metadata onto existing's last node so the new join segment carries the correct width/seed.
	private static <T extends PathNode> List<T> mergeAppend(List<T> existing, List<T> pathToAdd, NodeMetadataOps<T> ops)
	{
		List<T> result = new ArrayList<>(existing.size() + pathToAdd.size() - 1);
		result.addAll(existing.subList(0, existing.size() - 1));
		result.add(ops.withMetadataFrom(existing.get(existing.size() - 1), pathToAdd.get(0)));
		result.addAll(pathToAdd.subList(1, pathToAdd.size()));
		return result;
	}

	// existingEnd == addEnd: reverse pathToAdd, drop its now-first (matching) node with metadata
	// transfer to existing's last node.
	private static <T extends PathNode> List<T> mergeReverseAndAppend(List<T> existing, List<T> pathToAdd, NodeMetadataOps<T> ops)
	{
		List<T> reversed = reverseWithMetadata(pathToAdd, ops);
		List<T> result = new ArrayList<>(existing.size() + reversed.size() - 1);
		result.addAll(existing.subList(0, existing.size() - 1));
		result.add(ops.withMetadataFrom(existing.get(existing.size() - 1), reversed.get(0)));
		result.addAll(reversed.subList(1, reversed.size()));
		return result;
	}

	/**
	 * Returns true if any segment of {@code path} (or its expanded jagged envelope) overlaps the given resolution-invariant bounds. Used by
	 * both road and river drawing to skip paths that cannot contribute to the current draw region.
	 *
	 * @param expansionRI
	 *            Inflates the bounds by this much in each direction before testing. Use 0 for an exact bbox check; rivers use
	 *            {@code jaggedAmplitudeRI} to account for the bulge.
	 */
	public static boolean pathOverlapsRectangle(List<? extends PathNode> path, Rectangle boundsRI, double expansionRI)
	{
		Rectangle expanded = expansionRI == 0 ? boundsRI : new Rectangle(boundsRI.x - expansionRI, boundsRI.y - expansionRI, boundsRI.width + 2 * expansionRI, boundsRI.height + 2 * expansionRI);
		for (int i = 0; i < path.size() - 1; i++)
		{
			Point p1 = path.get(i).getLoc();
			Point p2 = path.get(i + 1).getLoc();
			Rectangle segBounds = Rectangle.fromCorners(Math.min(p1.x, p2.x), Math.min(p1.y, p2.y), Math.max(p1.x, p2.x), Math.max(p1.y, p2.y));
			if (expanded.overlaps(segBounds))
			{
				return true;
			}
		}
		return false;
	}

	/** Returns a new list containing each node's location, in order. */
	public static List<Point> toLocationList(List<? extends PathNode> path)
	{
		List<Point> result = new ArrayList<>(path.size());
		for (PathNode node : path)
		{
			result.add(node.getLoc());
		}
		return result;
	}

	/** Deduplicates consecutive nodes whose locations are {@link Point#isCloseEnough}. */
	public static <T extends PathNode> List<T> deduplicateConsecutive(List<T> path)
	{
		if (path.isEmpty())
		{
			return Collections.emptyList();
		}
		List<T> result = new ArrayList<>(path.size());
		for (T node : path)
		{
			if (result.isEmpty() || !result.get(result.size() - 1).getLoc().isCloseEnough(node.getLoc()))
			{
				result.add(node);
			}
		}
		return result;
	}
}
