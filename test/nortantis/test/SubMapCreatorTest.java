package nortantis.test;

import nortantis.MapCreator;
import nortantis.MapSettings;
import nortantis.SubMapCreator;
import nortantis.editor.River;
import nortantis.WorldGraph;
import nortantis.geom.Point;
import nortantis.geom.Rectangle;
import nortantis.swing.SubMapDialog;
import nortantis.util.OrderlessPair;
import nortantis.platform.Image;
import nortantis.platform.ImageHelper;
import nortantis.platform.PlatformFactory;
import nortantis.platform.awt.AwtFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.io.FileUtils;

import static org.junit.jupiter.api.Assertions.*;

public class SubMapCreatorTest
{
	static final String failedMapsFolderName = "failed sub-maps";

	// Set this to true to make every test write its result map to the failed sub-maps folder, so the actual results can be viewed even when
	// the tests pass. Each test also has a local forceWrite flag near its top that does the same for that single test.
	private static final boolean forceWriteAllMaps = true;

	@BeforeAll
	public static void setUpBeforeClass() throws IOException
	{
		PlatformFactory.setInstance(new AwtFactory());
		nortantis.swing.translation.Translation.initialize();
		FileUtils.deleteDirectory(new File(Paths.get("unit test files", failedMapsFolderName).toString()));
	}

	/**
	 * Verifies that when a sub-map contains two rivers that form a confluence in the source map, those rivers are still connected via a
	 * shared corner in the sub-map's edge edits. This is a regression test for a bug in SubMapCreator where the last edge of a tributary
	 * was incorrectly removed by simplifyToPath, preventing the confluence from being transferred.
	 */
	@Test
	public void subMapRiversFormConfluence() throws Exception
	{
		// Set to true to force this test to write its result map to the failed sub-maps folder, even when it passes.
		boolean forceWrite = false;

		String originalSettingsPath = Paths.get("unit test files", "map settings", "riversForSubMaps.nort").toString();
		MapSettings originalSettings = new MapSettings(originalSettingsPath);
		originalSettings.resolution = 0.5;

		WorldGraph originalGraph = MapCreator.createGraphForUnitTests(originalSettings);
		originalSettings.edits.initializeRiversFromGraph(originalGraph, originalSettings.resolution);

		// Sub-map selection bounds in RI (resolution-invariant) coordinates.
		Rectangle selectionBoundsRI = new Rectangle(1324, 999, 1307, 1307);

		int worldSize = SubMapDialog.computeDefaultWorldSize(originalSettings, selectionBoundsRI);

		long seed = 1962328436L;
		MapSettings subMapSettings = SubMapCreator.createSubMapSettings(originalSettings, originalGraph, selectionBoundsRI, worldSize, originalSettings.resolution, seed, true);

		List<River> rivers = subMapSettings.edits.rivers;

		assertEquals(2, rivers.size(), "Sub-map should contain exactly 2 rivers (expected a main river and a tributary)");

		assertRiversAreAllConnected(rivers, subMapSettings, "subMapRiversFormConfluence");
		if (forceWrite || forceWriteAllMaps)
		{
			saveFailedMap(subMapSettings, "subMapRiversFormConfluence");
		}
	}

	/**
	 * Verifies that rivers in a sub-map contain no finger branches. A finger is a branch point — a corner with degree &gt; 2 in the
	 * combined river edge graph — that was not present in the original map. The original map for this test case has no such branches.
	 * <p>
	 * Note: {@link WorldGraph#findRivers()} separates branch arms into distinct {@link River} objects, so checking degree within a single
	 * river's edges would never detect fingers. Instead this test builds the degree map from the unique set of all edges across all rivers.
	 * </p>
	 */
	@Test
	public void subMapRiversHaveNoFingers() throws Exception
	{
		// Set to true to force this test to write its result map to the failed sub-maps folder, even when it passes.
		boolean forceWrite = false;

		String originalSettingsPath = Paths.get("unit test files", "map settings", "riversForSubMaps.nort").toString();
		MapSettings originalSettings = new MapSettings(originalSettingsPath);
		originalSettings.resolution = 0.5;

		WorldGraph originalGraph = MapCreator.createGraphForUnitTests(originalSettings);
		originalSettings.edits.initializeRiversFromGraph(originalGraph, originalSettings.resolution);

		// Sub-map selection bounds in RI (resolution-invariant) coordinates.
		Rectangle selectionBoundsRI = new Rectangle(0, 0, 1348, 4096);

		int worldSize = SubMapDialog.computeDefaultWorldSize(originalSettings, selectionBoundsRI);

		long seed = 983909832L;
		MapSettings subMapSettings = SubMapCreator.createSubMapSettings(originalSettings, originalGraph, selectionBoundsRI, worldSize, originalSettings.resolution, seed, true);

		List<River> rivers = subMapSettings.edits.rivers;

		assertRiversHaveNoFingers(rivers, subMapSettings, "subMapRiversHaveNoFingers");
		if (forceWrite || forceWriteAllMaps)
		{
			saveFailedMap(subMapSettings, "subMapRiversHaveNoFingers");
		}
	}

	/**
	 * Verifies that rivers in a sub-map contain no fingers or loops for a sub-map with a complex river network.
	 */
	@Test
	public void subMapComplexRiversHaveNoFingersOrLoops() throws Exception
	{
		// Set to true to force this test to write its result map to the failed sub-maps folder, even when it passes.
		boolean forceWrite = false;

		String originalSettingsPath = Paths.get("unit test files", "map settings", "riversForSubMaps.nort").toString();
		MapSettings originalSettings = new MapSettings(originalSettingsPath);
		originalSettings.resolution = 0.5;

		WorldGraph originalGraph = MapCreator.createGraphForUnitTests(originalSettings);
		originalSettings.edits.initializeRiversFromGraph(originalGraph, originalSettings.resolution);

		// Sub-map selection bounds in RI (resolution-invariant) coordinates.
		Rectangle selectionBoundsRI = new Rectangle(2515, 1471, 1581, 2625);

		int worldSize = SubMapDialog.computeDefaultWorldSize(originalSettings, selectionBoundsRI);

		long seed = 1735631519L;
		MapSettings subMapSettings = SubMapCreator.createSubMapSettings(originalSettings, originalGraph, selectionBoundsRI, worldSize, originalSettings.resolution, seed, true);

		List<River> rivers = subMapSettings.edits.rivers;

		assertRiversHaveNoLoops(rivers, subMapSettings, "subMapComplexRiversHaveNoFingersOrLoops");
		assertRiversHaveNoFingers(rivers, subMapSettings, "subMapComplexRiversHaveNoFingersOrLoops");
		if (forceWrite || forceWriteAllMaps)
		{
			saveFailedMap(subMapSettings, "subMapComplexRiversHaveNoFingersOrLoops");
		}
	}

	/**
	 * Verifies that rivers in a sub-map contain no loops. A loop exists when the river's edges form a cycle, detected by checking that the
	 * edge count exceeds corner count minus one (the invariant for a simple path).
	 */
	@Test
	public void subMapRiversHaveNoLoops() throws Exception
	{
		// Set to true to force this test to write its result map to the failed sub-maps folder, even when it passes.
		boolean forceWrite = false;

		String originalSettingsPath = Paths.get("unit test files", "map settings", "riversForSubMaps.nort").toString();
		MapSettings originalSettings = new MapSettings(originalSettingsPath);
		originalSettings.resolution = 0.5;

		WorldGraph originalGraph = MapCreator.createGraphForUnitTests(originalSettings);
		originalSettings.edits.initializeRiversFromGraph(originalGraph, originalSettings.resolution);

		// Sub-map selection bounds in RI (resolution-invariant) coordinates.
		Rectangle selectionBoundsRI = new Rectangle(0, 0, 1348, 4096);

		int worldSize = SubMapDialog.computeDefaultWorldSize(originalSettings, selectionBoundsRI);

		long seed = 384811022L;
		MapSettings subMapSettings = SubMapCreator.createSubMapSettings(originalSettings, originalGraph, selectionBoundsRI, worldSize, originalSettings.resolution, seed, true);

		List<River> rivers = subMapSettings.edits.rivers;

		assertRiversHaveNoLoops(rivers, subMapSettings, "subMapRiversHaveNoLoops");
		if (forceWrite || forceWriteAllMaps)
		{
			saveFailedMap(subMapSettings, "subMapRiversHaveNoLoops");
		}
	}

	/**
	 * Verifies that a T-shaped river in a sub-map has 3 mouths where it meets the ocean. Sub-map rivers are stored as freehand
	 * {@link River} polylines (not tied to the new graph's edges), so a mouth is counted as a river-path endpoint whose location is
	 * adjacent to a coast or ocean corner in the sub-map graph. This is a regression test for a bug where one arm of the T was incorrectly
	 * dropped, leaving only 2 mouths.
	 */
	@Test
	public void subMapTShapedRiverHasThreeMouths() throws Exception
	{
		// Set to true to force this test to write its result map to the failed sub-maps folder, even when it passes.
		boolean forceWrite = false;

		String originalSettingsPath = Paths.get("unit test files", "map settings", "riversForSubMaps.nort").toString();
		MapSettings originalSettings = new MapSettings(originalSettingsPath);
		originalSettings.resolution = 0.5;

		WorldGraph originalGraph = MapCreator.createGraphForUnitTests(originalSettings);
		originalSettings.edits.initializeRiversFromGraph(originalGraph, originalSettings.resolution);

		// Sub-map selection bounds in RI (resolution-invariant) coordinates.
		Rectangle selectionBoundsRI = new Rectangle(1158, 1115, 1559, 1092);

		int worldSize = SubMapDialog.computeDefaultWorldSize(originalSettings, selectionBoundsRI);

		long seed = 595862260L;
		MapSettings subMapSettings = SubMapCreator.createSubMapSettings(originalSettings, originalGraph, selectionBoundsRI, worldSize, originalSettings.resolution, seed, true);

		WorldGraph newGraph = MapCreator.createGraphForUnitTests(subMapSettings);

		List<River> rivers = subMapSettings.edits.rivers;

		assertNoDuplicateRiverSegments(rivers, subMapSettings, "subMapTShapedRiverHasThreeMouths");

		long mouthCount = 0;
		for (River river : rivers)
		{
			if (riverEndpointTouchesCoastOrOcean(river.nodes.get(0).getLoc(), newGraph, subMapSettings.resolution))
				mouthCount++;
			if (riverEndpointTouchesCoastOrOcean(river.nodes.get(river.nodes.size() - 1).getLoc(), newGraph, subMapSettings.resolution))
				mouthCount++;
		}

		if (mouthCount != 3)
		{
			String failedMapPath = saveFailedMap(subMapSettings, "subMapTShapedRiverHasThreeMouths");
			fail("Expected the T-shaped river to have 3 mouths meeting the ocean, but found " + mouthCount + ".\nFailed map written to: " + failedMapPath);
		}
		else if (forceWrite || forceWriteAllMaps)
		{
			saveFailedMap(subMapSettings, "subMapTShapedRiverHasThreeMouths");
		}
	}

	/**
	 * Verifies that a T-shaped river in a sub-map has all its arms connected at the junction. This is a regression test for a bug where one
	 * arm of the T was disconnected from the others.
	 */
	@Test
	public void subMapRiversFormConfluence2() throws Exception
	{
		// Set to true to force this test to write its result map to the failed sub-maps folder, even when it passes.
		boolean forceWrite = false;

		String originalSettingsPath = Paths.get("unit test files", "map settings", "riversForSubMaps.nort").toString();
		MapSettings originalSettings = new MapSettings(originalSettingsPath);
		originalSettings.resolution = 0.5;

		WorldGraph originalGraph = MapCreator.createGraphForUnitTests(originalSettings);
		originalSettings.edits.initializeRiversFromGraph(originalGraph, originalSettings.resolution);

		// Sub-map selection bounds in RI (resolution-invariant) coordinates.
		Rectangle selectionBoundsRI = new Rectangle(661, 18, 2013, 2335);

		int worldSize = SubMapDialog.computeDefaultWorldSize(originalSettings, selectionBoundsRI);

		long seed = 1222331460L;
		MapSettings subMapSettings = SubMapCreator.createSubMapSettings(originalSettings, originalGraph, selectionBoundsRI, worldSize, originalSettings.resolution, seed, true);

		List<River> rivers = subMapSettings.edits.rivers;

		assertRiversAreAllConnected(rivers, subMapSettings, "subMapRiversFormConfluence2");
		if (forceWrite || forceWriteAllMaps)
		{
			saveFailedMap(subMapSettings, "subMapRiversFormConfluence2");
		}
	}

	/**
	 * Verifies that a sub-map covering the entire source map at the original detail level (a 1× "Match source detail" sub-map) produces a
	 * clean river network: no duplicate segments, no loops, and no degenerate single-point rivers. This exercises the full-map transfer
	 * path for {@code riversForSubMaps.nort}, where the selection covers the whole map so the sub-map's polygon count matches the source's.
	 */
	@Test
	public void subMapOfEntireMapAtOriginalDetailRivers() throws Exception
	{
		// Set to true to force this test to write its result map to the failed sub-maps folder, even when it passes.
		boolean forceWrite = false;

		String originalSettingsPath = Paths.get("unit test files", "map settings", "riversForSubMaps.nort").toString();
		MapSettings originalSettings = new MapSettings(originalSettingsPath);
		originalSettings.resolution = 0.5;

		WorldGraph originalGraph = MapCreator.createGraphForUnitTests(originalSettings);
		originalSettings.edits.initializeRiversFromGraph(originalGraph, originalSettings.resolution);

		// Selection bounds covering the entire source map, in RI (resolution-invariant) coordinates.
		Rectangle selectionBoundsRI = new Rectangle(0, 0, originalSettings.generatedWidth, originalSettings.generatedHeight);

		// For a full-map selection this returns the original world size, i.e. the original detail level ("Match source detail").
		int worldSize = SubMapDialog.computeDefaultWorldSize(originalSettings, selectionBoundsRI);
		assertEquals(originalSettings.worldSize, worldSize, "A full-map selection should default to the source map's world size (original detail level)");

		long seed = 1402779553L;
		// redistributeIcons=false: original detail level means "Match source detail", which copies rivers over exactly.
		MapSettings subMapSettings = SubMapCreator.createSubMapSettings(originalSettings, originalGraph, selectionBoundsRI, worldSize, originalSettings.resolution, seed, false);

		List<River> rivers = subMapSettings.edits.rivers;

		assertNoDuplicateRiverSegments(rivers, subMapSettings, "subMapOfEntireMapAtOriginalDetailRivers");
		assertRiversHaveNoLoops(rivers, subMapSettings, "subMapOfEntireMapAtOriginalDetailRivers");
		assertRiversHaveNoFingers(rivers, subMapSettings, "subMapOfEntireMapAtOriginalDetailRivers");
		if (forceWrite || forceWriteAllMaps)
		{
			saveFailedMap(subMapSettings, "subMapOfEntireMapAtOriginalDetailRivers");
		}
	}

	/**
	 * Verifies that a sub-map covering the entire source map at the original detail level (a 1× "Match source detail" sub-map) produces a
	 * clean river network for {@code simpleSmallWorld.nort}: no duplicate segments, no loops, and no degenerate single-point rivers.
	 */
	@Test
	public void subMapOfEntireMapAtOriginalDetailSimpleSmallWorld() throws Exception
	{
		// Set to true to force this test to write its result map to the failed sub-maps folder, even when it passes.
		boolean forceWrite = false;

		String originalSettingsPath = Paths.get("unit test files", "map settings", "simpleSmallWorld.nort").toString();
		MapSettings originalSettings = new MapSettings(originalSettingsPath);
		originalSettings.resolution = 0.5;

		WorldGraph originalGraph = MapCreator.createGraphForUnitTests(originalSettings);
		originalSettings.edits.initializeRiversFromGraph(originalGraph, originalSettings.resolution);

		// Selection bounds covering the entire source map, in RI (resolution-invariant) coordinates.
		Rectangle selectionBoundsRI = new Rectangle(0, 0, originalSettings.generatedWidth, originalSettings.generatedHeight);

		// For a full-map selection this returns the original world size, i.e. the original detail level ("Match source detail").
		int worldSize = SubMapDialog.computeDefaultWorldSize(originalSettings, selectionBoundsRI);
		assertEquals(originalSettings.worldSize, worldSize, "A full-map selection should default to the source map's world size (original detail level)");

		long seed = 768241095L;
		// redistributeIcons=false: original detail level means "Match source detail", which copies rivers over exactly.
		MapSettings subMapSettings = SubMapCreator.createSubMapSettings(originalSettings, originalGraph, selectionBoundsRI, worldSize, originalSettings.resolution, seed, false);

		List<River> rivers = subMapSettings.edits.rivers;

		assertNoDuplicateRiverSegments(rivers, subMapSettings, "subMapOfEntireMapAtOriginalDetailSimpleSmallWorld");
		assertRiversHaveNoLoops(rivers, subMapSettings, "subMapOfEntireMapAtOriginalDetailSimpleSmallWorld");
		assertRiversHaveNoFingers(rivers, subMapSettings, "subMapOfEntireMapAtOriginalDetailSimpleSmallWorld");
		if (forceWrite || forceWriteAllMaps)
		{
			saveFailedMap(subMapSettings, "subMapOfEntireMapAtOriginalDetailSimpleSmallWorld");
		}
	}

	/**
	 * Returns true if the new-graph corner closest to the given river-path endpoint (in RI coordinates) is adjacent to the ocean — i.e. it
	 * is a coast or ocean corner, or it touches a water center. Sub-map rivers are freehand polylines, so a river that reaches the sea ends
	 * at a shoreline corner; an interior confluence/junction endpoint sits inland and does not match.
	 */
	private boolean riverEndpointTouchesCoastOrOcean(Point endpointRI, WorldGraph newGraph, double resolution)
	{
		Point pixel = new Point(endpointRI.x * resolution, endpointRI.y * resolution);
		nortantis.graph.voronoi.Corner corner = newGraph.findClosestCorner(pixel);
		if (corner == null)
			return false;
		if (corner.isCoast || corner.isOcean || corner.isWater)
			return true;
		return corner.touches.stream().anyMatch(center -> center.isWater);
	}

	private String saveFailedMap(MapSettings subMapSettings, String testName) throws Exception
	{
		File failedMapsDir = Paths.get("unit test files", failedMapsFolderName).toFile();
		failedMapsDir.mkdirs();
		String failedMapPath = Paths.get("unit test files", failedMapsFolderName, testName + ".png").toString();
		Image map = new MapCreator().createMap(subMapSettings, null, null);
		ImageHelper.getInstance().write(map, failedMapPath);
		return failedMapPath;
	}

	/**
	 * Asserts that all rivers form a single connected component, i.e. every river shares at least one path point with some other river.
	 * Uses union-find on path-point sets to detect disconnected river arms.
	 */
	private void assertRiversAreAllConnected(List<River> rivers, MapSettings subMapSettings, String testName) throws Exception
	{
		if (rivers.size() <= 1)
			return;

		int[] parent = new int[rivers.size()];
		for (int i = 0; i < parent.length; i++)
			parent[i] = i;

		for (int i = 0; i < rivers.size(); i++)
		{
			Set<Point> pointsI = new HashSet<>(nortantis.PathOperations.toLocationList(rivers.get(i).nodes));
			for (int j = i + 1; j < rivers.size(); j++)
			{
				Set<Point> pointsJ = new HashSet<>(nortantis.PathOperations.toLocationList(rivers.get(j).nodes));
				Set<Point> intersection = new HashSet<>(pointsI);
				intersection.retainAll(pointsJ);
				if (!intersection.isEmpty())
				{
					int rootI = i, rootJ = j;
					while (parent[rootI] != rootI)
						rootI = parent[rootI];
					while (parent[rootJ] != rootJ)
						rootJ = parent[rootJ];
					if (rootI != rootJ)
						parent[rootI] = rootJ;
				}
			}
		}

		Set<Integer> roots = new HashSet<>();
		for (int i = 0; i < parent.length; i++)
		{
			int root = i;
			while (parent[root] != root)
				root = parent[root];
			roots.add(root);
		}

		if (roots.size() > 1)
		{
			String failedMapPath = saveFailedMap(subMapSettings, testName);
			fail("Rivers are not all connected: found " + roots.size() + " disconnected components among " + rivers.size() + " rivers.\nFailed map written to: " + failedMapPath);
		}
	}

	/**
	 * Asserts that no river path contains a repeated point (which would indicate a loop in the routed path).
	 */
	private void assertRiversHaveNoLoops(List<River> rivers, MapSettings subMapSettings, String testName) throws Exception
	{
		for (int ri = 0; ri < rivers.size(); ri++)
		{
			River river = rivers.get(ri);
			Set<Point> seen = new HashSet<>();
			for (nortantis.editor.RiverPathNode node : river.nodes)
			{
				Point p = node.getLoc();
				if (!seen.add(p))
				{
					String failedMapPath = saveFailedMap(subMapSettings, testName);
					fail("River " + ri + " has a loop: path point " + p + " appears more than once.\nFailed map written to: " + failedMapPath);
				}
			}
		}
	}

	/**
	 * Asserts that no two river segments across all rivers share the same pair of endpoints. A sub-map should never contain two segments
	 * with the same two control points: where river arms meet at a confluence they should share a single segment, not duplicate it. A
	 * duplicate segment is drawn as two overlapping Catmull-Rom curves, which renders as a small loop even though the node path has no
	 * topological loop. This catches the duplicated segment at the base of a T-junction.
	 */
	private void assertNoDuplicateRiverSegments(List<River> rivers, MapSettings subMapSettings, String testName) throws Exception
	{
		Map<OrderlessPair<Point>, Integer> segmentToRiver = new HashMap<>();
		for (int ri = 0; ri < rivers.size(); ri++)
		{
			List<Point> path = nortantis.PathOperations.toLocationList(rivers.get(ri).nodes);
			for (int i = 0; i + 1 < path.size(); i++)
			{
				Point a = path.get(i);
				Point b = path.get(i + 1);
				if (a.equals(b))
					continue; // A zero-length segment is a separate degeneracy, not a duplicate.
				Integer previousRiver = segmentToRiver.putIfAbsent(new OrderlessPair<>(a, b), ri);
				if (previousRiver != null)
				{
					String failedMapPath = saveFailedMap(subMapSettings, testName);
					fail("Duplicate river segment between " + a + " and " + b + " (first in river " + previousRiver + ", again in river " + ri
							+ "). Arms meeting at a confluence should share one segment, not duplicate it.\nFailed map written to: " + failedMapPath);
				}
			}
		}
	}

	/**
	 * Asserts that each river's path has no branching. Since each {@link River} is a linear polyline this is trivially true, but this check
	 * catches degenerate cases such as rivers with fewer than 2 points (which would be invisible and indicate a routing failure).
	 */
	private void assertRiversHaveNoFingers(List<River> rivers, MapSettings subMapSettings, String testName) throws Exception
	{
		for (int ri = 0; ri < rivers.size(); ri++)
		{
			River river = rivers.get(ri);
			if (river.nodes.size() < 2)
			{
				String failedMapPath = saveFailedMap(subMapSettings, testName);
				fail("River " + ri + " has fewer than 2 path points (" + river.nodes.size() + "), indicating a failed routing.\nFailed map written to: " + failedMapPath);
			}
		}
	}

}
