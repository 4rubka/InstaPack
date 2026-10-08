package com.instapack.mixin;

import com.instapack.ZipIndex;
import com.llamalad7.mixinextras.sugar.Local;
import java.util.zip.ZipFile;
//#if MC >= 1.21.11
import net.minecraft.resources.Identifier;
//#else
//$ import net.minecraft.resources.ResourceLocation;
//#endif
import net.minecraft.server.packs.FilePackResources;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.IoSupplier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FilePackResources.class)
abstract class FilePackResourcesMixin {
	@Shadow
	private String addPrefix(String path) {
		throw new AssertionError();
	}

	@Inject(method = "listResources", cancellable = true, at = @At(value = "INVOKE_ASSIGN",
		target = "Lnet/minecraft/server/packs/FilePackResources$SharedZipFileAccess;getOrCreateZipFile()Ljava/util/zip/ZipFile;"))
	private void instapack$indexedList(PackType type, String namespace, String directory, PackResources.ResourceOutput output, CallbackInfo ci, @Local ZipFile zip) {
		ci.cancel();
		if (zip == null) return;
		String root = addPrefix(type.getDirectory() + "/" + namespace + "/");
		ZipIndex.of(zip).forEachWithPrefix(root + directory + "/", (name, entry) -> {
			String path = name.substring(root.length());
			//#if MC >= 1.21.11
			Identifier id = Identifier.tryBuild(namespace, path);
			//#else
			//$ ResourceLocation id = ResourceLocation.tryBuild(namespace, path);
			//#endif
			if (id != null) output.accept(id, IoSupplier.create(zip, entry));
		});
	}
}
