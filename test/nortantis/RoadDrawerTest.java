package nortantis;

import nortantis.editor.Road;
import nortantis.editor.RoadPathNode;
import nortantis.geom.Point;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class RoadDrawerTest
{
	private static Road road(Point... locations)
	{
		List<RoadPathNode> nodes = new ArrayList<>(locations.length);
		for (Point loc : locations)
		{
			nodes.add(new RoadPathNode(loc));
		}
		return new Road(new CopyOnWriteArrayList<>(nodes));
	}

	private static List<Point> locs(Road road)
	{
		return PathOperations.toLocationList(road.nodes);
	}

	@Test
	public void removeEmptyOrSinglePointRoads_removesEmptyRoad()
	{
		List<Road> roads = new ArrayList<>();
		roads.add(road(new Point(0, 0)));
		roads.add(road(new Point(0, 0), new Point(1, 0)));
		RoadDrawer.removeEmptyOrSinglePointRoads(roads);
		assertEquals(1, roads.size());
		assertEquals(Arrays.asList(new Point(0, 0), new Point(1, 0)), locs(roads.get(0)));
	}

	@Test
	public void removeEmptyOrSinglePointRoads_removesZeroNodeRoad()
	{
		List<Road> roads = new ArrayList<>();
		roads.add(new Road(new CopyOnWriteArrayList<>()));
		roads.add(road(new Point(0, 0), new Point(1, 0)));
		RoadDrawer.removeEmptyOrSinglePointRoads(roads);
		assertEquals(1, roads.size());
	}

	@Test
	public void removeEmptyOrSinglePointRoads_keepsAllValid()
	{
		List<Road> roads = new ArrayList<>();
		roads.add(road(new Point(0, 0), new Point(1, 0)));
		roads.add(road(new Point(2, 2), new Point(3, 3), new Point(4, 4)));
		RoadDrawer.removeEmptyOrSinglePointRoads(roads);
		assertEquals(2, roads.size());
	}

	@Test
	public void tryConnectingRoadToExistingRoad_appendMatch()
	{
		Point a = new Point(0, 0);
		Point b = new Point(1, 0);
		Point c = new Point(2, 0);
		Point d = new Point(3, 0);
		Road existing = road(a, b, c);
		Road toAdd = road(c, d);
		List<Road> roads = new ArrayList<>(Arrays.asList(existing));
		Road joined = RoadDrawer.tryConnectingRoadToExistingRoad(toAdd, roads);
		assertNotNull(joined);
		assertSame(existing, joined);
		assertEquals(Arrays.asList(a, b, c, d), locs(existing));
	}

	@Test
	public void tryConnectingRoadToExistingRoad_noMatchReturnsNull()
	{
		Point a = new Point(0, 0);
		Point b = new Point(1, 0);
		Point c = new Point(5, 5);
		Point d = new Point(6, 6);
		Road existing = road(a, b);
		Road toAdd = road(c, d);
		List<Road> roads = new ArrayList<>(Arrays.asList(existing));
		assertNull(RoadDrawer.tryConnectingRoadToExistingRoad(toAdd, roads));
		// Existing road untouched.
		assertEquals(Arrays.asList(a, b), locs(existing));
	}

	@Test
	public void tryConnectingRoadToExistingRoad_skipsIdenticalReference()
	{
		Point a = new Point(0, 0);
		Point b = new Point(1, 0);
		Road only = road(a, b);
		List<Road> roads = new ArrayList<>(Arrays.asList(only));
		// roadToAdd == the only road in the list — should not match itself.
		assertNull(RoadDrawer.tryConnectingRoadToExistingRoad(only, roads));
	}

	@Test
	public void tryConnectingRoadToExistingRoad_rejectsShortRoad()
	{
		Point a = new Point(0, 0);
		Road existing = road(a, new Point(1, 0));
		Road tooShort = new Road(new CopyOnWriteArrayList<>(Collections.singletonList(new RoadPathNode(a))));
		List<Road> roads = new ArrayList<>(Arrays.asList(existing));
		assertNull(RoadDrawer.tryConnectingRoadToExistingRoad(tooShort, roads));
	}

	@Test
	public void addFreeHandRoadFromPoints_emptyPathReturnsEmpty()
	{
		List<Road> roads = new ArrayList<>();
		assertEquals(Collections.emptyList(), RoadDrawer.addFreeHandRoadFromPoints(Collections.emptyList(), roads));
		assertTrue(roads.isEmpty());
	}

	@Test
	public void addFreeHandRoadFromPoints_singlePointReturnsEmpty()
	{
		List<Road> roads = new ArrayList<>();
		assertEquals(Collections.emptyList(), RoadDrawer.addFreeHandRoadFromPoints(Arrays.asList(new Point(0, 0)), roads));
		assertTrue(roads.isEmpty());
	}

	@Test
	public void addFreeHandRoadFromPoints_addsNewRoadWhenNoOverlap()
	{
		List<Road> roads = new ArrayList<>();
		List<Point> path = Arrays.asList(new Point(0, 0), new Point(1, 0), new Point(2, 0));
		List<Road> changed = RoadDrawer.addFreeHandRoadFromPoints(path, roads);
		assertEquals(1, changed.size());
		assertEquals(path, locs(changed.get(0)));
		assertEquals(1, roads.size());
	}

	@Test
	public void removeSegmentsAndSplitRoads_removesEntireRoad()
	{
		Point a = new Point(0, 0);
		Point b = new Point(1, 0);
		Road only = road(a, b);
		List<Road> roads = new ArrayList<>(Arrays.asList(only));
		List<List<Point>> segmentsToRemove = Arrays.asList(Arrays.asList(a, b));
		List<Road> changed = RoadDrawer.removeSegmentsAndSplitRoads(roads, segmentsToRemove);
		// Both endpoints in split set: segment a→b removed; road now has no segments.
		assertEquals(1, changed.size());
		assertTrue(only.nodes.isEmpty());
	}

	@Test
	public void removeSegmentsAndSplitRoads_splitsRoadAtMiddleSegment()
	{
		Point a = new Point(0, 0);
		Point b = new Point(1, 0);
		Point c = new Point(2, 0);
		Point d = new Point(3, 0);
		Road full = road(a, b, c, d);
		List<Road> roads = new ArrayList<>(Arrays.asList(full));
		// Remove the middle segment b→c (both endpoints in split set).
		List<List<Point>> segmentsToRemove = Arrays.asList(Arrays.asList(b, c));
		List<Road> changed = RoadDrawer.removeSegmentsAndSplitRoads(roads, segmentsToRemove);
		// Should result in two pieces: a-b and c-d. The original road becomes piece 1; piece 2 is new.
		assertEquals(2, roads.size());
		// "changed" contains both the modified original and the newly created road.
		assertEquals(2, changed.size());
		assertEquals(Arrays.asList(a, b), locs(roads.get(0)));
		assertEquals(Arrays.asList(c, d), locs(roads.get(1)));
	}

	@Test
	public void removeSegmentsAndSplitRoads_unchangedRoadStaysOut()
	{
		Point a = new Point(0, 0);
		Point b = new Point(1, 0);
		Road untouched = road(a, b);
		List<Road> roads = new ArrayList<>(Arrays.asList(untouched));
		// No segments match.
		List<List<Point>> segmentsToRemove = Arrays.asList(Arrays.asList(new Point(99, 99), new Point(100, 100)));
		List<Road> changed = RoadDrawer.removeSegmentsAndSplitRoads(roads, segmentsToRemove);
		assertEquals(0, changed.size());
		assertEquals(1, roads.size());
		assertSame(untouched, roads.get(0));
	}

	@Test
	public void removeSegmentsAndSplitRoads_thenFindInnerNeighbors_handlesJunction()
	{
		// Two roads share a junction at b. Cutting the first road's a→b segment also splits the
		// second road at b. The synthetic reflection endpoint used by CurveCreator.createCurve(List)
		// for end nodes would otherwise shift the curve along each new end segment; the inner
		// neighbors (c, x, y) need to be in the redraw bounds to avoid tearing.
		Point a = new Point(0, 0);
		Point b = new Point(1, 0);
		Point c = new Point(2, 0);
		Point x = new Point(1, 1);
		Point y = new Point(1, -1);
		Road first = road(a, b, c);
		Road second = road(x, b, y);
		List<Road> roads = new ArrayList<>(Arrays.asList(first, second));
		List<List<Point>> segmentsToRemove = Arrays.asList(Arrays.asList(a, b));
		RoadDrawer.removeSegmentsAndSplitRoads(roads, segmentsToRemove);
		List<List<RoadPathNode>> nodeLists = new ArrayList<>();
		for (Road r : roads)
		{
			nodeLists.add(r.nodes);
		}
		java.util.Set<Point> neighbors = new java.util.HashSet<>(PathOperations.findInnerNeighborsOfCutEndpoints(nodeLists, segmentsToRemove));
		assertEquals(new java.util.HashSet<>(Arrays.asList(c, x, y)), neighbors);
	}
}
