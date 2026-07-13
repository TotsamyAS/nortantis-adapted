package nortantis.editor;

import nortantis.PathOperations;
import nortantis.geom.Point;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

public class RegionBoundary
{
	public final CopyOnWriteArrayList<RoadPathNode> nodes;
	public final CopyOnWriteArrayList<Integer> edgeIds;
	public final Integer sourceRegionId;
	public final Integer createdRegionId;

	public RegionBoundary(List<Point> locations, List<Integer> edgeIds, Integer sourceRegionId, Integer createdRegionId)
	{
		List<RoadPathNode> built = new ArrayList<>(locations.size());
		for (Point point : locations)
		{
			built.add(new RoadPathNode(point));
		}
		this.nodes = new CopyOnWriteArrayList<>(PathOperations.deduplicateConsecutive(built));
		this.edgeIds = new CopyOnWriteArrayList<>(edgeIds == null ? List.of() : edgeIds);
		this.sourceRegionId = sourceRegionId;
		this.createdRegionId = createdRegionId;
	}

	public RegionBoundary(RegionBoundary other)
	{
		this(PathOperations.toLocationList(other.nodes), other.edgeIds, other.sourceRegionId, other.createdRegionId);
	}

	@Override
	public int hashCode()
	{
		return Objects.hash(nodes, edgeIds, sourceRegionId, createdRegionId);
	}

	@Override
	public boolean equals(Object obj)
	{
		if (this == obj)
		{
			return true;
		}
		if (obj == null || getClass() != obj.getClass())
		{
			return false;
		}
		RegionBoundary other = (RegionBoundary) obj;
		return Objects.equals(nodes, other.nodes) && Objects.equals(edgeIds, other.edgeIds) && Objects.equals(sourceRegionId, other.sourceRegionId)
				&& Objects.equals(createdRegionId, other.createdRegionId);
	}
}
