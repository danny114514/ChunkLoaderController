package com.danny.chunkloader.gui;

import com.danny.chunkloader.ChunkLoaderPlugin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;

/**
 * GUI 事件监听。
 *
 * <p><b>必须 cancel 一切点击与拖拽</b>：这些 GUI 是「图标面板」而不是真正的容器。
 * 不 cancel 的话玩家可以把物品拿出来、或者把自己的东西塞进空槽，
 * 造成物品复制/丢失。
 *
 * <p><b>线程</b>：Folia 上 inventory 事件在玩家所属 region 线程触发，
 * 因此这里可以安全访问玩家对象，但不能跨 region 访问别的世界/区块。
 */
// InventoryView#getTitle() 被标记 deprecated（Adventure 迁移），但它是判断
// 「这个 GUI 是不是我们的」唯一可靠手段：不能只靠槽位数量（撞车概率高），
// 也不能只靠会话表（玩家可能同时被别处打开同类界面）。精确抑制。
@SuppressWarnings("deprecation")
public final class GuiListener implements Listener {

    private final ChunkLoaderPlugin plugin;

    public GuiListener(ChunkLoaderPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        String title = event.getView().getTitle();
        boolean map = ChunkMapGui.isOurTitle(title);
        boolean mine = MyChunksGui.isOurTitle(title);
        if (!map && !mine) {
            return;
        }

        // 无论点在哪（含玩家背包区），只要视图属于我们，就禁止移动物品
        event.setCancelled(true);

        // 只处理上层的槽位；点玩家自己背包不做任何业务
        if (event.getClickedInventory() == null
                || !event.getClickedInventory().equals(event.getView().getTopInventory())) {
            return;
        }

        int slot = event.getRawSlot();
        Inventory top = event.getView().getTopInventory();
        boolean rightClick = event.isRightClick();
        boolean leftClick = event.isLeftClick();

        // 中键/Shift 之类的操作不当作升级降级
        if (!leftClick && !rightClick) {
            return;
        }

        try {
            if (map) {
                plugin.mapGui().handleClick(player, top, slot, rightClick);
            } else {
                plugin.myChunksGui().handleClick(player, top, slot, rightClick);
            }
        } catch (RuntimeException e) {
            plugin.getLogger().log(java.util.logging.Level.WARNING,
                    "处理 GUI 点击时出错（玩家 " + player.getName() + " 槽位 " + slot + "）", e);
            player.sendMessage("§c操作出错，详见服务端日志。");
        }
    }

    /**
     * 禁止拖拽：Shift-点击、拖动分堆都可能绕过单点 cancel。
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onDrag(InventoryDragEvent event) {
        String title = event.getView().getTitle();
        if (ChunkMapGui.isOurTitle(title) || MyChunksGui.isOurTitle(title)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        String title = event.getView().getTitle();
        // 把「被关闭的界面实例」传给 GUI，让它判断是否该清会话。
        //
        // 不能无条件按玩家清理：player.openInventory(新界面) 会先关闭旧界面并触发
        // 这个事件，如果此时新会话已经登记，无条件移除就会把新会话删掉 ——
        // 表现为「打开正常，但之后每次点击都报会话丢失」。
        Inventory closed = event.getInventory();
        if (ChunkMapGui.isOurTitle(title)) {
            plugin.mapGui().onClose(player, closed);
        } else if (MyChunksGui.isOurTitle(title)) {
            plugin.myChunksGui().onClose(player, closed);
        }
    }

    /**
     * 玩家下线时清理会话。
     *
     * <p>虽然 {@link InventoryCloseEvent} 通常也会触发，但依赖它清内存是不够的
     * （玩家断线、被踢、服务端关闭等路径不保证都走 close）。这里传 {@code null}
     * 表示「无视界面实例，强制清理」。
     */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        plugin.mapGui().onClose(event.getPlayer(), null);
        plugin.myChunksGui().onClose(event.getPlayer(), null);
        // 彩蛋的冷却记录也一起清掉，否则 Map 会随「上线过的人数」无限增长
        plugin.easterEgg().forget(event.getPlayer().getUniqueId());
    }
}
