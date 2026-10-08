package com.instapack;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

final class DeltaClient {
	static final String CONTENT_TYPE = "application/x-instapack";
	private static final long MAX_OBJECT = 256L << 20;
	private static final Set<String> PLAIN_HOSTS = ConcurrentHashMap.newKeySet();

	private final PackStore store;
	private final Proxy proxy;

	DeltaClient(PackStore store, Proxy proxy) {
		this.store = store;
		this.proxy = proxy;
	}

	Path fetch(URL url, String expectedHash) throws IOException {
		String host = url.getHost() + ":" + url.getPort();
		if (PLAIN_HOSTS.contains(host)) return null;

		Manifest m;
		HttpURLConnection c = open(url);
		try {
			c.setRequestProperty("X-InstaPack", "manifest");
			if (c.getResponseCode() != 200 || !CONTENT_TYPE.equals(c.getContentType())) {
				PLAIN_HOSTS.add(host);
				return null;
			}
			try (InputStream in = new GZIPInputStream(c.getInputStream())) {
				m = Manifest.read(in);
			}
		} finally {
			c.disconnect();
		}
		if (expectedHash != null && !expectedHash.equals(m.hash())) {
			throw new IOException("Server manifest is for " + m.hash() + ", expected " + expectedHash);
		}
		Path ready = store.lookup(m.hash());
		if (ready != null) return ready;

		Set<String> missing = new LinkedHashSet<>();
		long bytes = 0;
		for (Manifest.Entry e : m.files()) {
			if (!store.hasObject(e.sha1()) && missing.add(e.sha1())) bytes += e.size();
		}
		int fetched = missing.size();
		if (!missing.isEmpty()) download(url, missing);
		InstaPack.LOG.info("Pack {}: {} files, fetched {} new ({} KiB)", m.hash(), m.files().size(), fetched, bytes >> 10);
		return store.assemble(m);
	}

	private void download(URL url, Set<String> wanted) throws IOException {
		HttpURLConnection c = open(url);
		try {
			c.setRequestMethod("POST");
			c.setDoOutput(true);
			c.setRequestProperty("X-InstaPack", "objects");
			c.setRequestProperty("Content-Type", CONTENT_TYPE);
			try (OutputStream out = new GZIPOutputStream(c.getOutputStream())) {
				out.write(String.join("\n", wanted).getBytes(StandardCharsets.US_ASCII));
			}
			if (c.getResponseCode() != 200) throw new IOException("Object request failed: HTTP " + c.getResponseCode());

			byte[] id = new byte[20];
			try (DataInputStream in = new DataInputStream(new GZIPInputStream(c.getInputStream(), 1 << 16))) {
				while (true) {
					try {
						in.readFully(id);
					} catch (EOFException end) {
						break;
					}
					String hex = HexFormat.of().formatHex(id);
					long size = in.readLong();
					if (!wanted.remove(hex) || size < 0 || size > MAX_OBJECT) throw new IOException("Unexpected object " + hex);
					store.putObject(hex, new Limited(in, size), size);
				}
			}
			if (!wanted.isEmpty()) throw new IOException(wanted.size() + " objects missing from server response");
		} finally {
			c.disconnect();
		}
	}

	private HttpURLConnection open(URL url) throws IOException {
		HttpURLConnection c = (HttpURLConnection) url.openConnection(proxy);
		c.setConnectTimeout(5000);
		c.setReadTimeout(15000);
		c.setUseCaches(false);
		c.setRequestProperty("User-Agent", "InstaPack");
		return c;
	}

	private static final class Limited extends InputStream {
		private final InputStream in;
		private long left;

		Limited(InputStream in, long left) {
			this.in = in;
			this.left = left;
		}

		@Override
		public int read() throws IOException {
			if (left <= 0) return -1;
			int b = in.read();
			if (b >= 0) left--;
			return b;
		}

		@Override
		public int read(byte[] b, int off, int len) throws IOException {
			if (left <= 0) return -1;
			int n = in.read(b, off, (int) Math.min(len, left));
			if (n > 0) left -= n;
			return n;
		}
	}
}
