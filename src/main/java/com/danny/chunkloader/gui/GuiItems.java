package com.danny.chunkloader.gui;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

/**
 * GUI 物品构造工具。
 *
 * <p>两个视图（地图 / 我的受控区块）都需要构造带名称与 Lore 的图标，
 * 逻辑完全一致，所以集中在这里。
 *
 * <p><b>关于 {@code setDisplayName} / {@code setLore} 的 deprecation</b>：
 * Paper 正在推动迁移到 Adventure 的 {@code Component}。但在 1.20.1 上这两个方法
 * 功能完全正常，且本插件全用 {@code §} 颜色码 —— 45 个格子的动态文案用
 * {@code Component} 构造只会更长更难读。因此这里<b>精确抑制</b>该警告，
 * 而不是在整个工程关掉 deprecation lint（那会漏掉真正需要修的东西）。
 */
@SuppressWarnings("deprecation")
public final class GuiItems {

    /** 空位填充物：用深色玻璃板，避免玩家以为能往里塞东西。 */
    public static final ItemStack FILLER =
            item(Material.BLACK_STAINED_GLASS_PANE, " ", List.of());

    private GuiItems() {
    }

    /**
     * 构造一个带名称与 Lore 的物品。
     *
     * @param lore 为空时不设置 Lore（避免有些客户端显示空的 Lore 行）
     */
    public static ItemStack item(Material material, String name, List<String> lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            if (lore != null && !lore.isEmpty()) {
                meta.setLore(lore);
            }
            stack.setItemMeta(meta);
        }
        return stack;
    }

    /** 把 {@code size} 范围内的空槽全部填上填充物。 */
    public static void fillEmpty(org.bukkit.inventory.Inventory inv, int size) {
        for (int slot = 0; slot < size; slot++) {
            if (inv.getItem(slot) == null) {
                inv.setItem(slot, FILLER.clone());
            }
        }
    }
}
