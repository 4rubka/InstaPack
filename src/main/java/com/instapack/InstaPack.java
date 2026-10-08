package com.instapack;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class InstaPack {
	public static final Logger LOG = LoggerFactory.getLogger("InstaPack");

	public static boolean skipUnchanged = true;
	public static boolean partialReload = true;
	public static boolean keepPacksAfterDisconnect = true;
	public static int unloadDelaySeconds = 3;
	public static boolean sharedCache = true;
	public static boolean delta = true;
	public static int keptPacks = 20;
	public static boolean profileReloads = false;

	private static PackStore store;
	private static boolean configLoaded;

	private InstaPack() {}

	public static synchronized void ensureConfig() {
		if (configLoaded) return;
		configLoaded = true;
		loadConfig(Minecraft.getInstance().gameDirectory.toPath().resolve("config").resolve("instapack.properties"));
	}

	public static synchronized PackStore store() {
		if (store == null) {
			ensureConfig();
			store = new PackStore(Minecraft.getInstance().gameDirectory.toPath().resolve("instapack"));
			store.collectGarbageAsync(keptPacks);
		}
		return store;
	}

	private static void loadConfig(Path file) {
		Properties p = new Properties();
		if (Files.isRegularFile(file)) {
			try (Reader r = Files.newBufferedReader(file)) {
				p.load(r);
			} catch (IOException e) {
				LOG.warn("Failed to read {}, using defaults", file, e);
			}
		}
		skipUnchanged = bool(p, "skipUnchanged", skipUnchanged);
		partialReload = bool(p, "partialReload", partialReload);
		keepPacksAfterDisconnect = bool(p, "keepPacksAfterDisconnect", keepPacksAfterDisconnect);
		unloadDelaySeconds = integer(p, "unloadDelaySeconds", unloadDelaySeconds);
		sharedCache = bool(p, "sharedCache", sharedCache);
		delta = bool(p, "delta", delta);
		keptPacks = integer(p, "keptPacks", keptPacks);
		profileReloads = bool(p, "profileReloads", profileReloads);
		try {
			Files.createDirectories(file.getParent());
			try (Writer w = Files.newBufferedWriter(file)) {
				p.store(w, "InstaPack");
			}
		} catch (IOException e) {
			LOG.warn("Failed to write {}", file, e);
		}
	}

	private static boolean bool(Properties p, String key, boolean def) {
		boolean v = Boolean.parseBoolean(p.getProperty(key, String.valueOf(def)));
		p.setProperty(key, String.valueOf(v));
		return v;
	}

	private static int integer(Properties p, String key, int def) {
		int v;
		try {
			v = Integer.parseInt(p.getProperty(key, String.valueOf(def)).trim());
		} catch (NumberFormatException e) {
			v = def;
		}
		p.setProperty(key, String.valueOf(v));
		return v;
	}
}
