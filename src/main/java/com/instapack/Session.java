package com.instapack;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.server.DownloadedPackSource;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.resources.ReloadInstance;
import net.minecraft.server.packs.resources.ReloadableResourceManager;
import net.minecraft.util.Unit;

public final class Session {
	private static List<Path> active = List.of();
	private static List<Path> pending;
	private static ReloadPlan plan = ReloadPlan.FULL;

	private static boolean lingering;
	private static boolean pushedSinceLinger;
	private static int lingerTicks;

	public static volatile Set<Class<?>> listenerFilter;

	private Session() {}

	public static boolean onStartReload(List<Path> packs, Runnable succeed) {
		InstaPack.store();
		if (packs.isEmpty() && InstaPack.keepPacksAfterDisconnect && active != null && !active.isEmpty()) {
			lingering = true;
			pushedSinceLinger = false;
			lingerTicks = 0;
			InstaPack.LOG.info("Keeping {} server pack(s) loaded until the next server decides", active.size());
			succeed.run();
			return true;
		}
		lingering = false;
		pending = packs;
		plan = InstaPack.skipUnchanged ? ReloadPlan.between(active, packs) : ReloadPlan.FULL;
		if (plan.kind() == ReloadPlan.Kind.PARTIAL && !InstaPack.partialReload) plan = ReloadPlan.FULL;
		InstaPack.LOG.info("Server packs changed, reload: {}", plan);
		return false;
	}

	public static CompletableFuture<Void> reload(Minecraft mc, DownloadedPackSource source) {
		ReloadPlan p = plan;
		plan = ReloadPlan.FULL;
		return switch (p.kind()) {
			case SKIP -> {
				source.onReloadSuccess();
				yield CompletableFuture.completedFuture(null);
			}
			case PARTIAL -> Compat.isLoading(mc) ? mc.reloadResourcePacks() : partialReload(mc, source, p.listeners());
			case FULL -> mc.reloadResourcePacks();
		};
	}

	private static CompletableFuture<Void> partialReload(Minecraft mc, DownloadedPackSource source, Set<Class<?>> listeners) {
		PackRepository repo = mc.getResourcePackRepository();
		repo.reload();
		List<PackResources> packs = repo.openAllSelected();
		ReloadInstance instance;
		listenerFilter = listeners;
		try {
			instance = ((ReloadableResourceManager) mc.getResourceManager())
				.createReload(Compat.backgroundExecutor(), mc, CompletableFuture.completedFuture(Unit.INSTANCE), packs);
		} finally {
			listenerFilter = null;
		}
		CompletableFuture<Void> done = new CompletableFuture<>();
		instance.done().whenCompleteAsync((ignored, error) -> {
			if (error == null) {
				source.onReloadSuccess();
				done.complete(null);
			} else {
				InstaPack.LOG.warn("Partial reload failed, doing a full one", error);
				active = null;
				mc.reloadResourcePacks().whenComplete((v, e) -> done.complete(null));
			}
		}, mc);
		return done;
	}

	public static void onReloadSuccess() {
		if (pending != null) {
			active = pending;
			pending = null;
		}
	}

	public static void onRecovery() {
		active = null;
		pending = null;
	}

	public static void onPackPushed() {
		pushedSinceLinger = true;
	}

	public static void onNewConnection() {
		pushedSinceLinger = false;
		lingerTicks = 0;
	}

	public static void tick(Minecraft mc) {
		if (!lingering || pushedSinceLinger || mc.level == null) return;
		if (++lingerTicks < InstaPack.unloadDelaySeconds * 20) return;
		lingering = false;
		DownloadedPackSource source = mc.getDownloadedPackSource();
		((com.instapack.mixin.DownloadedPackSourceAccessor) source).instapack$setPackSource(output -> {});
		pending = List.of();
		plan = ReloadPlan.between(active, pending);
		InstaPack.LOG.info("No server packs here, unloading the kept ones, reload: {}", plan);
		reload(mc, source);
	}
}
