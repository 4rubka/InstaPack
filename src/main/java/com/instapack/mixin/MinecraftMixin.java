package com.instapack.mixin;

import com.instapack.Session;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
abstract class MinecraftMixin {
	@Inject(method = "tick", at = @At("HEAD"))
	private void instapack$tick(CallbackInfo ci) {
		Session.tick((Minecraft) (Object) this);
	}
}
