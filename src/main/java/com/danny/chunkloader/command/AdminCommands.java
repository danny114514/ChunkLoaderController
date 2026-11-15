package com.danny.chunkloader.command;

import com.danny.chunkloader.ChunkLoaderPlugin;
import com.danny.chunkloader.model.ChunkControl;
import com.danny.chunkloader.model.ChunkState;
import com.danny.chunkloader.service.QuotaService;
import com.danny.chunkloader.storage.AuditLogDao;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;

import static com.danny.chunkloader.command.CommandSupport.PAGE_SIZE;
import static com.danny.chunkloader.command.CommandSupport.require;

/**
 * 管理员命令：{@code /cl admin *}。
 *
 * <p>拆成独立类的理由：管理员命令的能力边界与玩家命令完全不同 ——
 * 玩家只能碰脚下，管理员可以指定任意玩家、任意坐标、任意世界。
 * 混在一个类里会让人难以确认「这条命令到底谁能用」。
 *
 * <p>每个子命令都在入口处显式检查自己的权限节点，不做隐含的继承。
 */
public final class AdminCommands {

    private static final DateTimeFormatter LOG_TIME =
            DateTimeFormatter.ofPattern("MM-dd HH:mm");

    private final ChunkLoaderPlugin plugin;

    public AdminCommands(ChunkLoaderPlugin plugin) {
        this.plugin = plugin;
    }

    /** 路由 {@code /cl admin <sub>}。 */
    boolean dispatch(CommandSender sender, String[] args) {
        if (args.length < 2) {
            printHelp(sender);
            return true;
        }
        return switch (args[1].toLowerCase(java.util.Locale.ROOT)) {
            case "selftest" -> selftest(sender);
            case "status" -> status(sender);
            case "list" -> list(sender, args);
            case "set" -> set(sender, args);
            case "release" -> release(sender, args);
            case "log" -> log(sender, args);
            case "reload" -> reload(sender);
            default -> {
                sender.sendMessage("§c未知管理员子命令: " + args[1]);
                printHelp(sender);
                yield true;
            }
        };
    }

    // ══════════════════════════════════════════════════════════════════════
    //  /cl admin selftest
    // ══════════════════════════════════════════════════════════════════════

    /**
     * 实测 ticket 是否真正生效。
     *
     * <p>这是本插件最重要的诊断命令：弱加载依赖 NMS 反射，
     * 换服务端构建后「还能不能用」只能靠实测回答。
     */
    boolean selftest(CommandSender sender) {
        if (!require(sender, "chunkloader.admin.others")) {
            return true;
        }
        plugin.runSelfTest(sender);
        return true;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  /cl admin status
    // ══════════════════════════════════════════════════════════════════════

    /** 全局状态：用量、实测加载数、受控区块实测等级、长期未上线报告。 */
    boolean status(CommandSender sender) {
        if (!require(sender, "chunkloader.admin.others")) {
            return true;
        }
        CommandSupport.divider(sender);
        sender.sendMessage("§6§l系统状态");
        CommandSupport.divider(sender);

        try {
            int measuredTotal = 0;
            int mismatchCount = 0;

            // ── 各世界用量 ────────────────────────────────────────────────
            sender.sendMessage("§7各世界受控区块：");
            for (World w : Bukkit.getWorlds()) {
                int total = plugin.chunks().countByWorld(w.getName());
                int measured = 0;
                for (ChunkControl c : plugin.chunks().listByWorld(w.getName())) {
                    if (plugin.control().actualLevel(w, c.chunkX(), c.chunkZ()).orElse(99) <= 33) {
                        measured++;
                    }
                }
                measuredTotal += measured;
                sender.sendMessage("§f  " + w.getName() + "§8: §f" + total + " 受控"
                        + " §8/ 实测已加载 §f" + measured);
            }

            // ── 全服用量 ──────────────────────────────────────────────────
            sender.sendMessage("§7全服：");
            sender.sendMessage("§f  总量 §f" + plugin.chunks().countGlobal()
                    + " §8/ 上限 " + plugin.cfg().globalMaxTotalChunks());
            sender.sendMessage("§f  强加载 §f" + plugin.chunks().countGlobalStrong()
                    + " §8/ 上限 " + plugin.cfg().globalMaxStrongChunks());
            sender.sendMessage("§f  实测已加载 §f" + measuredTotal
                    + " §8（含 ticket 传播出的邻居，阈值 "
                    + plugin.cfg().globalLoadedWarnThreshold() + "）");
            if (plugin.quota().overLoadedWarnThreshold(measuredTotal, plugin.cfg())) {
                sender.sendMessage("§e  ⚠ 实测已加载数超过告警阈值");
            }

            // ── 受控区块实测等级（诊断：设置是否真的生效）─────────────────
            // 存在的理由：数据库里有记录 ≠ ticket 真的加上了。
            // 排查「明明设了强加载但看不出效果」时，这里能区分三种情况：
            // 未生效 / 被玩家视距遮蔽 / 一切正常。
            sender.sendMessage("§7受控区块实测等级 §8（期望 vs 实际）：");
            List<ChunkControl> allControlled = plugin.chunks().listAll();
            if (allControlled.isEmpty()) {
                sender.sendMessage("§8  无受控区块");
            } else {
                int shown = 0;
                for (ChunkControl c : allControlled) {
                    if (shown++ >= 10) {
                        sender.sendMessage("§8  …（共 " + allControlled.size() + " 个）");
                        break;
                    }
                    mismatchCount += reportOneLevel(sender, c);
                }
            }
            if (!allControlled.isEmpty()) {
                if (mismatchCount == 0) {
                    sender.sendMessage("§a  ✓ 全部受控区块的实测等级符合预期");
                } else {
                    sender.sendMessage("§c  ⚠ 有 " + mismatchCount + " 个区块的实测等级低于设置值（见上）");
                }
            }

            // ── 能力 ──────────────────────────────────────────────────────
            sender.sendMessage("§7能力：");
            sender.sendMessage("§f  弱加载(32) §8: "
                    + (plugin.tickets().weakAvailable() ? "§a可用" : "§c不可用 — "
                    + plugin.tickets().weakUnavailableReason()));
            sender.sendMessage("§f  强加载(31) §8: §a可用 §8（公开 API，不依赖 NMS）");

            reportStaleOwners(sender);
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "读取系统状态失败", e);
            sender.sendMessage("§c读取失败: " + e.getMessage());
        }
        CommandSupport.divider(sender);
        return true;
    }

    /**
     * 报告单个受控区块的实测等级。
     *
     * @return 1 表示实际等级低于期望（算异常），0 表示正常
     */
    private int reportOneLevel(CommandSender sender, ChunkControl c) {
        String where = c.world() + "[" + c.chunkX() + "," + c.chunkZ() + "] ";
        World w = Bukkit.getWorld(c.world());
        if (w == null) {
            sender.sendMessage("§8  " + where + "§c世界未加载");
            return 1;
        }
        int expected = c.state() == ChunkState.STRONG ? LevelText.STRONG : LevelText.WEAK;
        Optional<Integer> actual = plugin.control().actualLevel(w, c.chunkX(), c.chunkZ());
        String state = c.state().displayName();

        if (actual.isEmpty()) {
            sender.sendMessage("§f  " + where + state + " §8期望 " + expected + " §c实测 未加载");
            return 1;
        }
        int got = actual.get();
        if (got == expected) {
            sender.sendMessage("§f  " + where + state + " §a✓ 实测 " + got);
            return 0;
        }
        if (got < expected) {
            // 实际比设置更强：玩家视距内的原版 ticket 把 32 抬到了 31
            sender.sendMessage("§f  " + where + state + " §8期望 " + expected + " §e实测 " + got
                    + " §7(被更强的来源遮蔽，通常是玩家视距)");
            return 0;
        }
        sender.sendMessage("§f  " + where + state + " §8期望 " + expected + " §c实测 " + got
                + " §7(ticket 未生效或仍在处理中)");
        return 1;
    }

    /**
     * 长期未变动的控制记录（只报告，绝不自动释放）。
     */
    private void reportStaleOwners(CommandSender sender) throws SQLException {
        long staleMs = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000;
        var stale = plugin.chunks().reportStaleOwners(staleMs);
        sender.sendMessage("§7长期未变动的控制记录 §8（30 天以上，仅报告）：");
        if (stale.isEmpty()) {
            sender.sendMessage("§8  无");
            return;
        }
        int shown = 0;
        for (var row : stale) {
            if (shown++ >= 8) {
                sender.sendMessage("§8  …（共 " + stale.size() + " 项）");
                break;
            }
            long days = (System.currentTimeMillis() - row.lastTouchedAt()) / (24L * 60 * 60 * 1000);
            sender.sendMessage("§f  " + CommandSupport.shortId(row.owner().toString())
                    + " §8@ " + row.world() + " §f" + row.chunkCount()
                    + " 个 §8（" + days + " 天未变动）");
        }
        sender.sendMessage("§8  释放需手动执行: /cl admin release <玩家>");
    }

    // ══════════════════════════════════════════════════════════════════════
    //  /cl admin list <玩家>
    // ══════════════════════════════════════════════════════════════════════

    boolean list(CommandSender sender, String[] args) {
        if (!require(sender, "chunkloader.admin.others")) {
            return true;
        }
        if (args.length < 3) {
            sender.sendMessage("§c用法: /cl admin list <玩家>");
            return true;
        }
        OfflinePlayer target = CommandSupport.findPlayer(args[2]);
        if (target == null) {
            sender.sendMessage("§c找不到玩家: " + args[2] + "（该玩家必须至少进过一次服务器）");
            return true;
        }
        try {
            List<ChunkControl> all = plugin.chunks().listByOwnerAllWorlds(target.getUniqueId());
            String name = target.getName() == null ? args[2] : target.getName();
            sender.sendMessage("§6" + name + " §7的全部受控区块（" + all.size() + " 个）");
            if (all.isEmpty()) {
                sender.sendMessage("§8  无");
                return true;
            }
            int shown = 0;
            for (ChunkControl c : all) {
                if (shown++ >= 20) {
                    sender.sendMessage("§8  …（共 " + all.size() + " 个）");
                    break;
                }
                sender.sendMessage("§7  " + c.world() + " §f[" + c.chunkX() + ", " + c.chunkZ() + "] "
                        + (c.state() == ChunkState.STRONG ? "§a强加载" : "§e弱加载"));
            }
        } catch (SQLException e) {
            sender.sendMessage("§c读取失败: " + e.getMessage());
        }
        return true;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  /cl admin set <玩家> <x> <z> <状态>
    // ══════════════════════════════════════════════════════════════════════

    /** 直接设置任意坐标（管理员特权）。坐标按方块计，自动换算到区块。 */
    boolean set(CommandSender sender, String[] args) {
        if (!require(sender, "chunkloader.admin.others")) {
            return true;
        }
        if (args.length < 6) {
            sender.sendMessage("§c用法: /cl admin set <玩家> <x> <z> <weak|strong|none>");
            sender.sendMessage("§7  x z 为§f方块坐标§7（自动换算到区块）");
            sender.sendMessage("§7  作用于命令发送者所在世界（控制台则为第一个世界）");
            return true;
        }
        OfflinePlayer target = CommandSupport.findPlayer(args[2]);
        if (target == null) {
            sender.sendMessage("§c找不到玩家: " + args[2] + "（该玩家必须至少进过一次服务器）");
            return true;
        }
        int bx;
        int bz;
        try {
            bx = Integer.parseInt(args[3]);
            bz = Integer.parseInt(args[4]);
        } catch (NumberFormatException e) {
            sender.sendMessage("§c坐标必须是整数: " + args[3] + " " + args[4]);
            return true;
        }
        ChunkState state = ChunkState.parse(args[5]);
        if (state == null) {
            sender.sendMessage("§c无法识别的状态: " + args[5] + "（可用: weak / strong / none）");
            return true;
        }

        World world = (sender instanceof Player p) ? p.getWorld() : CommandSupport.firstWorld();
        if (world == null) {
            sender.sendMessage("§c没有可用的世界");
            return true;
        }
        UUID actor = (sender instanceof Player p) ? p.getUniqueId() : null;

        // bypassOwnership = true：管理员可以接管他人区块，会记入审计
        var outcome = plugin.control().setState(actor, target.getUniqueId(), world,
                bx >> 4, bz >> 4, state, true);
        CommandSupport.reply(sender, outcome);
        return true;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  /cl admin release <玩家|all>
    // ══════════════════════════════════════════════════════════════════════

    boolean release(CommandSender sender, String[] args) {
        if (!require(sender, "chunkloader.admin.others")) {
            return true;
        }
        if (args.length < 3) {
            sender.sendMessage("§c用法: /cl admin release <玩家|all>");
            sender.sendMessage("§7  all = 释放全服所有人的全部区块（慎用）");
            return true;
        }
        World world = (sender instanceof Player p) ? p.getWorld() : CommandSupport.firstWorld();
        if (world == null) {
            sender.sendMessage("§c没有可用的世界");
            return true;
        }
        UUID actor = (sender instanceof Player p) ? p.getUniqueId() : null;

        if ("all".equalsIgnoreCase(args[2])) {
            return releaseEverything(sender, actor);
        }

        OfflinePlayer target = CommandSupport.findPlayer(args[2]);
        if (target == null) {
            sender.sendMessage("§c找不到玩家: " + args[2] + "（该玩家必须至少进过一次服务器）");
            return true;
        }
        var outcome = plugin.control().releaseAll(actor, target.getUniqueId(), world, true);
        CommandSupport.reply(sender, outcome);
        return true;
    }

    /** 释放全服所有受控区块。逐条走，便于审计与 ticket 清理。 */
    private boolean releaseEverything(CommandSender sender, UUID actor) {
        try {
            List<ChunkControl> all = plugin.chunks().listAll();
            if (all.isEmpty()) {
                sender.sendMessage("§7全服没有受控区块。");
                return true;
            }
            int count = 0;
            for (ChunkControl c : all) {
                World w = Bukkit.getWorld(c.world());
                if (w == null) {
                    continue;   // 世界未加载，跳过但保留记录
                }
                plugin.control().setState(actor, c.owner(), w, c.chunkX(), c.chunkZ(),
                        ChunkState.UNCONTROLLED, true);
                count++;
            }
            sender.sendMessage("§a已释放全服 " + count + " 个受控区块。");
        } catch (SQLException e) {
            sender.sendMessage("§c读取失败: " + e.getMessage());
        }
        return true;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  /cl admin log [玩家] [页]
    // ══════════════════════════════════════════════════════════════════════

    boolean log(CommandSender sender, String[] args) {
        if (!require(sender, "chunkloader.admin.log")) {
            return true;
        }
        UUID filter = null;
        int page = 1;

        if (args.length >= 3) {
            OfflinePlayer target = CommandSupport.findPlayer(args[2]);
            if (target == null) {
                sender.sendMessage("§c找不到玩家: " + args[2]);
                return true;
            }
            filter = target.getUniqueId();
            if (args.length >= 4) {
                page = CommandSupport.parsePage(new String[]{args[3]}, 1);
            }
        }

        try {
            int total = plugin.audit().count(filter);
            List<AuditLogDao.AuditEntry> rows = plugin.audit()
                    .page(filter, Math.max(0, (page - 1) * PAGE_SIZE), PAGE_SIZE);

            CommandSupport.divider(sender);
            sender.sendMessage("§6审计日志 §8（共 " + total + " 条，第 " + page + "/"
                    + CommandSupport.totalPages(total) + " 页）");
            CommandSupport.divider(sender);

            if (rows.isEmpty()) {
                sender.sendMessage("§8  无记录");
                return true;
            }
            for (AuditLogDao.AuditEntry e : rows) {
                String time = Instant.ofEpochMilli(e.ts())
                        .atZone(ZoneId.systemDefault()).format(LOG_TIME);
                String resultColour = "OK".equals(e.result()) ? "§a" : "§c";
                String where = e.world() == null ? ""
                        : " " + e.world() + "[" + e.chunkX() + "," + e.chunkZ() + "]";
                sender.sendMessage("§8" + time + " §f" + CommandSupport.shortId(e.actor())
                        + " §7" + e.action() + resultColour + " " + e.result() + "§8" + where);
                if (e.reason() != null && !e.reason().isBlank()) {
                    sender.sendMessage("§8        " + CommandSupport.truncate(e.reason(), 90));
                }
            }
        } catch (SQLException e) {
            sender.sendMessage("§c读取失败: " + e.getMessage());
        }
        return true;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  /cl admin reload
    // ══════════════════════════════════════════════════════════════════════

    boolean reload(CommandSender sender) {
        if (!require(sender, "chunkloader.admin.reload")) {
            return true;
        }
        plugin.reloadPluginConfig();
        sender.sendMessage("§a配置已重载。已受控区块不受影响"
                + "（配额收紧不追溯，只是无法再新增）。");
        sender.sendMessage("§7当前: 本世界 " + plugin.cfg().maxTotalChunks()
                + " / 强加载 " + plugin.cfg().maxStrongChunks()
                + " / 全服 " + plugin.cfg().globalMaxTotalChunks()
                + " · " + plugin.cfg().globalMaxStrongChunks());
        sender.sendMessage("§7注: 日志清理间隔与弱加载能力不受 reload 影响（需重启）。");
        return true;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  帮助
    // ══════════════════════════════════════════════════════════════════════

    void printHelp(CommandSender sender) {
        CommandSupport.divider(sender);
        sender.sendMessage("§6§l管理员命令 §8（需要对应权限节点）");
        CommandSupport.divider(sender);
        sender.sendMessage("§f/cl admin selftest §8- 实测 ticket 是否真正生效 §a[换构建后必跑]");
        sender.sendMessage("§f/cl admin status §8- 用量、实测已加载数、受控区块实测等级");
        sender.sendMessage("§f/cl admin list <玩家> §8- 查看他人受控区块");
        sender.sendMessage("§f/cl admin set <玩家> <x> <z> <状态> §8- 直接设置（方块坐标）");
        sender.sendMessage("§f/cl admin release <玩家|all> §8- 撤销");
        sender.sendMessage("§f/cl admin log [玩家] [页] §8- 审计日志");
        sender.sendMessage("§f/cl admin reload §8- 重载配置（不追溯已有记录）");
        CommandSupport.divider(sender);
    }
}
