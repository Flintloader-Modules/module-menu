package com.terraformersmc.modmenu.gui;

import com.google.common.base.Joiner;
import com.mojang.blaze3d.systems.RenderSystem;
import com.terraformersmc.modmenu.ModMenu;
import com.terraformersmc.modmenu.config.ModMenuConfig;
import com.terraformersmc.modmenu.config.ModMenuConfigManager;
import com.terraformersmc.modmenu.gui.widget.DescriptionListWidget;
import com.terraformersmc.modmenu.gui.widget.LegacyTexturedButtonWidget;
import com.terraformersmc.modmenu.gui.widget.ModListWidget;
import com.terraformersmc.modmenu.gui.widget.entries.ModListEntry;
import com.terraformersmc.modmenu.util.DrawingUtil;
import com.terraformersmc.modmenu.util.ModMenuScreenTexts;
import com.terraformersmc.modmenu.util.TranslationUtil;
import com.terraformersmc.modmenu.util.mod.Mod;
import com.terraformersmc.modmenu.util.mod.ModBadgeRenderer;
import net.flintloader.punch.api.PunchLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.jar.JarFile;
import java.util.stream.Collectors;

public class ModsScreen extends Screen {
	private static final ResourceLocation FILTERS_BUTTON_LOCATION = new ResourceLocation(ModMenu.MOD_ID, "textures/gui/filters_button.png");
	private static final ResourceLocation CONFIGURE_BUTTON_LOCATION = new ResourceLocation(ModMenu.MOD_ID, "textures/gui/configure_button.png");

	private static final Logger LOGGER = LoggerFactory.getLogger("Mod Menu | ModsScreen");
	private final Screen previousScreen;
	private ModListEntry selected;
	private ModBadgeRenderer modBadgeRenderer;
	private double scrollPercent = 0;
	private boolean keepFilterOptionsShown = false;
	private boolean init = false;
	private boolean filterOptionsShown = false;
	private static final int RIGHT_PANE_Y = 48;
	private int paneWidth;
	private int rightPaneX;
	private int searchBoxX;
	private int filtersX;
	private int filtersWidth;
	private int searchRowWidth;
	public final Set<String> showModChildren = new HashSet<>();

	private EditBox searchBox;
	private @Nullable AbstractWidget filtersButton;
	private AbstractWidget sortingButton;
	private AbstractWidget librariesButton;
	private ModListWidget modList;
	private @Nullable AbstractWidget configureButton;
	private AbstractWidget websiteButton;
	private AbstractWidget issuesButton;
	private DescriptionListWidget descriptionListWidget;
	private AbstractWidget modsFolderButton;
	private AbstractWidget doneButton;

	public final Map<String, Boolean> modHasConfigScreen = new HashMap<>();
	public final Map<String, Throwable> modScreenErrors = new HashMap<>();

	public ModsScreen(Screen previousScreen) {
		super(ModMenuScreenTexts.TITLE);
		this.previousScreen = previousScreen;
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		if (modList.isMouseOver(mouseX, mouseY)) {
			return this.modList.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
		}
		if (descriptionListWidget.isMouseOver(mouseX, mouseY)) {
			return this.descriptionListWidget.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
		}
		return false;
	}

	@Override
	protected void init() {
		for (Mod mod : ModMenu.MODS.values()) {
			String id = mod.getId();
			if (!modHasConfigScreen.containsKey(id)) {
				try {
					Screen configScreen = ModMenu.getConfigScreen(id, this);
					modHasConfigScreen.put(id, configScreen != null);
				} catch (java.lang.NoClassDefFoundError e) {
					LOGGER.warn("The '" + id + "' mod config screen is not available because " + e.getLocalizedMessage() + " is missing.");
					modScreenErrors.put(id, e);
					modHasConfigScreen.put(id, false);
				} catch (Throwable e) {
					LOGGER.error("Error from mod '" + id + "'", e);
					modScreenErrors.put(id, e);
					modHasConfigScreen.put(id, false);
				}
			}
		}

		int paneY = ModMenuConfig.CONFIG_MODE.getValue() ? 48 : 48 + 19;
		this.paneWidth = this.width / 2 - 8;
		this.rightPaneX = this.width - this.paneWidth;

		// Mod list (initialized early for updateFiltersX)
		this.modList = new ModListWidget(this.minecraft, this.paneWidth, this.height - paneY - 36, paneY, ModMenuConfig.COMPACT_LIST.getValue() ? 23 : 36, this.modList, this);
		this.modList.setX(0);

		// Search box
		int filtersButtonSize = (ModMenuConfig.CONFIG_MODE.getValue() ? 0 : 22);
		int searchWidthMax = this.paneWidth - 32 - filtersButtonSize;
		int searchBoxWidth = ModMenuConfig.CONFIG_MODE.getValue() ? Math.min(200, searchWidthMax) : searchWidthMax;

		this.searchBoxX = this.paneWidth / 2 - searchBoxWidth / 2 - filtersButtonSize / 2;

		this.searchBox = new EditBox(this.font, this.searchBoxX, 22, searchBoxWidth, 20, this.searchBox, ModMenuScreenTexts.SEARCH);
		this.searchBox.setResponder(text -> {
			this.modList.filter(text, false);
		});

		// Filters button
		Component sortingText = ModMenuConfig.SORTING.getButtonText();
		Component librariesText = ModMenuConfig.SHOW_LIBRARIES.getButtonText();

		int sortingWidth = font.width(sortingText) + 20;
		int librariesWidth = font.width(librariesText) + 20;

		this.filtersWidth = librariesWidth + sortingWidth + 2;
		this.searchRowWidth = this.searchBoxX + searchBoxWidth + 22;

		this.updateFiltersX();

		if (!ModMenuConfig.CONFIG_MODE.getValue()) {
			this.filtersButton = LegacyTexturedButtonWidget.legacyTexturedBuilder(ModMenuScreenTexts.TOGGLE_FILTER_OPTIONS, button -> {
					this.setFilterOptionsShown(!this.filterOptionsShown);
				})
				.position(this.paneWidth / 2 + searchBoxWidth / 2 - 20 / 2 + 2, 22)
				.size(20, 20)
				.uv(0, 0, 20)
				.texture(FILTERS_BUTTON_LOCATION, 32, 64)
				.build();

			this.filtersButton.setTooltip(Tooltip.create(ModMenuScreenTexts.TOGGLE_FILTER_OPTIONS));
		}

		// Sorting button
		this.sortingButton = Button.builder(sortingText, button -> {
				ModMenuConfig.SORTING.cycleValue();
				ModMenuConfigManager.save();
				modList.reloadFilters();
				button.setMessage(ModMenuConfig.SORTING.getButtonText());
			})
			.pos(this.filtersX, 45)
			.size(sortingWidth, 20)
			.build();

		// Show libraries button
		this.librariesButton = Button.builder(librariesText, button -> {
				ModMenuConfig.SHOW_LIBRARIES.toggleValue();
				ModMenuConfigManager.save();
				modList.reloadFilters();
				button.setMessage(ModMenuConfig.SHOW_LIBRARIES.getButtonText());
			})
			.pos(this.filtersX + sortingWidth + 2, 45)
			.size(librariesWidth, 20)
			.build();

		// Configure button
		if (!ModMenuConfig.HIDE_CONFIG_BUTTONS.getValue()) {
			this.configureButton = LegacyTexturedButtonWidget.legacyTexturedBuilder(CommonComponents.EMPTY, button -> {
					final String id = Objects.requireNonNull(selected).getMod().getId();
					if (modHasConfigScreen.get(id)) {
						Screen configScreen = ModMenu.getConfigScreen(id, this);
						minecraft.setScreen(configScreen);
					} else {
						button.active = false;
					}
				})
				.position(width - 24, RIGHT_PANE_Y)
				.size(20, 20)
				.uv(0, 0, 20)
				.texture(CONFIGURE_BUTTON_LOCATION, 32, 64)
				.build();
		}

		// Website button
		int urlButtonWidths = this.paneWidth / 2 - 2;
		int cappedButtonWidth = Math.min(urlButtonWidths, 200);

		this.websiteButton = Button.builder(ModMenuScreenTexts.WEBSITE, button -> {
				final Mod mod = Objects.requireNonNull(selected).getMod();
				this.minecraft.setScreen(new ConfirmLinkScreen(confirmed -> {
					if (confirmed) {
						Util.getPlatform().openUri(mod.getWebsite());
					}
					this.minecraft.setScreen(this);
				}, mod.getWebsite(), false));
			})
			.pos(this.rightPaneX + (urlButtonWidths / 2) - (cappedButtonWidth / 2), RIGHT_PANE_Y + 36)
			.size(Math.min(urlButtonWidths, 200), 20)
			.build();

		// Issues button
		this.issuesButton = Button.builder(ModMenuScreenTexts.ISSUES, button -> {
				final Mod mod = Objects.requireNonNull(selected).getMod();
				this.minecraft.setScreen(new ConfirmLinkScreen(confirmed -> {
					if (confirmed) {
						Util.getPlatform().openUri(mod.getIssueTracker());
					}
					this.minecraft.setScreen(this);
				}, mod.getIssueTracker(), false));
			})
			.pos(this.rightPaneX + urlButtonWidths + 4 + (urlButtonWidths / 2) - (cappedButtonWidth / 2), RIGHT_PANE_Y + 36)
			.size(Math.min(urlButtonWidths, 200), 20)
			.build();

		// Description list
		this.descriptionListWidget = new DescriptionListWidget(this.minecraft, this.paneWidth, this.height - RIGHT_PANE_Y - 96, RIGHT_PANE_Y + 60, font.lineHeight + 1, this);
		this.descriptionListWidget.setX(this.rightPaneX);

		// Mods folder button
		this.modsFolderButton = Button.builder(ModMenuScreenTexts.MODS_FOLDER, button -> {
				Util.getPlatform().openUri(PunchLoader.getInstance().getGameDir().resolve("modules").toUri());
			})
			.pos(this.width / 2 - 154, this.height - 28)
			.size(150, 20)
			.build();

		// Done button
		this.doneButton = Button.builder(CommonComponents.GUI_DONE, button -> {
				minecraft.setScreen(previousScreen);
			})
			.pos(this.width / 2 + 4, this.height - 28)
			.size(150, 20)
			.build();

		// Initialize data
		modList.reloadFilters();
		this.setFilterOptionsShown(this.keepFilterOptionsShown ? this.filterOptionsShown : false);

		// Add children
		this.addWidget(this.searchBox);
		this.setInitialFocus(this.searchBox);

		if (this.filtersButton != null) {
			this.addRenderableWidget(this.filtersButton);
		}

		this.addRenderableWidget(this.sortingButton);
		this.addRenderableWidget(this.librariesButton);
		this.addWidget(this.modList);

		if (this.configureButton != null) {
			this.addRenderableWidget(this.configureButton);
		}

		this.addRenderableWidget(this.websiteButton);
		this.addRenderableWidget(this.issuesButton);
		this.addWidget(this.descriptionListWidget);
		this.addRenderableWidget(this.modsFolderButton);
		this.addRenderableWidget(this.doneButton);

		this.init = true;
		this.keepFilterOptionsShown = true;
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		return super.keyPressed(keyCode, scanCode, modifiers) || this.searchBox.keyPressed(keyCode, scanCode, modifiers);
	}

	@Override
	public boolean charTyped(char chr, int keyCode) {
		return this.searchBox.charTyped(chr, keyCode);
	}

	@Override
	public void render(GuiGraphics DrawContext, int mouseX, int mouseY, float delta) {
		super.render(DrawContext, mouseX, mouseY, delta);
		ModListEntry selectedEntry = selected;
		if (selectedEntry != null) {
			this.descriptionListWidget.render(DrawContext, mouseX, mouseY, delta);
		}
		this.modList.render(DrawContext, mouseX, mouseY, delta);
		this.searchBox.render(DrawContext, mouseX, mouseY, delta);
		RenderSystem.disableBlend();
		DrawContext.drawString(this.font, this.title, this.modList.getWidth() / 2, 8, 16777215);
		if (!ModMenuConfig.DISABLE_DRAG_AND_DROP.getValue()) {
			DrawContext.drawString(this.font, ModMenuScreenTexts.DROP_INFO_LINE_1, this.width - this.modList.getWidth() / 2, RIGHT_PANE_Y / 2 - minecraft.font.lineHeight - 1, ChatFormatting.GRAY.getColor());
			DrawContext.drawString(this.font, ModMenuScreenTexts.DROP_INFO_LINE_2, this.width - this.modList.getWidth() / 2, RIGHT_PANE_Y / 2 + 1, ChatFormatting.GRAY.getColor());
		}
		if (!ModMenuConfig.CONFIG_MODE.getValue()) {
			Component fullModCount = this.computeModCountText(true);
			if (!ModMenuConfig.CONFIG_MODE.getValue() && this.updateFiltersX()) {
				if (this.filterOptionsShown) {
					if (!ModMenuConfig.SHOW_LIBRARIES.getValue() || font.width(fullModCount) <= this.filtersX - 5) {
						DrawContext.drawString(font, fullModCount.getVisualOrderText(), this.searchBoxX, 52, 0xFFFFFF, false);
					} else {
						DrawContext.drawString(font, computeModCountText(false).getVisualOrderText(), this.searchBoxX, 46, 0xFFFFFF, false);
						DrawContext.drawString(font, computeLibraryCountText().getVisualOrderText(), this.searchBoxX, 57, 0xFFFFFF, false);
					}
				} else {
					if (!ModMenuConfig.SHOW_LIBRARIES.getValue() || font.width(fullModCount) <= modList.getWidth() - 5) {
						DrawContext.drawString(font, fullModCount.getVisualOrderText(), this.searchBoxX, 52, 0xFFFFFF, false);
					} else {
						DrawContext.drawString(font, computeModCountText(false).getVisualOrderText(), this.searchBoxX, 46, 0xFFFFFF, false);
						DrawContext.drawString(font, computeLibraryCountText().getVisualOrderText(), this.searchBoxX, 57, 0xFFFFFF, false);
					}
				}
			}
		}
		if (selectedEntry != null) {
			Mod mod = selectedEntry.getMod();
			int x = this.rightPaneX;
			if ("java".equals(mod.getId())) {
				DrawingUtil.drawRandomVersionBackground(mod, DrawContext, x, RIGHT_PANE_Y, 32, 32);
			}
			RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
			RenderSystem.enableBlend();
			DrawContext.blit(this.selected.getIconTexture(), x, RIGHT_PANE_Y, 0.0F, 0.0F, 32, 32, 32, 32);
			RenderSystem.disableBlend();
			int lineSpacing = font.lineHeight + 1;
			int imageOffset = 36;
			Component name = Component.literal(mod.getTranslatedName());
			FormattedText trimmedName = name;
			int maxNameWidth = this.width - (x + imageOffset);
			if (font.width(name) > maxNameWidth) {
				FormattedText ellipsis = FormattedText.of("...");
				trimmedName = FormattedText.composite(font.substrByWidth(name, maxNameWidth - font.width(ellipsis)), ellipsis);
			}
			DrawContext.drawString(font, Language.getInstance().getVisualOrder(trimmedName), x + imageOffset, RIGHT_PANE_Y + 1, 0xFFFFFF, false);
			if (mouseX > x + imageOffset && mouseY > RIGHT_PANE_Y + 1 && mouseY < RIGHT_PANE_Y + 1 + font.lineHeight && mouseX < x + imageOffset + font.width(trimmedName)) {
				this.setTooltipForNextRenderPass(ModMenuScreenTexts.modIdTooltip(mod.getId()));
			}
			if (this.init || modBadgeRenderer == null || modBadgeRenderer.getMod() != mod) {
				modBadgeRenderer = new ModBadgeRenderer(x + imageOffset + this.minecraft.font.width(trimmedName) + 2, RIGHT_PANE_Y, width - 28, selectedEntry.mod, this);
				this.init = false;
			}
			if (!ModMenuConfig.HIDE_BADGES.getValue()) {
				modBadgeRenderer.draw(DrawContext, mouseX, mouseY);
			}
			if (mod.isReal()) {
				DrawContext.drawString(font, mod.getPrefixedVersion(), x + imageOffset, RIGHT_PANE_Y + 2 + lineSpacing, 0x808080, false);
			}
			String authors;
			List<String> names = mod.getAuthors();

			if (!names.isEmpty()) {
				if (names.size() > 1) {
					authors = Joiner.on(", ").join(names);
				} else {
					authors = names.get(0);
				}
				DrawingUtil.drawWrappedString(DrawContext, I18n.get("modmenu.authorPrefix", authors), x + imageOffset, RIGHT_PANE_Y + 2 + lineSpacing * 2, this.paneWidth - imageOffset - 4, 1, 0x808080);
			}
		}
	}

	@Override
	public void renderBackground(GuiGraphics context, int mouseX, int mouseY, float delta) {
		this.renderDirtBackground(context);
	}

	private Component computeModCountText(boolean includeLibs) {
		int[] rootMods = formatModCount(ModMenu.ROOT_MODS.values().stream().filter(mod -> !mod.isHidden() && !mod.getBadges().contains(Mod.Badge.LIBRARY)).map(Mod::getId).collect(Collectors.toSet()));

		if (includeLibs && ModMenuConfig.SHOW_LIBRARIES.getValue()) {
			int[] rootLibs = formatModCount(ModMenu.ROOT_MODS.values().stream().filter(mod -> !mod.isHidden() && mod.getBadges().contains(Mod.Badge.LIBRARY)).map(Mod::getId).collect(Collectors.toSet()));
			return TranslationUtil.translateNumeric("modmenu.showingModsLibraries", rootMods, rootLibs);
		} else {
			return TranslationUtil.translateNumeric("modmenu.showingMods", rootMods);
		}
	}

	private Component computeLibraryCountText() {
		if (ModMenuConfig.SHOW_LIBRARIES.getValue()) {
			int[] rootLibs = formatModCount(ModMenu.ROOT_MODS.values().stream().filter(mod -> !mod.isHidden() && mod.getBadges().contains(Mod.Badge.LIBRARY)).map(Mod::getId).collect(Collectors.toSet()));
			return TranslationUtil.translateNumeric("modmenu.showingLibraries", rootLibs);
		} else {
			return Component.literal(null);
		}
	}

	private int[] formatModCount(Set<String> set) {
		int visible = this.modList.getDisplayedCountFor(set);
		int total = set.size();
		if (visible == total) {
			return new int[]{total};
		}
		return new int[]{visible, total};
	}

	@Override
	public void onClose() {
		this.modList.close();
		this.minecraft.setScreen(this.previousScreen);
	}

	private void setFilterOptionsShown(boolean filterOptionsShown) {
		this.filterOptionsShown = filterOptionsShown;

		this.sortingButton.visible = filterOptionsShown;
		this.librariesButton.visible = filterOptionsShown;
	}

	public ModListEntry getSelectedEntry() {
		return selected;
	}

	public void updateSelectedEntry(ModListEntry entry) {
		if (entry != null) {
			this.selected = entry;

			if (this.configureButton != null) {
				String modId = selected.getMod().getId();

				this.configureButton.active = modHasConfigScreen.get(modId);
				this.configureButton.visible = selected != null && modHasConfigScreen.get(modId) || modScreenErrors.containsKey(modId);

				if (modScreenErrors.containsKey(modId)) {
					Throwable e = modScreenErrors.get(modId);
					this.configureButton.setTooltip(Tooltip.create(ModMenuScreenTexts.configureError(modId, e)));
				} else {
					this.configureButton.setTooltip(Tooltip.create(ModMenuScreenTexts.CONFIGURE));
				}
			}

			this.websiteButton.visible = true;
			this.websiteButton.active = selected.getMod().getWebsite() != null;

			this.issuesButton.visible = true;
			this.issuesButton.active = selected.getMod().getIssueTracker() != null;
		}
	}

	public double getScrollPercent() {
		return scrollPercent;
	}

	public void updateScrollPercent(double scrollPercent) {
		this.scrollPercent = scrollPercent;
	}

	public String getSearchInput() {
		return this.searchBox.getValue();
	}

	private boolean updateFiltersX() {
		if ((this.filtersWidth + font.width(this.computeModCountText(true)) + 20) >= this.searchRowWidth && ((this.filtersWidth + font.width(computeModCountText(false)) + 20) >= this.searchRowWidth || (this.filtersWidth + font.width(this.computeLibraryCountText()) + 20) >= this.searchRowWidth)) {
			this.filtersX = this.paneWidth / 2 - this.filtersWidth / 2;
			return !filterOptionsShown;
		} else {
			this.filtersX = this.searchRowWidth - this.filtersWidth + 1;
			return true;
		}
	}

	@Override
	public void onFilesDrop(List<Path> paths) {
		Path modsDirectory = PunchLoader.getInstance().getGameDir().resolve("modules");

		// Filter out none mods
		List<Path> mods = paths.stream()
			.filter(ModsScreen::isFlintModule)
			.collect(Collectors.toList());

		if (mods.isEmpty()) {
			return;
		}

		String modList = mods.stream()
			.map(Path::getFileName)
			.map(Path::toString)
			.collect(Collectors.joining(", "));

		this.minecraft.setScreen(new ConfirmScreen((value) -> {
			if (value) {
				boolean allSuccessful = true;

				for (Path path : mods) {
					try {
						Files.copy(path, modsDirectory.resolve(path.getFileName()));
					} catch (IOException e) {
						LOGGER.warn("Failed to copy mod from {} to {}", path, modsDirectory.resolve(path.getFileName()));
						SystemToast.onPackCopyFailure(minecraft, path.toString());
						allSuccessful = false;
						break;
					}
				}

				if (allSuccessful) {
					SystemToast.add(minecraft.getToasts(), SystemToast.SystemToastId.PERIODIC_NOTIFICATION, ModMenuScreenTexts.DROP_SUCCESSFUL_LINE_1, ModMenuScreenTexts.DROP_SUCCESSFUL_LINE_1);
				}
			}
			this.minecraft.setScreen(this);
		}, ModMenuScreenTexts.DROP_CONFIRM, Component.literal(modList)));
	}

	private static boolean isFlintModule(Path mod) {
		try (JarFile jarFile = new JarFile(mod.toFile())) {
			return jarFile.getEntry("flintmodule.json") != null;
		} catch (IOException e) {
			return false;
		}
	}

	public Map<String, Boolean> getModHasConfigScreen() {
		return this.modHasConfigScreen;
	}
}
