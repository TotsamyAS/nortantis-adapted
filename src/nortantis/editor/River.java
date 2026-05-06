package nortantis.editor;

import nortantis.geom.Point;
import nortantis.util.Tuple2;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;

public class River
{
	/**
	 * Control points in the path are stored in a resolution-invariant way, meaning that changing the display quality in the editor does not
	 * change these values. The path has at least 2 points when fully drawn.
	 */
	public CopyOnWriteArrayList<Point> path;

	/**
	 * Width level (river level, same scale as {@code edge.river}) for each segment between consecutive path points. Length always equals
	 * {@code path.size() - 1}. Higher values produce wider strokes: stroke width = {@code sqrt(widthLevel * 0.5) * resolutionScale}.
	 */
	public CopyOnWriteArrayList<Integer> segmentWidthLevels;

	/**
	 * Seed for deterministic midpoint displacement when line style is Jagged. Fixed at creation so edits don't change the jaggedness
	 * character.
	 */
	public long noisyEdgesSeed;

	/**
	 * Creates a river from a path and matching per-segment width levels. The path is deduplicated of consecutive equal points.
	 *
	 * @param path
	 *            Control points in RI coordinates. Must have {@code segmentWidthLevels.size() == path.size() - 1}.
	 * @param segmentWidthLevels
	 *            River level per segment.
	 */
	public River(List<Point> path, List<Integer> segmentWidthLevels)
	{
		Tuple2<List<Point>, List<Integer>> deduped = deduplicateConsecutive(path, segmentWidthLevels);
		this.path = new CopyOnWriteArrayList<>(deduped.getFirst());
		this.segmentWidthLevels = new CopyOnWriteArrayList<>(deduped.getSecond());
		this.noisyEdgesSeed = new Random().nextLong();
	}

	public River(List<Point> path, List<Integer> segmentWidthLevels, long noisyEdgesSeed)
	{
		Tuple2<List<Point>, List<Integer>> deduped = deduplicateConsecutive(path, segmentWidthLevels);
		this.path = new CopyOnWriteArrayList<>(deduped.getFirst());
		this.segmentWidthLevels = new CopyOnWriteArrayList<>(deduped.getSecond());
		this.noisyEdgesSeed = noisyEdgesSeed;
	}

	/** Creates a river where every segment has the same width level. */
	public River(List<Point> path, int uniformWidthLevel)
	{
		this(path, Collections.nCopies(Math.max(0, path.size() - 1), uniformWidthLevel));
	}

	public River(List<Point> path, int uniformWidthLevel, long noisyEdgesSeed)
	{
		this(path, Collections.nCopies(Math.max(0, path.size() - 1), uniformWidthLevel), noisyEdgesSeed);
	}

	public River(River other)
	{
		this(other.path, other.segmentWidthLevels, other.noisyEdgesSeed);
	}

	/**
	 * Removes consecutive duplicate points and the corresponding segment width between them.
	 * Width at index {@code i} belongs to the segment from {@code path[i]} to {@code path[i+1]}.
	 * When {@code path[i]} is skipped as a duplicate, {@code widths[i-1]} (the zero-length segment) is also skipped,
	 * and the next non-duplicate point picks up {@code widths[i]} for the transition to the previous kept point.
	 */
	private static Tuple2<List<Point>, List<Integer>> deduplicateConsecutive(List<Point> path, List<Integer> widths)
	{
		List<Point> resultPath = new ArrayList<>(path.size());
		List<Integer> resultWidths = new ArrayList<>(Math.max(0, path.size() - 1));
		for (int i = 0; i < path.size(); i++)
		{
			Point point = path.get(i);
			if (resultPath.isEmpty() || !resultPath.get(resultPath.size() - 1).isCloseEnough(point))
			{
				if (!resultPath.isEmpty() && i > 0 && i - 1 < widths.size())
				{
					resultWidths.add(widths.get(i - 1));
				}
				resultPath.add(point);
			}
		}
		return new Tuple2<>(resultPath, resultWidths);
	}

	@Override
	public int hashCode()
	{
		return Objects.hash(path, noisyEdgesSeed, segmentWidthLevels);
	}

	@Override
	public boolean equals(Object obj)
	{
		if (this == obj)
		{
			return true;
		}
		if (obj == null)
		{
			return false;
		}
		if (getClass() != obj.getClass())
		{
			return false;
		}
		River other = (River) obj;
		return noisyEdgesSeed == other.noisyEdgesSeed && Objects.equals(segmentWidthLevels, other.segmentWidthLevels) && Objects.equals(path, other.path);
	}

	@Override
	public String toString()
	{
		return "River [path=" + path + ", noisyEdgesSeed=" + noisyEdgesSeed + ", segmentWidthLevels=" + segmentWidthLevels + "]";
	}
}
