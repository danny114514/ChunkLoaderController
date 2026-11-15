package com.danny.chunkloader.egg;

import com.danny.chunkloader.ChunkLoaderPlugin;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.UUID;
import java.util.logging.Level;

/**
 * 彩蛋：玩家在聊天栏发送特定文本时，在其位置召唤一道闪电。
 *
 * <p><b>触发词为什么不放配置文件</b>：彩蛋一旦可配置，就得在 {@code config.yml} 里
 * 写上默认值与注释 —— 那等于把答案直接贴给所有能看配置文件的人。
 * 所以触发词固定在下面的 {@link #TRIGGER} 常量里，配置只管「开不开」。
 * 副作用是想换触发词得重新编译，这本就是有意的。</p>
 *
 * <p><b>只监听玩家聊天</b>：控制台执行 {@code say ...} 或直接输入同样文本都不会触发
 * —— {@link AsyncChatEvent} 只为玩家触发，从事件类型上就排除了控制台。</p>
 */
public final class EasterEggListener implements Listener {

    /** 触发词。硬编码——配置里不出现，避免被一眼看穿。 */
    private static final String TRIGGER = "列奥德罗";

    private final ChunkLoaderPlugin plugin;

    /** 每玩家的冷却。 */
    private final Cooldown cooldown = new Cooldown();

    public EasterEggListener(ChunkLoaderPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        // 开关检查放在回调里而不是注册时：这样 /cl admin reload 改完立刻生效，
        // 不需要重启服务端。
        if (!plugin.cfg().easterEggEnabled()) {
            return;
        }

        Player player = event.getPlayer();

        // 取玩家实际发出的文本。
        // 用 originalMessage 而不是 message：其他插件可能在这之前改写 message
        // （加前缀、替换敏感词等），而我们要匹配的是玩家真正打出来的字。
        String text = PlainTextComponentSerializer.plainText()
                .serialize(event.originalMessage())
                .trim();

        if (!text.equals(TRIGGER)) {
            return;
        }

        int cooldownSeconds = plugin.cfg().easterEggCooldownSeconds();
        if (!cooldown.tryAcquire(player.getUniqueId(), System.currentTimeMillis(),
                cooldownSeconds * 1000L)) {
            return;
        }

        strikeAt(player);
    }

    /**
     * 在玩家所在位置召唤闪电。
     *
     * <p>必须切回 region 线程 —— 见类注释。{@code getScheduler().run} 的第三个参数
     * 是「实体已消失 / 所在 region 已卸载」时的回调，这里只记调试日志。</p>
     */
    private void strikeAt(Player player) {
        // 快照位置：region 线程真正执行时玩家可能已经移动。
        // 「在玩家的坐标处召唤」应理解为发消息那一刻站的位置。
        Location where = player.getLocation().clone();
        boolean effectOnly = "effect".equalsIgnoreCase(plugin.cfg().easterEggMode());
        String playerName = player.getName();

        player.getScheduler().run(plugin, task -> {
            try {
                // 落点：上半球内最近的合格避雷针上方一格；没有就劈玩家自己。
                //
                // 这一步必须在 region 线程做 —— LightningTarget.find 会读方块，
                // 在异步线程读会抛 "Cannot read world asynchronously"。
                Location target = LightningTarget.find(where);
                boolean onRod = !LightningTarget.isUserPosition(target, where);

                if (effectOnly) {
                    target.getWorld().strikeLightningEffect(target);
                } else {
                    target.getWorld().strikeLightning(target);
                }

                plugin.getLogger().info("彩蛋触发：" + playerName + " 在 "
                        + describe(where) + " 召唤了闪电 → 落点 " + describe(target)
                        + (onRod ? "（被避雷针吸引）" : "（附近无合格避雷针，劈在原地）")
                        + (effectOnly ? " [纯视觉]" : ""));
            } catch (RuntimeException e) {
                // 一个彩蛋不该把任何主流程搞崩
                plugin.getLogger().log(Level.WARNING, "彩蛋召唤闪电失败", e);
            }
        }, () -> plugin.getLogger().fine(
                "彩蛋跳过：" + playerName + " 已下线或所在区域已卸载"));
    }

    private static String describe(Location loc) {
        return loc.getWorld().getName()
                + " " + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ();
    }

    /** 玩家下线时清掉冷却记录，避免 Map 随上线人数无限增长。 */
    public void forget(UUID player) {
        cooldown.forget(player);
    }

    /** 当前记录了冷却的玩家数（调试用）。 */
    public int trackedPlayers() {
        return cooldown.size();
    }
}
