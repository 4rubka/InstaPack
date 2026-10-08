package com.instapack;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import net.minecraft.client.gui.font.FontManager;
import net.minecraft.client.resources.SplashManager;
import net.minecraft.client.resources.language.LanguageManager;
import net.minecraft.client.sounds.SoundManager;

public record ReloadPlan(Kind kind, Set<Class<?>> listeners, int changedFiles) {
	public enum Kind { SKIP, PARTIAL, FULL }

	public static final ReloadPlan FULL = new ReloadPlan(Kind.FULL, Set.of(), -1);

	private static final Map<String, Class<?>> OWNERS = Map.of(
		"lang", LanguageManager.class,
		"sounds", SoundManager.class,
		"sounds.json", SoundManager.class,
		"texts", SplashManager.class,
		"font", FontManager.class
	);

	private static final Map<String, Map<String, String>> INDEX_CACHE = new ConcurrentHashMap<>();

	public static ReloadPlan between(List<Path> from, List<Path> to) {
		if (from == null) return FULL;
		Map<String, String> before;
		Map<String, String> after;
		try {
			before = view(from);
			after = view(to);
		} catch (UncheckedIOException e) {
			InstaPack.LOG.warn("Could not index server packs, doing a full reload", e);
			return FULL;
		}

		Set<String> changed = new HashSet<>();
		before.forEach((path, fp) -> {
			if (!fp.equals(after.get(path))) changed.add(path);
		});
		after.keySet().forEach(path -> {
			if (!before.containsKey(path)) changed.add(path);
		});
		if (changed.isEmpty()) return new ReloadPlan(Kind.SKIP, Set.of(), 0);

		Set<Class<?>> owners = new HashSet<>();
		for (String path : changed) {
			String[] seg = path.split("/");
			int a = seg[0].equals("assets") ? 0 : seg.length > 1 && seg[1].equals("assets") ? 1 : -1;
			if (a < 0) {
				if (path.equals("pack.mcmeta") || path.endsWith("/pack.mcmeta")) return FULL;
				continue;
			}
			if (seg.length <= a + 2) continue;
			Class<?> owner = OWNERS.get(seg[a + 2]);
			if (owner == null) return new ReloadPlan(Kind.FULL, Set.of(), changed.size());
			owners.add(owner);
		}
		return owners.isEmpty()
			? new ReloadPlan(Kind.SKIP, Set.of(), changed.size())
			: new ReloadPlan(Kind.PARTIAL, owners, changed.size());
	}

	private static Map<String, String> view(List<Path> packs) {
		Map<String, String> merged = new HashMap<>();
		for (Path pack : packs) {
			index(pack).forEach((path, fp) -> merged.merge(path, fp, (a, b) -> a + "|" + b));
		}
		return merged;
	}

	private static Map<String, String> index(Path pack) {
		try {
			String key = pack.toAbsolutePath() + "@" + Files.getLastModifiedTime(pack).toMillis();
			Map<String, String> cached = INDEX_CACHE.get(key);
			if (cached != null) return cached;
			Map<String, String> idx = Files.isDirectory(pack) ? indexDirectory(pack) : indexZip(pack);
			INDEX_CACHE.put(key, idx);
			return idx;
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static Map<String, String> indexZip(Path zip) throws IOException {
		Map<String, String> idx = new HashMap<>();
		try (ZipFile z = new ZipFile(zip.toFile())) {
			for (Enumeration<? extends ZipEntry> e = z.entries(); e.hasMoreElements(); ) {
				ZipEntry entry = e.nextElement();
				if (!entry.isDirectory()) idx.put(entry.getName(), entry.getCrc() + ":" + entry.getSize());
			}
		}
		return idx;
	}

	private static Map<String, String> indexDirectory(Path dir) throws IOException {
		Map<String, String> idx = new HashMap<>();
		Path manifest = dir.resolveSibling(dir.getFileName() + ".idx");
		if (Files.isRegularFile(manifest)) {
			try (var in = Files.newInputStream(manifest)) {
				for (Manifest.Entry e : Manifest.read(in).files()) idx.put(e.path(), e.sha1());
			}
			return idx;
		}
		try (Stream<Path> s = Files.walk(dir)) {
			for (Path p : (Iterable<Path>) s.filter(Files::isRegularFile)::iterator) {
				idx.put(dir.relativize(p).toString().replace('\\', '/'), Files.size(p) + ":" + Files.getLastModifiedTime(p).toMillis());
			}
		}
		return idx;
	}

	@Override
	public String toString() {
		return kind + (kind == Kind.PARTIAL ? " " + listeners.stream().map(Class::getSimpleName).toList() : "") + " (" + changedFiles + " files changed)";
	}
}
