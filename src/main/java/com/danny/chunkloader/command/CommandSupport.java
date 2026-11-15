package com.danny.chunkloader.command;

import com.danny.chunkloader.ChunkLoaderPlugin;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;

/**
 * 命令层共用的小工具：权限、玩家解析、分页、文字格式化。
 *
 * <p>抽出来的理由是这些逻辑在三个命令类里都要用，而它们**与业务无关** ——
 * 混在业务代码里会让人读业务逻辑时不断被这些细节打断。
 */
public final class CommandSupport {

    /** 列表每页条数。{@code /cl list} 与 {@code /cl admin log} 共用。 */
    public static final int PAGE_SIZE = 8;

    private CommandSupport() {
    }

    /**
     * 检查权限，不足时直接回话。
     *
     * @return true 表示有权限，可以继续
     */
    public static boolean require(CommandSender sender, String node) {
        if (sender.hasPermission(node)) {
            return true;
        }
        sender.sendMessage("§c权限不足：需要 " + node);
        return false;
    }

    /**
     * 要求发送者是玩家。
     *
     * <p>控制台被拒绝时，提示里必须给出「那该用什么」—— 只说「只能由玩家执行」
     * 会让管理员卡住，尤其 {@code /cl info} 这种排查问题时最想用的命令。
     * 这里的替代建议是按命令区分的，不是一句通用安慰话。</p>
     *
     * @param what    命令名（用于提示，如 {@code info}）
     * @param hintFor 控制台可用的替代命令；传 {@code null} 表示没有直接替代
     * @return 玩家对象；不是玩家时返回 {@code null}（已回话）
     */
    public static Player requirePlayer(CommandSender sender, String what, String hintFor) {
        if (sender instanceof Player player) {
            return player;
        }
        sender.sendMessage("§c/cl " + what + " 需要玩家所在的世界与位置，控制台无法执行。");
        if (hintFor != null && !hintFor.isBlank()) {
            sender.sendMessage("§7控制台可以用：§f" + hintFor);
        }
        return null;
    }

    /**
     * 兼容旧调用的重载：没有替代命令时也能给出方向。
     */
    public static Player requirePlayer(CommandSender sender, String what) {
        return requirePlayer(sender, what, null);
    }

    /** 第一个已加载的世界；没有则返回 {@code null}。 */
    public static World firstWorld() {
        List<World> worlds = Bukkit.getWorlds();
        return worlds.isEmpty() ? null : worlds.get(0);
    }

    /**
     * 解析玩家：先按在线玩家精确匹配，再按 UUID，最后按离线玩家名。
     *
     * <p>顺序很重要 —— {@code Bukkit.getOfflinePlayer(String)} 对不存在的名字
     * 也会返回一个「离线玩家」对象（UUID 由名字哈希得出），
     * 直接用它会让人以为玩家存在。所以用 {@code hasPlayedBefore()} 过滤。
     *
     * @return 找不到时返回 {@code null}
     */
    public static OfflinePlayer findPlayer(String input) {
        Player online = Bukkit.getPlayerExact(input);
        if (online != null) {
            return online;
        }
        try {
            UUID uuid = UUID.fromString(input);
            OfflinePlayer byUuid = Bukkit.getOfflinePlayer(uuid);
            return (byUuid.hasPlayedBefore() || byUuid.isOnline()) ? byUuid : null;
        } catch (IllegalArgumentException notUuid) {
            // 不是 UUID 形式，继续按名字查
        }
        OfflinePlayer byName = Bukkit.getOfflinePlayer(input);
        return (byName.hasPlayedBefore() || byName.isOnline()) ? byName : null;
    }

    /** 解析页码参数，非法或缺失时返回 fallback。 */
    public static int parsePage(String[] args, int fallback) {
        if (args.length == 0) {
            return fallback;
        }
        try {
            return Math.max(1, Integer.parseInt(args[args.length - 1]));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** 总页数，至少 1。 */
    public static int totalPages(int total) {
        return Math.max(1, (total + PAGE_SIZE - 1) / PAGE_SIZE);
    }

    /** 把 64 字符的 UUID 显示成玩家名（找不到名字时截断 UUID）。 */
    public static String shortId(String uuid) {
        if (uuid == null) {
            return "?";
        }
        if ("CONSOLE".equals(uuid)) {
            return "CONSOLE";
        }
        try {
            OfflinePlayer op = Bukkit.getOfflinePlayer(UUID.fromString(uuid));
            String name = op.getName();
            return name != null ? name : uuid.substring(0, 8);
        } catch (IllegalArgumentException e) {
            // 库里可能是旧格式或被手改过的值，不要因此抛异常
            return uuid.length() > 8 ? uuid.substring(0, 8) : uuid;
        }
    }

    /** 按显示宽度截断，避免一行刷屏。 */
    public static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    /** 成功用绿色、失败用红色回话。 */
    public static void reply(CommandSender sender, com.danny.chunkloader.service.ChunkControlService.Outcome outcome) {
        sender.sendMessage((outcome.ok() ? "§a" : "§c") + outcome.message());
    }

    /**
     * 统一的命令分隔线。
     *
     * <p>抽出来是为了所有命令看起来一致 —— 手工复制粘贴 48 个空格很容易出错。
     */
    public static final String DIVIDER = "§8§m                                                 ";

    /** 发一条分隔线。 */
    public static void divider(CommandSender sender) {
        sender.sendMessage(DIVIDER);
    }
}
