package nortantis;

import nortantis.editor.River;
import nortantis.geom.Point;
import nortantis.geom.Rectangle;
import nortantis.graph.voronoi.Corner;
import nortantis.graph.voronoi.Edge;
import nortantis.platform.Color;
import nortantis.platform.DrawQuality;
import nortantis.platform.Image;
import nortantis.platform.Painter;
import nortantis.util.GeometryHelper;
import nortantis.util.OrderlessPair;
import nortantis.util.Range;

import java.util.*;

public class RiverDrawer
{
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
	 * Draws rivers onto an existing painter using the given color override. No translation or clipping is applied; the caller is responsible
	 * for the painter's transform. Pass {@code null} for {@code colorOverride} to use the settings river color.
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
			if (river.path.size() < 2)
			{
				continue;
			}
			if (drawBoundsRI != null && !riverOverlapsRectangle(river, drawBoundsRI, jaggedAmplitudeRI))
			{
				continue;
			}
			drawRiver(p, river, jaggedAmplitudeRI, minLengthRI);
		}
	}

	private void drawRiver(Painter p, River river, double jaggedAmplitudeRI, double minLengthRI)
	{
		List<Point> controlPoints = river.path;
		List<Integer> widthLevels = river.segmentWidthLevels;
		int numSegments = controlPoints.size() - 1;

		for (int i = 0; i < numSegments; i++)
		{
			float currentWidth = calcRiverStrokeWidth(widthLevels.get(i));
			float fromWidth = i == 0
					? findJunctionWidth(river, controlPoints.get(0), currentWidth)
					: calcRiverStrokeWidth(widthLevels.get(i - 1));
			float toWidth = i == numSegments - 1
					? findJunctionWidth(river, controlPoints.get(numSegments), currentWidth)
					: calcRiverStrokeWidth(widthLevels.get(i + 1));

			List<Point> segmentPathPixels = buildSegmentPathPixels(river, i, controlPoints, jaggedAmplitudeRI, minLengthRI);
			drawPathWithSmoothLineTransitions(p, segmentPathPixels, fromWidth, currentWidth, toWidth);
		}
	}

	private List<Point> buildSegmentPathPixels(River river, int segmentIndex, List<Point> controlPoints, double jaggedAmplitudeRI, double minLengthRI)
	{
		Point riStart = controlPoints.get(segmentIndex);
		Point riEnd = controlPoints.get(segmentIndex + 1);

		List<Point> pathRI;
		if (lineStyle == MapSettings.LineStyle.Jagged && jaggedAmplitudeRI > 0)
		{
			// Per-segment seed derived from the river seed so different segments get independent randomness.
			Random random = new Random(river.noisyEdgesSeed + segmentIndex);
			pathRI = new ArrayList<>();
			pathRI.add(riStart);
			subdivideInterior(riStart, riEnd, jaggedAmplitudeRI, minLengthRI, random, pathRI);
			pathRI.add(riEnd);
		}
		else if (lineStyle != MapSettings.LineStyle.Jagged)
		{
			// Smooth curve segment using centripetal Catmull-Rom with neighbor control points.
			int last = controlPoints.size() - 1;
			Point p0 = segmentIndex == 0
					? riStart.add(riStart.subtract(riEnd))
					: controlPoints.get(segmentIndex - 1);
			Point p3 = segmentIndex == last - 1
					? riEnd.add(riEnd.subtract(riStart))
					: controlPoints.get(segmentIndex + 2);
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
	 * At a junction where {@code currentRiver} meets other rivers at {@code endpoint}, returns the stroke width {@code currentRiver} should
	 * transition to at that endpoint. At multi-way junctions (more than two river segments), only the two rivers with the highest endpoint
	 * width levels participate in the smooth transition; all others return {@code defaultWidth}. Ties are broken by list insertion order.
	 */
	private float findJunctionWidth(River currentRiver, Point endpoint, float defaultWidth)
	{
		int currentEndWidthLevel = getEndpointWidthLevel(currentRiver, endpoint);

		// Build junction entries using the list index as an ID. We use reference equality (==) rather than
		// River.equals() so that two rivers with identical content are still treated as distinct.
		int currentIndex = -1;
		List<int[]> junctionEntries = new ArrayList<>();
		for (int i = 0; i < rivers.size(); i++)
		{
			River other = rivers.get(i);
			if (other == currentRiver)
			{
				currentIndex = i;
				junctionEntries.add(new int[]{currentEndWidthLevel, i});
			}
			else if (other.path.size() >= 2 && !other.segmentWidthLevels.isEmpty())
			{
				if (other.path.get(0).isCloseEnough(endpoint))
				{
					junctionEntries.add(new int[]{other.segmentWidthLevels.get(0), i});
				}
				else if (other.path.get(other.path.size() - 1).isCloseEnough(endpoint))
				{
					junctionEntries.add(new int[]{other.segmentWidthLevels.get(other.segmentWidthLevels.size() - 1), i});
				}
			}
		}

		if (junctionEntries.size() <= 1)
		{
			return defaultWidth;
		}

		// Sort descending by width level; use list index (insertion order) as a deterministic tiebreaker.
		junctionEntries.sort((a, b) -> a[0] != b[0] ? b[0] - a[0] : Integer.compare(a[1], b[1]));

		// Only the top-2-by-width rivers at this junction get smooth width transitions.
		boolean currentIsFirst = junctionEntries.get(0)[1] == currentIndex;
		boolean currentIsSecond = junctionEntries.get(1)[1] == currentIndex;
		if (!currentIsFirst && !currentIsSecond)
		{
			return defaultWidth;
		}

		int partnerWidthLevel = currentIsFirst ? junctionEntries.get(1)[0] : junctionEntries.get(0)[0];
		return calcRiverStrokeWidth(partnerWidthLevel);
	}

	private int getEndpointWidthLevel(River river, Point endpoint)
	{
		if (river.segmentWidthLevels.isEmpty())
		{
			return 0;
		}
		if (river.path.get(0).isCloseEnough(endpoint))
		{
			return river.segmentWidthLevels.get(0);
		}
		return river.segmentWidthLevels.get(river.segmentWidthLevels.size() - 1);
	}

	private boolean riverOverlapsRectangle(River river, Rectangle boundsRI, double jaggedAmplitudeRI)
	{
		Rectangle expandedBounds = new Rectangle(boundsRI.x - jaggedAmplitudeRI, boundsRI.y - jaggedAmplitudeRI, boundsRI.width + 2 * jaggedAmplitudeRI, boundsRI.height + 2 * jaggedAmplitudeRI);
		List<Point> path = river.path;
		for (int i = 0; i < path.size() - 1; i++)
		{
			Point p1 = path.get(i);
			Point p2 = path.get(i + 1);
			Rectangle segBounds = Rectangle.fromCorners(Math.min(p1.x, p2.x), Math.min(p1.y, p2.y), Math.max(p1.x, p2.x), Math.max(p1.y, p2.y));
			if (expandedBounds.overlaps(segBounds))
			{
				return true;
			}
		}
		return false;
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
	 * Builds non-overlapping {@link River} objects from an unordered set of connected Voronoi edges, adds them to {@code rivers}, and returns
	 * the changed rivers (either newly added or existing rivers that were extended by joining). Edges whose corner-to-corner segment already
	 * appears in {@code rivers} are skipped, splitting the result into multiple rivers if necessary. Each resulting river is merged with any
	 * existing river whose endpoint it connects to. Analogous to {@link RoadDrawer#addRoadsFromEdgesInEditor}.
	 */
	public static List<River> addRiversFromEdgesInEditor(Set<Edge> edgeSet, Corner start, int riverLevel,
			double resolutionScale, List<River> rivers)
	{
		Set<OrderlessPair<Point>> existingConnections = collectRiverConnections(rivers);
		List<River> newRivers = buildRiversFromEdgeSet(edgeSet, start, riverLevel, resolutionScale, existingConnections);
		return connectAndAddRivers(newRivers, rivers);
	}

	/**
	 * Splits {@code pathRI} at any segments that already exist in {@code rivers}, adds the non-overlapping sub-rivers to {@code rivers}, and
	 * returns the changed rivers (either newly added or existing rivers that were extended by joining). Each resulting sub-river is merged with
	 * any existing river whose endpoint it connects to. Analogous to {@link RoadDrawer#addFreeHandRoadFromPoints}.
	 */
	public static List<River> addFreeHandRiverFromPoints(List<Point> pathRI, int riverLevel, List<River> rivers)
	{
		Set<OrderlessPair<Point>> existingConnections = collectRiverConnections(rivers);
		List<River> newRivers = splitRiverPathAtOverlaps(pathRI, riverLevel, existingConnections);
		return connectAndAddRivers(newRivers, rivers);
	}

	/**
	 * For each river in {@code newRivers}, attempts to join it to an existing river in {@code rivers} whose endpoint matches. If joined, the
	 * existing river is extended and the new river is discarded (not added to {@code rivers}). If not joined, the new river is added to
	 * {@code rivers}. A second join attempt is made on the result in case the extended river can connect to yet another existing river.
	 * Analogous to the joining logic in {@link RoadDrawer#addFreeHandRoadFromPoints}.
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
	 * Attempts to join {@code riverToAdd} to an existing river in {@code rivers} by matching endpoints. If a match is found, {@code riverToAdd}
	 * is merged into the existing river (the existing river's path and segment widths are extended; {@code riverToAdd} is NOT added to
	 * {@code rivers}), and the extended existing river is returned. Returns {@code null} if no endpoint match is found. Analogous to
	 * {@link RoadDrawer#tryConnectingRoadToExistingRoad}.
	 */
	public static River tryConnectingRiverToExistingRiver(River riverToAdd, List<River> rivers)
	{
		for (River river : rivers)
		{
			if (river.path.isEmpty())
			{
				continue;
			}
			if (river == riverToAdd)
			{
				continue;
			}

			if (river.path.get(0).isCloseEnough(riverToAdd.path.get(0)))
			{
				riverToAdd.path.remove(0);
				Collections.reverse(riverToAdd.path);
				Collections.reverse(riverToAdd.segmentWidthLevels);
				river.path.addAll(0, riverToAdd.path);
				river.segmentWidthLevels.addAll(0, riverToAdd.segmentWidthLevels);
				return river;
			}
			if (river.path.get(0).isCloseEnough(riverToAdd.path.get(riverToAdd.path.size() - 1)))
			{
				riverToAdd.path.remove(riverToAdd.path.size() - 1);
				river.path.addAll(0, riverToAdd.path);
				river.segmentWidthLevels.addAll(0, riverToAdd.segmentWidthLevels);
				return river;
			}
			if (river.path.get(river.path.size() - 1).isCloseEnough(riverToAdd.path.get(0)))
			{
				riverToAdd.path.remove(0);
				river.path.addAll(riverToAdd.path);
				river.segmentWidthLevels.addAll(riverToAdd.segmentWidthLevels);
				return river;
			}
			if (river.path.get(river.path.size() - 1).isCloseEnough(riverToAdd.path.get(riverToAdd.path.size() - 1)))
			{
				riverToAdd.path.remove(riverToAdd.path.size() - 1);
				Collections.reverse(riverToAdd.path);
				Collections.reverse(riverToAdd.segmentWidthLevels);
				river.path.addAll(riverToAdd.path);
				river.segmentWidthLevels.addAll(riverToAdd.segmentWidthLevels);
				return river;
			}
		}
		return null;
	}

	private static Set<OrderlessPair<Point>> collectRiverConnections(List<River> rivers)
	{
		Set<OrderlessPair<Point>> connections = new HashSet<>();
		for (River river : rivers)
		{
			for (int i = 0; i < river.path.size() - 1; i++)
			{
				connections.add(new OrderlessPair<>(river.path.get(i), river.path.get(i + 1)));
			}
		}
		return connections;
	}

	/**
	 * Traverses {@code edgeSet} starting at {@code start}, building path(s) in RI coordinates. Edges whose corner pair already appears in
	 * {@code existingConnections} are skipped (committing the current path and restarting from the far corner). New pairs are added to
	 * {@code existingConnections} as they are consumed.
	 */
	private static List<River> buildRiversFromEdgeSet(Set<Edge> edgeSet, Corner start, int riverLevel,
			double resolutionScale, Set<OrderlessPair<Point>> existingConnections)
	{
		List<River> result = new ArrayList<>();
		if (edgeSet.isEmpty())
		{
			return result;
		}

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
				// Gap: save current path and restart from an arbitrary remaining edge.
				if (currentPath.size() >= 2)
				{
					result.add(new River(currentPath, riverLevel));
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
						// Overlapping edge: commit current path and start fresh from nextCorner.
						if (currentPath.size() >= 2)
						{
							result.add(new River(currentPath, riverLevel));
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
			result.add(new River(currentPath, riverLevel));
		}
		return result;
	}

	private static List<River> splitRiverPathAtOverlaps(List<Point> path, int riverLevel,
			Set<OrderlessPair<Point>> existingConnections)
	{
		List<River> result = new ArrayList<>();
		for (List<Point> subPath : GeometryHelper.splitPathAtOverlaps(path, existingConnections))
		{
			result.add(new River(subPath, riverLevel));
		}
		return result;
	}

	public static void removeEmptyOrShortRivers(List<River> riverList)
	{
		riverList.removeIf(river -> river.path.size() < 2);
	}
}
