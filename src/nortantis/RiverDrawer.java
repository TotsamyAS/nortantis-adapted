package nortantis;

import nortantis.editor.River;
import nortantis.editor.RiverPathNode;
import nortantis.geom.Point;
import nortantis.geom.Rectangle;
import nortantis.graph.voronoi.Center;
import nortantis.graph.voronoi.Corner;
import nortantis.graph.voronoi.Edge;
import nortantis.platform.Color;
import nortantis.platform.DrawQuality;
import nortantis.platform.Image;
import nortantis.platform.Painter;
import nortantis.util.OrderlessPair;
import nortantis.util.Range;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

public class RiverDrawer
{
	/**
	 * Per-segment metadata strategy used by {@link PathOperations} when reversing or stitching river paths.
	 */
	public static final PathOperations.NodeMetadataOps<RiverPathNode> RIVER_OPS = new PathOperations.NodeMetadataOps<>()
	{
		@Override
		public RiverPathNode withClearedMetadata(RiverPathNode original)
		{
			return new RiverPathNode(original.getLoc(), 0, 0L);
		}

		@Override
		public RiverPathNode withMetadataFrom(RiverPathNode target, RiverPathNode donor)
		{
			return new RiverPathNode(target.getLoc(), donor.getWidthLevelToNext(), donor.getSeedToNext());
		}
	};

	/**
	 * Tolerance (in graph pixel space) for matching a river control point to a {@link Corner}'s location, used to detect polygon-style
	 * river segments lying exactly on a Voronoi edge. Mirrors the constant used by {@code MapCreator.applyRiverEdits}.
	 */
	private static final double cornerMatchTolerancePixels = 0.5;

	private final List<River> rivers;
	private final double resolutionScale;
	private final MapSettings.LineStyle lineStyle;
	private final Color riverColor;
	private final WorldGraph graph;

	public RiverDrawer(MapSettings settings, WorldGraph graph)
	{
		this.graph = graph;
		this.resolutionScale = settings.resolution;
		this.lineStyle = settings.lineStyle;
		this.riverColor = settings.riverColor;
		this.rivers = settings.edits != null && settings.edits.rivers != null ? settings.edits.rivers : Collections.emptyList();
	}

	public RiverDrawer(List<River> rivers, double resolutionScale, MapSettings.LineStyle lineStyle, WorldGraph graph)
	{
		this.graph = graph;
		this.resolutionScale = resolutionScale;
		this.lineStyle = lineStyle;
		this.rivers = rivers != null ? rivers : Collections.emptyList();
		this.riverColor = Color.black; // not used; callers of this constructor pass a color override to drawRivers(Painter, Color)
	}

	public void drawRivers(Image map, Rectangle drawBounds)
	{
		if (rivers.isEmpty())
		{
			return;
		}

		double jaggedAmplitudeRI = graph.getMeanCenterWidth() / 2.0 / resolutionScale;
		double minLengthRI = 2.0 / resolutionScale;
		Rectangle drawBoundsRI = drawBounds == null ? null
				: new Rectangle(drawBounds.x / resolutionScale, drawBounds.y / resolutionScale, drawBounds.width / resolutionScale, drawBounds.height / resolutionScale);

		try (Painter p = map.createPainter(DrawQuality.High))
		{
			if (drawBounds != null)
			{
				p.translate(-drawBounds.x, -drawBounds.y);
			}
			drawRiversWithPainter(p, drawBoundsRI, jaggedAmplitudeRI, minLengthRI, riverColor);
		}
	}

	/**
	 * Draws rivers onto an existing painter using the given color override. No translation or clipping is applied; the caller is
	 * responsible for the painter's transform. Pass {@code null} for {@code colorOverride} to use the settings river color.
	 */
	public void drawRivers(Painter p, Color colorOverride)
	{
		if (rivers.isEmpty())
		{
			return;
		}

		double jaggedAmplitudeRI = graph.getMeanCenterWidth() / 2.0 / resolutionScale;
		double minLengthRI = 2.0 / resolutionScale;
		drawRiversWithPainter(p, null, jaggedAmplitudeRI, minLengthRI, colorOverride != null ? colorOverride : riverColor);
	}

	private void drawRiversWithPainter(Painter p, Rectangle drawBoundsRI, double jaggedAmplitudeRI, double minLengthRI, Color color)
	{
		p.setColor(color);
		for (River river : rivers)
		{
			List<RiverPathNode> nodes = river.nodes;
			if (nodes.size() < 2)
			{
				continue;
			}
			if (drawBoundsRI != null && !PathOperations.pathOverlapsRectangle(nodes, drawBoundsRI, jaggedAmplitudeRI))
			{
				continue;
			}
			drawRiver(p, nodes, jaggedAmplitudeRI, minLengthRI);
		}
	}

	private void drawRiver(Painter p, List<RiverPathNode> nodes, double jaggedAmplitudeRI, double minLengthRI)
	{
		int numSegments = nodes.size() - 1;
		for (int i = 0; i < numSegments; i++)
		{
			RiverPathNode current = nodes.get(i);
			float currentWidth = calcRiverStrokeWidth(current.getWidthLevelToNext());
			float fromWidth = i == 0 ? findJunctionWidth(nodes, nodes.get(0).getLoc(), currentWidth) : calcRiverStrokeWidth(nodes.get(i - 1).getWidthLevelToNext());
			float toWidth = i == numSegments - 1 ? findJunctionWidth(nodes, nodes.get(numSegments).getLoc(), currentWidth) : calcRiverStrokeWidth(nodes.get(i + 1).getWidthLevelToNext());

			List<Point> segmentPathPixels = buildSegmentPathPixels(nodes, i, jaggedAmplitudeRI, minLengthRI);
			drawPathWithSmoothLineTransitions(p, segmentPathPixels, fromWidth, currentWidth, toWidth);
		}
	}

	private List<Point> buildSegmentPathPixels(List<RiverPathNode> nodes, int segmentIndex, double jaggedAmplitudeRI, double minLengthRI)
	{
		Point riStart = nodes.get(segmentIndex).getLoc();
		Point riEnd = nodes.get(segmentIndex + 1).getLoc();

		List<Point> pathRI;
		if (lineStyle == MapSettings.LineStyle.Jagged && jaggedAmplitudeRI > 0)
		{
			// Per-segment seed lives on the node, so adding/removing a control point doesn't shift
			// the randomness of unrelated segments.
			Random random = new Random(nodes.get(segmentIndex).getSeedToNext());
			pathRI = new ArrayList<>();
			pathRI.add(riStart);
			subdivideInterior(riStart, riEnd, jaggedAmplitudeRI, minLengthRI, random, pathRI);
			pathRI.add(riEnd);
		}
		else if (lineStyle != MapSettings.LineStyle.Jagged)
		{
			// Smooth curve segment using centripetal Catmull-Rom with neighbor control points.
			int last = nodes.size() - 1;
			Point p0 = segmentIndex == 0 ? riStart.add(riStart.subtract(riEnd)) : nodes.get(segmentIndex - 1).getLoc();
			Point p3 = segmentIndex == last - 1 ? riEnd.add(riEnd.subtract(riStart)) : nodes.get(segmentIndex + 2).getLoc();
			pathRI = CurveCreator.createCurve(p0, riStart, riEnd, p3, CurveCreator.defaultDistanceBetweenPoints / resolutionScale);
			// createCurve runs t < t2 so the endpoint may be missing; ensure it is present.
			if (pathRI.isEmpty() || !pathRI.get(pathRI.size() - 1).isCloseEnough(riEnd))
			{
				pathRI.add(riEnd);
			}
		}
		else
		{
			// Jagged with zero amplitude: straight segment.
			pathRI = List.of(riStart, riEnd);
		}

		return scaleToPixels(pathRI);
	}

	private List<Point> scaleToPixels(List<Point> pathRI)
	{
		List<Point> pixels = new ArrayList<>(pathRI.size());
		for (Point pt : pathRI)
		{
			pixels.add(new Point(pt.x * resolutionScale, pt.y * resolutionScale));
		}
		return pixels;
	}

	private float calcRiverStrokeWidth(int widthLevel)
	{
		return (float) (resolutionScale * Math.sqrt(widthLevel * 0.5));
	}

	/**
	 * At a junction where this river meets other rivers at {@code endpoint}, returns the stroke width this river should transition to at
	 * that endpoint. At multi-way junctions (more than two river segments), only the two rivers with the highest endpoint width levels
	 * participate in the smooth transition; all others return {@code defaultWidth}. Ties are broken by list insertion order.
	 */
	private float findJunctionWidth(List<RiverPathNode> currentNodes, Point endpoint, float defaultWidth)
	{
		int currentEndWidthLevel = getEndpointWidthLevel(currentNodes, endpoint);

		// Build junction entries using the list index as an ID. We use reference equality (==) so
		// that two rivers with identical content are still treated as distinct.
		int currentIndex = -1;
		List<int[]> junctionEntries = new ArrayList<>();
		for (int i = 0; i < rivers.size(); i++)
		{
			List<RiverPathNode> otherNodes = rivers.get(i).nodes;
			if (otherNodes == currentNodes)
			{
				currentIndex = i;
				junctionEntries.add(new int[] { currentEndWidthLevel, i });
			}
			else if (otherNodes.size() >= 2)
			{
				if (otherNodes.get(0).getLoc().isCloseEnough(endpoint))
				{
					junctionEntries.add(new int[] { otherNodes.get(0).getWidthLevelToNext(), i });
				}
				else if (otherNodes.get(otherNodes.size() - 1).getLoc().isCloseEnough(endpoint))
				{
					junctionEntries.add(new int[] { otherNodes.get(otherNodes.size() - 2).getWidthLevelToNext(), i });
				}
			}
		}

		if (junctionEntries.size() <= 1)
		{
			return defaultWidth;
		}

		junctionEntries.sort((a, b) -> a[0] != b[0] ? b[0] - a[0] : Integer.compare(a[1], b[1]));

		boolean currentIsFirst = junctionEntries.get(0)[1] == currentIndex;
		boolean currentIsSecond = junctionEntries.get(1)[1] == currentIndex;
		if (!currentIsFirst && !currentIsSecond)
		{
			return defaultWidth;
		}

		int partnerWidthLevel = currentIsFirst ? junctionEntries.get(1)[0] : junctionEntries.get(0)[0];
		return calcRiverStrokeWidth(partnerWidthLevel);
	}

	private int getEndpointWidthLevel(List<RiverPathNode> nodes, Point endpoint)
	{
		if (nodes.size() < 2)
		{
			return 0;
		}
		if (nodes.get(0).getLoc().isCloseEnough(endpoint))
		{
			return nodes.get(0).getWidthLevelToNext();
		}
		return nodes.get(nodes.size() - 2).getWidthLevelToNext();
	}

	/**
	 * Draws a path with stroke width that interpolates smoothly from {@code fromWidth} at the start to {@code currentWidth} at the midpoint
	 * to {@code toWidth} at the end. Mirrors {@code VoronoiGraph.drawPathWithSmoothLineTransitions} exactly.
	 *
	 * @param path
	 *            Points in pixel coordinates.
	 */
	private void drawPathWithSmoothLineTransitions(Painter p, List<Point> path, float fromWidth, float currentWidth, float toWidth)
	{
		if (path == null || path.size() < 2)
		{
			return;
		}

		float widthAtStart = (fromWidth + currentWidth) / 2f;
		float widthAtEnd = (toWidth + currentWidth) / 2f;
		float previousWidth = widthAtStart;
		List<Point> pathSoFar = new ArrayList<>();
		pathSoFar.add(path.get(0));

		float pathLength = getPathLength(path);
		float lengthSoFar = 0;

		for (int i = 1; i < path.size(); i++)
		{
			float width;
			float distanceRatio = lengthSoFar / pathLength;
			if (distanceRatio < 0.5f)
			{
				float ratio = distanceRatio * 2f;
				width = (1f - ratio) * widthAtStart + ratio * currentWidth;
			}
			else
			{
				float ratio = distanceRatio - 0.5f;
				width = (1f - ratio) * currentWidth + ratio * widthAtEnd;
			}

			pathSoFar.add(path.get(i));
			if (width != previousWidth)
			{
				p.setBasicStroke(width);
				drawPolyline(p, pathSoFar);
				pathSoFar = new ArrayList<>();
				pathSoFar.add(path.get(i));
				previousWidth = width;
			}

			lengthSoFar += (float) path.get(i - 1).distanceTo(path.get(i));
		}

		if (pathSoFar.size() > 1)
		{
			p.setBasicStroke(widthAtEnd);
			drawPolyline(p, pathSoFar);
		}
	}

	private float getPathLength(List<Point> path)
	{
		if (path.size() < 2)
		{
			return 0;
		}
		float length = 0;
		for (int i = 1; i < path.size(); i++)
		{
			length += (float) path.get(i - 1).distanceTo(path.get(i));
		}
		return length;
	}

	private void drawPolyline(Painter p, List<Point> line)
	{
		int[] xPoints = new int[line.size()];
		int[] yPoints = new int[line.size()];
		for (int i : new Range(line.size()))
		{
			xPoints[i] = (int) line.get(i).x;
			yPoints[i] = (int) line.get(i).y;
		}
		p.drawPolyline(xPoints, yPoints);
	}

	/**
	 * Recursively adds displaced midpoints between a and b to result. Neither a nor b are added by this method; the caller manages them.
	 */
	private void subdivideInterior(Point a, Point b, double W_level, double minLength, Random random, List<Point> result)
	{
		double segLength = a.distanceTo(b);
		if (segLength < minLength)
		{
			return;
		}

		double maxDisp = Math.min(W_level, segLength / 4.0);
		if (maxDisp <= 0)
		{
			return;
		}

		Point mid = new Point((a.x + b.x) / 2.0, (a.y + b.y) / 2.0);

		double dx = b.x - a.x;
		double dy = b.y - a.y;
		double len = Math.sqrt(dx * dx + dy * dy);
		double perpX = -dy / len;
		double perpY = dx / len;

		double displacement = (random.nextDouble() * 2 - 1) * maxDisp;
		Point displaced = new Point(mid.x + perpX * displacement, mid.y + perpY * displacement);

		double subW = W_level * 0.5;
		subdivideInterior(a, displaced, subW, minLength, random, result);
		result.add(displaced);
		subdivideInterior(displaced, b, subW, minLength, random, result);
	}

	/**
	 * Builds non-overlapping {@link River} objects from an unordered set of connected Voronoi edges, adds them to {@code rivers}, and
	 * returns the changed rivers (either newly added or existing rivers that were extended by joining). Edges whose corner-to-corner
	 * segment already appears in {@code rivers} are skipped, splitting the result into multiple rivers if necessary. Each resulting river
	 * is merged with any existing river whose endpoint it connects to. Analogous to {@link RoadDrawer#addRoadsFromEdgesInEditor}.
	 */
	public static List<River> addRiversFromEdgesInEditor(Set<Edge> edgeSet, Corner start, int riverLevel, double resolutionScale, List<River> rivers)
	{
		Set<OrderlessPair<Point>> existingConnections = PathOperations.collectAllConnections(riverNodesList(rivers));
		List<River> newRivers = buildRiversFromEdgeSet(edgeSet, start, riverLevel, resolutionScale, existingConnections);
		return connectAndAddRivers(newRivers, rivers);
	}

	/**
	 * Clips {@code pathRI} at ocean and lake centers (dropping water sub-paths so the river ends at the coastline), splits the remaining
	 * land sub-paths at any segments that already exist in {@code rivers}, adds the resulting sub-rivers to {@code rivers}, and returns the
	 * changed rivers. A river that passes through one or more bodies of water becomes multiple sub-rivers. Analogous to
	 * {@link RoadDrawer#addFreeHandRoadFromPoints}.
	 */
	public static List<River> addFreeHandRiverFromPoints(List<Point> pathRI, int riverLevel, List<River> rivers, nortantis.WorldGraph graph, double resolutionScale)
	{
		List<List<Point>> landPaths = clipPathAtWater(pathRI, graph, resolutionScale);
		if (landPaths.isEmpty())
		{
			return Collections.emptyList();
		}
		Set<OrderlessPair<Point>> existingConnections = PathOperations.collectAllConnections(riverNodesList(rivers));
		Random random = new Random();
		List<River> newRivers = new ArrayList<>();
		for (List<Point> landPath : landPaths)
		{
			for (List<Point> subPath : nortantis.util.GeometryHelper.splitPathAtOverlaps(landPath, existingConnections))
			{
				newRivers.add(River.withUniformWidth(subPath, riverLevel, random));
			}
		}
		return connectAndAddRivers(newRivers, rivers);
	}

	/**
	 * Splits {@code pathRI} at segments whose both endpoints fall on ocean or lake centers, dropping those fully-submerged segments.
	 * Segments with at least one endpoint on land are kept as-is, so a river that crosses water briefly without a user-placed point on
	 * water is preserved. Each returned sub-path has at least 2 points.
	 */
	private static List<List<Point>> clipPathAtWater(List<Point> pathRI, nortantis.WorldGraph graph, double resolutionScale)
	{
		if (pathRI.size() < 2)
		{
			return Collections.emptyList();
		}

		boolean[] isWater = new boolean[pathRI.size()];
		for (int i = 0; i < pathRI.size(); i++)
		{
			isWater[i] = isPointOnWater(pathRI.get(i), graph, resolutionScale, c -> c.isWater);
		}

		List<List<Point>> result = new ArrayList<>();
		List<Point> current = new ArrayList<>();
		for (int i = 0; i < pathRI.size() - 1; i++)
		{
			if (isWater[i] && isWater[i + 1])
			{
				if (current.size() >= 2)
				{
					result.add(current);
				}
				current = new ArrayList<>();
			}
			else
			{
				if (current.isEmpty())
				{
					current.add(pathRI.get(i));
				}
				current.add(pathRI.get(i + 1));
			}
		}
		if (current.size() >= 2)
		{
			result.add(current);
		}
		return result;
	}

	private static boolean isPointOnWater(Point pathPointRI, nortantis.WorldGraph graph, double resolutionScale, Predicate<Center> isWaterCenter)
	{
		Center c = graph.findClosestCenter(pathPointRI.mult(resolutionScale));
		return c != null && isWaterCenter.test(c);
	}

	/**
	 * Returns river segments that should be removed in response to the user painting ocean or lake. Two cases are detected:
	 * <ol>
	 * <li>Segments with both endpoints in water centers (per {@code isWaterCenter}) &mdash; reuses the same water test as the freehand
	 * draw-time clipping in {@link #clipPathAtWater}.</li>
	 * <li>Polygon-style segments whose endpoints exactly match the {@link Corner#loc} of a Voronoi edge that is now a coastline or
	 * lakeshore (one adjacent Center water, one land). Rivers do not follow coastlines in real life, so these are removed.</li>
	 * </ol>
	 * A per-river bounding-box test against the combined bounds of {@code paintedCenters} skips rivers far from the painted area, so this
	 * can run cheaply on every drag tick. Within rivers that pass the bbox test, every segment is examined &mdash; required to catch
	 * segments whose two endpoints sit in centers that were painted across separate drag events.
	 *
	 * <p>
	 * The returned segments are 2-point lists; callers feed them through {@link #removeSegmentsAndSplitRivers}.
	 *
	 * @param isWaterCenter
	 *            Predicate that returns true for water centers given the user's pending {@code centerEdits} &mdash; callers should consult
	 *            edits rather than the live {@code Center.isWater} field, which is not updated until the next redraw.
	 */
	public static List<List<Point>> findRiverSegmentsToRemoveForWaterPaint(List<River> rivers, nortantis.WorldGraph graph, double resolutionScale, Set<Center> paintedCenters,
			Predicate<Center> isWaterCenter)
	{
		List<List<Point>> result = new ArrayList<>();
		if (rivers == null || rivers.isEmpty() || paintedCenters == null || paintedCenters.isEmpty())
		{
			return result;
		}

		Rectangle paintedBoundsRI = computeCentersBoundsRI(paintedCenters, resolutionScale);
		if (paintedBoundsRI == null)
		{
			return result;
		}

		for (River river : rivers)
		{
			List<RiverPathNode> nodes = river.nodes;
			if (nodes.size() < 2)
			{
				continue;
			}

			Rectangle riverBoundsRI = computeNodeBoundsRI(nodes);
			if (!paintedBoundsRI.overlaps(riverBoundsRI))
			{
				continue;
			}

			for (int i = 0; i < nodes.size() - 1; i++)
			{
				Point pA = nodes.get(i).getLoc();
				Point pB = nodes.get(i + 1).getLoc();

				if (isPointOnWater(pA, graph, resolutionScale, isWaterCenter) && isPointOnWater(pB, graph, resolutionScale, isWaterCenter))
				{
					result.add(List.of(pA, pB));
					continue;
				}

				Edge coastEdge = findCoastEdgeMatchingSegment(pA, pB, graph, resolutionScale, isWaterCenter);
				if (coastEdge != null)
				{
					result.add(List.of(pA, pB));
				}
			}
		}
		return result;
	}

	private static Rectangle computeCentersBoundsRI(Set<Center> centers, double resolutionScale)
	{
		Rectangle bounds = null;
		for (Center c : centers)
		{
			for (Corner corner : c.corners)
			{
				double x = corner.loc.x / resolutionScale;
				double y = corner.loc.y / resolutionScale;
				bounds = bounds == null ? new Rectangle(x, y, 0, 0) : bounds.add(x, y);
			}
		}
		return bounds;
	}

	private static Rectangle computeNodeBoundsRI(List<RiverPathNode> nodes)
	{
		Rectangle bounds = new Rectangle(nodes.get(0).getLoc().x, nodes.get(0).getLoc().y, 0, 0);
		for (int i = 1; i < nodes.size(); i++)
		{
			bounds = bounds.add(nodes.get(i).getLoc());
		}
		return bounds;
	}

	private static Edge findCoastEdgeMatchingSegment(Point pA, Point pB, nortantis.WorldGraph graph, double resolutionScale, Predicate<Center> isWaterCenter)
	{
		Point pAPixel = pA.mult(resolutionScale);
		Point pBPixel = pB.mult(resolutionScale);
		Corner cornerA = graph.findClosestCorner(pAPixel);
		Corner cornerB = graph.findClosestCorner(pBPixel);
		if (cornerA == null || cornerB == null || cornerA == cornerB)
		{
			return null;
		}
		if (cornerA.loc.distanceTo(pAPixel) > cornerMatchTolerancePixels || cornerB.loc.distanceTo(pBPixel) > cornerMatchTolerancePixels)
		{
			return null;
		}
		for (Edge edge : cornerA.protrudes)
		{
			if (edge.v0 == cornerB || edge.v1 == cornerB)
			{
				if (edge.d0 != null && edge.d1 != null && isWaterCenter.test(edge.d0) != isWaterCenter.test(edge.d1))
				{
					return edge;
				}
				return null;
			}
		}
		return null;
	}

	/**
	 * Splits each river in {@code rivers} at any node whose location appears as an endpoint of one of {@code segmentsToRemove}. A river
	 * that becomes multiple pieces stays in the list as its first piece, with the additional pieces appended as new {@link River} objects.
	 *
	 * @return The rivers that changed (existing rivers whose nodes were replaced, plus any newly created pieces from splits).
	 */
	public static List<River> removeSegmentsAndSplitRivers(List<River> rivers, List<List<Point>> segmentsToRemove)
	{
		Set<Point> splitLocs = new HashSet<>();
		for (List<Point> seg : segmentsToRemove)
		{
			splitLocs.addAll(seg);
		}

		List<River> changed = new ArrayList<>();
		List<River> newRivers = new ArrayList<>();
		for (River river : rivers)
		{
			List<RiverPathNode> nodes = river.nodes;
			List<List<RiverPathNode>> splits = PathOperations.splitAtLocations(nodes, splitLocs);
			boolean unchanged = splits.size() == 1 && splits.get(0).size() == nodes.size();
			if (unchanged)
			{
				continue;
			}

			changed.add(river);
			if (splits.isEmpty())
			{
				river.nodes = new CopyOnWriteArrayList<>();
			}
			else
			{
				// Atomic swap: a single volatile write replaces the entire path so concurrent
				// readers (e.g. the background draw thread) never see a mid-modification state.
				river.nodes = new CopyOnWriteArrayList<>(splits.get(0));
				for (int i = 1; i < splits.size(); i++)
				{
					newRivers.add(new River(splits.get(i)));
				}
			}
		}

		rivers.addAll(newRivers);
		changed.addAll(newRivers);
		return changed;
	}

	/**
	 * For each river in {@code newRivers}, attempts to join it to an existing river in {@code rivers} whose endpoint matches. If joined,
	 * the existing river is extended and the new river is discarded (not added to {@code rivers}). If not joined, the new river is added to
	 * {@code rivers}. A second join attempt is made on the result in case the extended river can connect to yet another existing river.
	 *
	 * @return The changed rivers — either newly added rivers or existing rivers that were extended.
	 */
	private static List<River> connectAndAddRivers(List<River> newRivers, List<River> rivers)
	{
		List<River> changed = new ArrayList<>();
		for (River newRiver : newRivers)
		{
			River joined = tryConnectingRiverToExistingRiver(newRiver, rivers);
			if (joined != null)
			{
				River joined2 = tryConnectingRiverToExistingRiver(joined, rivers);
				if (joined2 != null)
				{
					rivers.remove(joined);
					changed.add(joined2);
				}
				else
				{
					changed.add(joined);
				}
			}
			else
			{
				rivers.add(newRiver);
				changed.add(newRiver);
			}
		}
		return changed;
	}

	/**
	 * Attempts to join {@code riverToAdd} to an existing river in {@code rivers} by matching endpoints. If a match is found, the existing
	 * river's nodes are replaced with the merged node list (a single atomic volatile-field swap so concurrent readers don't see partial
	 * state), {@code riverToAdd} is NOT added to {@code rivers}, and the extended existing river is returned. Returns {@code null} if no
	 * endpoint match is found.
	 */
	public static River tryConnectingRiverToExistingRiver(River riverToAdd, List<River> rivers)
	{
		if (riverToAdd == null || riverToAdd.nodes.size() < 2)
		{
			return null;
		}
		List<RiverPathNode> toAdd = riverToAdd.nodes;
		for (River river : rivers)
		{
			if (river == riverToAdd)
			{
				continue;
			}
			List<RiverPathNode> existing = river.nodes;
			if (existing.size() < 2)
			{
				continue;
			}
			List<RiverPathNode> merged = tryMergeEndpoints(existing, toAdd);
			if (merged != null)
			{
				river.nodes = new CopyOnWriteArrayList<>(merged);
				return river;
			}
		}
		return null;
	}

	private static List<RiverPathNode> tryMergeEndpoints(List<RiverPathNode> existing, List<RiverPathNode> toAdd)
	{
		PathOperations.ExistingPathAccessor<RiverPathNode> single = new PathOperations.ExistingPathAccessor<>()
		{
			@Override
			public int count()
			{
				return 1;
			}

			@Override
			public List<RiverPathNode> get(int index)
			{
				return existing;
			}

			@Override
			public void replace(int index, List<RiverPathNode> newNodes)
			{
			}
		};
		PathOperations.Match<RiverPathNode> match = PathOperations.tryConnectToExistingPath(toAdd, single, RIVER_OPS);
		return match == null ? null : match.mergedNodes;
	}

	public static void removeEmptyOrShortRivers(List<River> riverList)
	{
		riverList.removeIf(river -> river.nodes.size() < 2);
	}

	private static Iterable<List<RiverPathNode>> riverNodesList(List<River> rivers)
	{
		List<List<RiverPathNode>> result = new ArrayList<>(rivers.size());
		for (River river : rivers)
		{
			result.add(river.nodes);
		}
		return result;
	}

	/**
	 * Traverses {@code edgeSet} starting at {@code start}, building river(s) whose nodes lie on Voronoi corners in RI coordinates. Edges
	 * whose corner pair already appears in {@code existingConnections} are skipped (committing the current path and restarting from the far
	 * corner). New pairs are added to {@code existingConnections} as they are consumed.
	 */
	private static List<River> buildRiversFromEdgeSet(Set<Edge> edgeSet, Corner start, int riverLevel, double resolutionScale, Set<OrderlessPair<Point>> existingConnections)
	{
		List<River> result = new ArrayList<>();
		if (edgeSet.isEmpty())
		{
			return result;
		}

		Random random = new Random();
		Set<Edge> remaining = new HashSet<>(edgeSet);
		Corner current = start;
		List<Point> currentPath = new ArrayList<>();
		currentPath.add(current.loc.mult(1.0 / resolutionScale));

		while (!remaining.isEmpty())
		{
			Edge next = null;
			for (Edge edge : remaining)
			{
				if ((edge.v0 != null && edge.v0 == current) || (edge.v1 != null && edge.v1 == current))
				{
					next = edge;
					break;
				}
			}

			if (next == null)
			{
				if (currentPath.size() >= 2)
				{
					result.add(River.withUniformWidth(currentPath, riverLevel, random));
				}
				currentPath = new ArrayList<>();
				Edge anyEdge = remaining.iterator().next();
				current = anyEdge.v0 != null ? anyEdge.v0 : anyEdge.v1;
				currentPath.add(current.loc.mult(1.0 / resolutionScale));
			}
			else
			{
				remaining.remove(next);
				Corner nextCorner = (next.v0 != null && next.v0 == current) ? next.v1 : next.v0;
				if (nextCorner != null)
				{
					Point currentPointRI = currentPath.get(currentPath.size() - 1);
					Point nextPointRI = nextCorner.loc.mult(1.0 / resolutionScale);
					OrderlessPair<Point> pair = new OrderlessPair<>(currentPointRI, nextPointRI);
					if (existingConnections.contains(pair))
					{
						if (currentPath.size() >= 2)
						{
							result.add(River.withUniformWidth(currentPath, riverLevel, random));
						}
						currentPath = new ArrayList<>();
						currentPath.add(nextPointRI);
					}
					else
					{
						existingConnections.add(pair);
						currentPath.add(nextPointRI);
					}
					current = nextCorner;
				}
				else
				{
					current = null;
				}
			}
		}

		if (currentPath.size() >= 2)
		{
			result.add(River.withUniformWidth(currentPath, riverLevel, random));
		}
		return result;
	}
}
