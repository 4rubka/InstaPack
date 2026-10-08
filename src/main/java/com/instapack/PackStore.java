package com.instapack;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

public final class PackStore {
	private final Path objects;
	private final Path packs;
	private final Path zips;

	PackStore(Path root) {
		this.objects = root.resolve("objects");
		this.packs = root.resolve("packs");
		this.zips = root.resolve("zips");
	}

	public Path lookup(String hash) {
		if (Files.isRegularFile(index(hash))) return touch(packs.resolve(hash), index(hash));
		Path zip = zips.resolve(hash + ".zip");
		if (Files.isRegularFile(zip)) return touch(zip, zip);
		return null;
	}

	public void rememberZip(String hash, Path downloaded) {
		Path zip = zips.resolve(hash + ".zip");
		if (Files.exists(zip)) return;
		try {
			Files.createDirectories(zips);
			linkOrCopy(downloaded, zip);
		} catch (IOException e) {
			InstaPack.LOG.debug("Could not cache {}", downloaded, e);
		}
	}

	public Path index(String hash) {
		return packs.resolve(hash + ".idx");
	}

	public Path object(String sha1) {
		return objects.resolve(sha1.substring(0, 2)).resolve(sha1);
	}

	public boolean hasObject(String sha1) {
		return Files.isRegularFile(object(sha1));
	}

	public void putObject(String sha1, InputStream data, long size) throws IOException {
		Path target = object(sha1);
		Files.createDirectories(target.getParent());
		Path tmp = target.resolveSibling(sha1 + "." + UUID.randomUUID() + ".tmp");
		try {
			MessageDigest md = MessageDigest.getInstance("SHA-1");
			long n = Files.copy(new DigestInputStream(data, md), tmp);
			if (n != size) throw new EOFException("Object " + sha1 + " truncated");
			if (!sha1.equals(HexFormat.of().formatHex(md.digest()))) throw new IOException("Object " + sha1 + " hash mismatch");
			move(tmp, target);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		} finally {
			Files.deleteIfExists(tmp);
		}
	}

	public Path assemble(Manifest m) throws IOException {
		Path dir = packs.resolve(m.hash());
		Files.createDirectories(packs);
		Path tmp = packs.resolve(m.hash() + "." + UUID.randomUUID() + ".tmp");
		try {
			for (Manifest.Entry e : m.files()) {
				Path target = tmp.resolve(e.path());
				Files.createDirectories(target.getParent());
				linkOrCopy(object(e.sha1()), target);
			}
			if (Files.exists(dir)) deleteTree(dir);
			move(tmp, dir);
		} finally {
			if (Files.exists(tmp)) deleteTree(tmp);
		}
		Files.writeString(index(m.hash()), m.write(), StandardCharsets.UTF_8);
		return dir;
	}

	void collectGarbageAsync(int keep) {
		Thread t = new Thread(() -> {
			try {
				prune(packs, keep, p -> p.getFileName().toString().endsWith(".idx"));
				prune(zips, keep, p -> true);
				dropUnlinkedObjects();
			} catch (Exception e) {
				InstaPack.LOG.debug("Cache cleanup failed", e);
			}
		}, "InstaPack cache cleanup");
		t.setDaemon(true);
		t.start();
	}

	private void prune(Path dir, int keep, java.util.function.Predicate<Path> entry) throws IOException {
		if (!Files.isDirectory(dir)) return;
		List<Path> all;
		try (Stream<Path> s = Files.list(dir)) {
			all = s.filter(entry).sorted(Comparator.comparing(PackStore::mtime).reversed()).toList();
		}
		for (Path p : all.subList(Math.min(keep, all.size()), all.size())) {
			String name = p.getFileName().toString();
			if (name.endsWith(".idx")) {
				Files.deleteIfExists(p);
				deleteTree(dir.resolve(name.substring(0, name.length() - 4)));
			} else {
				Files.deleteIfExists(p);
			}
		}
	}

	private void dropUnlinkedObjects() throws IOException {
		if (!Files.isDirectory(objects)) return;
		try (Stream<Path> s = Files.walk(objects)) {
			for (Path p : (Iterable<Path>) s.filter(Files::isRegularFile)::iterator) {
				Object links;
				try {
					links = Files.getAttribute(p, "unix:nlink");
				} catch (UnsupportedOperationException | IllegalArgumentException e) {
					return;
				}
				if (links instanceof Integer n && n <= 1) Files.deleteIfExists(p);
			}
		}
	}

	private static Path touch(Path result, Path marker) {
		try {
			Files.setLastModifiedTime(marker, FileTime.fromMillis(System.currentTimeMillis()));
		} catch (IOException ignored) {
		}
		return result;
	}

	private static FileTime mtime(Path p) {
		try {
			return Files.getLastModifiedTime(p);
		} catch (IOException e) {
			return FileTime.fromMillis(0);
		}
	}

	private static void linkOrCopy(Path source, Path target) throws IOException {
		try {
			Files.createLink(target, source);
		} catch (FileAlreadyExistsException e) {
			throw e;
		} catch (IOException | UnsupportedOperationException | SecurityException e) {
			Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	private static void move(Path from, Path to) throws IOException {
		try {
			Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	static void deleteTree(Path dir) throws IOException {
		if (!Files.exists(dir)) return;
		try (Stream<Path> s = Files.walk(dir)) {
			for (Path p : s.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
		}
	}
}
