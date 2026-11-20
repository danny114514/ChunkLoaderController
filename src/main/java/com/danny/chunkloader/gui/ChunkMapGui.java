package com.danny.chunkloader.gui;

import com.danny.chunkloader.ChunkLoaderPlugin;
import com.danny.chunkloader.command.LevelText;
import com.danny.chunkloader.model.ChunkControl;
import com.danny.chunkloader.model.ChunkGroup;
import com.danny.chunkloader.model.ChunkKey;
import com.danny.chunkloader.model.ChunkState;
import com.danny.chunkloader.service.ChunkControlService;
import com.danny.chunkloader.service.QuotaService;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;

/**
 * GUI 视图 A：周围区块地图。
 *
 * <p><b>布局</b>：9 列 × 6 行 = 54 格。前 5 行是区块格（9×5 = 45 个），
 * 第 6 行是信息栏。玩家所在区块在<b>第 2 行第 4 列</b>（0 基），横向 dx=-4..+4，
 * 纵向 dz=-2..+2。
 *
 * <p><b>为什么 9×5 而不是 7×7</b>：箱子容器固定 9 列 × 6 行 = 54 格。
 * 7×7 = 49 需要 7 行，超出容器上限，Bukkit 会直接抛异常。
 *
 * <p><b>显示口径</b>：
 * <ul>
 *   <li>名称 + 材质 = <b>玩家意图</b>（三种状态，稳定不闪）</li>
 *   <li>Lore 第一行 = <b>实际等级</b>（运行时读取，可能显示"正在应用…"）</li>
 *   <li>被遮蔽时用 Lore 说明，不改变名称与材质</li>
 * </ul>
 * <p>三者分离的理由：实际等级会因玩家走动而不断变化。如果让它决定材质，
 * 整张地图会持续闪烁，玩家反而无法确认自己设置的是哪一格。
 *
 * <p><b>刷新策略</b>：打开时读一次 + 手动刷新按钮 + 只回填刚点的那一格。
 * 禁止定时全量轮询 —— 45 格 × N 玩家会产生大量跨 region 调度。
 *
 * <p><b>线程</b>：事件在玩家所在 region 线程触发；ticket 读写本身 MT-safe，
 * 所以这里不需要额外切线程。绝不能在这里读区块方块数据。
 */
// ItemMeta#setDisplayName / setLore 与 InventoryView#getTitle 在 Paper 中被标记为
// deprecated（推动迁移到 Adventure Component），但在 1.20.1 上功能完全正常，
// 且是本插件统一使用 §-colour-code 的关键 —— 全面改 Adventure 只会让
// 45 个格子的动态文案构造复杂化，收益为零。这里显式抑制，而不是关掉整个
// deprecation lint（那会漏掉真正需要修的废弃 API，例如 JavaPlugin#getDescription）。
@SuppressWarnings("deprecation")
public final class ChunkMapGui {

    /** GUI 标题（含玩家名以便区分多个窗口）。 */
    public static final String TITLE_PREFIX = "§8区块地图";

    private static final int COLUMNS = 9;
    private static final int MAP_ROWS = 5;
    private static final int ROWS = MAP_ROWS + 1; // 5 行地图 + 1 行信息栏
    private static final int SIZE = ROWS * COLUMNS;

    /** 玩家所在格：第 2 行（0 基）、第 4 列（0 基）。 */
    private static final int CENTER_ROW = 2;
    private static final int CENTER_COL = 4;

    // ── 信息栏槽位（第 5 行）──────────────────────────────────────────────
    public static final int SLOT_LEGEND = 45;
    public static final int SLOT_VIEW_SWITCH = 46;
    public static final int SLOT_QUOTA = 47;
    public static final int SLOT_INFO = 48;
    public static final int SLOT_CLOSE = 49;
    public static final int SLOT_REFRESH = 52;

    private final ChunkLoaderPlugin plugin;

    /**
     * 打开中的会话。
     *
     * <p>会话数据与「它属于哪个界面实例」由 {@link SessionStore} 一起管理 ——
     * 后者是纯逻辑、可单元测试的。这里踩过的坑见 SessionStore 的类注释。
     */
    private final SessionStore<Session> sessions = new SessionStore<>();

    public ChunkMapGui(ChunkLoaderPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * 一次打开期间的会话信息。
     *
     * <p>不包含界面引用：界面归属由 {@link SessionStore} 的 token 单独追踪，
     * 这样会话数据可以被测试直接构造，不需要 Bukkit 的 Inventory。
     */
    public static final class Session {
        /** 打开时玩家所在的世界名（跨世界后 GUI 会失效，避免显示错位的坐标） */
        final String worldName;
        /** 打开时玩家所在区块（地图中心） */
        final int centerChunkX;
        final int centerChunkZ;

        Session(String worldName, int centerChunkX, int centerChunkZ) {
            this.worldName = worldName;
            this.centerChunkX = centerChunkX;
            this.centerChunkZ = centerChunkZ;
        }
    }

    /** 打开 GUI 并登记会话。 */
    public void open(Player player) {
        String worldName = player.getWorld().getName();
        int cx = player.getLocation().getBlockX() >> 4;
        int cz = player.getLocation().getBlockZ() >> 4;

        Inventory inv = Bukkit.createInventory(player, SIZE, TITLE_PREFIX + " §7- " + worldName);
        Session session = new Session(worldName, cx, cz);
        // 以这个界面实例为 token 登记；随后 openInventory 关闭旧界面时，
        // 关闭事件携带的是旧实例，SessionStore 比对后不会误删这条新会话。
        sessions.put(player.getUniqueId(), session, inv);

        renderAll(inv, player, session);
        player.openInventory(inv);
    }

    /**
     * 玩家关闭 GUI 时清理会话。
     *
     * <p>只有关闭的界面正是当前登记的那个时才移除（{@link SessionStore#removeIfCurrent}）。
     * 传 {@code null} 表示强制清理（玩家下线）。
     */
    public void onClose(Player player, Inventory closed) {
        sessions.removeIfCurrent(player.getUniqueId(), closed);
    }

    public Session session(UUID playerId) {
        return sessions.get(playerId);
    }

    /** 该 plot 是否是本插件的 GUI（标题前缀匹配）。 */
    public static boolean isOurTitle(String title) {
        return title != null && title.startsWith(TITLE_PREFIX);
    }

    /**
     * 全量渲染。只在打开与手动刷新时调用。
     */
    public void renderAll(Inventory inv, Player player, Session session) {
        try {
            renderMap(inv, player, session);
            renderInfoBar(inv, player, session);
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "渲染 GUI 失败", e);
        }
    }

    /**
     * 只回填单个区块格（点击后立即用）。
     *
     * <p>点一下只重算这一格，不做全量重绘 —— 全量意味着 45 次数据库查询 +
     * 45 次反射读等级，玩家每点一次都要付这个代价。
     */
    public void renderSingleChunk(Inventory inv, Player player, Session session,
                                  int chunkX, int chunkZ) {
        int slot = slotFor(session, chunkX, chunkZ);
        if (slot < 0) {
            return;
        }
        try {
            inv.setItem(slot, buildChunkItem(player, session, chunkX, chunkZ));
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.FINE, "回填单格失败", e);
        }
    }

    // ── 地图渲染 ────────────────────────────────────────────────────────────

    private void renderMap(Inventory inv, Player player, Session session) {
        for (int row = 0; row < MAP_ROWS; row++) {
            for (int col = 0; col < COLUMNS; col++) {
                int dx = col - CENTER_COL;
                int dz = row - CENTER_ROW;
                int chunkX = session.centerChunkX + dx;
                int chunkZ = session.centerChunkZ + dz;
                inv.setItem(row * COLUMNS + col, buildChunkItem(player, session, chunkX, chunkZ));
            }
        }
    }

    /** 区块坐标 → 槽位；不在 9×5 范围内返回 -1。 */
    private static int slotFor(Session session, int chunkX, int chunkZ) {
        int dx = chunkX - session.centerChunkX;
        int dz = chunkZ - session.centerChunkZ;
        int col = CENTER_COL + dx;
        int row = CENTER_ROW + dz;
        if (col < 0 || col >= COLUMNS || row < 0 || row >= MAP_ROWS) {
            return -1;
        }
        return row * COLUMNS + col;
    }

    /** 槽位 → 区块坐标；信息栏槽位返回 null。 */
    public static int[] chunkAtSlot(Session session, int slot) {
        if (slot < 0 || slot >= MAP_ROWS * COLUMNS) {
            return null;
        }
        int row = slot / COLUMNS;
        int col = slot % COLUMNS;
        int chunkX = session.centerChunkX + (col - CENTER_COL);
        int chunkZ = session.centerChunkZ + (row - CENTER_ROW);
        return new int[]{chunkX, chunkZ};
    }

    /**
     * 构造一个区块格。
     *
     * <p>名称 = <b>绝对区块坐标</b> + 意图状态；Lore 里给实际等级与方块坐标范围。
     *
     * <p><b>为什么用绝对坐标而不是相对坐标</b>：相对坐标有两个实际害处 ——
     * <ol>
     *   <li><b>中心格永远是 [+0, +0]</b>，看起来像"坐标算错了"，实测中被误判为 bug；</li>
     *   <li>玩家要核对某格是不是自己以为的那个区块时，必须心算
     *       "我脚下是 2,25，往右一格是 3,25"，而 F3 显示的是绝对坐标 ——
     *       两者对不上，无法直接验证。</li>
     * </ol>
     * <p>绝对坐标直接与 F3 的 {@code Chunk:} 行一致，一眼可核对。
     * 方位感由「中心格高亮 + 你在这里」提供，不需要靠数字表达。
     */
    private ItemStack buildChunkItem(Player player, Session session, int chunkX, int chunkZ) {
        String worldName = session.worldName;
        UUID uuid = player.getUniqueId();

        // ── 意图（来自数据库）─────────────────────────────────────────────
        ChunkState intended = ChunkState.UNCONTROLLED;
        Optional<ChunkControl> rec = plugin.control().find(worldName, chunkX, chunkZ);
        UUID owner = null;
        if (rec.isPresent()) {
            intended = rec.get().state();
            owner = rec.get().owner();
        }

        // ── 是否为地图中心（= 打开 GUI 时玩家所在区块）─────────────────────
        boolean isCenter = (chunkX == session.centerChunkX && chunkZ == session.centerChunkZ);

        // ── 材质按意图决定（稳定，不随实际等级闪动）───────────────────────
        Material material = switch (intended) {
            case UNCONTROLLED -> Material.GRAY_STAINED_GLASS_PANE;
            case WEAK -> Material.YELLOW_STAINED_GLASS_PANE;
            case STRONG -> Material.LIME_STAINED_GLASS_PANE;
            // 保留用浅蓝：与「未控制」的灰必须一眼区分开 ——
            // 灰色看起来像空地，会让人以为可以捡。
            case RESERVED -> Material.LIGHT_BLUE_STAINED_GLASS_PANE;
        };

        // ── 名称：[绝对区块X, 绝对区块Z] + 状态 ────────────────────────────
        boolean someoneElse = owner != null && !owner.equals(uuid);
        StringBuilder name = new StringBuilder("§f[").append(chunkX).append(", ").append(chunkZ).append("] ");
        if (isCenter) {
        // 中心格用箭头前缀标记"这格是我脚下"
            name.append("§b▶ §b你在这里§8 · ");
        }
        if (someoneElse) {
            name.append("§c").append(intended.displayName()).append(" §8（他人）");
        } else {
            name.append(colourOf(intended)).append(intended.displayName());
        }

        // ── Lore ──────────────────────────────────────────────────────────
        List<String> lore = new ArrayList<>();

        if (isCenter) {
            lore.add("§8（你现在就在这个区块里）");
            lore.add("");
        }

        // Lore 第一行给实际等级：名称与材质表达意图，Lore 表达真相
        Optional<Integer> actual = plugin.control().actualLevel(player.getWorld(), chunkX, chunkZ);
        if (actual.isPresent()) {
            int level = actual.get();
            lore.add("§7实际：" + LevelText.describeShort(level));

            // 遮蔽标记：弱加载被玩家视距抬到 31 时，名称仍显示"弱加载"，这里说明真相
            LevelText.shadowNote(intended, level).ifPresent(note ->
                    lore.add("§7" + note));

            // 意图与实际不一致时说明"正在应用"
            if (intended.isControlled() && !matchesIntended(intended, level)) {
                lore.add("§8§o正在应用…");
            }
                // 未控制格照实说明它为什么已加载（原版视距导致的，与本插件无关）
            if (intended == ChunkState.UNCONTROLLED) {
                lore.add("§8" + LevelText.uncontrolledNote(level));
            }
        } else {
            lore.add("§7实际：§8未加载");
            if (intended.isControlled()) {
                lore.add("§8§o正在应用…");
            }
        }

        lore.add("");
        // 方块坐标范围：区块坐标在名称里，这里给方块范围，
        // 方便和 F3 的 XYZ（方块坐标）直接对照。
        lore.add("§8方块 §7" + (chunkX << 4) + ".." + ((chunkX << 4) + 15)
                + "§8, §7" + (chunkZ << 4) + ".." + ((chunkZ << 4) + 15));

        // 操作提示（他人区块不给可操作暗示）
        if (someoneElse) {
            lore.add("§c该区块归他人所有，你无法修改");
        } else {
            lore.add("§a左键§7 升级 · §c右键§7 降级");
            lore.add("§8未控制 → 弱加载 → 强加载");
        }

        // 弱加载不可用时在 Lore 里明确说明，避免玩家以为是自己的操作没生效
        if (!plugin.tickets().weakAvailable() && intended != ChunkState.WEAK) {
            lore.add("§c弱加载当前不可用");
        }

        return item(material, name.toString(), lore);
    }

    private static boolean matchesIntended(ChunkState intended, int actualLevel) {
        return switch (intended) {
            case WEAK -> actualLevel == LevelText.WEAK || actualLevel <= LevelText.STRONG;
            case STRONG -> actualLevel <= LevelText.STRONG;
            // 保留态的正确表现就是「没加载」—— 原版视距内它本来会被加载，
            // 那种情况下不该显示「正在应用…」，那是正常现象不是延迟。
            case RESERVED -> true;
            case UNCONTROLLED -> actualLevel > LevelText.BORDER;
        };
    }

    private static String colourOf(ChunkState state) {
        return switch (state) {
            case UNCONTROLLED -> "§7";
            case WEAK -> "§e";
            case STRONG -> "§a";
            case RESERVED -> "§b";
        };
    }

    // ── 信息栏 ──────────────────────────────────────────────────────────────

    private void renderInfoBar(Inventory inv, Player player, Session session) {
        // [图例]
        inv.setItem(SLOT_LEGEND, item(Material.BOOK, "§6图例",
                List.of(
                        "§7格子名称是§f区块坐标§7，如 [2, 25]",
                        "§8与 F3 的 Chunk: 行一致，可直接对照",
                        "§8Lore 里另有该区块的方块坐标范围",
                        "",
                        "§7灰 §f未控制 §8- 无主，原版自动加载",
                        "§e黄 §f弱加载 §8(32) - 方块刻运行，§c实体冻结",
                        "§a绿 §f强加载 §8(31) - 实体刻与方块刻全运行",
                        "§b蓝 §f保留 §8- 不加载，但仍归其主人所有",
                        "",
                        "§7「未控制」和「保留」的区别很重要：",
                        "§8§o未控制 = 谁都能占；保留 = 别人抢不走。",
                        "",
                        "§7Lore 里的「实际」是运行时真实等级，",
                        "§7可能与你的设置不同（玩家附近本来就已加载）。",
                        "",
                        "§7边界区块（33）仅加载不计算，",
                        "§8§o不可手动设置 —— 由原版自动产生。",
                        "",
                        "§7左键升级 · 右键降级",
                        "§7Shift+左键 = 加入/移出群组",
                        "",
                        "§b▶ §7= 打开地图时你所在的区块")));

        // [视图切换] → 视图 B
        inv.setItem(SLOT_VIEW_SWITCH, item(Material.CHEST, "§b我的受控区块",
                List.of("§7查看并远程管理你已控制的区块",
                        "§8（新增仍需走到那里开地图）",
                        "",
                        "§a点击切换视图")));

        // 配额格只显示当前世界用量：全服数字与玩家决策无关，放进 Lore 供参考
        try {
            QuotaService.Usage u = plugin.quota().usage(player.getUniqueId(),
                    player.getWorld().getName(), plugin.cfg());
            List<String> lore = new ArrayList<>();
            lore.add("§7受控总量：§f" + u.worldTotal() + " §8/ " + u.maxTotal());
            lore.add("§7强加载：§f" + u.worldStrong() + " §8/ " + u.maxStrong());
            lore.add("");
            lore.add("§8全服（供参考）：");
            lore.add("§8  总量 " + u.globalTotal() + "/" + u.globalMaxTotal()
                    + " · 强 " + u.globalStrong() + "/" + u.globalMaxStrong());
            String over = plugin.quota().overQuotaWarning(player.getUniqueId(),
                    player.getWorld().getName(), plugin.cfg());
            if (over != null) {
                lore.add("");
                lore.add("§e⚠ 已超额，无法新增");
            }
            inv.setItem(SLOT_QUOTA, item(Material.EMERALD, "§a本世界配额", lore));
        } catch (SQLException e) {
            inv.setItem(SLOT_QUOTA, item(Material.BARRIER, "§c配额读取失败",
                    List.of("§8" + e.getMessage())));
        }

        // [当前区块信息]
        int cx = player.getLocation().getBlockX() >> 4;
        int cz = player.getLocation().getBlockZ() >> 4;
        Optional<Integer> here = plugin.control().actualLevel(player.getWorld(), cx, cz);
        Optional<ChunkControl> hereRec = plugin.control().find(player.getWorld().getName(), cx, cz);
        List<String> infoLore = new ArrayList<>();
        infoLore.add("§7区块 §f[" + cx + ", " + cz + "]");
        infoLore.add("§7意图：§f" + hereRec.map(c -> c.state().displayName()).orElse("未控制"));
        infoLore.add("§7实际：§f" + here.map(LevelText::describe).orElse("未加载"));
        inv.setItem(SLOT_INFO, item(Material.COMPASS, "§f你所在的区块", infoLore));

        // [关闭]
        inv.setItem(SLOT_CLOSE, item(Material.RED_STAINED_GLASS_PANE, "§c关闭", List.of()));

        // 手动刷新入口：不做定时刷新，避免 45 格 × N 玩家产生持续跨 region 调度
        inv.setItem(SLOT_REFRESH, item(Material.SUNFLOWER, "§e刷新实际等级",
                List.of("§7重新读取全部 45 格的真实等级",
                        "§8不做定时刷新（避免拖慢服务端）",
                        "",
                        "§a点击刷新")));

        // 空位填充，避免玩家往里塞东西的错觉
        GuiItems.fillEmpty(inv, SIZE);
    }

    // ── 构造物品 ────────────────────────────────────────────────────────────
    // 实现在 GuiItems（两个视图共用）。这里保留同名委托，让几百个调用点不必改动。

    private static ItemStack item(Material material, String name, List<String> lore) {
        return GuiItems.item(material, name, lore);
    }

    /**
     * 处理点击。
     *
     * @param rightClick 是否右键（降级）
     * @param shiftClick 是否按住 Shift（加入群组，与改加载状态互不干扰）
     * @return true 表示已处理（调用方应 cancel 事件）
     */
    public boolean handleClick(Player player, Inventory inv, int slot,
                              boolean rightClick, boolean shiftClick) {
        Session session = sessions.get(player.getUniqueId());
        if (session == null) {
            // 走到这里意味着：玩家看到的是我们的界面，但点到的实例上没有会话。
            // 记 warning 而不是静默失败 —— 但这个分支本身不该再被触发。
            plugin.getLogger().warning(
                    "GUI 会话丢失（玩家 " + player.getName() + " 槽位 " + slot + "）。已重开界面。");
            open(player);
            return true;
        }
        // 点的是旧界面（重绘过程中的中间态）→ 忽略，避免对错位的格子误操作
        if (inv != null && !sessions.isCurrentInventory(player.getUniqueId(), inv)) {
            return true;
        }
        // 世界变了：地图坐标失效，直接重开
        if (!session.worldName.equals(player.getWorld().getName())) {
            open(player);
            return true;
        }

        // ── 信息栏按钮 ────────────────────────────────────────────────────
        switch (slot) {
            case SLOT_CLOSE -> {
                player.closeInventory();
                return true;
            }
            case SLOT_REFRESH -> {
                renderAll(inv, player, session);
                player.updateInventory();
                return true;
            }
            case SLOT_VIEW_SWITCH -> {
                // ⚠ 必须走插件的单例，绝不能 new。
                // 会话（sessions）存在实例字段里 —— new 出来的实例没有玩家会话，
                // GuiListener 用 plugin 单例去查就会 session==null、点击静默失效。
                plugin.myChunksGui().open(player, 1);
                return true;
            }
            case SLOT_LEGEND, SLOT_QUOTA, SLOT_INFO -> {
                return true;   // 纯展示
            }
            default -> {
                // 落到区块格处理
            }
        }

        int[] chunk = chunkAtSlot(session, slot);
        if (chunk == null) {
            return true;
        }
        int chunkX = chunk[0];
        int chunkZ = chunk[1];

        // ── Shift+左键 = 加入群组 ─────────────────────────────────────────
        // 普通左右键仍然改加载状态，Shift 是独立入口 ——
        // 这样「管理加载」和「管理归属」不会被同一次点击混淆。
        if (shiftClick && !rightClick) {
            return handleShiftClick(player, session, chunkX, chunkZ);
        }

        // ── 归属检查：他人区块不可动 ───────────────────────────────────────
        Optional<ChunkControl> rec = plugin.control()
                .find(session.worldName, chunkX, chunkZ);
        if (rec.isPresent() && !rec.get().isOwnedBy(player.getUniqueId())) {
            ChunkControl other = rec.get();
            if (other.state() == ChunkState.RESERVED) {
                player.sendMessage("§c该区块被其他玩家保留着（不加载，但对方没有放弃它）。");
            } else {
                player.sendMessage("§c该区块已被其他玩家控制，你无法修改。");
            }
            return true;
        }

        // ── 左键升级 / 右键降级 ──────────────────────────────────────────────
        ChunkState current = rec.map(ChunkControl::state).orElse(ChunkState.UNCONTROLLED);
        ChunkState target = rightClick ? current.downgraded() : current.upgraded();

        // 已经封顶/封底：给出反馈而不是静默无反应
        if (target == current) {
            player.sendMessage("§e该区块已经是" + current.displayName()
                    + (rightClick ? "，无法再降级。" : "，无法再升级。"));
            return true;
        }

        // 弱加载不可用时不要静默失败
        if (target == ChunkState.WEAK && !plugin.tickets().weakAvailable()) {
            player.sendMessage("§c弱加载不可用：" + plugin.tickets().weakUnavailableReason());
            player.sendMessage("§7强加载不受影响，可以设为强加载。");
            return true;
        }

        ChunkControlService.Outcome outcome = plugin.control().setState(
                player.getUniqueId(), player.getUniqueId(),
                player.getWorld(), chunkX, chunkZ, target, false);

        if (outcome.ok()) {
            // 只重绘这一格，不整屏重绘（详见 renderSingleChunk 的说明）
            renderSingleChunk(inv, player, session, chunkX, chunkZ);
            renderInfoBar(inv, player, session);
            player.updateInventory();
            player.sendMessage((outcome.changed() ? "§a" : "§7") + outcome.message());

            // 首次设弱加载时提示实体冻结，避免玩家种了半小时地才发现掉落物不动
            if (outcome.changed() && target == ChunkState.WEAK && plugin.cfg().warnOnWeakSet()) {
                player.sendMessage("§e§o弱加载：实体冻结 —— 掉落物不动、怪物不动、刷怪塔不产出");
            }
        } else {
            player.sendMessage("§c" + outcome.message());
        }
        return true;
    }

    /**
     * Shift+左键：把区块加入群组。
     *
     * <p>交互设计：普通左右键改「加载状态」，Shift+左键改「归属/分组」。
     * 两者分开是因为它们是不同维度的事 —— 把区块加进群组不该顺手改变它的加载状态。</p>
     *
     * <p>如果玩家一个群组都没有，直接告诉他先建一个，而不是静默失败。</p>
     */
    private boolean handleShiftClick(Player player, Session session, int chunkX, int chunkZ) {
        List<ChunkGroup> groups = plugin.groups().listGroups(player.getUniqueId());
        if (groups.isEmpty()) {
            player.sendMessage("§c你还没有任何群组。先创建一个：§f/cl group create <名字>");
            player.sendMessage("§7建好之后，Shift+左键 就能把区块加进去。");
            return true;
        }
        // 有多个群组时用最后一个（最近创建的）作为默认目标，并在消息里说清楚。
        // 这样单群组的玩家不需要任何额外操作，多群组的玩家也总能知道发生了什么。
        ChunkGroup target = groups.get(groups.size() - 1);

        // 已经有群组归属的区块：Shift+左键 改成「移出」
        Optional<ChunkControl> rec = plugin.control().find(session.worldName, chunkX, chunkZ);
        if (rec.isPresent() && rec.get().hasGroup()
                && rec.get().isOwnedBy(player.getUniqueId())) {
            var outcome = plugin.groups().removeChunk(
                    player.getUniqueId(), player.getWorld(), chunkX, chunkZ);
            player.sendMessage((outcome.ok() ? "§a" : "§c") + outcome.message());
            if (outcome.ok() && outcome.changed()) {
                renderSingleChunk(player.getOpenInventory().getTopInventory(), player,
                        session, chunkX, chunkZ);
                player.updateInventory();
            }
            return true;
        }

        var outcome = plugin.groups().addChunk(
                player.getUniqueId(), target.name(), player.getWorld(), chunkX, chunkZ);
        player.sendMessage((outcome.ok() ? "§a" : "§c") + outcome.message());
        if (groups.size() > 1) {
            player.sendMessage("§7（目标群组是最近创建的「" + target.name()
                    + "」；想指定别的请用 /cl group add <名字>）");
        }
        if (outcome.ok() && outcome.changed()) {
            renderSingleChunk(player.getOpenInventory().getTopInventory(), player,
                    session, chunkX, chunkZ);
            player.updateInventory();
        }
        return true;
    }

    /** 供视图 B 使用：把某区块从会话中刷新（远程操作后不重开窗口）。 */
    public void refreshChunkInAnySession(Player player, int chunkX, int chunkZ) {
        Session s = sessions.get(player.getUniqueId());
        if (s == null) {
            return;
        }
        if (player.getOpenInventory().getTopInventory().getSize() != SIZE) {
            return;
        }
        renderSingleChunk(player.getOpenInventory().getTopInventory(), player, s, chunkX, chunkZ);
        player.updateInventory();
    }

    /** 统计：当前打开的 GUI 数量（调试用）。 */
    public int openCount() {
        return sessions.size();
    }

    /**
     * GUI 布局的纯逻辑自检（不依赖服务端状态，供 {@code /cl admin selftest} 调用）。
     *
     * <p>为什么值得单独测：槽位映射一旦错位，玩家点「自己这一格」会操作到
     * 相邻的另一个区块 —— 这种错误在视觉上看不出来（格子挨着），
     * 但会造成实实在在的错误控制。而它是纯数学，可以脱离玩家验证。
     *
     * <p>检查三件事：
     * <ol>
     *   <li>45 个格子的 {@code slot → 坐标 → slot} 往返一致（映射可逆）</li>
     *   <li>中心格（第 2 行第 4 列）确实对应中心区块</li>
     *   <li>信息栏槽位不会被误判为地图格</li>
     * </ol>
     *
     * @return 失败时返回原因，通过则返回 {@code null}
     */
    public static String selfCheckLayout() {
        // inventory 传 null：布局自检只做坐标↔槽位的纯数学运算，与界面实例无关。
        Session probe = new Session("__layout_test__", 1000, -1000);

        // 1. 全部 45 格往返一致，且相对坐标范围正确
        int checked = 0;
        for (int slot = 0; slot < MAP_ROWS * COLUMNS; slot++) {
            int[] chunk = chunkAtSlot(probe, slot);
            if (chunk == null) {
                return "槽位 " + slot + " 在映射表内却返回 null";
            }
            int back = slotFor(probe, chunk[0], chunk[1]);
            if (back != slot) {
                return "槽位往返不一致: slot=" + slot + " → [" + chunk[0] + "," + chunk[1]
                        + "] → slot=" + back;
            }
            int dx = chunk[0] - probe.centerChunkX;
            int dz = chunk[1] - probe.centerChunkZ;
            if (dx < -4 || dx > 4 || dz < -2 || dz > 2) {
                return "槽位 " + slot + " 对应的相对坐标越界: [" + dx + "," + dz + "]";
            }
            checked++;
        }
        if (checked != 45) {
            return "地图格数量应为 45，实际 " + checked;
        }

        // 2. 中心格必须正好是中心区块（不然玩家点自己那一格会错位）
        int centerSlot = CENTER_ROW * COLUMNS + CENTER_COL;
        int[] centerChunk = chunkAtSlot(probe, centerSlot);
        if (centerChunk == null
                || centerChunk[0] != probe.centerChunkX
                || centerChunk[1] != probe.centerChunkZ) {
            return "中心格（槽位 " + centerSlot + "）未对应中心区块";
        }

        // 3. 信息栏槽位不得被当成地图格
        for (int slot = MAP_ROWS * COLUMNS; slot < SIZE; slot++) {
            if (chunkAtSlot(probe, slot) != null) {
                return "信息栏槽位 " + slot + " 被误判为地图格";
            }
            if (isMapSlot(slot)) {
                return "信息栏槽位 " + slot + " 被 isMapSlot 误判";
            }
        }

        // 4. 容器容量必须恰好装得下布局（54 格 = 9×6 是箱子上限）
        if (SIZE != 54) {
            return "容器容量应为 54，实际 " + SIZE + "（箱子只有 54 格）";
        }

        return null;
    }

    /**
     * 校验槽位是否属于地图区（供事件监听器提前 cancel）。
     */
    public static boolean isMapSlot(int slot) {
        return slot >= 0 && slot < MAP_ROWS * COLUMNS;
    }

    /** 世界名 + 区块 → ChunkKey，便于日志。 */
    static ChunkKey keyOf(Session session, int chunkX, int chunkZ) {
        return new ChunkKey(session.worldName, chunkX, chunkZ);
    }
}
