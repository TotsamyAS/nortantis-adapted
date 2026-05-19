package nortantis;

import nortantis.editor.RoadPathNode;
import nortantis.geom.Point;
import nortantis.geom.Rectangle;
import nortantis.util.OrderlessPair;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PathOperationsTest
{
	private static List<RoadPathNode> road(Point... locations)
	{
		List<RoadPathNode> result = new ArrayList<>(locations.length);
		for (Point loc : locations)
		{
			result.add(new RoadPathNode(loc));
		}
		return result;
	}

	@Test
	public void toLocationList_returnsLocationsInOrder()
	{
		Point p1 = new Point(1, 2);
		Point p2 = new Point(3, 4);
		Point p3 = new Point(5, 6);
		List<Point> result = PathOperations.toLocationList(road(p1, p2, p3));
		assertEquals(Arrays.asList(p1, p2, p3), result);
	}

	@Test
	public void toLocationList_empty()
	{
		assertEquals(Collections.emptyList(), PathOperations.toLocationList(Collections.emptyList()));
	}

	@Test
	public void deduplicateConsecutive_removesAdjacentDuplicates()
	{
		Point a = new Point(0, 0);
		Point b = new Point(1, 0);
		Point c = new Point(2, 0);
		List<RoadPathNode> path = road(a, a, b, b, b, c);
		List<RoadPathNode> result = PathOperations.deduplicateConsecutive(path);
		assertEquals(Arrays.asList(a, b, c), PathOperations.toLocationList(result));
	}

	@Test
	public void deduplicateConsecutive_preservesSeparatedDuplicates()
	{
		Point a = new Point(0, 0);
		Point b = new Point(1, 0);
		List<RoadPathNode> path = road(a, b, a, b);
		List<RoadPathNode> result = PathOperations.deduplicateConsecutive(path);
		// No two consecutive duplicates, so list is unchanged.
		assertEquals(Arrays.asList(a, b, a, b), PathOperations.toLocationList(result));
	}

	@Test
	public void deduplicateConsecutive_empty()
	{
		assertEquals(Collections.emptyList(), PathOperations.deduplicateConsecutive(Collections.<RoadPathNode>emptyList()));
	}

	@Test
	public void pathOverlapsRectangle_segmentInsideBounds()
	{
		List<RoadPathNode> path = road(new Point(5, 5), new Point(15, 15));
		Rectangle bounds = new Rectangle(0, 0, 20, 20);
		assertTrue(PathOperations.pathOverlapsRectangle(path, bounds, 0));
	}

	@Test
	public void pathOverlapsRectangle_segmentOutsideBounds()
	{
		List<RoadPathNode> path = road(new Point(30, 30), new Point(40, 40));
		Rectangle bounds = new Rectangle(0, 0, 20, 20);
		assertFalse(PathOperations.pathOverlapsRectangle(path, bounds, 0));
	}

	@Test
	public void pathOverlapsRectangle_expansionIncludesSegment()
	{
		List<RoadPathNode> path = road(new Point(22, 22), new Point(25, 25));
		Rectangle bounds = new Rectangle(0, 0, 20, 20);
		// Without expansion, outside.
		assertFalse(PathOperations.pathOverlapsRectangle(path, bounds, 0));
		// With expansion of 5, the bounds reach (25, 25) so the segment overlaps.
		assertTrue(PathOperations.pathOverlapsRectangle(path, bounds, 5));
	}

	@Test
	public void splitAtLocations_noSplitPoints()
	{
		Point a = new Point(0, 0);
		Point b = new Point(1, 0);
		Point c = new Point(2, 0);
		List<List<RoadPathNode>> result = PathOperations.splitAtLocations(road(a, b, c), new HashSet<>());
		assertEquals(1, result.size());
		assertEquals(Arrays.asList(a, b, c), PathOperations.toLocationList(result.get(0)));
	}

	@Test
	public void splitAtLocations_middleSplit()
	{
		Point a = new Point(0, 0);
		Point b = new Point(1, 0);
		Point c = new Point(2, 0);
		Point d = new Point(3, 0);
		Set<Point> splits = new HashSet<>();
		splits.add(c);
		List<List<RoadPathNode>> result = PathOperations.splitAtLocations(road(a, b, c, d), splits);
		// Split point c terminates the first sub-path AND starts the second (segments touching c are preserved).
		assertEquals(2, result.size());
		assertEquals(Arrays.asList(a, b, c), PathOperations.toLocationList(result.get(0)));
		assertEquals(Arrays.asList(c, d), PathOperations.toLocationList(result.get(1)));
	}

	@Test
	public void splitAtLocations_adjacentSplitPointsDropSegmentBetween()
	{
		Point a = new Point(0, 0);
		Point b = new Point(1, 0);
		Point c = new Point(2, 0);
		Point d = new Point(3, 0);
		Set<Point> splits = new HashSet<>(Arrays.asList(b, c));
		List<List<RoadPathNode>> result = PathOperations.splitAtLocations(road(a, b, c, d), splits);
		// b→c is removed (both endpoints are split points). a→b and c→d are preserved.
		assertEquals(2, result.size());
		assertEquals(Arrays.asList(a, b), PathOperations.toLocationList(result.get(0)));
		assertEquals(Arrays.asList(c, d), PathOperations.toLocationList(result.get(1)));
	}

	@Test
	public void splitAtLocations_singleNodePathReturnsEmpty()
	{
		List<List<RoadPathNode>> result = PathOperations.splitAtLocations(road(new Point(0, 0)), new HashSet<>());
		assertEquals(0, result.size());
	}

	@Test
	public void collectAllConnections_collectsOrderlessPairs()
	{
		Point a = new Point(0, 0);
		Point b = new Point(1, 0);
		Point c = new Point(2, 0);
		Set<OrderlessPair<Point>> result = PathOperations.collectAllConnections(Arrays.asList(road(a, b, c)));
		assertEquals(2, result.size());
		assertTrue(result.contains(new OrderlessPair<>(a, b)));
		assertTrue(result.contains(new OrderlessPair<>(b, c)));
	}

	@Test
	public void collectAllConnections_treatsReverseAsSame()
	{
		Point a = new Point(0, 0);
		Point b = new Point(1, 0);
		Set<OrderlessPair<Point>> result = PathOperations.collectAllConnections(Arrays.asList(road(a, b), road(b, a)));
		// OrderlessPair means {a,b} == {b,a}, so deduplicated to one entry.
		assertEquals(1, result.size());
	}

	@Test
	public void reverseWithMetadata_roadReversesLocations()
	{
		Point a = new Point(0, 0);
		Point b = new Point(1, 0);
		Point c = new Point(2, 0);
		List<RoadPathNode> reversed = PathOperations.reverseWithMetadata(road(a, b, c), RoadDrawer.ROAD_OPS);
		assertEquals(Arrays.asList(c, b, a), PathOperations.toLocationList(reversed));
	}

	@Test
	public void tryConnectToExistingPath_endpointMatchAppends()
	{
		Point a = new Point(0, 0);
		Point b = new Point(1, 0);
		Point c = new Point(2, 0);
		Point d = new Point(3, 0);
		List<RoadPathNode> existing = road(a, b, c);
		List<RoadPathNode> toAdd = road(c, d);
		PathOperations.Match<RoadPathNode> match = PathOperations.tryConnectToExistingPath(toAdd, singlePathAccessor(existing), RoadDrawer.ROAD_OPS);
		assertNotNull(match);
		assertEquals(Arrays.asList(a, b, c, d), PathOperations.toLocationList(match.mergedNodes));
	}

	@Test
	public void tryConnectToExistingPath_endpointMatchPrepends()
	{
		Point a = new Point(0, 0);
		Point b = new Point(1, 0);
		Point c = new Point(2, 0);
		Point d = new Point(3, 0);
		List<RoadPathNode> existing = road(c, d);
		List<RoadPathNode> toAdd = road(a, b, c);
		PathOperations.Match<RoadPathNode> match = PathOperations.tryConnectToExistingPath(toAdd, singlePathAccessor(existing), RoadDrawer.ROAD_OPS);
		assertNotNull(match);
		assertEquals(Arrays.asList(a, b, c, d), PathOperations.toLocationList(match.mergedNodes));
	}

	@Test
	public void tryConnectToExistingPath_noMatchReturnsNull()
	{
		Point a = new Point(0, 0);
		Point b = new Point(1, 0);
		Point c = new Point(5, 5);
		Point d = new Point(6, 6);
		List<RoadPathNode> existing = road(a, b);
		List<RoadPathNode> toAdd = road(c, d);
		assertNull(PathOperations.tryConnectToExistingPath(toAdd, singlePathAccessor(existing), RoadDrawer.ROAD_OPS));
	}

	@Test
	public void tryConnectToExistingPath_shortPathReturnsNull()
	{
		Point a = new Point(0, 0);
		assertNull(PathOperations.tryConnectToExistingPath(road(a), singlePathAccessor(road(new Point(1, 1), new Point(2, 2))), RoadDrawer.ROAD_OPS));
	}

	private static <T extends nortantis.editor.PathNode> PathOperations.ExistingPathAccessor<T> singlePathAccessor(List<T> path)
	{
		return new PathOperations.ExistingPathAccessor<>()
		{
			@Override
			public int count()
			{
				return 1;
			}

			@Override
			public List<T> get()
			{
				return path;
			}
		};
	}

	@Test
	public void tryConnectToExistingPath_reverseAndAppendMatch()
	{
		Point a = new Point(0, 0);
		Point b = new Point(1, 0);
		Point c = new Point(2, 0);
		// existing: a-b-c, toAdd: d-c (end-end match → reverse-and-append).
		Point d = new Point(3, 0);
		List<RoadPathNode> existing = road(a, b, c);
		List<RoadPathNode> toAdd = road(d, c);
		PathOperations.Match<RoadPathNode> match = PathOperations.tryConnectToExistingPath(toAdd, singlePathAccessor(existing), RoadDrawer.ROAD_OPS);
		assertNotNull(match);
		assertEquals(Arrays.asList(a, b, c, d), PathOperations.toLocationList(match.mergedNodes));
	}

	@Test
	public void splitAtLocations_endpointsAreSplitPointsNoSubdivide()
	{
		Point a = new Point(0, 0);
		Point b = new Point(1, 0);
		Point c = new Point(2, 0);
		Set<Point> splits = new HashSet<>(Arrays.asList(a, c));
		List<List<RoadPathNode>> result = PathOperations.splitAtLocations(road(a, b, c), splits);
		// Endpoint splits don't actually split — no preceding or following segment exists to cut from.
		// Result is the original sub-path as a single piece.
		assertEquals(1, result.size());
		assertEquals(Arrays.asList(a, b, c), PathOperations.toLocationList(result.get(0)));
	}

	@Test
	public void deduplicateConsecutive_singleNode()
	{
		Point a = new Point(0, 0);
		List<RoadPathNode> result = PathOperations.deduplicateConsecutive(road(a));
		assertEquals(Arrays.asList(a), PathOperations.toLocationList(result));
	}

	@Test
	public void findInnerNeighborsOfCutEndpoints_middleCutFindsBothInnerNeighbors()
	{
		Point a = new Point(0, 0);
		Point b = new Point(1, 0);
		Point c = new Point(2, 0);
		Point d = new Point(3, 0);
		Point e = new Point(4, 0);
		// Simulate post-split state for [a,b,c,d,e] cut at c→d → [a,b,c] and [d,e].
		List<List<RoadPathNode>> paths = Arrays.asList(road(a, b, c), road(d, e));
		List<List<Point>> removed = Arrays.asList(Arrays.asList(c, d));
		Set<Point> result = new HashSet<>(PathOperations.findInnerNeighborsOfCutEndpoints(paths, removed));
		// Inner neighbor of c (new end of first sub-path) is b; inner neighbor of d (new end of second sub-path) is e.
		assertEquals(new HashSet<>(Arrays.asList(b, e)), result);
	}

	@Test
	public void findInnerNeighborsOfCutEndpoints_ignoresOriginalEndpoints()
	{
		Point a = new Point(0, 0);
		Point b = new Point(1, 0);
		Point c = new Point(2, 0);
		// Original [a,b,c] cut at a→b → [b,c]. Only b is a new endpoint matching a cut point;
		// the cut also names a (already an original endpoint), but a is no longer in the path.
		List<List<RoadPathNode>> paths = Arrays.asList(road(b, c));
		List<List<Point>> removed = Arrays.asList(Arrays.asList(a, b));
		Set<Point> result = new HashSet<>(PathOperations.findInnerNeighborsOfCutEndpoints(paths, removed));
		// b's inner neighbor is c. c is not a cut point, so its inner neighbor is not requested.
		assertEquals(new HashSet<>(Arrays.asList(c)), result);
	}

	@Test
	public void findInnerNeighborsOfCutEndpoints_handlesTwoNodeRemainder()
	{
		Point a = new Point(0, 0);
		Point b = new Point(1, 0);
		Point c = new Point(2, 0);
		// Post-cut state: [a,b]. b is the new endpoint from removing b→c.
		List<List<RoadPathNode>> paths = Arrays.asList(road(a, b));
		List<List<Point>> removed = Arrays.asList(Arrays.asList(b, c));
		Set<Point> result = new HashSet<>(PathOperations.findInnerNeighborsOfCutEndpoints(paths, removed));
		assertEquals(new HashSet<>(Arrays.asList(a)), result);
	}

	@Test
	public void findInnerNeighborsOfCutEndpoints_deduplicates()
	{
		Point a = new Point(0, 0);
		Point b = new Point(1, 0);
		Point c = new Point(2, 0);
		// Two paths both ending at b — should report a only once.
		List<List<RoadPathNode>> paths = Arrays.asList(road(a, b), road(a, b, c));
		List<List<Point>> removed = Arrays.asList(Arrays.asList(b, new Point(99, 99)));
		Set<Point> result = new HashSet<>(PathOperations.findInnerNeighborsOfCutEndpoints(paths, removed));
		// First path: b at end → inner is a. Second path: c at end (not in cut points); b at index 1 (not endpoint).
		assertEquals(new HashSet<>(Arrays.asList(a)), result);
	}

	@Test
	public void findInnerNeighborsOfCutEndpoints_skipsShortPaths()
	{
		Point a = new Point(0, 0);
		Point b = new Point(1, 0);
		List<List<RoadPathNode>> paths = Arrays.asList(Collections.<RoadPathNode> emptyList(), road(a));
		List<List<Point>> removed = Arrays.asList(Arrays.asList(a, b));
		// Empty and single-node paths must be ignored rather than throwing on out-of-bounds access.
		assertTrue(PathOperations.findInnerNeighborsOfCutEndpoints(paths, removed).isEmpty());
	}

	@Test
	public void findInnerNeighborsOfCutEndpoints_emptyInputsReturnEmpty()
	{
		Point a = new Point(0, 0);
		Point b = new Point(1, 0);
		List<List<RoadPathNode>> paths = Arrays.asList(road(a, b));
		List<List<Point>> removed = Arrays.asList(Arrays.asList(a, b));
		assertTrue(PathOperations.findInnerNeighborsOfCutEndpoints(null, removed).isEmpty());
		assertTrue(PathOperations.findInnerNeighborsOfCutEndpoints(paths, null).isEmpty());
		assertTrue(PathOperations.findInnerNeighborsOfCutEndpoints(paths, Collections.emptyList()).isEmpty());
	}

	@Test
	public void deduplicateConsecutive_returnsSameOnNoDuplicates()
	{
		Point a = new Point(0, 0);
		Point b = new Point(1, 0);
		List<RoadPathNode> path = road(a, b);
		List<RoadPathNode> result = PathOperations.deduplicateConsecutive(path);
		// Same content (deduplicateConsecutive does not necessarily return the same list instance, just same data).
		assertEquals(Arrays.asList(a, b), PathOperations.toLocationList(result));
		assertNotNull(result);
		assertSame(result.get(0), path.get(0));
	}
}
