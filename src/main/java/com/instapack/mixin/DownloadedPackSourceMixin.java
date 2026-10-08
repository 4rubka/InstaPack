package com.instapack.mixin;

import com.instapack.InstaDownloader;
import com.instapack.Session;
import com.llamalad7.mixinextras.sugar.Local;
import java.net.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.client.resources.server.DownloadedPackSource;
import net.minecraft.client.resources.server.PackDownloader;
import net.minecraft.client.resources.server.PackReloadConfig;
import net.minecraft.server.packs.DownloadQueue;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.repository.Pack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(DownloadedPackSource.class)
abstract class DownloadedPackSourceMixin {
	@Inject(method = "createDownloader", at = @At("RETURN"), cancellable = true)
	private void instapack$wrapDownloader(DownloadQueue queue, Executor mainThread, User user, Proxy proxy, CallbackInfoReturnable<PackDownloader> cir) {
		cir.setReturnValue(new InstaDownloader(cir.getReturnValue(), proxy));
	}

	@ModifyVariable(method = "loadRequestedPacks", at = @At("STORE"))
	private Pack.ResourcesSupplier instapack$directoryPacks(Pack.ResourcesSupplier resources, @Local Path path) {
		return Files.isDirectory(path) ? new PathPackResources.PathResourcesSupplier(path) : resources;
	}

	@Inject(method = "startReload", at = @At("HEAD"), cancellable = true)
	private void instapack$planReload(PackReloadConfig.Callbacks callbacks, CallbackInfo ci) {
		List<Path> paths = callbacks.packsToLoad().stream().map(PackReloadConfig.IdAndPath::path).toList();
		if (Session.onStartReload(paths, callbacks::onSuccess)) ci.cancel();
	}

	@Redirect(method = "startReload", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;reloadResourcePacks()Ljava/util/concurrent/CompletableFuture;"))
	private CompletableFuture<Void> instapack$reload(Minecraft mc) {
		return Session.reload(mc, (DownloadedPackSource) (Object) this);
	}

	@Inject(method = "onReloadSuccess", at = @At("HEAD"))
	private void instapack$committed(CallbackInfo ci) {
		Session.onReloadSuccess();
	}

	@Inject(method = "onRecovery", at = @At("HEAD"))
	private void instapack$recovery(CallbackInfo ci) {
		Session.onRecovery();
	}

	@Inject(method = {"pushPack", "pushLocalPack"}, at = @At("HEAD"))
	private void instapack$pushed(CallbackInfo ci) {
		Session.onPackPushed();
	}

	@Inject(method = {"configureForServerControl", "configureForLocalWorld"}, at = @At("HEAD"))
	private void instapack$newConnection(CallbackInfo ci) {
		Session.onNewConnection();
	}
}
