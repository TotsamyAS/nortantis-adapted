package nortantis.swing;

import nortantis.*;
import nortantis.editor.*;
import nortantis.geom.Point;
import nortantis.graph.voronoi.Center;
import nortantis.graph.voronoi.Corner;
import nortantis.graph.voronoi.Edge;
import nortantis.platform.DrawQuality;
import nortantis.platform.Image;
import nortantis.platform.awt.AwtBridge;
import nortantis.swing.translation.Translation;
import nortantis.util.Assets;
import nortantis.util.GeometryHelper;
import nortantis.util.Helper;
import nortantis.util.OSHelper;
import nortantis.util.Tuple2;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.nio.file.Paths;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;

public class LandWaterTool extends EditorTool
{

	private JPanel colorDisplay;
	private RowHider colorChooserHider;

	private JToggleButton landButton;
	private JToggleButton oceanButton;
	private JToggleButton lakesButton;
	private JToggleButton riversButton;
	private RowHider riverOptionHider;
	private JSlider riverWidthSlider;
	private Corner riverStart;
	private Center roadStart;
	private RowHider modeHider;
	private JToggleButton fillRegionColorButton;
	private JToggleButton mergeRegionsButton;
	private Region selectedRegion;
	private JToggleButton selectColorFromMapButton;

	private JComboBox<ImageIcon> brushSizeComboBox;
	private RowHider brushSizeHider;
	private RowHider selectColorHider;
	private JCheckBox onlyUpdateLandCheckbox;

	private JSlider hueSlider;
	private JSlider saturationSlider;
	private JSlider brightnessSlider;
	private boolean areRegionColorsVisible;
	private boolean areRegionBoundariesVisible;
	private boolean areRoadsVisible;
	private RowHider onlyUpdateLandCheckboxHider;
	private RowHider generateColorButtonHider;
	private RowHider colorGeneratorSettingsHider;
	private JPanel baseColorPanel;
	private ActionListener brushActionListener;
	private DrawModeWidget modeWidget;
	private JToggleButton newRegionButton;
	private RowHider newRegionButtonHider;
	private JToggleButton roadsButton;
	private SegmentedButtonWidget brushTypeWidget;
	private JToggleButton polygonDrawStyleButton;
	private JToggleButton freeHandDrawStyleButton;
	private RowHider drawStyleHider;

	// Free-hand road drawing state (RI = resolution-invariant coordinates)
	private List<Point> freeHandRoadPathRI = null;
	private Point freeHandRoadSnapPoint = null;
	private Point polygonRoadSnapStart = null;

	// Free-hand river drawing state (RI = resolution-invariant coordinates)
	private List<Point> freeHandRiverPathRI = null;
	private Point freeHandRiverSnapPoint = null;
	private Point polygonRiverSnapStart = null;
	private JToggleButton riverPolygonDrawStyleButton;
	private JToggleButton riverFreeHandDrawStyleButton;
	private RowHider riverDrawStyleHider;

	// Sticky segment selection. Persists across mouse moves until cleared by an action (e.g. line
	// removed by an edit, or user clicks empty space). Used so the river width slider has a fixed
	// target segment to retune, and so the segment stays visibly highlighted while the user moves
	// the mouse. At most one of {stickyRiver, stickyRoad} is non-null. stickySegmentIndex points
	// at the segment from nodes[stickySegmentIndex] to nodes[stickySegmentIndex + 1].
	private River stickyRiver = null;
	private Road stickyRoad = null;
	private int stickySegmentIndex = -1;

	// Hover state — what's under the cursor right now, refreshed on every mouse-moved. Drives the
	// hover ring on a control point and the right-click context menu's target. Cleared when the
	// mouse leaves the map or moves to a position where nothing is in range.
	private River hoveredRiver = null;
	private Road hoveredRoad = null;

	// In-progress control-point drag (edit mode). Captures which line/control-point was grabbed
	// independent of sticky/hover state, since neither concept reflects "currently being dragged".
	// dragSnapshotPath is the line's nodes-as-locations before the drag started, used to determine
	// which centers need redraw at commit time. dragOccurred distinguishes a true drag from a
	// single click that happens to land on a control point.
	private boolean dragInProgress = false;
	private boolean dragOccurred = false;
	private List<Point> dragSnapshotPath = null;
	private River dragRiver = null;
	private Road dragRoad = null;
	private int dragControlPointIndex = -1;

	// At drag-start we record every OTHER line (same type) whose control point sits at the same
	// location as the dragged one. During the drag they move in lockstep so Y-junctions don't tear
	// apart. Each entry also carries the before-drag snapshot so the redraw at commit time covers
	// the centers under the original path too.
	private List<RiverDragShare> dragSharedRivers = null;
	private List<RoadDragShare> dragSharedRoads = null;

	private record RiverDragShare(River river, int cpIndex, List<Point> snapshot)
	{
	}

	private record RoadDragShare(Road road, int cpIndex, List<Point> snapshot)
	{
	}

	// Width-slider edit state. sliderEditWidthBeforeDrag is the selected segment's width level before
	// the user grabs the slider thumb; on release we compare against the new width and only push an
	// undo point if it actually changed. -1 means "no edit in progress".
	private int sliderEditWidthBeforeDrag = -1;
	// True while we're applying the slider's value to the selected segment programmatically, so the
	// change listener doesn't recurse or attempt to re-tune the segment.
	private boolean syncingSliderToSelection = false;

	private enum LineType
	{
		RIVER, ROAD
	}

	/**
	 * Result of hit-testing the cursor against rivers (or roads): the closest control point or
	 * segment if one is in range, or null at the call site. Exactly one of {@code river} or
	 * {@code road} is non-null. Exactly one of {@code controlPointIndex} or {@code segmentIndex}
	 * is &gt;= 0.
	 */
	private record LineHit(River river, Road road, int controlPointIndex, int segmentIndex)
	{
		boolean isControlPoint()
		{
			return controlPointIndex >= 0;
		}

		boolean isSegment()
		{
			return segmentIndex >= 0;
		}
	}

	static String getToolbarNameStatic()
	{
		return Translation.get("landWaterTool.name");
	}

	static String getColorGeneratorSettingsName()
	{
		return Translation.get("landWaterTool.colorGeneratorSettings");
	}

	public LandWaterTool(MainWindow mainWindow, ToolsPanel toolsPanel, MapUpdater mapUpdater)
	{
		super(mainWindow, toolsPanel, mapUpdater);
	}

	@Override
	public String getToolbarName()
	{
		return getToolbarNameStatic();
	}

	@Override
	public int getMnemonic()
	{
		return KeyEvent.VK_Z;
	}

	@Override
	public String getKeyboardShortcutText()
	{
		return "(Alt+Z)";
	}

	@Override
	public Image getToolIcon()
	{
		Image icon = Assets.readImage(Paths.get(Assets.getAssetsPath(), "internal/Land Water tool.png").toString());
		try (nortantis.platform.Painter p = icon.createPainter(DrawQuality.High))
		{
			String land = Translation.get("landWaterTool.toolIcon.land");
			String water = Translation.get("landWaterTool.toolIcon.water");
			p.setFont(createToolIconFont(19, land + water));
			p.setColor(nortantis.platform.Color.black);
			p.drawString(land, 7, 15);
			p.drawString(water, 12 + getXOffSetBasedOnLanguage(), 48);
		}
		return icon;
	}

	private int getXOffSetBasedOnLanguage()
	{
		return switch (Translation.getEffectiveLocale().getLanguage())
		{
			case "de" -> OSHelper.isMac() ? 0 : -3;
			case "es" -> 3;
			case "fr" -> 4;
			case "pt" -> 4;
			case "ru" -> 4;
			default -> 0;
		};
	}


	@Override
	protected JPanel createToolOptionsPanel()
	{
		GridBagOrganizer organizer = new GridBagOrganizer();

		JPanel toolOptionsPanel = organizer.panel;
		toolOptionsPanel.setBorder(BorderFactory.createEmptyBorder(3, 3, 3, 3));

		oceanButton = new JToggleButton(Translation.get("landWaterTool.ocean"));
		brushActionListener = new ActionListener()
		{
			@Override
			public void actionPerformed(ActionEvent e)
			{
				mapEditingPanel.clearSelectedCenters();

				updateColorControlVisibility();
				onlyUpdateLandCheckboxHider.setVisible(landButton.isSelected());

				showOrHideNewRegionButton();
				newRegionButton.setSelected(false);

				if (brushSizeComboBox != null)
				{
					brushSizeHider.setVisible(oceanButton.isSelected() || lakesButton.isSelected() || landButton.isSelected() || (riversButton.isSelected() && modeWidget.isEraseMode())
							|| (roadsButton.isSelected() && modeWidget.isEraseMode()));
				}

				showOrHideRoadAndRiverOptions();
			}
		};
		oceanButton.addActionListener(brushActionListener);

		lakesButton = new JToggleButton(Translation.get("landWaterTool.lakes"));
		lakesButton.setToolTipText(Translation.get("landWaterTool.lakes.tooltip"));
		lakesButton.addActionListener(brushActionListener);

		riversButton = new JToggleButton(Translation.get("landWaterTool.rivers"));
		riversButton.addActionListener(brushActionListener);

		landButton = new JToggleButton(Translation.get("landWaterTool.land"));
		landButton.addActionListener(brushActionListener);

		fillRegionColorButton = new JToggleButton(Translation.get("landWaterTool.fillRegionColor"));
		fillRegionColorButton.addActionListener(brushActionListener);

		mergeRegionsButton = new JToggleButton(Translation.get("landWaterTool.mergeRegions"));
		mergeRegionsButton.addActionListener(brushActionListener);

		roadsButton = new JToggleButton(Translation.get("landWaterTool.roads"));
		roadsButton.addActionListener(brushActionListener);

		oceanButton.setSelected(true); // Selected by default
		brushTypeWidget = new SegmentedButtonWidget(List.of(oceanButton, lakesButton, riversButton, landButton, fillRegionColorButton, mergeRegionsButton, roadsButton));
		brushTypeWidget.addToOrganizer(organizer, Translation.get("landWaterTool.brush.label"), "");

		// River options
		{
			modeWidget = new DrawModeWidget(Translation.get("landWaterTool.drawRivers"), Translation.get("landWaterTool.eraseRivers"), false, "", true,
					Translation.get("landWaterTool.editRiverOrRoad"), () -> brushActionListener.actionPerformed(null));
			modeHider = modeWidget.addToOrganizer(organizer, Translation.get("landWaterTool.riverMode.help"));

			int maxSliderValue = 1 + (int) Math.round(Math.sqrt((GraphRiver.MAX_RIVER_LEVEL - GraphRiver.RIVERS_THIS_SIZE_OR_SMALLER_WILL_NOT_BE_DRAWN - 1) / 2.0));
			riverWidthSlider = new JSlider(1, maxSliderValue);
			final int initialValue = 1;
			riverWidthSlider.setValue(initialValue);
			SliderWithDisplayedValue sliderWithDisplay = new SliderWithDisplayedValue(riverWidthSlider);
			riverOptionHider = sliderWithDisplay.addToOrganizer(organizer, Translation.get("landWaterTool.riverWidth.label"), Translation.get("landWaterTool.riverWidth.help"));

			// In edit mode with a river segment selected, slider changes retune that segment's width
			// rather than (or in addition to) setting the width for the next-drawn river. Live updates
			// fire on every tick; the undo point is only set when the slider is released (or focus
			// leaves) to avoid an undo entry per drag pixel.
			riverWidthSlider.addChangeListener(ev -> handleRiverWidthSliderChanged());
			riverWidthSlider.addMouseListener(new java.awt.event.MouseAdapter()
			{
				@Override
				public void mousePressed(java.awt.event.MouseEvent ev)
				{
					sliderEditWidthBeforeDrag = currentEditSelectedSegmentWidthLevel();
				}

				@Override
				public void mouseReleased(java.awt.event.MouseEvent ev)
				{
					commitSliderEditIfChanged();
				}
			});
		}

		// River draw style (graph vs. free-hand)
		{
			riverPolygonDrawStyleButton = new JToggleButton(Translation.get("landWaterTool.riverStyle.graph"));
			riverPolygonDrawStyleButton.setSelected(true);
			riverPolygonDrawStyleButton.setToolTipText(Translation.get("landWaterTool.riverStyle.graph.tooltip"));
			riverPolygonDrawStyleButton.addActionListener(e ->
			{
				cancelFreeHandDrawing(LineType.RIVER);
				brushActionListener.actionPerformed(null);
			});
			riverFreeHandDrawStyleButton = new JToggleButton(Translation.get("landWaterTool.riverStyle.freeHand"));
			riverFreeHandDrawStyleButton.setToolTipText(Translation.get("landWaterTool.riverStyle.freeHand.tooltip"));
			riverFreeHandDrawStyleButton.addActionListener(e ->
			{
				cancelFreeHandDrawing(LineType.RIVER);
				brushActionListener.actionPerformed(null);
			});
			SegmentedButtonWidget riverDrawStyleWidget = new SegmentedButtonWidget(List.of(riverPolygonDrawStyleButton, riverFreeHandDrawStyleButton));
			riverDrawStyleHider = riverDrawStyleWidget.addToOrganizer(organizer, Translation.get("landWaterTool.riverStyle.label"), Translation.get("landWaterTool.riverStyle.help"));
		}

		// Road draw style (polygon vs. free-hand)
		{
			polygonDrawStyleButton = new JToggleButton(Translation.get("landWaterTool.roadStyle.polygon"));
			polygonDrawStyleButton.setSelected(true);
			polygonDrawStyleButton.setToolTipText(Translation.get("landWaterTool.roadStyle.polygon.tooltip"));
			polygonDrawStyleButton.addActionListener(e ->
			{
				cancelFreeHandDrawing(LineType.ROAD);
				brushActionListener.actionPerformed(null);
			});
			freeHandDrawStyleButton = new JToggleButton(Translation.get("landWaterTool.roadStyle.freeHand"));
			freeHandDrawStyleButton.setToolTipText(Translation.get("landWaterTool.roadStyle.freeHand.tooltip"));
			freeHandDrawStyleButton.addActionListener(e ->
			{
				cancelFreeHandDrawing(LineType.ROAD);
				brushActionListener.actionPerformed(null);
			});
			SegmentedButtonWidget drawStyleWidget = new SegmentedButtonWidget(List.of(polygonDrawStyleButton, freeHandDrawStyleButton));
			drawStyleHider = drawStyleWidget.addToOrganizer(organizer, Translation.get("landWaterTool.roadStyle.label"), Translation.get("landWaterTool.roadStyle.help"));

			// Register Escape (cancel) and Enter (commit) key bindings for free-hand drawing
			mapEditingPanel.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "cancelFreeHandRoad");
			mapEditingPanel.getActionMap().put("cancelFreeHandRoad", new AbstractAction()
			{
				@Override
				public void actionPerformed(ActionEvent e)
				{
					cancelFreeHandDrawing(LineType.ROAD);
					cancelFreeHandDrawing(LineType.RIVER);
				}
			});
			mapEditingPanel.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "commitFreeHandRoad");
			mapEditingPanel.getActionMap().put("commitFreeHandRoad", new AbstractAction()
			{
				@Override
				public void actionPerformed(ActionEvent e)
				{
					if (riversButton.isSelected() && isFreeHandRiverDrawMode())
					{
						finalizeFreeHandLine(LineType.RIVER);
					}
					else
					{
						finalizeFreeHandLine(LineType.ROAD);
					}
				}
			});
		}

		Tuple2<JComboBox<ImageIcon>, RowHider> brushSizeTuple = organizer.addBrushSizeComboBox(brushSizes);
		brushSizeComboBox = brushSizeTuple.getFirst();
		brushSizeHider = brushSizeTuple.getSecond();

		// Create new region button
		{
			newRegionButton = new JToggleButton(Translation.get("landWaterTool.createNewRegion"));
			newRegionButton.addActionListener(e -> updateColorControlVisibility());
			newRegionButton.setToolTipText(Translation.get("landWaterTool.createNewRegion.help"));
			newRegionButtonHider = organizer.addLabelAndComponent(new JLabel(""), newRegionButton, GridBagOrganizer.rowVerticalInset);
		}

		// Color chooser
		colorDisplay = SwingHelper.createColorPickerPreviewPanel();
		colorDisplay.setBackground(Color.black);

		JButton chooseButton = new JButton(Translation.get("common.choose"));
		chooseButton.addActionListener(new ActionListener()
		{
			public void actionPerformed(ActionEvent e)
			{
				cancelSelectColorFromMap();
				SwingHelper.showColorPickerWithPreviewPanel(toolOptionsPanel, colorDisplay, Translation.get("landWaterTool.regionColor.title"));
			}
		});
		colorChooserHider = organizer.addLabelAndComponentsHorizontal(Translation.get("landWaterTool.color.label"), "", Arrays.asList(colorDisplay, chooseButton), SwingHelper.colorPickerLeftPadding);

		selectColorFromMapButton = new JToggleButton(Translation.get("landWaterTool.selectColorFromMap"));
		selectColorFromMapButton.setToolTipText(Translation.get("landWaterTool.selectColorFromMap.tooltip"));
		selectColorHider = organizer.addLabelAndComponent("", "", selectColorFromMapButton, 0);

		JButton generateColorButton = new JButton(Translation.get("landWaterTool.generateColor"));
		generateColorButton.setToolTipText(Translation.get("landWaterTool.generateColor.tooltip"));
		generateColorButton.addActionListener(new ActionListener()
		{
			@Override
			public void actionPerformed(ActionEvent e)
			{
				cancelSelectColorFromMap();
				Color newColor = AwtBridge.toAwtColor(MapCreator.generateColorFromBaseColor(new Random(), AwtBridge.fromAwtColor(baseColorPanel.getBackground()), hueSlider.getValue(),
						saturationSlider.getValue(), brightnessSlider.getValue()));
				colorDisplay.setBackground(newColor);
			}
		});
		generateColorButtonHider = organizer.addLabelAndComponent("", "", generateColorButton, 2);

		onlyUpdateLandCheckbox = new JCheckBox(Translation.get("landWaterTool.onlyUpdateExistingLand"));
		onlyUpdateLandCheckbox.setToolTipText(Translation.get("landWaterTool.onlyUpdateExistingLand.tooltip"));
		onlyUpdateLandCheckboxHider = organizer.addLabelAndComponent("", "", onlyUpdateLandCheckbox);

		colorGeneratorSettingsHider = organizer.addLeftAlignedComponent(createColorGeneratorOptionsPanel(toolOptionsPanel));

		showOrHideBrushOptions();

		organizer.addHorizontalSpacerRowToHelpComponentAlignment(0.66);
		organizer.addVerticalFillerRow();
		return toolOptionsPanel;
	}

	private void showOrHideRoadAndRiverOptions()
	{
		modeHider.setVisible(riversButton.isSelected() || roadsButton.isSelected());
		// Clear edit-mode sticky selection and hover state when leaving edit mode, leaving the rivers/roads
		// brush family entirely, or switching between rivers and roads (sticky on one type would render
		// stale highlights while editing the other type). Do this before computing slider visibility so
		// the slider hides if the sticky was just cleared.
		boolean leftEditMode = !modeWidget.isEditMode() || (!riversButton.isSelected() && !roadsButton.isSelected());
		boolean stickyTypeMismatch = (stickyRiver != null && !riversButton.isSelected()) || (stickyRoad != null && !roadsButton.isSelected());
		if (leftEditMode || stickyTypeMismatch)
		{
			boolean hadSticky = stickyRiver != null || stickyRoad != null;
			boolean hadHover = hoveredRiver != null || hoveredRoad != null;
			if (hadSticky || hadHover)
			{
				clearStickySegment();
				clearHoverState();
				mapEditingPanel.clearHighlightedPolylines();
				mapEditingPanel.repaint();
			}
		}
		// River width slider is shown in draw mode (sets the width of new rivers) and in edit mode
		// only when a sticky river segment is selected (slider then retunes that segment's width).
		// Hidden in edit mode without a selection so the slider's value doesn't look meaningful when
		// it has no effect.
		boolean showSliderInDraw = riversButton.isSelected() && modeWidget.isDrawMode();
		boolean showSliderInEdit = riversButton.isSelected() && modeWidget.isEditMode() && stickyRiver != null;
		riverOptionHider.setVisible(showSliderInDraw || showSliderInEdit);
		riverDrawStyleHider.setVisible(riversButton.isSelected() && modeWidget.isDrawMode());
		drawStyleHider.setVisible(roadsButton.isSelected() && modeWidget.isDrawMode());
		if (updater != null)
		{
			updater.doWhenMapIsReadyForInteractions(() ->
			{
				if (isSelected())
				{
					boolean riverDrawActive = riversButton.isSelected() && modeWidget.isDrawMode();
					boolean roadDrawActive = roadsButton.isSelected() && modeWidget.isDrawMode();
					if (!riverDrawActive && !roadDrawActive)
					{
						cancelFreeHandDrawing(LineType.ROAD);
						cancelFreeHandDrawing(LineType.RIVER);
						clearRoadControlPointDisplay();
						mapEditingPanel.repaint();
					}
					else if (roadDrawActive)
					{
						updateControlPointDisplay(null, LineType.ROAD);
						mapEditingPanel.repaint();
					}
					else
					{
						if (!isFreeHandRiverDrawMode())
						{
							freeHandRiverPathRI = null;
							freeHandRiverSnapPoint = null;
						}
						updateControlPointDisplay(null, LineType.RIVER);
						mapEditingPanel.repaint();
					}
				}
			});
		}
	}

	private boolean isFreeHandDrawMode()
	{
		return freeHandDrawStyleButton != null && freeHandDrawStyleButton.isSelected();
	}

	private boolean isFreeHandRiverDrawMode()
	{
		return riverFreeHandDrawStyleButton != null && riverFreeHandDrawStyleButton.isSelected();
	}

	private double getSnapRadiusRI()
	{
		// Match the outer edge of the drawn circle (including the stroke outline) so a click on the
		// visible circle line is considered "on" the control point, not just clicks inside it.
		return mapEditingPanel.getRoadControlPointHitRadiusInGraphPixels() / mainWindow.displayQualityScale;
	}

	private Point computeSnapPointForType(java.awt.Point mouseLocation, LineType type)
	{
		if (updater.mapParts == null)
		{
			return null;
		}
		return findNearestPointInPaths(mouseLocation, getAllLinePaths(type), getFreeHandPath(type));
	}

	private List<List<Point>> getAllLinePaths(LineType type)
	{
		return type == LineType.RIVER ? mainWindow.edits.rivers.stream().map(r -> PathOperations.toLocationList(r.nodes)).toList()
				: mainWindow.edits.roads.stream().map(r -> PathOperations.toLocationList(r.nodes)).toList();
	}

	private List<Point> getFreeHandPath(LineType type)
	{
		return type == LineType.RIVER ? freeHandRiverPathRI : freeHandRoadPathRI;
	}

	private void setFreeHandPath(LineType type, List<Point> path)
	{
		if (type == LineType.RIVER)
		{
			freeHandRiverPathRI = path;
		}
		else
		{
			freeHandRoadPathRI = path;
		}
	}

	private void setSnapPoint(LineType type, Point snapPoint)
	{
		if (type == LineType.RIVER)
		{
			freeHandRiverSnapPoint = snapPoint;
		}
		else
		{
			freeHandRoadSnapPoint = snapPoint;
		}
	}

	private void updateControlPointDisplay(java.awt.Point mouseLocation, LineType type)
	{
		if (updater.mapParts == null || updater.mapParts.graph == null)
		{
			return;
		}

		Point snapPoint = mouseLocation != null ? computeSnapPointForType(mouseLocation, type) : null;
		setSnapPoint(type, snapPoint);

		List<Point> circlesGraphPixels = new ArrayList<>();
		Point mouseRI = mouseLocation != null ? getPointOnGraph(mouseLocation).mult(1.0 / mainWindow.displayQualityScale) : null;
		double highlightThresholdRI = updater.mapParts.graph.getMeanCenterWidth() / mainWindow.displayQualityScale;
		for (List<Point> path : getAllLinePaths(type))
		{
			if (mouseRI != null && isPathNearPoint(path, mouseRI, highlightThresholdRI))
			{
				for (Point riPoint : path)
				{
					circlesGraphPixels.add(riPoint.mult(mainWindow.displayQualityScale));
				}
			}
		}
		// Show the start of the line being drawn so the user can snap to it and close the loop.
		List<Point> freeHandPath = getFreeHandPath(type);
		if (freeHandPath != null && freeHandPath.size() >= 2)
		{
			circlesGraphPixels.add(freeHandPath.get(0).mult(mainWindow.displayQualityScale));
		}
		mapEditingPanel.setControlPointCircles(circlesGraphPixels);

		if (snapPoint != null)
		{
			mapEditingPanel.setHoveredRoadControlPoint(snapPoint.mult(mainWindow.displayQualityScale));
		}
		else
		{
			mapEditingPanel.clearHoveredControlPoint();
		}

		if (mouseLocation != null && freeHandPath != null && (type == LineType.RIVER || isFreeHandDrawMode()))
		{
			Point currentRI = snapPoint != null ? snapPoint : getPointOnGraph(mouseLocation).mult(1.0 / mainWindow.displayQualityScale);
			List<Point> previewGraphPixels = new ArrayList<>();
			for (Point riPoint : freeHandPath)
			{
				previewGraphPixels.add(riPoint.mult(mainWindow.displayQualityScale));
			}
			previewGraphPixels.add(currentRI.mult(mainWindow.displayQualityScale));
			mapEditingPanel.setFreeHandPreviewPath(previewGraphPixels);
		}
	}

	private void clearRoadControlPointDisplay()
	{
		freeHandRoadSnapPoint = null;
		mapEditingPanel.clearRoadControlPointCircles();
		mapEditingPanel.clearHoveredControlPoint();
		mapEditingPanel.clearFreeHandRoadPreviewPath();
	}

	private void cancelFreeHandDrawing(LineType type)
	{
		if (type == LineType.ROAD)
		{
			if (freeHandRoadPathRI == null)
			{
				return;
			}
			freeHandRoadPathRI = null;
			polygonRoadSnapStart = null;
			freeHandRoadSnapPoint = null;
		}
		else
		{
			freeHandRiverPathRI = null;
			freeHandRiverSnapPoint = null;
			polygonRiverSnapStart = null;
		}
		// Clear only the in-progress preview path. Leave the nearby control point circles and the snap
		// indicator in place so the highlight persists after escape until the user moves the mouse again.
		mapEditingPanel.clearFreeHandRoadPreviewPath();
		mapEditingPanel.repaint();
	}

	private void finalizeFreeHandLine(LineType type)
	{
		List<Point> freeHandPath = getFreeHandPath(type);
		if (freeHandPath == null || freeHandPath.size() < 2)
		{
			setFreeHandPath(type, null);
			clearRoadControlPointDisplay();
			return;
		}

		List<Point> pathToCommit = freeHandPath;
		setFreeHandPath(type, null);
		clearRoadControlPointDisplay();
		mapEditingPanel.repaint();

		List<List<Point>> pathsForCenters;
		if (type == LineType.RIVER)
		{
			int sliderWidth = riverWidthSlider.getValue();
			int base = sliderWidth - 1;
			int riverLevel = base * base * 2 + GraphRiver.RIVERS_THIS_SIZE_OR_SMALLER_WILL_NOT_BE_DRAWN + 1;
			List<River> newRivers = RiverDrawer.addFreeHandRiverFromPoints(pathToCommit, riverLevel, mainWindow.edits.rivers, updater.mapParts.graph, mainWindow.displayQualityScale);
			if (newRivers.isEmpty())
			{
				return;
			}
			pathsForCenters = newRivers.stream().map(r -> PathOperations.toLocationList(r.nodes)).collect(Collectors.toList());
		}
		else
		{
			List<Road> changedList = RoadDrawer.addFreeHandRoadFromPoints(pathToCommit, mainWindow.edits.roads);
			RoadDrawer.removeEmptyOrSinglePointRoads(mainWindow.edits.roads);
			updater.addRoadsToRedrawLowPriority(changedList, mainWindow.displayQualityScale);
			pathsForCenters = changedList.stream().map(r -> PathOperations.toLocationList(r.nodes)).collect(Collectors.toList());
		}

		updater.createAndShowMapIncrementalUsingCenters(getCentersTouchingPoints(pathsForCenters));
		updater.doWhenMapIsNotDrawing(() -> updater.createAndShowLowPriorityChanges());
		undoer.setUndoPoint(UpdateType.Incremental, this);
	}

	private JPanel createColorGeneratorOptionsPanel(JPanel toolOptionsPanel)
	{
		GridBagOrganizer organizer = new GridBagOrganizer();
		organizer.panel.setBorder(BorderFactory.createTitledBorder(new DynamicLineBorder("controlShadow", 1), getColorGeneratorSettingsName()));

		baseColorPanel = SwingHelper.createColorPickerPreviewPanel();
		final JButton baseColorChooseButton = new JButton(Translation.get("common.choose"));
		baseColorChooseButton.addActionListener(new ActionListener()
		{
			public void actionPerformed(ActionEvent arg0)
			{
				SwingHelper.showColorPicker(toolOptionsPanel, baseColorPanel, Translation.get("landWaterTool.baseColor.title"), () ->
				{
				});
			}
		});
		organizer.addLabelAndComponentsHorizontal(Translation.get("landWaterTool.baseColor.label"), Translation.get("landWaterTool.baseColor.help"),
				Arrays.asList(baseColorPanel, baseColorChooseButton), SwingHelper.borderWidthBetweenComponents);

		final int labelWidth = 30;

		hueSlider = new JSlider();
		hueSlider.setMaximum(360);
		SliderWithDisplayedValue hueSliderWithDisplay = new SliderWithDisplayedValue(hueSlider, null, null, labelWidth);
		hueSliderWithDisplay.addToOrganizer(organizer, Translation.get("landWaterTool.hueRange.label"), Translation.get("landWaterTool.hueRange.help"));

		saturationSlider = new JSlider();
		saturationSlider.setMaximum(100);
		SliderWithDisplayedValue saturationSliderWithDisplay = new SliderWithDisplayedValue(saturationSlider, null, null, labelWidth);
		saturationSliderWithDisplay.addToOrganizer(organizer, Translation.get("landWaterTool.saturationRange.label"), Translation.get("landWaterTool.saturationRange.help"));

		brightnessSlider = new JSlider();
		brightnessSlider.setMaximum(100);
		SliderWithDisplayedValue brightnessSliderWithDisplay = new SliderWithDisplayedValue(brightnessSlider, null, null, labelWidth);
		brightnessSliderWithDisplay.addToOrganizer(organizer, Translation.get("landWaterTool.brightnessRange.label"), Translation.get("landWaterTool.brightnessRange.help"));

		return organizer.panel;
	}

	private void showOrHideBrushOptions()
	{
		fillRegionColorButton.setVisible(areRegionColorsVisible);
		mergeRegionsButton.setVisible(areRegionBoundariesVisible || areRegionColorsVisible);
		roadsButton.setVisible(areRoadsVisible);

		if (mergeRegionsButton.isSelected() && !mergeRegionsButton.isVisible())
		{
			landButton.setSelected(true);
		}
		else if (fillRegionColorButton.isSelected() && !fillRegionColorButton.isVisible())
		{
			landButton.setSelected(true);
		}
		else if (roadsButton.isSelected() && !roadsButton.isVisible())
		{
			oceanButton.setSelected(true);
		}

		brushActionListener.actionPerformed(null);

		showOrHideNewRegionButton();
	}

	private void showOrHideNewRegionButton()
	{
		newRegionButtonHider.setVisible((areRegionBoundariesVisible || areRegionColorsVisible) && landButton.isSelected());
		String labelKey = areRegionColorsVisible ? "landWaterTool.createNewRegion" : "landWaterTool.createNewRegion.singleColor";
		newRegionButton.setText(Translation.get(labelKey));
		String helpKey = areRegionColorsVisible ? "landWaterTool.createNewRegion.help" : "landWaterTool.createNewRegion.singleColor.help";
		newRegionButton.setToolTipText(Translation.get(helpKey));
	}

	private void updateColorControlVisibility()
	{
		boolean showColorControls = (newRegionButton.isSelected() && areRegionColorsVisible) || fillRegionColorButton.isSelected();
		colorChooserHider.setVisible(showColorControls);
		selectColorHider.setVisible(showColorControls);
		generateColorButtonHider.setVisible(showColorControls);
		colorGeneratorSettingsHider.setVisible(showColorControls);
	}

	/**
	 * Hit-tests {@code panelPoint} against rivers or roads (whichever brush is active) and returns the closest control point or segment in
	 * range, or {@code null} if nothing is close enough. Control points take precedence over segments when both are in range, since the
	 * user is more likely to want to grab a control point. Control-point threshold matches the road control-point hit radius; segment
	 * threshold is twice that, so thick lines are easy to grab.
	 *
	 * @param scopeRiver
	 *            if non-null, only this river is considered (used in edit mode while a sticky segment is locked).
	 * @param scopeRoad
	 *            if non-null, only this road is considered (used in edit mode while a sticky segment is locked).
	 */
	private LineHit editModeHitTest(java.awt.Point panelPoint, LineType activeType, River scopeRiver, Road scopeRoad)
	{
		if (updater.mapParts == null || updater.mapParts.graph == null || panelPoint == null)
		{
			return null;
		}
		Point clickGraph = getPointOnGraph(panelPoint);
		double controlPointRadius = mapEditingPanel.getRoadControlPointHitRadiusInGraphPixels();
		double segmentThreshold = controlPointRadius * 2.0;
		double scale = mainWindow.displayQualityScale;
		// Rivers can wander away from the segment centerline by up to one full jagged amplitude —
		// jagged lines by design, and splines because the noisy-edge curve drifts the same way.
		// Stretch the threshold to that amplitude so right-clicking on the visible drawn line — not
		// just the underlying centerline — picks the segment. Use a max (not a sum) with the base
		// threshold: the base is calibrated for an easy click on a thin line, but a wider visible
		// line already provides plenty of slack on its own.
		if (activeType == LineType.RIVER)
		{
			double riverDrawEnvelopePixels = RiverDrawer.getJaggedAmplitudeRI(updater.mapParts.graph, scale) * scale;
			segmentThreshold = Math.max(segmentThreshold, riverDrawEnvelopePixels);
		}

		River bestCpRiver = null;
		Road bestCpRoad = null;
		int bestCpIdx = -1;
		double bestCpDist = controlPointRadius;
		River bestSegRiver = null;
		Road bestSegRoad = null;
		int bestSegIdx = -1;
		double bestSegDist = segmentThreshold;

		if (activeType == LineType.RIVER)
		{
			Iterable<River> riversToScan = scopeRiver != null ? Collections.singletonList(scopeRiver) : mainWindow.edits.rivers;
			for (River river : riversToScan)
			{
				List<RiverPathNode> nodes = river.nodes;
				for (int i = 0; i < nodes.size(); i++)
				{
					Point pGraph = nodes.get(i).getLoc().mult(scale);
					double d = clickGraph.distanceTo(pGraph);
					if (d < bestCpDist)
					{
						bestCpDist = d;
						bestCpRiver = river;
						bestCpIdx = i;
					}
				}
				for (int i = 0; i < nodes.size() - 1; i++)
				{
					Point a = nodes.get(i).getLoc().mult(scale);
					Point b = nodes.get(i + 1).getLoc().mult(scale);
					double d = GeometryHelper.distanceFromPointToSegment(clickGraph, a, b);
					if (d < bestSegDist)
					{
						bestSegDist = d;
						bestSegRiver = river;
						bestSegIdx = i;
					}
				}
			}
		}
		else
		{
			Iterable<Road> roadsToScan = scopeRoad != null ? Collections.singletonList(scopeRoad) : mainWindow.edits.roads;
			for (Road road : roadsToScan)
			{
				List<RoadPathNode> nodes = road.nodes;
				for (int i = 0; i < nodes.size(); i++)
				{
					Point pGraph = nodes.get(i).getLoc().mult(scale);
					double d = clickGraph.distanceTo(pGraph);
					if (d < bestCpDist)
					{
						bestCpDist = d;
						bestCpRoad = road;
						bestCpIdx = i;
					}
				}
				for (int i = 0; i < nodes.size() - 1; i++)
				{
					Point a = nodes.get(i).getLoc().mult(scale);
					Point b = nodes.get(i + 1).getLoc().mult(scale);
					double d = GeometryHelper.distanceFromPointToSegment(clickGraph, a, b);
					if (d < bestSegDist)
					{
						bestSegDist = d;
						bestSegRoad = road;
						bestSegIdx = i;
					}
				}
			}
		}

		if (bestCpRiver != null)
		{
			return new LineHit(bestCpRiver, null, bestCpIdx, -1);
		}
		if (bestCpRoad != null)
		{
			return new LineHit(null, bestCpRoad, bestCpIdx, -1);
		}
		if (bestSegRiver != null)
		{
			return new LineHit(bestSegRiver, null, -1, bestSegIdx);
		}
		if (bestSegRoad != null)
		{
			return new LineHit(null, bestSegRoad, -1, bestSegIdx);
		}
		return null;
	}

	/**
	 * Returns the hit-test scope for the active brush in edit mode while a sticky line is locked: only the sticky line is considered.
	 * Used by right-click and hover so they don't surface other lines while the user has one selected.
	 */
	private LineHit editModeHitTestScopedToStickyIfAny(java.awt.Point panelPoint, LineType activeType)
	{
		if (stickyRiver != null && activeType == LineType.RIVER)
		{
			return editModeHitTest(panelPoint, activeType, stickyRiver, null);
		}
		if (stickyRoad != null && activeType == LineType.ROAD)
		{
			return editModeHitTest(panelPoint, activeType, null, stickyRoad);
		}
		return editModeHitTest(panelPoint, activeType, null, null);
	}

	private void clearHoverState()
	{
		hoveredRiver = null;
		hoveredRoad = null;
	}

	private void setStickyRiverSegment(River river, int segmentIndex)
	{
		boolean wasRiver = stickyRiver != null;
		stickyRiver = river;
		stickyRoad = null;
		stickySegmentIndex = segmentIndex;
		if (!wasRiver)
		{
			// Slider visibility depends on stickyRiver in edit mode — refresh so it appears.
			refreshRiverWidthSliderVisibility();
		}
	}

	private void setStickyRoadSegment(Road road, int segmentIndex)
	{
		boolean wasRiver = stickyRiver != null;
		stickyRiver = null;
		stickyRoad = road;
		stickySegmentIndex = segmentIndex;
		if (wasRiver)
		{
			refreshRiverWidthSliderVisibility();
		}
	}

	private void clearStickySegment()
	{
		boolean wasRiver = stickyRiver != null;
		stickyRiver = null;
		stickyRoad = null;
		stickySegmentIndex = -1;
		if (wasRiver)
		{
			refreshRiverWidthSliderVisibility();
		}
	}

	/** Refreshes the river-width slider's visibility based on the current mode and sticky state. */
	private void refreshRiverWidthSliderVisibility()
	{
		if (riverOptionHider == null)
		{
			return;
		}
		boolean showSliderInDraw = riversButton.isSelected() && modeWidget.isDrawMode();
		boolean showSliderInEdit = riversButton.isSelected() && modeWidget.isEditMode() && stickyRiver != null;
		riverOptionHider.setVisible(showSliderInDraw || showSliderInEdit);
	}

	private boolean stickyLineActive()
	{
		return stickyRiver != null || stickyRoad != null;
	}

	/**
	 * Refreshes the edit-mode hover visuals (control-point circles + the hover ring) at {@code mouseLocation}. When a sticky line is locked,
	 * only that line's control points are drawn (regardless of cursor distance), and the hover ring is restricted to that line's CPs.
	 * When nothing is sticky, behaves like draw mode: any line within the highlight threshold contributes its CPs.
	 */
	private void updateEditModeHoverDisplay(java.awt.Point mouseLocation, LineType type)
	{
		if (updater.mapParts == null || updater.mapParts.graph == null)
		{
			return;
		}
		double scale = mainWindow.displayQualityScale;
		List<Point> circlesGraphPixels = new ArrayList<>();
		Point mouseRI = mouseLocation != null ? getPointOnGraph(mouseLocation).mult(1.0 / scale) : null;

		if (stickyLineActive())
		{
			if (type == LineType.RIVER && stickyRiver != null)
			{
				for (RiverPathNode n : stickyRiver.nodes)
				{
					circlesGraphPixels.add(n.getLoc().mult(scale));
				}
			}
			else if (type == LineType.ROAD && stickyRoad != null)
			{
				for (RoadPathNode n : stickyRoad.nodes)
				{
					circlesGraphPixels.add(n.getLoc().mult(scale));
				}
			}
		}
		else
		{
			double highlightThresholdRI = updater.mapParts.graph.getMeanCenterWidth() / scale;
			for (List<Point> path : getAllLinePaths(type))
			{
				if (mouseRI != null && isPathNearPoint(path, mouseRI, highlightThresholdRI))
				{
					for (Point riPoint : path)
					{
						circlesGraphPixels.add(riPoint.mult(scale));
					}
				}
			}
		}
		mapEditingPanel.setControlPointCircles(circlesGraphPixels);

		// Hover ring on the specific CP under the cursor, scoped to the sticky line if one is locked.
		Point snapPointRI = null;
		if (mouseLocation != null && mouseRI != null)
		{
			double snapRadius = getSnapRadiusRI();
			if (stickyLineActive())
			{
				if (type == LineType.RIVER && stickyRiver != null)
				{
					snapPointRI = findNearestPointInSinglePath(mouseRI, PathOperations.toLocationList(stickyRiver.nodes), snapRadius);
				}
				else if (type == LineType.ROAD && stickyRoad != null)
				{
					snapPointRI = findNearestPointInSinglePath(mouseRI, PathOperations.toLocationList(stickyRoad.nodes), snapRadius);
				}
			}
			else
			{
				snapPointRI = computeSnapPointForType(mouseLocation, type);
			}
		}
		if (snapPointRI != null)
		{
			mapEditingPanel.setHoveredRoadControlPoint(snapPointRI.mult(scale));
		}
		else
		{
			mapEditingPanel.clearHoveredControlPoint();
		}
	}

	private Point findNearestPointInSinglePath(Point mouseRI, List<Point> path, double snapRadius)
	{
		Point best = null;
		double bestDist = snapRadius;
		for (Point p : path)
		{
			double d = p.distanceTo(mouseRI);
			if (d < bestDist)
			{
				bestDist = d;
				best = p;
			}
		}
		return best;
	}

	/**
	 * Adds the sticky segment polyline to the panel's highlight list. Callers must clear highlighted polylines beforehand if they want
	 * only the sticky highlight to remain. Safe to call when no sticky segment is selected (no-op).
	 */
	private void applyStickySegmentHighlight()
	{
		double scale = mainWindow.displayQualityScale;
		if (stickyRiver != null && stickySegmentIndex >= 0)
		{
			List<RiverPathNode> nodes = stickyRiver.nodes;
			if (stickySegmentIndex < nodes.size() - 1)
			{
				Point a = nodes.get(stickySegmentIndex).getLoc().mult(scale);
				Point b = nodes.get(stickySegmentIndex + 1).getLoc().mult(scale);
				mapEditingPanel.addPolylinesToHighlight(List.of(a, b));
			}
		}
		else if (stickyRoad != null && stickySegmentIndex >= 0)
		{
			List<RoadPathNode> nodes = stickyRoad.nodes;
			if (stickySegmentIndex < nodes.size() - 1)
			{
				Point a = nodes.get(stickySegmentIndex).getLoc().mult(scale);
				Point b = nodes.get(stickySegmentIndex + 1).getLoc().mult(scale);
				mapEditingPanel.addPolylinesToHighlight(List.of(a, b));
			}
		}
	}

	/** Returns the river level for {@code sliderValue} using the same formula as the draw-mode press handlers. */
	private int sliderValueToRiverLevel(int sliderValue)
	{
		int base = sliderValue - 1;
		return base * base * 2 + GraphRiver.RIVERS_THIS_SIZE_OR_SMALLER_WILL_NOT_BE_DRAWN + 1;
	}

	/** Inverse of {@link #sliderValueToRiverLevel(int)}; clamped to the slider's bounds. */
	private int riverLevelToSliderValue(int riverLevel)
	{
		int adjusted = riverLevel - GraphRiver.RIVERS_THIS_SIZE_OR_SMALLER_WILL_NOT_BE_DRAWN - 1;
		if (adjusted < 0)
		{
			adjusted = 0;
		}
		int base = (int) Math.round(Math.sqrt(adjusted / 2.0));
		int value = base + 1;
		if (value < riverWidthSlider.getMinimum())
		{
			value = riverWidthSlider.getMinimum();
		}
		if (value > riverWidthSlider.getMaximum())
		{
			value = riverWidthSlider.getMaximum();
		}
		return value;
	}

	private int currentEditSelectedSegmentWidthLevel()
	{
		if (stickyRiver == null || stickySegmentIndex < 0)
		{
			return -1;
		}
		List<RiverPathNode> nodes = stickyRiver.nodes;
		if (stickySegmentIndex >= nodes.size() - 1)
		{
			return -1;
		}
		return nodes.get(stickySegmentIndex).getWidthLevelToNext();
	}

	/**
	 * Whenever the slider value changes AND we're in edit mode with a river segment selected, rewrite the segment's width level live. The
	 * change listener also fires when the slider is synced programmatically from selection; {@link #syncingSliderToSelection} suppresses
	 * the rewrite in that case.
	 */
	private void handleRiverWidthSliderChanged()
	{
		if (syncingSliderToSelection)
		{
			return;
		}
		if (!modeWidget.isEditMode() || stickyRiver == null || stickySegmentIndex < 0)
		{
			return;
		}
		List<RiverPathNode> nodes = new ArrayList<>(stickyRiver.nodes);
		if (stickySegmentIndex >= nodes.size() - 1)
		{
			return;
		}
		int newLevel = sliderValueToRiverLevel(riverWidthSlider.getValue());
		RiverPathNode old = nodes.get(stickySegmentIndex);
		if (old.getWidthLevelToNext() == newLevel)
		{
			return;
		}
		nodes.set(stickySegmentIndex, new RiverPathNode(old.getLoc(), newLevel, old.getSeedToNext(), old.getEdgeIndexToNext()));
		stickyRiver.nodes = new java.util.concurrent.CopyOnWriteArrayList<>(nodes);
		List<List<Point>> centersTouched = new ArrayList<>();
		centersTouched.add(PathOperations.toLocationList(nodes));
		updater.createAndShowMapIncrementalUsingCenters(getCentersTouchingPoints(centersTouched));
	}

	private void commitSliderEditIfChanged()
	{
		if (sliderEditWidthBeforeDrag < 0)
		{
			return;
		}
		int newLevel = currentEditSelectedSegmentWidthLevel();
		if (newLevel >= 0 && newLevel != sliderEditWidthBeforeDrag)
		{
			undoer.setUndoPoint(UpdateType.Incremental, this);
		}
		sliderEditWidthBeforeDrag = -1;
	}

	/** Reflects the selected segment's current width in the slider, suppressing the change-listener side effect. */
	private void syncSliderToSelectedSegment()
	{
		int level = currentEditSelectedSegmentWidthLevel();
		if (level < 0)
		{
			return;
		}
		int sliderValue = riverLevelToSliderValue(level);
		if (sliderValue == riverWidthSlider.getValue())
		{
			return;
		}
		syncingSliderToSelection = true;
		try
		{
			riverWidthSlider.setValue(sliderValue);
		}
		finally
		{
			syncingSliderToSelection = false;
		}
	}

	private Integer regionIdToExpand;

	private void handleMousePressOrDrag(MouseEvent e, boolean isMouseDrag)
	{
		if (!SwingUtilities.isLeftMouseButton(e))
		{
			return;
		}

		if (mergeRegionsButton.isSelected() && isMouseDrag)
		{
			return;
		}

		highlightHoverCentersOrEdgesAndBrush(e.getPoint());

		if (oceanButton.isSelected() || lakesButton.isSelected())
		{
			Set<Center> selected = getSelectedCenters(e.getPoint());
			boolean hasChange = false;
			for (Center center : selected)
			{
				CenterEdit edit = mainWindow.edits.centerEdits.get(center.index);
				hasChange |= !edit.isWater;
				hasChange |= edit.isLake != lakesButton.isSelected();
				// Note that I'm nulling out trees in the assignment below because any trees that failed to draw previously should be
				// cleared out when the Center becomes water.
				mainWindow.edits.centerEdits.put(edit.index, new CenterEdit(edit.index, true, lakesButton.isSelected(), null, edit.icon, null));
			}
			if (hasChange)
			{
				removeRiversCoveredByPaintedWater(selected);
				handleMapChange(selected);
			}
		}
		else if (landButton.isSelected())
		{
			if (selectColorFromMapButton.isVisible() && selectColorFromMapButton.isSelected())
			{
				selectColorFromMap(e);
				return;
			}

			Set<Center> selected = getSelectedCenters(e.getPoint());

			if (!isMouseDrag)
			{
				// Set the id of the region that will be expanded as the user drags the mouse.

				if (newRegionButton.isSelected())
				{
					Color color = areRegionColorsVisible ? colorDisplay.getBackground() : mainWindow.getLandColor();
					regionIdToExpand = createNewRegion(color);
					newRegionButton.setSelected(false);
					updateColorControlVisibility();
				}
				else
				{
					Set<Center> mouseDownCenters = getSelectedCenters(e.getPoint(), 1);
					if (mouseDownCenters == null || mouseDownCenters.isEmpty())
					{
						// The mouse press was not on the map
						return;
					}
					assert mouseDownCenters.size() == 1;
					Center mouseDownCenter = mouseDownCenters.iterator().next();
					CenterEdit centerEdit = mainWindow.edits.centerEdits.get(mouseDownCenter.index);
					if (centerEdit.regionId == null)
					{
						// Find the nearest political region when drawing in water.
						nortantis.geom.Point graphPoint = getPointOnGraph(e.getPoint());
						Optional<CenterEdit> nearest = mainWindow.edits.centerEdits.values().stream().filter(cEdit -> cEdit.regionId != null).min((c1, c2) -> Double
								.compare(updater.mapParts.graph.centers.get(c1.index).loc.distanceTo(graphPoint), updater.mapParts.graph.centers.get(c2.index).loc.distanceTo(graphPoint)));
						regionIdToExpand = nearest.map(edit -> edit.regionId).orElse(null);
					}
					else
					{
						regionIdToExpand = centerEdit.regionId;
					}
				}
			}

			boolean hasChange = false;
			for (Center center : selected)
			{
				CenterEdit edit = mainWindow.edits.centerEdits.get(center.index);
				if (onlyUpdateLandCheckbox.isSelected() && edit.isWater)
				{
					continue;
				}
				// Always add region IDs to edits even if regions aren't displayed because the user might show them later.
				Integer newRegionId = getOrCreateRegionIdForEdit(center, mainWindow.getLandColor());
				hasChange |= (edit.regionId == null) || newRegionId != edit.regionId;
				hasChange |= edit.isWater;
				mainWindow.edits.centerEdits.put(edit.index, new CenterEdit(edit.index, false, false, newRegionId, edit.icon, edit.trees));
			}
			if (hasChange)
			{
				handleMapChange(selected);
			}

		}
		else if (fillRegionColorButton.isSelected())
		{
			if (selectColorFromMapButton.isSelected())
			{
				selectColorFromMap(e);
			}
			else
			{
				Center center = updater.mapParts.graph.findClosestCenter(getPointOnGraph(e.getPoint()));
				if (center != null)
				{
					Region region = center.region;
					if (region != null)
					{
						RegionEdit edit = mainWindow.edits.regionEdits.get(region.id);
						edit.color = AwtBridge.fromAwtColor(colorDisplay.getBackground());
						Set<Center> regionCenters = region.getCenters();
						handleMapChange(regionCenters);
					}
				}
			}
		}
		else if (mergeRegionsButton.isSelected())
		{
			Center center = updater.mapParts.graph.findClosestCenter(getPointOnGraph(e.getPoint()));
			if (center != null)
			{
				Region region = center.region;
				if (region != null)
				{
					if (selectedRegion == null)
					{
						selectedRegion = region;
						mapEditingPanel.addSelectedCenters(selectedRegion.getCenters());
					}
					else
					{
						if (region == selectedRegion)
						{
							// Cancel the selection
							selectedRegion = null;
							mapEditingPanel.clearSelectedCenters();
						}
						else
						{
							// Loop over edits instead of region.getCenters() because the region assigned to centers is changed by map
							// drawing, but edits is thread safe.
							for (CenterEdit c : mainWindow.edits.centerEdits.values())
							{
								assert c != null;
								if (c.regionId != null && c.regionId == region.id)
								{
									CenterEdit newValues = new CenterEdit(c.index, c.isWater, c.isLake, selectedRegion.id, c.icon, c.trees);
									mainWindow.edits.centerEdits.put(c.index, newValues);
								}

							}
							mainWindow.edits.regionEdits.remove(region.id);
							selectedRegion = null;
							mapEditingPanel.clearSelectedCenters();
							handleMapChange(region.getCenters());
						}
					}
				}
			}
		}
		else if (riversButton.isSelected())
		{
			if (modeWidget.isEraseMode())
			{
				List<List<Point>> riverSegmentsToRemove = getSelectedRiverSegments(e.getPoint());
				RiverDrawer.removeSegmentsAndSplitRivers(mainWindow.edits.rivers, riverSegmentsToRemove);
				RiverDrawer.removeEmptyOrShortRivers(mainWindow.edits.rivers);
				mapEditingPanel.clearHighlightedEdges();

				if (!riverSegmentsToRemove.isEmpty())
				{
					Set<Center> centersToRedraw = getCentersTouchingPoints(
							pointsToCoverInRedrawAfterPathCut(mainWindow.edits.rivers, r -> r.nodes, riverSegmentsToRemove));
					if (!centersToRedraw.isEmpty())
					{
						updater.createAndShowMapIncrementalUsingCenters(centersToRedraw);
					}
					updater.doWhenMapIsNotDrawing(() -> updater.createAndShowLowPriorityChanges());
					undoer.setUndoPoint(UpdateType.Incremental, this);
				}
			}
		}
		else if (roadsButton.isSelected())
		{
			if (modeWidget.isEraseMode())
			{
				List<List<Point>> roadSegmentsToRemove = getSelectedRoadSegments(e.getPoint());
				List<Road> changed = RoadDrawer.removeSegmentsAndSplitRoads(mainWindow.edits.roads, roadSegmentsToRemove);
				RoadDrawer.removeEmptyOrSinglePointRoads(mainWindow.edits.roads);
				mapEditingPanel.clearHighlightedPolylines();
				updater.createAndShowMapIncrementalUsingCenters(
						getCentersTouchingPoints(pointsToCoverInRedrawAfterPathCut(mainWindow.edits.roads, r -> r.nodes, roadSegmentsToRemove)));
				updater.addRoadsToRedrawLowPriority(changed, mainWindow.displayQualityScale);
			}
		}
	}

	private Set<Center> getCentersTouchingPoints(List<List<Point>> pointLists)
	{
		if (updater.mapParts == null || updater.mapParts.graph == null)
		{
			assert false;
			return Collections.emptySet();
		}

		Set<Center> result = new HashSet<>();
		for (List<Point> points : pointLists)
		{
			for (Point point : points)
			{
				Center c = updater.mapParts.graph.findClosestCenter(point.mult(mainWindow.displayQualityScale), true);
				if (c != null)
				{
					result.add(c);
				}
			}
		}
		return result;
	}

	/**
	 * Appends a {@link PathOperations#CATMULL_ROM_PROPAGATION_RADIUS}-clipped slice of node locations to {@code sink}, one slice per
	 * supplied {@code changedIndex}. Used to scope incremental redraws to "the segments whose Catmull-Rom curve shape can change as a
	 * result of a control-point edit", instead of redrawing every center under the entire path.
	 */
	private static void appendControlPointEditScope(List<List<Point>> sink, List<? extends PathNode> path, int... changedIndices)
	{
		if (path == null || path.isEmpty())
		{
			return;
		}
		for (int idx : changedIndices)
		{
			List<Point> slice = PathOperations.nodeLocationsAround(path, idx, PathOperations.CATMULL_ROM_PROPAGATION_RADIUS);
			if (!slice.isEmpty())
			{
				sink.add(slice);
			}
		}
	}

	/** Same as {@link #appendControlPointEditScope} but takes a pre-extracted snapshot of point locations (e.g. a drag's before-path). */
	private static void appendControlPointEditScopeFromSnapshot(List<List<Point>> sink, List<Point> snapshot, int... changedIndices)
	{
		if (snapshot == null || snapshot.isEmpty())
		{
			return;
		}
		for (int idx : changedIndices)
		{
			List<Point> slice = PathOperations.pointsAround(snapshot, idx, PathOperations.CATMULL_ROM_PROPAGATION_RADIUS);
			if (!slice.isEmpty())
			{
				sink.add(slice);
			}
		}
	}

	/**
	 * Bundles the removed-segment endpoint points together with the "inner neighbor" points needed to fully cover any new end segments
	 * created by the cut. See {@link PathOperations#findInnerNeighborsOfCutEndpoints} for why the extra points are required to prevent
	 * tearing at the incremental update boundary. Works for both rivers and roads via the {@code nodeAccessor} projection.
	 */
	private static <T> List<List<Point>> pointsToCoverInRedrawAfterPathCut(List<T> pathsAfterCut, java.util.function.Function<T, ? extends List<? extends PathNode>> nodeAccessor,
			List<List<Point>> removedSegments)
	{
		List<List<? extends PathNode>> nodeLists = new ArrayList<>(pathsAfterCut.size());
		for (T path : pathsAfterCut)
		{
			nodeLists.add(nodeAccessor.apply(path));
		}
		List<Point> innerNeighbors = PathOperations.findInnerNeighborsOfCutEndpoints(nodeLists, removedSegments);
		if (innerNeighbors.isEmpty())
		{
			return removedSegments;
		}
		List<List<Point>> combined = new ArrayList<>(removedSegments.size() + 1);
		combined.addAll(removedSegments);
		combined.add(innerNeighbors);
		return combined;
	}

	private final int singlePointRoadSelectionRadiusBeforeZoomAndScale = 10;

	private List<List<Point>> getSelectedRoadSegments(java.awt.Point pointFromMouse)
	{
		nortantis.geom.Point graphPointResolutionInvariant = getPointOnGraph(pointFromMouse).mult(1.0 / mainWindow.displayQualityScale);
		int brushDiameter = brushSizes.get(brushSizeComboBox.getSelectedIndex());
		if (brushDiameter <= 1)
		{
			// Find the closest road point within a certain diameter.
			int radius = (int) ((double) ((singlePointRoadSelectionRadiusBeforeZoomAndScale / mainWindow.zoom) * mapEditingPanel.osScale));
			List<Point> closest = findClosestRoadSegmentWithinRadius(graphPointResolutionInvariant, radius);

			List<List<Point>> result = new ArrayList<>(1);
			if (closest != null && !closest.isEmpty())
			{
				result.add(closest);
			}
			return result;
		}
		else
		{
			double brushRadiusResolutionInvariant = (double) ((brushDiameter / mainWindow.zoom) * mapEditingPanel.osScale) / (2 * mainWindow.displayQualityScale);
			return findRoadSegmentsWithinRadius(graphPointResolutionInvariant, brushRadiusResolutionInvariant);
		}
	}

	private List<Point> findClosestRoadSegmentWithinRadius(Point targetPoint, double radius)
	{
		return findClosestSegmentWithinRadius(targetPoint, radius, mainWindow.edits.roads.stream().map(r -> PathOperations.toLocationList(r.nodes)).toList());
	}

	private List<Point> findClosestSegmentWithinRadius(Point targetPoint, double radius, List<List<Point>> paths)
	{
		Point closestStart = null;
		Point closestEnd = null;
		double closestDistance = Double.POSITIVE_INFINITY;
		// Each list in withinRadius is a concatenation of consecutive overlapping segment pairs:
		// e.g. for path A-B-C-D with all three segments overlapping, the list is [A,B,B,C,C,D].
		// Iterate by pairs (i += 2) to get each underlying segment and use line distance, so a
		// click near a shared corner returns a real segment (not a degenerate point-to-itself).
		List<List<Point>> withinRadius = findSegmentsWithinRadius(paths, targetPoint, radius);
		for (List<Point> segment : withinRadius)
		{
			for (int i = 0; i + 1 < segment.size(); i += 2)
			{
				Point p1 = segment.get(i);
				Point p2 = segment.get(i + 1);
				double d = distanceToSegment(targetPoint, p1, p2);
				if (d < closestDistance)
				{
					closestDistance = d;
					closestStart = p1;
					closestEnd = p2;
				}
			}
		}

		if (closestStart == null)
		{
			return Collections.emptyList();
		}

		return Arrays.asList(closestStart, closestEnd);
	}

	private List<List<Point>> findRoadSegmentsWithinRadius(Point targetPoint, double radius)
	{
		return findSegmentsWithinRadius(mainWindow.edits.roads.stream().map(r -> PathOperations.toLocationList(r.nodes)).toList(), targetPoint, radius);
	}

	private List<List<Point>> findSegmentsWithinRadius(List<List<Point>> paths, Point targetPoint, double radius)
	{
		List<List<Point>> result = new ArrayList<>();
		for (List<Point> path : paths)
		{
			if (path.size() < 2)
			{
				continue;
			}

			List<Point> soFar = new ArrayList<>();
			for (int i = 0; i < path.size() - 1; i++)
			{
				Point p1 = path.get(i);
				Point p2 = path.get(i + 1);
				if (GeometryHelper.doesLineOverlapCircle(p1, p2, targetPoint, radius))
				{
					soFar.add(p1);
					soFar.add(p2);
				}
				else
				{
					if (!soFar.isEmpty())
					{
						result.add(soFar);
						soFar = new ArrayList<>();
					}
				}
			}

			if (!soFar.isEmpty())
			{
				result.add(soFar);
			}
		}

		return result;
	}

	private double distanceToSegment(Point p, Point a, Point b)
	{
		double dx = b.x - a.x;
		double dy = b.y - a.y;
		double lengthSquared = dx * dx + dy * dy;
		if (lengthSquared == 0)
		{
			return p.distanceTo(a);
		}
		double t = Math.max(0, Math.min(1, ((p.x - a.x) * dx + (p.y - a.y) * dy) / lengthSquared));
		double closestX = a.x + t * dx;
		double closestY = a.y + t * dy;
		return Math.sqrt((p.x - closestX) * (p.x - closestX) + (p.y - closestY) * (p.y - closestY));
	}

	private boolean isPathNearPoint(List<Point> path, Point target, double threshold)
	{
		for (int i = 0; i < path.size() - 1; i++)
		{
			if (distanceToSegment(target, path.get(i), path.get(i + 1)) <= threshold)
			{
				return true;
			}
		}
		return path.size() == 1 && path.get(0).distanceTo(target) <= threshold;
	}

	private Point findNearestPointInPaths(java.awt.Point mouseLocation, List<List<Point>> paths, List<Point> inProgressPath)
	{
		Point mouseRI = getPointOnGraph(mouseLocation).mult(1.0 / mainWindow.displayQualityScale);
		double snapRadius = getSnapRadiusRI();

		List<Point> candidates = new ArrayList<>();
		for (List<Point> path : paths)
			candidates.addAll(path);
		if (inProgressPath != null && inProgressPath.size() >= 2)
			candidates.add(inProgressPath.get(0));

		Point nearest = Helper.minItem(candidates, Comparator.comparingDouble(p -> p.distanceTo(mouseRI)));
		return nearest != null && nearest.distanceTo(mouseRI) < snapRadius ? nearest : null;
	}

	private List<List<Point>> getSelectedRiverSegments(java.awt.Point pointFromMouse)
	{
		Point targetPoint = getPointOnGraph(pointFromMouse).mult(1.0 / mainWindow.displayQualityScale);
		int brushDiameter = brushSizes.get(brushSizeComboBox.getSelectedIndex());
		List<List<Point>> riverPaths = mainWindow.edits.rivers.stream().map(r -> PathOperations.toLocationList(r.nodes)).toList();
		if (brushDiameter <= 1)
		{
			int radius = (int) ((double) ((singlePointRoadSelectionRadiusBeforeZoomAndScale / mainWindow.zoom) * mapEditingPanel.osScale));
			List<Point> closest = findClosestSegmentWithinRadius(targetPoint, radius, riverPaths);
			List<List<Point>> result = new ArrayList<>(1);
			if (closest != null && !closest.isEmpty())
			{
				result.add(closest);
			}
			return result;
		}
		else
		{
			double brushRadiusResolutionInvariant = (double) ((brushDiameter / mainWindow.zoom) * mapEditingPanel.osScale) / (2 * mainWindow.displayQualityScale);
			return findSegmentsWithinRadius(riverPaths, targetPoint, brushRadiusResolutionInvariant);
		}
	}

	/**
	 * After the user has painted ocean or lake on {@code paintedCenters} (centerEdits already updated), removes river segments that are
	 * entirely in water or that follow exactly along a Voronoi edge which has just become a coast or lakeshore. The actual water test
	 * consults {@code centerEdits} because {@link Center#isWater} is not updated until the next redraw runs. Triggers an incremental redraw
	 * of the centers along any removed segments so stale river pixels on the land side of a new coast get cleaned up.
	 */
	private void removeRiversCoveredByPaintedWater(Set<Center> paintedCenters)
	{
		if (updater.mapParts == null || updater.mapParts.graph == null || mainWindow.edits.rivers.isEmpty())
		{
			return;
		}

		java.util.function.Predicate<Center> isWaterAfterEdit = center ->
		{
			CenterEdit edit = mainWindow.edits.centerEdits.get(center.index);
			return edit != null ? edit.isWater : center.isWater;
		};

		List<List<Point>> segmentsToRemove = RiverDrawer.findRiverSegmentsToRemoveForWaterPaint(mainWindow.edits.rivers, updater.mapParts.graph, mainWindow.displayQualityScale, paintedCenters,
				isWaterAfterEdit);
		if (segmentsToRemove.isEmpty())
		{
			return;
		}

		RiverDrawer.removeSegmentsAndSplitRivers(mainWindow.edits.rivers, segmentsToRemove);
		RiverDrawer.removeEmptyOrShortRivers(mainWindow.edits.rivers);

		// Redraw centers on the land side of removed segments too, so the river pixels there get cleared.
		Set<Center> centersToRedraw = getCentersTouchingPoints(pointsToCoverInRedrawAfterPathCut(mainWindow.edits.rivers, r -> r.nodes, segmentsToRemove));
		if (!centersToRedraw.isEmpty())
		{
			updater.createAndShowMapIncrementalUsingCenters(centersToRedraw);
		}
		updater.doWhenMapIsNotDrawing(() -> updater.createAndShowLowPriorityChanges());
	}

	/**
	 * Bridges the river's natural endpoint(s) to {@code snapStart}/{@code snapEnd} by appending (or prepending) a new node, rather than
	 * replacing the natural endpoint. This is what the drag preview shows — a polygon path that walks Voronoi corners then bridges from
	 * the last/first corner to the snap point. Replacing the natural endpoint (the old behavior) made the polygon river end short of the
	 * snap target when the target was a middle control point of another river (no merge possible, no bridge drawn).
	 *
	 * <p>
	 * The bridge segment inherits the polygon path's width and gets a fresh seed; its edge index is cleared because the bridge doesn't
	 * follow a single Voronoi edge.
	 */
	private static void applyRiverSnapPoints(River river, Point snapStart, Point naturalStartRI, Point snapEnd, Point naturalEndRI)
	{
		List<RiverPathNode> updated = new ArrayList<>(river.nodes);
		if (updated.isEmpty())
		{
			return;
		}
		Random random = new Random();
		if (snapEnd != null && !snapEnd.isCloseEnough(naturalEndRI))
		{
			appendRiverBridge(updated, snapEnd, naturalEndRI, random);
		}
		if (snapStart != null && !snapStart.isCloseEnough(naturalStartRI))
		{
			prependRiverBridge(updated, snapStart, naturalStartRI, random);
		}
		river.nodes = new java.util.concurrent.CopyOnWriteArrayList<>(updated);
	}

	private static void appendRiverBridge(List<RiverPathNode> nodes, Point snap, Point natural, Random random)
	{
		if (nodes.isEmpty())
		{
			return;
		}
		RiverPathNode last = nodes.get(nodes.size() - 1);
		if (last.getLoc().isCloseEnough(natural))
		{
			int width = nodes.size() >= 2 ? nodes.get(nodes.size() - 2).getWidthLevelToNext() : 0;
			long seed = random.nextLong();
			// The old last node now has an outgoing segment (the bridge); set its to-next metadata
			// so the bridge inherits the polygon path's width and gets a fresh seed.
			nodes.set(nodes.size() - 1, new RiverPathNode(last.getLoc(), width, seed, RiverPathNode.EDGE_INDEX_NONE));
			// New last node with cleared to-next (no outgoing segment).
			nodes.add(new RiverPathNode(snap, 0, 0L, RiverPathNode.EDGE_INDEX_NONE));
			return;
		}
		RiverPathNode first = nodes.get(0);
		if (first.getLoc().isCloseEnough(natural))
		{
			// Path's "natural" location is actually the start — fall through to prepend semantics.
			prependRiverBridge(nodes, snap, natural, random);
		}
	}

	private static void prependRiverBridge(List<RiverPathNode> nodes, Point snap, Point natural, Random random)
	{
		if (nodes.isEmpty())
		{
			return;
		}
		RiverPathNode first = nodes.get(0);
		if (first.getLoc().isCloseEnough(natural))
		{
			int width = first.getWidthLevelToNext() != 0 ? first.getWidthLevelToNext() : (nodes.size() >= 2 ? nodes.get(1).getWidthLevelToNext() : 0);
			long seed = random.nextLong();
			// New first node carries the bridge segment's to-next metadata so the bridge has consistent
			// width with the rest of the polygon river.
			nodes.add(0, new RiverPathNode(snap, width, seed, RiverPathNode.EDGE_INDEX_NONE));
			return;
		}
		RiverPathNode last = nodes.get(nodes.size() - 1);
		if (last.getLoc().isCloseEnough(natural))
		{
			// Path's "natural" location is actually the end — fall through to append semantics.
			appendRiverBridge(nodes, snap, natural, random);
		}
	}

	private static void applyRoadSnapPoints(Road road, Point snapStart, Point naturalStartRI, Point snapEnd, Point naturalEndRI)
	{
		List<RoadPathNode> updated = new ArrayList<>(road.nodes);
		if (updated.isEmpty())
		{
			return;
		}
		if (snapEnd != null && !snapEnd.isCloseEnough(naturalEndRI))
		{
			appendRoadBridge(updated, snapEnd, naturalEndRI);
		}
		if (snapStart != null && !snapStart.isCloseEnough(naturalStartRI))
		{
			prependRoadBridge(updated, snapStart, naturalStartRI);
		}
		road.nodes = new java.util.concurrent.CopyOnWriteArrayList<>(updated);
	}

	private static void appendRoadBridge(List<RoadPathNode> nodes, Point snap, Point natural)
	{
		if (nodes.isEmpty())
		{
			return;
		}
		if (nodes.get(nodes.size() - 1).getLoc().isCloseEnough(natural))
		{
			nodes.add(new RoadPathNode(snap));
		}
		else if (nodes.get(0).getLoc().isCloseEnough(natural))
		{
			prependRoadBridge(nodes, snap, natural);
		}
	}

	private static void prependRoadBridge(List<RoadPathNode> nodes, Point snap, Point natural)
	{
		if (nodes.isEmpty())
		{
			return;
		}
		if (nodes.get(0).getLoc().isCloseEnough(natural))
		{
			nodes.add(0, new RoadPathNode(snap));
		}
		else if (nodes.get(nodes.size() - 1).getLoc().isCloseEnough(natural))
		{
			appendRoadBridge(nodes, snap, natural);
		}
	}

	private void selectColorFromMap(MouseEvent e)
	{
		Center center = updater.mapParts.graph.findClosestCenter(getPointOnGraph(e.getPoint()));
		if (center != null)
		{
			if (center != null && center.region != null)
			{
				colorDisplay.setBackground(AwtBridge.toAwtColor(center.region.backgroundColor));
				selectColorFromMapButton.setSelected(false);
			}
		}
	}

	private void cancelSelectColorFromMap()
	{
		if (selectColorFromMapButton.isSelected())
		{
			selectColorFromMapButton.setSelected(false);
			selectedRegion = null;
			mapEditingPanel.clearSelectedCenters();
		}
	}

	private Set<Center> getSelectedCenters(java.awt.Point point)
	{
		return getSelectedCenters(point, brushSizes.get(brushSizeComboBox.getSelectedIndex()));
	}

	private int getOrCreateRegionIdForEdit(Center center, Color color)
	{
		// When the user clicked down on centers that had a region id, use that one.
		if (regionIdToExpand != null)
		{
			return regionIdToExpand;
		}

		// If a neighboring center has a region, use that region.
		for (Center neighbor : center.neighbors)
		{
			CenterEdit neighborEdit = mainWindow.edits.centerEdits.get(neighbor.index);
			if (neighborEdit.regionId != null)
			{
				return neighborEdit.regionId;
			}
		}

		// Find the closest center with a region.
		Optional<CenterEdit> opt = mainWindow.edits.centerEdits.values().stream().filter(cEdit1 -> cEdit1.regionId != null).min((cEdit1, cEdit2) -> Double
				.compare(updater.mapParts.graph.centers.get(cEdit1.index).loc.distanceTo(center.loc), updater.mapParts.graph.centers.get(cEdit2.index).loc.distanceTo(center.loc)));
		if (opt.isPresent())
		{
			return opt.get().regionId;
		}
		else
		{
			int newRegionId = createNewRegion(color);
			return newRegionId;
		}
	}

	private int createNewRegion(Color color)
	{
		int largestRegionId;
		if (mainWindow.edits.regionEdits.isEmpty())
		{
			largestRegionId = -1;
		}
		else
		{
			largestRegionId = mainWindow.edits.regionEdits.values().stream().max((r1, r2) -> Integer.compare(r1.regionId, r2.regionId)).get().regionId;
		}

		int newRegionId = largestRegionId + 1;

		RegionEdit regionEdit = new RegionEdit(newRegionId, AwtBridge.fromAwtColor(color));
		mainWindow.edits.regionEdits.put(newRegionId, regionEdit);
		return newRegionId;
	}

	private void handleMapChange(Set<Center> centers)
	{
		updater.createAndShowMapIncrementalUsingCenters(centers);
	}

	@Override
	protected void handleMousePressedOnMap(MouseEvent e)
	{
		handleMousePressOrDrag(e, false);

		if (riversButton.isSelected() && modeWidget.isDrawMode() && !isFreeHandRiverDrawMode())
		{
			polygonRiverSnapStart = computeSnapPointForType(e.getPoint(), LineType.RIVER);
			riverStart = updater.mapParts.graph.findClosestCorner(getPointOnGraph(e.getPoint()));
		}
		else if (riversButton.isSelected() && modeWidget.isDrawMode() && isFreeHandRiverDrawMode() && SwingUtilities.isLeftMouseButton(e) && updater.mapParts != null && updater.mapParts.graph != null)
		{
			Point riPoint = freeHandRiverSnapPoint != null ? freeHandRiverSnapPoint : getPointOnGraph(e.getPoint()).mult(1.0 / mainWindow.displayQualityScale);
			if (e.getClickCount() == 1)
			{
				if (freeHandRiverPathRI == null)
				{
					freeHandRiverPathRI = new ArrayList<>();
				}
				freeHandRiverPathRI.add(riPoint);
				updateControlPointDisplay(e.getPoint(), LineType.RIVER);
				mapEditingPanel.repaint();
			}
			else if (e.getClickCount() == 2)
			{
				finalizeFreeHandLine(LineType.RIVER);
			}
		}
		else if (roadsButton.isSelected() && modeWidget.isDrawMode() && isFreeHandDrawMode() && SwingUtilities.isLeftMouseButton(e) && updater.mapParts != null && updater.mapParts.graph != null)
		{
			// Use press (not click) so moving the mouse between press and release still registers the control point.
			Point riPoint = freeHandRoadSnapPoint != null ? freeHandRoadSnapPoint : getPointOnGraph(e.getPoint()).mult(1.0 / mainWindow.displayQualityScale);
			if (e.getClickCount() == 1)
			{
				if (freeHandRoadPathRI == null)
				{
					freeHandRoadPathRI = new ArrayList<>();
				}
				freeHandRoadPathRI.add(riPoint);
				updateControlPointDisplay(e.getPoint(), LineType.ROAD);
				mapEditingPanel.repaint();
			}
			else if (e.getClickCount() == 2)
			{
				// Double-click: the first press already added the final point; finalize now.
				finalizeFreeHandLine(LineType.ROAD);
			}
		}
		else if (roadsButton.isSelected() && modeWidget.isDrawMode() && !isFreeHandDrawMode())
		{
			polygonRoadSnapStart = computeSnapPointForType(e.getPoint(), LineType.ROAD);
			roadStart = updater.mapParts.graph.findClosestCenter(getPointOnGraph(e.getPoint()));
		}
		else if (modeWidget.isEditMode() && (riversButton.isSelected() || roadsButton.isSelected()) && SwingUtilities.isLeftMouseButton(e))
		{
			// Edit mode press. The hit-test scope is restricted to the sticky line while one is
			// locked, matching what's highlighted on screen. Clicking on the sticky line's control
			// point starts a drag; clicking on a different segment of it updates the sticky segment;
			// clicking anything outside the sticky line clears the sticky lock (no other action this
			// click — the next click is fresh).
			LineType activeType = riversButton.isSelected() ? LineType.RIVER : LineType.ROAD;
			LineHit hit = editModeHitTestScopedToStickyIfAny(e.getPoint(), activeType);
			if (hit != null && hit.isControlPoint())
			{
				dragRiver = hit.river();
				dragRoad = hit.road();
				dragControlPointIndex = hit.controlPointIndex();
				dragInProgress = true;
				dragOccurred = false;
				dragSnapshotPath = dragRiver != null ? PathOperations.toLocationList(dragRiver.nodes)
						: PathOperations.toLocationList(dragRoad.nodes);
				captureSharedControlPointsForDrag();
			}
			else if (hit != null && hit.isSegment() && hit.river() != null)
			{
				// Only rivers support sticky segment selection (the width slider needs a target).
				// Road segments aren't selectable because there's no road-segment-level setting to tune.
				setStickyRiverSegment(hit.river(), hit.segmentIndex());
				syncSliderToSelectedSegment();
				mapEditingPanel.clearHighlightedPolylines();
				applyStickySegmentHighlight();
				// Refresh hover so the sticky line's CPs are now the only ones drawn.
				updateEditModeHoverDisplay(e.getPoint(), activeType);
				mapEditingPanel.repaint();
				dragInProgress = false;
				dragOccurred = false;
				dragSnapshotPath = null;
			}
			else
			{
				if (stickyRiver != null || stickyRoad != null)
				{
					clearStickySegment();
					mapEditingPanel.clearHighlightedPolylines();
					// Refresh hover so other lines' CPs reappear now that the lock is released.
					updateEditModeHoverDisplay(e.getPoint(), activeType);
					mapEditingPanel.repaint();
				}
				dragInProgress = false;
				dragOccurred = false;
				dragSnapshotPath = null;
			}
		}
	}

	/**
	 * Scans the active type's other lines for any control point sharing the dragged CP's location and records them so they can be moved
	 * in lockstep. Run once at drag-start; the resulting list survives until {@link #clearDragSharedTargets()}. A river only matches other
	 * rivers, and a road only matches other roads — we don't pull cross-type joints because the user's description was scoped to same-type
	 * Y-junctions.
	 */
	private void captureSharedControlPointsForDrag()
	{
		dragSharedRivers = new ArrayList<>();
		dragSharedRoads = new ArrayList<>();
		Point primaryLoc;
		if (dragRiver != null && dragControlPointIndex >= 0 && dragControlPointIndex < dragRiver.nodes.size())
		{
			primaryLoc = dragRiver.nodes.get(dragControlPointIndex).getLoc();
		}
		else if (dragRoad != null && dragControlPointIndex >= 0 && dragControlPointIndex < dragRoad.nodes.size())
		{
			primaryLoc = dragRoad.nodes.get(dragControlPointIndex).getLoc();
		}
		else
		{
			return;
		}
		if (dragRiver != null)
		{
			for (River other : mainWindow.edits.rivers)
			{
				if (other == dragRiver)
				{
					continue;
				}
				List<RiverPathNode> nodes = other.nodes;
				for (int i = 0; i < nodes.size(); i++)
				{
					if (nodes.get(i).getLoc().isCloseEnough(primaryLoc))
					{
						dragSharedRivers.add(new RiverDragShare(other, i, PathOperations.toLocationList(nodes)));
					}
				}
			}
		}
		else if (dragRoad != null)
		{
			for (Road other : mainWindow.edits.roads)
			{
				if (other == dragRoad)
				{
					continue;
				}
				List<RoadPathNode> nodes = other.nodes;
				for (int i = 0; i < nodes.size(); i++)
				{
					if (nodes.get(i).getLoc().isCloseEnough(primaryLoc))
					{
						dragSharedRoads.add(new RoadDragShare(other, i, PathOperations.toLocationList(nodes)));
					}
				}
			}
		}
	}

	private void clearDragSharedTargets()
	{
		dragSharedRivers = null;
		dragSharedRoads = null;
	}

	/**
	 * Moves the in-progress dragged control point to follow the cursor. Updates {@code river.nodes} (or {@code road.nodes}) in place and
	 * triggers an incremental redraw of the centers under the new path so the user sees the line follow the cursor live. Any other lines
	 * sharing the original control-point location (recorded at drag-start) move with it so Y-junctions stay joined. The undo point is
	 * set only on mouse release ({@link #handleEditModeControlPointDragEnd(java.util.List)}), not on each drag tick.
	 */
	private void handleEditModeControlPointDrag(MouseEvent e)
	{
		dragOccurred = true;
		Point newLocRI = getPointOnGraph(e.getPoint()).mult(1.0 / mainWindow.displayQualityScale);
		// Only the segments adjacent to the moved CP (within Catmull-Rom propagation range) need redrawing; long paths used to redraw end
		// to end on every drag tick, which made manipulating long roads or rivers chug. The unaffected portions of the road's dashed
		// pattern are caught later by the low-priority redraw queued in handleEditModeControlPointDragEnd.
		List<List<Point>> centersTouched = new ArrayList<>();
		if (dragRiver != null)
		{
			List<RiverPathNode> nodes = new ArrayList<>(dragRiver.nodes);
			int idx = dragControlPointIndex;
			if (idx < 0 || idx >= nodes.size())
			{
				return;
			}
			appendControlPointEditScope(centersTouched, nodes, idx);
			moveRiverControlPointTo(nodes, idx, newLocRI);
			dragRiver.nodes = new java.util.concurrent.CopyOnWriteArrayList<>(nodes);
			appendControlPointEditScope(centersTouched, nodes, idx);
			// Move every shared CP in lockstep so joined Y-shape rivers stay joined.
			if (dragSharedRivers != null)
			{
				for (RiverDragShare share : dragSharedRivers)
				{
					List<RiverPathNode> sharedNodes = new ArrayList<>(share.river().nodes);
					int sharedIdx = share.cpIndex();
					if (sharedIdx < 0 || sharedIdx >= sharedNodes.size())
					{
						continue;
					}
					appendControlPointEditScope(centersTouched, sharedNodes, sharedIdx);
					moveRiverControlPointTo(sharedNodes, sharedIdx, newLocRI);
					share.river().nodes = new java.util.concurrent.CopyOnWriteArrayList<>(sharedNodes);
					appendControlPointEditScope(centersTouched, sharedNodes, sharedIdx);
				}
			}
		}
		else if (dragRoad != null)
		{
			List<RoadPathNode> nodes = new ArrayList<>(dragRoad.nodes);
			int idx = dragControlPointIndex;
			if (idx < 0 || idx >= nodes.size())
			{
				return;
			}
			appendControlPointEditScope(centersTouched, nodes, idx);
			nodes.set(idx, new RoadPathNode(newLocRI));
			dragRoad.nodes = new java.util.concurrent.CopyOnWriteArrayList<>(nodes);
			appendControlPointEditScope(centersTouched, nodes, idx);
			if (dragSharedRoads != null)
			{
				for (RoadDragShare share : dragSharedRoads)
				{
					List<RoadPathNode> sharedNodes = new ArrayList<>(share.road().nodes);
					int sharedIdx = share.cpIndex();
					if (sharedIdx < 0 || sharedIdx >= sharedNodes.size())
					{
						continue;
					}
					appendControlPointEditScope(centersTouched, sharedNodes, sharedIdx);
					sharedNodes.set(sharedIdx, new RoadPathNode(newLocRI));
					share.road().nodes = new java.util.concurrent.CopyOnWriteArrayList<>(sharedNodes);
					appendControlPointEditScope(centersTouched, sharedNodes, sharedIdx);
				}
			}
		}
		else
		{
			return;
		}
		// Refresh hover visuals (control-point circles + hover ring) at the new cursor position, then
		// re-apply the sticky segment highlight on top so it survives the polyline clear.
		LineType activeType = riversButton.isSelected() ? LineType.RIVER : LineType.ROAD;
		mapEditingPanel.clearHighlightedPolylines();
		updateEditModeHoverDisplay(e.getPoint(), activeType);
		applyStickySegmentHighlight();
		updater.createAndShowMapIncrementalUsingCenters(getCentersTouchingPoints(centersTouched));
		mapEditingPanel.repaint();
	}

	/**
	 * Moves the river control point at {@code idx} in {@code nodes} to {@code newLocRI}, clearing the edge index on the moved node and on
	 * its predecessor (both adjacent segments no longer follow a single Voronoi edge). Mutates {@code nodes} in place; does not assign to
	 * any River's {@code .nodes} field.
	 */
	private void moveRiverControlPointTo(List<RiverPathNode> nodes, int idx, Point newLocRI)
	{
		RiverPathNode old = nodes.get(idx);
		nodes.set(idx, new RiverPathNode(newLocRI, old.getWidthLevelToNext(), old.getSeedToNext(), RiverPathNode.EDGE_INDEX_NONE));
		if (idx > 0)
		{
			RiverPathNode prev = nodes.get(idx - 1);
			nodes.set(idx - 1, new RiverPathNode(prev.getLoc(), prev.getWidthLevelToNext(), prev.getSeedToNext(), RiverPathNode.EDGE_INDEX_NONE));
		}
	}

	/**
	 * Commits the drag: snapshots the new path, records the undo point, and triggers the redraw of the changed line. Called from the
	 * mouse release handler. The drag's snapshot of the path before the drag is provided so centers along the original path are also
	 * redrawn (in case the moved control point pulled the line off some centers).
	 */
	private void handleEditModeControlPointDragEnd(List<Point> beforePath)
	{
		// Same scoping rationale as handleEditModeControlPointDrag: only the segments within Catmull-Rom propagation range of the moved
		// control point need a normal-priority redraw. For roads, the rest of the line is picked up by the low-priority queue below so
		// the dashed pattern is rephased across the whole line.
		List<List<Point>> centerPaths = new ArrayList<>();
		int draggedIdx = dragControlPointIndex;
		appendControlPointEditScopeFromSnapshot(centerPaths, beforePath, draggedIdx);
		if (dragRiver != null)
		{
			appendControlPointEditScope(centerPaths, dragRiver.nodes, draggedIdx);
			if (dragSharedRivers != null)
			{
				for (RiverDragShare share : dragSharedRivers)
				{
					appendControlPointEditScopeFromSnapshot(centerPaths, share.snapshot(), share.cpIndex());
					appendControlPointEditScope(centerPaths, share.river().nodes, share.cpIndex());
				}
			}
		}
		else if (dragRoad != null)
		{
			appendControlPointEditScope(centerPaths, dragRoad.nodes, draggedIdx);
			// Queue a low-priority redraw of the entire road so the dotted pattern is recomputed across
			// any centers the drag pulled the road onto but that aren't covered by the eager scope. The eager pass above only redrew
			// centers near the moved control point; this catches the long-haul centers along the unchanged portions of the road.
			updater.addRoadsToRedrawLowPriority(Collections.singletonList(dragRoad), mainWindow.displayQualityScale);
			if (dragSharedRoads != null)
			{
				for (RoadDragShare share : dragSharedRoads)
				{
					appendControlPointEditScopeFromSnapshot(centerPaths, share.snapshot(), share.cpIndex());
					appendControlPointEditScope(centerPaths, share.road().nodes, share.cpIndex());
					updater.addRoadsToRedrawLowPriority(Collections.singletonList(share.road()), mainWindow.displayQualityScale);
				}
			}
		}
		undoer.setUndoPoint(UpdateType.Incremental, this);
		updater.createAndShowMapIncrementalUsingCenters(getCentersTouchingPoints(centerPaths));
		updater.doWhenMapIsNotDrawing(() -> updater.createAndShowLowPriorityChanges());
		dragRiver = null;
		dragRoad = null;
		dragControlPointIndex = -1;
		clearDragSharedTargets();
	}

	/**
	 * Right-click handling:
	 * <ul>
	 * <li>When a free-hand line is in progress, removes the last control point Krita-style.</li>
	 * <li>Otherwise, in draw or edit mode for rivers/roads, opens a context menu targeted at whatever is under the cursor.</li>
	 * </ul>
	 */
	@Override
	protected void handleMouseRightPressedOnMap(MouseEvent e)
	{
		if (riversButton.isSelected() && modeWidget.isDrawMode() && isFreeHandRiverDrawMode() && freeHandRiverPathRI != null && !freeHandRiverPathRI.isEmpty())
		{
			if (freeHandRiverPathRI.size() > 1)
			{
				freeHandRiverPathRI.remove(freeHandRiverPathRI.size() - 1);
			}
			updateControlPointDisplay(e.getPoint(), LineType.RIVER);
			mapEditingPanel.repaint();
			return;
		}
		if (roadsButton.isSelected() && modeWidget.isDrawMode() && isFreeHandDrawMode() && freeHandRoadPathRI != null && !freeHandRoadPathRI.isEmpty())
		{
			if (freeHandRoadPathRI.size() > 1)
			{
				freeHandRoadPathRI.remove(freeHandRoadPathRI.size() - 1);
			}
			updateControlPointDisplay(e.getPoint(), LineType.ROAD);
			mapEditingPanel.repaint();
			return;
		}
		if ((riversButton.isSelected() || roadsButton.isSelected()) && (modeWidget.isDrawMode() || modeWidget.isEditMode()))
		{
			showRiverRoadContextMenu(e);
		}
	}

	/**
	 * Re-runs the cursor hit-test and shows a context menu targeted at whatever is under the cursor. Items are context-dependent:
	 * Delete Control Point when on a control point; Delete Segment, Insert Control Point, and (in edit mode only) Select Segment when on
	 * a segment. Returns without showing a menu when nothing is in range.
	 */
	private void showRiverRoadContextMenu(MouseEvent e)
	{
		LineType activeType = riversButton.isSelected() ? LineType.RIVER : LineType.ROAD;
		// While a sticky segment is locked in edit mode, the menu only targets that line.
		final LineHit hit = modeWidget.isEditMode() ? editModeHitTestScopedToStickyIfAny(e.getPoint(), activeType)
				: editModeHitTest(e.getPoint(), activeType, null, null);
		if (hit == null)
		{
			return;
		}
		JPopupMenu menu = new JPopupMenu();
		if (hit.isControlPoint())
		{
			JMenuItem deleteCp = new JMenuItem(Translation.get("landWaterTool.edit.deleteControlPoint"));
			deleteCp.addActionListener(ev -> deleteControlPoint(hit));
			menu.add(deleteCp);
		}
		else if (hit.isSegment())
		{
			JMenuItem deleteSeg = new JMenuItem(Translation.get("landWaterTool.edit.deleteSegment"));
			deleteSeg.addActionListener(ev -> deleteSegment(hit));
			menu.add(deleteSeg);

			JMenuItem insertCp = new JMenuItem(Translation.get("landWaterTool.edit.insertControlPoint"));
			final java.awt.Point clickPanelPoint = e.getPoint();
			insertCp.addActionListener(ev -> insertControlPointIntoSegment(hit, clickPanelPoint));
			menu.add(insertCp);

			// "Select Segment" makes the hit segment the sticky selection. Only rivers (the width slider
			// needs a target); only in edit mode (where the slider applies); and only when not already
			// the sticky one.
			boolean alreadySticky = hit.river() != null && hit.river() == stickyRiver && hit.segmentIndex() == stickySegmentIndex;
			if (modeWidget.isEditMode() && hit.river() != null && !alreadySticky)
			{
				JMenuItem selectSeg = new JMenuItem(Translation.get("landWaterTool.edit.selectSegment"));
				selectSeg.addActionListener(ev -> selectSegmentFromHit(hit));
				menu.add(selectSeg);
			}
		}
		if (menu.getComponentCount() > 0)
		{
			menu.show(e.getComponent(), e.getX(), e.getY());
		}
	}

	private void selectSegmentFromHit(LineHit hit)
	{
		// Only rivers can be sticky-selected (the width slider needs a target). The "Select Segment"
		// menu item is gated to river hits, so we shouldn't reach here for a road.
		if (hit.river() == null)
		{
			return;
		}
		setStickyRiverSegment(hit.river(), hit.segmentIndex());
		syncSliderToSelectedSegment();
		mapEditingPanel.clearHighlightedPolylines();
		applyStickySegmentHighlight();
		mapEditingPanel.repaint();
	}

	/**
	 * Deletes the control point identified by {@code hit} (must be a control-point hit). The sticky
	 * selection is preserved across the edit: if the deleted CP belongs to the sticky river/road,
	 * {@link #stickySegmentIndex} is adjusted so it tracks the same geometric segment when possible.
	 * If the line drops below 2 control points, the line is removed and any sticky selection on it
	 * is cleared.
	 */
	private void deleteControlPoint(LineHit hit)
	{
		int idx = hit.controlPointIndex();
		if (hit.river() != null)
		{
			River line = hit.river();
			List<RiverPathNode> nodes = new ArrayList<>(line.nodes);
			if (idx < 0 || idx >= nodes.size())
			{
				return;
			}
			List<Point> beforePath = PathOperations.toLocationList(nodes);
			if (idx > 0 && idx < nodes.size() - 1)
			{
				// Middle: the previous node's outgoing segment now spans to nodes[idx+1]. Clear its
				// edge index because the new bridging segment doesn't follow a single Voronoi edge.
				RiverPathNode prev = nodes.get(idx - 1);
				nodes.set(idx - 1, new RiverPathNode(prev.getLoc(), prev.getWidthLevelToNext(), prev.getSeedToNext(), RiverPathNode.EDGE_INDEX_NONE));
			}
			nodes.remove(idx);
			adjustStickyAfterControlPointDelete(line, null, idx, nodes.size());
			// After delete: the post-delete index closest to where the change happened is min(idx, lastIndex). The redraw slice around
			// it (radius = CATMULL_ROM_PROPAGATION_RADIUS) covers the new bridging segment plus its tangent-reference neighbors.
			int afterIdx = Math.max(0, Math.min(idx, nodes.size() - 1));
			commitRiverEdit(line, beforePath, nodes, idx, afterIdx);
		}
		else if (hit.road() != null)
		{
			Road line = hit.road();
			List<RoadPathNode> nodes = new ArrayList<>(line.nodes);
			if (idx < 0 || idx >= nodes.size())
			{
				return;
			}
			List<Point> beforePath = PathOperations.toLocationList(nodes);
			nodes.remove(idx);
			adjustStickyAfterControlPointDelete(null, line, idx, nodes.size());
			int afterIdx = Math.max(0, Math.min(idx, nodes.size() - 1));
			commitRoadEdit(line, beforePath, nodes, idx, afterIdx);
		}
	}

	/**
	 * Deletes the segment identified by {@code hit} (must be a segment hit). Splits the line at that segment into a "first half" and a
	 * "second half". The first half mutates the existing line in place; the second half (if non-degenerate) becomes a new line. The
	 * sticky selection follows the segment when possible: if the sticky segment was before the deleted segment it stays in the first half;
	 * if it was after it moves to the second half. The deleted segment itself can't be sticky after the operation.
	 */
	private void deleteSegment(LineHit hit)
	{
		int idx = hit.segmentIndex();
		if (hit.river() != null)
		{
			River line = hit.river();
			List<RiverPathNode> nodes = new ArrayList<>(line.nodes);
			if (idx < 0 || idx >= nodes.size() - 1)
			{
				return;
			}
			List<RiverPathNode> firstHalf = new ArrayList<>(nodes.subList(0, idx + 1));
			List<RiverPathNode> secondHalf = new ArrayList<>(nodes.subList(idx + 1, nodes.size()));
			// The last node of the first half no longer has an outgoing segment; clear its "to-next"
			// metadata so we don't leave dangling width/seed/edgeIndex referencing the removed segment.
			if (!firstHalf.isEmpty())
			{
				RiverPathNode last = firstHalf.get(firstHalf.size() - 1);
				firstHalf.set(firstHalf.size() - 1, new RiverPathNode(last.getLoc(), 0, 0L, RiverPathNode.EDGE_INDEX_NONE));
			}
			List<River> changed = new ArrayList<>();
			if (firstHalf.size() >= 2)
			{
				line.nodes = new java.util.concurrent.CopyOnWriteArrayList<>(firstHalf);
				changed.add(line);
			}
			else
			{
				mainWindow.edits.rivers.remove(line);
			}
			River newRiver = null;
			if (secondHalf.size() >= 2)
			{
				newRiver = new River(secondHalf);
				mainWindow.edits.rivers.add(newRiver);
				changed.add(newRiver);
			}
			adjustStickyAfterRiverSegmentDelete(line, newRiver, idx, firstHalf.size());
			List<List<Point>> removedSegments = Collections.singletonList(Arrays.asList(nodes.get(idx).getLoc(), nodes.get(idx + 1).getLoc()));
			commitRiverCut(removedSegments);
		}
		else if (hit.road() != null)
		{
			Road line = hit.road();
			List<RoadPathNode> nodes = new ArrayList<>(line.nodes);
			if (idx < 0 || idx >= nodes.size() - 1)
			{
				return;
			}
			List<RoadPathNode> firstHalf = new ArrayList<>(nodes.subList(0, idx + 1));
			List<RoadPathNode> secondHalf = new ArrayList<>(nodes.subList(idx + 1, nodes.size()));
			List<Road> changed = new ArrayList<>();
			if (firstHalf.size() >= 2)
			{
				line.nodes = new java.util.concurrent.CopyOnWriteArrayList<>(firstHalf);
				changed.add(line);
			}
			else
			{
				mainWindow.edits.roads.remove(line);
			}
			Road newRoad = null;
			if (secondHalf.size() >= 2)
			{
				newRoad = new Road(secondHalf);
				mainWindow.edits.roads.add(newRoad);
				changed.add(newRoad);
			}
			adjustStickyAfterRoadSegmentDelete(line, newRoad, idx, firstHalf.size());
			List<List<Point>> removedSegments = Collections.singletonList(Arrays.asList(nodes.get(idx).getLoc(), nodes.get(idx + 1).getLoc()));
			commitRoadCut(removedSegments, changed);
		}
	}

	/**
	 * Inserts a new control point at {@code panelPoint} into the segment identified by {@code hit} (must be a segment hit). The sticky
	 * selection is preserved: if the sticky segment was the one being inserted into, it stays as the "first half" of the split; sticky
	 * segments after the insert point shift by 1 to remain on the same geometric segment.
	 */
	private void insertControlPointIntoSegment(LineHit hit, java.awt.Point panelPoint)
	{
		Point clickRI = getPointOnGraph(panelPoint).mult(1.0 / mainWindow.displayQualityScale);
		int idx = hit.segmentIndex();
		if (hit.river() != null)
		{
			River line = hit.river();
			List<RiverPathNode> nodes = new ArrayList<>(line.nodes);
			if (idx < 0 || idx >= nodes.size() - 1)
			{
				return;
			}
			List<Point> beforePath = PathOperations.toLocationList(nodes);
			RiverPathNode boundary = nodes.get(idx);
			int width = boundary.getWidthLevelToNext();
			Random random = new Random();
			// Existing node[idx] now goes to the new node — keep its width but generate a fresh seed
			// and clear its edge index since the split segment no longer follows a single Voronoi edge.
			nodes.set(idx, new RiverPathNode(boundary.getLoc(), width, random.nextLong(), RiverPathNode.EDGE_INDEX_NONE));
			// New node carries its outgoing segment's width with a fresh seed; no edge index either.
			RiverPathNode newNode = new RiverPathNode(clickRI, width, random.nextLong(), RiverPathNode.EDGE_INDEX_NONE);
			nodes.add(idx + 1, newNode);
			adjustStickyAfterControlPointInsert(line, null, idx);
			// The split segment ran from idx to idx+1 in beforePath; the new node lands at idx+1 in the post-insert path. Slicing
			// around those indices (radius = CATMULL_ROM_PROPAGATION_RADIUS) covers every segment whose tangent-reference position
			// shifted because of the insert.
			commitRiverEdit(line, beforePath, nodes, idx, idx + 1);
		}
		else if (hit.road() != null)
		{
			Road line = hit.road();
			List<RoadPathNode> nodes = new ArrayList<>(line.nodes);
			if (idx < 0 || idx >= nodes.size() - 1)
			{
				return;
			}
			List<Point> beforePath = PathOperations.toLocationList(nodes);
			nodes.add(idx + 1, new RoadPathNode(clickRI));
			adjustStickyAfterControlPointInsert(null, line, idx);
			commitRoadEdit(line, beforePath, nodes, idx, idx + 1);
		}
	}

	/**
	 * Adjusts {@link #stickySegmentIndex} after a control point was removed from a line. If the sticky line wasn't the modified line, no
	 * change. Otherwise the index is shifted to track the same geometric segment when possible. If the line drops below 2 nodes the sticky
	 * is cleared.
	 *
	 * @param river
	 *            the river that was modified, or null if a road was modified
	 * @param road
	 *            the road that was modified, or null if a river was modified
	 * @param deletedIdx
	 *            index of the deleted control point in the pre-delete node list
	 * @param newSize
	 *            number of nodes remaining after the delete
	 */
	private void adjustStickyAfterControlPointDelete(River river, Road road, int deletedIdx, int newSize)
	{
		boolean affectsSticky = (river != null && river == stickyRiver) || (road != null && road == stickyRoad);
		if (!affectsSticky || stickySegmentIndex < 0)
		{
			return;
		}
		int newSegmentCount = newSize - 1;
		if (newSegmentCount < 1)
		{
			clearStickySegment();
			return;
		}
		int K = stickySegmentIndex;
		if (deletedIdx == 0)
		{
			// First CP deleted: segment 0 disappears, segments K >= 1 shift to K - 1.
			stickySegmentIndex = Math.max(0, K - 1);
		}
		else if (deletedIdx == newSize)
		{
			// Last CP deleted (deletedIdx equals the old last-node index, which is newSize after delete).
			// Segment newSize-1 (= old size-2) disappears. Earlier segments unchanged.
			if (K >= newSegmentCount)
			{
				stickySegmentIndex = newSegmentCount - 1;
			}
		}
		else
		{
			// Middle CP deleted. Segments deletedIdx-1 and deletedIdx merge into segment deletedIdx-1.
			// Segments > deletedIdx shift down by 1.
			if (K < deletedIdx - 1)
			{
				// unchanged
			}
			else if (K == deletedIdx - 1 || K == deletedIdx)
			{
				stickySegmentIndex = deletedIdx - 1;
			}
			else
			{
				stickySegmentIndex = K - 1;
			}
		}
		if (stickySegmentIndex >= newSegmentCount || stickySegmentIndex < 0)
		{
			clearStickySegment();
		}
	}

	/**
	 * Adjusts sticky-segment indices after a segment was deleted from a river. If the sticky river was the modified one, the sticky may
	 * migrate to the newly-split second half river ({@code newRiver}), or be cleared entirely if it landed on the deleted segment.
	 */
	private void adjustStickyAfterRiverSegmentDelete(River modifiedRiver, River newRiver, int deletedSegIdx, int firstHalfSize)
	{
		if (stickyRiver != modifiedRiver || stickySegmentIndex < 0)
		{
			return;
		}
		int K = stickySegmentIndex;
		if (K < deletedSegIdx)
		{
			if (firstHalfSize >= 2)
			{
				// Sticky stays in the first half (still the original line).
				return;
			}
			clearStickySegment();
		}
		else if (K == deletedSegIdx)
		{
			// Sticky segment was the deleted one.
			clearStickySegment();
		}
		else
		{
			// K > deletedSegIdx — sticky belongs to what's now the second half.
			if (newRiver != null)
			{
				int newIdx = K - (deletedSegIdx + 1);
				if (newIdx >= 0 && newIdx < newRiver.nodes.size() - 1)
				{
					setStickyRiverSegment(newRiver, newIdx);
					return;
				}
			}
			clearStickySegment();
		}
	}

	/** Road counterpart of {@link #adjustStickyAfterRiverSegmentDelete}. */
	private void adjustStickyAfterRoadSegmentDelete(Road modifiedRoad, Road newRoad, int deletedSegIdx, int firstHalfSize)
	{
		if (stickyRoad != modifiedRoad || stickySegmentIndex < 0)
		{
			return;
		}
		int K = stickySegmentIndex;
		if (K < deletedSegIdx)
		{
			if (firstHalfSize >= 2)
			{
				return;
			}
			clearStickySegment();
		}
		else if (K == deletedSegIdx)
		{
			clearStickySegment();
		}
		else
		{
			if (newRoad != null)
			{
				int newIdx = K - (deletedSegIdx + 1);
				if (newIdx >= 0 && newIdx < newRoad.nodes.size() - 1)
				{
					setStickyRoadSegment(newRoad, newIdx);
					return;
				}
			}
			clearStickySegment();
		}
	}

	/**
	 * Adjusts {@link #stickySegmentIndex} after a control point was inserted into segment {@code splitSegmentIdx} of a line. If the sticky
	 * line wasn't the modified one, no change. The sticky segment stays on the same geometric portion when possible: the original split
	 * segment becomes the new "first half" (kept as sticky); segments after the insert shift up by 1.
	 */
	private void adjustStickyAfterControlPointInsert(River river, Road road, int splitSegmentIdx)
	{
		boolean affectsSticky = (river != null && river == stickyRiver) || (road != null && road == stickyRoad);
		if (!affectsSticky || stickySegmentIndex < 0)
		{
			return;
		}
		int K = stickySegmentIndex;
		if (K > splitSegmentIdx)
		{
			stickySegmentIndex = K + 1;
		}
		// K <= splitSegmentIdx: unchanged (the first half keeps the same index as the original segment).
	}

	/**
	 * Commits a modified river's node list in place and triggers a redraw scoped to the segments affected by changing a single control
	 * point. Pass the deleted/inserted/moved index in {@code beforeChangedIndex} (relative to {@code beforePath}) and the corresponding
	 * index in the new node list in {@code afterChangedIndex}. The slice radius is {@link PathOperations#CATMULL_ROM_PROPAGATION_RADIUS}
	 * so the visible curve-shape change is fully covered. Preserves sticky selection.
	 */
	private void commitRiverEdit(River line, List<Point> beforePath, List<RiverPathNode> newNodes, int beforeChangedIndex, int afterChangedIndex)
	{
		boolean removed = newNodes.size() < 2;
		if (removed)
		{
			mainWindow.edits.rivers.remove(line);
			if (stickyRiver == line)
			{
				clearStickySegment();
			}
		}
		else
		{
			line.nodes = new java.util.concurrent.CopyOnWriteArrayList<>(newNodes);
		}
		List<List<Point>> centerPaths = new ArrayList<>();
		appendControlPointEditScopeFromSnapshot(centerPaths, beforePath, beforeChangedIndex);
		if (!removed)
		{
			appendControlPointEditScope(centerPaths, line.nodes, afterChangedIndex);
		}
		finishRiverPostEdit(getCentersTouchingPoints(centerPaths));
	}

	/**
	 * Used by the segment-delete (cut) path: redraws the removed-segment area plus the inner neighbors of the new end segments. See
	 * {@link PathOperations#findInnerNeighborsOfCutEndpoints} for why the inner neighbors are required (Catmull-Rom end segments use a
	 * synthetic reflection control point, so the curve shape on the new end segments changes and the redraw bounds must cover them).
	 */
	private void commitRiverCut(List<List<Point>> removedSegments)
	{
		Set<Center> centersToRedraw = getCentersTouchingPoints(
				pointsToCoverInRedrawAfterPathCut(mainWindow.edits.rivers, r -> r.nodes, removedSegments));
		finishRiverPostEdit(centersToRedraw);
	}

	private void finishRiverPostEdit(Set<Center> centersToRedraw)
	{
		undoer.setUndoPoint(UpdateType.Incremental, this);
		updater.createAndShowMapIncrementalUsingCenters(centersToRedraw);
		mapEditingPanel.clearHighlightedPolylines();
		applyStickySegmentHighlight();
		mapEditingPanel.repaint();
	}

	/** Road counterpart of {@link #commitRiverEdit}. */
	private void commitRoadEdit(Road line, List<Point> beforePath, List<RoadPathNode> newNodes, int beforeChangedIndex, int afterChangedIndex)
	{
		boolean removed = newNodes.size() < 2;
		if (removed)
		{
			mainWindow.edits.roads.remove(line);
			if (stickyRoad == line)
			{
				clearStickySegment();
			}
		}
		else
		{
			line.nodes = new java.util.concurrent.CopyOnWriteArrayList<>(newNodes);
		}
		List<List<Point>> centerPaths = new ArrayList<>();
		appendControlPointEditScopeFromSnapshot(centerPaths, beforePath, beforeChangedIndex);
		List<Road> changed = removed ? Collections.emptyList() : Collections.singletonList(line);
		if (!removed)
		{
			appendControlPointEditScope(centerPaths, line.nodes, afterChangedIndex);
		}
		finishRoadPostEdit(getCentersTouchingPoints(centerPaths), changed);
	}

	/** Road counterpart of {@link #commitRiverCut}. */
	private void commitRoadCut(List<List<Point>> removedSegments, List<Road> changed)
	{
		Set<Center> centersToRedraw = getCentersTouchingPoints(
				pointsToCoverInRedrawAfterPathCut(mainWindow.edits.roads, r -> r.nodes, removedSegments));
		finishRoadPostEdit(centersToRedraw, changed);
	}

	private void finishRoadPostEdit(Set<Center> centersToRedraw, List<Road> changed)
	{
		undoer.setUndoPoint(UpdateType.Incremental, this);
		if (!changed.isEmpty())
		{
			updater.addRoadsToRedrawLowPriority(changed, mainWindow.displayQualityScale);
		}
		updater.createAndShowMapIncrementalUsingCenters(centersToRedraw);
		updater.doWhenMapIsNotDrawing(() -> updater.createAndShowLowPriorityChanges());
		mapEditingPanel.clearHighlightedPolylines();
		applyStickySegmentHighlight();
		mapEditingPanel.repaint();
	}

	@Override
	protected void handleMouseReleasedOnMap(MouseEvent e)
	{
		regionIdToExpand = null;

		if (dragInProgress)
		{
			boolean wasDrag = dragOccurred;
			List<Point> snapshot = dragSnapshotPath;
			dragInProgress = false;
			dragOccurred = false;
			dragSnapshotPath = null;
			// Only commit when the user actually moved the cursor; a click-without-drag on a control
			// point is just a select and shouldn't push an undo point.
			if (wasDrag)
			{
				handleEditModeControlPointDragEnd(snapshot);
			}
			else
			{
				// click-without-drag: discard the shared-target list captured at press time.
				dragRiver = null;
				dragRoad = null;
				dragControlPointIndex = -1;
				clearDragSharedTargets();
			}
			return;
		}

		if (riversButton.isSelected() && modeWidget.isDrawMode() && !isFreeHandRiverDrawMode() && riverStart != null)
		{
			Corner end = updater.mapParts.graph.findClosestCorner(getPointOnGraph(e.getPoint()));
			Point polygonRiverSnapEnd = computeSnapPointForType(e.getPoint(), LineType.RIVER);
			Set<Edge> river = filterOutOceanAndCoastEdges(updater.mapParts.graph.findPathGreedy(riverStart, end));
			if (DebugFlags.printRiverEdgeIndexes())
			{
				System.out.println("River edges (polygon mode):");
				for (Edge edge : river)
				{
					System.out.println("  index=" + edge.index);
				}
			}
			int base = (riverWidthSlider.getValue() - 1);
			int riverLevel = base * base * 2 + GraphRiver.RIVERS_THIS_SIZE_OR_SMALLER_WILL_NOT_BE_DRAWN + 1;
			List<River> newRivers = RiverDrawer.addRiversFromEdgesInEditor(river, riverStart, riverLevel, mainWindow.displayQualityScale, mainWindow.edits.rivers);

			if (polygonRiverSnapStart != null || polygonRiverSnapEnd != null)
			{
				Point riverStartRI = riverStart.loc.mult(1.0 / mainWindow.displayQualityScale);
				Point endRI = end.loc.mult(1.0 / mainWindow.displayQualityScale);
				for (River r : newRivers)
				{
					if (r == null || r.nodes.isEmpty())
					{
						continue;
					}
					applyRiverSnapPoints(r, polygonRiverSnapStart, riverStartRI, polygonRiverSnapEnd, endRI);
				}
			}

			// After snap points are prepended/appended, try to merge each changed river with any
			// existing river whose endpoint now matches the snap point. This handles continuing a
			// freehand river using polygon mode: the snap prepend puts the freehand river's endpoint
			// at the start of the new river, but addRiversFromEdgesInEditor ran before that prepend
			// and could not see the connection.
			if (polygonRiverSnapStart != null || polygonRiverSnapEnd != null)
			{
				for (int i = 0; i < newRivers.size(); i++)
				{
					River r = newRivers.get(i);
					if (r == null || r.nodes.isEmpty())
					{
						continue;
					}
					River joined = RiverDrawer.tryConnectingRiverToExistingRiver(r, mainWindow.edits.rivers);
					if (joined != null)
					{
						mainWindow.edits.rivers.remove(r);
						newRivers.set(i, joined);
					}
				}
			}

			riverStart = null;
			polygonRiverSnapStart = null;
			mapEditingPanel.clearHighlightedEdges();
			mapEditingPanel.clearHighlightedPolylines();
			mapEditingPanel.repaint();

			if (!newRivers.isEmpty())
			{
				List<List<Point>> pathsForCenters = newRivers.stream().map(r -> PathOperations.toLocationList(r.nodes)).collect(Collectors.toList());
				updater.createAndShowMapIncrementalUsingCenters(getCentersTouchingPoints(pathsForCenters));
			}
		}
		else if (roadsButton.isSelected() && modeWidget.isDrawMode() && !isFreeHandDrawMode() && roadStart != null)
		{
			Point polygonSnapEnd = computeSnapPointForType(e.getPoint(), LineType.ROAD);
			Center end = updater.mapParts.graph.findClosestCenter(getPointOnGraph(e.getPoint()));
			List<Edge> edges = updater.mapParts.graph.findShortestPath(roadStart, end, (ignored1, ignored2, distance) -> distance);

			mapEditingPanel.clearHighlightedEdges();
			mapEditingPanel.clearHighlightedPolylines();
			clearRoadControlPointDisplay();
			mapEditingPanel.repaint();

			List<Road> changed = RoadDrawer.addRoadsFromEdgesInEditor(edges, updater.mapParts.graph, mainWindow.edits.roads, mainWindow.displayQualityScale);

			// If snap points are set, extend the road's endpoints to connect to those exact control-point locations.
			// Skip if the snap point already equals the center location (already the natural start/end of the Delaunay road).
			if (polygonRoadSnapStart != null || polygonSnapEnd != null)
			{
				Point roadStartRI = roadStart.loc.mult(1.0 / mainWindow.displayQualityScale);
				Point endRI = end.loc.mult(1.0 / mainWindow.displayQualityScale);
				for (Road road : changed)
				{
					if (road == null || road.nodes.isEmpty())
					{
						continue;
					}
					applyRoadSnapPoints(road, polygonRoadSnapStart, roadStartRI, polygonSnapEnd, endRI);
				}
			}

			// After snap points are prepended/appended, try to merge each changed road with any existing
			// road whose endpoint now matches the snap point. This handles continuing a freehand road
			// using polygon mode: the snap prepend puts the freehand road's endpoint at the start of
			// the new road, but addRoadsFromEdgesInEditor ran before that prepend and could not see
			// the connection.
			if (polygonRoadSnapStart != null || polygonSnapEnd != null)
			{
				for (int i = 0; i < changed.size(); i++)
				{
					Road road = changed.get(i);
					if (road == null || road.nodes.isEmpty())
					{
						continue;
					}
					Road joined = RoadDrawer.tryConnectingRoadToExistingRoad(road, mainWindow.edits.roads);
					if (joined != null)
					{
						mainWindow.edits.roads.remove(road);
						changed.set(i, joined);
					}
				}
			}

			updater.addRoadsToRedrawLowPriority(changed, mainWindow.displayQualityScale);
			updater.createAndShowMapIncrementalUsingEdges(new HashSet<Edge>(edges));

			polygonRoadSnapStart = null;
		}

		updater.doWhenMapIsNotDrawing(() -> updater.createAndShowLowPriorityChanges());

		undoer.setUndoPoint(UpdateType.Incremental, this);
	}

	private Set<Edge> filterOutOceanAndCoastEdges(Set<Edge> edges)
	{
		return edges.stream().filter(e -> (e.d0 == null || !e.d0.isWater) && (e.d1 == null || !e.d1.isWater)).collect(Collectors.toSet());
	}

	@Override
	protected void handleMouseMovedOnMap(MouseEvent e)
	{
		highlightHoverCentersOrEdgesAndBrush(e.getPoint());
	}

	protected void highlightHoverCentersOrEdgesAndBrush(java.awt.Point mouseLocation)
	{
		if (mouseLocation == null)
		{
			return;
		}

		mapEditingPanel.clearHighlightedCenters();
		mapEditingPanel.clearHighlightedEdges();
		mapEditingPanel.clearHighlightedPolylines();
		mapEditingPanel.hideBrush();

		boolean isSelectingColorFromMap = selectColorFromMapButton.isVisible() && selectColorFromMapButton.isSelected();
		if (isSelectingColorFromMap || mergeRegionsButton.isSelected() || fillRegionColorButton.isSelected())
		{
			if (updater.mapParts == null || updater.mapParts.graph == null)
			{
				assert false;
				return;
			}
			Center center = updater.mapParts.graph.findClosestCenter(getPointOnGraph(mouseLocation), true);
			if (center != null)
			{
				if (center.region != null)
				{
					mapEditingPanel.addHighlightedCenters(center.region.getCenters());
				}
				mapEditingPanel.setCenterHighlightMode(HighlightMode.outlineGroup);
			}
		}
		else if (oceanButton.isSelected() || lakesButton.isSelected() || landButton.isSelected())
		{
			Set<Center> selected = getSelectedCenters(mouseLocation);

			if (DebugFlags.printCenterIndexes())
			{
				System.out.println("Highlighted center indexes:");
				for (Center center : selected)
				{
					System.out.println(center.index);
				}
			}

			mapEditingPanel.addHighlightedCenters(selected);
			mapEditingPanel.setCenterHighlightMode(HighlightMode.outlineEveryCenter);
		}
		else if (riversButton.isSelected() && modeWidget.isEraseMode())
		{
			int brushDiameter = brushSizes.get(brushSizeComboBox.getSelectedIndex());
			if (brushDiameter > 1)
			{
				mapEditingPanel.showBrush(mouseLocation, brushDiameter);
			}
			List<List<Point>> riverSegments = getSelectedRiverSegments(mouseLocation);
			for (List<Point> segment : scalePoints(riverSegments, mainWindow.displayQualityScale))
			{
				mapEditingPanel.addPolylinesToHighlight(segment);
			}
		}
		else if (riversButton.isSelected() && modeWidget.isDrawMode())
		{
			updateControlPointDisplay(mouseLocation, LineType.RIVER);
		}
		else if ((riversButton.isSelected() || roadsButton.isSelected()) && modeWidget.isEditMode())
		{
			LineType activeType = riversButton.isSelected() ? LineType.RIVER : LineType.ROAD;
			updateEditModeHoverDisplay(mouseLocation, activeType);
			// Re-apply the sticky segment polyline after the polyline clear above so it persists
			// across mouse moves.
			applyStickySegmentHighlight();
		}
		else if (roadsButton.isSelected() && modeWidget.isEraseMode())
		{
			int brushDiameter = brushSizes.get(brushSizeComboBox.getSelectedIndex());
			if (brushDiameter > 1)
			{
				mapEditingPanel.showBrush(mouseLocation, brushDiameter);
			}

			List<List<Point>> roadSegments = getSelectedRoadSegments(mouseLocation);
			for (List<Point> list : scalePoints(roadSegments, mainWindow.displayQualityScale))
			{
				mapEditingPanel.addPolylinesToHighlight(list);
			}
		}
		else if (roadsButton.isSelected() && modeWidget.isDrawMode())
		{
			updateControlPointDisplay(mouseLocation, LineType.ROAD);
		}

		mapEditingPanel.repaint();
	}

	private List<List<Point>> scalePoints(List<List<Point>> points, double scale)
	{
		List<List<Point>> scaledPoints = new ArrayList<>();

		for (List<Point> pointList : points)
		{
			List<Point> scaledPointList = new ArrayList<>();
			for (Point point : pointList)
			{
				Point scaledPoint = new Point(point.x * scale, point.y * scale);
				scaledPointList.add(scaledPoint);
			}
			scaledPoints.add(scaledPointList);
		}

		return scaledPoints;
	}

	@Override
	protected void handleMouseDraggedOnMap(MouseEvent e)
	{
		if (dragInProgress && modeWidget.isEditMode() && (riversButton.isSelected() || roadsButton.isSelected()))
		{
			handleEditModeControlPointDrag(e);
			return;
		}
		if (riversButton.isSelected() && modeWidget.isDrawMode() && !isFreeHandRiverDrawMode())
		{
			if (riverStart != null)
			{
				mapEditingPanel.clearHighlightedEdges();
				mapEditingPanel.clearHighlightedPolylines();
				Corner end = updater.mapParts.graph.findClosestCorner(getPointOnGraph(e.getPoint()));
				Set<Edge> river = filterOutOceanAndCoastEdges(updater.mapParts.graph.findPathGreedy(riverStart, end));
				mapEditingPanel.addHighlightedEdges(river, EdgeType.Voronoi);
				// Preview the snap-back connection that will be added when the mouse is released, so the
				// user isn't surprised by the gap between the polygon path and the freehand control point
				// they clicked on (or the one they're hovering near at the other end).
				Point currentEndSnapPoint = computeSnapPointForType(e.getPoint(), LineType.RIVER);
				Point riverStartRI = riverStart.loc.mult(1.0 / mainWindow.displayQualityScale);
				Point endRI = end == null ? null : end.loc.mult(1.0 / mainWindow.displayQualityScale);
				if (polygonRiverSnapStart != null && !polygonRiverSnapStart.isCloseEnough(riverStartRI))
				{
					mapEditingPanel.addPolylinesToHighlight(List.of(polygonRiverSnapStart.mult(mainWindow.displayQualityScale), riverStart.loc));
				}
				if (currentEndSnapPoint != null && endRI != null && !currentEndSnapPoint.isCloseEnough(endRI))
				{
					mapEditingPanel.addPolylinesToHighlight(List.of(end.loc, currentEndSnapPoint.mult(mainWindow.displayQualityScale)));
				}
				updateControlPointDisplay(e.getPoint(), LineType.RIVER);
				mapEditingPanel.repaint();
			}
		}
		else if (riversButton.isSelected() && modeWidget.isDrawMode() && isFreeHandRiverDrawMode())
		{
			updateControlPointDisplay(e.getPoint(), LineType.RIVER);
			mapEditingPanel.repaint();
		}
		else if (roadsButton.isSelected() && modeWidget.isDrawMode() && isFreeHandDrawMode())
		{
			// Update control point snap display and the in-progress preview while the mouse moves.
			updateControlPointDisplay(e.getPoint(), LineType.ROAD);
			mapEditingPanel.repaint();
		}
		else if (roadsButton.isSelected() && modeWidget.isDrawMode())
		{
			if (roadStart != null)
			{
				mapEditingPanel.clearHighlightedEdges();
				mapEditingPanel.clearHighlightedPolylines();
				Center end = updater.mapParts.graph.findClosestCenter(getPointOnGraph(e.getPoint()));
				List<Edge> edges = updater.mapParts.graph.findShortestPath(roadStart, end, (ignored1, ignored2, distance) -> distance);
				Point roadStartRI = roadStart.loc.mult(1.0 / mainWindow.displayQualityScale);
				Point currentEndSnapPoint = computeSnapPointForType(e.getPoint(), LineType.ROAD);
				Point endRI = end.loc.mult(1.0 / mainWindow.displayQualityScale);
				boolean snapStartActive = polygonRoadSnapStart != null && !polygonRoadSnapStart.isCloseEnough(roadStartRI);
				boolean snapEndActive = currentEndSnapPoint != null && !currentEndSnapPoint.isCloseEnough(endRI);
				if (edges != null && !edges.isEmpty())
				{
					int startTrim = snapEndActive ? 1 : 0;
					int endTrim = snapStartActive ? edges.size() - 1 : edges.size();
					List<Edge> edgesToHighlight = (startTrim < endTrim) ? edges.subList(startTrim, endTrim) : List.of();
					mapEditingPanel.addHighlightedEdges(edgesToHighlight, EdgeType.Delaunay);
					if (snapEndActive)
					{
						Edge endEdge = edges.get(0);
						Center endNeighbor = endEdge.d0 == end ? endEdge.d1 : endEdge.d0;
						if (endNeighbor != null)
						{
							mapEditingPanel.addPolylinesToHighlight(List.of(endNeighbor.loc, currentEndSnapPoint.mult(mainWindow.displayQualityScale)));
						}
					}
					if (snapStartActive)
					{
						Edge roadStartEdge = edges.get(edges.size() - 1);
						Center snapNeighbor = roadStartEdge.d0 == roadStart ? roadStartEdge.d1 : roadStartEdge.d0;
						if (snapNeighbor != null)
						{
							mapEditingPanel.addPolylinesToHighlight(List.of(polygonRoadSnapStart.mult(mainWindow.displayQualityScale), snapNeighbor.loc));
						}
					}
				}
				else
				{
					mapEditingPanel.addHighlightedEdges(edges, EdgeType.Delaunay);
				}
				updateControlPointDisplay(e.getPoint(), LineType.ROAD);
				mapEditingPanel.repaint();
			}
		}
		else
		{
			handleMousePressOrDrag(e, true);
		}
	}

	@Override
	protected void handleMouseExitedMap(MouseEvent e)
	{
		mapEditingPanel.clearHighlightedCenters();
		if ((riversButton.isSelected() || roadsButton.isSelected()) && modeWidget.isEraseMode())
		{
			mapEditingPanel.clearHighlightedEdges();
			mapEditingPanel.clearHighlightedPolylines();
		}
		if (roadsButton.isSelected() && modeWidget.isDrawMode())
		{
			freeHandRoadSnapPoint = null;
			mapEditingPanel.clearHoveredControlPoint();
			// Keep circles and preview path visible so the user can see them even when the mouse exits.
		}
		if (riversButton.isSelected() && modeWidget.isDrawMode() && isFreeHandRiverDrawMode())
		{
			freeHandRiverSnapPoint = null;
			mapEditingPanel.clearHoveredControlPoint();
		}
		if ((riversButton.isSelected() || roadsButton.isSelected()) && modeWidget.isEditMode())
		{
			// Clear hover state and the hover ring, but keep the sticky-line CPs and segment highlight
			// visible so the user can see what they had selected even with the cursor off the map.
			clearHoverState();
			mapEditingPanel.clearHoveredControlPoint();
		}
		mapEditingPanel.hideBrush();
		mapEditingPanel.repaint();
	}

	@Override
	protected void onAfterShowMap()
	{
		java.awt.Point mousePosition = mapEditingPanel.getMousePosition();
		updateHighlightsForMousePosition(mousePosition);
	}

	@Override
	public void onSwitchingTo()
	{
		super.onSwitchingTo();
		updater.doWhenMapIsReadyForInteractions(() ->
		{
			if (isSelected())
			{
				updateHighlightsForMousePosition(mapEditingPanel.getMousePosition());
			}
		});
	}

	private void updateHighlightsForMousePosition(java.awt.Point mousePosition)
	{
		if (mousePosition == null)
		{
			return;
		}

		if (roadsButton.isSelected() && modeWidget.isDrawMode())
		{
			updateControlPointDisplay(mousePosition, LineType.ROAD);
			mapEditingPanel.repaint();
		}
		else
		{
			highlightHoverCentersOrEdgesAndBrush(mousePosition);
		}
	}


	@Override
	public void onSwitchingAway()
	{
		cancelFreeHandDrawing(LineType.ROAD);
		cancelFreeHandDrawing(LineType.RIVER);
		clearStickySegment();
		clearHoverState();
		if (selectedRegion != null)
		{
			selectedRegion = null;
			mapEditingPanel.clearSelectedCenters();
		}
	}

	@Override
	protected void onAfterUndoRedo()
	{
		cancelFreeHandDrawing(LineType.ROAD);
		cancelFreeHandDrawing(LineType.RIVER);
		// Sticky/hover may reference river/road instances that no longer exist after undo/redo.
		clearStickySegment();
		clearHoverState();
		selectedRegion = null;
		mapEditingPanel.clearSelectedCenters();
		mapEditingPanel.clearHighlightedCenters();
		mapEditingPanel.clearHighlightedPolylines();
		mapEditingPanel.repaint();
	}

	@Override
	public void loadSettingsIntoGUI(MapSettings settings, boolean isUndoRedoOrAutomaticChange, boolean refreshImagePreviews)
	{
		areRegionColorsVisible = settings.drawRegionColors;
		areRegionBoundariesVisible = settings.drawRegionBoundaries;
		areRoadsVisible = settings.drawRoads;

		// These settings are part of MapSettings, so they get pulled in by undo/redo, but I exclude them here
		// because it feels weird to me to have them change with undo/redo since they don't directly affect the map.
		if (!isUndoRedoOrAutomaticChange)
		{
			baseColorPanel.setBackground(AwtBridge.toAwtColor(settings.regionBaseColor));
			hueSlider.setValue(settings.hueRange);
			saturationSlider.setValue(settings.saturationRange);
			brightnessSlider.setValue(settings.brightnessRange);

			// I'm setting this color here because I only want it to change when you create new settings or load settings from a file,
			// not on undo/redo or in response to the ThemePanel changing.
			colorDisplay.setBackground(AwtBridge.toAwtColor(settings.regionBaseColor));
		}

		// Clear any selection
		selectedRegion = null;
		mapEditingPanel.clearSelectedCenters();

		showOrHideBrushOptions();
	}

	@Override
	public void getSettingsFromGUI(MapSettings settings)
	{
		settings.regionBaseColor = AwtBridge.fromAwtColor(baseColorPanel.getBackground());
		settings.hueRange = hueSlider.getValue();
		settings.saturationRange = saturationSlider.getValue();
		settings.brightnessRange = brightnessSlider.getValue();
	}

	@Override
	public void handleEnablingAndDisabling(MapSettings settings)
	{
		// There's nothing to do because this tool never disables anything.
	}

	@Override
	public void onBeforeLoadingNewMap()
	{
	}

	@Override
	protected void onBeforeUndoRedo()
	{
		cancelFreeHandDrawing(LineType.ROAD);
	}
}
