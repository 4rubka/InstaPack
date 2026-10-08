package com.instapack;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public record Manifest(String hash, List<Entry> files) {
	public static final String HEADER = "instapack 1";
	private static final Pattern SHA1 = Pattern.compile("[0-9a-f]{40}");

	public record Entry(String sha1, long size, String path) {}

	public static Manifest read(InputStream in) throws IOException {
		BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
		if (!HEADER.equals(r.readLine())) throw new IOException("Not an InstaPack manifest");
		String hash = sha1(r.readLine());
		List<Entry> files = new ArrayList<>();
		String line;
		while ((line = r.readLine()) != null) {
			if (line.isEmpty()) continue;
			int a = line.indexOf(' ');
			int b = a < 0 ? -1 : line.indexOf(' ', a + 1);
			if (b < 0) throw new IOException("Bad manifest line: " + line);
			long size = Long.parseLong(line.substring(a + 1, b));
			String path = line.substring(b + 1);
			if (!safePath(path)) throw new IOException("Unsafe path in manifest: " + path);
			files.add(new Entry(sha1(line.substring(0, a)), size, path));
		}
		return new Manifest(hash, files);
	}

	public String write() {
		StringBuilder sb = new StringBuilder(HEADER).append('\n').append(hash).append('\n');
		for (Entry e : files) sb.append(e.sha1).append(' ').append(e.size).append(' ').append(e.path).append('\n');
		return sb.toString();
	}

	private static String sha1(String s) throws IOException {
		if (s == null) throw new IOException("Truncated manifest");
		s = s.toLowerCase(Locale.ROOT);
		if (!SHA1.matcher(s).matches()) throw new IOException("Bad sha1: " + s);
		return s;
	}

	static boolean safePath(String p) {
		if (p.isEmpty() || p.startsWith("/") || p.indexOf('\\') >= 0 || p.indexOf(':') >= 0 || p.indexOf('\0') >= 0) return false;
		for (String part : p.split("/", -1)) {
			if (part.isEmpty() || part.equals(".") || part.equals("..")) return false;
		}
		return true;
	}
}
