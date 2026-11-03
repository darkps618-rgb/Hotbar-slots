package ggs.modid.client;

import com.mojang.blaze3d.platform.InputConstants;

import ggs.modid.Mod;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

public class ModClient implements ClientModInitializer {
	private static final int DIRECT_KEYS = 4;

	private static KeyMapping openMenuKey;
	private static KeyMapping nextProfileKey;
	private static KeyMapping previousProfileKey;
	private static final KeyMapping[] directKeys = new KeyMapping[DIRECT_KEYS];

	private static boolean started;

	@Override
	public void onInitializeClient() {
		HotbarProfiles.load();

		KeyMapping.Category category = KeyMapping.Category.register(Mod.id("main"));

		openMenuKey = register("key.hotbarprofiles.open", GLFW.GLFW_KEY_K, category);
		nextProfileKey = register("key.hotbarprofiles.next", GLFW.GLFW_KEY_RIGHT_BRACKET, category);
		previousProfileKey = register("key.hotbarprofiles.previous", GLFW.GLFW_KEY_LEFT_BRACKET, category);
		for (int i = 0; i < DIRECT_KEYS; i++) {
			// Unbound by default: bind them in Controls to jump straight to the 1st..4th profile in the list.
			directKeys[i] = register("key.hotbarprofiles.profile_" + (i + 1), GLFW.GLFW_KEY_UNKNOWN, category);
		}

		ClientTickEvents.END_CLIENT_TICK.register(ModClient::onTick);
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
			if (started) {
				HotbarProfiles.captureActive(client);
			}
		});
	}

	private static KeyMapping register(String translationKey, int glfwKey, KeyMapping.Category category) {
		return KeyMappingHelper.registerKeyMapping(
				new KeyMapping(translationKey, InputConstants.Type.KEYSYM, glfwKey, category));
	}

	private static void onTick(Minecraft client) {
		if (client.options == null) {
			return;
		}

		// Options are loaded by now, so apply the saved profile once, on the first tick.
		if (!started) {
			started = true;
			if (HotbarProfiles.isFresh()) {
				// First launch: remember the player's current hotbar keys as the starting profile.
				HotbarProfiles.captureActive(client);
				HotbarProfiles.clearFresh();
			}
			HotbarProfiles.applyActive(client);
		}

		while (openMenuKey.consumeClick()) {
			HotbarProfiles.openScreen(client, new HotbarProfilesScreen(null));
		}
		while (nextProfileKey.consumeClick()) {
			HotbarProfiles.cycle(client, 1);
		}
		while (previousProfileKey.consumeClick()) {
			HotbarProfiles.cycle(client, -1);
		}
		for (int i = 0; i < DIRECT_KEYS; i++) {
			while (directKeys[i].consumeClick()) {
				HotbarProfiles.switchTo(client, i, true);
			}
		}
	}
}
