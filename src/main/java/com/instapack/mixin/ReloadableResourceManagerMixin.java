package com.instapack.mixin;

import com.instapack.InstaPack;
import com.instapack.Session;
import java.util.List;
import java.util.Set;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ReloadableResourceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(value = ReloadableResourceManager.class, priority = 1500)
abstract class ReloadableResourceManagerMixin {
	@ModifyArg(method = "createReload", index = 1, at = @At(value = "INVOKE", target = "Lnet/minecraft/server/packs/resources/SimpleReloadInstance;create(Lnet/minecraft/server/packs/resources/ResourceManager;Ljava/util/List;Ljava/util/concurrent/Executor;Ljava/util/concurrent/Executor;Ljava/util/concurrent/CompletableFuture;Z)Lnet/minecraft/server/packs/resources/ReloadInstance;"))
	private List<PreparableReloadListener> instapack$onlyOwners(List<PreparableReloadListener> listeners) {
		Set<Class<?>> only = Session.listenerFilter;
		if (only == null) return listeners;
		return listeners.stream().filter(l -> only.stream().anyMatch(c -> c.isInstance(l))).toList();
	}

	@ModifyArg(method = "createReload", index = 5, at = @At(value = "INVOKE", target = "Lnet/minecraft/server/packs/resources/SimpleReloadInstance;create(Lnet/minecraft/server/packs/resources/ResourceManager;Ljava/util/List;Ljava/util/concurrent/Executor;Ljava/util/concurrent/Executor;Ljava/util/concurrent/CompletableFuture;Z)Lnet/minecraft/server/packs/resources/ReloadInstance;"))
	private boolean instapack$profile(boolean profiled) {
		InstaPack.ensureConfig();
		return profiled || InstaPack.profileReloads;
	}
}
