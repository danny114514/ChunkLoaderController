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
            // 这里只列【控制台真的能用】的命令。
            // 早期版本写的是「可用: /cl info · /cl list」，但那两条都要玩家位置，
            // 控制台执行只会被拒绝 —— 先推荐再拒绝是最糟糕的用户体验。
            sender.sendMessage("§eGUI 需要玩家客户端，控制台无法打开。");
            sender.sendMessage("§7控制台可用的命令：");
            sender.sendMessage("§f  /cl admin status §8- 全局用量、实测已加载数、各区块实际等级");
            sender.sendMessage("§f  /cl admin list <玩家> §8- 某玩家控制了哪些区块");
            sender.sendMessage("§f  /cl admin set <玩家> <x> <z> <状态> §8- 直接设置某个区块");
            sender.sendMessage("§f  /cl admin release <玩家|all> §8- 撤销");
            sender.sendMessage("§f  /cl admin log [玩家] [页] §8- 审计日志");
            sender.sendMessage("§f  /cl admin selftest §8- 实测 ticket 是否生效");
            sender.sendMessage("§f  /cl admin reload §8- 重载配置");
            sender.sendMessage("§8（/cl info 等命令需要玩家所在的世界与位置，无法在控制台执行）");
            return true;
        }
        if (!CommandSupport.require(sender, "chunkloader.use")) {
            return true;
        }
        plugin.mapGui().open(player);
        return true;
    }

    /**
     * 帮助信息。
     *
     * <p>控制台与玩家看到的列表不同 —— 玩家的 {@code /cl}、{@code /cl info}
     * 等命令都依赖「所在世界与位置」，在控制台执行只会被拒绝。
     * 帮助里列出用不了的命令，与「先推荐再拒绝」是同一类问题。</p>
     */
    private void printHelp(CommandSender sender) {
        CommandSupport.divider(sender);
        sender.sendMessage("§6§l区块加载控制器 §7v" + plugin.getPluginMeta().getVersion());
        CommandSupport.divider(sender);

        if (sender instanceof Player) {
            sender.sendMessage("§f/cl §8- 打开周围区块地图（GUI）");
            sender.sendMessage("§f/cl info §8- 脚下区块的持有者 / 意图状态 / 实际等级 / 配额");
            sender.sendMessage("§f/cl list [页] §8- 列出自己受控的区块");
            sender.sendMessage("§f/cl set <weak|strong|none> §8- 设置脚下区块");
            sender.sendMessage("§f/cl release all §8- 释放自己的全部区块");
            sender.sendMessage("§f/cl admin <...> §8- 管理员命令（见 /cl admin）");
        } else {
            // 控制台：只列真正能执行的
            sender.sendMessage("§7控制台只能使用管理员命令（其余命令需要玩家位置）：");
            sender.sendMessage("§f/cl admin status §8- 全局用量、实测已加载数、各区块实际等级");
            sender.sendMessage("§f/cl admin list <玩家> §8- 某玩家控制了哪些区块");
            sender.sendMessage("§f/cl admin set <玩家> <x> <z> <状态> §8- 直接设置");
            sender.sendMessage("§f/cl admin release <玩家|all> §8- 撤销");
            sender.sendMessage("§f/cl admin log [玩家] [页] §8- 审计日志");
            sender.sendMessage("§f/cl admin selftest §8- 实测 ticket 是否生效");
            sender.sendMessage("§f/cl admin reload §8- 重载配置");
        }

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
