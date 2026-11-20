package com.danny.chunkloader.command;

import com.danny.chunkloader.ChunkLoaderPlugin;
import com.danny.chunkloader.model.ChunkControl;
import com.danny.chunkloader.model.ChunkGroup;
import com.danny.chunkloader.model.ChunkState;
import com.danny.chunkloader.service.GroupService;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static com.danny.chunkloader.command.CommandSupport.require;
import static com.danny.chunkloader.command.CommandSupport.requirePlayer;

/**
 * {@code /cl group ...} 子命令：把多个区块当作一个整体来管理。
 *
 * <p>群组是<b>私有</b>的（只有创建者能用），且一个区块最多属于一个群组。</p>
 *
 * <p><b>这里没有「把整组设为未控制」这个操作</b>，这是有意的：
 * 「未控制」等于放弃归属，别人立刻能抢。批量放弃一整组地太危险，
 * 要做请用 {@code /cl group delete}，那条命令的措辞会明确提醒后果。</p>
 */
public final class GroupCommands {

    private final ChunkLoaderPlugin plugin;

    public GroupCommands(ChunkLoaderPlugin plugin) {
        this.plugin = plugin;
    }

    /** 路由 {@code /cl group <sub>}。 */
    boolean dispatch(CommandSender sender, String[] args) {
        if (args.length < 2) {
            printHelp(sender);
            return true;
        }
        String sub = args[1].toLowerCase(Locale.ROOT);
        return switch (sub) {
            case "create" -> create(sender, args);
            case "delete" -> delete(sender, args);
            case "rename" -> rename(sender, args);
            case "list" -> list(sender, args);
            case "show", "info" -> show(sender, args);
            case "add" -> add(sender, args);
            case "remove" -> remove(sender, args);
            case "set" -> set(sender, args);
            default -> {
                sender.sendMessage("§c未知群组子命令: " + args[1]);
                printHelp(sender);
                yield true;
            }
        };
    }

    // ══════════════════════════════════════════════════════════════════════
    //  create / delete / rename
    // ══════════════════════════════════════════════════════════════════════

    private boolean create(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender, "group create", "/cl admin list <玩家>");
        if (player == null) {
            return true;
        }
        if (args.length < 3) {
            sender.sendMessage("§c用法: /cl group create <群组名>");
            return true;
        }
        reply(sender, plugin.groups().create(player.getUniqueId(), args[2]));
        return true;
    }

    private boolean delete(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender, "group delete", null);
        if (player == null) {
            return true;
        }
        if (args.length < 3) {
            sender.sendMessage("§c用法: /cl group delete <群组名>");
            return true;
        }
        // 删除会释放整组区块的归属，必须让人确认自己知道后果
        sender.sendMessage("§e提示：删除群组会把组内所有区块释放为「未控制」，"
                + "其他人之后可以认领它们。");
        reply(sender, plugin.groups().delete(player.getUniqueId(), args[2], Bukkit::getWorld));
        return true;
    }

    private boolean rename(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender, "group rename", null);
        if (player == null) {
            return true;
        }
        if (args.length < 4) {
            sender.sendMessage("§c用法: /cl group rename <旧名> <新名>");
            return true;
        }
        reply(sender, plugin.groups().rename(player.getUniqueId(), args[2], args[3]));
        return true;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  list / show
    // ══════════════════════════════════════════════════════════════════════

    private boolean list(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender, "group list", "/cl admin list <玩家>");
        if (player == null) {
            return true;
        }
        List<ChunkGroup> groups = plugin.groups().listGroups(player.getUniqueId());

        CommandSupport.divider(sender);
        sender.sendMessage("§6你的群组 §8（共 " + groups.size() + " 个）");
        CommandSupport.divider(sender);

        if (groups.isEmpty()) {
            sender.sendMessage("§7还没有群组。用 §f/cl group create <名字>§7 创建一个。");
            return true;
        }
        for (ChunkGroup g : groups) {
            int count = plugin.groups().countChunks(g.id());
            String stateNote = describeGroupState(g.id());
            sender.sendMessage("§f  " + g.name() + " §8— " + count + " 个区块" + stateNote);
        }
        CommandSupport.divider(sender);
        sender.sendMessage("§7/cl group show <名字> §8看详情 · "
                + "§7/cl group set <名字> <状态> §8批量切换");
        return true;
    }

    /** 描述一个群组当前的状态分布，例如「全部弱加载」或「混合：弱 3 / 保留 1」。 */
    private String describeGroupState(long groupId) {
        List<ChunkControl> chunks = plugin.groups().listChunks(groupId);
        if (chunks.isEmpty()) {
            return " §8（空）";
        }
        ChunkState first = chunks.get(0).state();
        boolean uniform = chunks.stream().allMatch(c -> c.state() == first);
        if (uniform) {
            return " §8（全部 " + first.displayName() + "）";
        }
        // 混合时按状态统计
        StringBuilder sb = new StringBuilder(" §8（混合：");
        boolean firstEntry = true;
        for (ChunkState s : ChunkState.values()) {
            long n = chunks.stream().filter(c -> c.state() == s).count();
            if (n == 0) {
                continue;
            }
            if (!firstEntry) {
                sb.append(" / ");
            }
            sb.append(s.displayName()).append(' ').append(n);
            firstEntry = false;
        }
        return sb.append("）").toString();
    }

    private boolean show(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender, "group show", null);
        if (player == null) {
            return true;
        }
        if (args.length < 3) {
            sender.sendMessage("§c用法: /cl group show <群组名>");
            return true;
        }
        Optional<ChunkGroup> found = plugin.groups().find(player.getUniqueId(), args[2]);
        if (found.isEmpty()) {
            sender.sendMessage("§c你没有叫「" + ChunkGroup.normalizeName(args[2]) + "」的群组。");
            return true;
        }
        ChunkGroup g = found.get();
        List<ChunkControl> chunks = plugin.groups().listChunks(g.id());

        CommandSupport.divider(sender);
        sender.sendMessage("§6群组 §f" + g.name() + " §8（" + chunks.size() + " 个区块）");
        CommandSupport.divider(sender);
        if (chunks.isEmpty()) {
            sender.sendMessage("§7  空。在 GUI 里 §fShift+左键§7 点区块可加入。");
            return true;
        }
        // 最多列 20 条，避免刷屏
        int shown = 0;
        for (ChunkControl c : chunks) {
            if (shown++ >= 20) {
                sender.sendMessage("§8  …（共 " + chunks.size() + " 个）");
                break;
            }
            String colour = switch (c.state()) {
                case STRONG -> "§a";
                case WEAK -> "§e";
                case RESERVED -> "§b";
                default -> "§7";
            };
            sender.sendMessage("§7  " + c.world() + " §f[" + c.chunkX() + ", " + c.chunkZ() + "] "
                    + colour + c.state().displayName());
        }
        CommandSupport.divider(sender);
        sender.sendMessage("§7批量操作: /cl group set " + g.name() + " <weak|strong|reserve>");
        return true;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  add / remove / set
    // ══════════════════════════════════════════════════════════════════════

    private boolean add(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender, "group add", null);
        if (player == null) {
            return true;
        }
        if (args.length < 3) {
            sender.sendMessage("§c用法: /cl group add <群组名>");
            sender.sendMessage("§7  把脚下的区块加入群组。也可以用 GUI 里 Shift+左键 点击添加。");
            return true;
        }
        Location loc = player.getLocation();
        int cx = loc.getBlockX() >> 4;
        int cz = loc.getBlockZ() >> 4;
        reply(sender, plugin.groups().addChunk(
                player.getUniqueId(), args[2], player.getWorld(), cx, cz));
        return true;
    }

    private boolean remove(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender, "group remove", null);
        if (player == null) {
            return true;
        }
        Location loc = player.getLocation();
        int cx = loc.getBlockX() >> 4;
        int cz = loc.getBlockZ() >> 4;
        reply(sender, plugin.groups().removeChunk(player.getUniqueId(), player.getWorld(), cx, cz));
        return true;
    }

    private boolean set(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender, "group set", "/cl admin set <玩家> <x> <z> <状态>");
        if (player == null) {
            return true;
        }
        if (args.length < 4) {
            sender.sendMessage("§c用法: /cl group set <群组名> <weak|strong|reserve>");
            sender.sendMessage("§7  一次性把组内所有区块改成该状态。");
            sender.sendMessage("§8  （不支持 none —— 放弃整组请用 /cl group delete）");
            return true;
        }
        ChunkState target = ChunkState.parse(args[3]);
        if (target == null) {
            sender.sendMessage("§c无法识别的状态: " + args[3]
                    + "（可用: weak / strong / reserve）");
            return true;
        }
        reply(sender, plugin.groups().setGroupState(
                player.getUniqueId(), args[2], target, Bukkit::getWorld));
        return true;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  工具
    // ══════════════════════════════════════════════════════════════════════

    private static void reply(CommandSender sender, GroupService.Outcome outcome) {
        sender.sendMessage((outcome.ok() ? "§a" : "§c") + outcome.message());
    }

    void printHelp(CommandSender sender) {
        CommandSupport.divider(sender);
        sender.sendMessage("§6§l群组 §8（把多个区块当作一个整体管理）");
        CommandSupport.divider(sender);
        sender.sendMessage("§f/cl group create <名字> §8- 新建群组");
        sender.sendMessage("§f/cl group list §8- 列出你的群组");
        sender.sendMessage("§f/cl group show <名字> §8- 查看组内区块");
        sender.sendMessage("§f/cl group add <名字> §8- 把脚下区块加入群组");
        sender.sendMessage("§f/cl group remove §8- 把脚下区块移出群组（仍归你）");
        sender.sendMessage("§f/cl group set <名字> <weak|strong|reserve> §8- 批量切换");
        sender.sendMessage("§f/cl group rename <旧> <新> §8- 改名");
        sender.sendMessage("§f/cl group delete <名字> §8- 删群组（组内区块会被释放）");
        CommandSupport.divider(sender);
        sender.sendMessage("§7GUI 里 §fShift+左键§7 点区块 = 加入你的第一个群组");
        sender.sendMessage("§7状态 §b保留§7 = 不加载但不放弃归属，他人无法认领");
    }

    /** Tab 补全建议。 */
    List<String> complete(Player player, String[] args) {
        if (args.length == 3) {
            String sub = args[1].toLowerCase(Locale.ROOT);
            if (List.of("delete", "show", "info", "add", "set", "rename").contains(sub)) {
                List<String> names = new ArrayList<>();
                for (ChunkGroup g : plugin.groups().listGroups(player.getUniqueId())) {
                    names.add(g.name());
                }
                return filter(names, args[2]);
            }
        }
        if (args.length == 4 && "set".equals(args[1].toLowerCase(Locale.ROOT))) {
            return filter(List.of("weak", "strong", "reserve"), args[3]);
        }
        return List.of();
    }

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
