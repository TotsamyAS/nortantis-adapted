package nortantis;

import nortantis.graph.voronoi.Corner;
import nortantis.graph.voronoi.Edge;

import java.util.*;

public class GraphRiver implements Iterable<Edge>
{
	public static final int RIVERS_THIS_SIZE_OR_SMALLER_WILL_NOT_BE_DRAWN = 2;
	/**
	 * Maximum river level, corresponding to the maximum drawable width in LandWaterTool's river width slider. Used to cap scaled river
	 * levels in sub-maps.
	 */
	public static final int MAX_RIVER_LEVEL = (14 * 14 * 2) + RIVERS_THIS_SIZE_OR_SMALLER_WILL_NOT_BE_DRAWN + 1;


	private List<Edge> edges;
	private int width;

	public GraphRiver()
	{
		edges = new ArrayList<>();
		width = 0;
	}

	public void add(Edge edge)
	{
		width = Math.max(width, edge.river);
		edges.add(edge);
	}

	public void addAll(GraphRiver other)
	{
		width = Math.max(width, other.width);
		edges.addAll(other.edges);
	}

	public void reverse()
	{
		Collections.reverse(edges);
	}

	public List<Edge> getEdges()
	{
		return Collections.unmodifiableList(edges);
	}

	@Override
	public Iterator<Edge> iterator()
	{
		return edges.iterator();
	}

	/**
	 * Returns the corners along this river in order, with one corner per edge endpoint. The list has {@code edges.size() + 1} entries:
	 * corners[i] and corners[i+1] are the two endpoints of edges[i].
	 */
	public List<Corner> getOrderedCorners()
	{
		List<Corner> result = new ArrayList<>(edges.size() + 1);
		if (edges.isEmpty())
		{
			return result;
		}

		Corner start;
		if (edges.size() == 1)
		{
			start = edges.get(0).v0;
		}
		else
		{
			// Determine the start corner: whichever corner of edges[0] is NOT shared with edges[1].
			Edge firstEdge = edges.get(0);
			Edge secondEdge = edges.get(1);
			if (firstEdge.v0 == secondEdge.v0 || firstEdge.v0 == secondEdge.v1)
			{
				start = firstEdge.v1;
			}
			else
			{
				start = firstEdge.v0;
			}
		}

		result.add(start);
		Corner prev = start;
		for (Edge e : edges)
		{
			Corner next = (e.v0 == prev) ? e.v1 : e.v0;
			result.add(next);
			prev = next;
		}
		return result;
	}

	public Set<Corner> getCorners()
	{
		Set<Corner> result = new HashSet<>();
		for (Edge e : edges)
		{
			if (e.v0 != null)
			{
				result.add(e.v0);
			}

			if (e.v1 != null)
			{
				result.add(e.v1);
			}
		}
		return result;
	}
}
