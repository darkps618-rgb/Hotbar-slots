package ggs.modid.client;

import java.util.List;

import com.mojang.blaze3d.platform.InputConstants;

import ggs.modid.client.HotbarProfiles.Profile;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** Menu for creating, renaming, switching and editing hotbar profiles. */
public class HotbarProfilesScreen extends Screen {
	private static final int SLOTS = HotbarProfiles.SLOTS;
	private static final int COLUMN_WIDTH = 148;
	private static final int COLUMN_GAP = 8;

	private final Screen parent;
	private final Button[] keyButtons = new Button[SLOTS];

	/** Profile currently shown in the editor (not necessarily the active one). */
	private int selected;
	/** Slot waiting for a key press, or -1. */
	private int listening = -1;
	private boolean confirmDelete;

	private EditBox nameBox;
	private StringWidget statusWidget;
	private Button deleteButton;
	private Button activateButton;

	public HotbarProfilesScreen(Screen parent) {
		super(Component.translatable("hotbarprofiles.screen.title"));
		this.parent = parent;
		// Pick up any hotbar key changes made in the vanilla Controls screen.
		HotbarProfiles.captureActive(Minecraft.getInstance());
		this.selected = HotbarProfiles.activeIndex();
	}

	private List<Profile> profiles() {
		return HotbarProfiles.profiles();
	}

	private Profile current() {
		return profiles().get(selected);
	}

	@Override
	protected void init() {
		int cx = this.width / 2;
		int left = cx - (COLUMN_WIDTH * 2 + COLUMN_GAP) / 2;
		int fullWidth = COLUMN_WIDTH * 2 + COLUMN_GAP;

		// Title
		this.addRenderableWidget(new StringWidget(left, 12, fullWidth, 9, this.title, this.font).alignCenter());

		// [<]  [ profile name ]  [>]
		int y = 28;
		this.addRenderableWidget(Button.builder(Component.literal("<"), b -> select(selected - 1))
				.bounds(left, y, 20, 20).build());
		this.nameBox = new EditBox(this.font, left + 24, y, fullWidth - 48, 20, this.title);
		this.nameBox.setMaxLength(24);
		this.nameBox.setValue(current().name);
		this.nameBox.setResponder(this::onNameChanged);
		this.addRenderableWidget(this.nameBox);
		this.addRenderableWidget(Button.builder(Component.literal(">"), b -> select(selected + 1))
				.bounds(left + fullWidth - 20, y, 20, 20).build());

		// [New] [Duplicate] [Delete] [Set Active]
		y += 26;
		int bw = (fullWidth - 3 * 4) / 4;
		this.addRenderableWidget(Button.builder(Component.translatable("hotbarprofiles.button.new"), b -> onNew())
				.bounds(left, y, bw, 20).build());
		this.addRenderableWidget(Button.builder(Component.translatable("hotbarprofiles.button.duplicate"), b -> onDuplicate())
				.bounds(left + (bw + 4), y, bw, 20).build());
		this.deleteButton = this.addRenderableWidget(Button.builder(Component.translatable("hotbarprofiles.button.delete"), b -> onDelete())
				.bounds(left + 2 * (bw + 4), y, bw, 20).build());
		this.activateButton = this.addRenderableWidget(Button.builder(Component.translatable("hotbarprofiles.button.activate"), b -> onActivate())
				.bounds(left + 3 * (bw + 4), y, bw, 20).build());

		// Status line
		y += 26;
		this.statusWidget = this.addRenderableWidget(new StringWidget(left, y, fullWidth, 9, Component.empty(), this.font).alignCenter());

		// 9 slot rows in two columns (slots 1-5 left, 6-9 right)
		int gridTop = y + 16;
		int rowHeight = Math.max(21, Math.min(24, (this.height - gridTop - 34) / 5));
		for (int slot = 0; slot < SLOTS; slot++) {
			int col = slot / 5;
			int row = slot % 5;
			int x = left + col * (COLUMN_WIDTH + COLUMN_GAP);
			int rowY = gridTop + row * rowHeight;
			final int s = slot;

			this.addRenderableWidget(new StringWidget(x, rowY + 6, 38, 9,
					Component.translatable("hotbarprofiles.slot", slot + 1), this.font).alignLeft());
			this.keyButtons[slot] = this.addRenderableWidget(Button.builder(Component.empty(), b -> startListening(s))
					.bounds(x + 40, rowY, 84, 20).build());
			this.addRenderableWidget(Button.builder(Component.literal("X"), b -> assign(s, HotbarProfiles.UNBOUND))
					.bounds(x + 128, rowY, 20, 20).build());
		}

		// Done
		int doneY = Math.min(gridTop + 5 * rowHeight + 6, this.height - 24);
		this.addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> this.onClose())
				.bounds(cx - 100, doneY, 200, 20).build());

		refresh();
	}

	// ---------------------------------------------------------------------------------------
	// Actions
	// ---------------------------------------------------------------------------------------

	private void select(int index) {
		this.selected = Math.floorMod(index, profiles().size());
		this.listening = -1;
		this.confirmDelete = false;
		this.rebuildWidgets();
	}

	private void onNameChanged(String text) {
		if (!text.isBlank()) {
			current().name = text.strip();
		}
	}

	private void onNew() {
		this.selected = HotbarProfiles.create();
		this.listening = -1;
		this.confirmDelete = false;
		this.rebuildWidgets();
	}

	private void onDuplicate() {
		this.selected = HotbarProfiles.duplicate(this.selected);
		this.listening = -1;
		this.confirmDelete = false;
		this.rebuildWidgets();
	}

	private void onDelete() {
		if (!this.confirmDelete) {
			this.confirmDelete = true;
			refresh();
			return;
		}
		this.confirmDelete = false;
		this.listening = -1;
		this.selected = HotbarProfiles.delete(this.minecraft, this.selected);
		this.rebuildWidgets();
	}

	private void onActivate() {
		HotbarProfiles.switchTo(this.minecraft, this.selected, true);
		this.listening = -1;
		this.confirmDelete = false;
		this.rebuildWidgets();
	}

	private void startListening(int slot) {
		this.listening = slot;
		this.confirmDelete = false;
		refresh();
	}

	private void assign(int slot, String keyName) {
		this.listening = -1;
		this.confirmDelete = false;
		HotbarProfiles.setKey(this.minecraft, this.selected, slot, keyName);
		refresh();
	}

	// ---------------------------------------------------------------------------------------
	// Input
	// ---------------------------------------------------------------------------------------

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (this.listening >= 0) {
			if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
				this.listening = -1;
				refresh();
			} else {
				assign(this.listening, InputConstants.Type.KEYSYM.getOrCreate(event.key()).getName());
			}
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (this.listening >= 0) {
			// Middle / side mouse buttons can be bound. Left and right click just cancel and act normally.
			if (event.button() >= 2) {
				assign(this.listening, InputConstants.Type.MOUSE.getOrCreate(event.button()).getName());
				return true;
			}
			this.listening = -1;
			refresh();
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public void onClose() {
		HotbarProfiles.save();
		HotbarProfiles.openScreen(this.minecraft, this.parent);
	}

	// ---------------------------------------------------------------------------------------
	// Labels
	// ---------------------------------------------------------------------------------------

	private void refresh() {
		for (int i = 0; i < SLOTS; i++) {
			if (this.keyButtons[i] != null) {
				this.keyButtons[i].setMessage(slotLabel(i));
			}
		}
		this.statusWidget.setMessage(statusText());
		this.deleteButton.setMessage(this.confirmDelete
				? Component.translatable("hotbarprofiles.button.delete_confirm").withStyle(ChatFormatting.RED)
				: Component.translatable("hotbarprofiles.button.delete"));
		this.deleteButton.active = profiles().size() > 1;
		this.activateButton.active = this.selected != HotbarProfiles.activeIndex();
	}

	private Component slotLabel(int slot) {
		if (this.listening == slot) {
			return Component.literal("> ? <").withStyle(ChatFormatting.YELLOW);
		}
		Profile profile = current();
		String name = profile.keys.get(slot);
		Component label = HotbarProfiles.keyLabel(name);
		if (!HotbarProfiles.isUnbound(name)) {
			for (int other = 0; other < SLOTS; other++) {
				if (other != slot && name.equals(profile.keys.get(other))) {
					// The same key is used by two slots in this profile.
					return label.copy().withStyle(ChatFormatting.RED);
				}
			}
		}
		return label;
	}

	private Component statusText() {
		if (this.listening >= 0) {
			return Component.translatable("hotbarprofiles.status.listening", this.listening + 1).withStyle(ChatFormatting.YELLOW);
		}
		if (this.selected == HotbarProfiles.activeIndex()) {
			return Component.translatable("hotbarprofiles.status.active").withStyle(ChatFormatting.GREEN);
		}
		return Component.translatable("hotbarprofiles.status.inactive").withStyle(ChatFormatting.GRAY);
	}
}
