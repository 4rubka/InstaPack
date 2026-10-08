package com.instapack.server;

import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

public final class PackBuilder {
	private static final long FIXED_TIME = 315532800000L + 86400000L * 31;
	private static final Set<String> STORED = Set.of("png", "ogg", "jpg", "jpeg", "gz", "zip");
	private static final Set<String> JUNK_NAMES = Set.of(".ds_store", "thumbs.db", "desktop.ini");
	private static final Set<String> JUNK_EXT = Set.of("psd", "xcf", "kra", "bbmodel", "blend", "blend1", "ase", "aseprite", "pdn", "bak", "tmp");

	public record Built(byte[] zip, String hash, Map<String, byte[]> objects, String manifest, int files) {}

	private final boolean minifyJson;
	private final boolean stripJunk;

	public PackBuilder(boolean minifyJson, boolean stripJunk) {
		this.minifyJson = minifyJson;
		this.stripJunk = stripJunk;
	}

	public Built build(Path source) throws IOException {
		boolean dir = Files.isDirectory(source);
		Map<String, byte[]> files = dir ? readDirectory(source) : readZip(source);
		if (!files.containsKey("pack.mcmeta")) throw new IOException("pack.mcmeta not found in " + source);

		Map<String, byte[]> objects = new HashMap<>();
		Map<String, String> fileHash = new TreeMap<>();
		for (Map.Entry<String, byte[]> f : files.entrySet()) {
			String sha1 = sha1(f.getValue());
			objects.put(sha1, f.getValue());
			fileHash.put(f.getKey(), sha1);
		}
		byte[] zip = dir ? zip(files) : Files.readAllBytes(source);
		String hash = sha1(zip);

		StringBuilder manifest = new StringBuilder("instapack 1\n").append(hash).append('\n');
		fileHash.forEach((path, sha1) -> manifest.append(sha1).append(' ').append(objects.get(sha1).length).append(' ').append(path).append('\n'));
		return new Built(zip, hash, objects, manifest.toString(), files.size());
	}

	private static byte[] zip(Map<String, byte[]> files) throws IOException {
		ByteArrayOutputStream zipBytes = new ByteArrayOutputStream();
		try (ZipOutputStream zip = new ZipOutputStream(zipBytes)) {
			zip.setLevel(Deflater.BEST_COMPRESSION);
			for (Map.Entry<String, byte[]> f : files.entrySet()) {
				byte[] data = f.getValue();
				ZipEntry e = new ZipEntry(f.getKey());
				e.setTime(FIXED_TIME);
				if (STORED.contains(extension(f.getKey()))) {
					CRC32 crc = new CRC32();
					crc.update(data);
					e.setMethod(ZipEntry.STORED);
					e.setSize(data.length);
					e.setCompressedSize(data.length);
					e.setCrc(crc.getValue());
				}
				zip.putNextEntry(e);
				zip.write(data);
				zip.closeEntry();
			}
		}
		return zipBytes.toByteArray();
	}

	private Map<String, byte[]> readDirectory(Path dir) throws IOException {
		Map<String, byte[]> files = new TreeMap<>();
		try (Stream<Path> s = Files.walk(dir)) {
			for (Path p : (Iterable<Path>) s.filter(Files::isRegularFile)::iterator) {
				String rel = dir.relativize(p).toString().replace('\\', '/');
				add(files, rel, Files.readAllBytes(p));
			}
		}
		return files;
	}

	private static Map<String, byte[]> readZip(Path file) throws IOException {
		Map<String, byte[]> files = new TreeMap<>();
		try (ZipFile zip = new ZipFile(file.toFile())) {
			for (Enumeration<? extends ZipEntry> en = zip.entries(); en.hasMoreElements(); ) {
				ZipEntry e = en.nextElement();
				if (e.isDirectory()) continue;
				try (InputStream in = zip.getInputStream(e)) {
					files.put(e.getName(), in.readAllBytes());
				}
			}
		}
		return files;
	}

	private void add(Map<String, byte[]> files, String path, byte[] data) {
		String name = path.substring(path.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
		if (stripJunk && (path.startsWith("__MACOSX/") || name.startsWith("._") || JUNK_NAMES.contains(name)
			|| JUNK_EXT.contains(extension(name)) || path.startsWith(".git/"))) return;
		String ext = extension(name);
		if (minifyJson && (ext.equals("json") || ext.equals("mcmeta"))) {
			try {
				String text = new String(data, StandardCharsets.UTF_8).replace("﻿", "");
				data = JsonParser.parseString(text).toString().getBytes(StandardCharsets.UTF_8);
			} catch (RuntimeException ignored) {
			}
		}
		files.put(path, data);
	}

	private static String extension(String name) {
		int dot = name.lastIndexOf('.');
		return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
	}

	public static String sha1(byte[] data) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(data));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}
