package com.danny.chunkloader.gui;

import com.danny.chunkloader.ChunkLoaderPlugin;
import com.danny.chunkloader.command.LevelText;
import com.danny.chunkloader.model.ChunkControl;
import com.danny.chunkloader.model.ChunkState;
import com.danny.chunkloader.service.ChunkControlService;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

/**
 * GUI 视图 B：我的受控区块。
 *
 * <p><b>核心设计约束</b>：这里可以<b>远程管理已有区块</b>（改弱/强、释放），
 * 但<b>不能远程新增</b>。新增必须走到那个区块开地图（视图 A）。
 *
 * <p>为什么故意不做远程新增：这是防「远程圈地」的关键设计。
 * 如果允许远程新增，一个玩家可以在几分钟内把地图上任意位置的区块全部占掉，
 * 而管理员无从判断他是否真的到过那里 —— 区块加载本身是有 CPU 成本的资源。
 *
 * <p><b>布局</b>：9×6 = 54 格。前 5 行 = 45 个区块格（与配额上限一致），
 * 第 6 行 = 导航栏。超过 45 个时（配额被调大）用翻页。
 */
// 理由同 ChunkMapGui：setDisplayName / setLore / getTitle / createInventory 的
// 三参重载都被标记 deprecated（Adventure 迁移），但在 1.20.1 上功能正常，
// 且是本插件统一 §-colour-code 文案的必要手段。精确抑制而非全局关闭。
@SuppressWarnings("deprecation")
public final class MyChunksGui {

    public static final String TITLE_PREFIX = "§8我的受控区块";

    private static final int COLUMNS = 9;
    private static final int LIST_ROWS = 5;
    private static final int ROWS = LIST_ROWS + 1;
    private static final int SIZE = ROWS * COLUMNS;
    private static final int PER_PAGE = LIST_ROWS * COLUMNS;   // 45

    private static final int SLOT_PREV = 45;
    private static final int SLOT_BACK = 47;
    private static final int SLOT_INFO = 49;
    private static final int SLOT_NEXT = 53;

    private final ChunkLoaderPlugin plugin;

    /**
     * 打开中的会话。
     *
     * <p>会话数据与「它属于哪个界面实例」由 {@link SessionStore} 一起管理 ——
     * 后者是纯逻辑、可单元测试的。这里踩过的坑见 SessionStore 的类注释。
     */
    private final SessionStore<PageSession> sessions = new SessionStore<>();

    public MyChunksGui(ChunkLoaderPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * 一页的上下文，用于把点击的槽位映射回具体区块。
     *
     * <p>不再包含界面引用：界面归属由 {@link SessionStore} 的 token 单独追踪，
     * 这样会话数据可以被测试直接构造，不需要 Bukkit 的 Inventory。
     */
    record PageSession(String worldName, int page, List<ChunkControl> entries) {
    }

    public static boolean isOurTitle(String title) {
        return title != null && title.startsWith(TITLE_PREFIX);
    }

    public void open(Player player, int page) {
        renderTo(player, page, null);
    }

    /**
     * 渲染并打开（或原地重绘）界面。
     *
     * @param reuse 若非 null，则直接重绘这个已有界面而不重新打开。
     *              这个参数是「切换状态后不重开界面」的关键实现（原因见
     *              {@link #handleClick} 内的说明）；重开界面既会触发一轮
     *              close/open 事件，也会让玩家看到屏幕闪一下。
     */
    private void renderTo(Player player, int page, Inventory reuse) {
        String worldName = player.getWorld().getName();
        List<ChunkControl> all;
        try {
            all = plugin.chunks().listByOwner(player.getUniqueId(), worldName);
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "读取受控区块失败", e);
            player.sendMessage("§c读取受控区块失败：" + e.getMessage());
            return;
        }

        int totalPages = Math.max(1, (all.size() + PER_PAGE - 1) / PER_PAGE);
        int safePage = Math.min(Math.max(1, page), totalPages);
        int from = (safePage - 1) * PER_PAGE;
        int to = Math.min(all.size(), from + PER_PAGE);
        List<ChunkControl> pageEntries = from < to ? all.subList(from, to) : List.of();

        Inventory inv = reuse;
        if (inv == null) {
            inv = Bukkit.createInventory(player, SIZE,
                    TITLE_PREFIX + " §7- " + worldName + " §8(" + safePage + "/" + totalPages + ")");
        }

        // 先登记（以【这个】界面实例为 token），再渲染，最后（仅首次）打开。
        // 打开时会关闭旧界面并触发关闭事件，但事件携带的是旧实例，
        // SessionStore 比对实例后不会误删这条新会话。
        sessions.put(player.getUniqueId(),
                new PageSession(worldName, safePage, new ArrayList<>(pageEntries)), inv);

        render(inv, player, worldName, pageEntries, safePage, totalPages, all.size());
        if (reuse == null) {
            player.openInventory(inv);
        } else {
            player.updateInventory();
        }
    }

    /**
     * 界面关闭时清理会话。
     *
     * <p>只有关闭的界面正是当前登记的那个时才移除（{@link SessionStore#removeIfCurrent}）。
     * 传 {@code null} 表示强制清理（玩家下线）。
     */
    public void onClose(Player player, Inventory closed) {
        sessions.removeIfCurrent(player.getUniqueId(), closed);
    }

    public PageSession session(UUID playerId) {
        return sessions.get(playerId);
    }

    private void render(Inventory inv, Player player, String worldName,
                        List<ChunkControl> entries, int page, int totalPages, int totalCount) {
        World world = Bukkit.getWorld(worldName);

        // ── 区块格 ────────────────────────────────────────────────────────
        for (int i = 0; i < entries.size() && i < PER_PAGE; i++) {
            ChunkControl c = entries.get(i);
            inv.setItem(i, buildChunkItem(player, world, c));
        }

        // ── 导航栏 ────────────────────────────────────────────────────────
        if (page > 1) {
            inv.setItem(SLOT_PREV, item(Material.ARROW, "§a◀ 上一页",
                    List.of("§7第 " + (page - 1) + " / " + totalPages + " 页")));
        }
        inv.setItem(SLOT_BACK, item(Material.CHEST, "§b返回地图",
                List.of("§7回到周围的区块地图",
                        "§8（新增区块必须在地图里点击）",
                        "",
                        "§a点击返回")));

        List<String> infoLore = new ArrayList<>();
        infoLore.add("§7世界：§f" + worldName);
        infoLore.add("§7共 §f" + totalCount + " §7个受控区块");
        infoLore.add("§7第 §f" + page + "§7 / " + totalPages + " §7页");
        infoLore.add("");
        infoLore.add("§a左键§7 升级 · §c右键§7 降级");
        infoLore.add("§8降级到底 = 释放该区块");
        infoLore.add("");
        infoLore.add("§e注意：这里只能管理已有区块。");
        infoLore.add("§e新增必须走到那个区块再打开地图。");
        inv.setItem(SLOT_INFO, item(Material.BOOK, "§f说明", infoLore));

        if (page < totalPages) {
            inv.setItem(SLOT_NEXT, item(Material.ARROW, "§a下一页 ▶",
                    List.of("§7第 " + (page + 1) + " / " + totalPages + " 页")));
        }

        // 空位填充
        GuiItems.fillEmpty(inv, SIZE);
    }

    private ItemStack buildChunkItem(Player player, World world, ChunkControl c) {
        // 三种有主状态各自的视觉：强=绿、弱=黄、保留=浅蓝。
        // 保留必须与「未控制」的灰区分开 —— 灰看起来像空地，
        // 而保留的区块其实是被占着的，别人拿不到。
        Material material = switch (c.state()) {
            case STRONG -> Material.LIME_STAINED_GLASS_PANE;
            case WEAK -> Material.YELLOW_STAINED_GLASS_PANE;
            case RESERVED -> Material.LIGHT_BLUE_STAINED_GLASS_PANE;
            default -> Material.GRAY_STAINED_GLASS_PANE;
        };
        String stateColour = switch (c.state()) {
            case STRONG -> "§a";
            case WEAK -> "§e";
            case RESERVED -> "§b";
            default -> "§7";
        };
        String name = "§f[" + c.chunkX() + ", " + c.chunkZ() + "] "
                + stateColour + c.state().displayName()
                + (c.hasGroup() ? " §8· 群组" : "");

        List<String> lore = new ArrayList<>();
        // 实际等级（Lore 第一行）
        if (world != null) {
            plugin.control().actualLevel(world, c.chunkX(), c.chunkZ()).ifPresentOrElse(
                    level -> lore.add("§7实际：" + LevelText.describeShort(level)),
                    () -> lore.add("§7实际：§8未加载"));
        } else {
            lore.add("§7实际：§c世界未加载");
        }

        lore.add("");
        lore.add("§8创建 " + relativeTime(c.createdAt()));
        lore.add("§8更新 " + relativeTime(c.updatedAt()));

        // 距离（便于玩家判断是否需要自己跑过去）
        if (world != null && world.equals(player.getWorld())) {
            int pcx = player.getLocation().getBlockX() >> 4;
            int pcz = player.getLocation().getBlockZ() >> 4;
            int dist = Math.max(Math.abs(pcx - c.chunkX()), Math.abs(pcz - c.chunkZ()));
            lore.add("§7距离：§f" + dist + " §7区块 §8(" + (dist * 16) + " 格)");
        }

        lore.add("");
        lore.add("§a左键§7 升级 · §c右键§7 降级");

        return item(material, name, lore);
    }

    /**
     * 处理点击。
     *
     * @return true 表示已处理（调用方应 cancel 事件）
     */
    public boolean handleClick(Player player, Inventory inv, int slot, boolean rightClick) {
        PageSession session = sessions.get(player.getUniqueId());
        if (session == null) {
            // 走到这里说明：玩家看到的是我们的界面，但点到的实例上没有会话。
            // 这会发生在「用 new 出来的实例登记会话」或「会话被误删」的情况下。
            // 记 warning 而不是静默失败 —— 但这个分支本身不该再被触发。
            plugin.getLogger().warning(
                    "GUI 会话丢失（玩家 " + player.getName() + " 槽位 " + slot + "）。已重开界面。");
            open(player, 1);
            return true;
        }

        // 玩家点的是旧界面（例如刚重绘过、客户端还没刷新）→ 忽略，不要误操作新区块
        if (inv != null && !sessions.isCurrentInventory(player.getUniqueId(), inv)) {
            return true;
        }

        // ── 导航 ──────────────────────────────────────────────────────────
        // 翻页与状态变更都走原地重绘（reuse = 当前界面），不重开界面。
        if (slot == SLOT_PREV) {
            renderTo(player, session.page() - 1, inv);
            return true;
        }
        if (slot == SLOT_NEXT) {
            renderTo(player, session.page() + 1, inv);
            return true;
        }
        if (slot == SLOT_BACK) {
            // 必须用插件单例：new 出来的实例没有会话。
            plugin.mapGui().open(player);
            return true;
        }
        if (slot == SLOT_INFO) {
            return true;
        }
        if (slot >= PER_PAGE) {
            return true;
        }

        // ── 区块格 ────────────────────────────────────────────────────────
        if (slot < 0 || slot >= session.entries().size()) {
            return true;
        }
        ChunkControl c = session.entries().get(slot);
        World world = Bukkit.getWorld(c.world());
        if (world == null) {
            player.sendMessage("§c该区块所在世界当前未加载：" + c.world());
            return true;
        }

        ChunkState current = c.state();
        ChunkState target = rightClick ? current.downgraded() : current.upgraded();
        if (target == current) {
            player.sendMessage("§e已经是" + current.displayName()
                    + (rightClick ? "，无法再降级。" : "，无法再升级。"));
            return true;
        }
        if (target == ChunkState.WEAK && !plugin.tickets().weakAvailable()) {
            player.sendMessage("§c弱加载不可用：" + plugin.tickets().weakUnavailableReason());
            return true;
        }

        // 远程管理已有区块：这是允许的。注意 bypass=false：
        // 这里不检查「是否在场」，因为改的是自己已有的区块。
        ChunkControlService.Outcome outcome = plugin.control().setState(
                player.getUniqueId(), player.getUniqueId(), world,
                c.chunkX(), c.chunkZ(), target, false);

        player.sendMessage((outcome.ok() ? "§a" : "§c") + outcome.message());

        if (outcome.ok() && outcome.changed()) {
            // 原地重绘，不重开界面。
            //
            // 为什么不能 open() 重开：openInventory 会先关闭旧界面、触发
            // InventoryCloseEvent。那不只是"闪一下"的问题 —— 曾经正是这个顺序
            // 导致新会话被旧界面的关闭事件删掉，于是第二次点击必报会话丢失。
            //
            // 释放区块后记录会消失，页码与槽位都可能变化，所以必须整页重绘
            // （不能只更新一格）：renderTo 会重新查库并重排。
            renderTo(player, session.page(), inv);
        }
        return true;
    }

    private static String relativeTime(long millis) {
        long diff = System.currentTimeMillis() - millis;
        if (diff < 0) {
            return "刚刚";
        }
        long minutes = diff / 60000L;
        if (minutes < 1) {
            return "刚刚";
        }
        if (minutes < 60) {
            return minutes + " 分钟前";
        }
        long hours = minutes / 60;
        if (hours < 24) {
            return hours + " 小时前";
        }
        return (hours / 24) + " 天前";
    }

    // 实现在 GuiItems（两个视图共用）。保留同名委托，调用点不必改动。
    private static ItemStack item(Material material, String name, List<String> lore) {
        return GuiItems.item(material, name, lore);
    }

    /** 统计：当前打开的视图 B 数量（调试用）。 */
    public int openCount() {
        return sessions.size();
    }
}
