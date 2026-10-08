package com.instapack;

import java.net.Proxy;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.server.PackDownloader;
import net.minecraft.server.packs.DownloadQueue;

public final class InstaDownloader implements PackDownloader {
	private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "InstaPack download");
		t.setDaemon(true);
		return t;
	});

	private final PackDownloader vanilla;
	private final Proxy proxy;

	public InstaDownloader(PackDownloader vanilla, Proxy proxy) {
		this.vanilla = vanilla;
		this.proxy = proxy;
	}

	@Override
	public void download(Map<UUID, DownloadQueue.DownloadRequest> requests, Consumer<DownloadQueue.BatchResult> output) {
		PackStore store = InstaPack.store();
		IO.execute(() -> {
			DownloadQueue.BatchResult result = new DownloadQueue.BatchResult();
			Map<UUID, DownloadQueue.DownloadRequest> rest = new HashMap<>();
			DeltaClient delta = new DeltaClient(store, proxy);
			requests.forEach((id, request) -> {
				String hash = request.hash() == null ? null : request.hash().toString();
				Path ready = null;
				if (hash != null && InstaPack.sharedCache) ready = store.lookup(hash);
				if (ready == null && InstaPack.delta && request.url().getProtocol().startsWith("http")) {
					try {
						ready = delta.fetch(request.url(), hash);
					} catch (Exception e) {
						InstaPack.LOG.warn("Delta download of {} failed, falling back to the full pack", request.url(), e);
					}
				}
				if (ready != null) result.downloaded().put(id, ready);
				else rest.put(id, request);
			});

			if (rest.isEmpty()) {
				Minecraft.getInstance().execute(() -> output.accept(result));
				return;
			}
			vanilla.download(rest, plain -> {
				result.downloaded().putAll(plain.downloaded());
				result.failed().addAll(plain.failed());
				if (InstaPack.sharedCache) {
					plain.downloaded().forEach((id, path) -> {
						var hash = rest.get(id).hash();
						if (hash != null) IO.execute(() -> store.rememberZip(hash.toString(), path));
					});
				}
				output.accept(result);
			});
		});
	}
}
