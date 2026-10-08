package com.instapack.velocity;

import com.google.inject.Inject;
import com.instapack.server.PackBuilder;
import com.instapack.server.PackHost;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.slf4j.Logger;

@Plugin(id = "instapack", name = "InstaPack", version = "1.0.0",
	description = "Hosts the network resource pack and sends only changed files to players with the InstaPack mod.")
public final class InstaPackVelocity {
	private final ProxyServer proxy;
	private final Logger logger;
	private final Path dataDir;
	private final Properties config = new Properties();
	private PackHost host;

	@Inject
	public InstaPackVelocity(ProxyServer proxy, Logger logger, @DataDirectory Path dataDir) {
		this.proxy = proxy;
		this.logger = logger;
		this.dataDir = dataDir;
	}

	@Subscribe
	public void onInit(ProxyInitializeEvent event) {
		loadConfig();
		String source = config.getProperty("source", "").trim();
		host = new PackHost(dataDir, source.isEmpty() ? null : Path.of(source), Integer.parseInt(config.getProperty("port")),
			bool("minify-json"), bool("strip-junk"), logger::info);
		try {
			host.start();
		} catch (IOException e) {
			logger.error("Could not start the pack web server", e);
		}
		if (bool("watch")) {
			proxy.getScheduler().buildTask(this, () -> {
				if (host.poll()) proxy.getAllPlayers().forEach(this::send);
			}).repeat(3, TimeUnit.SECONDS).schedule();
		}
		proxy.getCommandManager().register(proxy.getCommandManager().metaBuilder("instapack").plugin(this).build(), new SimpleCommand() {
			@Override
			public void execute(Invocation inv) {
				boolean changed = host.rebuild();
				if (changed) proxy.getAllPlayers().forEach(InstaPackVelocity.this::send);
				PackBuilder.Built p = host.pack();
				inv.source().sendMessage(Component.text("InstaPack: " + (p == null ? "no pack" : (changed ? "pushed " : "unchanged ") + p.hash())));
			}

			@Override
			public boolean hasPermission(Invocation inv) {
				return inv.source().hasPermission("instapack.admin");
			}
		});
	}

	@Subscribe
	public void onShutdown(ProxyShutdownEvent event) {
		if (host != null) host.stop();
	}

	@Subscribe
	public void onConnected(ServerPostConnectEvent event) {
		if (event.getPreviousServer() == null && bool("send-on-join")) send(event.getPlayer());
	}

	private void send(Player player) {
		PackBuilder.Built p = host.pack();
		if (p == null) return;
		String address = config.getProperty("public-host", "").trim();
		if (address.isEmpty()) {
			address = player.getVirtualHost().map(a -> a.getHostString()).orElse("localhost");
		}
		player.sendResourcePackOffer(proxy.createResourcePackBuilder(host.url(address))
			.setId(PackHost.PACK_ID)
			.setHash(HexFormat.of().parseHex(p.hash()))
			.setPrompt(MiniMessage.miniMessage().deserialize(config.getProperty("prompt", "")))
			.setShouldForce(bool("required"))
			.build());
	}

	private boolean bool(String key) {
		return Boolean.parseBoolean(config.getProperty(key));
	}

	private void loadConfig() {
		Path file = dataDir.resolve("config.properties");
		config.setProperty("source", "");
		config.setProperty("port", "8765");
		config.setProperty("public-host", "");
		config.setProperty("send-on-join", "true");
		config.setProperty("required", "false");
		config.setProperty("prompt", "This server uses a custom resource pack.");
		config.setProperty("watch", "true");
		config.setProperty("minify-json", "true");
		config.setProperty("strip-junk", "true");
		try {
			Files.createDirectories(dataDir);
			if (Files.isRegularFile(file)) {
				try (Reader r = Files.newBufferedReader(file)) {
					config.load(r);
				}
			}
			try (Writer w = Files.newBufferedWriter(file)) {
				config.store(w, "InstaPack. Put the pack, unzipped, into pack/ (or pack.zip next to this file).\n"
					+ "source: a folder or zip to serve instead (e.g. another plugin's pack); a zip goes to vanilla clients unchanged\n"
					+ "public-host: empty = the address each player typed to join\n"
					+ "prompt: MiniMessage formatting");
			}
		} catch (IOException e) {
			logger.warn("Could not read/write {}", file, e);
		}
	}
}
