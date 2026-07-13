package nortantis.rest;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import nortantis.ImageCache;
import nortantis.ImageAndMasks;
import nortantis.Background;
import nortantis.BorderPosition;
import nortantis.IconType;
import nortantis.IconDrawer;
import nortantis.LandShape;
import nortantis.MapCreator;
import nortantis.MapSettings;
import nortantis.MapText;
import nortantis.NameCreator;
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
import nortantis.editor.RegionEdit;
import nortantis.editor.Road;
import nortantis.editor.RoadPathNode;
import nortantis.geom.Dimension;
import nortantis.geom.Point;
import nortantis.graph.voronoi.Center;
import nortantis.graph.voronoi.Corner;
import nortantis.graph.voronoi.Edge;
import nortantis.platform.Image;
import nortantis.platform.ImageHelper;
import nortantis.platform.PlatformFactory;
import nortantis.platform.awt.AwtFactory;
import nortantis.swing.MapEdits;
import nortantis.swing.translation.Translation;
import nortantis.util.Assets;
import nortantis.util.GeometryHelper;
import nortantis.util.Logger;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.imgscalr.Scalr.Method;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.OutputStream;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

public class NortantisRestServer
{
	private static final Object EXPORT_STREAM_LOCK = new Object();
	private static final String BRUSH_LOG_PREFIX = "[nortantis-brush-service]";
	private static final String BORDERS_LOG_PREFIX = "[nortantis-borders-service]";
	private static final String REGION_PAINT_LOG_PREFIX = "[nortantis-region-paint-service]";
	private static final String TEXT_LOG_PREFIX = "[nortantis-text-service]";
	private static final String ICONS_LOG_PREFIX = "[nortantis-icons-service]";
	private static final String FORESTS_LOG_PREFIX = "[nortantis-forests-service]";
	private static final String ROADS_LOG_PREFIX = "[nortantis-roads-service]";
	private static final String GENERATION_LOG_PREFIX = "[nortantis-generation-service]";
	private static final String PROJECTS_LOG_PREFIX = "[nortantis-projects-service]";
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
		server.createContext("/api/editor/session/open", NortantisRestServer::editorSessionOpen);
		server.createContext("/api/editor/session/edit", NortantisRestServer::editProject);
		server.createContext("/api/editor/session/render", NortantisRestServer::editorSessionRender);
		server.createContext("/api/editor/session/project", NortantisRestServer::editorSessionProject);
		server.createContext("/api/editor/session/export", NortantisRestServer::editorSessionExport);
		server.createContext("/api/editor/session/generate-stream", NortantisRestServer::editorSessionGenerateStream);
		server.createContext("/api/editor/session/region-color", NortantisRestServer::regionColor);
		server.createContext("/api/editor/session/brush-selection", NortantisRestServer::brushSelection);
		server.createContext("/api/editor/session/text-pick", NortantisRestServer::textPick);
		server.createContext("/api/editor/assets/icons", NortantisRestServer::iconAssets);
		server.createContext("/api/editor/assets/icon-preview", NortantisRestServer::iconPreview);
		server.createContext("/api/projects/default", NortantisRestServer::defaultProject);
		server.createContext("/api/projects/metadata", NortantisRestServer::projectMetadata);
		server.createContext("/api/projects/export", NortantisRestServer::exportProject);
		server.createContext("/api/projects/export-stream", NortantisRestServer::exportProjectStream);
		server.createContext("/api/projects/edit", NortantisRestServer::editProject);
		server.createContext("/api/projects/region-color", NortantisRestServer::regionColor);
		server.createContext("/api/projects/text-pick", NortantisRestServer::textPick);
		server.createContext("/api/projects/brush-selection", NortantisRestServer::brushSelection);
		server.createContext("/api/assets/icons", NortantisRestServer::iconAssets);
		server.createContext("/api/assets/icon-preview", NortantisRestServer::iconPreview);
		server.setExecutor(Executors.newFixedThreadPool(Integer.parseInt(System.getenv().getOrDefault("NORTANTIS_REST_THREADS", "2"))));
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

	private static void editorSessionOpen(HttpExchange exchange) throws IOException
	{
		if (!exchange.getRequestMethod().equals("POST"))
		{
			send(exchange, 405, "application/json", "{\"ok\":false}".getBytes(StandardCharsets.UTF_8));
			return;
		}
		Path project = Files.createTempFile("nortantis-editor-open-", MapSettings.fileExtensionWithDot);
		long startedAt = System.nanoTime();
		String sessionId = "";
		try
		{
			JSONObject input = readJsonBody(exchange);
			sessionId = (String) input.get("sessionId");
			String projectBase64 = (String) input.get("projectBase64");
			if (sessionId == null || sessionId.isBlank() || projectBase64 == null || projectBase64.isBlank())
			{
				throw new IllegalArgumentException("Missing session");
			}
			Files.write(project, Base64.getDecoder().decode(projectBase64));
			if (isToolLoggingEnabled(ToolLog.PROJECTS))
			{
				toolLog(ToolLog.PROJECTS, "session-open:start", "sessionId", sessionId, "projectBytes", Files.size(project));
			}
			MapSettings settings = new MapSettings(project.toString());
			Dimension maxDimensions = readMaxDimensions(input);
			MapParts mapParts = new MapParts();
			Image image = new MapCreator().createMap(settings, maxDimensions, mapParts);
			storeEditorSession(sessionId, settings, mapParts, image, maxDimensions);
			EditorSession session = EDITOR_SESSIONS.get(sessionId);
			String result = "{\"ok\":true,\"previewBase64\":\"" + imageToBase64(image, ".png") + "\",\"metadata\":" + sessionMetadataJson(session) + "}";
			if (isToolLoggingEnabled(ToolLog.PROJECTS))
			{
				toolLog(ToolLog.PROJECTS, "session-open:success", "sessionId", sessionId, "durationMs", elapsedMs(startedAt), "mapWidth", image.getWidth(), "mapHeight", image.getHeight());
			}
			send(exchange, 200, "application/json", result.getBytes(StandardCharsets.UTF_8));
		}
		catch (Exception ex)
		{
			if (isToolLoggingEnabled(ToolLog.PROJECTS))
			{
				toolLog(ToolLog.PROJECTS, "session-open:error", "sessionId", sessionId, "durationMs", elapsedMs(startedAt), "error", ex.toString());
			}
			send(exchange, 500, "application/json", ("{\"ok\":false,\"error\":\"" + escape(ex.getMessage()) + "\"}").getBytes(StandardCharsets.UTF_8));
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
		try
		{
			JSONObject input = readJsonBody(exchange);
			String sessionId = (String) input.get("sessionId");
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
			if (isToolLoggingEnabled(ToolLog.PROJECTS))
			{
				toolLog(ToolLog.PROJECTS, "session-project:success", "sessionId", sessionId, "durationMs", elapsedMs(startedAt), "projectBytes", Files.size(project));
			}
			send(exchange, 200, "application/json", result.getBytes(StandardCharsets.UTF_8));
		}
		catch (Exception ex)
		{
			if (isToolLoggingEnabled(ToolLog.PROJECTS))
			{
				toolLog(ToolLog.PROJECTS, "session-project:error", "durationMs", elapsedMs(startedAt), "error", ex.toString());
			}
			send(exchange, 500, "application/json", ("{\"ok\":false,\"error\":\"" + escape(ex.getMessage()) + "\"}").getBytes(StandardCharsets.UTF_8));
		}
		finally
		{
			Files.deleteIfExists(project);
		}
	}

	private static void editorSessionRender(HttpExchange exchange) throws IOException
	{
		if (!exchange.getRequestMethod().equals("POST"))
		{
			send(exchange, 405, "application/json", "{\"ok\":false}".getBytes(StandardCharsets.UTF_8));
			return;
		}
		try
		{
			JSONObject input = readJsonBody(exchange);
			String sessionId = (String) input.get("sessionId");
			EditorSession session = sessionId == null ? null : EDITOR_SESSIONS.get(sessionId);
			if (session == null)
			{
				throw new IllegalArgumentException("Missing editor session");
			}
			String result = "{\"ok\":true,\"previewBase64\":\"" + imageToBase64(session.map, ".png") + "\",\"metadata\":" + sessionMetadataJson(session) + "}";
			send(exchange, 200, "application/json", result.getBytes(StandardCharsets.UTF_8));
		}
		catch (Exception ex)
		{
			send(exchange, 500, "application/json", ("{\"ok\":false,\"error\":\"" + escape(ex.getMessage()) + "\"}").getBytes(StandardCharsets.UTF_8));
		}
	}

	private static void editorSessionExport(HttpExchange exchange) throws IOException
	{
		if (!exchange.getRequestMethod().equals("POST"))
		{
			send(exchange, 405, "application/json", "{\"ok\":false}".getBytes(StandardCharsets.UTF_8));
			return;
		}
		long startedAt = System.nanoTime();
		try
		{
			JSONObject input = readJsonBody(exchange);
			String sessionId = (String) input.get("sessionId");
			String format = input.get("format") instanceof String ? ((String) input.get("format")).trim().toLowerCase() : "jpg";
			EditorSession session = sessionId == null ? null : EDITOR_SESSIONS.get(sessionId);
			if (session == null)
			{
				throw new IllegalArgumentException("Missing editor session");
			}
			String extension = "png".equals(format) ? ".png" : ".jpg";
			String result = "{\"ok\":true,\"imageBase64\":\"" + imageToBase64(session.map, extension) + "\",\"format\":\"" + ("png".equals(format) ? "png" : "jpg") + "\"}";
			if (isToolLoggingEnabled(ToolLog.PROJECTS))
			{
				toolLog(ToolLog.PROJECTS, "session-export:success", "sessionId", sessionId, "format", format, "durationMs", elapsedMs(startedAt));
			}
			send(exchange, 200, "application/json", result.getBytes(StandardCharsets.UTF_8));
		}
		catch (Exception ex)
		{
			if (isToolLoggingEnabled(ToolLog.PROJECTS))
			{
				toolLog(ToolLog.PROJECTS, "session-export:error", "durationMs", elapsedMs(startedAt), "error", ex.toString());
			}
			send(exchange, 500, "application/json", ("{\"ok\":false,\"error\":\"" + escape(ex.getMessage()) + "\"}").getBytes(StandardCharsets.UTF_8));
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

	private static String editorHtml()
	{
		return """
<!doctype html>
<html lang="ru">
<head>
	<meta charset="utf-8">
	<meta name="viewport" content="width=device-width, initial-scale=1">
	<title>Nortantis Web Editor</title>
	<style>
		:root{color-scheme:dark;--gold:#d88b35;--wine:#6f241f;--text:#faf9d9;--muted:#e4b279;--line:rgba(216,139,53,.36);--panel:rgba(18,7,5,.76);--panel-strong:rgba(6,0,0,.88);--card:rgba(0,0,0,.24);--field:rgba(255,238,186,.08);--danger:#ffb0a8}
		*{box-sizing:border-box}body{margin:0;height:100vh;background:radial-gradient(circle at top left,rgba(216,139,53,.16),transparent 34%),linear-gradient(135deg,#160805,#050202);color:var(--text);font:14px/1.35 system-ui,-apple-system,Segoe UI,sans-serif;overflow:hidden}
		.editor{height:100vh;min-width:0;display:grid;grid-template-columns:minmax(0,1fr) 42px minmax(330px,24vw);grid-template-rows:minmax(0,1fr);overflow:hidden}
		.stage{position:relative;min-width:0;min-height:0;overflow:hidden;background:rgba(0,0,0,.18)}
		.canvas{position:absolute;inset:0;overflow:hidden;cursor:crosshair;touch-action:none;outline:none}
		.canvas.is-panning{cursor:grabbing}
		.map{position:absolute;left:50%;top:50%;max-width:none;transform-origin:0 0;image-rendering:auto;user-select:none;-webkit-user-drag:none;border:1px solid rgba(216,139,53,.18);box-shadow:0 18px 60px rgba(0,0,0,.45)}
		.loading{position:absolute;inset:0;display:grid;place-items:center;background:radial-gradient(circle at center,rgba(18,7,5,.88),rgba(5,2,2,.94));z-index:3}
		.loading-card{width:min(420px,90vw);padding:20px;border:1px solid var(--line);border-radius:16px;background:linear-gradient(180deg,var(--panel),var(--panel-strong));box-shadow:0 18px 48px rgba(0,0,0,.46)}
		.bar{height:8px;border:1px solid var(--line);border-radius:999px;background:#100d0a;overflow:hidden}.bar span{display:block;height:100%;width:42%;background:linear-gradient(90deg,var(--gold),#f4c469);animation:pulse 1.1s infinite alternate}
		@keyframes pulse{from{transform:translateX(-70%)}to{transform:translateX(180%)}}
		.tool-rail{position:relative;z-index:1;display:flex;flex-direction:column;align-items:center;justify-content:flex-start;gap:12px;padding:10px 5px;border-left:1px solid var(--line);border-right:1px solid var(--line);background:rgba(0,0,0,.18)}
		.rail-button{width:32px;height:32px;min-height:32px;padding:0;border:1px solid rgba(216,139,53,.28);border-radius:12px;background:rgba(0,0,0,.18);color:var(--text);font-weight:900;display:grid;place-items:center}
		.rail-button:hover,.rail-button.active{border-color:rgba(216,139,53,.72);background:rgba(216,139,53,.16);color:var(--gold)}
		.panel{min-height:0;min-width:0;background:linear-gradient(180deg,var(--panel),var(--panel-strong));display:flex;flex-direction:column}
		.head{padding:10px;border-bottom:1px solid var(--line);display:flex;gap:8px;align-items:center;justify-content:space-between}
		.head strong{display:block;max-width:100%;font-size:12px;line-height:1.1;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.status{color:var(--muted);font-size:10px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
		.tools{padding:12px;display:grid;gap:14px;overflow:auto}
		.group{display:grid;gap:12px;padding:0 0 16px;margin:0 0 16px;border-bottom:1px solid rgba(216,139,53,.16)}.group:last-child{border-bottom:0;margin-bottom:0;padding-bottom:0}.group h2{font-size:16px;color:var(--text);margin:0}
		.panel-view{display:none}.panel-view.active{display:grid}
		.row{display:flex;gap:8px;align-items:center;flex-wrap:wrap}
		button{display:inline-flex;min-height:42px;align-items:center;justify-content:center;gap:6px;border:1px solid rgba(216,139,53,.28);border-radius:12px;padding:9px 10px;background:rgba(0,0,0,.18);color:var(--text);font:inherit;font-weight:800;cursor:pointer;transition:border-color .16s ease,background .16s ease,color .16s ease,transform .16s ease}
		button:hover:not(:disabled),button.active{border-color:rgba(216,139,53,.72);background:rgba(216,139,53,.16);color:var(--gold)}button:active:not(:disabled){transform:translateY(1px)}button.danger{color:var(--danger)}
		button.land.active{border-color:rgba(197,152,79,.9);background:linear-gradient(180deg,rgba(115,89,43,.34),rgba(76,98,54,.22));color:#f2d28d}
		button.water.active{border-color:rgba(96,169,201,.85);background:linear-gradient(180deg,rgba(45,103,132,.34),rgba(31,62,96,.24));color:#9ed8f1}
		label.field{display:grid;gap:6px;color:var(--muted);font-size:12px;font-weight:700}
		input[type=range]{width:100%;accent-color:var(--gold)}.hint{color:var(--muted);font-size:12px;margin:0}.button-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:8px}.full-width{grid-column:1/-1;width:100%}
		@media(max-width:900px){.editor{grid-template-columns:minmax(0,1fr) 42px minmax(280px,78vw)}}
	</style>
</head>
<body>
	<div class="editor">
		<main class="stage">
			<div id="canvas" class="canvas"><img id="map" class="map" alt=""></div>
			<div id="loading" class="loading"><div class="loading-card"><strong>Загрузка проекта Nortantis</strong><p class="hint" id="loadingText">Ожидание данных от приложения...</p><div class="bar"><span></span></div></div></div>
		</main>
		<nav class="tool-rail" aria-label="Инструменты">
			<button class="rail-button active" data-panel-button="terrain" aria-label="Суша и море">◐</button>
			<button class="rail-button" data-panel-button="project" aria-label="Проект">▣</button>
			<button class="rail-button" data-panel-button="info" aria-label="О сервисе">i</button>
		</nav>
		<aside class="panel">
			<div class="head"><div><strong id="title">Nortantis</strong><div id="status" class="status">Ожидание</div></div></div>
			<div class="tools">
				<section class="group panel-view active" data-panel="terrain">
					<h2>Суша и море</h2>
					<div class="button-grid"><button id="land" class="land active">Суша</button><button id="water" class="water">Море</button></div>
					<label class="field">Размер кисти: <span id="radiusLabel">48</span>
					<input id="radius" type="range" min="8" max="140" value="48">
					</label>
					<p class="hint">ЛКМ рисует. СКМ или пробел + движение мыши перемещает холст. Колесо масштабирует.</p>
				</section>
				<section class="group panel-view" data-panel="project">
					<h2>Проект</h2>
					<div class="button-grid"><button id="save">Сохранить</button><button id="export">Экспорт JPG</button></div>
					<p class="hint">Сохранение и экспорт идут через основное приложение, чтобы квота хранилища оставалась источником истины.</p>
				</section>
				<section class="group panel-view" data-panel="info">
					<h2>О сервисе</h2>
					<p class="hint">Этот редактор выполняется внутри Nortantis-сервиса. Основное приложение передаёт проект только при открытии, сохранении и экспорте.</p>
				</section>
			</div>
		</aside>
	</div>
	<script>
		const state={sessionId:null,metadata:null,mode:'land',radius:48,scale:1,panX:0,panY:0,drawing:false,panning:false,space:false,pending:false,queued:null,dirty:false,hasFit:false};
		const map=document.getElementById('map'),canvas=document.getElementById('canvas'),loading=document.getElementById('loading'),loadingText=document.getElementById('loadingText'),status=document.getElementById('status'),landButton=document.getElementById('land'),waterButton=document.getElementById('water');
		const setStatus=(text)=>{status.textContent=text};
		function fit(){if(!map.naturalWidth)return;const r=canvas.getBoundingClientRect();state.scale=Math.min(r.width/map.naturalWidth,r.height/map.naturalHeight)*0.92;state.panX=-map.naturalWidth*state.scale/2;state.panY=-map.naturalHeight*state.scale/2;state.hasFit=true;applyTransform()}
		function applyTransform(){map.style.transform=`translate(${state.panX}px,${state.panY}px) scale(${state.scale})`}
		function setPreview(base64,resetView){map.onload=()=>{if(resetView||!state.hasFit)fit();else applyTransform()};map.src='data:image/png;base64,'+base64}
		function mapPoint(event){const r=map.getBoundingClientRect();return{x:(event.clientX-r.left)/r.width*state.metadata.width,y:(event.clientY-r.top)/r.height*state.metadata.height}}
		async function postJson(url,body){const res=await fetch(url,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)});const json=await res.json();if(!json.ok)throw new Error(json.error||'request failed');return json}
		async function openProject(data){state.sessionId=data.projectId;state.hasFit=false;document.getElementById('title').textContent=data.name||'Nortantis';loading.style.display='grid';loadingText.textContent='Создание редакторской сессии...';setStatus('Открытие');const json=await postJson('/api/editor/session/open',{sessionId:state.sessionId,projectBase64:data.projectBase64,previewMaxDimensionPixels:data.previewMaxDimensionPixels||1536});state.metadata=json.metadata;setPreview(json.previewBase64,true);loading.style.display='none';setStatus('Готово')}
		async function editAt(point){const command={type:'terrain.brush',mode:state.mode,x:point.x,y:point.y,radius:state.radius};if(state.pending){state.queued=command;return}state.pending=true;try{const json=await postJson('/api/editor/session/edit',{sessionId:state.sessionId,command,returnPreview:true,omitProjectBytes:true});if(json.metadata)state.metadata=json.metadata;if(json.previewBase64)setPreview(json.previewBase64,false);state.dirty=true;setStatus('Есть несохранённые изменения')}catch(e){setStatus('Ошибка: '+e.message)}finally{state.pending=false;if(state.queued){const next=state.queued;state.queued=null;await editAt({x:next.x,y:next.y})}}}
		async function currentProject(){const json=await postJson('/api/editor/session/project',{sessionId:state.sessionId});return json.projectBase64}
		landButton.onclick=()=>{state.mode='land';landButton.classList.add('active');waterButton.classList.remove('active')};
		waterButton.onclick=()=>{state.mode='water';waterButton.classList.add('active');landButton.classList.remove('active')};
		document.getElementById('radius').oninput=(e)=>{state.radius=Number(e.target.value);document.getElementById('radiusLabel').textContent=String(state.radius)};
		document.getElementById('save').onclick=async()=>{setStatus('Сохранение...');const projectBase64=await currentProject();parent.postMessage({type:'nortantis:save',projectId:state.sessionId,projectBase64},'*')};
		document.getElementById('export').onclick=async()=>{setStatus('Экспорт...');const projectBase64=await currentProject();parent.postMessage({type:'nortantis:export',projectId:state.sessionId,projectBase64,format:'jpg'},'*')};
		document.querySelectorAll('[data-panel-button]').forEach(button=>button.onclick=()=>{const key=button.dataset.panelButton;document.querySelectorAll('[data-panel-button]').forEach(item=>item.classList.toggle('active',item===button));document.querySelectorAll('[data-panel]').forEach(panel=>panel.classList.toggle('active',panel.dataset.panel===key))});
		canvas.addEventListener('pointerdown',e=>{if(!state.metadata)return;canvas.setPointerCapture(e.pointerId);if(e.button===1||state.space){state.panning=true;canvas.classList.add('is-panning');state.lastX=e.clientX;state.lastY=e.clientY;return}if(e.button===0){state.drawing=true;editAt(mapPoint(e))}});
		canvas.addEventListener('pointermove',e=>{if(state.panning){state.panX+=e.clientX-state.lastX;state.panY+=e.clientY-state.lastY;state.lastX=e.clientX;state.lastY=e.clientY;applyTransform();return}if(state.drawing)editAt(mapPoint(e))});
		canvas.addEventListener('pointerup',()=>{state.drawing=false;state.panning=false;canvas.classList.remove('is-panning')});
		canvas.addEventListener('wheel',e=>{e.preventDefault();const before=mapPoint(e);state.scale*=e.deltaY<0?1.1:0.9;applyTransform();const after=mapPoint(e);const r=map.getBoundingClientRect();state.panX+=(after.x-before.x)/state.metadata.width*r.width;state.panY+=(after.y-before.y)/state.metadata.height*r.height;applyTransform()},{passive:false});
		window.addEventListener('keydown',e=>{if(e.code==='Space')state.space=true;const step=e.shiftKey?72:32;const code=e.code.toLowerCase(),key=(e.key||'').toLowerCase();if(code==='arrowup'||code==='keyw'||key==='w'||key==='ц'){state.panY+=step;applyTransform()}if(code==='arrowdown'||code==='keys'||key==='s'||key==='ы'){state.panY-=step;applyTransform()}if(code==='arrowleft'||code==='keya'||key==='a'||key==='ф'){state.panX+=step;applyTransform()}if(code==='arrowright'||code==='keyd'||key==='d'||key==='в'){state.panX-=step;applyTransform()}});
		window.addEventListener('keyup',e=>{if(e.code==='Space')state.space=false});
		window.addEventListener('resize',()=>{if(state.hasFit)applyTransform();else fit()});
		window.addEventListener('message',e=>{if(e.data?.type==='nortantis:open')openProject(e.data)});
		parent.postMessage({type:'nortantis:ready'},'*');
	</script>
</body>
</html>
		""";
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
		settings.generatedWidth = size;
		settings.generatedHeight = size;
		settings.worldSize = worldSizeForMapSize(size);
		settings.regionCount = SettingsGenerator.maxGeneratedRegionCount(settings.worldSize);
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
		Path image = Files.createTempFile("nortantis-export-", exportFormat.extension);
		try
		{
			Files.write(project, exchange.getRequestBody().readAllBytes());
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
			send(exchange, 200, exportFormat.contentType, Files.readAllBytes(image));
		}
		catch (Exception ex)
		{
			send(exchange, 500, "application/json", ("{\"ok\":false,\"error\":\"" + escape(ex.getMessage()) + "\"}").getBytes());
		}
		finally
		{
			NameCreator.forcedTitle.remove();
			Files.deleteIfExists(project);
			Files.deleteIfExists(image);
		}
	}

	private static void projectMetadata(HttpExchange exchange) throws IOException
	{
		if (!exchange.getRequestMethod().equals("POST"))
		{
			send(exchange, 405, "application/json", "{\"ok\":false}".getBytes());
			return;
		}
		Path project = Files.createTempFile("nortantis-project-", MapSettings.fileExtensionWithDot);
		try
		{
			Files.write(project, exchange.getRequestBody().readAllBytes());
			MapSettings settings = new MapSettings(project.toString());
			String sessionId = readSessionId(exchange);
			EditorSession session = sessionId == null ? null : EDITOR_SESSIONS.get(sessionId);
			String body = session != null ? metadataJson(session) : metadataJson(settings);
			send(exchange, 200, "application/json", body.getBytes(StandardCharsets.UTF_8));
		}
		catch (Exception ex)
		{
			send(exchange, 500, "application/json", ("{\"ok\":false,\"error\":\"" + escape(ex.getMessage()) + "\"}").getBytes());
		}
		finally
		{
			Files.deleteIfExists(project);
		}
	}

	private static void exportProjectStream(HttpExchange exchange) throws IOException
	{
		if (!exchange.getRequestMethod().equals("POST"))
		{
			send(exchange, 405, "application/json", "{\"ok\":false}".getBytes());
			return;
		}
		exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
		exchange.getResponseHeaders().set("Cache-Control", "no-store");
		exchange.sendResponseHeaders(200, 0);
		try (OutputStream output = exchange.getResponseBody())
		{
			SseEmitter emitter = new SseEmitter(output);
			synchronized (EXPORT_STREAM_LOCK)
			{
				Path project = Files.createTempFile("nortantis-project-", MapSettings.fileExtensionWithDot);
				Path image = Files.createTempFile("nortantis-export-", ".png");
				Dimension maxDimensions = readMaxDimensions(exchange);
				String sessionId = readSessionId(exchange);
				PrintStream originalOut = System.out;
				PrintStream forwardingOut = new PrintStream(new PhaseForwardingOutputStream(originalOut, emitter), true, StandardCharsets.UTF_8);
				try
				{
					Files.write(project, exchange.getRequestBody().readAllBytes());
					System.setOut(forwardingOut);
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
					emitter.send("image", Base64.getEncoder().encodeToString(Files.readAllBytes(image)));
					emitter.send("done", "{}");
				}
				catch (Exception ex)
				{
					emitter.send("error", "{\"error\":\"" + escape(ex.getMessage()) + "\"}");
				}
				finally
				{
					NameCreator.forcedTitle.remove();
					System.setOut(originalOut);
					forwardingOut.flush();
					Files.deleteIfExists(project);
					Files.deleteIfExists(image);
				}
			}
		}
	}

	private static void editProject(HttpExchange exchange) throws IOException
	{
		if (!exchange.getRequestMethod().equals("POST"))
		{
			send(exchange, 405, "application/json", "{\"ok\":false}".getBytes());
			return;
		}
		Path project = Files.createTempFile("nortantis-edit-", MapSettings.fileExtensionWithDot);
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
			EditorSession editSession = null;
			if (sessionId != null && !sessionId.isBlank() && command != null && isIncrementalCommand(command))
			{
				editSession = existingSession != null ? existingSession : getOrCreateEditorSession(sessionId, project, readMaxDimensions(input));
				if ("region.paint".equals(command.get("type")) && !editSession.settings.drawRegionColors)
				{
					editSession = applyFirstRegionPaint(sessionId, command, editSession);
					settings = editSession.settings;
					previewBase64 = readReturnPreview(input) ? imageToBase64(editSession.map, ".png") : null;
				}
				else
				{
					previewBase64 = applyIncrementalEdit(settings, command, editSession, readReturnPreview(input));
				}
			}
			else
			{
				applyEditCommand(settings, command);
			}
			settings.writeToFile(project.toString());
			String result = "{\"ok\":true"
					+ (omitProjectBytes ? "" : ",\"projectBase64\":\"" + Base64.getEncoder().encodeToString(Files.readAllBytes(project)) + "\"")
					+ (previewBase64 == null ? "" : ",\"previewBase64\":\"" + previewBase64 + "\"")
					+ (editSession == null ? "" : ",\"metadata\":" + sessionMetadataJson(editSession)) + "}";
			if (isToolLoggingEnabled(commandLog))
			{
				toolLog(commandLog, "edit:success", "durationMs", elapsedMs(startedAt), "resultBase64Length", result.length(), "hasPreview", previewBase64 != null);
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
			Files.deleteIfExists(project);
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

	private static String metadataJson(MapSettings settings)
	{
		return "{\"ok\":true,\"width\":" + settings.generatedWidth + ",\"height\":" + settings.generatedHeight + ",\"resolution\":" + settings.resolution
				+ ",\"borderPadding\":" + borderPadding(settings) + ",\"capabilities\":{\"text\":true,\"landWaterBrush\":true,\"jpgExport\":true,\"icons\":true,\"roads\":true,\"regions\":true}}";
	}

	private static String metadataJson(EditorSession session)
	{
		return "{\"ok\":true," + sessionMetadataFieldsJson(session) + "}";
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
				+ ",\"capabilities\":{\"text\":true,\"landWaterBrush\":true,\"jpgExport\":true,\"icons\":true,\"roads\":true,\"regions\":true}";
	}

	private static String iconAssetsJson(MapSettings settings)
	{
		StringBuilder result = new StringBuilder("{\"ok\":true,\"artPack\":\"").append(escape(settings.artPack)).append("\",\"types\":[");
		boolean firstType = true;
		ImageCache cache = ImageCache.getInstance(settings.artPack, settings.customImagesPath);
		for (IconType type : IconType.values())
		{
			if (type != IconType.mountains && type != IconType.sand && type != IconType.trees)
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
		if ("icon.center.set".equals(type) || "icon.center.clear".equals(type) || "trees.center.set".equals(type) || "trees.center.clear".equals(type) || "road.add".equals(type) || "region.paint".equals(type) || "region.boundary.draw".equals(type)
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
		return "terrain.brush".equals(type) || "text.add".equals(type) || "text.update".equals(type) || "text.delete".equals(type) || "icon.center.set".equals(type) || "icon.center.clear".equals(type) || "trees.center.set".equals(type) || "trees.center.clear".equals(type) || "road.add".equals(type) || "region.paint".equals(type)
				|| "region.boundary.draw".equals(type) || "region.boundary.erase".equals(type);
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

	private static String applyIncrementalEdit(MapSettings persistedSettings, JSONObject command, EditorSession session, boolean returnPreview) throws IOException
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
				if (returnPreview && !changedText.isEmpty())
				{
					new MapCreator().incrementalUpdateText(session.settings, session.mapParts, session.map, changedText);
				}
				if (isTextFieldLoggingEnabled())
				{
					textLog("incremental:redraw", "durationMs", elapsedMs(startedAt), "changedCount", changedIds.size(), "textBounds", changedText.size(), "command", command);
				}
				return returnPreview ? imageToBase64(session.map, ".png") : null;
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
			new MapCreator().incrementalUpdateForCentersAndEdges(session.settings, session.mapParts, session.map, changedIds, null, false);
			String previewBase64 = imageToBase64(session.map, ".png");
			if (isBrushLoggingEnabled())
			{
				brushLog("incremental:success", "durationMs", elapsedMs(startedAt), "redrawAndEncodeMs", elapsedMs(redrawStartedAt), "selectedCount", changedIds.size(), "mapWidth", session.map.getWidth(),
						"mapHeight", session.map.getHeight());
			}
			return previewBase64;
		}
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
		if ("road.add".equals(type))
		{
			JSONArray points = (JSONArray) command.get("points");
			if (points == null || points.size() < 2)
			{
				throw new IllegalArgumentException("Road requires at least two points");
			}
			List<Point> path = new ArrayList<>();
			Set<Integer> changed = new HashSet<>();
			double resolution = settings.resolution == 0.0 ? 1.0 : settings.resolution;
			for (Object pointValue : points)
			{
				if (!(pointValue instanceof JSONObject))
				{
					continue;
				}
				Point pointRi = readGraphPointRi(settings, (JSONObject) pointValue, sessionCoordinates, sessionBorderPadding);
				path.add(pointRi);
				Center center = graph.findClosestCenter(pointRi.mult(resolution), true);
				if (center != null)
				{
					changed.add(center.index);
				}
			}
			settings.drawRoads = true;
			settings.edits.roads.add(Road.fromLocations(path));
			if (isRoadsLoggingEnabled())
			{
				roadLog("path:added", "pointCount", path.size(), "changedCount", changed.size(), "roadCount", settings.edits.roads.size());
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
			List<Point> lineRi = readLineRiPoints(settings, command, sessionCoordinates, sessionBorderPadding);
			double radius = ((Number) command.get("radius")).doubleValue() * (settings.resolution == 0.0 ? 1.0 : settings.resolution);
			Set<Edge> cutEdges = findEdgesNearLine(graph, line, radius);
			Set<Integer> changed = new HashSet<>();
			if (isBordersLoggingEnabled())
			{
				borderLog("boundary:line", "type", type, "linePoints", line.size(), "first", line.get(0), "last", line.get(line.size() - 1), "radius", radius, "cutEdges", cutEdges.size(),
						"sessionCoordinates", sessionCoordinates, "sessionBorderPadding", sessionBorderPadding);
			}
			if ("region.boundary.draw".equals(type))
			{
				double selectionRadius = Math.max(radius * 1.75, 42.0);
				Set<Center> centersToRedraw = centersNearBoundaryLine(settings, graph, line, selectionRadius);
				if (isBordersLoggingEnabled())
				{
					borderLog("boundary:explicit-draw", "pathPoints", lineRi.size(), "selectionRadius", selectionRadius, "redrawCenters", centersToRedraw.size());
				}
				settings.edits.regionBoundaryLines.add(Road.fromLocations(lineRi));
				addCentersAndNeighbors(changed, centersToRedraw);
				if (isBordersLoggingEnabled())
				{
					borderLog("boundary:draw-success", "durationMs", elapsedMs(borderStartedAt), "changedCount", changed.size(), "lineCount", settings.edits.regionBoundaryLines.size());
				}
				return changed;
			}
			int removedLines = 0;
			List<Road> removed = new ArrayList<>();
			for (Road boundaryLine : settings.edits.regionBoundaryLines)
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
				if (linesAreClose(boundaryGraphLine, line, radius))
				{
					removed.add(boundaryLine);
					addCentersAndNeighbors(changed, centersNearBoundaryLine(settings, graph, boundaryGraphLine, Math.max(radius * 1.75, 42.0)));
				}
			}
			for (Road boundaryLine : removed)
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

	private static Integer findNeighborRegionId(MapSettings settings, WorldGraph graph, Center center, Integer currentRegionId)
	{
		for (Center neighbor : center.neighbors)
		{
			Integer neighborRegionId = getCenterRegionId(settings, neighbor);
			if (neighborRegionId != null && !neighborRegionId.equals(currentRegionId))
			{
				return neighborRegionId;
			}
		}
		return settings.edits.centerEdits.values().stream()
				.filter(edit -> edit.regionId != null && !edit.regionId.equals(currentRegionId) && edit.index >= 0 && edit.index < graph.centers.size())
				.min((left, right) -> Double.compare(graph.centers.get(left.index).loc.distanceTo(center.loc), graph.centers.get(right.index).loc.distanceTo(center.loc)))
				.map(edit -> edit.regionId)
				.orElse(null);
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

	private static List<Point> readLineRiPoints(MapSettings settings, JSONObject command, boolean sessionCoordinates, Integer sessionBorderPadding)
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
				result.add(readGraphPointRi(settings, (JSONObject) value, sessionCoordinates, sessionBorderPadding));
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

	private static Set<Edge> findEdgesNearLine(WorldGraph graph, List<Point> line, double radius)
	{
		Set<Edge> crossing = new HashSet<>();
		Set<Edge> nearby = new HashSet<>();
		double fallbackRadius = Math.max(2.0, Math.min(radius * 0.25, 8.0));
		for (Edge edge : graph.edges)
		{
			if (edge.d0 == null || edge.d1 == null || edge.v0 == null || edge.v1 == null)
			{
				continue;
			}
			for (int index = 0; index < line.size() - 1; index++)
			{
				Point start = line.get(index);
				Point end = line.get(index + 1);
				if (linesIntersect(edge.v0.loc, edge.v1.loc, start, end))
				{
					crossing.add(edge);
					break;
				}
				if (segmentsClose(edge.v0.loc, edge.v1.loc, start, end, fallbackRadius))
				{
					nearby.add(edge);
					break;
				}
			}
		}
		return crossing.isEmpty() ? nearby : crossing;
	}

	private static Set<Center> centersOnOneSideOfBoundaryLine(MapSettings settings, Set<Edge> cutEdges, List<Point> line)
	{
		Set<Center> result = new HashSet<>();
		for (Edge edge : cutEdges)
		{
			if (edge.d0 == null || edge.d1 == null)
			{
				continue;
			}
			Integer leftRegion = getCenterRegionId(settings, edge.d0);
			Integer rightRegion = getCenterRegionId(settings, edge.d1);
			if (leftRegion == null || !leftRegion.equals(rightRegion))
			{
				continue;
			}
			double side0 = signedDistanceToNearestLineSegment(edge.d0.loc, line);
			double side1 = signedDistanceToNearestLineSegment(edge.d1.loc, line);
			double distance0 = distanceToLine(edge.d0.loc, line);
			double distance1 = distanceToLine(edge.d1.loc, line);
			if (Math.signum(side0) == Math.signum(side1))
			{
				result.add(distance0 <= distance1 ? edge.d0 : edge.d1);
			}
			else
			{
				result.add(side0 >= side1 ? edge.d0 : edge.d1);
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
		for (Road boundaryLine : settings.edits.regionBoundaryLines)
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
			Set<Edge> lineEdges = findBarrierEdgesNearLine(settings, graph, graphLine, regionId, radius);
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

	private static List<Set<Center>> findSplitSides(MapSettings settings, WorldGraph graph, Set<Edge> cutEdges)
	{
		List<Set<Center>> result = new ArrayList<>();
		Set<Center> visited = new HashSet<>();
		for (Edge edge : cutEdges)
		{
			if (edge.d0 == null || edge.d1 == null)
			{
				continue;
			}
			Integer regionId = getCenterRegionId(settings, edge.d0);
			if (regionId == null || !regionId.equals(getCenterRegionId(settings, edge.d1)))
			{
				continue;
			}
			Set<Center> left = collectRegionComponent(settings, edge.d0, regionId, cutEdges);
			Set<Center> right = collectRegionComponent(settings, edge.d1, regionId, cutEdges);
			if (!left.isEmpty() && !right.isEmpty() && !left.equals(right))
			{
				result.add(left.size() <= right.size() ? left : right);
			}
			visited.addAll(left);
			visited.addAll(right);
		}
		result.sort((left, right) -> Integer.compare(right.size(), left.size()));
		return result;
	}

	private static List<Integer> splitSideSizes(List<Set<Center>> splitSides)
	{
		List<Integer> result = new ArrayList<>();
		for (Set<Center> side : splitSides)
		{
			result.add(side.size());
		}
		return result;
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
		Center closest = graph.findClosestCenter(point, true);
		if (closest == null)
		{
			return selected;
		}
		for (Center center : graph.centers)
		{
			if (isCenterOverlappingCircle(center, point, radius))
			{
				selected.add(center);
			}
		}
		if (selected.isEmpty())
		{
			selected.add(closest);
		}
		return selected;
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
		double resolution = session.settings.resolution == 0.0 ? 1.0 : session.settings.resolution;
		int borderPadding = borderPadding(session);
		StringBuilder result = new StringBuilder("{\"ok\":true,\"sessionReady\":true,\"metadata\":");
		result.append(sessionMetadataJson(session));
		result.append(",\"polygons\":[");
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
		result.append("]}");
		return result.toString();
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
		if (settings.edits.regionBoundaryLines == null)
		{
			settings.edits.regionBoundaryLines = new CopyOnWriteArrayList<>();
		}
	}

	private record ExportFormat(String extension, String contentType)
	{
	}

	private static class EditorSession
	{
		final MapSettings settings;
		final MapParts mapParts;
		final Image map;
		final Dimension maxDimensions;

		EditorSession(MapSettings settings, MapParts mapParts, Image map, Dimension maxDimensions)
		{
			this.settings = settings;
			this.mapParts = mapParts;
			this.map = map;
			this.maxDimensions = maxDimensions;
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

	private static boolean isServiceLoggingEnabled(String key)
	{
		return Boolean.TRUE.equals(SERVICE_LOGGING.get(key));
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
			case "region.paint" -> ToolLog.REGION_PAINT;
			case "text.add", "text.update", "text.delete" -> ToolLog.TEXT;
			case "icon.center.set", "icon.center.clear" -> ToolLog.ICONS;
			case "trees.center.set", "trees.center.clear" -> ToolLog.FORESTS;
			case "road.add" -> ToolLog.ROADS;
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
		GENERATION("GENERATION_LOGGING", GENERATION_LOG_PREFIX),
		PROJECTS("PROJECTS_LOGGING", PROJECTS_LOG_PREFIX),
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

	private static class PhaseForwardingOutputStream extends OutputStream
	{
		private final PrintStream original;
		private final SseEmitter emitter;
		private final StringBuilder line = new StringBuilder();

		PhaseForwardingOutputStream(PrintStream original, SseEmitter emitter)
		{
			this.original = original;
			this.emitter = emitter;
		}

		@Override
		public void write(int value) throws IOException
		{
			original.write(value);
			if (value == '\n')
			{
				flushLine();
			}
			else if (value != '\r')
			{
				line.append((char) value);
			}
		}

		@Override
		public void flush() throws IOException
		{
			original.flush();
			flushLine();
		}

		private void flushLine() throws IOException
		{
			String message = line.toString().trim();
			line.setLength(0);
			if (!message.isEmpty())
			{
				emitter.send("phase", "{\"line\":\"" + escape(message) + "\"}");
			}
		}
	}
}
