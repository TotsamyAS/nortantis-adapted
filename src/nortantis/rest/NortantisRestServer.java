package nortantis.rest;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import nortantis.ImageCache;
import nortantis.ImageAndMasks;
import nortantis.Background;
import nortantis.BorderPosition;
import nortantis.IconType;
import nortantis.IconDrawer;
import nortantis.GraphRiver;
import nortantis.LandShape;
import nortantis.MapCreator;
import nortantis.MapSettings;
import nortantis.MapText;
import nortantis.NameCreator;
import nortantis.NameLanguagePack;
import nortantis.Region;
import nortantis.SettingsGenerator;
import nortantis.TextDrawer;
import nortantis.TextType;
import nortantis.WorldGraph;
import nortantis.editor.CenterEdit;
import nortantis.editor.CenterIcon;
import nortantis.editor.CenterIconType;
import nortantis.editor.CenterTrees;
import nortantis.editor.MapParts;
import nortantis.editor.RegionBoundary;
import nortantis.editor.RegionEdit;
import nortantis.editor.River;
import nortantis.editor.RiverPathNode;
import nortantis.editor.Road;
import nortantis.editor.RoadPathNode;
import nortantis.geom.Dimension;
import nortantis.geom.IntRectangle;
import nortantis.geom.Point;
import nortantis.geom.RotatedRectangle;
import nortantis.graph.voronoi.Center;
import nortantis.graph.voronoi.Corner;
import nortantis.graph.voronoi.Edge;
import nortantis.platform.Image;
import nortantis.platform.Font;
import nortantis.platform.FontStyle;
import nortantis.platform.Color;
import nortantis.platform.ImageHelper;
import nortantis.platform.PlatformFactory;
import nortantis.platform.awt.AwtFactory;
import nortantis.swing.MapEdits;
import nortantis.swing.translation.Translation;
import nortantis.util.GeometryHelper;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.imgscalr.Scalr.Method;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class NortantisRestServer
{
	private static final String BRUSH_LOG_PREFIX = "[nortantis-brush-service]";
	private static final String BORDERS_LOG_PREFIX = "[nortantis-borders-service]";
	private static final String REGION_PAINT_LOG_PREFIX = "[nortantis-region-paint-service]";
	private static final String TEXT_LOG_PREFIX = "[nortantis-text-service]";
	private static final String ICONS_LOG_PREFIX = "[nortantis-icons-service]";
	private static final String FORESTS_LOG_PREFIX = "[nortantis-forests-service]";
	private static final String ROADS_LOG_PREFIX = "[nortantis-roads-service]";
	private static final String RIVERS_LOG_PREFIX = "[nortantis-rivers-service]";
	private static final String GENERATION_LOG_PREFIX = "[nortantis-generation-service]";
	private static final String PROJECTS_LOG_PREFIX = "[nortantis-projects-service]";
	private static final String SAVE_EXPORT_LOG_PREFIX = "[nortantis-save-export-service]";
	private static final String ASSETS_LOG_PREFIX = "[nortantis-assets-service]";
	private static final ConcurrentHashMap<String, Boolean> SERVICE_LOGGING = new ConcurrentHashMap<>();
	private static final ConcurrentHashMap<String, EditorSession> EDITOR_SESSIONS = new ConcurrentHashMap<>();

	public static void main(String[] args) throws IOException
	{
		System.setProperty("java.awt.headless", "true");
		PlatformFactory.setInstance(new AwtFactory());
		Translation.initialize();
		loadServiceConfig();

		int port = Integer.parseInt(System.getenv().getOrDefault("NORTANTIS_REST_PORT", "8091"));
		HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
		server.createContext("/editor", NortantisRestServer::editor);
		server.createContext("/health", NortantisRestServer::health);
		server.createContext("/api/editor/session/open-stream", NortantisRestServer::editorSessionOpenStream);
		server.createContext("/api/editor/session/edit", NortantisRestServer::editProject);
		server.createContext("/api/editor/session/project", NortantisRestServer::editorSessionProject);
		server.createContext("/api/editor/session/generate-stream", NortantisRestServer::editorSessionGenerateStream);
		server.createContext("/api/editor/session/region-color", NortantisRestServer::regionColor);
		server.createContext("/api/editor/session/brush-selection", NortantisRestServer::brushSelection);
		server.createContext("/api/editor/session/text-pick", NortantisRestServer::textPick);
		server.createContext("/api/editor/session/history", NortantisRestServer::editorSessionHistory);
		server.createContext("/api/editor/session/path-preview", NortantisRestServer::editorSessionPathPreview);
		server.createContext("/api/editor/session/topology", NortantisRestServer::editorSessionTopology);
		server.createContext("/api/editor/assets/icons", NortantisRestServer::iconAssets);
		server.createContext("/api/editor/assets/icon-preview", NortantisRestServer::iconPreview);
		server.createContext("/api/projects/default", NortantisRestServer::defaultProject);
		server.createContext("/api/projects/export", NortantisRestServer::exportProject);
		server.createContext("/api/assets/icons", NortantisRestServer::iconAssets);
		server.start();
		System.out.println("Nortantis REST server listening on " + port);
	}

	private static void editor(HttpExchange exchange) throws IOException
	{
		if (!exchange.getRequestMethod().equals("GET"))
		{
			send(exchange, 405, "application/json", "{\"ok\":false}".getBytes(StandardCharsets.UTF_8));
			return;
		}
		String path = exchange.getRequestURI().getPath();
		String resource;
		String contentType;
		if ("/editor".equals(path) || "/editor/".equals(path))
		{
			resource = "/nortantis/rest/editor/index.html";
			contentType = "text/html; charset=utf-8";
		}
		else if ("/editor/editor.css".equals(path))
		{
			resource = "/nortantis/rest/editor/editor.css";
			contentType = "text/css; charset=utf-8";
		}
		else if ("/editor/editor.js".equals(path))
		{
			resource = "/nortantis/rest/editor/editor.js";
			contentType = "text/javascript; charset=utf-8";
		}
		else if (path.matches("^/editor/i18n/[a-z]{2}(?:-[a-z0-9]{2,8})*\\.json$"))
		{
			resource = "/nortantis/rest/editor" + path.substring("/editor".length());
			contentType = "application/json; charset=utf-8";
		}
		else
		{
			send(exchange, 404, "application/json", "{\"ok\":false,\"error\":\"notFound\"}".getBytes(StandardCharsets.UTF_8));
			return;
		}
		try (InputStream input = NortantisRestServer.class.getResourceAsStream(resource))
		{
			if (input == null)
			{
				send(exchange, 500, "application/json", "{\"ok\":false,\"error\":\"editorAssetMissing\"}".getBytes(StandardCharsets.UTF_8));
				return;
			}
			exchange.getResponseHeaders().set("Cache-Control", "no-store");
			send(exchange, 200, contentType, input.readAllBytes());
		}
	}

	private static void editorSessionOpenStream(HttpExchange exchange) throws IOException
	{
		if (!exchange.getRequestMethod().equals("POST"))
		{
			send(exchange, 405, "application/json", "{\"ok\":false}".getBytes(StandardCharsets.UTF_8));
			return;
		}
		JSONObject input;
		String sessionId;
		String projectBase64;
		try
		{
			input = readJsonBody(exchange);
			sessionId = input.get("sessionId") instanceof String ? ((String) input.get("sessionId")).trim() : "";
			projectBase64 = input.get("projectBase64") instanceof String ? (String) input.get("projectBase64") : "";
			if (sessionId.isEmpty() || projectBase64.isBlank())
			{
				throw new IllegalArgumentException("Missing session");
			}
		}
		catch (Exception ex)
		{
			send(exchange, 400, "application/json", ("{\"ok\":false,\"error\":\"" + escape(ex.getMessage()) + "\"}").getBytes(StandardCharsets.UTF_8));
			return;
		}

		exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
		exchange.getResponseHeaders().set("Cache-Control", "no-store");
		exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
		exchange.sendResponseHeaders(200, 0);
		Path project = Files.createTempFile("nortantis-editor-open-stream-", MapSettings.fileExtensionWithDot);
		long startedAt = System.nanoTime();
		try (OutputStream output = exchange.getResponseBody())
		{
			SseEmitter emitter = new SseEmitter(output);
			try
			{
				Files.write(project, Base64.getDecoder().decode(projectBase64));
				if (isToolLoggingEnabled(ToolLog.PROJECTS))
				{
					toolLog(ToolLog.PROJECTS, "session-open-stream:start", "sessionId", sessionId, "projectBytes", Files.size(project));
				}
				MapSettings settings = new MapSettings(project.toString());
				Dimension maxDimensions = readMaxDimensions(input);
				MapParts mapParts = new MapParts();
				Image image = new MapCreator(message -> emitGenerationPhase(emitter, message)).createMap(settings, maxDimensions, mapParts);
				storeEditorSession(sessionId, settings, mapParts, image, maxDimensions);
				EditorSession session = EDITOR_SESSIONS.get(sessionId);
				emitter.send("result", "{\"previewBase64\":\"" + imageToBase64(image, ".png") + "\",\"metadata\":" + sessionMetadataJson(session) + "}");
				emitter.send("done", "{}");
				if (isToolLoggingEnabled(ToolLog.PROJECTS))
				{
					toolLog(ToolLog.PROJECTS, "session-open-stream:success", "sessionId", sessionId, "durationMs", elapsedMs(startedAt), "mapWidth", image.getWidth(), "mapHeight", image.getHeight());
				}
			}
			catch (Exception ex)
			{
				if (isToolLoggingEnabled(ToolLog.PROJECTS))
				{
					toolLog(ToolLog.PROJECTS, "session-open-stream:error", "sessionId", sessionId, "durationMs", elapsedMs(startedAt), "error", ex.toString());
				}
				emitter.send("error", "{\"error\":\"" + escape(ex.getMessage()) + "\"}");
			}
		}
		finally
		{
			Files.deleteIfExists(project);
		}
	}

	private static void editorSessionProject(HttpExchange exchange) throws IOException
	{
		if (!exchange.getRequestMethod().equals("POST"))
		{
			send(exchange, 405, "application/json", "{\"ok\":false}".getBytes(StandardCharsets.UTF_8));
			return;
		}
		Path project = Files.createTempFile("nortantis-editor-project-", MapSettings.fileExtensionWithDot);
		long startedAt = System.nanoTime();
		String operationId = "untracked";
		try
		{
			JSONObject input = readJsonBody(exchange);
			operationId = normalizeOperationId(input.get("operationId"));
			String sessionId = (String) input.get("sessionId");
			if (isToolLoggingEnabled(ToolLog.SAVE_EXPORT))
			{
				toolLog(ToolLog.SAVE_EXPORT, "serialize:start", "operationId", operationId, "projectId", sessionId);
			}
			EditorSession session = sessionId == null ? null : EDITOR_SESSIONS.get(sessionId);
			if (session == null)
			{
				throw new IllegalArgumentException("Missing editor session");
			}
			synchronized (session)
			{
				session.settings.writeToFile(project.toString());
			}
			String result = "{\"ok\":true,\"projectBase64\":\"" + Base64.getEncoder().encodeToString(Files.readAllBytes(project)) + "\",\"metadata\":" + sessionMetadataJson(session) + "}";
			if (isToolLoggingEnabled(ToolLog.SAVE_EXPORT))
			{
				toolLog(ToolLog.SAVE_EXPORT, "serialize:success", "operationId", operationId, "projectId", sessionId, "durationMs", elapsedMs(startedAt), "projectBytes", Files.size(project));
			}
			send(exchange, 200, "application/json", result.getBytes(StandardCharsets.UTF_8));
		}
		catch (Exception ex)
		{
			if (isToolLoggingEnabled(ToolLog.SAVE_EXPORT))
			{
				toolLog(ToolLog.SAVE_EXPORT, "serialize:error", "operationId", operationId, "durationMs", elapsedMs(startedAt), "errorType", ex.getClass().getSimpleName());
			}
			send(exchange, 500, "application/json", ("{\"ok\":false,\"error\":\"" + escape(ex.getMessage()) + "\"}").getBytes(StandardCharsets.UTF_8));
		}
		finally
		{
			Files.deleteIfExists(project);
		}
	}

	private static void editorSessionHistory(HttpExchange exchange) throws IOException
	{
		if (!exchange.getRequestMethod().equals("POST"))
		{
			send(exchange, 405, "application/json", "{\"ok\":false}".getBytes(StandardCharsets.UTF_8));
			return;
		}
		try
		{
			JSONObject input = readJsonBody(exchange);
			String sessionId = input.get("sessionId") instanceof String ? ((String) input.get("sessionId")).trim() : "";
			String action = input.get("action") instanceof String ? ((String) input.get("action")).trim() : "";
			EditorSession session = EDITOR_SESSIONS.get(sessionId);
			if (session == null || !("undo".equals(action) || "redo".equals(action)))
			{
				throw new IllegalArgumentException("Invalid editor history request");
			}
			boolean changed;
			synchronized (session)
			{
				changed = restoreEditorHistory(session, action);
			}
			String result = "{\"ok\":true,\"changed\":" + changed
					+ ",\"canUndo\":" + session.canUndo()
					+ ",\"canRedo\":" + session.canRedo()
					+ ",\"previewBase64\":\"" + imageToBase64(session.map, ".png") + "\",\"metadata\":" + sessionMetadataJson(session) + "}";
			send(exchange, 200, "application/json", result.getBytes(StandardCharsets.UTF_8));
		}
		catch (Exception ex)
		{
			send(exchange, 400, "application/json", ("{\"ok\":false,\"error\":\"" + escape(ex.getMessage()) + "\"}").getBytes(StandardCharsets.UTF_8));
		}
	}

	private static void editorSessionTopology(HttpExchange exchange) throws IOException
	{
		if (!exchange.getRequestMethod().equals("POST"))
		{
			send(exchange, 405, "application/json", "{\"ok\":false}".getBytes(StandardCharsets.UTF_8));
			return;
		}
		try
		{
			JSONObject input = readJsonBody(exchange);
			String sessionId = input.get("sessionId") instanceof String ? ((String) input.get("sessionId")).trim() : "";
			EditorSession session = EDITOR_SESSIONS.get(sessionId);
			if (session == null)
			{
				throw new IllegalArgumentException("Missing editor session");
			}
			String result;
			synchronized (session)
			{
				StringBuilder topology = new StringBuilder("{\"ok\":true,\"sessionReady\":true,\"metadata\":");
				topology.append(sessionMetadataJson(session));
				topology.append(",\"polygons\":");
				appendPolygonsJson(topology, new HashSet<>(session.mapParts.graph.centers), session);
				topology.append("}");
				result = topology.toString();
			}
			send(exchange, 200, "application/json", result.getBytes(StandardCharsets.UTF_8));
		}
		catch (Exception ex)
		{
			send(exchange, 400, "application/json", ("{\"ok\":false,\"error\":\"" + escape(ex.getMessage()) + "\"}").getBytes(StandardCharsets.UTF_8));
		}
	}

	private static void editorSessionPathPreview(HttpExchange exchange) throws IOException
	{
		if (!exchange.getRequestMethod().equals("POST"))
		{
			send(exchange, 405, "application/json", "{\"ok\":false}".getBytes(StandardCharsets.UTF_8));
			return;
		}
		try
		{
			JSONObject input = readJsonBody(exchange);
			String sessionId = input.get("sessionId") instanceof String ? ((String) input.get("sessionId")).trim() : "";
			JSONObject command = input.get("command") instanceof JSONObject ? (JSONObject) input.get("command") : null;
			EditorSession session = EDITOR_SESSIONS.get(sessionId);
			if (session == null || command == null)
			{
				throw new IllegalArgumentException("Invalid path preview request");
			}
			String result;
			synchronized (session)
			{
				List<Point> inputLine = readLineGraphPoints(session.settings, command, true, borderPadding(session));
				String type = command.get("type") instanceof String ? (String) command.get("type") : "";
				if ("region.islands.lasso".equals(type))
				{
					List<Point> closedLasso = closePolygon(inputLine);
					Set<Center> selected = selectLandComponentsFullyInsideLasso(session.settings, session.mapParts.graph, closedLasso);
					result = pathPreviewJson(closedLasso, selected, session);
				}
				else
				{
				double radius = command.get("radius") instanceof Number
						? ((Number) command.get("radius")).doubleValue() * (session.settings.resolution == 0.0 ? 1.0 : session.settings.resolution)
						: Math.max(8.0, averageNeighborDistance(session.mapParts.graph) * 0.5);
				List<Point> previewLine = inputLine;
				if (type.startsWith("region.boundary"))
				{
					SnappedBoundary snapped = snapBoundaryToRegionEdges(session.settings, session.mapParts.graph, inputLine, radius);
					if (snapped != null && !snapped.points().isEmpty())
					{
						previewLine = snapped.points();
					}
				}
				else if (type.startsWith("river."))
				{
					SnappedPath snapped = snapLineToGraphEdges(session.mapParts.graph, inputLine, radius);
					if (snapped != null && !snapped.points().isEmpty())
					{
						previewLine = snapped.points();
					}
				}
				Set<Center> selected = centersNearBoundaryLine(session.settings, session.mapParts.graph, previewLine, Math.max(radius, averageNeighborDistance(session.mapParts.graph) * 0.65));
				result = pathPreviewJson(previewLine, selected, session);
				}
			}
			send(exchange, 200, "application/json", result.getBytes(StandardCharsets.UTF_8));
		}
		catch (Exception ex)
		{
			send(exchange, 400, "application/json", ("{\"ok\":false,\"error\":\"" + escape(ex.getMessage()) + "\"}").getBytes(StandardCharsets.UTF_8));
		}
	}

	private static void editorSessionGenerateStream(HttpExchange exchange) throws IOException
	{
		if (!exchange.getRequestMethod().equals("POST"))
		{
			send(exchange, 405, "application/json", "{\"ok\":false}".getBytes(StandardCharsets.UTF_8));
			return;
		}

		JSONObject input;
		String sessionId;
		int size;
		long startedAt = System.nanoTime();
		try
		{
			input = readJsonBody(exchange);
			sessionId = input.get("sessionId") instanceof String ? ((String) input.get("sessionId")).trim() : "";
			if (sessionId.isEmpty() || !(input.get("size") instanceof Number))
			{
				throw new IllegalArgumentException("Missing generation parameters");
			}
			size = ((Number) input.get("size")).intValue();
			if (size != 1024 && size != 2048 && size != 4096)
			{
				throw new IllegalArgumentException("Unsupported map size");
			}
		}
		catch (Exception ex)
		{
			send(exchange, 400, "application/json", ("{\"ok\":false,\"error\":\"" + escape(ex.getMessage()) + "\"}").getBytes(StandardCharsets.UTF_8));
			return;
		}

		exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
		exchange.getResponseHeaders().set("Cache-Control", "no-store");
		exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
		exchange.sendResponseHeaders(200, 0);
		Path project = Files.createTempFile("nortantis-editor-generate-", MapSettings.fileExtensionWithDot);
		try (OutputStream output = exchange.getResponseBody())
		{
			SseEmitter emitter = new SseEmitter(output);
			try
			{
				if (isToolLoggingEnabled(ToolLog.GENERATION))
				{
					toolLog(ToolLog.GENERATION, "start", "sessionId", sessionId, "size", size, "blank", Boolean.TRUE.equals(input.get("blank")));
				}
				MapSettings settings = SettingsGenerator.generate(null);
				applyDefaultProjectOptions(settings, input);
				String title = input.get("name") instanceof String ? ((String) input.get("name")).trim() : "";
				if (!title.isBlank())
				{
					NameCreator.forcedTitle.set(title);
				}
				MapParts mapParts = new MapParts();
				fitResolutionToExactSquareSize(settings, size);
				Dimension maxDimensions = new Dimension(size, size);
				Image result = new MapCreator(message -> emitGenerationPhase(emitter, message)).createMap(settings, null, mapParts);
				if (result.getWidth() != size || result.getHeight() != size)
				{
					throw new IllegalStateException("Generated map size mismatch: " + result.getWidth() + "x" + result.getHeight());
				}
				storeEditorSession(sessionId, settings, mapParts, result, maxDimensions);
				settings.writeToFile(project.toString());
				EditorSession session = EDITOR_SESSIONS.get(sessionId);
				emitter.send("result", "{\"previewBase64\":\"" + imageToBase64(result, ".png") + "\",\"metadata\":" + sessionMetadataJson(session) + "}");
				emitter.send("done", "{}");
				if (isToolLoggingEnabled(ToolLog.GENERATION))
				{
					toolLog(ToolLog.GENERATION, "success", "sessionId", sessionId, "size", size, "durationMs", elapsedMs(startedAt), "mapWidth", result.getWidth(), "mapHeight", result.getHeight());
				}
			}
			catch (Exception ex)
			{
				if (isToolLoggingEnabled(ToolLog.GENERATION))
				{
					toolLog(ToolLog.GENERATION, "error", "sessionId", sessionId, "size", size, "durationMs", elapsedMs(startedAt), "error", ex.toString());
				}
				emitter.send("error", "{\"error\":\"" + escape(ex.getMessage()) + "\"}");
			}
			finally
			{
				NameCreator.forcedTitle.remove();
			}
		}
		finally
		{
			Files.deleteIfExists(project);
		}
	}
	private static void health(HttpExchange exchange) throws IOException
	{
		if (!exchange.getRequestMethod().equals("GET"))
		{
			send(exchange, 405, "application/json", "{\"ok\":false}".getBytes());
			return;
		}
		send(exchange, 200, "application/json", "{\"ok\":true}".getBytes());
	}

	private static void defaultProject(HttpExchange exchange) throws IOException
	{
		if (!exchange.getRequestMethod().equals("POST"))
		{
			send(exchange, 405, "application/json", "{\"ok\":false}".getBytes());
			return;
		}
		Path temp = Files.createTempFile("nortantis-default-", MapSettings.fileExtensionWithDot);
		try
		{
			JSONObject input = readJsonBody(exchange);
			MapSettings settings = SettingsGenerator.generate(null);
			applyDefaultProjectOptions(settings, input);
			settings.writeToFile(temp.toString());
			send(exchange, 200, "application/octet-stream", Files.readAllBytes(temp));
		}
		catch (Exception ex)
		{
			send(exchange, 500, "application/json", ("{\"ok\":false,\"error\":\"" + escape(ex.getMessage()) + "\"}").getBytes());
		}
		finally
		{
			Files.deleteIfExists(temp);
		}
	}

	private static JSONObject readJsonBody(HttpExchange exchange) throws Exception
	{
		byte[] body = exchange.getRequestBody().readAllBytes();
		if (body.length == 0)
		{
			return new JSONObject();
		}
		String text = new String(body, StandardCharsets.UTF_8).trim();
		if (text.isEmpty())
		{
			return new JSONObject();
		}
		return (JSONObject) new JSONParser().parse(text);
	}

	private static void applyDefaultProjectOptions(MapSettings settings, JSONObject input)
	{
		int size = readMapSize(input);
		boolean blank = Boolean.TRUE.equals(input.get("blank"));
		settings.nameLocale = NameLanguagePack.normalizeLocale(input.get("locale") instanceof String ? (String) input.get("locale") : "ru");
		if ("ru".equals(settings.nameLocale))
		{
			settings.titleFont = fontWithCyrillicFallback(settings.titleFont);
			settings.regionFont = fontWithCyrillicFallback(settings.regionFont);
			settings.mountainRangeFont = fontWithCyrillicFallback(settings.mountainRangeFont);
			settings.otherMountainsFont = fontWithCyrillicFallback(settings.otherMountainsFont);
			settings.citiesFont = fontWithCyrillicFallback(settings.citiesFont);
			settings.riverFont = fontWithCyrillicFallback(settings.riverFont);
		}
		settings.generatedWidth = size;
		settings.generatedHeight = size;
		settings.worldSize = worldSizeForMapSize(size);
		settings.regionCount = readRegionCount(input, settings.worldSize, blank);
		settings.resolution = MapSettings.defaultResolution;
		if (blank)
		{
			settings.centerLandToWaterProbability = 0.0;
			settings.edgeLandToWaterProbability = 0.0;
			settings.landShape = LandShape.Scattered;
			settings.cityProbability = 0.0;
			settings.drawRegionBoundaries = false;
			settings.drawRegionColors = false;
			settings.drawText = false;
			settings.drawBoldBackground = false;
			settings.edits = new MapEdits();
			WorldGraph graph = MapCreator.createGraph(settings, true);
			settings.edits.initializeCenterEdits(graph.centers);
			for (Center center : graph.centers)
			{
				settings.edits.centerEdits.put(center.index, new CenterEdit(center.index, true, false, null, null, null));
			}
		}
	}

	private static int readRegionCount(JSONObject input, int worldSize, boolean blank)
	{
		Object value = input.get("regionCount");
		if (blank || value == null)
		{
			return SettingsGenerator.maxGeneratedRegionCount(worldSize);
		}
		if (!(value instanceof Number))
		{
			throw new IllegalArgumentException("regionCount must be a number");
		}
		double rawRegionCount = ((Number) value).doubleValue();
		if (!Double.isFinite(rawRegionCount) || rawRegionCount != Math.rint(rawRegionCount))
		{
			throw new IllegalArgumentException("regionCount must be an integer");
		}
		int regionCount = (int) rawRegionCount;
		if (regionCount < SettingsGenerator.minRegionCount || regionCount > SettingsGenerator.maxRegionCount)
		{
			throw new IllegalArgumentException("regionCount must be between " + SettingsGenerator.minRegionCount + " and " + SettingsGenerator.maxRegionCount);
		}
		return regionCount;
	}

	private static Font fontWithCyrillicFallback(Font font)
	{
		if (font == null || font.canDisplayUpTo("Карта земель Северин") < 0)
		{
			return font;
		}
		return Font.create("Serif", font.getStyle(), font.getSize());
	}

	private static int readMapSize(JSONObject input)
	{
		Object value = input.get("size");
		if (!(value instanceof Number))
		{
			return 2048;
		}
		int size = ((Number) value).intValue();
		if (size <= 1024)
		{
			return 1024;
		}
		if (size >= 4096)
		{
			return 4096;
		}
		return 2048;
	}

	private static int worldSizeForMapSize(int size)
	{
		if (size <= 1024)
		{
			return 4000;
		}
		if (size >= 4096)
		{
			return 12000;
		}
		return 8000;
	}

	private static void fitResolutionToExactSquareSize(MapSettings settings, int targetSize)
	{
		double low = 0.0;
		double high = 2.0;
		for (int iteration = 0; iteration < 80; iteration++)
		{
			double candidate = (low + high) / 2.0;
			if (outputSizeAtResolution(settings, candidate) < targetSize)
			{
				low = candidate;
			}
			else
			{
				high = candidate;
			}
		}
		settings.resolution = high;
		if (outputSizeAtResolution(settings, settings.resolution) != targetSize)
		{
			throw new IllegalStateException("Unable to fit Nortantis map to " + targetSize + " pixels");
		}
	}

	private static int outputSizeAtResolution(MapSettings settings, double resolution)
	{
		int mapSize = (int) (settings.generatedWidth * resolution);
		int outsideBorder = settings.drawBorder && settings.borderPosition == BorderPosition.Outside_map
				? (int) (settings.borderWidth * resolution) * 2
				: 0;
		return mapSize + outsideBorder;
	}

	private static void exportProject(HttpExchange exchange) throws IOException
	{
		if (!exchange.getRequestMethod().equals("POST"))
		{
			send(exchange, 405, "application/json", "{\"ok\":false}".getBytes());
			return;
		}
		Path project = Files.createTempFile("nortantis-project-", MapSettings.fileExtensionWithDot);
		ExportFormat exportFormat = readExportFormat(exchange);
		Dimension maxDimensions = readMaxDimensions(exchange);
		String sessionId = readSessionId(exchange);
		String operationId = normalizeOperationId(exchange.getRequestHeaders().getFirst("X-Nortantis-Operation-Id"));
		long startedAt = System.nanoTime();
		Path image = Files.createTempFile("nortantis-export-", exportFormat.extension);
		try
		{
			byte[] projectBytes = exchange.getRequestBody().readAllBytes();
			if (isToolLoggingEnabled(ToolLog.SAVE_EXPORT))
			{
				toolLog(ToolLog.SAVE_EXPORT, "render:start", "operationId", operationId, "format", exportFormat.extension.substring(1), "projectBytes", projectBytes.length);
			}
			Files.write(project, projectBytes);
			MapSettings settings = new MapSettings(project.toString());
			String title = readMapTitle(exchange);
			if (title != null && !title.isBlank())
			{
				NameCreator.forcedTitle.set(title);
			}
			ImageCache.clear();
			MapParts mapParts = sessionId == null ? null : new MapParts();
			Image result = new MapCreator().createMap(settings, maxDimensions, mapParts);
			if (sessionId != null)
			{
				storeEditorSession(sessionId, settings, mapParts, result, maxDimensions);
			}
			ImageHelper.getInstance().write(result, image.toString());
			ImageCache.clear();
			byte[] imageBytes = Files.readAllBytes(image);
			if (isToolLoggingEnabled(ToolLog.SAVE_EXPORT))
			{
				toolLog(ToolLog.SAVE_EXPORT, "render:success", "operationId", operationId, "format", exportFormat.extension.substring(1), "imageBytes", imageBytes.length,
						"width", result.getWidth(), "height", result.getHeight(), "durationMs", elapsedMs(startedAt));
			}
			send(exchange, 200, exportFormat.contentType, imageBytes);
		}
		catch (Exception ex)
		{
			if (isToolLoggingEnabled(ToolLog.SAVE_EXPORT))
			{
				toolLog(ToolLog.SAVE_EXPORT, "render:error", "operationId", operationId, "durationMs", elapsedMs(startedAt), "errorType", ex.getClass().getSimpleName());
			}
			send(exchange, 500, "application/json", ("{\"ok\":false,\"error\":\"" + escape(ex.getMessage()) + "\"}").getBytes());
		}
		finally
		{
			NameCreator.forcedTitle.remove();
			Files.deleteIfExists(project);
			Files.deleteIfExists(image);
		}
	}


	private static void editProject(HttpExchange exchange) throws IOException
	{
		if (!exchange.getRequestMethod().equals("POST"))
		{
			send(exchange, 405, "application/json", "{\"ok\":false}".getBytes());
			return;
		}
		Path project = null;
		long startedAt = System.nanoTime();
		ToolLog commandLog = null;
		try
		{
			JSONObject input = (JSONObject) new JSONParser().parse(new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8));
			String projectBase64 = (String) input.get("projectBase64");
			JSONObject command = (JSONObject) input.get("command");
			String sessionId = (String) input.get("sessionId");
			boolean omitProjectBytes = Boolean.TRUE.equals(input.get("omitProjectBytes"));
			commandLog = toolLogForCommand(command);
			if (isToolLoggingEnabled(commandLog))
			{
				toolLog(commandLog, "edit:start", "command", command, "payloadBase64Length", projectBase64 != null ? projectBase64.length() : 0, "sessionId", sessionId);
			}
			EditorSession existingSession = sessionId == null || sessionId.isBlank() ? null : EDITOR_SESSIONS.get(sessionId);
			MapSettings settings;
			if (projectBase64 != null && !projectBase64.isBlank())
			{
				project = Files.createTempFile("nortantis-edit-", MapSettings.fileExtensionWithDot);
				Files.write(project, Base64.getDecoder().decode(projectBase64));
				settings = new MapSettings(project.toString());
			}
			else if (existingSession != null && command != null && isIncrementalCommand(command))
			{
				settings = existingSession.settings;
			}
			else
			{
				throw new IllegalStateException("Missing project data and editor session");
			}
			String previewBase64 = null;
			IncrementalPreview previewPatch = null;
			EditorSession editSession = null;
			if (sessionId != null && !sessionId.isBlank() && command != null && isIncrementalCommand(command))
			{
				editSession = existingSession != null ? existingSession : getOrCreateEditorSession(sessionId, project, readMaxDimensions(input));
				MapSettings historyBefore = editSession.prepareHistory(command);
				if (isRegionColorCommand(command) && !editSession.settings.drawRegionColors)
				{
					editSession = applyFirstRegionPaint(sessionId, command, editSession);
					settings = editSession.settings;
					previewBase64 = readReturnPreview(input) ? imageToBase64(editSession.map, ".png") : null;
				}
				else
				{
					previewPatch = applyIncrementalEdit(settings, command, editSession, readReturnPreview(input));
				}
				editSession.commitHistory(historyBefore, command);
			}
			else
			{
				applyEditCommand(settings, command);
			}

			String serializedProjectBase64 = null;
			if (!omitProjectBytes)
			{
				if (project == null)
				{
					project = Files.createTempFile("nortantis-edit-", MapSettings.fileExtensionWithDot);
				}
				settings.writeToFile(project.toString());
				serializedProjectBase64 = Base64.getEncoder().encodeToString(Files.readAllBytes(project));
			}

			String result = "{\"ok\":true"
					+ (serializedProjectBase64 == null ? "" : ",\"projectBase64\":\"" + serializedProjectBase64 + "\"")
					+ (previewBase64 == null ? "" : ",\"previewBase64\":\"" + previewBase64 + "\"")
					+ (previewPatch == null ? "" : ",\"previewPatchBase64\":\"" + previewPatch.base64() + "\",\"previewPatch\":" + rectangleJson(previewPatch.bounds()))
					+ (editSession == null ? "" : ",\"metadata\":" + sessionMetadataJson(editSession)) + "}";
			if (isToolLoggingEnabled(commandLog))
			{
				toolLog(commandLog, "edit:success", "durationMs", elapsedMs(startedAt), "resultBase64Length", result.length(), "hasPreview", previewBase64 != null || previewPatch != null);
			}
			send(exchange, 200, "application/json", result.getBytes(StandardCharsets.UTF_8));
		}
		catch (Exception ex)
		{
			if (isToolLoggingEnabled(commandLog))
			{
				toolLog(commandLog, "edit:error", "durationMs", elapsedMs(startedAt), "error", ex.toString());
			}
			send(exchange, 500, "application/json", ("{\"ok\":false,\"error\":\"" + escape(ex.getMessage()) + "\"}").getBytes());
		}
		finally
		{
			if (project != null)
			{
				Files.deleteIfExists(project);
			}
		}
	}

	private static void iconAssets(HttpExchange exchange) throws IOException
	{
		if (!exchange.getRequestMethod().equals("POST"))
		{
			send(exchange, 405, "application/json", "{\"ok\":false}".getBytes());
			return;
		}
		Path project = Files.createTempFile("nortantis-assets-", MapSettings.fileExtensionWithDot);
		long startedAt = System.nanoTime();
		try
		{
			byte[] body = exchange.getRequestBody().readAllBytes();
			if (exchange.getRequestURI().getPath().startsWith("/api/editor/"))
			{
				JSONObject input = body.length == 0 ? new JSONObject() : (JSONObject) new JSONParser().parse(new String(body, StandardCharsets.UTF_8));
				String sessionId = input.get("sessionId") instanceof String ? (String) input.get("sessionId") : "";
				EditorSession session = EDITOR_SESSIONS.get(sessionId);
				if (session == null)
				{
					throw new IllegalStateException("Missing editor session");
				}
				synchronized (session)
				{
					String result = iconAssetsJson(session.settings);
					if (isToolLoggingEnabled(ToolLog.ASSETS))
					{
						toolLog(ToolLog.ASSETS, "list:success", "sessionId", sessionId, "durationMs", elapsedMs(startedAt), "responseLength", result.length());
					}
					send(exchange, 200, "application/json", result.getBytes(StandardCharsets.UTF_8));
					return;
				}
			}
			MapSettings settings;
			if (body.length > 0)
			{
				Files.write(project, body);
				settings = new MapSettings(project.toString());
			}
			else
			{
				settings = SettingsGenerator.generate(null);
			}
			send(exchange, 200, "application/json", iconAssetsJson(settings).getBytes(StandardCharsets.UTF_8));
		}
		catch (Exception ex)
		{
			if (isToolLoggingEnabled(ToolLog.ASSETS))
			{
				toolLog(ToolLog.ASSETS, "list:error", "durationMs", elapsedMs(startedAt), "error", ex.toString());
			}
			send(exchange, 500, "application/json", ("{\"ok\":false,\"error\":\"" + escape(ex.getMessage()) + "\"}").getBytes(StandardCharsets.UTF_8));
		}
		finally
		{
			Files.deleteIfExists(project);
		}
	}

	private static void iconPreview(HttpExchange exchange) throws IOException
	{
		if (!exchange.getRequestMethod().equals("POST"))
		{
			send(exchange, 405, "application/json", "{\"ok\":false}".getBytes());
			return;
		}
		Path project = Files.createTempFile("nortantis-icon-preview-", MapSettings.fileExtensionWithDot);
		Path image = Files.createTempFile("nortantis-icon-preview-", ".png");
		long startedAt = System.nanoTime();
		try
		{
			byte[] body = exchange.getRequestBody().readAllBytes();
			MapSettings settings;
			IconType type;
			String group;
			String name;
			if (exchange.getRequestURI().getPath().startsWith("/api/editor/"))
			{
				JSONObject input = body.length == 0 ? new JSONObject() : (JSONObject) new JSONParser().parse(new String(body, StandardCharsets.UTF_8));
				String sessionId = input.get("sessionId") instanceof String ? (String) input.get("sessionId") : "";
				EditorSession session = EDITOR_SESSIONS.get(sessionId);
				if (session == null)
				{
					throw new IllegalStateException("Missing editor session");
				}
				settings = session.settings;
				type = IconType.valueOf((String) input.get("type"));
				group = (String) input.get("group");
				name = (String) input.get("name");
			}
			else if (body.length > 0)
			{
				Files.write(project, body);
				settings = new MapSettings(project.toString());
				type = IconType.valueOf(requiredHeader(exchange, "X-Nortantis-Icon-Type"));
				group = requiredHeader(exchange, "X-Nortantis-Icon-Group");
				name = requiredHeader(exchange, "X-Nortantis-Icon-Name");
			}
			else
			{
				settings = SettingsGenerator.generate(null);
				type = IconType.valueOf(requiredHeader(exchange, "X-Nortantis-Icon-Type"));
				group = requiredHeader(exchange, "X-Nortantis-Icon-Group");
				name = requiredHeader(exchange, "X-Nortantis-Icon-Name");
			}
			ImageAndMasks icon = ImageCache.getInstance(settings.artPack, settings.customImagesPath).getIconsByNameForGroup(type, group).get(name);
			if (icon == null)
			{
				send(exchange, 404, "application/json", "{\"ok\":false,\"error\":\"notFound\"}".getBytes(StandardCharsets.UTF_8));
				return;
			}
			int max = 96;
			double scale = Math.min((double) max / icon.image.getWidth(), (double) max / icon.image.getHeight());
			int width = Math.max(1, (int) Math.round(icon.image.getWidth() * scale));
			int height = Math.max(1, (int) Math.round(icon.image.getHeight() * scale));
			try (Image scaled = icon.image.scale(Method.QUALITY, width, height))
			{
				ImageHelper.getInstance().write(scaled, image.toString());
			}
			send(exchange, 200, "image/png", Files.readAllBytes(image));
			if (isToolLoggingEnabled(ToolLog.ASSETS))
			{
				toolLog(ToolLog.ASSETS, "preview:success", "type", type, "group", group, "name", name, "durationMs", elapsedMs(startedAt), "width", width, "height", height);
			}
		}
		catch (Exception ex)
		{
			if (isToolLoggingEnabled(ToolLog.ASSETS))
			{
				toolLog(ToolLog.ASSETS, "preview:error", "durationMs", elapsedMs(startedAt), "error", ex.toString());
			}
			send(exchange, 500, "application/json", ("{\"ok\":false,\"error\":\"" + escape(ex.getMessage()) + "\"}").getBytes(StandardCharsets.UTF_8));
		}
		finally
		{
			Files.deleteIfExists(project);
			Files.deleteIfExists(image);
		}
	}

	private static String requiredHeader(HttpExchange exchange, String name)
	{
		String value = exchange.getRequestHeaders().getFirst(name);
		if (value == null || value.isBlank())
		{
			throw new IllegalArgumentException("Missing header " + name);
		}
		return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
	}

	private static void brushSelection(HttpExchange exchange) throws IOException
	{
		if (!exchange.getRequestMethod().equals("POST"))
		{
			send(exchange, 405, "application/json", "{\"ok\":false}".getBytes());
			return;
		}
		long startedAt = System.nanoTime();
		boolean forestSelection = false;
		try
		{
			JSONObject input = (JSONObject) new JSONParser().parse(new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8));
			String sessionId = (String) input.get("sessionId");
			JSONObject command = (JSONObject) input.get("command");
			forestSelection = command != null && "trees.brush".equals(command.get("type"));
			if (sessionId == null || sessionId.isBlank() || command == null || !("terrain.brush".equals(command.get("type")) || forestSelection))
			{
				send(exchange, 400, "application/json", "{\"ok\":false,\"error\":\"invalid\"}".getBytes(StandardCharsets.UTF_8));
				return;
			}
			EditorSession session = EDITOR_SESSIONS.get(sessionId);
			if (session == null)
			{
				if (forestSelection ? isForestsLoggingEnabled() : isBrushLoggingEnabled())
				{
					toolLog(forestSelection ? ToolLog.FORESTS : ToolLog.BRUSH, "selection:session-missing", "sessionId", sessionId);
				}
				send(exchange, 200, "application/json", "{\"ok\":true,\"polygons\":[],\"sessionReady\":false}".getBytes(StandardCharsets.UTF_8));
				return;
			}
			String result;
			synchronized (session)
			{
				double resolution = session.settings.resolution == 0.0 ? 1.0 : session.settings.resolution;
				int borderPadding = borderPadding(session);
				double commandX = ((Number) command.get("x")).doubleValue();
				double commandY = ((Number) command.get("y")).doubleValue();
				double commandRadius = ((Number) command.get("radius")).doubleValue();
				double x = commandX * resolution - borderPadding;
				double y = commandY * resolution - borderPadding;
				double radius = commandRadius * resolution;
				Set<Center> selected = selectCentersForBrush(session.mapParts.graph, new Point(x, y), radius);
				result = brushSelectionJson(selected, session);
				if (forestSelection ? isForestsLoggingEnabled() : isBrushLoggingEnabled())
				{
					toolLog(forestSelection ? ToolLog.FORESTS : ToolLog.BRUSH, "coordinates:selection", "sessionId", sessionId, "commandX", commandX, "commandY", commandY, "commandRadius", commandRadius, "scaledX", x, "scaledY", y,
							"scaledRadius", radius, "resolution", resolution, "mapWidth", session.map.getWidth(), "mapHeight", session.map.getHeight(), "coordinateWidth",
							session.map.getWidth() / resolution, "coordinateHeight", session.map.getHeight() / resolution, "borderPadding", borderPadding,
							"borderPaddingRi", borderPadding / resolution, "selectedCount", selected.size());
					toolLog(forestSelection ? ToolLog.FORESTS : ToolLog.BRUSH, "selection:success", "sessionId", sessionId, "durationMs", elapsedMs(startedAt), "selectedCount", selected.size());
				}
			}
			send(exchange, 200, "application/json", result.getBytes(StandardCharsets.UTF_8));
		}
		catch (Exception ex)
		{
			if (forestSelection ? isForestsLoggingEnabled() : isBrushLoggingEnabled())
			{
				toolLog(forestSelection ? ToolLog.FORESTS : ToolLog.BRUSH, "selection:error", "durationMs", elapsedMs(startedAt), "error", ex.toString());
			}
			send(exchange, 500, "application/json", ("{\"ok\":false,\"error\":\"" + escape(ex.getMessage()) + "\"}").getBytes(StandardCharsets.UTF_8));
		}
	}

	private static void regionColor(HttpExchange exchange) throws IOException
	{
		if (!exchange.getRequestMethod().equals("POST"))
		{
			send(exchange, 405, "application/json", "{\"ok\":false}".getBytes());
			return;
		}
		Path project = Files.createTempFile("nortantis-region-color-", MapSettings.fileExtensionWithDot);
		long startedAt = System.nanoTime();
		try
		{
			JSONObject input = (JSONObject) new JSONParser().parse(new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8));
			JSONObject command = (JSONObject) input.get("command");
			String sessionId = (String) input.get("sessionId");
			EditorSession session = sessionId == null || sessionId.isBlank() ? null : EDITOR_SESSIONS.get(sessionId);
			MapSettings settings;
			WorldGraph graph;
			Integer borderPaddingOverride = null;
			if (session != null)
			{
					synchronized (session)
					{
						settings = session.settings;
						graph = session.mapParts.graph;
						borderPaddingOverride = borderPadding(session);
						String result = regionColorJson(settings, graph, command, true, borderPaddingOverride);
						if (isRegionPaintLoggingEnabled())
						{
							regionPaintLog("picker:success", "sessionId", sessionId, "durationMs", elapsedMs(startedAt), "command", command, "result", result);
						}
						send(exchange, 200, "application/json", result.getBytes(StandardCharsets.UTF_8));
						return;
				}
			}
			String projectBase64 = (String) input.get("projectBase64");
			if (projectBase64 == null || projectBase64.isBlank())
			{
				throw new IllegalStateException("Missing project data and editor session");
			}
			Files.write(project, Base64.getDecoder().decode(projectBase64));
			settings = new MapSettings(project.toString());
			ensureEdits(settings);
			graph = MapCreator.createGraph(settings, !settings.edits.isInitialized());
			if (!settings.edits.isInitialized())
			{
				settings.edits.initializeCenterEdits(graph.centers);
			}
			if (settings.edits.regionEdits.isEmpty())
			{
				settings.edits.initializeRegionEdits(graph.regions.values());
			}
			send(exchange, 200, "application/json", regionColorJson(settings, graph, command, false, null).getBytes(StandardCharsets.UTF_8));
		}
		catch (Exception ex)
		{
			if (isRegionPaintLoggingEnabled())
			{
				regionPaintLog("picker:error", "durationMs", elapsedMs(startedAt), "error", ex.toString());
			}
			send(exchange, 500, "application/json", ("{\"ok\":false,\"error\":\"" + escape(ex.getMessage()) + "\"}").getBytes(StandardCharsets.UTF_8));
		}
		finally
		{
			Files.deleteIfExists(project);
		}
	}

	private static void textPick(HttpExchange exchange) throws IOException
	{
		if (!exchange.getRequestMethod().equals("POST"))
		{
			send(exchange, 405, "application/json", "{\"ok\":false}".getBytes());
			return;
		}
		Path project = Files.createTempFile("nortantis-text-pick-", MapSettings.fileExtensionWithDot);
		long startedAt = System.nanoTime();
		try
		{
			JSONObject input = (JSONObject) new JSONParser().parse(new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8));
			JSONObject command = (JSONObject) input.get("command");
			String sessionId = (String) input.get("sessionId");
			EditorSession activeSession = sessionId == null || sessionId.isBlank() ? null : EDITOR_SESSIONS.get(sessionId);
			if (activeSession != null)
			{
				synchronized (activeSession)
				{
					String result = textPickJson(activeSession, command);
					if (isTextFieldLoggingEnabled())
					{
						textLog("pick:session-success", "sessionId", sessionId, "durationMs", elapsedMs(startedAt), "result", result);
					}
					send(exchange, 200, "application/json", result.getBytes(StandardCharsets.UTF_8));
					return;
				}
			}
			String projectBase64 = (String) input.get("projectBase64");
			if (projectBase64 == null || projectBase64.isBlank())
			{
				throw new IllegalStateException("Missing project data");
			}
			Files.write(project, Base64.getDecoder().decode(projectBase64));
			EditorSession session = sessionId == null || sessionId.isBlank()
					? getOrCreateEditorSession("text-pick-" + System.nanoTime(), project, readMaxDimensions(input))
					: getOrCreateEditorSession(sessionId, project, readMaxDimensions(input));
			synchronized (session)
			{
				String result = textPickJson(session, command);
				if (isTextFieldLoggingEnabled())
				{
					textLog("pick:success", "sessionId", sessionId, "durationMs", elapsedMs(startedAt), "result", result);
				}
				send(exchange, 200, "application/json", result.getBytes(StandardCharsets.UTF_8));
			}
		}
		catch (Exception ex)
		{
			if (isTextFieldLoggingEnabled())
			{
				textLog("pick:error", "durationMs", elapsedMs(startedAt), "error", ex.toString());
			}
			send(exchange, 500, "application/json", ("{\"ok\":false,\"error\":\"" + escape(ex.getMessage()) + "\"}").getBytes(StandardCharsets.UTF_8));
		}
		finally
		{
			Files.deleteIfExists(project);
		}
	}

	private static void send(HttpExchange exchange, int status, String contentType, byte[] body) throws IOException
	{
		exchange.getResponseHeaders().set("Content-Type", contentType);
		exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
		exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, X-Nortantis-Session-Id, X-Nortantis-Max-Dimension, X-Nortantis-Export-Format, X-Nortantis-Map-Title-Base64, X-Nortantis-Icon-Type, X-Nortantis-Icon-Group, X-Nortantis-Icon-Name");
		exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
		exchange.sendResponseHeaders(status, body.length);
		try (OutputStream output = exchange.getResponseBody())
		{
			output.write(body);
		}
	}

	private static String escape(String input)
	{
		if (input == null)
		{
			return "";
		}
		return input.replace("\\", "\\\\").replace("\"", "\\\"");
	}

	private static String readMapTitle(HttpExchange exchange)
	{
		String encoded = exchange.getRequestHeaders().getFirst("X-Nortantis-Map-Title-Base64");
		if (encoded == null || encoded.isBlank())
		{
			return null;
		}
		return new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
	}

	private static ExportFormat readExportFormat(HttpExchange exchange)
	{
		String format = exchange.getRequestHeaders().getFirst("X-Nortantis-Export-Format");
		if ("jpg".equalsIgnoreCase(format) || "jpeg".equalsIgnoreCase(format))
		{
			return new ExportFormat(".jpg", "image/jpeg");
		}
		return new ExportFormat(".png", "image/png");
	}

	private static String readSessionId(HttpExchange exchange)
	{
		String value = exchange.getRequestHeaders().getFirst("X-Nortantis-Session-Id");
		if (value == null || value.isBlank())
		{
			return null;
		}
		return value.trim();
	}

	private static Dimension readMaxDimensions(HttpExchange exchange)
	{
		String raw = exchange.getRequestHeaders().getFirst("X-Nortantis-Max-Dimension");
		if (raw == null || raw.isBlank())
		{
			return null;
		}
		try
		{
			int max = Integer.parseInt(raw.trim());
			if (max <= 0)
			{
				return null;
			}
			return new Dimension(max, max);
		}
		catch (NumberFormatException ignored)
		{
			return null;
		}
	}

	private static Dimension readMaxDimensions(JSONObject input)
	{
		Object value = input.get("previewMaxDimensionPixels");
		if (!(value instanceof Number))
		{
			return null;
		}
		int max = ((Number) value).intValue();
		return max > 0 ? new Dimension(max, max) : null;
	}

	private static boolean readReturnPreview(JSONObject input)
	{
		Object value = input.get("returnPreview");
		return !(value instanceof Boolean) || ((Boolean) value);
	}

	private static String sessionMetadataJson(EditorSession session)
	{
		return "{" + sessionMetadataFieldsJson(session) + "}";
	}

	private static String sessionMetadataFieldsJson(EditorSession session)
	{
		double resolution = session.settings.resolution == 0.0 ? 1.0 : session.settings.resolution;
		double width = session.map.getWidth() / resolution;
		double height = session.map.getHeight() / resolution;
		int borderPadding = borderPadding(session);
		return "\"width\":" + width + ",\"height\":" + height + ",\"resolution\":" + resolution + ",\"previewWidth\":" + session.map.getWidth() + ",\"previewHeight\":"
				+ session.map.getHeight() + ",\"borderPadding\":" + (borderPadding / resolution) + ",\"previewBorderPadding\":" + borderPadding
				+ ",\"textStyles\":" + textStylesJson(session.settings)
				+ ",\"capabilities\":{\"text\":true,\"landWaterBrush\":true,\"jpgExport\":true,\"icons\":true,\"roads\":true,\"regions\":true}";
	}

	private static String textStylesJson(MapSettings settings)
	{
		StringBuilder result = new StringBuilder("{");
		appendTextStyleJson(result, "Title", settings.titleFont, settings.drawBoldBackground, settings.textColor, settings.boldBackgroundColor);
		appendTextStyleJson(result, "Region", settings.regionFont, settings.drawBoldBackground, settings.textColor, settings.boldBackgroundColor);
		appendTextStyleJson(result, "Mountain_range", settings.mountainRangeFont, false, settings.textColor, settings.boldBackgroundColor);
		appendTextStyleJson(result, "Other_mountains", settings.otherMountainsFont, false, settings.textColor, settings.boldBackgroundColor);
		appendTextStyleJson(result, "City", settings.citiesFont, false, settings.textColor, settings.boldBackgroundColor);
		appendTextStyleJson(result, "Lake", settings.riverFont, false, settings.textColor, settings.boldBackgroundColor);
		appendTextStyleJson(result, "River", settings.riverFont, false, settings.textColor, settings.boldBackgroundColor);
		return result.append("}").toString();
	}

	private static void appendTextStyleJson(StringBuilder result, String type, Font font, boolean boldBackground, Color textColor, Color boldBackgroundColor)
	{
		if (result.length() > 1)
		{
			result.append(',');
		}
		FontStyle fontStyle = font == null ? FontStyle.Plain : font.getStyle();
		boolean bold = fontStyle == FontStyle.Bold || fontStyle == FontStyle.BoldItalic;
		boolean italic = fontStyle == FontStyle.Italic || fontStyle == FontStyle.BoldItalic;
		result.append('"').append(type).append("\":{")
				.append("\"fontFamily\":\"").append(escape(font == null ? "Serif" : font.getFamily())).append("\",")
				.append("\"fontName\":\"").append(escape(font == null ? "Serif" : font.getName())).append("\",")
				.append("\"size\":").append(font == null ? 16.0f : font.getSize()).append(',')
				.append("\"weight\":").append(bold ? 700 : 400).append(',')
				.append("\"italic\":").append(italic).append(',')
				.append("\"boldBackground\":").append(boldBackground).append(',')
				.append("\"color\":\"").append(cssColor(textColor)).append("\",")
				.append("\"boldBackgroundColor\":\"").append(cssColor(boldBackgroundColor)).append("\"}");
	}

	private static String cssColor(Color color)
	{
		if (color == null)
		{
			return "rgba(250, 249, 217, 1)";
		}
		return "rgba(" + color.getRed() + ", " + color.getGreen() + ", " + color.getBlue() + ", " + (color.getAlpha() / 255.0) + ")";
	}

	private static String iconAssetsJson(MapSettings settings)
	{
		StringBuilder result = new StringBuilder("{\"ok\":true,\"artPack\":\"").append(escape(settings.artPack)).append("\",\"types\":[");
		boolean firstType = true;
		ImageCache cache = ImageCache.getInstance(settings.artPack, settings.customImagesPath);
		for (IconType type : IconType.values())
		{
			if (type != IconType.mountains && type != IconType.sand && type != IconType.trees && type != IconType.cities)
			{
				continue;
			}
			if (!firstType)
			{
				result.append(",");
			}
			firstType = false;
			result.append("{\"type\":\"").append(type.name()).append("\",\"groups\":[");
			boolean firstGroup = true;
			for (String group : cache.getIconGroupNames(type))
			{
				if (!firstGroup)
				{
					result.append(",");
				}
				firstGroup = false;
				result.append("{\"id\":\"").append(escape(group)).append("\",\"icons\":[");
				boolean firstIcon = true;
				int count = 0;
				for (String iconName : cache.getIconGroupFileNamesWithoutWidthOrExtensionAsList(type, group))
				{
					if (count++ >= 120)
					{
						break;
					}
					if (!firstIcon)
					{
						result.append(",");
					}
					firstIcon = false;
					result.append("\"").append(escape(iconName)).append("\"");
				}
				result.append("]}");
			}
			result.append("]}");
		}
		result.append("]}");
		return result.toString();
	}

	private static int borderPadding(MapSettings settings)
	{
		return Background.calcBorderWidthScaledByResolution(settings);
	}

	private static int borderPadding(EditorSession session)
	{
		return session.mapParts != null && session.mapParts.background != null
				? session.mapParts.background.getBorderPaddingScaledByResolution()
				: borderPadding(session.settings);
	}

	private static void storeEditorSession(String sessionId, MapSettings settings, MapParts mapParts, Image map, Dimension maxDimensions)
	{
		EditorSession previous = EDITOR_SESSIONS.put(sessionId, new EditorSession(settings, mapParts, map, maxDimensions));
		if (previous != null)
		{
			previous.close();
		}
		if (isBrushLoggingEnabled())
		{
			brushLog("session:stored", "sessionId", sessionId, "mapWidth", map.getWidth(), "mapHeight", map.getHeight(), "resolution", settings.resolution);
		}
	}

	private static EditorSession getOrCreateEditorSession(String sessionId, Path project, Dimension maxDimensions) throws Exception
	{
		EditorSession existing = EDITOR_SESSIONS.get(sessionId);
		if (existing != null)
		{
			return existing;
		}
		long startedAt = System.nanoTime();
		MapSettings settings = new MapSettings(project.toString());
		MapParts mapParts = new MapParts();
		Image map = new MapCreator().createMap(settings, maxDimensions, mapParts);
		EditorSession session = new EditorSession(settings, mapParts, map, maxDimensions);
		EditorSession previous = EDITOR_SESSIONS.put(sessionId, session);
		if (previous != null)
		{
			previous.close();
		}
		if (isBrushLoggingEnabled())
		{
			brushLog("session:created", "sessionId", sessionId, "durationMs", elapsedMs(startedAt), "mapWidth", map.getWidth(), "mapHeight", map.getHeight(), "resolution", settings.resolution);
		}
		return session;
	}

	private static void applyEditCommand(MapSettings settings, JSONObject command)
	{
		if (command == null)
		{
			throw new IllegalArgumentException("Missing edit command");
		}
		String type = (String) command.get("type");
		if ("text.add".equals(type))
		{
			ensureEdits(settings);
			double resolution = settings.resolution == 0.0 ? 1.0 : settings.resolution;
			double x = ((Number) command.get("x")).doubleValue() * resolution;
			double y = ((Number) command.get("y")).doubleValue() * resolution;
			String text = ((String) command.get("text")).trim();
			MapText mapText = TextDrawer.createMapText(text, new Point(x, y), 0.0, readTextType(command.get("textType")), settings.resolution);
			settings.edits.text.add(mapText);
			settings.edits.textBoundsNeedRefresh = true;
			return;
		}
		if ("text.update".equals(type))
		{
			ensureEdits(settings);
			int index = ((Number) command.get("index")).intValue();
			if (index < 0 || index >= settings.edits.text.size())
			{
				throw new IllegalArgumentException("Text index out of range");
			}
			MapText mapText = settings.edits.text.get(index);
			if (command.get("x") instanceof Number && command.get("y") instanceof Number)
			{
				mapText.location = new Point(((Number) command.get("x")).doubleValue(), ((Number) command.get("y")).doubleValue());
			}
			mapText.value = ((String) command.get("text")).trim();
			mapText.type = readTextType(command.get("textType"));
			mapText.line1Bounds = null;
			mapText.line2Bounds = null;
			settings.edits.textBoundsNeedRefresh = true;
			return;
		}
		if ("text.delete".equals(type))
		{
			ensureEdits(settings);
			String text = ((String) command.get("text")).trim();
			settings.edits.text.removeIf(mapText -> text.equals(mapText.value));
			settings.edits.textBoundsNeedRefresh = true;
			return;
		}
		if ("terrain.brush".equals(type))
		{
			ensureEdits(settings);
			String mode = ((String) command.get("mode")).trim();
			double resolution = settings.resolution == 0.0 ? 1.0 : settings.resolution;
			int borderPadding = borderPadding(settings);
			double x = ((Number) command.get("x")).doubleValue() * resolution - borderPadding;
			double y = ((Number) command.get("y")).doubleValue() * resolution - borderPadding;
			double radius = ((Number) command.get("radius")).doubleValue() * resolution;
			if (isBrushLoggingEnabled())
			{
				brushLog("command:terrain", "mode", mode, "x", x, "y", y, "radius", radius, "resolution", resolution, "borderPadding", borderPadding,
						"borderPaddingRi", borderPadding / resolution, "editsInitialized", settings.edits.isInitialized());
			}
			applyTerrainBrush(settings, mode, new Point(x, y), radius);
			return;
		}
		WorldGraph graph = MapCreator.createGraph(settings, !settings.edits.isInitialized());
		if (!settings.edits.isInitialized())
		{
			settings.edits.initializeCenterEdits(graph.centers);
		}
		if (settings.edits.regionEdits.isEmpty())
		{
			settings.edits.initializeRegionEdits(graph.regions.values());
		}
		if ("icon.center.set".equals(type) || "icon.center.clear".equals(type) || "relief.erase".equals(type) || "trees.center.set".equals(type) || "trees.center.clear".equals(type) || "road.add".equals(type) || "road.draw".equals(type) || "road.erase".equals(type)
				|| "river.draw".equals(type) || "river.erase".equals(type) || "region.paint".equals(type) || "region.islands.lasso".equals(type) || "region.boundary.draw".equals(type)
				|| "region.boundary.erase".equals(type))
		{
			applyMapToolCommand(settings, graph, command, false, null);
			return;
		}
		throw new IllegalArgumentException("Unknown edit command: " + type);
	}

	private static boolean isIncrementalCommand(JSONObject command)
	{
		Object type = command.get("type");
		return "terrain.brush".equals(type) || "text.add".equals(type) || "text.update".equals(type) || "text.delete".equals(type) || "icon.center.set".equals(type) || "icon.center.clear".equals(type) || "relief.erase".equals(type) || "trees.center.set".equals(type) || "trees.center.clear".equals(type) || "road.add".equals(type) || "road.draw".equals(type) || "road.erase".equals(type)
				|| "river.draw".equals(type) || "river.erase".equals(type) || "region.paint".equals(type) || "region.islands.lasso".equals(type)
				|| "region.boundary.draw".equals(type) || "region.boundary.erase".equals(type);
	}

	private static boolean isRegionColorCommand(JSONObject command)
	{
		if (command == null)
		{
			return false;
		}
		Object type = command.get("type");
		return "region.paint".equals(type) || "region.islands.lasso".equals(type);
	}

	private static boolean isTextCommand(JSONObject command)
	{
		if (command == null)
		{
			return false;
		}
		Object type = command.get("type");
		return "text.add".equals(type) || "text.update".equals(type) || "text.delete".equals(type);
	}

	private static void applyTerrainBrush(MapSettings settings, String mode, Point point, double radius)
	{
		long startedAt = System.nanoTime();
		WorldGraph graph = MapCreator.createGraph(settings, !settings.edits.isInitialized());
		if (isBrushLoggingEnabled())
		{
			brushLog("graph:created", "durationMs", elapsedMs(startedAt), "centerCount", graph.centers.size(), "width", graph.getWidth(), "height", graph.getHeight());
		}
		if (!settings.edits.isInitialized())
		{
			settings.edits.initializeCenterEdits(graph.centers);
			if (isBrushLoggingEnabled())
			{
				brushLog("edits:initialized", "durationMs", elapsedMs(startedAt), "centerEditCount", settings.edits.centerEdits.size());
			}
		}

		long selectStartedAt = System.nanoTime();
		Set<Center> selected = selectCentersForBrush(graph, point, radius);
		Integer regionIdToExpand = "land".equals(mode) ? findNearestRegionId(settings, graph, point) : null;
		if (isBrushLoggingEnabled())
		{
			brushLog("centers:selected", "durationMs", elapsedMs(selectStartedAt), "selectedCount", selected.size(), "regionIdToExpand", regionIdToExpand);
		}
		int changed = 0;
		for (Center center : selected)
		{
			CenterEdit edit = settings.edits.centerEdits.get(center.index);
			if (edit == null)
			{
				edit = new CenterEdit(center.index, center.isWater, center.isLake, center.region != null ? center.region.id : null, null, null);
			}
			if ("water".equals(mode))
			{
				if (!edit.isWater || edit.isLake || edit.regionId != null || edit.trees != null)
				{
					changed++;
				}
				settings.edits.centerEdits.put(edit.index, new CenterEdit(edit.index, true, false, null, edit.icon, null));
			}
			else if ("land".equals(mode))
			{
				Integer regionId = edit.regionId != null ? edit.regionId : regionIdToExpand;
				if (edit.isWater || edit.isLake || (regionId != null && !regionId.equals(edit.regionId)))
				{
					changed++;
				}
				settings.edits.centerEdits.put(edit.index, new CenterEdit(edit.index, false, false, regionId, edit.icon, edit.trees));
			}
			else
			{
				throw new IllegalArgumentException("Unknown terrain brush mode: " + mode);
			}
		}
		if (isBrushLoggingEnabled())
		{
			brushLog("terrain:applied", "durationMs", elapsedMs(startedAt), "selectedCount", selected.size(), "changedCount", changed);
		}
	}

	private static IncrementalPreview applyIncrementalEdit(MapSettings persistedSettings, JSONObject command, EditorSession session, boolean returnPreview) throws IOException
	{
		long startedAt = System.nanoTime();
		synchronized (session)
		{
			ensureEdits(persistedSettings);
			ensureEdits(session.settings);
			if (!session.settings.edits.isInitialized())
			{
				session.settings.edits.initializeCenterEdits(session.mapParts.graph.centers);
			}
			if (!persistedSettings.edits.isInitialized())
			{
				persistedSettings.edits.initializeCenterEdits(session.mapParts.graph.centers);
			}
			if (session.settings.edits.regionEdits.isEmpty())
			{
				session.settings.edits.initializeRegionEdits(session.mapParts.graph.regions.values());
			}
			if (persistedSettings.edits.regionEdits.isEmpty())
			{
				persistedSettings.edits.initializeRegionEdits(session.mapParts.graph.regions.values());
			}
			boolean sharedSettings = persistedSettings == session.settings;
			Set<Integer> changedIds;
			if ("terrain.brush".equals(command.get("type")))
			{
				changedIds = applyTerrainBrushToSession(persistedSettings, command, session, sharedSettings);
			}
			else if (isTextCommand(command))
			{
				Integer sessionBorderPadding = borderPadding(session);
				List<MapText> changedText = new ArrayList<>();
				Object commandType = command.get("type");
				if (("text.update".equals(commandType) || "text.delete".equals(commandType)) && command.get("index") instanceof Number)
				{
					int index = ((Number) command.get("index")).intValue();
					if (index >= 0 && index < session.settings.edits.text.size())
					{
						changedText.add(session.settings.edits.text.get(index).deepCopy());
					}
				}
				changedIds = applyMapToolCommand(session.settings, session.mapParts.graph, command, true, sessionBorderPadding);
				if (!sharedSettings)
				{
					applyMapToolCommand(persistedSettings, session.mapParts.graph, command, true, sessionBorderPadding);
				}
				if ("text.add".equals(commandType) && !session.settings.edits.text.isEmpty())
				{
					changedText.add(session.settings.edits.text.get(session.settings.edits.text.size() - 1));
				}
				else if ("text.update".equals(commandType) && command.get("index") instanceof Number)
				{
					int index = ((Number) command.get("index")).intValue();
					if (index >= 0 && index < session.settings.edits.text.size())
					{
						changedText.add(session.settings.edits.text.get(index));
					}
				}
				IntRectangle updateBounds = null;
				if (returnPreview && !changedText.isEmpty())
				{
					updateBounds = new MapCreator().incrementalUpdateText(session.settings, session.mapParts, session.map, changedText);
				}
				if (isTextFieldLoggingEnabled())
				{
					textLog("incremental:redraw", "durationMs", elapsedMs(startedAt), "changedCount", changedIds.size(), "textBounds", changedText.size(), "command", command);
				}
				return returnPreview ? createIncrementalPreview(session.map, updateBounds) : null;
			}
			else
			{
				Integer sessionBorderPadding = borderPadding(session);
				changedIds = applyMapToolCommand(session.settings, session.mapParts.graph, command, true, sessionBorderPadding);
				if (!sharedSettings)
				{
					applyMapToolCommand(persistedSettings, session.mapParts.graph, command, true, sessionBorderPadding);
				}
			}

			if (!returnPreview)
			{
				if (isBrushLoggingEnabled())
				{
					brushLog("incremental:settings-only", "durationMs", elapsedMs(startedAt), "changedCount", changedIds.size());
				}
				return null;
			}

			long redrawStartedAt = System.nanoTime();
			IntRectangle updateBounds = new MapCreator().incrementalUpdateForCentersAndEdges(session.settings, session.mapParts, session.map, changedIds, null, false);
			IncrementalPreview preview = createIncrementalPreview(session.map, updateBounds);
			if (isBrushLoggingEnabled())
			{
				brushLog("incremental:success", "durationMs", elapsedMs(startedAt), "redrawAndEncodeMs", elapsedMs(redrawStartedAt), "selectedCount", changedIds.size(), "mapWidth", session.map.getWidth(),
						"mapHeight", session.map.getHeight(), "patchWidth", updateBounds == null ? 0 : updateBounds.width, "patchHeight", updateBounds == null ? 0 : updateBounds.height);
			}
			return preview;
		}
	}

	private static IncrementalPreview createIncrementalPreview(Image map, IntRectangle bounds) throws IOException
	{
		if (bounds == null)
		{
			return null;
		}
		IntRectangle clipped = bounds.findIntersection(new IntRectangle(0, 0, map.getWidth(), map.getHeight()));
		if (clipped == null || clipped.isEmpty())
		{
			return null;
		}
		try (Image patch = map.copySubImage(clipped))
		{
			return new IncrementalPreview(imageToBase64(patch, ".png"), clipped);
		}
	}

	private static String rectangleJson(IntRectangle bounds)
	{
		return "{\"x\":" + bounds.x + ",\"y\":" + bounds.y + ",\"width\":" + bounds.width + ",\"height\":" + bounds.height + "}";
	}

	private static EditorSession applyFirstRegionPaint(String sessionId, JSONObject command, EditorSession previous) throws IOException
	{
		long startedAt = System.nanoTime();
		synchronized (previous)
		{
			MapSettings nextSettings = previous.settings.deepCopy();
			ensureEdits(nextSettings);
			if (!nextSettings.edits.isInitialized())
			{
				nextSettings.edits.initializeCenterEdits(previous.mapParts.graph.centers);
			}
			if (nextSettings.edits.regionEdits.isEmpty())
			{
				nextSettings.edits.initializeRegionEdits(previous.mapParts.graph.regions.values());
			}
			applyMapToolCommand(nextSettings, previous.mapParts.graph, command, true, borderPadding(previous));
			MapParts nextParts = new MapParts();
			Image nextMap = null;
			try
			{
				nextMap = new MapCreator().createMap(nextSettings, previous.maxDimensions, nextParts);
				EditorSession next = new EditorSession(nextSettings, nextParts, nextMap, previous.maxDimensions);
				next.copyHistoryFrom(previous);
				if (!EDITOR_SESSIONS.replace(sessionId, previous, next))
				{
					throw new IllegalStateException("Editor session changed during region color initialization");
				}
				previous.close();
				if (isRegionPaintLoggingEnabled())
				{
					regionPaintLog("cache:initialized", "sessionId", sessionId, "durationMs", elapsedMs(startedAt), "mapWidth", nextMap.getWidth(), "mapHeight", nextMap.getHeight());
				}
				return next;
			}
			catch (Exception ex)
			{
				if (nextMap != null)
				{
					nextMap.close();
				}
				nextParts.closeImages();
				throw ex instanceof IOException ? (IOException) ex : new IOException("Unable to initialize region colors", ex);
			}
		}
	}

	private static Set<Integer> applyTerrainBrushToSession(MapSettings persistedSettings, JSONObject command, EditorSession session, boolean sharedSettings)
	{
		String mode = ((String) command.get("mode")).trim();
		double resolution = session.settings.resolution == 0.0 ? 1.0 : session.settings.resolution;
		int borderPadding = borderPadding(session);
		double commandX = ((Number) command.get("x")).doubleValue();
		double commandY = ((Number) command.get("y")).doubleValue();
		double commandRadius = ((Number) command.get("radius")).doubleValue();
		double x = commandX * resolution - borderPadding;
		double y = commandY * resolution - borderPadding;
		double radius = commandRadius * resolution;
		Point point = new Point(x, y);
		Set<Center> selected = selectCentersForBrush(session.mapParts.graph, point, radius);
		Integer regionIdToExpand = "land".equals(mode) ? findNearestRegionId(session.settings, session.mapParts.graph, point) : null;
		if (isBrushLoggingEnabled())
		{
			brushLog("coordinates:edit", "mode", mode, "commandX", commandX, "commandY", commandY, "commandRadius", commandRadius, "scaledX", x, "scaledY", y,
					"scaledRadius", radius, "resolution", resolution, "mapWidth", session.map.getWidth(), "mapHeight", session.map.getHeight(), "coordinateWidth",
					session.map.getWidth() / resolution, "coordinateHeight", session.map.getHeight() / resolution, "borderPadding", borderPadding,
					"borderPaddingRi", borderPadding / resolution, "selectedCount", selected.size(), "regionIdToExpand", regionIdToExpand);
		}
		Set<Integer> changedIds = new HashSet<>();
		for (Center center : selected)
		{
			changedIds.add(center.index);
			applyCenterTerrainEdit(session.settings, center, mode, regionIdToExpand);
			if (!sharedSettings)
			{
				applyCenterTerrainEdit(persistedSettings, center, mode, regionIdToExpand);
			}
		}
		return changedIds;
	}

	private static Set<Integer> applyMapToolCommand(MapSettings settings, WorldGraph graph, JSONObject command, boolean sessionCoordinates, Integer sessionBorderPadding)
	{
		String type = (String) command.get("type");
		if ("text.add".equals(type))
		{
			settings.drawText = true;
			Point point = readGraphPointScaled(settings, command, sessionCoordinates, sessionBorderPadding);
			String text = ((String) command.get("text")).trim();
			TextType textType = readTextType(command.get("textType"));
			MapText mapText = TextDrawer.createMapText(text, point, 0.0, textType, settings.resolution);
			settings.edits.text.add(mapText);
			settings.edits.textBoundsNeedRefresh = true;
			if (isTextFieldLoggingEnabled())
			{
				textLog("command:add", "text", text, "textType", textType, "point", point, "count", settings.edits.text.size());
			}
			Center center = graph.findClosestCenter(point, true);
			return center == null ? Set.of() : Set.of(center.index);
		}
		if ("text.update".equals(type))
		{
			settings.drawText = true;
			int index = ((Number) command.get("index")).intValue();
			if (index < 0 || index >= settings.edits.text.size())
			{
				throw new IllegalArgumentException("Text index out of range");
			}
			MapText mapText = settings.edits.text.get(index);
			double resolution = settings.resolution == 0.0 ? 1.0 : settings.resolution;
			Point previousPoint = mapText.location.mult(resolution);
			Point nextPoint = previousPoint;
			if (command.get("x") instanceof Number && command.get("y") instanceof Number)
			{
				nextPoint = readGraphPointScaled(settings, command, sessionCoordinates, sessionBorderPadding);
				mapText.location = nextPoint.mult(1.0 / resolution);
			}
			mapText.value = ((String) command.get("text")).trim();
			mapText.type = readTextType(command.get("textType"));
			mapText.line1Bounds = null;
			mapText.line2Bounds = null;
			settings.edits.textBoundsNeedRefresh = true;
			if (isTextFieldLoggingEnabled())
			{
				textLog("command:update", "index", index, "text", mapText.value, "textType", mapText.type, "previousPoint", previousPoint, "nextPoint", nextPoint, "location", mapText.location,
						"sessionCoordinates", sessionCoordinates, "sessionBorderPadding", sessionBorderPadding);
			}
			Set<Integer> changed = new HashSet<>();
			Center previousCenter = graph.findClosestCenter(previousPoint, true);
			Center nextCenter = graph.findClosestCenter(nextPoint, true);
			if (previousCenter != null)
			{
				changed.add(previousCenter.index);
			}
			if (nextCenter != null)
			{
				changed.add(nextCenter.index);
			}
			return changed;
		}
		if ("text.delete".equals(type))
		{
			int index = ((Number) command.get("index")).intValue();
			if (index < 0 || index >= settings.edits.text.size())
			{
				throw new IllegalArgumentException("Text index out of range");
			}
			MapText removed = settings.edits.text.remove(index);
			settings.edits.textBoundsNeedRefresh = true;
			Point previousPoint = removed.location.mult(settings.resolution == 0.0 ? 1.0 : settings.resolution);
			Center previousCenter = graph.findClosestCenter(previousPoint, true);
			if (isTextFieldLoggingEnabled())
			{
				textLog("command:delete", "index", index, "text", removed.value, "point", previousPoint, "remaining", settings.edits.text.size());
			}
			return previousCenter == null ? Set.of() : Set.of(previousCenter.index);
		}
		if ("relief.erase".equals(type))
		{
			Point point = readGraphPointScaled(settings, command, sessionCoordinates, sessionBorderPadding);
			double resolution = settings.resolution == 0.0 ? 1.0 : settings.resolution;
			double radius = command.get("radius") instanceof Number ? ((Number) command.get("radius")).doubleValue() * resolution : 48.0 * resolution;
			Set<Center> selected = selectCentersForBrush(graph, point, radius);
			Set<Integer> changed = new HashSet<>();
			List<nortantis.editor.FreeIcon> removedIcons = settings.edits.freeIcons.doWithLockAndReturnResult(() ->
			{
				List<nortantis.editor.FreeIcon> removed = new ArrayList<>();
				for (nortantis.editor.FreeIcon icon : settings.edits.freeIcons)
				{
					if (icon == null)
					{
						continue;
					}
					boolean selectedAnchor = icon.centerIndex != null && selected.stream().anyMatch(center -> center.index == icon.centerIndex);
					boolean selectedLocation = icon.getScaledLocation(resolution).distanceTo(point) <= radius;
					if (selectedAnchor || selectedLocation)
					{
						removed.add(icon);
					}
				}
				settings.edits.freeIcons.removeAll(removed);
				return removed;
			});
			for (Center center : selected)
			{
				CenterEdit edit = settings.edits.centerEdits.get(center.index);
				if (edit == null)
				{
					edit = new CenterEdit(center.index, center.isWater, center.isLake, center.region != null ? center.region.id : null, null, null);
				}
				settings.edits.centerEdits.put(center.index, new CenterEdit(edit.index, edit.isWater, edit.isLake, edit.regionId, null, null));
				changed.add(center.index);
			}
			for (nortantis.editor.FreeIcon icon : removedIcons)
			{
				Center center = icon.centerIndex != null && icon.centerIndex >= 0 && icon.centerIndex < graph.centers.size()
						? graph.centers.get(icon.centerIndex)
						: graph.findClosestCenter(icon.getScaledLocation(resolution), true);
				if (center != null)
				{
					changed.add(center.index);
				}
			}
			settings.edits.hasIconEdits = true;
			if (isIconsLoggingEnabled())
			{
				iconLog("relief:erased", "point", point, "radius", radius, "selectedCenters", selected.size(), "removedIcons", removedIcons.size(), "changedCount", changed.size());
			}
			return changed;
		}
		if ("icon.center.set".equals(type) || "icon.center.clear".equals(type))
		{
			Point point = readGraphPointScaled(settings, command, sessionCoordinates, sessionBorderPadding);
			Center center = graph.findClosestCenter(point, true);
			if (center == null)
			{
				return Set.of();
			}
			CenterEdit edit = settings.edits.centerEdits.get(center.index);
			if (edit == null)
			{
				edit = new CenterEdit(center.index, center.isWater, center.isLake, center.region != null ? center.region.id : null, null, null);
			}
			CenterIcon icon = null;
			if ("icon.center.set".equals(type))
			{
				icon = readCenterIcon(settings, command);
			}
			settings.edits.centerEdits.put(edit.index, new CenterEdit(edit.index, edit.isWater, edit.isLake, edit.regionId, icon, edit.trees));
			settings.edits.hasIconEdits = true;
			if (isIconsLoggingEnabled())
			{
				iconLog("center:changed", "type", type, "centerId", center.index, "point", point, "icon", icon);
			}
			return Set.of(center.index);
		}
		if ("trees.center.set".equals(type) || "trees.center.clear".equals(type))
		{
			Point point = readGraphPointScaled(settings, command, sessionCoordinates, sessionBorderPadding);
			double resolution = settings.resolution == 0.0 ? 1.0 : settings.resolution;
			double radius = command.get("radius") instanceof Number ? ((Number) command.get("radius")).doubleValue() * resolution : 0.0;
			Set<Center> selected = radius > 0.0 ? selectCentersForBrush(graph, point, radius) : new HashSet<>();
			if (selected.isEmpty())
			{
				Center closest = graph.findClosestCenter(point, true);
				if (closest != null)
				{
					selected.add(closest);
				}
			}
			String artPack = command.get("artPack") instanceof String && !((String) command.get("artPack")).isBlank() ? (String) command.get("artPack") : settings.artPack;
			String treeType = command.get("treeType") instanceof String ? ((String) command.get("treeType")).trim() : "";
			if ("trees.center.set".equals(type) && treeType.isBlank())
			{
				throw new IllegalArgumentException("Missing tree type");
			}
			double density = command.get("density") instanceof Number ? ((Number) command.get("density")).doubleValue() : 0.5;
			Set<Integer> changed = new HashSet<>();
			for (Center center : selected)
			{
				CenterEdit edit = settings.edits.centerEdits.get(center.index);
				if (edit == null)
				{
					edit = new CenterEdit(center.index, center.isWater, center.isLake, center.region != null ? center.region.id : null, null, null);
				}
				CenterTrees trees = "trees.center.set".equals(type)
						? new CenterTrees(artPack, treeType, Math.max(0.1, Math.min(1.0, density)), center.treeSeed)
						: null;
				settings.edits.centerEdits.put(edit.index, new CenterEdit(edit.index, edit.isWater, edit.isLake, edit.regionId, edit.icon, trees));
				changed.add(center.index);
			}
			settings.edits.hasIconEdits = true;
			if (isForestsLoggingEnabled())
			{
				forestLog("centers:changed", "type", type, "point", point, "radius", radius, "selectedCount", selected.size(), "changedCount", changed.size(), "treeType", treeType, "density", density);
			}
			return changed;
		}
		if ("road.add".equals(type) || "road.draw".equals(type) || "road.erase".equals(type))
		{
			List<Point> graphLine = readLineGraphPoints(settings, command, sessionCoordinates, sessionBorderPadding);
			Set<Integer> changed = new HashSet<>();
			double resolution = settings.resolution == 0.0 ? 1.0 : settings.resolution;
			double radius = command.get("radius") instanceof Number ? ((Number) command.get("radius")).doubleValue() * resolution : Math.max(8.0, averageNeighborDistance(graph) * 0.5);
			addCentersAndNeighbors(changed, centersNearBoundaryLine(settings, graph, graphLine, Math.max(radius, averageNeighborDistance(graph))));
			settings.drawRoads = true;
			if (!"road.erase".equals(type))
			{
				settings.edits.roads.add(Road.fromLocations(graphLine.stream().map(point -> point.mult(1.0 / resolution)).toList()));
			}
			else
			{
				List<Road> replacement = new ArrayList<>();
				int touched = 0;
				for (Road road : settings.edits.roads)
				{
					List<Road> parts = splitRoadOutsideStroke(road, graphLine, radius, resolution);
					if (parts.size() != 1 || parts.get(0) != road)
					{
						touched++;
					}
					replacement.addAll(parts);
				}
				settings.edits.roads = new CopyOnWriteArrayList<>(replacement);
				if (isRoadsLoggingEnabled())
				{
					roadLog("path:erased", "touched", touched, "remaining", replacement.size(), "changedCount", changed.size());
				}
			}
			if (isRoadsLoggingEnabled())
			{
				roadLog("path:changed", "type", type, "pointCount", graphLine.size(), "changedCount", changed.size(), "roadCount", settings.edits.roads.size());
			}
			return changed;
		}
		if ("river.draw".equals(type) || "river.erase".equals(type))
		{
			List<Point> graphLine = readLineGraphPoints(settings, command, sessionCoordinates, sessionBorderPadding);
			double resolution = settings.resolution == 0.0 ? 1.0 : settings.resolution;
			double radius = command.get("radius") instanceof Number ? ((Number) command.get("radius")).doubleValue() * resolution : Math.max(8.0, averageNeighborDistance(graph) * 0.5);
			Set<Integer> changed = new HashSet<>();
			if ("river.draw".equals(type))
			{
				SnappedPath snapped = snapLineToGraphEdges(graph, graphLine, radius);
				if (snapped == null || snapped.edges().isEmpty())
				{
					throw new IllegalArgumentException("River could not be snapped to map edges");
				}
				int sliderBase = command.get("width") instanceof Number ? ((Number) command.get("width")).intValue() : 2;
				int widthLevel = GraphRiver.sliderBaseToRiverLevel(Math.max(0, Math.min(GraphRiver.MAX_RIVER_SLIDER_BASE, sliderBase)));
				List<RiverPathNode> nodes = new ArrayList<>();
				for (int index = 0; index < snapped.points().size(); index++)
				{
					boolean last = index == snapped.points().size() - 1;
					Edge edge = last ? null : snapped.edges().get(index);
					nodes.add(new RiverPathNode(snapped.points().get(index).mult(1.0 / resolution), last ? 0 : widthLevel, last ? 0L : edge.noisyEdgesSeed,
							last ? RiverPathNode.EDGE_INDEX_NONE : edge.index));
				}
				settings.edits.rivers.add(new River(nodes));
				addCentersAndNeighbors(changed, graph.getCentersFromEdges(snapped.edges()));
				if (isRiversLoggingEnabled())
				{
					riverLog("path:added", "edgeIds", snapped.edges().stream().map(edge -> edge.index).toList(), "widthLevel", widthLevel, "changedCount", changed.size());
				}
			}
			else
			{
				List<River> replacement = new ArrayList<>();
				int touched = 0;
				for (River river : settings.edits.rivers)
				{
					List<River> parts = splitRiverOutsideStroke(river, graphLine, radius, resolution);
					if (parts.size() != 1 || parts.get(0) != river)
					{
						touched++;
					}
					replacement.addAll(parts);
				}
				settings.edits.rivers = new CopyOnWriteArrayList<>(replacement);
				addCentersAndNeighbors(changed, centersNearBoundaryLine(settings, graph, graphLine, Math.max(radius, averageNeighborDistance(graph))));
				if (isRiversLoggingEnabled())
				{
					riverLog("path:erased", "touched", touched, "remaining", replacement.size(), "changedCount", changed.size());
				}
			}
			return changed;
		}
		if ("region.islands.lasso".equals(type))
		{
			long paintStartedAt = System.nanoTime();
			List<Point> lasso = closePolygon(readLineGraphPoints(settings, command, sessionCoordinates, sessionBorderPadding));
			Set<Center> selected = selectLandComponentsFullyInsideLasso(settings, graph, lasso);
			if (selected.isEmpty())
			{
				if (isRegionPaintLoggingEnabled())
				{
					regionPaintLog("island-lasso:empty", "durationMs", elapsedMs(paintStartedAt), "lassoPoints", lasso.size());
				}
				return Set.of();
			}
			int targetRegionId = createNewRegionId(settings, graph);
			for (Center center : selected)
			{
				CenterEdit edit = settings.edits.centerEdits.get(center.index);
				if (edit == null)
				{
					edit = new CenterEdit(center.index, center.isWater, center.isLake, center.region != null ? center.region.id : null, null, null);
				}
				settings.edits.centerEdits.put(edit.index, new CenterEdit(edit.index, edit.isWater, edit.isLake, targetRegionId, edit.icon, edit.trees));
			}
			settings.drawRegionColors = true;
			settings.edits.regionEdits.put(targetRegionId, new RegionEdit(targetRegionId, parseHexColor((String) command.get("color"))));
			Set<Integer> changed = new HashSet<>();
			addCentersAndNeighbors(changed, selected);
			if (isRegionPaintLoggingEnabled())
			{
				regionPaintLog("island-lasso:assigned", "durationMs", elapsedMs(paintStartedAt), "lassoPoints", lasso.size(), "selectedCenters", selected.size(), "targetRegionId", targetRegionId, "changedCount", changed.size());
			}
			return changed;
		}
		if ("region.paint".equals(type))
		{
			long paintStartedAt = System.nanoTime();
			Point point = readGraphPointScaled(settings, command, sessionCoordinates, sessionBorderPadding);
			Center center = graph.findClosestCenter(point, true);
			if (center == null)
			{
				return Set.of();
			}
			CenterEdit edit = settings.edits.centerEdits.get(center.index);
			Integer regionId = edit != null && edit.regionId != null ? edit.regionId : center.region != null ? center.region.id : null;
			if (regionId == null)
			{
				throw new IllegalArgumentException("No region at point");
			}
			double barrierRadius = customBoundaryBarrierRadius(settings, graph);
			Set<Edge> blockedEdges = findCustomBoundaryEdges(settings, graph, regionId, barrierRadius);
			Set<Center> paintedCenters = collectRegionComponent(settings, center, regionId, blockedEdges);
			if (paintedCenters.isEmpty())
			{
				paintedCenters = Set.of(center);
			}
			Set<Center> sameRegionCenters = centersForRegion(settings, graph, regionId);
			double resolvedBarrierRadius = barrierRadius;
			int componentCount = blockedEdges.isEmpty() ? 1 : countRegionComponents(settings, graph, regionId, blockedEdges);
			boolean isPartialRegion = !blockedEdges.isEmpty() && componentCount > 1 && paintedCenters.size() < sameRegionCenters.size();
			int targetRegionId = regionId;
			if (isPartialRegion)
			{
				targetRegionId = createNewRegionId(settings, graph);
				for (Center paintedCenter : paintedCenters)
				{
					CenterEdit centerEdit = settings.edits.centerEdits.get(paintedCenter.index);
					if (centerEdit == null)
					{
						centerEdit = new CenterEdit(
								paintedCenter.index,
								paintedCenter.isWater,
								paintedCenter.isLake,
								paintedCenter.region != null ? paintedCenter.region.id : null,
								null,
								null);
					}
					settings.edits.centerEdits.put(centerEdit.index, new CenterEdit(centerEdit.index, centerEdit.isWater, centerEdit.isLake, targetRegionId, centerEdit.icon, centerEdit.trees));
				}
			}
			settings.drawRegionColors = true;
			settings.edits.regionEdits.put(targetRegionId, new RegionEdit(targetRegionId, parseHexColor((String) command.get("color"))));
			Set<Integer> changed = new HashSet<>();
			if (isPartialRegion)
			{
				addCentersAndNeighbors(changed, paintedCenters);
			}
			else
			{
				for (Center graphCenter : graph.centers)
				{
					Integer centerRegionId = getCenterRegionId(settings, graphCenter);
					if (centerRegionId != null && targetRegionId == centerRegionId)
					{
						changed.add(graphCenter.index);
					}
				}
			}
			if (isRegionPaintLoggingEnabled())
			{
				regionPaintLog("component", "durationMs", elapsedMs(paintStartedAt), "sourceRegionId", regionId, "targetRegionId", targetRegionId, "partialRegion", isPartialRegion,
						"split", componentCount > 1, "componentCount", componentCount, "barrierRadius", barrierRadius, "resolvedBarrierRadius", resolvedBarrierRadius, "blockedEdges", blockedEdges.size(),
						"sameRegionCenters", sameRegionCenters.size(), "paintedCenters", paintedCenters.size(), "changedCount", changed.size());
			}
			return changed;
		}
		if ("region.boundary.draw".equals(type) || "region.boundary.erase".equals(type))
		{
			long borderStartedAt = System.nanoTime();
			settings.drawRegionBoundaries = true;
			if (settings.edits.regionBoundaryLines == null)
			{
				settings.edits.regionBoundaryLines = new CopyOnWriteArrayList<>();
			}
			List<Point> line = readLineGraphPoints(settings, command, sessionCoordinates, sessionBorderPadding);
			double resolution = settings.resolution == 0.0 ? 1.0 : settings.resolution;
			double radius = ((Number) command.get("radius")).doubleValue() * resolution;
			Set<Integer> changed = new HashSet<>();
			if ("region.boundary.draw".equals(type))
			{
				SnappedBoundary snapped = snapBoundaryToRegionEdges(settings, graph, line, radius);
				if (snapped == null || snapped.edges().isEmpty() || snapped.points().size() < 2)
				{
					throw new IllegalArgumentException("Boundary could not be snapped to a region edge path");
				}
				List<Point> snappedRi = snapped.points().stream().map(point -> point.mult(1.0 / resolution)).toList();
				double selectionRadius = Math.max(radius * 1.75, 42.0);
				Set<Center> centersToRedraw = centersNearBoundaryLine(settings, graph, snapped.points(), selectionRadius);
				BoundarySplit split = splitRegionAlongBoundary(settings, graph, snapped);
				centersToRedraw.addAll(split.centers());
				if (isBordersLoggingEnabled())
				{
					borderLog("boundary:snapped", "inputPoints", line.size(), "snappedPoints", snapped.points().size(), "snappedEdges", snapped.edges().size(), "edgeIds", snapped.edges().stream().map(edge -> edge.index).toList(),
							"sourceRegionId", snapped.sourceRegionId(), "createdRegionId", split.createdRegionId(), "selectionRadius", selectionRadius, "splitCenters", split.centers().size(), "redrawCenters", centersToRedraw.size());
				}
				settings.edits.regionBoundaryLines.add(new RegionBoundary(snappedRi, snapped.edges().stream().map(edge -> edge.index).toList(), snapped.sourceRegionId(), split.createdRegionId()));
				addCentersAndNeighbors(changed, centersToRedraw);
				if (isBordersLoggingEnabled())
				{
					borderLog("boundary:draw-success", "durationMs", elapsedMs(borderStartedAt), "changedCount", changed.size(), "lineCount", settings.edits.regionBoundaryLines.size());
				}
				return changed;
			}
			int removedLines = 0;
			List<RegionBoundary> removed = new ArrayList<>();
			for (RegionBoundary boundaryLine : settings.edits.regionBoundaryLines)
			{
				if (boundaryLine.nodes == null || boundaryLine.nodes.size() < 2)
				{
					continue;
				}
				List<Point> boundaryGraphLine = new ArrayList<>();
				for (RoadPathNode node : boundaryLine.nodes)
				{
					boundaryGraphLine.add(node.getLoc().mult(settings.resolution == 0.0 ? 1.0 : settings.resolution));
				}
				Set<Edge> boundaryEdges = resolveBoundaryEdges(boundaryLine, graph, resolution);
				boolean touched = boundaryEdges.stream().anyMatch(edge -> edge.v0 != null && edge.v1 != null && lineTouchesSegment(line, edge.v0.loc, edge.v1.loc, radius));
				if (!touched)
				{
					touched = linesAreClose(boundaryGraphLine, line, radius);
				}
				if (touched)
				{
					removed.add(boundaryLine);
					mergeBoundaryRegions(settings, graph, boundaryLine, changed);
					addCentersAndNeighbors(changed, centersNearBoundaryLine(settings, graph, boundaryGraphLine, Math.max(radius * 1.75, 42.0)));
					if (isBordersLoggingEnabled())
					{
						borderLog("boundary:erase-hit", "edgeIds", boundaryLine.edgeIds, "sourceRegionId", boundaryLine.sourceRegionId, "createdRegionId", boundaryLine.createdRegionId,
								"resolvedEdges", boundaryEdges.size());
					}
				}
			}
			for (RegionBoundary boundaryLine : removed)
			{
				if (settings.edits.regionBoundaryLines.remove(boundaryLine))
				{
					removedLines++;
				}
			}
			if (isBordersLoggingEnabled())
			{
				borderLog("boundary:erase-success", "durationMs", elapsedMs(borderStartedAt), "removedLines", removedLines, "changedCount", changed.size(), "lineCount", settings.edits.regionBoundaryLines.size());
			}
			if (removedLines == 0)
			{
				Set<String> mergedPairs = new HashSet<>();
				for (Edge edge : findNativeRegionEdgesNearLine(settings, graph, line, radius))
				{
					if (edge.d0 == null || edge.d1 == null || !isEditableRegionCenter(settings, edge.d0) || !isEditableRegionCenter(settings, edge.d1))
					{
						continue;
					}
					Integer first = getCenterRegionId(settings, edge.d0);
					Integer second = getCenterRegionId(settings, edge.d1);
					if (first == null || second == null || first.equals(second))
					{
						continue;
					}
					int retained = Math.min(first, second);
					int removedRegion = Math.max(first, second);
					if (mergedPairs.add(retained + ":" + removedRegion))
					{
						mergeRegionIds(settings, graph, retained, removedRegion, changed);
					}
				}
				if (isBordersLoggingEnabled())
				{
					borderLog("boundary:native-erase", "mergedPairs", mergedPairs, "changedCount", changed.size());
				}
			}
			return changed;
		}
		throw new IllegalArgumentException("Unknown map tool command: " + type);
	}

	private static String regionColorJson(MapSettings settings, WorldGraph graph, JSONObject command, boolean sessionCoordinates, Integer sessionBorderPadding)
	{
		ensureEdits(settings);
		if (!settings.edits.isInitialized())
		{
			settings.edits.initializeCenterEdits(graph.centers);
		}
		if (settings.edits.regionEdits.isEmpty())
		{
			settings.edits.initializeRegionEdits(graph.regions.values());
		}
		Point point = readGraphPointScaled(settings, command, sessionCoordinates, sessionBorderPadding);
		Center center = graph.findClosestCenter(point, true);
		if (center == null)
		{
			return "{\"ok\":false,\"error\":\"notFound\"}";
		}
		Integer regionId = getCenterRegionId(settings, center);
		if (regionId == null)
		{
			return "{\"ok\":false,\"error\":\"notFound\"}";
		}
		nortantis.platform.Color color = getRegionColor(settings, graph, regionId);
		if (color == null)
		{
			return "{\"ok\":false,\"error\":\"notFound\"}";
		}
		return "{\"ok\":true,\"regionId\":" + regionId + ",\"color\":\"" + colorToHex(color) + "\"}";
	}

	private static String textPickJson(EditorSession session, JSONObject command)
	{
		ensureEdits(session.settings);
		TextDrawer textDrawer = new TextDrawer(session.settings);
		textDrawer.setMapTexts(session.settings.edits.text);
		textDrawer.updateTextBoundsIfNeeded(session.mapParts.graph);
		Point point = readGraphPointScaled(session.settings, command, true, borderPadding(session));
		MapText selected = session.settings.edits.findTextPicked(point);
		if (isTextFieldLoggingEnabled())
		{
			textLog("pick:point", "point", point, "textCount", session.settings.edits.text.size(), "selected", selected);
		}
		if (selected == null)
		{
			return "{\"ok\":true,\"text\":null}";
		}
		int index = session.settings.edits.text.indexOf(selected);
		double resolution = session.settings.resolution == 0.0 ? 1.0 : session.settings.resolution;
		double paddingRi = borderPadding(session) / resolution;
		return "{\"ok\":true,\"text\":{\"index\":" + index
				+ ",\"text\":\"" + escape(selected.value) + "\""
				+ ",\"textType\":\"" + selected.type.name() + "\""
				+ ",\"x\":" + (selected.location.x + paddingRi)
				+ ",\"y\":" + (selected.location.y + paddingRi)
				+ "}}";
	}

	private static Integer getCenterRegionId(MapSettings settings, Center center)
	{
		CenterEdit edit = settings.edits.centerEdits.get(center.index);
		if (edit != null && edit.regionId != null)
		{
			return edit.regionId;
		}
		return center.region == null ? null : center.region.id;
	}

	private static nortantis.platform.Color getRegionColor(MapSettings settings, WorldGraph graph, int regionId)
	{
		RegionEdit edit = settings.edits.regionEdits.get(regionId);
		if (edit != null)
		{
			return edit.color;
		}
		Region region = graph.regions.get(regionId);
		return region == null ? null : region.backgroundColor;
	}

	private static int createNewRegionId(MapSettings settings, WorldGraph graph)
	{
		int largest = -1;
		for (Integer id : graph.regions.keySet())
		{
			largest = Math.max(largest, id);
		}
		for (RegionEdit edit : settings.edits.regionEdits.values())
		{
			largest = Math.max(largest, edit.regionId);
		}
		return largest + 1;
	}

	private static List<Point> readLineGraphPoints(MapSettings settings, JSONObject command, boolean sessionCoordinates, Integer sessionBorderPadding)
	{
		JSONArray values = (JSONArray) command.get("points");
		if (values == null || values.size() < 2)
		{
			throw new IllegalArgumentException("Boundary line requires at least two points");
		}
		List<Point> result = new ArrayList<>();
		for (Object value : values)
		{
			if (value instanceof JSONObject)
			{
				result.add(readGraphPointScaled(settings, (JSONObject) value, sessionCoordinates, sessionBorderPadding));
			}
		}
		if (result.size() < 2)
		{
			throw new IllegalArgumentException("Boundary line requires at least two valid points");
		}
		return result;
	}

	private static void addCentersAndNeighbors(Set<Integer> changed, Set<Center> centers)
	{
		for (Center center : centers)
		{
			changed.add(center.index);
			for (Center neighbor : center.neighbors)
			{
				changed.add(neighbor.index);
			}
		}
	}

	private static boolean linesAreClose(List<Point> first, List<Point> second, double radius)
	{
		for (int i = 0; i < first.size() - 1; i++)
		{
			Point a = first.get(i);
			Point b = first.get(i + 1);
			for (int j = 0; j < second.size() - 1; j++)
			{
				if (segmentsClose(a, b, second.get(j), second.get(j + 1), radius))
				{
					return true;
				}
			}
		}
		return false;
	}

	private static SnappedBoundary snapBoundaryToRegionEdges(MapSettings settings, WorldGraph graph, List<Point> line, double radius)
	{
		Center sourceCenter = graph.centers.stream()
				.filter(center -> isEditableRegionCenter(settings, center))
				.min(Comparator.comparingDouble(center -> center.loc.distanceTo(line.get(0))))
				.orElse(null);
		Integer sourceRegionId = sourceCenter == null ? null : getCenterRegionId(settings, sourceCenter);
		if (sourceRegionId == null)
		{
			return null;
		}

		Set<Edge> allowedEdges = new HashSet<>();
		Set<Corner> allowedCorners = new HashSet<>();
		for (Edge edge : graph.edges)
		{
			if (edge.v0 == null || edge.v1 == null || edge.d0 == null || edge.d1 == null)
			{
				continue;
			}
			if (!sourceRegionId.equals(getCenterRegionId(settings, edge.d0)) || !sourceRegionId.equals(getCenterRegionId(settings, edge.d1)))
			{
				continue;
			}
			if (!isEditableRegionCenter(settings, edge.d0) || !isEditableRegionCenter(settings, edge.d1))
			{
				continue;
			}
			allowedEdges.add(edge);
			allowedCorners.add(edge.v0);
			allowedCorners.add(edge.v1);
		}
		if (allowedEdges.isEmpty())
		{
			return null;
		}

		double neighborDistance = averageNeighborDistance(graph);
		boolean closed = line.size() >= 4 && line.get(0).distanceTo(line.get(line.size() - 1)) <= Math.max(radius * 1.5, neighborDistance * 1.35);
		List<Corner> waypoints = boundaryWaypoints(line, allowedCorners, sourceRegionId, settings, neighborDistance, closed);
		if (waypoints.size() < 2)
		{
			return null;
		}
		List<Edge> orderedEdges = new ArrayList<>();
		for (int index = 0; index < waypoints.size() - 1; index++)
		{
			Corner start = waypoints.get(index);
			Corner end = waypoints.get(index + 1);
			List<Point> target = List.of(start.loc, end.loc);
			List<Edge> segment = shortestEdgePath(start, end, allowedEdges, target, Math.max(8.0, radius));
			if (segment.isEmpty())
			{
				return null;
			}
			for (Edge edge : segment)
			{
				if (!orderedEdges.isEmpty() && orderedEdges.get(orderedEdges.size() - 1).equals(edge))
				{
					continue;
				}
				orderedEdges.add(edge);
			}
		}
		if (orderedEdges.isEmpty())
		{
			return null;
		}
		List<Point> orderedPoints = new ArrayList<>();
		Corner current = waypoints.get(0);
		orderedPoints.add(current.loc);
		for (Edge edge : orderedEdges)
		{
			Corner next = edge.getOtherCorner(current);
			if (next == null)
			{
				return null;
			}
			current = next;
			orderedPoints.add(current.loc);
		}
		if (isBordersLoggingEnabled())
		{
			borderLog("boundary:waypoints", "closed", closed, "inputPoints", line.size(), "waypoints", waypoints.stream().map(corner -> corner.index).toList(),
					"edgeIds", orderedEdges.stream().map(edge -> edge.index).toList());
		}
		return new SnappedBoundary(orderedEdges, orderedPoints, sourceRegionId);
	}

	private static List<Corner> boundaryWaypoints(List<Point> line, Set<Corner> corners, Integer sourceRegionId, MapSettings settings, double spacing, boolean closed)
	{
		List<Corner> result = new ArrayList<>();
		Corner start = closed
				? nearestCorner(corners, line.get(0), null)
				: nearestRegionBoundaryAnchor(corners, line.get(0), sourceRegionId, settings, null);
		if (start == null)
		{
			return result;
		}
		result.add(start);
		double accumulated = 0.0;
		Point previous = line.get(0);
		double sampleSpacing = Math.max(8.0, spacing * 0.65);
		for (int index = 1; index < line.size() - 1; index++)
		{
			Point point = line.get(index);
			accumulated += previous.distanceTo(point);
			previous = point;
			if (accumulated < sampleSpacing)
			{
				continue;
			}
			Corner waypoint = nearestCorner(corners, point, result.get(result.size() - 1));
			if (waypoint != null)
			{
				result.add(waypoint);
				accumulated = 0.0;
			}
		}
		Corner end = closed
				? start
				: nearestRegionBoundaryAnchor(corners, line.get(line.size() - 1), sourceRegionId, settings, result.get(result.size() - 1));
		if (end != null && (closed || !end.equals(result.get(result.size() - 1))))
		{
			result.add(end);
		}
		return result;
	}

	private static Corner nearestCorner(Set<Corner> corners, Point point, Corner excluded)
	{
		return corners.stream().filter(corner -> excluded == null || !corner.equals(excluded)).min(Comparator.comparingDouble(corner -> corner.loc.distanceTo(point))).orElse(null);
	}

	private static Corner nearestRegionBoundaryAnchor(Set<Corner> corners, Point point, Integer sourceRegionId, MapSettings settings, Corner excluded)
	{
		Corner anchored = corners.stream()
				.filter(corner -> (excluded == null || !corner.equals(excluded)) && isRegionBoundaryAnchor(corner, sourceRegionId, settings))
				.min(Comparator.comparingDouble(corner -> corner.loc.distanceTo(point)))
				.orElse(null);
		return anchored != null ? anchored : nearestCorner(corners, point, excluded);
	}

	private static List<Edge> shortestEdgePath(Corner start, Corner end, Set<Edge> allowedEdges, List<Point> targetLine, double attractionScale)
	{
		if (start.equals(end))
		{
			return List.of();
		}
		Map<Corner, Double> distances = new HashMap<>();
		Map<Corner, Edge> previousEdges = new HashMap<>();
		PriorityQueue<BoundaryQueueNode> queue = new PriorityQueue<>(Comparator.comparingDouble(BoundaryQueueNode::distance));
		distances.put(start, 0.0);
		queue.add(new BoundaryQueueNode(start, 0.0));
		while (!queue.isEmpty())
		{
			BoundaryQueueNode node = queue.poll();
			if (node.distance() > distances.getOrDefault(node.corner(), Double.MAX_VALUE))
			{
				continue;
			}
			if (node.corner().equals(end))
			{
				break;
			}
			for (Edge edge : node.corner().protrudes)
			{
				if (!allowedEdges.contains(edge))
				{
					continue;
				}
				Corner next = edge.getOtherCorner(node.corner());
				if (next == null)
				{
					continue;
				}
				double lineDistance = distanceToLine(edge.midpoint, targetLine) / attractionScale;
				double weight = edge.v0.loc.distanceTo(edge.v1.loc) * (1.0 + lineDistance * lineDistance * 10.0);
				double nextDistance = node.distance() + weight;
				if (nextDistance < distances.getOrDefault(next, Double.MAX_VALUE))
				{
					distances.put(next, nextDistance);
					previousEdges.put(next, edge);
					queue.add(new BoundaryQueueNode(next, nextDistance));
				}
			}
		}
		if (!previousEdges.containsKey(end))
		{
			return List.of();
		}
		List<Edge> reversed = new ArrayList<>();
		Corner current = end;
		while (!current.equals(start))
		{
			Edge edge = previousEdges.get(current);
			if (edge == null)
			{
				return List.of();
			}
			reversed.add(edge);
			current = edge.getOtherCorner(current);
		}
		List<Edge> result = new ArrayList<>(reversed.size());
		for (int index = reversed.size() - 1; index >= 0; index--)
		{
			result.add(reversed.get(index));
		}
		return result;
	}

	private static SnappedPath snapLineToGraphEdges(WorldGraph graph, List<Point> line, double radius)
	{
		Set<Edge> allowedEdges = new HashSet<>();
		Set<Corner> corners = new HashSet<>();
		for (Edge edge : graph.edges)
		{
			if (edge.v0 == null || edge.v1 == null)
			{
				continue;
			}
			allowedEdges.add(edge);
			corners.add(edge.v0);
			corners.add(edge.v1);
		}
		if (allowedEdges.isEmpty())
		{
			return null;
		}
		List<Corner> waypoints = new ArrayList<>();
		Corner start = nearestCorner(corners, line.get(0), null);
		if (start == null)
		{
			return null;
		}
		waypoints.add(start);
		double spacing = Math.max(8.0, averageNeighborDistance(graph) * 0.65);
		double accumulated = 0.0;
		Point previous = line.get(0);
		for (int index = 1; index < line.size() - 1; index++)
		{
			Point point = line.get(index);
			accumulated += previous.distanceTo(point);
			previous = point;
			if (accumulated < spacing)
			{
				continue;
			}
			Corner waypoint = nearestCorner(corners, point, waypoints.get(waypoints.size() - 1));
			if (waypoint != null)
			{
				waypoints.add(waypoint);
				accumulated = 0.0;
			}
		}
		Corner end = nearestCorner(corners, line.get(line.size() - 1), waypoints.get(waypoints.size() - 1));
		if (end != null)
		{
			waypoints.add(end);
		}
		List<Edge> edges = new ArrayList<>();
		for (int index = 0; index < waypoints.size() - 1; index++)
		{
			List<Edge> segment = shortestEdgePath(waypoints.get(index), waypoints.get(index + 1), allowedEdges,
					List.of(waypoints.get(index).loc, waypoints.get(index + 1).loc), Math.max(8.0, radius));
			if (segment.isEmpty())
			{
				return null;
			}
			for (Edge edge : segment)
			{
				if (edges.isEmpty() || !edges.get(edges.size() - 1).equals(edge))
				{
					edges.add(edge);
				}
			}
		}
		if (edges.isEmpty())
		{
			return null;
		}
		List<Point> points = new ArrayList<>();
		Corner current = waypoints.get(0);
		points.add(current.loc);
		for (Edge edge : edges)
		{
			current = edge.getOtherCorner(current);
			if (current == null)
			{
				return null;
			}
			points.add(current.loc);
		}
		return new SnappedPath(edges, points);
	}

	private static boolean isRegionBoundaryAnchor(Corner corner, Integer sourceRegionId, MapSettings settings)
	{
		for (Edge edge : corner.protrudes)
		{
			Integer leftRegion = edge.d0 == null ? null : getCenterRegionId(settings, edge.d0);
			Integer rightRegion = edge.d1 == null ? null : getCenterRegionId(settings, edge.d1);
			boolean leftSource = sourceRegionId.equals(leftRegion) && isEditableRegionCenter(settings, edge.d0);
			boolean rightSource = sourceRegionId.equals(rightRegion) && isEditableRegionCenter(settings, edge.d1);
			if (leftSource != rightSource)
			{
				return true;
			}
		}
		return corner.isBorder;
	}

	private static BoundarySplit splitRegionAlongBoundary(MapSettings settings, WorldGraph graph, SnappedBoundary boundary)
	{
		Set<Edge> blockedEdges = new HashSet<>(boundary.edges());
		for (Edge edge : boundary.edges())
		{
			if (edge.d0 == null || edge.d1 == null)
			{
				continue;
			}
			if (!boundary.sourceRegionId().equals(getCenterRegionId(settings, edge.d0)) || !boundary.sourceRegionId().equals(getCenterRegionId(settings, edge.d1)))
			{
				continue;
			}
			Set<Center> left = collectRegionComponent(settings, edge.d0, boundary.sourceRegionId(), blockedEdges);
			Set<Center> right = collectRegionComponent(settings, edge.d1, boundary.sourceRegionId(), blockedEdges);
			if (left.isEmpty() || right.isEmpty() || left.equals(right))
			{
				continue;
			}
			Set<Center> splitCenters = left.size() <= right.size() ? left : right;
			int newRegionId = createNewRegionId(settings, graph);
			nortantis.platform.Color sourceColor = getRegionColor(settings, graph, boundary.sourceRegionId());
			settings.edits.regionEdits.put(newRegionId, new RegionEdit(newRegionId, sourceColor == null ? settings.regionBaseColor : sourceColor));
			for (Center center : splitCenters)
			{
				CenterEdit edit = settings.edits.centerEdits.get(center.index);
				if (edit == null)
				{
					edit = new CenterEdit(center.index, center.isWater, center.isLake, boundary.sourceRegionId(), null, null);
				}
				settings.edits.centerEdits.put(center.index, edit.copyWithRegionId(newRegionId));
			}
			if (isBordersLoggingEnabled())
			{
				borderLog("boundary:region-split", "sourceRegionId", boundary.sourceRegionId(), "newRegionId", newRegionId, "leftSize", left.size(), "rightSize", right.size(), "movedCenters", splitCenters.size());
			}
			return new BoundarySplit(splitCenters, newRegionId);
		}
		throw new IllegalArgumentException("Boundary must connect existing region or coast boundaries");
	}

	private static void mergeBoundaryRegions(MapSettings settings, WorldGraph graph, RegionBoundary boundary, Set<Integer> changed)
	{
		Integer retainedRegion = boundary.sourceRegionId;
		Integer removedRegion = boundary.createdRegionId;
		if (retainedRegion == null || removedRegion == null)
		{
			double resolution = settings.resolution == 0.0 ? 1.0 : settings.resolution;
			for (Edge edge : resolveBoundaryEdges(boundary, graph, resolution))
			{
				Integer left = edge.d0 == null ? null : getCenterRegionId(settings, edge.d0);
				Integer right = edge.d1 == null ? null : getCenterRegionId(settings, edge.d1);
				if (left != null && right != null && !left.equals(right))
				{
					retainedRegion = Math.min(left, right);
					removedRegion = Math.max(left, right);
					break;
				}
			}
		}
		if (retainedRegion == null || removedRegion == null || retainedRegion.equals(removedRegion))
		{
			return;
		}
		if (!regionsConnectedWithoutBoundary(settings, graph, retainedRegion, removedRegion, boundary))
		{
			if (isBordersLoggingEnabled())
			{
				borderLog("boundary:merge-skipped", "retainedRegionId", retainedRegion, "removedRegionId", removedRegion, "reason", "remaining-barrier");
			}
			return;
		}
		Set<Center> merged = centersForRegion(settings, graph, removedRegion);
		for (Center center : merged)
		{
			CenterEdit edit = settings.edits.centerEdits.get(center.index);
			if (edit == null)
			{
				edit = new CenterEdit(center.index, center.isWater, center.isLake, removedRegion, null, null);
			}
			settings.edits.centerEdits.put(center.index, edit.copyWithRegionId(retainedRegion));
		}
		settings.edits.regionEdits.remove(removedRegion);
		addCentersAndNeighbors(changed, merged);
		if (isBordersLoggingEnabled())
		{
			borderLog("boundary:region-merged", "removedRegionId", removedRegion, "retainedRegionId", retainedRegion, "mergedCenters", merged.size());
		}
	}

	private static void mergeRegionIds(MapSettings settings, WorldGraph graph, int retainedRegion, int removedRegion, Set<Integer> changed)
	{
		Set<Center> merged = centersForRegion(settings, graph, removedRegion);
		for (Center center : merged)
		{
			CenterEdit edit = settings.edits.centerEdits.get(center.index);
			if (edit == null)
			{
				edit = new CenterEdit(center.index, center.isWater, center.isLake, removedRegion, null, null);
			}
			settings.edits.centerEdits.put(center.index, edit.copyWithRegionId(retainedRegion));
		}
		settings.edits.regionEdits.remove(removedRegion);
		addCentersAndNeighbors(changed, merged);
		if (isBordersLoggingEnabled())
		{
			borderLog("boundary:native-region-merged", "removedRegionId", removedRegion, "retainedRegionId", retainedRegion, "mergedCenters", merged.size());
		}
	}

	private static boolean regionsConnectedWithoutBoundary(MapSettings settings, WorldGraph graph, Integer firstRegion, Integer secondRegion, RegionBoundary excluded)
	{
		double resolution = settings.resolution == 0.0 ? 1.0 : settings.resolution;
		Set<Edge> blocked = new HashSet<>();
		for (RegionBoundary other : settings.edits.regionBoundaryLines)
		{
			if (other != excluded)
			{
				blocked.addAll(resolveBoundaryEdges(other, graph, resolution));
			}
		}
		Center start = graph.centers.stream().filter(center -> firstRegion.equals(getCenterRegionId(settings, center))).findFirst().orElse(null);
		if (start == null)
		{
			return false;
		}
		Set<Center> visited = new HashSet<>();
		ArrayDeque<Center> queue = new ArrayDeque<>();
		visited.add(start);
		queue.add(start);
		while (!queue.isEmpty())
		{
			Center current = queue.removeFirst();
			if (secondRegion.equals(getCenterRegionId(settings, current)))
			{
				return true;
			}
			for (Edge edge : current.borders)
			{
				if (blocked.contains(edge))
				{
					continue;
				}
				Center next = edge.d0 == current ? edge.d1 : edge.d0;
				if (next == null || visited.contains(next))
				{
					continue;
				}
				Integer nextRegion = getCenterRegionId(settings, next);
				if (firstRegion.equals(nextRegion) || secondRegion.equals(nextRegion))
				{
					visited.add(next);
					queue.add(next);
				}
			}
		}
		return false;
	}

	private static Set<Edge> resolveBoundaryEdges(RegionBoundary boundary, WorldGraph graph, double resolution)
	{
		Set<Edge> result = new HashSet<>();
		if (boundary.edgeIds != null && !boundary.edgeIds.isEmpty())
		{
			Set<Integer> ids = new HashSet<>(boundary.edgeIds);
			for (Edge edge : graph.edges)
			{
				if (ids.contains(edge.index))
				{
					result.add(edge);
				}
			}
		}
		if (!result.isEmpty() || boundary.nodes == null || boundary.nodes.size() < 2)
		{
			return result;
		}
		List<Point> graphLine = boundary.nodes.stream().map(node -> node.getLoc().mult(resolution)).toList();
		return findEdgesMatchingLineSegments(graph, graphLine, Math.max(2.0, averageNeighborDistance(graph) * 0.12));
	}

	private static boolean lineTouchesSegment(List<Point> line, Point start, Point end, double radius)
	{
		for (int index = 0; index < line.size() - 1; index++)
		{
			if (segmentsClose(start, end, line.get(index), line.get(index + 1), radius))
			{
				return true;
			}
		}
		return false;
	}

	private static List<Road> splitRoadOutsideStroke(Road road, List<Point> stroke, double radius, double resolution)
	{
		if (road.nodes == null || road.nodes.size() < 2)
		{
			return List.of();
		}
		List<Road> result = new ArrayList<>();
		List<RoadPathNode> current = new ArrayList<>();
		current.add(road.nodes.get(0));
		boolean touched = false;
		for (int index = 0; index < road.nodes.size() - 1; index++)
		{
			RoadPathNode next = road.nodes.get(index + 1);
			boolean erase = lineTouchesSegment(stroke, road.nodes.get(index).getLoc().mult(resolution), next.getLoc().mult(resolution), radius);
			if (erase)
			{
				touched = true;
				if (current.size() >= 2)
				{
					result.add(new Road(current));
				}
				current = new ArrayList<>();
				current.add(next);
			}
			else
			{
				current.add(next);
			}
		}
		if (current.size() >= 2)
		{
			result.add(new Road(current));
		}
		return touched ? result : List.of(road);
	}

	private static List<River> splitRiverOutsideStroke(River river, List<Point> stroke, double radius, double resolution)
	{
		if (river.nodes == null || river.nodes.size() < 2)
		{
			return List.of();
		}
		List<River> result = new ArrayList<>();
		List<RiverPathNode> current = new ArrayList<>();
		current.add(river.nodes.get(0));
		boolean touched = false;
		for (int index = 0; index < river.nodes.size() - 1; index++)
		{
			RiverPathNode next = river.nodes.get(index + 1);
			boolean erase = lineTouchesSegment(stroke, river.nodes.get(index).getLoc().mult(resolution), next.getLoc().mult(resolution), radius);
			if (erase)
			{
				touched = true;
				appendRiverPart(result, current);
				current = new ArrayList<>();
				current.add(next);
			}
			else
			{
				current.add(next);
			}
		}
		appendRiverPart(result, current);
		return touched ? result : List.of(river);
	}

	private static void appendRiverPart(List<River> result, List<RiverPathNode> nodes)
	{
		if (nodes.size() < 2)
		{
			return;
		}
		List<RiverPathNode> copy = new ArrayList<>(nodes);
		RiverPathNode last = copy.get(copy.size() - 1);
		copy.set(copy.size() - 1, new RiverPathNode(last.getLoc(), 0, 0L, RiverPathNode.EDGE_INDEX_NONE, last.getCornerIndexAnchor()));
		result.add(new River(copy));
	}

	private static Set<Edge> findEdgesMatchingLineSegments(WorldGraph graph, List<Point> line, double tolerance)
	{
		Set<Edge> result = new HashSet<>();
		for (int index = 0; index < line.size() - 1; index++)
		{
			Point start = line.get(index);
			Point end = line.get(index + 1);
			for (Edge edge : graph.edges)
			{
				if (edge.v0 == null || edge.v1 == null)
				{
					continue;
				}
				boolean forward = edge.v0.loc.distanceTo(start) <= tolerance && edge.v1.loc.distanceTo(end) <= tolerance;
				boolean reverse = edge.v1.loc.distanceTo(start) <= tolerance && edge.v0.loc.distanceTo(end) <= tolerance;
				if (forward || reverse)
				{
					result.add(edge);
					break;
				}
			}
		}
		return result;
	}

	private static Set<Edge> findNativeRegionEdgesNearLine(MapSettings settings, WorldGraph graph, List<Point> line, double radius)
	{
		Set<Edge> result = new HashSet<>();
		double hitRadius = Math.max(radius, averageNeighborDistance(graph) * 0.55);
		for (Edge edge : graph.edges)
		{
			if (edge.d0 == null || edge.d1 == null || edge.v0 == null || edge.v1 == null)
			{
				continue;
			}
			Integer first = getCenterRegionId(settings, edge.d0);
			Integer second = getCenterRegionId(settings, edge.d1);
			if (first == null || second == null || first.equals(second))
			{
				continue;
			}
			if (lineTouchesSegment(line, edge.v0.loc, edge.v1.loc, hitRadius) || lineTouchesSegment(line, edge.d0.loc, edge.d1.loc, hitRadius))
			{
				result.add(edge);
			}
		}
		return result;
	}

	private static Set<Center> centersNearBoundaryLine(MapSettings settings, WorldGraph graph, List<Point> line, double radius)
	{
		Set<Center> result = new HashSet<>();
		for (Center center : graph.centers)
		{
			if (!isEditableRegionCenter(settings, center))
			{
				continue;
			}
			if (distanceToLine(center.loc, line) <= radius)
			{
				result.add(center);
			}
		}
		double sampleStep = Math.max(6.0, radius * 0.35);
		for (int index = 0; index < line.size() - 1; index++)
		{
			Point start = line.get(index);
			Point end = line.get(index + 1);
			double segmentLength = start.distanceTo(end);
			int samples = Math.max(1, (int) Math.ceil(segmentLength / sampleStep));
			for (int sample = 0; sample <= samples; sample++)
			{
				double t = samples == 0 ? 0.0 : (double) sample / samples;
				Point point = new Point(start.x + (end.x - start.x) * t, start.y + (end.y - start.y) * t);
				Center center = graph.findClosestCenter(point, true);
				if (center != null && isEditableRegionCenter(settings, center))
				{
					result.add(center);
				}
			}
		}
		if (result.size() < 3)
		{
			Set<Center> expanded = new HashSet<>(result);
			for (Center center : result)
			{
				for (Center neighbor : center.neighbors)
				{
					if (isEditableRegionCenter(settings, neighbor) && distanceToLine(neighbor.loc, line) <= radius * 2.0)
					{
						expanded.add(neighbor);
					}
				}
			}
			result = expanded;
		}
		return result;
	}

	private static boolean isEditableRegionCenter(MapSettings settings, Center center)
	{
		CenterEdit edit = settings.edits.centerEdits.get(center.index);
		boolean isWater = edit != null ? edit.isWater : center.isWater;
		return !isWater && getCenterRegionId(settings, center) != null;
	}

	private static List<Point> closePolygon(List<Point> points)
	{
		if (points.size() < 3)
		{
			throw new IllegalArgumentException("Lasso requires at least three points");
		}
		List<Point> closed = new ArrayList<>(points);
		if (closed.get(0).distanceTo(closed.get(closed.size() - 1)) > 0.001)
		{
			closed.add(closed.get(0));
		}
		return closed;
	}

	private static Set<Center> selectLandComponentsFullyInsideLasso(MapSettings settings, WorldGraph graph, List<Point> closedLasso)
	{
		double minX = closedLasso.stream().mapToDouble(point -> point.x).min().orElse(0.0);
		double maxX = closedLasso.stream().mapToDouble(point -> point.x).max().orElse(0.0);
		double minY = closedLasso.stream().mapToDouble(point -> point.y).min().orElse(0.0);
		double maxY = closedLasso.stream().mapToDouble(point -> point.y).max().orElse(0.0);
		Set<Center> visited = new HashSet<>();
		Set<Center> selected = new HashSet<>();
		for (Center center : graph.centers)
		{
			if (visited.contains(center) || isCurrentWater(settings, center) || center.loc.x < minX || center.loc.x > maxX || center.loc.y < minY || center.loc.y > maxY)
			{
				continue;
			}
			Set<Center> component = collectConnectedLand(settings, center);
			visited.addAll(component);
			if (isLandComponentFullyInsideLasso(settings, graph, component, closedLasso))
			{
				selected.addAll(component);
			}
		}
		return selected;
	}

	private static Set<Center> collectConnectedLand(MapSettings settings, Center start)
	{
		Set<Center> result = new HashSet<>();
		ArrayDeque<Center> queue = new ArrayDeque<>();
		queue.add(start);
		while (!queue.isEmpty())
		{
			Center current = queue.removeFirst();
			if (!result.add(current))
			{
				continue;
			}
			for (Center neighbor : current.neighbors)
			{
				if (!result.contains(neighbor) && !isCurrentWater(settings, neighbor))
				{
					queue.addLast(neighbor);
				}
			}
		}
		return result;
	}

	private static boolean isLandComponentFullyInsideLasso(MapSettings settings, WorldGraph graph, Set<Center> component, List<Point> closedLasso)
	{
		for (Center center : component)
		{
			if (!isPointInsidePolygon(center.loc, closedLasso))
			{
				return false;
			}
		}
		Set<Integer> checkedCoastEdges = new HashSet<>();
		int coastEdgeCount = 0;
		for (Center center : component)
		{
			for (Edge edge : center.borders)
			{
				if (edge == null || !checkedCoastEdges.add(edge.index))
				{
					continue;
				}
				Center other = edge.d0 == center ? edge.d1 : edge.d0;
				if (other != null && !isCurrentWater(settings, other))
				{
					continue;
				}
				coastEdgeCount++;
				List<Point> coastline = graph.noisyEdges.getNoisyEdge(edge.index);
				if ((coastline == null || coastline.size() < 2) && edge.v0 != null && edge.v1 != null)
				{
					coastline = List.of(edge.v0.loc, edge.v1.loc);
				}
				if (coastline == null || coastline.isEmpty())
				{
					return false;
				}
				for (Point point : coastline)
				{
					if (!isPointInsidePolygon(point, closedLasso))
					{
						return false;
					}
				}
			}
		}
		return coastEdgeCount > 0;
	}

	private static boolean isCurrentWater(MapSettings settings, Center center)
	{
		CenterEdit edit = settings.edits.centerEdits.get(center.index);
		return edit != null ? edit.isWater : center.isWater;
	}

	private static boolean isPointInsidePolygon(Point point, List<Point> closedPolygon)
	{
		boolean inside = false;
		for (int index = 0; index < closedPolygon.size() - 1; index++)
		{
			Point start = closedPolygon.get(index);
			Point end = closedPolygon.get(index + 1);
			if (GeometryHelper.distanceFromPointToSegment(point, start, end) <= 0.75)
			{
				return true;
			}
			boolean crosses = (start.y > point.y) != (end.y > point.y)
					&& point.x < (end.x - start.x) * (point.y - start.y) / (end.y - start.y) + start.x;
			if (crosses)
			{
				inside = !inside;
			}
		}
		return inside;
	}

	private static double signedDistanceToNearestLineSegment(Point point, List<Point> line)
	{
		double bestDistance = Double.MAX_VALUE;
		double bestSignedDistance = 0.0;
		for (int index = 0; index < line.size() - 1; index++)
		{
			Point start = line.get(index);
			Point end = line.get(index + 1);
			double distance = GeometryHelper.distanceFromPointToSegment(point, start, end);
			if (distance < bestDistance)
			{
				bestDistance = distance;
				double dx = end.x - start.x;
				double dy = end.y - start.y;
				double length = Math.hypot(dx, dy);
				bestSignedDistance = length == 0.0 ? 0.0 : (dx * (point.y - start.y) - dy * (point.x - start.x)) / length;
			}
		}
		return bestSignedDistance;
	}

	private static double distanceToLine(Point point, List<Point> line)
	{
		double bestDistance = Double.MAX_VALUE;
		for (int index = 0; index < line.size() - 1; index++)
		{
			bestDistance = Math.min(bestDistance, GeometryHelper.distanceFromPointToSegment(point, line.get(index), line.get(index + 1)));
		}
		return bestDistance;
	}

	private static boolean segmentsClose(Point a, Point b, Point c, Point d, double radius)
	{
		return GeometryHelper.distanceFromPointToSegment(a, c, d) <= radius
				|| GeometryHelper.distanceFromPointToSegment(b, c, d) <= radius
				|| GeometryHelper.distanceFromPointToSegment(c, a, b) <= radius
				|| GeometryHelper.distanceFromPointToSegment(d, a, b) <= radius
				|| linesIntersect(a, b, c, d);
	}

	private static boolean linesIntersect(Point a, Point b, Point c, Point d)
	{
		int o1 = orientation(a, b, c);
		int o2 = orientation(a, b, d);
		int o3 = orientation(c, d, a);
		int o4 = orientation(c, d, b);
		if (o1 != o2 && o3 != o4)
		{
			return true;
		}
		return o1 == 0 && onSegment(a, c, b) || o2 == 0 && onSegment(a, d, b) || o3 == 0 && onSegment(c, a, d) || o4 == 0 && onSegment(c, b, d);
	}

	private static int orientation(Point a, Point b, Point c)
	{
		double value = (b.y - a.y) * (c.x - b.x) - (b.x - a.x) * (c.y - b.y);
		if (Math.abs(value) < 0.0000001)
		{
			return 0;
		}
		return value > 0 ? 1 : 2;
	}

	private static boolean onSegment(Point a, Point b, Point c)
	{
		return b.x <= Math.max(a.x, c.x) && b.x >= Math.min(a.x, c.x) && b.y <= Math.max(a.y, c.y) && b.y >= Math.min(a.y, c.y);
	}

	private static double customBoundaryBarrierRadius(MapSettings settings, WorldGraph graph)
	{
		double resolution = settings.resolution == 0.0 ? 1.0 : settings.resolution;
		double neighborDistance = averageNeighborDistance(graph);
		return Math.max(8.0, Math.min(Math.max(14.0 * resolution, neighborDistance * 0.55), 24.0));
	}

	private static double averageNeighborDistance(WorldGraph graph)
	{
		double total = 0.0;
		int count = 0;
		for (Center center : graph.centers)
		{
			for (Center neighbor : center.neighbors)
			{
				if (neighbor.index <= center.index)
				{
					continue;
				}
				total += center.loc.distanceTo(neighbor.loc);
				count++;
				if (count >= 600)
				{
					return total / count;
				}
			}
		}
		return count == 0 ? 42.0 : total / count;
	}

	private static Set<Edge> findCustomBoundaryEdges(MapSettings settings, WorldGraph graph, Integer regionId, double radius)
	{
		Set<Edge> result = new HashSet<>();
		if (regionId == null || settings.edits.regionBoundaryLines == null || settings.edits.regionBoundaryLines.isEmpty())
		{
			return result;
		}
		double resolution = settings.resolution == 0.0 ? 1.0 : settings.resolution;
		int lineIndex = 0;
		for (RegionBoundary boundaryLine : settings.edits.regionBoundaryLines)
		{
			lineIndex++;
			if (boundaryLine.nodes == null || boundaryLine.nodes.size() < 2)
			{
				continue;
			}
			List<Point> graphLine = new ArrayList<>();
			for (RoadPathNode node : boundaryLine.nodes)
			{
				graphLine.add(node.getLoc().mult(resolution));
			}
			Set<Edge> lineEdges = resolveBoundaryEdges(boundaryLine, graph, resolution);
			if (lineEdges.isEmpty())
			{
				lineEdges = findBarrierEdgesNearLine(settings, graph, graphLine, regionId, radius);
			}
			else
			{
				lineEdges.removeIf(edge -> edge.d0 == null || edge.d1 == null || !regionId.equals(getCenterRegionId(settings, edge.d0)) || !regionId.equals(getCenterRegionId(settings, edge.d1)));
			}
			result.addAll(lineEdges);
			if (isRegionPaintLoggingEnabled())
			{
				regionPaintLog("line-barrier", "lineIndex", lineIndex, "regionId", regionId, "linePoints", graphLine.size(), "radius", radius, "lineEdges", lineEdges.size(), "totalEdges", result.size());
			}
		}
		if (isRegionPaintLoggingEnabled())
		{
			regionPaintLog("blocked-edges", "regionId", regionId, "lineCount", settings.edits.regionBoundaryLines.size(), "radius", radius, "blockedEdges", result.size());
		}
		return result;
	}

	private static Set<Edge> findBarrierEdgesNearLine(MapSettings settings, WorldGraph graph, List<Point> line, Integer regionId, double radius)
	{
		Set<Edge> result = new HashSet<>();
		double edgeRadius = Math.max(3.0, radius * 0.6);
		for (Edge edge : graph.edges)
		{
			if (edge.d0 == null || edge.d1 == null || edge.v0 == null || edge.v1 == null)
			{
				continue;
			}
			if (!regionId.equals(getCenterRegionId(settings, edge.d0)) || !regionId.equals(getCenterRegionId(settings, edge.d1)))
			{
				continue;
			}
			for (int index = 0; index < line.size() - 1; index++)
			{
				Point start = line.get(index);
				Point end = line.get(index + 1);
				boolean centersStraddleLine = pointsStraddleLine(edge.d0.loc, edge.d1.loc, line, radius);
				if (linesIntersect(edge.v0.loc, edge.v1.loc, start, end)
						|| linesIntersect(edge.d0.loc, edge.d1.loc, start, end)
						|| centersStraddleLine && (segmentsClose(edge.v0.loc, edge.v1.loc, start, end, edgeRadius) || segmentsClose(edge.d0.loc, edge.d1.loc, start, end, radius)))
				{
					result.add(edge);
					break;
				}
			}
		}
		return result;
	}

	private static boolean pointsStraddleLine(Point left, Point right, List<Point> line, double tolerance)
	{
		double leftSigned = signedDistanceToNearestLineSegment(left, line);
		double rightSigned = signedDistanceToNearestLineSegment(right, line);
		if (leftSigned == 0.0 || rightSigned == 0.0)
		{
			return true;
		}
		if (leftSigned < 0.0 && rightSigned > 0.0 || leftSigned > 0.0 && rightSigned < 0.0)
		{
			return true;
		}
		return Math.abs(leftSigned) <= tolerance && distanceToLine(right, line) <= tolerance
				|| Math.abs(rightSigned) <= tolerance && distanceToLine(left, line) <= tolerance;
	}

	private static Set<Center> centersForRegion(MapSettings settings, WorldGraph graph, Integer regionId)
	{
		Set<Center> result = new HashSet<>();
		if (regionId == null)
		{
			return result;
		}
		for (Center center : graph.centers)
		{
			if (regionId.equals(getCenterRegionId(settings, center)))
			{
				result.add(center);
			}
		}
		return result;
	}

	private static int countRegionComponents(MapSettings settings, WorldGraph graph, Integer regionId, Set<Edge> blockedEdges)
	{
		Set<Center> remaining = centersForRegion(settings, graph, regionId);
		int components = 0;
		while (!remaining.isEmpty())
		{
			Center start = remaining.iterator().next();
			Set<Center> component = collectRegionComponent(settings, start, regionId, blockedEdges);
			remaining.removeAll(component);
			components++;
		}
		return components;
	}

	private static Set<Center> collectRegionComponent(MapSettings settings, Center start, Integer regionId, Set<Edge> blockedEdges)
	{
		Set<Center> result = new HashSet<>();
		ArrayList<Center> queue = new ArrayList<>();
		queue.add(start);
		while (!queue.isEmpty())
		{
			Center current = queue.remove(queue.size() - 1);
			if (!result.add(current))
			{
				continue;
			}
			for (Center neighbor : current.neighbors)
			{
				if (!regionId.equals(getCenterRegionId(settings, neighbor)) || result.contains(neighbor) || isBlockedBetween(current, neighbor, blockedEdges))
				{
					continue;
				}
				queue.add(neighbor);
			}
		}
		return result;
	}

	private static boolean isBlockedBetween(Center left, Center right, Set<Edge> blockedEdges)
	{
		for (Edge edge : blockedEdges)
		{
			if ((edge.d0 == left && edge.d1 == right) || (edge.d0 == right && edge.d1 == left))
			{
				return true;
			}
		}
		return false;
	}

	private static String colorToHex(nortantis.platform.Color color)
	{
		return String.format("#%02x%02x%02x", color.getRed(), color.getGreen(), color.getBlue());
	}

	private static CenterIcon readCenterIcon(MapSettings settings, JSONObject command)
	{
		String kind = ((String) command.get("iconKind")).trim();
		CenterIconType iconType = switch (kind)
		{
			case "mountains" -> CenterIconType.Mountain;
			case "hills" -> CenterIconType.Hill;
			case "sand" -> CenterIconType.Dune;
			case "cities" -> CenterIconType.City;
			default -> throw new IllegalArgumentException("Unsupported center icon kind: " + kind);
		};
		String artPack = command.get("artPack") instanceof String && !((String) command.get("artPack")).isBlank() ? (String) command.get("artPack") : settings.artPack;
		String groupId = command.get("groupId") instanceof String && !((String) command.get("groupId")).isBlank() ? (String) command.get("groupId") : "";
		String iconName = command.get("iconName") instanceof String && !((String) command.get("iconName")).isBlank() ? (String) command.get("iconName") : null;
		if (iconType == CenterIconType.City && iconName != null)
		{
			return new CenterIcon(iconType, artPack, groupId, iconName);
		}
		return new CenterIcon(iconType, artPack, groupId, iconName == null ? 0 : findIconIndex(IconDrawer.centerIconTypeToIconType(iconType), artPack, groupId, iconName, settings.customImagesPath));
	}

	private static int findIconIndex(IconType type, String artPack, String groupId, String iconName, String customImagesPath)
	{
		List<ImageAndMasks> icons = ImageCache.getInstance(artPack, customImagesPath).getIconsInGroup(type, groupId);
		for (int index = 0; index < icons.size(); index++)
		{
			if (iconName.equals(icons.get(index).fileNameWithoutParametersOrExtension))
			{
				return index;
			}
		}
		return 0;
	}

	private static Point readGraphPointScaled(MapSettings settings, JSONObject command, boolean sessionCoordinates, Integer sessionBorderPadding)
	{
		Point ri = readGraphPointRi(settings, command, sessionCoordinates, sessionBorderPadding);
		double resolution = settings.resolution == 0.0 ? 1.0 : settings.resolution;
		return ri.mult(resolution);
	}

	private static Point readGraphPointRi(MapSettings settings, JSONObject command, boolean sessionCoordinates, Integer sessionBorderPadding)
	{
		double x = ((Number) command.get("x")).doubleValue();
		double y = ((Number) command.get("y")).doubleValue();
		if (sessionCoordinates)
		{
			double resolution = settings.resolution == 0.0 ? 1.0 : settings.resolution;
			double paddingRi = (sessionBorderPadding == null ? borderPadding(settings) : sessionBorderPadding) / resolution;
			return new Point(x - paddingRi, y - paddingRi);
		}
		return new Point(x, y);
	}

	private static TextType readTextType(Object value)
	{
		if (!(value instanceof String) || ((String) value).isBlank())
		{
			return TextType.Other_mountains;
		}
		try
		{
			return TextType.valueOf(((String) value).trim());
		}
		catch (IllegalArgumentException ex)
		{
			throw new IllegalArgumentException("Invalid text type");
		}
	}

	private static nortantis.platform.Color parseHexColor(String color)
	{
		if (color == null || !color.matches("^#[0-9a-fA-F]{6}$"))
		{
			throw new IllegalArgumentException("Invalid color");
		}
		int red = Integer.parseInt(color.substring(1, 3), 16);
		int green = Integer.parseInt(color.substring(3, 5), 16);
		int blue = Integer.parseInt(color.substring(5, 7), 16);
		return nortantis.platform.Color.create(red, green, blue);
	}

	private static void applyCenterTerrainEdit(MapSettings settings, Center center, String mode, Integer regionIdToExpand)
	{
		CenterEdit edit = settings.edits.centerEdits.get(center.index);
		if (edit == null)
		{
			edit = new CenterEdit(center.index, center.isWater, center.isLake, center.region != null ? center.region.id : null, null, null);
		}
		if ("water".equals(mode))
		{
			settings.edits.centerEdits.put(edit.index, new CenterEdit(edit.index, true, false, null, edit.icon, null));
		}
		else if ("land".equals(mode))
		{
			Integer regionId = edit.regionId != null ? edit.regionId : regionIdToExpand;
			settings.edits.centerEdits.put(edit.index, new CenterEdit(edit.index, false, false, regionId, edit.icon, edit.trees));
		}
		else
		{
			throw new IllegalArgumentException("Unknown terrain brush mode: " + mode);
		}
	}

	private static String imageToBase64(Image image, String extension) throws IOException
	{
		Path temp = Files.createTempFile("nortantis-incremental-", extension);
		try
		{
			ImageHelper.getInstance().write(image, temp.toString());
			return Base64.getEncoder().encodeToString(Files.readAllBytes(temp));
		}
		finally
		{
			Files.deleteIfExists(temp);
		}
	}

	private static Set<Center> selectCentersForBrush(WorldGraph graph, Point point, double radius)
	{
		Set<Center> selected = new HashSet<>();
		if (!new RotatedRectangle(graph.bounds).overlapsCircle(point, radius))
		{
			return selected;
		}
		Center closest = graph.findClosestCenter(point);
		if (closest == null)
		{
			return selected;
		}
		selected.add(closest);
		if (radius <= 0.5)
		{
			return selected;
		}
		return graph.breadthFirstSearch(center -> isCenterOverlappingCircle(center, point, radius), closest);
	}

	private static boolean isCenterOverlappingCircle(Center center, Point point, double radius)
	{
		if (center.loc.distanceTo(point) <= radius)
		{
			return true;
		}
		for (Corner corner : center.corners)
		{
			if (corner.loc.distanceTo(point) <= radius)
			{
				return true;
			}
		}
		return false;
	}

	private static String brushSelectionJson(Set<Center> selected, EditorSession session)
	{
		StringBuilder result = new StringBuilder("{\"ok\":true,\"sessionReady\":true,\"metadata\":");
		result.append(sessionMetadataJson(session));
		result.append(",\"polygons\":");
		appendPolygonsJson(result, selected, session);
		result.append(",\"edges\":");
		appendSelectionEdgesJson(result, selected, session);
		result.append("}");
		return result.toString();
	}

	private static void appendSelectionEdgesJson(StringBuilder result, Set<Center> selected, EditorSession session)
	{
		double resolution = session.settings.resolution == 0.0 ? 1.0 : session.settings.resolution;
		int borderPadding = borderPadding(session);
		Set<Integer> appendedEdges = new HashSet<>();
		result.append("[");
		boolean firstEdge = true;
		for (Center center : selected)
		{
			for (Edge edge : center.borders)
			{
				if (edge == null || !appendedEdges.add(edge.index))
				{
					continue;
				}
				List<Point> points = session.mapParts.graph.noisyEdges.getNoisyEdge(edge.index);
				if ((points == null || points.size() < 2) && edge.v0 != null && edge.v1 != null)
				{
					points = List.of(edge.v0.loc, edge.v1.loc);
				}
				if (points == null || points.size() < 2)
				{
					continue;
				}
				boolean internal = edge.d0 != null && edge.d1 != null && selected.contains(edge.d0) && selected.contains(edge.d1);
				if (!firstEdge)
				{
					result.append(",");
				}
				firstEdge = false;
				result.append("{\"index\":").append(edge.index)
						.append(",\"internal\":").append(internal)
						.append(",\"points\":[");
				for (int pointIndex = 0; pointIndex < points.size(); pointIndex++)
				{
					if (pointIndex > 0)
					{
						result.append(",");
					}
					Point point = points.get(pointIndex);
					result.append("[")
							.append((point.x + borderPadding) / resolution)
							.append(",")
							.append((point.y + borderPadding) / resolution)
							.append("]");
				}
				result.append("]}");
			}
		}
		result.append("]");
	}

	private static String pathPreviewJson(List<Point> points, Set<Center> selected, EditorSession session)
	{
		double resolution = session.settings.resolution == 0.0 ? 1.0 : session.settings.resolution;
		int borderPadding = borderPadding(session);
		StringBuilder result = new StringBuilder("{\"ok\":true,\"points\":[");
		for (int index = 0; index < points.size(); index++)
		{
			if (index > 0)
			{
				result.append(",");
			}
			Point point = points.get(index);
			result.append("[").append((point.x + borderPadding) / resolution).append(",").append((point.y + borderPadding) / resolution).append("]");
		}
		result.append("],\"polygons\":");
		appendPolygonsJson(result, selected, session);
		result.append(",\"edges\":");
		appendSelectionEdgesJson(result, selected, session);
		result.append("}");
		return result.toString();
	}

	private static void appendPolygonsJson(StringBuilder result, Set<Center> selected, EditorSession session)
	{
		double resolution = session.settings.resolution == 0.0 ? 1.0 : session.settings.resolution;
		int borderPadding = borderPadding(session);
		result.append("[");
		boolean firstCenter = true;
		for (Center center : selected)
		{
			if (center.corners == null || center.corners.isEmpty())
			{
				continue;
			}
			if (!firstCenter)
			{
				result.append(",");
			}
			firstCenter = false;
			result.append("{\"index\":").append(center.index).append(",\"points\":[");
			boolean firstCorner = true;
			for (Corner corner : center.corners)
			{
				if (corner == null || corner.loc == null)
				{
					continue;
				}
				if (!firstCorner)
				{
					result.append(",");
				}
				firstCorner = false;
				result.append("[")
						.append((corner.loc.x + borderPadding) / resolution)
						.append(",")
						.append((corner.loc.y + borderPadding) / resolution)
						.append("]");
			}
			result.append("]}");
		}
		result.append("]");
	}

	private static Integer findNearestRegionId(MapSettings settings, WorldGraph graph, Point point)
	{
		Center closest = graph.findClosestCenter(point, true);
		if (closest != null)
		{
			CenterEdit closestEdit = settings.edits.centerEdits.get(closest.index);
			if (closestEdit != null && closestEdit.regionId != null)
			{
				return closestEdit.regionId;
			}
			if (closest.region != null)
			{
				return closest.region.id;
			}
		}
		return settings.edits.centerEdits.values().stream()
				.filter(edit -> edit.regionId != null && edit.index >= 0 && edit.index < graph.centers.size())
				.min((left, right) -> Double.compare(
						graph.centers.get(left.index).loc.distanceTo(point),
						graph.centers.get(right.index).loc.distanceTo(point)))
				.map(edit -> edit.regionId)
				.orElse(null);
	}

	private static boolean restoreEditorHistory(EditorSession session, String action)
	{
		MapSettings target = session.historyTarget(action);
		if (target == null)
		{
			return false;
		}
		MapSettings current = session.settings;
		ensureEdits(current);
		ensureEdits(target);
		Set<Integer> changedCenters = new HashSet<>();
		Set<Integer> centerIds = new HashSet<>(current.edits.centerEdits.keySet());
		centerIds.addAll(target.edits.centerEdits.keySet());
		for (Integer centerId : centerIds)
		{
			if (!Objects.equals(current.edits.centerEdits.get(centerId), target.edits.centerEdits.get(centerId)))
			{
				changedCenters.add(centerId);
			}
		}

		Set<Integer> changedRegions = new HashSet<>(current.edits.regionEdits.keySet());
		changedRegions.addAll(target.edits.regionEdits.keySet());
		changedRegions.removeIf(regionId -> Objects.equals(current.edits.regionEdits.get(regionId), target.edits.regionEdits.get(regionId)));
		if (!changedRegions.isEmpty())
		{
			for (Center center : session.mapParts.graph.centers)
			{
				Integer currentRegion = getCenterRegionId(current, center);
				Integer targetRegion = getCenterRegionId(target, center);
				if (changedRegions.contains(currentRegion) || changedRegions.contains(targetRegion))
				{
					changedCenters.add(center.index);
				}
			}
		}
		if (current.drawRegionColors != target.drawRegionColors || current.drawRegionBoundaries != target.drawRegionBoundaries
				|| !Objects.equals(current.edits.regionBoundaryLines, target.edits.regionBoundaryLines)
				|| !Objects.equals(current.edits.roads, target.edits.roads) || !Objects.equals(current.edits.rivers, target.edits.rivers))
		{
			for (Center center : session.mapParts.graph.centers)
			{
				changedCenters.add(center.index);
			}
		}

		boolean textChanged = !Objects.equals(current.edits.text, target.edits.text);
		List<MapText> changedText = new ArrayList<>();
		if (textChanged)
		{
			current.edits.text.forEach(text -> changedText.add(text.deepCopy()));
			target.edits.text.forEach(text -> changedText.add(text.deepCopy()));
		}

		session.settings = target;
		try
		{
			MapCreator creator = new MapCreator();
			if (!changedCenters.isEmpty())
			{
				creator.incrementalUpdateForCentersAndEdges(target, session.mapParts, session.map, changedCenters, null, false);
			}
			if (textChanged && !changedText.isEmpty())
			{
				creator.incrementalUpdateText(target, session.mapParts, session.map, changedText);
			}
			session.completeHistory(action, current);
			return true;
		}
		catch (RuntimeException ex)
		{
			session.settings = current;
			throw ex;
		}
	}

	private static void ensureEdits(MapSettings settings)
	{
		if (settings.edits == null)
		{
			settings.edits = new MapEdits();
		}
		if (settings.edits.text == null)
		{
			settings.edits.text = new CopyOnWriteArrayList<>();
		}
		if (settings.edits.centerEdits == null)
		{
			settings.edits.centerEdits = new ConcurrentHashMap<>();
		}
		if (settings.edits.regionEdits == null)
		{
			settings.edits.regionEdits = new ConcurrentHashMap<>();
		}
		if (settings.edits.roads == null)
		{
			settings.edits.roads = new CopyOnWriteArrayList<>();
		}
		if (settings.edits.rivers == null)
		{
			settings.edits.rivers = new CopyOnWriteArrayList<>();
		}
		if (settings.edits.regionBoundaryLines == null)
		{
			settings.edits.regionBoundaryLines = new CopyOnWriteArrayList<>();
		}
	}

	private record IncrementalPreview(String base64, IntRectangle bounds)
	{
	}

	private record ExportFormat(String extension, String contentType)
	{
	}

	private record BoundaryQueueNode(Corner corner, double distance)
	{
	}

	private record SnappedBoundary(List<Edge> edges, List<Point> points, Integer sourceRegionId)
	{
	}

	private record BoundarySplit(Set<Center> centers, Integer createdRegionId)
	{
	}

	private record SnappedPath(List<Edge> edges, List<Point> points)
	{
	}

	private static class EditorSession
	{
		private static final int MAX_HISTORY = 50;
		MapSettings settings;
		final MapParts mapParts;
		final Image map;
		final Dimension maxDimensions;
		final ArrayDeque<MapSettings> undoHistory = new ArrayDeque<>();
		final ArrayDeque<MapSettings> redoHistory = new ArrayDeque<>();
		String activeHistoryGroup;

		EditorSession(MapSettings settings, MapParts mapParts, Image map, Dimension maxDimensions)
		{
			this.settings = settings;
			this.mapParts = mapParts;
			this.map = map;
			this.maxDimensions = maxDimensions;
		}

		MapSettings prepareHistory(JSONObject command)
		{
			String group = historyGroup(command);
			if (group != null && group.equals(activeHistoryGroup))
			{
				return null;
			}
			return settings.deepCopy();
		}

		void commitHistory(MapSettings before, JSONObject command)
		{
			if (before == null)
			{
				return;
			}
			String group = historyGroup(command);
			if (before.equals(settings))
			{
				return;
			}
			activeHistoryGroup = group;
			undoHistory.push(before);
			while (undoHistory.size() > MAX_HISTORY)
			{
				undoHistory.removeLast();
			}
			redoHistory.clear();
		}

		MapSettings historyTarget(String action)
		{
			ArrayDeque<MapSettings> source = "undo".equals(action) ? undoHistory : redoHistory;
			return source.isEmpty() ? null : source.peek().deepCopy();
		}

		void completeHistory(String action, MapSettings current)
		{
			ArrayDeque<MapSettings> source = "undo".equals(action) ? undoHistory : redoHistory;
			ArrayDeque<MapSettings> destination = "undo".equals(action) ? redoHistory : undoHistory;
			source.pop();
			destination.push(current.deepCopy());
			while (destination.size() > MAX_HISTORY)
			{
				destination.removeLast();
			}
			activeHistoryGroup = null;
		}

		void copyHistoryFrom(EditorSession source)
		{
			undoHistory.addAll(source.undoHistory);
			redoHistory.addAll(source.redoHistory);
			activeHistoryGroup = source.activeHistoryGroup;
		}

		boolean canUndo()
		{
			return !undoHistory.isEmpty();
		}

		boolean canRedo()
		{
			return !redoHistory.isEmpty();
		}

		private static String historyGroup(JSONObject command)
		{
			if (command == null || !(command.get("historyGroup") instanceof String))
			{
				return null;
			}
			String value = ((String) command.get("historyGroup")).trim();
			return value.isEmpty() ? null : value;
		}

		void close()
		{
			map.close();
			mapParts.closeImages();
		}
	}

	private static boolean isBrushLoggingEnabled()
	{
		return isServiceLoggingEnabled("BRUSH_LOGGING");
	}

	private static boolean isBordersLoggingEnabled()
	{
		return isServiceLoggingEnabled("BORDERS_LOGGING");
	}

	private static boolean isTextFieldLoggingEnabled()
	{
		return isServiceLoggingEnabled("TEXT_FIELD_LOGGING");
	}

	private static boolean isRegionPaintLoggingEnabled()
	{
		return isServiceLoggingEnabled("REGION_PAINT_LOGGING");
	}

	private static boolean isIconsLoggingEnabled()
	{
		return isServiceLoggingEnabled("ICONS_LOGGING");
	}

	private static boolean isForestsLoggingEnabled()
	{
		return isServiceLoggingEnabled("FORESTS_LOGGING");
	}

	private static boolean isRoadsLoggingEnabled()
	{
		return isServiceLoggingEnabled("ROADS_LOGGING");
	}

	private static boolean isRiversLoggingEnabled()
	{
		return isServiceLoggingEnabled("RIVERS_LOGGING");
	}

	private static boolean isServiceLoggingEnabled(String key)
	{
		return Boolean.TRUE.equals(SERVICE_LOGGING.get(key));
	}

	private static String normalizeOperationId(Object value)
	{
		String operationId = value instanceof String ? ((String) value).trim() : "";
		return operationId.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}") ? operationId : "untracked";
	}

	private static void loadServiceConfig()
	{
		SERVICE_LOGGING.clear();
		for (ToolLog tool : ToolLog.values())
		{
			SERVICE_LOGGING.put(tool.configKey, false);
		}
		String explicit = System.getenv("NORTANTIS_CONFIG_PATH");
		Path path = explicit == null || explicit.isBlank() ? findServiceConfigPath() : Paths.get(explicit);
		if (!Files.exists(path))
		{
			System.err.println("[nortantis-config] config not found path=" + path.toAbsolutePath());
			return;
		}
		try
		{
			JSONObject root = (JSONObject) new JSONParser().parse(Files.readString(path, StandardCharsets.UTF_8));
			JSONObject logging = root.get("logging") instanceof JSONObject ? (JSONObject) root.get("logging") : new JSONObject();
			for (ToolLog tool : ToolLog.values())
			{
				SERVICE_LOGGING.put(tool.configKey, Boolean.TRUE.equals(logging.get(tool.configKey)));
			}
		}
		catch (Exception ex)
		{
			System.err.println("[nortantis-config] invalid config path=" + path.toAbsolutePath() + " error=" + ex.getMessage());
		}
	}

	private static Path findServiceConfigPath()
	{
		Path local = Paths.get("config.json");
		if (Files.exists(local))
		{
			return local;
		}
		return Paths.get("services/nortantis-adapted/config.json");
	}

	private static long elapsedMs(long startedAt)
	{
		return Math.round((System.nanoTime() - startedAt) / 1_000_000.0);
	}

	private static void brushLog(String event, Object... pairs)
	{
		writeToolLog(BRUSH_LOG_PREFIX, event, pairs);
	}

	private static void borderLog(String event, Object... pairs)
	{
		writeToolLog(BORDERS_LOG_PREFIX, event, pairs);
	}

	private static void textLog(String event, Object... pairs)
	{
		writeToolLog(TEXT_LOG_PREFIX, event, pairs);
	}

	private static void regionPaintLog(String event, Object... pairs)
	{
		writeToolLog(REGION_PAINT_LOG_PREFIX, event, pairs);
	}

	private static void iconLog(String event, Object... pairs)
	{
		writeToolLog(ICONS_LOG_PREFIX, event, pairs);
	}

	private static void forestLog(String event, Object... pairs)
	{
		writeToolLog(FORESTS_LOG_PREFIX, event, pairs);
	}

	private static void roadLog(String event, Object... pairs)
	{
		writeToolLog(ROADS_LOG_PREFIX, event, pairs);
	}

	private static void riverLog(String event, Object... pairs)
	{
		writeToolLog(RIVERS_LOG_PREFIX, event, pairs);
	}

	private static void toolLog(ToolLog tool, String event, Object... pairs)
	{
		writeToolLog(tool.prefix, event, pairs);
	}

	private static void writeToolLog(String prefix, String event, Object... pairs)
	{
		StringBuilder message = new StringBuilder(prefix).append(" ").append(event);
		for (int index = 0; index + 1 < pairs.length; index += 2)
		{
			message.append(" ").append(pairs[index]).append("=").append(pairs[index + 1]);
		}
		System.err.println(message);
	}

	private static ToolLog toolLogForCommand(JSONObject command)
	{
		if (command == null || !(command.get("type") instanceof String))
		{
			return null;
		}
		return switch ((String) command.get("type"))
		{
			case "terrain.brush" -> ToolLog.BRUSH;
			case "region.boundary.draw", "region.boundary.erase" -> ToolLog.BORDERS;
			case "region.paint", "region.islands.lasso" -> ToolLog.REGION_PAINT;
			case "text.add", "text.update", "text.delete" -> ToolLog.TEXT;
			case "icon.center.set", "icon.center.clear", "relief.erase" -> ToolLog.ICONS;
			case "trees.center.set", "trees.center.clear" -> ToolLog.FORESTS;
			case "road.add", "road.draw", "road.erase" -> ToolLog.ROADS;
			case "river.draw", "river.erase" -> ToolLog.RIVERS;
			default -> null;
		};
	}

	private static boolean isToolLoggingEnabled(ToolLog tool)
	{
		return tool != null && isServiceLoggingEnabled(tool.configKey);
	}

	private enum ToolLog
	{
		BRUSH("BRUSH_LOGGING", BRUSH_LOG_PREFIX),
		BORDERS("BORDERS_LOGGING", BORDERS_LOG_PREFIX),
		REGION_PAINT("REGION_PAINT_LOGGING", REGION_PAINT_LOG_PREFIX),
		TEXT("TEXT_FIELD_LOGGING", TEXT_LOG_PREFIX),
		ICONS("ICONS_LOGGING", ICONS_LOG_PREFIX),
		FORESTS("FORESTS_LOGGING", FORESTS_LOG_PREFIX),
		ROADS("ROADS_LOGGING", ROADS_LOG_PREFIX),
		RIVERS("RIVERS_LOGGING", RIVERS_LOG_PREFIX),
		GENERATION("GENERATION_LOGGING", GENERATION_LOG_PREFIX),
		PROJECTS("PROJECTS_LOGGING", PROJECTS_LOG_PREFIX),
		SAVE_EXPORT("SAVE_EXPORT_LOGGING", SAVE_EXPORT_LOG_PREFIX),
		ASSETS("ASSETS_LOGGING", ASSETS_LOG_PREFIX);

		final String configKey;
		final String prefix;

		ToolLog(String configKey, String prefix)
		{
			this.configKey = configKey;
			this.prefix = prefix;
		}
	}

	private static class SseEmitter
	{
		private final OutputStream output;

		SseEmitter(OutputStream output)
		{
			this.output = output;
		}

		synchronized void send(String event, String data) throws IOException
		{
			output.write(("event: " + event + "\n").getBytes(StandardCharsets.UTF_8));
			output.write(("data: " + data + "\n\n").getBytes(StandardCharsets.UTF_8));
			output.flush();
		}
	}

	private static void emitGenerationPhase(SseEmitter emitter, String message)
	{
		String normalized = message == null ? "" : message.trim();
		String key = generationPhaseKey(normalized);
		if (key == null)
		{
			return;
		}
		try
		{
			emitter.send("phase", "{\"key\":\"" + key + "\",\"message\":\"" + escape(normalized) + "\"}");
		}
		catch (IOException ex)
		{
			throw new IllegalStateException("Generation progress stream closed", ex);
		}
	}

	private static String generationPhaseKey(String message)
	{
		if (message.startsWith("Creating the map")) return "creatingMap";
		if (message.startsWith("Using low memory")) return "lowMemory";
		if (message.startsWith("Generating the background image")) return "backgroundImage";
		if (message.startsWith("Creating the graph")) return "graph";
		if (message.startsWith("Adding icons")) return "icons";
		if (message.startsWith("Adding mountains and hills")) return "mountains";
		if (message.startsWith("Adding sand dunes")) return "dunes";
		if (message.startsWith("Adding trees")) return "trees";
		if (message.startsWith("Adding cities")) return "cities";
		if (message.startsWith("Adding land")) return "land";
		if (message.startsWith("Darkening land near shores")) return "shores";
		if (message.startsWith("Adding rivers")) return "rivers";
		if (message.startsWith("Drawing ocean")) return "ocean";
		if (message.startsWith("Adding waves") || message.startsWith("Adding shading to ocean")) return "waves";
		if (message.startsWith("Adding roads") || message.startsWith("Drawing roads")) return "roads";
		if (message.startsWith("Drawing all icons")) return "drawIcons";
		if (message.startsWith("Adding text")) return "text";
		if (message.startsWith("Adding border")) return "border";
		if (message.startsWith("Adding grunge")) return "grunge";
		if (message.startsWith("Starting job to create grunge")) return "grungeJob";
		if (message.startsWith("Starting job to create frayed edges")) return "frayedEdgesJob";
		if (message.startsWith("Adding frayed edges")) return "frayedEdges";
		if (message.startsWith("Done creating map")) return "done";
		return null;
	}

}
