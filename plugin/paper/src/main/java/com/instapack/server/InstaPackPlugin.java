package com.instapack.server;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.HexFormat;
import java.util.logging.Level;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

public final class InstaPackPlugin extends JavaPlugin implements Listener {
	private PackHost host;

	@Override
	public void onEnable() {
		saveDefaultConfig();
		String source = getConfig().getString("source", "");
		host = new PackHost(getDataFolder().toPath(), source == null || source.isBlank() ? null : java.nio.file.Path.of(source), getConfig().getInt("port", 8765),
			getConfig().getBoolean("optimize.minify-json", true), getConfig().getBoolean("optimize.strip-junk", true), getLogger()::info);
		try {
			host.start();
		} catch (IOException e) {
			getLogger().log(Level.SEVERE, "Could not start the pack web server", e);
		}
		getServer().getPluginManager().registerEvents(this, this);
		if (getConfig().getBoolean("watch", true)) {
			Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
				if (host.poll()) pushToAll();
			}, 60L, 60L);
		}
	}

	@Override
	public void onDisable() {
		if (host != null) host.stop();
	}

	private void pushToAll() {
		Bukkit.getScheduler().runTask(this, () -> Bukkit.getOnlinePlayers().forEach(this::send));
	}

	private void send(Player player) {
		PackBuilder.Built p = host.pack();
		if (p == null) return;
		String address = getConfig().getString("public-host", "");
		if (address == null || address.isBlank()) {
			InetSocketAddress joined = player.getVirtualHost();
			address = joined != null ? joined.getHostString() : Bukkit.getIp().isEmpty() ? "localhost" : Bukkit.getIp();
		}
		player.setResourcePack(PackHost.PACK_ID, host.url(address), HexFormat.of().parseHex(p.hash()),
			MiniMessage.miniMessage().deserialize(getConfig().getString("prompt", "")), getConfig().getBoolean("required", false));
	}

	@EventHandler
	public void onJoin(PlayerJoinEvent event) {
		if (getConfig().getBoolean("send-on-join", true)) send(event.getPlayer());
	}

	@Override
	public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
		if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
			reloadConfig();
			Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
				boolean changed = host.rebuild();
				if (changed) pushToAll();
				PackBuilder.Built p = host.pack();
				sender.sendMessage("InstaPack: " + (p == null ? "no pack" : (changed ? "pushed " : "unchanged ") + p.hash()));
			});
			return true;
		}
		return false;
	}
}
