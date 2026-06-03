package nortantis;

import nortantis.editor.CenterEdit;
import nortantis.editor.CenterIcon;
import nortantis.editor.CenterIconType;
import nortantis.editor.CenterTrees;
import nortantis.editor.FreeIcon;
import nortantis.editor.RegionEdit;
import nortantis.editor.Road;
import nortantis.geom.Point;
import nortantis.geom.Rectangle;
import nortantis.graph.voronoi.Center;
import nortantis.graph.voronoi.Corner;
import nortantis.graph.voronoi.Edge;
import nortantis.platform.Font;
import nortantis.GraphRiver;
import nortantis.editor.River;
import nortantis.editor.RiverPathNode;
import nortantis.swing.MapEdits;

import nortantis.util.OrderlessPair;
import nortantis.util.Tuple2;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

/**
 * Creates a new MapSettings for a zoomed-in sub-map of an existing map. The sub-map inherits the original map's land/water shape, region
 * colors, text, icons, and roads within the selected area.
 */
public class SubMapCreator
{
	/**
	 * Creates a new MapSettings for a sub-map of the given original map.
	 *
	 * @param originalSettings
	 *            The original map settings.
	 * @param originalGraph
	 *            The original world graph (used for land/water lookup).
	 * @param selectionBoundsRI
	 *            The selection bounds in resolution-invariant (RI) coordinates.
	 * @param subMapWorldSize
	 *            The number of Voronoi polygons for the sub-map.
	 * @param originalResolution
	 *            The resolution at which originalGraph was created (i.e. the display quality scale), used to convert resolution-invariant
	 *            coordinates to originalGraph pixel coordinates.
	 * @return New MapSettings for the sub-map, with pre-populated edits.
	 */
	public static MapSettings createSubMapSettings(MapSettings originalSettings, WorldGraph originalGraph, Rectangle selectionBoundsRI, int subMapWorldSize, double originalResolution, long seed,
			boolean redistributeIcons)
	{
		// Compute new dimensions and world size.
		// The largest dimension of the sub-map matches the largest dimension of the original map.
		// Whichever axis of the selection box is larger gets that max value; the other is scaled proportionally.
		int maxOriginalDimension = Math.max(originalSettings.generatedWidth, originalSettings.generatedHeight);
		int newGenWidth;
		int newGenHeight;
		if (selectionBoundsRI.width >= selectionBoundsRI.height)
		{
			newGenWidth = maxOriginalDimension;
			newGenHeight = (int) Math.round((double) maxOriginalDimension * selectionBoundsRI.height / selectionBoundsRI.width);
		}
		else
		{
			newGenHeight = maxOriginalDimension;
			newGenWidth = (int) Math.round((double) maxOriginalDimension * selectionBoundsRI.width / selectionBoundsRI.height);
		}
		newGenWidth = Math.max(1, newGenWidth);
		newGenHeight = Math.max(1, newGenHeight);

		int newWorldSize = Math.max(1, Math.min(SettingsGenerator.maxWorldSize, subMapWorldSize));

		// Deep-copy original settings, override key fields.
		MapSettings newSettings = originalSettings.deepCopyExceptEdits();
		newSettings.randomSeed = seed;
		newSettings.generatedWidth = newGenWidth;
		newSettings.generatedHeight = newGenHeight;
		newSettings.worldSize = newWorldSize;
		newSettings.imageExportPath = null;
		newSettings.heightmapExportPath = null;
		// No rotation/flip on the sub-map.
		newSettings.rightRotationCount = 0;
		newSettings.flipHorizontally = false;
		newSettings.flipVertically = false;

		// Scale font sizes to keep text proportional to the visible features.
		//
		// zoomFactor: how much the selection is magnified relative to the original map — equals 1.0
		// when the sub-map covers the entire original, and grows as the selection shrinks.
		//
		// detailRatio: how many times more (or fewer) polygons the sub-map has compared to the
		// 1× equivalent (same polygon density as the source), matching the multiplier shown in the
		// SubMapDialog detail slider. At ratio=1 the polygon density is unchanged; at ratio=2 the
		// sub-map has twice as many polygons (more detail) so features are more finely divided and
		// text should be smaller relative to them.
		//
		// Combining both: fontScale = zoomFactor / pow(detailRatio, 0.25), clamped to [1.0, …] so fonts never
		// shrink below the source map's sizes, and capped at maxFontSize to prevent illegibly huge text.
		// The 0.25 exponent reduces the suppression effect so fonts stay larger at high polygon counts.
		double zoomFactor = (double) newGenWidth / selectionBoundsRI.width;
		double selectionArea = selectionBoundsRI.width * selectionBoundsRI.height;
		double originalMapArea = originalSettings.generatedWidth * (double) originalSettings.generatedHeight;
		double oneXWorldSize = originalSettings.worldSize * selectionArea / originalMapArea;
		double detailRatio = oneXWorldSize > 0 ? newWorldSize / oneXWorldSize : 1.0;
		double fontScale = Math.max(1.0, zoomFactor / Math.max(1.0, Math.pow(detailRatio, 0.25)));
		newSettings.titleFont = scaleFontSize(newSettings.titleFont, fontScale);
		newSettings.regionFont = scaleFontSize(newSettings.regionFont, fontScale);
		newSettings.mountainRangeFont = scaleFontSize(newSettings.mountainRangeFont, fontScale);
		newSettings.otherMountainsFont = scaleFontSize(newSettings.otherMountainsFont, fontScale);
		newSettings.citiesFont = scaleFontSize(newSettings.citiesFont, fontScale);
		newSettings.riverFont = scaleFontSize(newSettings.riverFont, fontScale);
		// Initialize fresh empty edits so createGraphForUnitTests will create elevation (isInitialized=false).
		newSettings.edits = new MapEdits();
		// The sub-map graph must be built at originalResolution so the RI ↔ pixel conversions used when transferring rivers and icons
		// (which divide graph-pixel coordinates by originalResolution) land in the correct sub-map RI space.
		newSettings.resolution = originalResolution;

		// Build the WorldGraph for the sub-map (to get center positions and count).
		// We call this with createElevationBiomesLakesAndRegions=false because land/water and icon placement will be determined by the
		// source map, not by a new, generated world.
		// This gives us the same Voronoi structure MapCreator will use when rendering (same seed, same params).
		WorldGraph newGraph = MapCreator.createGraph(newSettings, false);

		// For each new center, use majority/plurality voting to assign water/lake/region.
		MapEdits newEdits = new MapEdits();
		// Rivers are populated directly by transferRivers; prevent MapCreator from re-initializing them from the new graph.
		newEdits.hasInitializedRivers = true;
		Map<Integer, List<Integer>> originalRegionToNewCenters = buildCenterEdits(newGraph, originalGraph, originalSettings.edits, selectionBoundsRI, originalResolution, newEdits);

		// Propagate coast/corner flags now that isWater/isLake are set on all centers.
		// markLakes must run first so that updateCoastAndCornerFlags sees the correct isLake values
		// when computing numOcean (ocean = isWater && !isLake); otherwise lake-shore corners get
		// isCoast instead of isWater, which is semantically wrong even though the avoidCorner
		// predicate catches both.
		newGraph.markLakes();
		newGraph.updateCoastAndCornerFlags();

		// Build remaining MapEdits.

		transferRegionEdits(originalGraph, originalSettings.edits, originalRegionToNewCenters, newEdits);

		transferRivers(originalGraph, originalSettings.edits, newGraph, selectionBoundsRI, newEdits, originalResolution, redistributeIcons, newGenWidth, newGenHeight);

		transferText(originalSettings.edits, selectionBoundsRI, newEdits, newGenWidth, newGenHeight, fontScale);

		transferFreeIcons(originalSettings.edits, originalGraph, newGraph, selectionBoundsRI, originalResolution, newEdits, newGenWidth, newGenHeight, redistributeIcons, seed);
		newEdits.hasIconEdits = true;

		transferRoads(originalSettings.edits, selectionBoundsRI, newGenWidth, newGenHeight, newEdits);

		// Attach the new edits to the new settings.
		newSettings.edits = newEdits;

		return newSettings;
	}

	private static void transferRegionEdits(WorldGraph originalGraph, MapEdits originalEdits, Map<Integer, List<Integer>> originalRegionToNewCenters, MapEdits newEdits)
	{
		// Copy colors for all referenced original regionIds.
		for (Integer originalRegionId : originalRegionToNewCenters.keySet())
		{
			RegionEdit originalRegionEdit = originalEdits.regionEdits.get(originalRegionId);
			if (originalRegionEdit != null)
			{
				newEdits.regionEdits.put(originalRegionId, new RegionEdit(originalRegionId, originalRegionEdit.color));
			}
			else if (originalGraph.regions.containsKey(originalRegionId))
			{
				nortantis.platform.Color color = originalGraph.regions.get(originalRegionId).backgroundColor;
				newEdits.regionEdits.put(originalRegionId, new RegionEdit(originalRegionId, color));
			}
		}
	}

	private static void transferText(MapEdits originalEdits, Rectangle selectionBoundsRI, MapEdits newEdits, int newGenWidth, int newGenHeight, double zoomFactor)
	{
		// Copy MapText entries whose location falls inside selectionBoundsRI.
		newEdits.text = new CopyOnWriteArrayList<>();
		for (MapText text : originalEdits.text)
		{
			if (selectionBoundsRI.containsOrOverlaps(text.location))
			{
				MapText newText = text.deepCopy();
				newText.location = transformRIPoint(text.location, selectionBoundsRI, newGenWidth, newGenHeight);
				// Clear bounds since they'll be recomputed at the new resolution.
				newText.line1Bounds = null;
				newText.line2Bounds = null;
				if (newText.fontOverride != null)
				{
					newText.fontOverride = scaleFontSize(newText.fontOverride, zoomFactor);
				}
				newEdits.text.add(newText);
			}
		}
	}

	private static void transferRoads(MapEdits originalEdits, Rectangle selectionBoundsRI, int newGenWidth, int newGenHeight, MapEdits newEdits)
	{
		// Clip each road to the selection boundary, inserting intersection points where
		// segments cross the edge so roads reach the map border instead of stopping short.
		for (Road road : originalEdits.roads)
		{
			List<Point> roadLocations = PathOperations.toLocationList(road.nodes);
			for (List<Point> clippedPath : clipRoadPath(roadLocations, selectionBoundsRI, newGenWidth, newGenHeight))
			{
				newEdits.roads.add(Road.fromLocations(clippedPath));
			}
		}
	}

	/**
	 * For each center in {@code newGraph}, samples its loc and all Voronoi corners in original-graph space and uses majority/plurality
	 * voting to assign water, lake, and region. Populates {@code newEdits.centerEdits} and mutates {@code newCenter.isWater} /
	 * {@code newCenter.isLake} (required before {@code updateCoastAndCornerFlags}).
	 *
	 * @return A map from original region ID to the list of new center indices assigned to that region.
	 */
	private static Map<Integer, List<Integer>> buildCenterEdits(WorldGraph newGraph, WorldGraph originalGraph, MapEdits originalEdits, Rectangle selectionBoundsRI, double originalResolution,
			MapEdits newEdits)
	{
		Map<Integer, List<Integer>> originalRegionToNewCenters = new HashMap<>();

		for (Center newCenter : newGraph.centers)
		{
			// Build sample points: center loc + all Voronoi corners, mapped to original-graph pixel space.
			List<Point> samplePoints = new ArrayList<>(newCenter.corners.size() + 1);
			samplePoints.add(mapToOriginalGraphPoint(newCenter.loc, newGraph, selectionBoundsRI, originalResolution));
			for (Corner corner : newCenter.corners)
			{
				samplePoints.add(mapToOriginalGraphPoint(corner.loc, newGraph, selectionBoundsRI, originalResolution));
			}

			// Tally votes from the original map for each sample point.
			int waterVotes = 0;
			int lakeVotes = 0;
			Map<Integer, Integer> regionVotes = new HashMap<>();
			for (Point samplePoint : samplePoints)
			{
				Center originalCenter = originalGraph.findClosestCenter(samplePoint, false);
				boolean sampleIsWater, sampleIsLake;
				Integer sampleRegionId;
				if (originalCenter != null && originalEdits.centerEdits.containsKey(originalCenter.index))
				{
					CenterEdit originalCenterEdit = originalEdits.centerEdits.get(originalCenter.index);
					sampleIsWater = originalCenterEdit.isWater;
					sampleIsLake = originalCenterEdit.isLake;
					sampleRegionId = originalCenterEdit.regionId;
				}
				else if (originalCenter != null)
				{
					sampleIsWater = originalCenter.isWater;
					sampleIsLake = originalCenter.isLake;
					sampleRegionId = originalCenter.region != null ? originalCenter.region.id : null;
				}
				else
				{
					sampleIsWater = true;
					sampleIsLake = false;
					sampleRegionId = null;
				}
				if (sampleIsWater)
					waterVotes++;
				if (sampleIsLake)
					lakeVotes++;
				if (sampleRegionId != null)
					regionVotes.merge(sampleRegionId, 1, Integer::sum);
			}

			// Majority vote: ≥50% water samples → water; ≥50% of water samples are lake → lake.
			boolean isWater = waterVotes * 2 >= samplePoints.size();
			boolean isLake = isWater && waterVotes > 0 && lakeVotes * 2 >= waterVotes;
			// Plurality vote for region: the region with the most sample-point votes wins.
			Integer regionId = null;
			int maxVotes = 0;
			for (Map.Entry<Integer, Integer> e : regionVotes.entrySet())
			{
				if (e.getValue() > maxVotes)
				{
					maxVotes = e.getValue();
					regionId = e.getKey();
				}
			}

			// Apply to the new center (required before updateCoastAndCornerFlags).
			newCenter.isWater = isWater;
			newCenter.isLake = isLake;

			if (regionId != null)
			{
				originalRegionToNewCenters.computeIfAbsent(regionId, k -> new ArrayList<>()).add(newCenter.index);
			}
			newEdits.centerEdits.put(newCenter.index, new CenterEdit(newCenter.index, isWater, isLake, regionId, null, null));
		}

		return originalRegionToNewCenters;
	}

	/**
	 * Transfers free icons from the original edits into {@code newEdits}. Cities and decorations are always copied by position. Mountains,
	 * hills, sand, and trees are either redistributed by center (if {@code redistributeIcons}) or copied by position.
	 */
	private static void transferFreeIcons(MapEdits originalEdits, WorldGraph originalGraph, WorldGraph newGraph, Rectangle selectionBoundsRI, double originalResolution, MapEdits newEdits,
			int newGenWidth, int newGenHeight, boolean redistributeIcons, long seed)
	{
		// Cities and decorations always copy by position, regardless of redistributeIcons.
		// They must be copied before redistribution so that redistribution can skip their centers.
		for (FreeIcon icon : originalEdits.freeIcons)
		{
			if (icon.type != IconType.cities && icon.type != IconType.decorations)
			{
				continue;
			}
			if (selectionBoundsRI.containsOrOverlaps(icon.locationResolutionInvariant))
			{
				Point newLoc = transformRIPoint(icon.locationResolutionInvariant, selectionBoundsRI, newGenWidth, newGenHeight);
				Integer newCenterIndex = null;
				if (icon.centerIndex != null)
				{
					Point newGraphPoint = new Point(newLoc.x * originalResolution, newLoc.y * originalResolution);
					Center nearestNewCenter = newGraph.findClosestCenter(newGraphPoint, false);
					if (nearestNewCenter != null)
					{
						newCenterIndex = nearestNewCenter.index;
					}
				}
				newEdits.freeIcons.addOrReplace(new FreeIcon(newLoc, icon.scale, icon.type, icon.artPack, icon.groupId, icon.iconIndex, icon.iconName, newCenterIndex, icon.density, icon.fillColor,
						icon.filterColor, icon.maximizeOpacity, icon.fillWithColor, icon.originalScale));
			}
		}

		if (redistributeIcons)
		{
			// Redistribute mountains, hills, sand, and trees based on per-center mapping.
			redistributeIconsByCenter(originalGraph, originalEdits, newGraph, selectionBoundsRI, originalResolution, newEdits, seed);
		}
		else
		{
			// Copy mountains, hills, sand, and trees by position (original behavior).
			for (FreeIcon icon : originalEdits.freeIcons)
			{
				if (icon.type == IconType.cities || icon.type == IconType.decorations)
				{
					continue;
				}
				if (selectionBoundsRI.containsOrOverlaps(icon.locationResolutionInvariant))
				{
					Point newLoc = transformRIPoint(icon.locationResolutionInvariant, selectionBoundsRI, newGenWidth, newGenHeight);
					newEdits.freeIcons.addOrReplace(new FreeIcon(newLoc, icon.scale, icon.type, icon.artPack, icon.groupId, icon.iconIndex, icon.iconName, null, icon.density, icon.fillColor,
							icon.filterColor, icon.maximizeOpacity, icon.fillWithColor, icon.originalScale));
				}
			}
		}
	}

	/**
	 * Redistributes mountains, hills, sand, and trees across the new graph's centers.
	 * <p>
	 * <b>Non-tree icons (mountains, hills, sand)</b> use a two-step approach:
	 * <ol>
	 * <li>Direct mapping: each original icon within the selection is placed at the nearest new center as a CenterIcon, so that IconDrawer
	 * will correctly compute position and scale for the new graph during rendering.</li>
	 * <li>Zoom-in expansion: for new centers that still have no icon after step 1, the new center's loc is mapped back to the original
	 * graph to check if that original center had an icon. If so, a CenterIcon with a random iconIndex is placed. This adds extra icons when
	 * the sub-map has more polygons than the original segment, preserving per-polygon density.</li>
	 * </ol>
	 * Centers that already have a non-tree icon (e.g. a city placed earlier) are always skipped.
	 * </p>
	 * <p>
	 * <b>Trees</b>: for each new center, the original center at its loc is found. If that original center has a {@code CenterTrees}
	 * (including dormant ones), it is copied to the new center with a fresh random seed. If there is no {@code CenterTrees} but the
	 * original center has visible tree FreeIcons, a {@code CenterTrees} is derived from those icons. Direct loc mapping naturally preserves
	 * density at any zoom level: many new centers that map to the same original tree center each receive their own {@code CenterTrees}, and
	 * IconDrawer handles per-polygon placement during rendering.
	 * </p>
	 */
	private static void redistributeIconsByCenter(WorldGraph originalGraph, MapEdits originalEdits, WorldGraph newGraph, Rectangle selectionBoundsRI, double originalResolution, MapEdits newEdits,
			long seed)
	{
		// --- Non-tree icons: Step 1 — direct position mapping. ---
		// For each original mountain/hill/sand icon within the selection, find the nearest new center and
		// place a CenterIcon there. Using CenterIcon (rather than FreeIcon) lets IconDrawer correctly
		// compute position (including the mountain Y offset) and scale for the new graph during rendering.
		for (FreeIcon icon : originalEdits.freeIcons)
		{
			if (icon.type == IconType.trees || icon.type == IconType.cities || icon.type == IconType.decorations)
			{
				continue;
			}
			if (!selectionBoundsRI.containsOrOverlaps(icon.locationResolutionInvariant))
			{
				continue;
			}
			// Use the original center's loc as the reference point rather than the icon's drawn position.
			// Mountain icons are offset upward from the polygon base by getAnchoredMountainDrawPoint, so
			// icon.locationResolutionInvariant is above the polygon centroid. Using it would select a new
			// center that is higher than the ones step 2 assigns, causing the step 1 mountain to appear
			// noticeably higher. Using the original center's centroid aligns step 1 with step 2's mapping.
			Point referenceRI;
			if (icon.centerIndex != null && icon.centerIndex < originalGraph.centers.size())
			{
				Center originalCenter = originalGraph.centers.get(icon.centerIndex);
				referenceRI = new Point(originalCenter.loc.x / originalResolution, originalCenter.loc.y / originalResolution);
			}
			else
			{
				referenceRI = icon.locationResolutionInvariant;
			}
			double newGraphX = (referenceRI.x - selectionBoundsRI.x) / selectionBoundsRI.width * newGraph.bounds.width;
			double newGraphY = (referenceRI.y - selectionBoundsRI.y) / selectionBoundsRI.height * newGraph.bounds.height;
			Center nearestNew = newGraph.findClosestCenter(new Point(newGraphX, newGraphY), false);
			if (nearestNew == null)
			{
				continue;
			}
			if (newEdits.freeIcons.getNonTree(nearestNew.index) != null)
			{
				continue; // city already there
			}
			CenterEdit nearestCenterEdit = newEdits.centerEdits.get(nearestNew.index);
			if (nearestCenterEdit == null || nearestCenterEdit.isWater || nearestCenterEdit.icon != null)
			{
				continue;
			}
			CenterIcon centerIcon = new CenterIcon(IconDrawer.iconTypeToCenterIconType(icon.type), icon.artPack, icon.groupId, icon.iconIndex);
			newEdits.centerEdits.put(nearestNew.index, nearestCenterEdit.copyWithIcon(centerIcon));
		}

		// Build lookup for step 2: original center index → non-tree FreeIcons (mountains, hills, sand).
		Map<Integer, List<FreeIcon>> originalCenterToIcons = new HashMap<>();
		for (FreeIcon icon : originalEdits.freeIcons)
		{
			if (icon.type == IconType.cities || icon.type == IconType.decorations || icon.type == IconType.trees)
			{
				continue;
			}
			int originalCenterIndex;
			if (icon.centerIndex != null)
			{
				originalCenterIndex = icon.centerIndex;
			}
			else
			{
				Point scaledPoint = new Point(icon.locationResolutionInvariant.x * originalResolution, icon.locationResolutionInvariant.y * originalResolution);
				Center nearest = originalGraph.findClosestCenter(scaledPoint, false);
				if (nearest == null)
				{
					continue;
				}
				originalCenterIndex = nearest.index;
			}
			originalCenterToIcons.computeIfAbsent(originalCenterIndex, k -> new ArrayList<>()).add(icon);
		}

		// Build lookup for tree redistribution: original center index → CenterTrees (includes dormant trees).
		// This is the primary source; visible tree FreeIcons are the fallback for centers whose CenterTrees
		// was cleared after being converted to FreeIcons.
		Map<Integer, CenterTrees> originalCenterToCenterTrees = new HashMap<>();
		for (Map.Entry<Integer, CenterEdit> entry : originalEdits.centerEdits.entrySet())
		{
			if (entry.getValue().trees != null)
			{
				originalCenterToCenterTrees.put(entry.getKey(), entry.getValue().trees);
			}
		}

		// Fallback: build lookup for visible tree FreeIcons on centers with no CenterTrees.
		Map<Integer, List<FreeIcon>> originalCenterToTreeIcons = new HashMap<>();
		for (FreeIcon icon : originalEdits.freeIcons)
		{
			if (icon.type != IconType.trees)
			{
				continue;
			}
			int originalCenterIndex;
			if (icon.centerIndex != null)
			{
				originalCenterIndex = icon.centerIndex;
			}
			else
			{
				Point scaledPoint = new Point(icon.locationResolutionInvariant.x * originalResolution, icon.locationResolutionInvariant.y * originalResolution);
				Center nearest = originalGraph.findClosestCenter(scaledPoint, false);
				if (nearest == null)
				{
					continue;
				}
				originalCenterIndex = nearest.index;
			}
			if (!originalCenterToCenterTrees.containsKey(originalCenterIndex))
			{
				originalCenterToTreeIcons.computeIfAbsent(originalCenterIndex, k -> new ArrayList<>()).add(icon);
			}
		}

		boolean hasNonTreeData = !originalCenterToIcons.isEmpty();
		boolean hasTreeData = !originalCenterToCenterTrees.isEmpty() || !originalCenterToTreeIcons.isEmpty();

		for (Center newCenter : newGraph.centers)
		{
			CenterEdit existingEdit = newEdits.centerEdits.get(newCenter.index);
			if (existingEdit != null && existingEdit.isWater)
			{
				continue;
			}

			Point locationInOriginalSpace = mapToOriginalGraphPoint(newCenter.loc, newGraph, selectionBoundsRI, originalResolution);
			Center originalCenterAtLocation = (hasNonTreeData || hasTreeData) ? originalGraph.findClosestCenter(locationInOriginalSpace, false) : null;

			// --- Non-tree icons: Step 2 — zoom-in expansion. ---
			// For new centers not yet assigned by step 1, check whether their loc maps to an original
			// center that had an icon. If so, place a CenterIcon with a random iconIndex from the same
			// group, letting IconDrawer handle positioning and scaling during rendering.
			if (hasNonTreeData && newEdits.freeIcons.getNonTree(newCenter.index) == null && (existingEdit == null || existingEdit.icon == null) && originalCenterAtLocation != null)
			{
				List<FreeIcon> iconsAtLocation = originalCenterToIcons.get(originalCenterAtLocation.index);
				if (iconsAtLocation != null)
				{
					FreeIcon icon = iconsAtLocation.get(0);
					int randomIconIndex = new Random(seed + newCenter.index).nextInt(Integer.MAX_VALUE);
					CenterIcon centerIcon = new CenterIcon(IconDrawer.iconTypeToCenterIconType(icon.type), icon.artPack, icon.groupId, randomIconIndex);
					newEdits.centerEdits.put(newCenter.index, existingEdit != null ? existingEdit.copyWithIcon(centerIcon) : new CenterEdit(newCenter.index, false, false, null, centerIcon, null));
					existingEdit = newEdits.centerEdits.get(newCenter.index);
				}
			}

			// --- Trees: direct mapping from original center. ---
			// Copy CenterTrees (including dormant) from the original center at this location. IconDrawer
			// naturally places trees at the right density for the new polygon sizes during rendering.
			// Fallback: if the original center has visible tree FreeIcons but no CenterTrees, derive
			// CenterTrees from those icons.
			if (hasTreeData && originalCenterAtLocation != null)
			{
				CenterTrees originalTrees = originalCenterToCenterTrees.get(originalCenterAtLocation.index);
				if (originalTrees != null)
				{
					CenterTrees newTrees = new CenterTrees(originalTrees.artPack, originalTrees.treeType, originalTrees.density, seed + newCenter.index, originalTrees.isDormant);
					CenterEdit current = newEdits.centerEdits.get(newCenter.index);
					if (current != null)
					{
						newEdits.centerEdits.put(newCenter.index, current.copyWithTrees(newTrees));
					}
				}
				else
				{
					List<FreeIcon> treeFreeIcons = originalCenterToTreeIcons.get(originalCenterAtLocation.index);
					if (treeFreeIcons != null && !treeFreeIcons.isEmpty())
					{
						String artPack = treeFreeIcons.get(0).artPack;
						String treeType = treeFreeIcons.get(0).groupId;
						double avgDensity = treeFreeIcons.stream().mapToDouble(t -> t.density).average().getAsDouble();
						CenterTrees newTrees = new CenterTrees(artPack, treeType, avgDensity, seed + newCenter.index);
						CenterEdit current = newEdits.centerEdits.get(newCenter.index);
						if (current != null)
						{
							newEdits.centerEdits.put(newCenter.index, current.copyWithTrees(newTrees));
						}
					}
				}
			}
		}
	}

	/**
	 * Maps a point from new-graph pixel space to original-graph pixel space.
	 */
	private static Point mapToOriginalGraphPoint(Point newGraphPoint, WorldGraph newGraph, Rectangle selectionBoundsRI, double originalResolution)
	{
		double originalX = (newGraphPoint.x / newGraph.bounds.width * selectionBoundsRI.width + selectionBoundsRI.x) * originalResolution;
		double originalY = (newGraphPoint.y / newGraph.bounds.height * selectionBoundsRI.height + selectionBoundsRI.y) * originalResolution;
		return new Point(originalX, originalY);
	}

	/**
	 * Transfers rivers from the original map into {@code newEdits.rivers}.
	 * <p>
	 * <b>This is intended, load-bearing behavior — do not collapse the two branches.</b> {@code redistributeIcons} mirrors the detail-level
	 * choice in {@link nortantis.swing.SubMapDialog}: it is {@code false} for "Match source detail" and {@code true} for the custom
	 * "Choose" detail level (more polygons than the source). The two cases deliberately differ:
	 * </p>
	 * <ul>
	 * <li><b>Match source detail ({@code redistributeIcons == false}):</b> each river path is clipped to the selection and transformed
	 * directly into sub-map RI coordinates (the same coordinate transform used for roads), reproducing the original river faithfully as a
	 * freehand {@link River} polyline. The source path is authoritative and is never reshaped or dropped here.</li>
	 * <li><b>Choose / custom detail ({@code redistributeIcons == true}):</b> rivers are <em>redistributed</em> — each clipped sub-path is
	 * re-routed through the sub-map's finer graph via {@link #routeClippedRiverToSubMap} so they follow the new polygon topology, matching
	 * how icons are redistributed across the new polygons in this mode. This is why the Choose detail level looks different from a
	 * magnified copy.</li>
	 * </ul>
	 * <p>
	 * Either way {@link #removeDuplicateRiverSegments} trims overlaps where arms meet at a confluence.
	 * </p>
	 */
	private static void transferRivers(WorldGraph originalGraph, MapEdits originalEdits, WorldGraph newGraph, Rectangle selectionBoundsRI, MapEdits newEdits, double originalResolution,
			boolean redistributeIcons, int newGenWidth, int newGenHeight)
	{
		if (originalEdits.rivers.isEmpty())
		{
			return;
		}

		double riverLevelScale = computeRiverLevelScale(originalGraph, originalResolution, selectionBoundsRI, newGraph);

		if (!redistributeIcons)
		{
			// Match source detail: copy each river over exactly, clipped to the selection and transformed into sub-map coordinates. The
			// river is reproduced faithfully — its shape is preserved and it is never dropped, even where it runs into ocean or lakes. (No
			// loop removal here: the source path is authoritative, and a complex source river can legitimately revisit points.)
			for (River river : originalEdits.rivers)
			{
				newEdits.rivers.addAll(clipRiverPath(river, selectionBoundsRI, newGenWidth, newGenHeight, riverLevelScale));
			}
		}
		else
		{
			// Choose / custom detail: redistribute rivers by re-routing them through the new, finer graph.
			if (DebugFlags.highlightSubMapRiverWaypoints())
			{
				DebugFlags.clearSubMapRiverWaypointCornerIndexes();
			}
			for (River river : originalEdits.rivers)
			{
				// A source endpoint is a coastal mouth if it lies inside the selection and is adjacent to water in the
				// source map. (An endpoint that exits via a selection boundary is a map-edge exit, not a mouth, and is
				// excluded by the contains() check.) Only the original river's true endpoints can be mouths — interior
				// clip boundaries are map-edge exits — so the first clipped sub-path's start and the last sub-path's end
				// are the only candidates.
				Point sourceFirst = river.nodes.get(0).getLoc();
				Point sourceLast = river.nodes.get(river.nodes.size() - 1).getLoc();
				boolean sourceStartIsMouth = selectionBoundsRI.contains(sourceFirst.x, sourceFirst.y) && isRIPointAdjacentToWater(sourceFirst, originalGraph, originalEdits, originalResolution);
				boolean sourceEndIsMouth = selectionBoundsRI.contains(sourceLast.x, sourceLast.y) && isRIPointAdjacentToWater(sourceLast, originalGraph, originalEdits, originalResolution);

				List<River> clippedSubPaths = clipRiverPath(river, selectionBoundsRI, newGenWidth, newGenHeight, riverLevelScale);
				for (int s = 0; s < clippedSubPaths.size(); s++)
				{
					boolean startIsMouth = s == 0 && sourceStartIsMouth;
					boolean endIsMouth = s == clippedSubPaths.size() - 1 && sourceEndIsMouth;
					River routedRiver = routeClippedRiverToSubMap(clippedSubPaths.get(s), startIsMouth, endIsMouth, newGraph, newEdits, originalResolution);
					if (routedRiver != null)
					{
						newEdits.rivers.add(routedRiver);
					}
				}
			}
		}

		removeDuplicateRiverSegments(newEdits.rivers);
	}

	/**
	 * Removes duplicated segments where two transferred rivers overlap. Rivers are routed through the new graph independently, so where a
	 * tributary meets a trunk its first or last segment can land on the same pair of corners the trunk already passes through. Drawn as two
	 * overlapping Catmull-Rom curves, such a duplicate renders as a small loop at the confluence. Trimming the overlapping segment from the
	 * tributary's end leaves the two rivers sharing a single confluence corner, which is the correct topology.
	 */
	private static void removeDuplicateRiverSegments(List<River> rivers)
	{
		// Count how many rivers use each undirected segment (keyed by its endpoint locations).
		Map<OrderlessPair<Point>, Integer> segmentCounts = new HashMap<>();
		for (River river : rivers)
		{
			List<RiverPathNode> nodes = river.nodes;
			for (int i = 0; i + 1 < nodes.size(); i++)
			{
				segmentCounts.merge(new OrderlessPair<>(nodes.get(i).getLoc(), nodes.get(i + 1).getLoc()), 1, Integer::sum);
			}
		}

		// Trim each river's leading and trailing segments while they are shared with another river. A shared segment is interior to exactly
		// one river (the trunk) and terminal in the other (the tributary), so trimming ends removes the duplicate from the tributary only,
		// never from the trunk. Decrementing as we trim keeps the surviving copy.
		for (River river : rivers)
		{
			List<RiverPathNode> nodes = river.nodes;
			while (nodes.size() >= 2 && isSegmentShared(segmentCounts, nodes.get(0).getLoc(), nodes.get(1).getLoc()))
			{
				decrementSegment(segmentCounts, nodes.get(0).getLoc(), nodes.get(1).getLoc());
				nodes.remove(0);
			}
			while (nodes.size() >= 2 && isSegmentShared(segmentCounts, nodes.get(nodes.size() - 2).getLoc(), nodes.get(nodes.size() - 1).getLoc()))
			{
				decrementSegment(segmentCounts, nodes.get(nodes.size() - 2).getLoc(), nodes.get(nodes.size() - 1).getLoc());
				nodes.remove(nodes.size() - 1);
			}
		}

		// Drop any river trimmed below a drawable length.
		rivers.removeIf(river -> river.nodes.size() < 2);
	}

	private static boolean isSegmentShared(Map<OrderlessPair<Point>, Integer> segmentCounts, Point a, Point b)
	{
		Integer count = segmentCounts.get(new OrderlessPair<>(a, b));
		return count != null && count > 1;
	}

	private static void decrementSegment(Map<OrderlessPair<Point>, Integer> segmentCounts, Point a, Point b)
	{
		segmentCounts.merge(new OrderlessPair<>(a, b), -1, Integer::sum);
	}

	/**
	 * Computes a scale factor for river levels when transferring from the original graph to the sub-map.
	 * <p>
	 * Rivers should appear proportionally wider when zoomed in. Width ∝ sqrt(riverLevel), so scaling width by zoomFactor requires scaling
	 * level by zoomFactor². When the sub-map has higher polygon density than a 1× equivalent (detailRatio > 1), rivers are widened less,
	 * matching the same attenuation used for font scaling in transferText. The floor of 1.0 ensures rivers are never narrower in the
	 * sub-map than in the source.
	 * </p>
	 */
	private static double computeRiverLevelScale(WorldGraph originalGraph, double originalResolution, Rectangle selectionBoundsRI, WorldGraph newGraph)
	{
		double originalRIWidth = originalGraph.getWidth() / originalResolution;
		double originalRIHeight = originalGraph.getHeight() / originalResolution;
		double maxOriginalDim = Math.max(originalRIWidth, originalRIHeight);
		double maxSelectionDim = Math.max(selectionBoundsRI.width, selectionBoundsRI.height);
		double zoomFactor = maxSelectionDim > 0 ? maxOriginalDim / maxSelectionDim : 1.0;
		double originalMapArea = originalRIWidth * originalRIHeight;
		double selectionArea = selectionBoundsRI.width * selectionBoundsRI.height;
		double oneXWorldSize = originalMapArea > 0 ? originalGraph.centers.size() * selectionArea / originalMapArea : 1.0;
		double detailRatio = oneXWorldSize > 0 ? newGraph.centers.size() / oneXWorldSize : 1.0;
		return Math.max(1.0, zoomFactor * zoomFactor / Math.max(1.0, Math.pow(detailRatio, 0.5)));
	}

	/**
	 * Maximum ratio of a routed leg's length to the straight-line distance between its two waypoints before the leg is replaced by a direct
	 * freehand hop. A greedy route that wanders far past this ratio is detouring (e.g. around a coastal mouth it cannot reach, or across a
	 * gap where the source isthmus vanished in the finer graph); hopping straight is both simpler and closer to the source river's intent.
	 */
	private static final double riverLegLengthCapRatio = 3.0;

	/**
	 * Re-routes one clipped sub-path of a source river through the new graph as a single freehand {@link River}.
	 * <p>
	 * The sub-path's nodes are in sub-map RI coordinates with per-segment width levels (from {@link #clipRiverPath}). Each node is snapped
	 * to the closest new-graph corner to form an ordered list of <em>waypoints</em>; a start/end node that is a coastal mouth is snapped
	 * instead to the nearest water-adjacent corner so the river reliably reaches the sea. Consecutive duplicate waypoints, and waypoints
	 * whose corner was already used earlier in this river, are dropped so the waypoint list stays simple.
	 * </p>
	 * <p>
	 * Each consecutive waypoint pair is then routed with {@link WorldGraph#findPathGreedy}, avoiding coast/ocean/water corners <em>and</em>
	 * every corner already used by an earlier leg of this river (except the current leg's start). Because each greedy result is a simple
	 * path and later legs cannot reuse earlier corners, the concatenation is simple by construction — no finger-pruning or loop-closing
	 * cleanup is needed. If a leg is unroutable or wanders past {@link #riverLegLengthCapRatio} times the straight-line distance, the two
	 * waypoints are connected by a direct freehand hop instead (this also subsumes the old {@code attachMouths} pass: a mouth corner the
	 * greedy router cannot reach is connected with a short hop, exactly a river's final approach to the sea).
	 * </p>
	 * Returns the built {@link River}, or {@code null} if fewer than two distinct waypoints survive.
	 */
	private static River routeClippedRiverToSubMap(River clippedSubPath, boolean startIsMouth, boolean endIsMouth, WorldGraph newGraph, MapEdits newEdits, double resolution)
	{
		List<RiverPathNode> clippedNodes = clippedSubPath.nodes;
		if (clippedNodes.size() < 2)
		{
			return null;
		}

		// --- Snap each clipped node to a new-graph corner to form the waypoint list. ---
		Predicate<Corner> isWaterAdjacent = c -> isNewCornerAdjacentToWater(c, newEdits);
		List<Corner> waypoints = new ArrayList<>();
		// Width level for the leg leaving each waypoint, taken from the clipped node it was snapped from.
		List<Integer> waypointWidths = new ArrayList<>();
		Set<Corner> waypointCorners = new HashSet<>();
		for (int i = 0; i < clippedNodes.size(); i++)
		{
			RiverPathNode node = clippedNodes.get(i);
			// The clipped node is in sub-map RI; RI × resolution is new-graph pixel space.
			Point newGraphPoint = node.getLoc().mult(resolution);
			boolean isMouthNode = (i == 0 && startIsMouth) || (i == clippedNodes.size() - 1 && endIsMouth);
			Corner corner;
			if (isMouthNode)
			{
				corner = findClosestCornerMatching(newGraph.corners, newGraphPoint, isWaterAdjacent);
				if (corner == null)
				{
					corner = newGraph.findClosestCorner(newGraphPoint);
				}
			}
			else
			{
				corner = newGraph.findClosestCorner(newGraphPoint);
			}
			if (corner == null)
			{
				continue;
			}
			// Drop an interior waypoint that snapped onto a coast/ocean/water corner. The greedy router refuses to route
			// through such corners (only a start/end mouth is allowed to sit on one), so keeping it as an intermediate forces
			// the search into a dead-end turn followed by a pair of freehand hops, which renders as a tight swirl. Skipping it
			// lets the leg route cleanly from the previous inland waypoint to the next. The first and last waypoints are always
			// kept: they are the river's true endpoints (a coastal mouth or a map-edge exit).
			boolean isEndpoint = i == 0 || i == clippedNodes.size() - 1;
			if (!isEndpoint && (corner.isCoast || corner.isOcean || corner.isWater))
			{
				continue;
			}
			// Collapse consecutive duplicates, and skip a corner already used earlier in this river so the path stays simple
			// even if the source sub-path revisits a location (redistribute mode may clean source loops, unlike exact-copy).
			if (!waypoints.isEmpty() && waypoints.get(waypoints.size() - 1).equals(corner))
			{
				continue;
			}
			if (waypointCorners.contains(corner))
			{
				continue;
			}
			waypoints.add(corner);
			waypointCorners.add(corner);
			waypointWidths.add(node.getWidthLevelToNext());
			if (DebugFlags.highlightSubMapRiverWaypoints())
			{
				DebugFlags.addSubMapRiverWaypointCornerIndex(corner.index);
			}
		}

		if (waypoints.size() < 2)
		{
			return null;
		}

		// --- Route each consecutive waypoint pair, building one simple corner sequence. ---
		List<Corner> pathCorners = new ArrayList<>();
		// Edge index and width level for the segment leaving pathCorners[i]; the final node has no outgoing segment.
		List<Integer> pathEdgeIndices = new ArrayList<>();
		List<Integer> pathWidths = new ArrayList<>();
		Set<Corner> usedCorners = new HashSet<>();
		pathCorners.add(waypoints.get(0));
		usedCorners.add(waypoints.get(0));

		boolean truncated = false;
		for (int k = 0; k + 1 < waypoints.size() && !truncated; k++)
		{
			Corner legStart = pathCorners.get(pathCorners.size() - 1);
			Corner legEnd = waypoints.get(k + 1);
			int legWidth = waypointWidths.get(k);
			if (legStart.equals(legEnd))
			{
				continue;
			}

			// Avoid water, every corner already used by an earlier leg, and every other waypoint corner, except this leg's
			// own start (so the search can leave it) and end. Blocking the other waypoints as intermediates is what keeps
			// each waypoint touched exactly once, as a leg endpoint: without it a greedy leg could overshoot through a later
			// waypoint (e.g. a coastal mouth), and the leg that should terminate there would then re-enter the path and lose
			// that endpoint. findPathGreedy always allows reaching the destination, so the leg's own water-adjacent mouth end
			// stays reachable.
			final Corner legStartFinal = legStart;
			final Corner legEndFinal = legEnd;
			Predicate<Corner> avoid = c -> (c.isCoast || c.isOcean || c.isWater || usedCorners.contains(c) || waypointCorners.contains(c)) && !c.equals(legStartFinal) && !c.equals(legEndFinal);
			Set<Edge> legEdges = newGraph.findPathGreedy(legStart, legEnd, avoid, null);

			List<Corner> legCorners = reconstructLegCorners(legEdges, legStart, legEnd);
			boolean useDirectHop = legCorners == null || cornerPathLength(legCorners) > riverLegLengthCapRatio * legStart.loc.distanceTo(legEnd.loc);

			if (useDirectHop)
			{
				// Connect the two waypoints directly (no Voronoi edge), covering unreachable coastal mouths and vanished isthmuses.
				if (usedCorners.contains(legEnd))
				{
					truncated = true;
					break;
				}
				pathCorners.add(legEnd);
				pathEdgeIndices.add(RiverPathNode.EDGE_INDEX_NONE);
				pathWidths.add(legWidth);
				usedCorners.add(legEnd);
			}
			else
			{
				for (int ci = 1; ci < legCorners.size(); ci++)
				{
					Corner to = legCorners.get(ci);
					// Safety net: a leg that re-enters the existing path (a rare overshoot through a later waypoint) would
					// create a loop. Stop the river at the previous corner rather than close the loop.
					if (usedCorners.contains(to))
					{
						truncated = true;
						break;
					}
					Edge edge = findEdgeBetween(legEdges, legCorners.get(ci - 1), to);
					pathCorners.add(to);
					pathEdgeIndices.add(edge != null ? edge.index : RiverPathNode.EDGE_INDEX_NONE);
					pathWidths.add(legWidth);
					usedCorners.add(to);
				}
			}
		}

		if (pathCorners.size() < 2)
		{
			return null;
		}

		// --- Build the River from the concatenated corner sequence. ---
		List<RiverPathNode> nodes = new ArrayList<>(pathCorners.size());
		Random random = new Random();
		for (int i = 0; i < pathCorners.size(); i++)
		{
			Point ri = pathCorners.get(i).loc.mult(1.0 / resolution);
			if (i + 1 < pathCorners.size())
			{
				nodes.add(new RiverPathNode(ri, pathWidths.get(i), random.nextLong(), pathEdgeIndices.get(i)));
			}
			else
			{
				nodes.add(new RiverPathNode(ri, 0, 0L, RiverPathNode.EDGE_INDEX_NONE));
			}
		}
		return new River(nodes);
	}

	/**
	 * Reconstructs the ordered corner sequence of a single routed leg from the unordered {@code legEdges} returned by
	 * {@link WorldGraph#findPathGreedy}, walking from {@code legStart} to {@code legEnd}. The greedy result is a simple back-pointer chain,
	 * so the walk visits each corner once. Returns {@code [legStart, …, legEnd]}, or {@code null} if {@code legEdges} is empty or does not
	 * form a connected chain from start to end.
	 */
	private static List<Corner> reconstructLegCorners(Set<Edge> legEdges, Corner legStart, Corner legEnd)
	{
		if (legEdges.isEmpty())
		{
			return null;
		}
		List<Corner> corners = new ArrayList<>();
		corners.add(legStart);
		Set<Corner> visited = new HashSet<>();
		visited.add(legStart);
		Corner current = legStart;
		while (!current.equals(legEnd))
		{
			Corner next = null;
			for (Edge e : legEdges)
			{
				Corner neighbor = edgeOtherCorner(e, current);
				if (neighbor != null && !visited.contains(neighbor))
				{
					next = neighbor;
					break;
				}
			}
			if (next == null)
			{
				return null;
			}
			corners.add(next);
			visited.add(next);
			current = next;
		}
		return corners;
	}

	/**
	 * Returns the edge in {@code edges} connecting corners {@code a} and {@code b}, or {@code null} if none does.
	 */
	private static Edge findEdgeBetween(Set<Edge> edges, Corner a, Corner b)
	{
		for (Edge e : edges)
		{
			if (e.v0 != null && e.v1 != null && ((e.v0.equals(a) && e.v1.equals(b)) || (e.v0.equals(b) && e.v1.equals(a))))
			{
				return e;
			}
		}
		return null;
	}

	/**
	 * Returns the total straight-line length along the given corner sequence.
	 */
	private static double cornerPathLength(List<Corner> corners)
	{
		double total = 0;
		for (int i = 1; i < corners.size(); i++)
		{
			total += corners.get(i - 1).loc.distanceTo(corners.get(i).loc);
		}
		return total;
	}


	/**
	 * Clips a river's RI-coordinate path to the selection rectangle, inserting intersection points at the boundary where segments cross it.
	 * Returns a list of {@link River} sub-paths (each with ≥ 2 points) in new-map RI coordinates, with width levels scaled by
	 * {@code riverLevelScale} and capped at {@link GraphRiver#MAX_RIVER_LEVEL}.
	 * <p>
	 * Edge indices on the original river's {@link RiverPathNode}s are intentionally <em>not</em> propagated to the result: the sub-map has
	 * its own independent {@link WorldGraph} so original edge indices would point at the wrong edges (or out of range).
	 * {@link River#fromLocationsAndWidths} creates nodes with {@link RiverPathNode#EDGE_INDEX_NONE}, which is the correct value here — the
	 * resulting sub-map river is a freehand polyline that {@link nortantis.RiverDrawer} renders directly.
	 * </p>
	 */
	private static List<River> clipRiverPath(River river, Rectangle selectionBounds, int newWidth, int newHeight, double riverLevelScale)
	{
		List<River> result = new ArrayList<>();
		List<RiverPathNode> nodes = river.nodes;
		if (nodes.isEmpty())
			return result;

		List<Point> currentPath = new ArrayList<>();
		List<Integer> currentWidths = new ArrayList<>();
		boolean prevInside = selectionBounds.contains(nodes.get(0).getLoc());
		if (prevInside)
			currentPath.add(transformRIPoint(nodes.get(0).getLoc(), selectionBounds, newWidth, newHeight));

		for (int i = 1; i < nodes.size(); i++)
		{
			Point prev = nodes.get(i - 1).getLoc();
			Point curr = nodes.get(i).getLoc();
			int rawWidth = nodes.get(i - 1).getWidthLevelToNext();
			int scaledWidth = Math.min(GraphRiver.MAX_RIVER_LEVEL, (int) Math.round(rawWidth * riverLevelScale));
			boolean currInside = selectionBounds.contains(curr);

			if (prevInside && currInside)
			{
				currentPath.add(transformRIPoint(curr, selectionBounds, newWidth, newHeight));
				currentWidths.add(scaledWidth);
			}
			else if (prevInside && !currInside)
			{
				Point exit = segmentBoundaryIntersection(prev, curr, selectionBounds);
				if (exit != null)
				{
					currentPath.add(transformRIPoint(exit, selectionBounds, newWidth, newHeight));
					currentWidths.add(scaledWidth);
				}
				if (currentPath.size() >= 2)
					result.add(River.fromLocationsAndWidths(currentPath, currentWidths));
				currentPath = new ArrayList<>();
				currentWidths = new ArrayList<>();
			}
			else if (!prevInside && currInside)
			{
				Point entry = segmentBoundaryIntersection(prev, curr, selectionBounds);
				if (entry != null)
					currentPath.add(transformRIPoint(entry, selectionBounds, newWidth, newHeight));
				currentPath.add(transformRIPoint(curr, selectionBounds, newWidth, newHeight));
				currentWidths.add(scaledWidth);
			}
			else
			{
				// Both outside: the segment may still pass through the rectangle.
				Optional<Tuple2<Point, Point>> through = segmentThroughIntersections(prev, curr, selectionBounds);
				if (through.isPresent())
				{
					List<Point> subPath = new ArrayList<>();
					List<Integer> subWidths = new ArrayList<>();
					subPath.add(transformRIPoint(through.get().getFirst(), selectionBounds, newWidth, newHeight));
					subPath.add(transformRIPoint(through.get().getSecond(), selectionBounds, newWidth, newHeight));
					subWidths.add(scaledWidth);
					result.add(River.fromLocationsAndWidths(subPath, subWidths));
				}
			}
			prevInside = currInside;
		}

		if (currentPath.size() >= 2)
			result.add(River.fromLocationsAndWidths(currentPath, currentWidths));

		return result;
	}

	/**
	 * Returns true if the nearest original-graph corner to {@code riPoint} is adjacent to a water center (using originalEdits where
	 * present, falling back to the center's own flag). Used to determine intentional water proximity at river endpoints.
	 */
	private static boolean isRIPointAdjacentToWater(Point riPoint, WorldGraph originalGraph, MapEdits originalEdits, double originalResolution)
	{
		Point pixelPoint = new Point(riPoint.x * originalResolution, riPoint.y * originalResolution);
		Corner corner = originalGraph.findClosestCorner(pixelPoint);
		return corner != null && isSourceCornerAdjacentToWater(corner, originalEdits);
	}

	/**
	 * Returns the corner on the opposite end of {@code e} from {@code corner}, or {@code null} if {@code corner} is not an endpoint of
	 * {@code e}.
	 */
	private static Corner edgeOtherCorner(Edge e, Corner corner)
	{
		if (e.v0 != null && e.v0.equals(corner))
			return e.v1;
		if (e.v1 != null && e.v1.equals(corner))
			return e.v0;
		return null;
	}

	/**
	 * Returns true if any center adjacent to {@code sourceCorner} is water (using originalEdits where present, otherwise the center's own
	 * flag).
	 */
	private static boolean isSourceCornerAdjacentToWater(Corner sourceCorner, MapEdits originalEdits)
	{
		for (Center c : sourceCorner.touches)
		{
			CenterEdit ce = originalEdits.centerEdits.get(c.index);
			boolean isWater = ce != null ? ce.isWater : c.isWater;
			if (isWater)
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Returns true if any center adjacent to {@code newCorner} is water according to newEdits (falling back to the center's own flag).
	 */
	private static boolean isNewCornerAdjacentToWater(Corner newCorner, MapEdits newEdits)
	{
		for (Center c : newCorner.touches)
		{
			CenterEdit ce = newEdits.centerEdits.get(c.index);
			boolean isWater = ce != null ? ce.isWater : c.isWater;
			if (isWater)
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Returns the closest corner in {@code corners} to {@code target} that satisfies {@code predicate}, or {@code null} if none match.
	 */
	private static Corner findClosestCornerMatching(List<Corner> corners, Point target, Predicate<Corner> predicate)
	{
		double bestDist = Double.MAX_VALUE;
		Corner bestCorner = null;
		for (Corner corner : corners)
		{
			if (predicate.test(corner))
			{
				double dx = corner.loc.x - target.x;
				double dy = corner.loc.y - target.y;
				double dist = dx * dx + dy * dy;
				if (dist < bestDist)
				{
					bestDist = dist;
					bestCorner = corner;
				}
			}
		}
		return bestCorner;
	}

	private static final float maxFontSize = 240f;

	/**
	 * Returns a copy of the given font with its size multiplied by {@code factor}, capped at {@link #maxFontSize}.
	 */
	private static Font scaleFontSize(Font font, double factor)
	{
		float newSize = Math.min(maxFontSize, (float) (font.getSize() * factor));
		return font.deriveFont(font.getStyle(), newSize);
	}

	/**
	 * Transforms a point from original RI space to new RI space.
	 */
	private static Point transformRIPoint(Point sourcePointRI, Rectangle selectionBoundsRI, int newGenWidth, int newGenHeight)
	{
		double newX = (sourcePointRI.x - selectionBoundsRI.x) / selectionBoundsRI.width * newGenWidth;
		double newY = (sourcePointRI.y - selectionBoundsRI.y) / selectionBoundsRI.height * newGenHeight;
		return new Point(newX, newY);
	}

	/**
	 * Clips a road's RI-coordinate path to the selection rectangle, inserting intersection points at the boundary where segments cross it.
	 * Returns a list of sub-paths (each with >= 2 points) in new-map RI coordinates, ready to become Road objects.
	 */
	private static List<List<Point>> clipRoadPath(List<Point> path, Rectangle selectionBounds, int newWidth, int newHeight)
	{
		List<List<Point>> result = new ArrayList<>();
		if (path.isEmpty())
		{
			return result;
		}

		List<Point> current = new ArrayList<>();
		boolean prevInside = selectionBounds.contains(path.get(0));
		if (prevInside)
		{
			current.add(transformRIPoint(path.get(0), selectionBounds, newWidth, newHeight));
		}

		for (int i = 1; i < path.size(); i++)
		{
			Point prev = path.get(i - 1);
			Point curr = path.get(i);
			boolean currInside = selectionBounds.contains(curr);

			if (prevInside && currInside)
			{
				current.add(transformRIPoint(curr, selectionBounds, newWidth, newHeight));
			}
			else if (prevInside && !currInside)
			{
				// Exiting: add exit intersection at the boundary, then close current sub-path.
				Point exit = segmentBoundaryIntersection(prev, curr, selectionBounds);
				if (exit != null)
				{
					current.add(transformRIPoint(exit, selectionBounds, newWidth, newHeight));
				}
				if (current.size() >= 2)
				{
					result.add(new ArrayList<>(current));
				}
				current.clear();
			}
			else if (!prevInside && currInside)
			{
				// Entering: start a new sub-path from the entry intersection.
				Point entry = segmentBoundaryIntersection(prev, curr, selectionBounds);
				if (entry != null)
				{
					current.add(transformRIPoint(entry, selectionBounds, newWidth, newHeight));
				}
				current.add(transformRIPoint(curr, selectionBounds, newWidth, newHeight));
			}
			else
			{
				// Both outside: the segment may still pass through the rectangle.
				Optional<Tuple2<Point, Point>> throughPoints = segmentThroughIntersections(prev, curr, selectionBounds);
				if (throughPoints.isPresent())
				{
					List<Point> subPath = new ArrayList<>();
					subPath.add(transformRIPoint(throughPoints.get().getFirst(), selectionBounds, newWidth, newHeight));
					subPath.add(transformRIPoint(throughPoints.get().getSecond(), selectionBounds, newWidth, newHeight));
					result.add(subPath);
				}
			}

			prevInside = currInside;
		}

		if (current.size() >= 2)
		{
			result.add(current);
		}
		return result;
	}

	/**
	 * When both endpoints of segment P1→P2 are outside {@code rect}, finds the two boundary intersection points (ordered from P1 to P2) if
	 * the segment passes through the rectangle. Returns empty if there are fewer than two distinct intersections.
	 */
	private static Optional<Tuple2<Point, Point>> segmentThroughIntersections(Point p1, Point p2, Rectangle rect)
	{
		double dx = p2.x - p1.x;
		double dy = p2.y - p1.y;
		List<double[]> hits = new ArrayList<>(); // each entry: { t, x, y }

		// Left edge
		if (dx != 0)
		{
			double t = (rect.x - p1.x) / dx;
			if (t > 0 && t < 1)
			{
				double y = p1.y + t * dy;
				if (y >= rect.y && y <= rect.getBottom())
				{
					hits.add(new double[] { t, rect.x, y });
				}
			}
		}

		// Right edge
		if (dx != 0)
		{
			double t = (rect.getRight() - p1.x) / dx;
			if (t > 0 && t < 1)
			{
				double y = p1.y + t * dy;
				if (y >= rect.y && y <= rect.getBottom())
				{
					hits.add(new double[] { t, rect.getRight(), y });
				}
			}
		}

		// Top edge
		if (dy != 0)
		{
			double t = (rect.y - p1.y) / dy;
			if (t > 0 && t < 1)
			{
				double x = p1.x + t * dx;
				if (x >= rect.x && x <= rect.getRight())
				{
					hits.add(new double[] { t, x, rect.y });
				}
			}
		}

		// Bottom edge
		if (dy != 0)
		{
			double t = (rect.getBottom() - p1.y) / dy;
			if (t > 0 && t < 1)
			{
				double x = p1.x + t * dx;
				if (x >= rect.x && x <= rect.getRight())
				{
					hits.add(new double[] { t, x, rect.getBottom() });
				}
			}
		}

		hits.sort((a, b) -> Double.compare(a[0], b[0]));
		if (hits.size() >= 2)
		{
			double[] first = hits.get(0);
			double[] last = hits.get(hits.size() - 1);
			return Optional.of(new Tuple2<>(new Point(first[1], first[2]), new Point(last[1], last[2])));
		}
		return Optional.empty();
	}

	/**
	 * Returns the intersection point of segment P1→P2 with the boundary of {@code rect}. P1 and P2 should be on opposite sides (one inside,
	 * one outside). Returns null if no valid intersection is found.
	 */
	private static Point segmentBoundaryIntersection(Point p1, Point p2, Rectangle rect)
	{
		double dx = p2.x - p1.x;
		double dy = p2.y - p1.y;
		double bestT = Double.MAX_VALUE;
		Point bestPt = null;

		// Left edge: x = rect.x
		if (dx != 0)
		{
			double t = (rect.x - p1.x) / dx;
			if (t > 0 && t < 1)
			{
				double y = p1.y + t * dy;
				if (y >= rect.y && y <= rect.getBottom() && t < bestT)
				{
					bestT = t;
					bestPt = new Point(rect.x, y);
				}
			}
		}

		// Right edge: x = rect.getRight()
		if (dx != 0)
		{
			double t = (rect.getRight() - p1.x) / dx;
			if (t > 0 && t < 1)
			{
				double y = p1.y + t * dy;
				if (y >= rect.y && y <= rect.getBottom() && t < bestT)
				{
					bestT = t;
					bestPt = new Point(rect.getRight(), y);
				}
			}
		}

		// Top edge: y = rect.y
		if (dy != 0)
		{
			double t = (rect.y - p1.y) / dy;
			if (t > 0 && t < 1)
			{
				double x = p1.x + t * dx;
				if (x >= rect.x && x <= rect.getRight() && t < bestT)
				{
					bestT = t;
					bestPt = new Point(x, rect.y);
				}
			}
		}

		// Bottom edge: y = rect.getBottom()
		if (dy != 0)
		{
			double t = (rect.getBottom() - p1.y) / dy;
			if (t > 0 && t < 1)
			{
				double x = p1.x + t * dx;
				if (x >= rect.x && x <= rect.getRight() && t < bestT)
				{
					bestT = t;
					bestPt = new Point(x, rect.getBottom());
				}
			}
		}

		return bestPt;
	}
}
