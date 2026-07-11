package nortantis.rest;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import nortantis.ImageCache;
import nortantis.MapCreator;
import nortantis.MapSettings;
import nortantis.NameCreator;
import nortantis.SettingsGenerator;
import nortantis.platform.Image;
import nortantis.platform.ImageHelper;
import nortantis.platform.PlatformFactory;
import nortantis.platform.awt.AwtFactory;
import nortantis.swing.translation.Translation;

import java.io.IOException;
import java.io.PrintStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.Executors;

public class NortantisRestServer
{
	private static final Object EXPORT_STREAM_LOCK = new Object();

	public static void main(String[] args) throws IOException
	{
		System.setProperty("java.awt.headless", "true");
		PlatformFactory.setInstance(new AwtFactory());
		Translation.initialize();

		int port = Integer.parseInt(System.getenv().getOrDefault("NORTANTIS_REST_PORT", "8091"));
		HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
		server.createContext("/health", NortantisRestServer::health);
		server.createContext("/api/projects/default", NortantisRestServer::defaultProject);
		server.createContext("/api/projects/export", NortantisRestServer::exportProject);
		server.createContext("/api/projects/export-stream", NortantisRestServer::exportProjectStream);
		server.setExecutor(Executors.newFixedThreadPool(Integer.parseInt(System.getenv().getOrDefault("NORTANTIS_REST_THREADS", "2"))));
		server.start();
		System.out.println("Nortantis REST server listening on " + port);
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
			MapSettings settings = SettingsGenerator.generate(null);
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

	private static void exportProject(HttpExchange exchange) throws IOException
	{
		if (!exchange.getRequestMethod().equals("POST"))
		{
			send(exchange, 405, "application/json", "{\"ok\":false}".getBytes());
			return;
		}
		Path project = Files.createTempFile("nortantis-project-", MapSettings.fileExtensionWithDot);
		Path image = Files.createTempFile("nortantis-export-", ".png");
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
			Image result = new MapCreator().createMap(settings, null, null);
			ImageHelper.getInstance().write(result, image.toString());
			ImageCache.clear();
			send(exchange, 200, "image/png", Files.readAllBytes(image));
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
					Image result = new MapCreator().createMap(settings, null, null);
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

	private static void send(HttpExchange exchange, int status, String contentType, byte[] body) throws IOException
	{
		exchange.getResponseHeaders().set("Content-Type", contentType);
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
