package com.danny.chunkloader.command;

import com.danny.chunkloader.ChunkLoaderPlugin;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code /cl} 命令入口：只负责解析与分发。
 *
 * <p>真正的业务实现分布在三个类里，本类刻意保持「一眼看完」的体量：
 * <ul>
 *   <li>{@link PlayerCommands} —— 普通玩家命令（info / list / set / release）</li>
 *   <li>{@link AdminCommands} —— 管理员命令（admin *）</li>
 *   <li>{@link CommandSupport} —— 权限、玩家解析、分页等共用工具</li>
 * </ul>
 *
 * <p><b>无状态设计</b>：不维护「多轮交互会话」。所有交互都是一条命令进、
 * 一段输出出。理由：命令会话状态在多线程 + 玩家下线 + 插件重载的组合下
 * 极易泄漏，而这些命令都不需要多轮。
 *
 * <p><b>线程约束</b>：命令在 Folia 上由 region 线程执行（玩家命令）或全局线程执行
 * （控制台命令）。因此这里只做「读数据库 + 加 ticket」这类 MT-safe 操作，
 * 绝不在此读区块方块数据（那会抛 {@code Cannot read world asynchronously}）。
 */
public final class ChunkLoaderCommand implements CommandExecutor, TabCompleter {

    private final ChunkLoaderPlugin plugin;
    private final PlayerCommands players;
    private final AdminCommands admins;

    public ChunkLoaderCommand(ChunkLoaderPlugin plugin) {
        this.plugin = plugin;
        this.players = new PlayerCommands(plugin);
        this.admins = new AdminCommands(plugin);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            return openGui(sender);
        }
        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "info" -> players.info(sender);
            case "list" -> players.list(sender, args);
            case "set" -> players.set(sender, args);
            case "release" -> players.releaseAll(sender, args);
            case "admin" -> admins.dispatch(sender, args);
            case "help" -> {
                printHelp(sender);
                yield true;
            }
            default -> {
                sender.sendMessage("§c未知子命令: " + args[0]);
                printHelp(sender);
                yield true;
            }
        };
    }

    /** {@code /cl} 无参数：打开区块地图（控制台则给出提示）。 */
    private boolean openGui(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§e控制台无法打开 GUI。可用: /cl info · /cl list · /cl admin ...");
            return true;
        }
        if (!CommandSupport.require(sender, "chunkloader.use")) {
            return true;
        }
        plugin.mapGui().open(player);
        return true;
    }

    private void printHelp(CommandSender sender) {
        CommandSupport.divider(sender);
        sender.sendMessage("§6§l区块加载控制器 §7v" + plugin.getPluginMeta().getVersion());
        CommandSupport.divider(sender);
        sender.sendMessage("§f/cl §8- 打开周围区块地图（GUI）");
        sender.sendMessage("§f/cl info §8- 脚下区块的持有者 / 意图状态 / 实际等级 / 配额");
        sender.sendMessage("§f/cl list [页] §8- 列出自己受控的区块");
        sender.sendMessage("§f/cl set <weak|strong|none> §8- 设置脚下区块");
        sender.sendMessage("§f/cl release all §8- 释放自己的全部区块");
        sender.sendMessage("§f/cl admin <...> §8- 管理员命令（见 /cl admin）");
        CommandSupport.divider(sender);
        sender.sendMessage("§7状态说明：§a强加载§7=实体刻运行（31） · §e弱加载§7=仅方块刻（32）");
        sender.sendMessage("§7弱加载：作物/熔炉/漏斗正常，§c实体冻结§7（掉落物不动、怪物不动、刷怪塔不产出）");
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Tab 补全
    // ══════════════════════════════════════════════════════════════════════

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filter(List.of("info", "list", "set", "release", "admin", "help"), args[0]);
        }
        String first = args[0].toLowerCase(Locale.ROOT);

        if ("admin".equals(first)) {
            return completeAdmin(sender, args);
        }
        if ("set".equals(first) && args.length == 2) {
            return filter(List.of("weak", "strong", "none"), args[1]);
        }
        if ("release".equals(first) && args.length == 2) {
            return filter(List.of("all"), args[1]);
        }
        return List.of();
    }

    private List<String> completeAdmin(CommandSender sender, String[] args) {
        if (args.length == 2) {
            return filter(List.of("selftest", "status", "list", "set", "release", "log", "reload"),
                    args[1]);
        }
        String sub = args[1].toLowerCase(Locale.ROOT);

        // 第三位是玩家名（release 额外支持 all）
        if (args.length == 3 && List.of("list", "set", "release", "log").contains(sub)) {
            List<String> names = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) {
                names.add(p.getName());
            }
            if ("release".equals(sub)) {
                names.add("all");
            }
            return filter(names, args[2]);
        }
        // 第六位是状态
        if (args.length == 6 && "set".equals(sub)) {
            return filter(List.of("weak", "strong", "none"), args[5]);
        }
        return List.of();
    }

    /** 前缀过滤，大小写不敏感。 */
    private static List<String> filter(List<String> options, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String s : options) {
            if (s.toLowerCase(Locale.ROOT).startsWith(p)) {
                out.add(s);
            }
        }
        return out;
    }
}
