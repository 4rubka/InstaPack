package com.instapack.server;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;

public final class PackHost {
	public static final UUID PACK_ID = UUID.nameUUIDFromBytes("instapack:pack".getBytes());

	private final Path dataDir;
	private final Path source;
	private final PackBuilder builder;
	private final Consumer<String> info;
	private final int port;
	private volatile PackBuilder.Built pack;
	private PackHttpServer http;
	private long lastStamp;

	public PackHost(Path dataDir, Path source, int port, boolean minifyJson, boolean stripJunk, Consumer<String> info) {
		this.dataDir = dataDir;
		this.source = source;
		this.port = port;
		this.builder = new PackBuilder(minifyJson, stripJunk);
		this.info = info;
	}

	public void start() throws IOException {
		Files.createDirectories(dataDir.resolve("pack"));
		rebuild();
		lastStamp = stamp();
		http = new PackHttpServer(port, () -> pack);
		http.start();
		info.accept("Serving the pack on port " + port);
	}

	public void stop() {
		if (http != null) http.stop();
	}

	public PackBuilder.Built pack() {
		return pack;
	}

	public String url(String host) {
		return "http://" + host + ":" + port + "/" + pack.hash() + ".zip";
	}

	public boolean poll() {
		long now = stamp();
		if (now == lastStamp) return false;
		lastStamp = now;
		return rebuild();
	}

	public synchronized boolean rebuild() {
		Path src = source();
		try {
			long start = System.currentTimeMillis();
			PackBuilder.Built built = builder.build(src);
			PackBuilder.Built old = pack;
			if (old != null && old.hash().equals(built.hash())) return false;
			pack = built;
			info.accept("Pack " + built.hash() + ": " + built.files() + " files, " + (built.zip().length >> 10) + " KiB, built in "
				+ (System.currentTimeMillis() - start) + " ms");
			return true;
		} catch (IOException e) {
			info.accept((pack == null ? "No pack yet: " : "Pack rebuild failed, keeping the previous one: ") + e.getMessage());
			return false;
		}
	}

	private Path source() {
		if (source != null) return source;
		Path dir = dataDir.resolve("pack");
		if (Files.exists(dir.resolve("pack.mcmeta"))) return dir;
		Path zip = dataDir.resolve("pack.zip");
		return Files.isRegularFile(zip) ? zip : dir;
	}

	private long stamp() {
		try (Stream<Path> s = Files.walk(source())) {
			return s.filter(Files::isRegularFile).mapToLong(p -> {
				try {
					return Files.getLastModifiedTime(p).toMillis() * 31 + Files.size(p) + p.hashCode();
				} catch (IOException e) {
					return 0;
				}
			}).sum();
		} catch (IOException e) {
			return 0;
		}
	}
}
