package com.instapack;

import java.util.concurrent.Executor;
//#if MC >= 1.21.11
import net.minecraft.util.Util;
//#else
//$ import net.minecraft.Util;
//#endif
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.LoadingOverlay;

final class Compat {
	private Compat() {}

	static boolean isLoading(Minecraft mc) {
		//#if MC >= 26.2
		return mc.gui.overlay() instanceof LoadingOverlay;
		//#else
		//$ return mc.getOverlay() instanceof LoadingOverlay;
		//#endif
	}

	static Executor backgroundExecutor() {
		return Util.backgroundExecutor();
	}
}
