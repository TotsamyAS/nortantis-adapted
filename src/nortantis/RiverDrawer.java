package nortantis;

import nortantis.editor.River;
import nortantis.geom.Point;
import nortantis.geom.Rectangle;
import nortantis.platform.Color;
import nortantis.platform.DrawQuality;
import nortantis.platform.Image;
import nortantis.platform.Painter;
import nortantis.util.Range;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

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

		if (settings.edits != null && settings.edits.rivers != null)
		{
			this.rivers = settings.edits.rivers;
		}
		else
		{
			this.rivers = Collections.emptyList();
		}
	}

	public void drawRivers(Image map, Rectangle drawBounds)
	{
		if (rivers.isEmpty())
		{
			return;
		}

		// Amplitude of midpoint displacement for jagged rivers, in RI coordinates: half the mean Voronoi cell width.
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

			p.setColor(riverColor);

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
	}

	private void drawRiver(Painter p, River river, double jaggedAmplitudeRI, double minLengthRI)
	{
		List<Point> controlPoints = river.path;
		List<Integer> widthLevels = river.segmentWidthLevels;
		int numSegments = controlPoints.size() - 1;

		for (int i = 0; i < numSegments; i++)
		{
			float currentWidth = calcRiverStrokeWidth(widthLevels.get(i));
			float fromWidth = i == 0 ? currentWidth : (calcRiverStrokeWidth(widthLevels.get(i - 1)) + currentWidth) / 2f;
			float toWidth = i == numSegments - 1 ? currentWidth : (currentWidth + calcRiverStrokeWidth(widthLevels.get(i + 1))) / 2f;

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
	 * to {@code toWidth} at the end. Mirrors the behavior of {@code VoronoiGraph.drawPathWithSmoothLineTransitions} for polygon rivers.
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
}
