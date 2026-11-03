package ggs.modid.client;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.platform.InputConstants;

import ggs.modid.Mod;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Stores named hotbar profiles and applies them to the vanilla "key.hotbar.1..9" key mappings.
 *
 * A profile is simply 9 key names (one per hotbar slot). Applying a profile rebinds the vanilla
 * hotbar keys, so vanilla scrolling, number keys, creative hotbar saving etc. keep working.
 *
 * If another keybind (for example "Drop Item" on Q) uses a key the active profile needs, that
 * other keybind is unbound while the profile is active and given back when you switch away.
 */
public final class HotbarProfiles {
	public static final int SLOTS = 9;
	public static final String UNBOUND = "key.keyboard.unknown";

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final String[] SURVIVAL_KEYS = {"1", "2", "3", "4", "5", "6", "7", "8", "9"};
	private static final String[] PVP_KEYS = {"q", "z", "x", "c", "v", "r", "g", "y", "u"};

	/** One named set of 9 hotbar keys. Key names look like "key.keyboard.q" or "key.mouse.4". */
	public static final class Profile {
		public String name = "Profile";
		public List<String> keys = new ArrayList<>();
	}

	public static final class Config {
		public int active = 0;
		public List<Profile> profiles = new ArrayList<>();
		/** Keybinds we unbound to make room for the active profile: mapping name -> original key. */
		public Map<String, String> displaced = new LinkedHashMap<>();
		public transient boolean fresh;
	}

	private static Config config = defaults();
	private static Method setScreenMethod;

	private HotbarProfiles() {
	}

	// ---------------------------------------------------------------------------------------
	// Persistence
	// ---------------------------------------------------------------------------------------

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("hotbar_profiles.json");
	}

	public static void load() {
		Path path = file();
		Config loaded = null;
		if (Files.exists(path)) {
			try (Reader reader = Files.newBufferedReader(path)) {
				loaded = GSON.fromJson(reader, Config.class);
			} catch (Exception e) {
				Mod.LOGGER.error("Could not read {}, starting with defaults (old file kept as .broken)", path, e);
				try {
					Files.move(path, path.resolveSibling("hotbar_profiles.json.broken"), StandardCopyOption.REPLACE_EXISTING);
				} catch (IOException ignored) {
					// nothing more we can do
				}
			}
		}
		boolean fresh = loaded == null;
		if (fresh) {
			loaded = defaults();
		}
		sanitize(loaded);
		loaded.fresh = fresh;
		config = loaded;
		if (fresh) {
			save();
		}
	}

	public static void save() {
		Path path = file();
		try {
			Files.createDirectories(path.getParent());
			try (Writer writer = Files.newBufferedWriter(path)) {
				GSON.toJson(config, writer);
			}
		} catch (IOException e) {
			Mod.LOGGER.error("Could not save {}", path, e);
		}
	}

	private static Config defaults() {
		Config c = new Config();
		c.profiles.add(profileOf("Survival", SURVIVAL_KEYS));
		c.profiles.add(profileOf("PvP", PVP_KEYS));
		return c;
	}

	private static Profile profileOf(String name, String[] letters) {
		Profile p = new Profile();
		p.name = name;
		for (String letter : letters) {
			p.keys.add("key.keyboard." + letter);
		}
		return p;
	}

	private static void sanitize(Config c) {
		if (c.profiles == null) {
			c.profiles = new ArrayList<>();
		}
		c.profiles.removeIf(Objects::isNull);
		if (c.profiles.isEmpty()) {
			c.profiles.add(profileOf("Survival", SURVIVAL_KEYS));
		}
		for (Profile p : c.profiles) {
			if (p.name == null || p.name.isBlank()) {
				p.name = "Profile";
			}
			if (p.keys == null) {
				p.keys = new ArrayList<>();
			}
			while (p.keys.size() < SLOTS) {
				p.keys.add(UNBOUND);
			}
			while (p.keys.size() > SLOTS) {
				p.keys.remove(p.keys.size() - 1);
			}
			for (int i = 0; i < SLOTS; i++) {
				if (p.keys.get(i) == null) {
					p.keys.set(i, UNBOUND);
				}
			}
		}
		if (c.displaced == null) {
			c.displaced = new LinkedHashMap<>();
		}
		c.active = Math.max(0, Math.min(c.active, c.profiles.size() - 1));
	}

	// ---------------------------------------------------------------------------------------
	// Queries
	// ---------------------------------------------------------------------------------------

	public static List<Profile> profiles() {
		return config.profiles;
	}

	public static int activeIndex() {
		return config.active;
	}

	public static Profile active() {
		return config.profiles.get(config.active);
	}

	/** True only on the very first launch (no config file existed). */
	public static boolean isFresh() {
		return config.fresh;
	}

	public static void clearFresh() {
		config.fresh = false;
	}

	public static InputConstants.Key parseKey(String name) {
		if (name == null) {
			return InputConstants.UNKNOWN;
		}
		try {
			return InputConstants.getKey(name);
		} catch (RuntimeException e) {
			return InputConstants.UNKNOWN;
		}
	}

	public static boolean isUnbound(String name) {
		return name == null || name.equals(UNBOUND) || parseKey(name).equals(InputConstants.UNKNOWN);
	}

	public static Component keyLabel(String name) {
		if (isUnbound(name)) {
			return Component.translatable("hotbarprofiles.unbound");
		}
		return parseKey(name).getDisplayName();
	}

	// ---------------------------------------------------------------------------------------
	// Editing
	// ---------------------------------------------------------------------------------------

	private static String uniqueName(String base) {
		String candidate = base;
		int n = 2;
		while (nameTaken(candidate)) {
			candidate = base + " " + n++;
		}
		return candidate;
	}

	private static boolean nameTaken(String name) {
		for (Profile p : config.profiles) {
			if (p.name.equalsIgnoreCase(name)) {
				return true;
			}
		}
		return false;
	}

	/** Creates a new profile with keys 1-9 and returns its index. */
	public static int create() {
		Profile p = profileOf(uniqueName("New Profile"), SURVIVAL_KEYS);
		config.profiles.add(p);
		save();
		return config.profiles.size() - 1;
	}

	/** Copies a profile and returns the index of the copy. */
	public static int duplicate(int index) {
		Profile source = config.profiles.get(index);
		Profile copy = new Profile();
		copy.name = uniqueName(source.name + " Copy");
		copy.keys = new ArrayList<>(source.keys);
		config.profiles.add(index + 1, copy);
		if (config.active > index) {
			config.active++;
		}
		save();
		return index + 1;
	}

	/** Deletes a profile (the last one cannot be deleted). Returns the index to select afterwards. */
	public static int delete(Minecraft mc, int index) {
		if (config.profiles.size() <= 1) {
			return index;
		}
		boolean wasActive = index == config.active;
		config.profiles.remove(index);
		if (wasActive) {
			config.active = Math.max(0, Math.min(index, config.profiles.size() - 1));
			applyActive(mc);
		} else if (index < config.active) {
			config.active--;
		}
		save();
		return Math.max(0, Math.min(index, config.profiles.size() - 1));
	}

	public static void setKey(Minecraft mc, int profileIndex, int slot, String keyName) {
		config.profiles.get(profileIndex).keys.set(slot, keyName);
		save();
		if (profileIndex == config.active) {
			applyActive(mc);
		}
	}

	// ---------------------------------------------------------------------------------------
	// Switching / applying
	// ---------------------------------------------------------------------------------------

	/** Reads the vanilla hotbar keys back into the active profile (picks up edits made in Controls). */
	public static void captureActive(Minecraft mc) {
		Options options = mc.options;
		if (options == null) {
			return;
		}
		Profile profile = active();
		for (int i = 0; i < SLOTS; i++) {
			profile.keys.set(i, options.keyHotbarSlots[i].saveString());
		}
		save();
	}

	public static void switchTo(Minecraft mc, int index, boolean notify) {
		if (index < 0 || index >= config.profiles.size()) {
			return;
		}
		if (index != config.active) {
			captureActive(mc);
			config.active = index;
			applyActive(mc);
		}
		if (notify) {
			notifySwitch(mc);
		}
	}

	public static void cycle(Minecraft mc, int delta) {
		int n = config.profiles.size();
		switchTo(mc, Math.floorMod(config.active + delta, n), true);
	}

	/** Rebinds the vanilla hotbar keys to the active profile. Safe to call repeatedly. */
	public static void applyActive(Minecraft mc) {
		Options options = mc.options;
		if (options == null) {
			return;
		}
		Profile profile = active();

		Map<String, KeyMapping> byName = new HashMap<>();
		for (KeyMapping mapping : options.keyMappings) {
			byName.put(mapping.getName(), mapping);
		}

		// 1. Give back keys the previous profile took away (unless the player rebound them since).
		for (Map.Entry<String, String> entry : config.displaced.entrySet()) {
			KeyMapping mapping = byName.get(entry.getKey());
			if (mapping != null && mapping.isUnbound()) {
				mapping.setKey(parseKey(entry.getValue()));
			}
		}
		config.displaced.clear();

		// 2. Point the nine hotbar mappings at the profile's keys.
		Set<KeyMapping> hotbar = Collections.newSetFromMap(new IdentityHashMap<>());
		Set<String> used = new HashSet<>();
		for (int i = 0; i < SLOTS; i++) {
			KeyMapping mapping = options.keyHotbarSlots[i];
			hotbar.add(mapping);
			InputConstants.Key key = parseKey(profile.keys.get(i));
			mapping.setKey(key);
			if (!key.equals(InputConstants.UNKNOWN)) {
				used.add(mapping.saveString());
			}
		}

		// 3. Any other keybind sitting on one of those keys is unbound while this profile is active.
		for (KeyMapping mapping : options.keyMappings) {
			if (hotbar.contains(mapping) || mapping.isUnbound()) {
				continue;
			}
			String current = mapping.saveString();
			if (used.contains(current)) {
				config.displaced.put(mapping.getName(), current);
				mapping.setKey(InputConstants.UNKNOWN);
			}
		}

		KeyMapping.resetMapping();
		options.save();
		save();
	}

	private static void notifySwitch(Minecraft mc) {
		SystemToast.addOrUpdate(
				mc.getToastManager(),
				SystemToast.SystemToastId.NARRATOR_TOGGLE,
				Component.translatable("hotbarprofiles.toast.title"),
				Component.literal(active().name));
	}

	// ---------------------------------------------------------------------------------------
	// Screens
	// ---------------------------------------------------------------------------------------

	/**
	 * Opens a screen (null closes the current one). Minecraft renamed setScreen to setScreenAndShow
	 * in 26.2, so this looks the method up by name to work on either.
	 */
	public static void openScreen(Minecraft mc, Screen screen) {
		try {
			if (setScreenMethod == null) {
				try {
					setScreenMethod = Minecraft.class.getMethod("setScreenAndShow", Screen.class);
				} catch (NoSuchMethodException e) {
					setScreenMethod = Minecraft.class.getMethod("setScreen", Screen.class);
				}
			}
			setScreenMethod.invoke(mc, screen);
		} catch (ReflectiveOperationException e) {
			Mod.LOGGER.error("Could not open screen", e);
		}
	}
}
