package com.terraformersmc.modmenu.util.mod;

import com.terraformersmc.modmenu.gui.ModsScreen;
import com.terraformersmc.modmenu.util.DrawingUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.FormattedCharSequence;

import java.util.Set;

public class ModBadgeRenderer {
	protected int startX, startY, badgeX, badgeY, badgeMax;
	protected Mod mod;
	protected Minecraft client;
	protected final ModsScreen screen;

	public ModBadgeRenderer(int startX, int startY, int endX, Mod mod, ModsScreen screen) {
		this.startX = startX;
		this.startY = startY;
		this.badgeMax = endX;
		this.mod = mod;
		this.screen = screen;
		this.client = Minecraft.getInstance();
	}

	public void draw(GuiGraphics DrawContext, int mouseX, int mouseY) {
		this.badgeX = startX;
		this.badgeY = startY;
		Set<Mod.Badge> badges = mod.getBadges();
		badges.forEach(badge -> drawBadge(DrawContext, badge, mouseX, mouseY));
	}

	public void drawBadge(GuiGraphics DrawContext, Mod.Badge badge, int mouseX, int mouseY) {
		this.drawBadge(DrawContext, badge.getText().getVisualOrderText(), badge.getOutlineColor(), badge.getFillColor(), mouseX, mouseY);
	}

	public void drawBadge(GuiGraphics DrawContext, FormattedCharSequence text, int outlineColor, int fillColor, int mouseX, int mouseY) {
		int width = client.font.width(text) + 6;
		if (badgeX + width < badgeMax) {
			DrawingUtil.drawBadge(DrawContext, badgeX, badgeY, width, text, outlineColor, fillColor, 0xCACACA);
			badgeX += width + 3;
		}
	}

	public Mod getMod() {
		return mod;
	}
}
