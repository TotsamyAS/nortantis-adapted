package nortantis;

import nortantis.editor.River;
import nortantis.editor.RiverPathNode;
import nortantis.geom.Point;
import nortantis.graph.voronoi.Center;
import nortantis.graph.voronoi.Corner;
import nortantis.platform.PlatformFactory;
import nortantis.platform.awt.AwtFactory;
import nortantis.util.OrderlessPair;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class RiverDrawerTest
{
	private static final int WIDTH = 5;

	@BeforeAll
	public static void setUpBeforeClass()
	{
		PlatformFactory.setInstance(new AwtFactory());
		nortantis.swing.translation.Translation.initialize();
	}

	private static River river(int widthLevel, Point... locations)
	{
		List<RiverPathNode> nodes = new ArrayList<>(locations.length);
		for (int i = 0; i < locations.length; i++)
		{
			boolean isLast = i == locations.length - 1;
			nodes.add(new RiverPathNode(locations[i], isLast ? 0 : widthLevel, isLast ? 0L : 12345L));
		}
		return new River(new CopyOnWriteArrayList<>(nodes));
	}

	private static List<Point> locs(River river)
	{
		return PathOperations.toLocationList(river.nodes);
	}

	@Test
	public void removeEmptyOrShortRivers_removesOneNodeRiver()
	{
		List<River> rivers = new ArrayList<>();
		rivers.add(river(WIDTH, new Point(0, 0))); // one-node river: degenerate
		rivers.add(river(WIDTH, new Point(0, 0), new Point(1, 0)));
		RiverDrawer.removeEmptyOrShortRivers(rivers);
		assertEquals(1, rivers.size());
	}

	@Test
	public void removeEmptyOrShortRivers_removesEmpty()
	{
		List<River> rivers = new ArrayList<>();
		rivers.add(new River(new CopyOnWriteArrayList<>()));
		rivers.add(river(WIDTH, new Point(0, 0), new Point(1, 0)));
		RiverDrawer.removeEmptyOrShortRivers(rivers);
		assertEquals(1, rivers.size());
	}

	@Test
	public void removeEmptyOrShortRivers_keepsAll()
	{
		List<River> rivers = new ArrayList<>();
		rivers.add(river(WIDTH, new Point(0, 0), new Point(1, 0)));
		rivers.add(river(WIDTH, new Point(2, 0), new Point(3, 0), new Point(4, 0)));
		RiverDrawer.removeEmptyOrShortRivers(rivers);
		assertEquals(2, rivers.size());
	}

	@Test
	public void tryConnectingRiverToExistingRiver_appendMatch()
	{
		Point a = new Point(0, 0);
		Point b = new Point(1, 0);
		Point c = new Point(2, 0);
		Point d = new Point(3, 0);
		River existing = river(WIDTH, a, b, c);
		River toAdd = river(WIDTH, c, d);
		List<River> rivers = new ArrayList<>(Arrays.asList(existing));
		River joined = RiverDrawer.tryConnectingRiverToExistingRiver(toAdd, rivers);
		assertNotNull(joined);
		assertSame(existing, joined);
		assertEquals(Arrays.asList(a, b, c, d), locs(existing));
	}

	@Test
	public void tryConnectingRiverToExistingRiver_noMatchReturnsNull()
	{
		Point a = new Point(0, 0);
		Point b = new Point(1, 0);
		Point c = new Point(5, 5);
		Point d = new Point(6, 6);
		River existing = river(WIDTH, a, b);
		River toAdd = river(WIDTH, c, d);
		List<River> rivers = new ArrayList<>(Arrays.asList(existing));
		assertNull(RiverDrawer.tryConnectingRiverToExistingRiver(toAdd, rivers));
		assertEquals(Arrays.asList(a, b), locs(existing));
	}

	@Test
	public void tryConnectingRiverToExistingRiver_rejectsShortRiver()
	{
		Point a = new Point(0, 0);
		River existing = river(WIDTH, a, new Point(1, 0));
		River tooShort = new River(new CopyOnWriteArrayList<>(Collections.singletonList(new RiverPathNode(a, 0, 0))));
		List<River> rivers = new ArrayList<>(Arrays.asList(existing));
		assertNull(RiverDrawer.tryConnectingRiverToExistingRiver(tooShort, rivers));
	}

	@Test
	public void updateExistingRiverWidthsForPointPairs_updatesMatchingSegment()
	{
		Point a = new Point(0, 0);
		Point b = new Point(1, 0);
		Point c = new Point(2, 0);
		River existing = river(WIDTH, a, b, c);
		List<River> rivers = new ArrayList<>(Arrays.asList(existing));
		Set<OrderlessPair<Point>> toMatch = new HashSet<>(Collections.singletonList(new OrderlessPair<>(a, b)));
		int newWidth = WIDTH + 10;
		List<River> changed = RiverDrawer.updateExistingRiverWidthsForPointPairs(rivers, toMatch, newWidth);
		assertEquals(1, changed.size());
		assertEquals(newWidth, existing.nodes.get(0).getWidthLevelToNext());
		// The b→c segment width is unchanged.
		assertEquals(WIDTH, existing.nodes.get(1).getWidthLevelToNext());
	}

	@Test
	public void updateExistingRiverWidthsForPointPairs_noChangeWhenWidthAlreadyMatches()
	{
		Point a = new Point(0, 0);
		Point b = new Point(1, 0);
		River existing = river(WIDTH, a, b);
		List<River> rivers = new ArrayList<>(Arrays.asList(existing));
		Set<OrderlessPair<Point>> toMatch = new HashSet<>(Collections.singletonList(new OrderlessPair<>(a, b)));
		// Updating to the same width should not record the river as changed.
		List<River> changed = RiverDrawer.updateExistingRiverWidthsForPointPairs(rivers, toMatch, WIDTH);
		assertTrue(changed.isEmpty());
	}

	@Test
	public void updateExistingRiverWidthsForPointPairs_reverseDirectionMatches()
	{
		Point a = new Point(0, 0);
		Point b = new Point(1, 0);
		River existing = river(WIDTH, a, b);
		List<River> rivers = new ArrayList<>(Arrays.asList(existing));
		// Segment to match is (b, a) — order should not matter because we use OrderlessPair.
		Set<OrderlessPair<Point>> toMatch = new HashSet<>(Collections.singletonList(new OrderlessPair<>(b, a)));
		int newWidth = WIDTH + 10;
		List<River> changed = RiverDrawer.updateExistingRiverWidthsForPointPairs(rivers, toMatch, newWidth);
		assertEquals(1, changed.size());
		assertEquals(newWidth, existing.nodes.get(0).getWidthLevelToNext());
	}

	@Test
	public void updateExistingRiverWidthsForPointPairs_emptyInputsReturnEmpty()
	{
		Point a = new Point(0, 0);
		Point b = new Point(1, 0);
		River existing = river(WIDTH, a, b);
		List<River> rivers = new ArrayList<>(Arrays.asList(existing));
		assertTrue(RiverDrawer.updateExistingRiverWidthsForPointPairs(rivers, Collections.emptySet(), 99).isEmpty());
		assertTrue(RiverDrawer.updateExistingRiverWidthsForPointPairs(Collections.emptyList(), new HashSet<>(Collections.singletonList(new OrderlessPair<>(a, b))), 99).isEmpty());
		assertTrue(RiverDrawer.updateExistingRiverWidthsForPointPairs(null, new HashSet<>(Collections.singletonList(new OrderlessPair<>(a, b))), 99).isEmpty());
	}

	@Test
	public void removeSegmentsAndSplitRivers_splitsAtMiddle()
	{
		Point a = new Point(0, 0);
		Point b = new Point(1, 0);
		Point c = new Point(2, 0);
		Point d = new Point(3, 0);
		River full = river(WIDTH, a, b, c, d);
		List<River> rivers = new ArrayList<>(Arrays.asList(full));
		// Remove the middle segment b→c (both endpoints in split set).
		List<List<Point>> segmentsToRemove = Arrays.asList(Arrays.asList(b, c));
		List<River> changed = RiverDrawer.removeSegmentsAndSplitRivers(rivers, segmentsToRemove);
		assertEquals(2, rivers.size());
		assertEquals(2, changed.size());
		assertEquals(Arrays.asList(a, b), locs(rivers.get(0)));
		assertEquals(Arrays.asList(c, d), locs(rivers.get(1)));
	}

	@Test
	public void removeSegmentsAndSplitRivers_emptyResultClearsRiverNodes()
	{
		Point a = new Point(0, 0);
		Point b = new Point(1, 0);
		River only = river(WIDTH, a, b);
		List<River> rivers = new ArrayList<>(Arrays.asList(only));
		List<List<Point>> segmentsToRemove = Arrays.asList(Arrays.asList(a, b));
		List<River> changed = RiverDrawer.removeSegmentsAndSplitRivers(rivers, segmentsToRemove);
		// Segment removed; river still in the list but with empty nodes.
		assertEquals(1, changed.size());
		assertTrue(only.nodes.isEmpty());
	}

	@Test
	public void removeSegmentsAndSplitRivers_unchangedRiverNotReported()
	{
		Point a = new Point(0, 0);
		Point b = new Point(1, 0);
		River untouched = river(WIDTH, a, b);
		List<River> rivers = new ArrayList<>(Arrays.asList(untouched));
		List<List<Point>> segmentsToRemove = Arrays.asList(Arrays.asList(new Point(99, 99), new Point(100, 100)));
		List<River> changed = RiverDrawer.removeSegmentsAndSplitRivers(rivers, segmentsToRemove);
		assertEquals(0, changed.size());
		assertEquals(1, rivers.size());
		assertSame(untouched, rivers.get(0));
		assertEquals(Arrays.asList(a, b), locs(untouched));
	}

	@Test
	public void removeSegmentsAndSplitRivers_junctionRiverNotSplit()
	{
		// Two rivers share a junction at b. Cutting the first river's a→b segment must NOT split the second river at b —
		// only the first river actually contains the removed segment. The second river stays whole. findInnerNeighborsOfCutEndpoints
		// then reports just c (the inner neighbor of the first river's new end at b). Regression test for the bug where erasing one
		// river's segment flattened a branching river by splitting it at the shared junction.
		Point a = new Point(0, 0);
		Point b = new Point(1, 0);
		Point c = new Point(2, 0);
		Point x = new Point(1, 1);
		Point y = new Point(1, -1);
		River first = river(WIDTH, a, b, c);
		River second = river(WIDTH, x, b, y);
		List<River> rivers = new ArrayList<>(Arrays.asList(first, second));
		List<List<Point>> segmentsToRemove = Arrays.asList(Arrays.asList(a, b));
		RiverDrawer.removeSegmentsAndSplitRivers(rivers, segmentsToRemove);

		// first river lost its a→b segment and now starts at b; second river is unchanged.
		assertEquals(2, rivers.size());
		assertEquals(Arrays.asList(b, c), locs(rivers.get(0)));
		assertEquals(Arrays.asList(x, b, y), locs(rivers.get(1)));

		List<List<RiverPathNode>> nodeLists = new ArrayList<>();
		for (River r : rivers)
		{
			nodeLists.add(r.nodes);
		}
		Set<Point> neighbors = new HashSet<>(PathOperations.findInnerNeighborsOfCutEndpoints(nodeLists, segmentsToRemove));
		assertEquals(new HashSet<>(Arrays.asList(c)), neighbors);
	}

	/**
	 * The user's concern: adding a one-segment polygon river that connects a freehand river to a coastline corner. When that segment's edge
	 * is filtered out (it is a coast edge), LandWaterTool builds a synthetic bridge whose coast end is a freehand node
	 * ({@link RiverPathNode#EDGE_INDEX_NONE}) sitting exactly on the corner. This verifies the resync pass anchors that node to the corner
	 * so it tracks the coast across smoothing (rather than being left unanchored and stranded on a later line-style change).
	 */
	@Test
	public void resyncAnchorsFreehandMouthEndingExactlyOnACoastCorner()
	{
		MapSettings settings = new MapSettings(Paths.get("unit test files", "map settings", "riversForSubMaps.nort").toString());
		settings.resolution = 0.5;
		double resolution = settings.resolution;
		WorldGraph graph = MapCreator.createGraphForUnitTests(settings);

		Corner mouthCorner = null;
		Center landNeighbor = null;
		for (Corner corner : graph.corners)
		{
			if (RiverDrawer.isMouthCorner(corner))
			{
				for (Center center : corner.touches)
				{
					if (!center.isWater)
					{
						landNeighbor = center;
						break;
					}
				}
				if (landNeighbor != null)
				{
					mouthCorner = corner;
					break;
				}
			}
		}
		assertNotNull(mouthCorner, "Test settings should contain a coastline (a mouth corner adjacent to water and land)");

		// A freehand river whose terminal sits exactly on the coast corner (like LandWaterTool's synthetic-bridge node at start.loc),
		// flowing inland to a point that is not on any corner.
		Point mouthRI = mouthCorner.loc.mult(1.0 / resolution);
		Point inlandRI = mouthCorner.loc.add(landNeighbor.loc).mult(0.5).mult(1.0 / resolution);
		River river = new River(new CopyOnWriteArrayList<>(Arrays.asList(new RiverPathNode(mouthRI, WIDTH, 1L), new RiverPathNode(inlandRI, 0, 0L))));
		List<River> rivers = new ArrayList<>(Collections.singletonList(river));

		RiverDrawer.resyncRiverNodeLocationsToGraph(rivers, graph, resolution);

		assertEquals(mouthCorner.index, river.nodes.get(0).getCornerIndexAnchor(), "A freehand mouth ending exactly on a coast corner should be anchored to that corner");
		assertEquals(RiverPathNode.CORNER_INDEX_NONE, river.nodes.get(1).getCornerIndexAnchor(), "The inland endpoint should not be anchored");
	}

	/**
	 * The contract guard: a freehand mouth that ends merely <em>near</em> the coast (not exactly on a corner) must NOT be anchored.
	 * Anchoring it would drag the user's deliberately-placed endpoint onto a corner; the user confirmed an appears-connected freehand mouth
	 * breaking on a line-style change is user error, out of scope.
	 */
	@Test
	public void resyncDoesNotAnchorFreehandMouthThatEndsNearButNotOnACoastCorner()
	{
		MapSettings settings = new MapSettings(Paths.get("unit test files", "map settings", "riversForSubMaps.nort").toString());
		settings.resolution = 0.5;
		double resolution = settings.resolution;
		WorldGraph graph = MapCreator.createGraphForUnitTests(settings);

		Corner mouthCorner = null;
		for (Corner corner : graph.corners)
		{
			if (RiverDrawer.isMouthCorner(corner))
			{
				mouthCorner = corner;
				break;
			}
		}
		assertNotNull(mouthCorner, "Test settings should contain a coastline");

		// Endpoints offset a few RI off the corner — near the coast, but not exactly on it.
		Point nearCoastRI = mouthCorner.loc.mult(1.0 / resolution).add(new Point(3, 3));
		Point inlandRI = nearCoastRI.add(new Point(20, 20));
		River river = new River(new CopyOnWriteArrayList<>(Arrays.asList(new RiverPathNode(nearCoastRI, WIDTH, 1L), new RiverPathNode(inlandRI, 0, 0L))));
		List<River> rivers = new ArrayList<>(Collections.singletonList(river));

		RiverDrawer.resyncRiverNodeLocationsToGraph(rivers, graph, resolution);

		assertEquals(RiverPathNode.CORNER_INDEX_NONE, river.nodes.get(0).getCornerIndexAnchor(), "A freehand mouth that ends near but not exactly on a coast corner must not be anchored");
	}

}
