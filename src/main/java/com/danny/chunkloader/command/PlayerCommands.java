package com.danny.chunkloader.command;

import com.danny.chunkloader.ChunkLoaderPlugin;
import com.danny.chunkloader.model.ChunkControl;
import com.danny.chunkloader.model.ChunkState;
import com.danny.chunkloader.service.ChunkControlService;
import com.danny.chunkloader.service.QuotaService;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;

import static com.danny.chunkloader.command.CommandSupport.PAGE_SIZE;
import static com.danny.chunkloader.command.CommandSupport.require;
import static com.danny.chunkloader.command.CommandSupport.requirePlayer;

/**
 * 普通玩家可用的命令：{@code /cl info}、{@code list}、{@code set}、{@code release}。
 *
 * <p>这些命令的共同约束：<b>只能作用于自己脚下的区块</b>。
 * 这不是偷懒，是防远程圈地的核心设计 —— 所以位这里没有任何接受坐标的参数。
 */
public final class PlayerCommands {

    private final ChunkLoaderPlugin plugin;

    public PlayerCommands(ChunkLoaderPlugin plugin) {
        this.plugin = plugin;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  /cl info
    // ══════════════════════════════════════════════════════════════════════

    /** 脚下区块的完整信息：持有者 / 意图 / 实际等级 / 配额 / 弱加载可用性。 */
    boolean info(CommandSender sender) {
        if (!require(sender, "chunkloader.use")) {
            return true;
        }
        Player player = requirePlayer(sender, "info", "/cl admin status（看全部受控区块的实测等级）");
        if (player == null) {
            return true;
        }

        Location loc = player.getLocation();
        World world = player.getWorld();
        int cx = loc.getBlockX() >> 4;
        int cz = loc.getBlockZ() >> 4;
        UUID uuid = player.getUniqueId();

        CommandSupport.divider(sender);
        sender.sendMessage("§6脚下区块 §f" + world.getName() + " §7[" + cx + ", " + cz + "]");
        sender.sendMessage("§8方块 " + (cx << 4) + ".." + ((cx << 4) + 15)
                + ", " + (cz << 4) + ".." + ((cz << 4) + 15));
        CommandSupport.divider(sender);

        // ── 意图状态（数据库）─────────────────────────────────────────────
        Optional<ChunkControl> rec = plugin.control().find(world.getName(), cx, cz);
        if (rec.isEmpty()) {
            sender.sendMessage("§7意图状态: §f未控制 §8（不加载额外内容，由原版决定）");
        } else {
            ChunkControl c = rec.get();
            String ownerNote = c.isOwnedBy(uuid) ? "§a你" : "§c" + CommandSupport.shortId(c.owner().toString());
            sender.sendMessage("§7意图状态: §f" + c.state().displayName() + " §8· 持有者 " + ownerNote);
        }

        // ── 实际等级（NMS）───────────────────────────────────────────────
        plugin.control().actualLevel(world, cx, cz).ifPresentOrElse(
                level -> sender.sendMessage("§7实际等级: §f" + LevelText.describe(level)),
                () -> sender.sendMessage("§7实际等级: §8未加载（该处没有任何 ticket）"));

        // ── 配额 ──────────────────────────────────────────────────────────
        try {
            QuotaService.Usage u = plugin.quota().usage(uuid, world.getName(), plugin.cfg());
            sender.sendMessage("§7本世界配额: §f" + u.worldTotal() + "/" + u.maxTotal()
                    + " §8· 强加载 §f" + u.worldStrong() + "/" + u.maxStrong());
            sender.sendMessage("§7全服用量: §f" + u.globalTotal() + "/" + u.globalMaxTotal()
                    + " §8· 强加载 §f" + u.globalStrong() + "/" + u.globalMaxStrong());

            String over = plugin.quota().overQuotaWarning(uuid, world.getName(), plugin.cfg());
            if (over != null) {
                sender.sendMessage("§e⚠ " + over);
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "读取配额失败", e);
            sender.sendMessage("§c读取配额失败: " + e.getMessage());
        }

        // ── 能力 ──────────────────────────────────────────────────────────
        if (!plugin.tickets().weakAvailable()) {
            sender.sendMessage("§c⚠ 弱加载当前不可用: " + plugin.tickets().weakUnavailableReason());
        }
        CommandSupport.divider(sender);
        sender.sendMessage("§7左键=升级 · 右键=降级 §8（在 GUI 中操作 /cl）");
        return true;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  /cl list [页]
    // ══════════════════════════════════════════════════════════════════════

    /** 列出玩家在当前世界的受控区块。 */
    boolean list(CommandSender sender, String[] args) {
        if (!require(sender, "chunkloader.use")) {
            return true;
        }
        Player player = requirePlayer(sender, "list", "/cl admin list <玩家>");
        if (player == null) {
            return true;
        }
        int page = CommandSupport.parsePage(args, 1);
        UUID uuid = player.getUniqueId();
        String world = player.getWorld().getName();

        try {
            List<ChunkControl> all = plugin.chunks().listByOwner(uuid, world);

            CommandSupport.divider(sender);
            sender.sendMessage("§6我在 §f" + world + " §6的受控区块 §8（共 " + all.size() + " 个）");
            CommandSupport.divider(sender);

            String over = plugin.quota().overQuotaWarning(uuid, world, plugin.cfg());
            if (over != null) {
                sender.sendMessage("§e⚠ " + over);
            }

            if (all.isEmpty()) {
                sender.sendMessage("§7还没有受控区块。用 §f/cl§7 打开地图后左键点击来添加。");
                return true;
            }

            int from = Math.max(0, (page - 1) * PAGE_SIZE);
            int to = Math.min(all.size(), from + PAGE_SIZE);
            if (from >= all.size()) {
                sender.sendMessage("§e第 " + page + " 页为空（共 " + CommandSupport.totalPages(all.size()) + " 页）");
                return true;
            }

            for (int i = from; i < to; i++) {
                ChunkControl c = all.get(i);
                String stateColour = c.state() == ChunkState.STRONG ? "§a" : "§e";
                String actual = plugin.control()
                        .actualLevel(player.getWorld(), c.chunkX(), c.chunkZ())
                        .map(l -> " §8实际 " + l)
                        .orElse(" §8实际 未加载");
                sender.sendMessage("§7" + (i + 1) + ". §f[" + c.chunkX() + ", " + c.chunkZ() + "] "
                        + stateColour + c.state().displayName() + actual);
            }

            CommandSupport.divider(sender);
            StringBuilder nav = new StringBuilder("§7第 " + page + "/"
                    + CommandSupport.totalPages(all.size()) + " 页 ");
            if (page > 1) {
                nav.append("§8[§a◀ 上一页§8] ");
            }
            if (to < all.size()) {
                nav.append("§8[§a下一页 ▶§8]");
            }
            sender.sendMessage(nav.toString());
            return true;
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "读取受控区块列表失败", e);
            sender.sendMessage("§c读取失败: " + e.getMessage());
            return true;
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  /cl set <weak|strong|none>
    // ══════════════════════════════════════════════════════════════════════

    /** 设置脚下区块的状态（不接受坐标参数）。 */
    boolean set(CommandSender sender, String[] args) {
        if (!require(sender, "chunkloader.use")) {
            return true;
        }
        Player player = requirePlayer(sender, "set", "/cl admin set <玩家> <x> <z> <状态>");
        if (player == null) {
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage("§c用法: /cl set <weak|strong|none>");
            sender.sendMessage("§7  weak=弱加载(32) · strong=强加载(31) · none=释放");
            return true;
        }
        ChunkState target = ChunkState.parse(args[1]);
        if (target == null) {
            sender.sendMessage("§c无法识别的状态: " + args[1] + "（可用: weak / strong / none）");
            return true;
        }
        return applyToFeetChunk(sender, player, target);
    }

    // ══════════════════════════════════════════════════════════════════════
    //  /cl release all
    // ══════════════════════════════════════════════════════════════════════

    /** 释放玩家在当前世界的全部受控区块。 */
    boolean releaseAll(CommandSender sender, String[] args) {
        if (!require(sender, "chunkloader.use")) {
            return true;
        }
        Player player = requirePlayer(sender, "release", "/cl admin release <玩家|all>");
        if (player == null) {
            return true;
        }
        if (args.length < 2 || !"all".equalsIgnoreCase(args[1])) {
            sender.sendMessage("§c用法: /cl release all");
            sender.sendMessage("§7  释放你在当前世界的全部受控区块（不影响其他世界）");
            return true;
        }
        ChunkControlService.Outcome outcome = plugin.control()
                .releaseAll(player.getUniqueId(), player.getUniqueId(), player.getWorld(), false);
        CommandSupport.reply(sender, outcome);
        return true;
    }

    // ── 内部 ────────────────────────────────────────────────────────────────

    /**
     * 对「脚下区块」执行状态设置。
     *
     * <p>GUI 点击与 {@code /cl set} 最终都走到这里，所以弱加载提示只需要写一份
     * （首次设弱加载时提示实体冻结）。
     */
    private boolean applyToFeetChunk(CommandSender sender, Player player, ChunkState target) {
        Location loc = player.getLocation();
        int cx = loc.getBlockX() >> 4;
        int cz = loc.getBlockZ() >> 4;

        ChunkControlService.Outcome outcome = plugin.control().setState(
                player.getUniqueId(), player.getUniqueId(), player.getWorld(), cx, cz, target, false);
        CommandSupport.reply(sender, outcome);

        // 首次设弱加载的一次性提示
        if (outcome.ok() && outcome.changed() && target == ChunkState.WEAK
                && plugin.cfg().warnOnWeakSet()) {
            CommandSupport.divider(sender);
            sender.sendMessage("§e弱加载：方块计算继续（作物/熔炉/漏斗/区块内红石正常），但实体冻结");
            sender.sendMessage("§e—— 掉落物不会消失、怪物不动、刷怪塔不工作");
            CommandSupport.divider(sender);
        }
        return true;
    }
}
