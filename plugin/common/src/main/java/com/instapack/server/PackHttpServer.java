package com.instapack.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public final class PackHttpServer {
	private static final String CONTENT_TYPE = "application/x-instapack";
	private static final int MAX_REQUEST = 4 << 20;

	private final HttpServer server;
	private final ExecutorService pool = Executors.newFixedThreadPool(4, r -> {
		Thread t = new Thread(r, "InstaPack HTTP");
		t.setDaemon(true);
		return t;
	});
	private final Supplier<PackBuilder.Built> pack;

	public PackHttpServer(int port, Supplier<PackBuilder.Built> pack) throws IOException {
		this.pack = pack;
		this.server = HttpServer.create(new InetSocketAddress(port), 64);
		server.setExecutor(pool);
		server.createContext("/", this::handle);
	}

	public void start() {
		server.start();
	}

	public void stop() {
		server.stop(0);
		pool.shutdownNow();
	}

	private void handle(HttpExchange ex) throws IOException {
		try (ex) {
			PackBuilder.Built p = pack.get();
			if (p == null) {
				ex.sendResponseHeaders(503, -1);
				return;
			}
			String mode = ex.getRequestHeaders().getFirst("X-InstaPack");
			if ("manifest".equals(mode) && ex.getRequestMethod().equals("GET")) {
				ex.getResponseHeaders().set("Content-Type", CONTENT_TYPE);
				ex.sendResponseHeaders(200, 0);
				try (OutputStream out = new GZIPOutputStream(ex.getResponseBody())) {
					out.write(p.manifest().getBytes(StandardCharsets.UTF_8));
				}
			} else if ("objects".equals(mode) && ex.getRequestMethod().equals("POST")) {
				sendObjects(ex, p);
			} else if (ex.getRequestMethod().equals("GET") || ex.getRequestMethod().equals("HEAD")) {
				ex.getResponseHeaders().set("Content-Type", "application/zip");
				boolean head = ex.getRequestMethod().equals("HEAD");
				ex.sendResponseHeaders(200, head ? -1 : p.zip().length);
				if (!head) ex.getResponseBody().write(p.zip());
			} else {
				ex.sendResponseHeaders(405, -1);
			}
		} catch (IOException ignored) {
		}
	}

	private void sendObjects(HttpExchange ex, PackBuilder.Built p) throws IOException {
		Set<String> wanted = new LinkedHashSet<>();
		try (InputStream in = new GZIPInputStream(ex.getRequestBody())) {
			byte[] body = in.readNBytes(MAX_REQUEST);
			for (String line : new String(body, StandardCharsets.US_ASCII).split("\n")) {
				line = line.trim();
				if (line.isEmpty()) continue;
				if (!p.objects().containsKey(line)) {
					ex.sendResponseHeaders(404, -1);
					return;
				}
				wanted.add(line);
			}
		}
		ex.getResponseHeaders().set("Content-Type", CONTENT_TYPE);
		ex.sendResponseHeaders(200, 0);
		try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(ex.getResponseBody(), 1 << 16))) {
			for (String sha1 : wanted) {
				byte[] data = p.objects().get(sha1);
				out.write(HexFormat.of().parseHex(sha1));
				out.writeLong(data.length);
				out.write(data);
			}
		}
	}
}
