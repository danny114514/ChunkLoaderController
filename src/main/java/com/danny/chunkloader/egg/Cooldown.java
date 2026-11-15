package com.danny.chunkloader.egg;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按玩家计算的冷却计时器（纯逻辑，不依赖 Bukkit）。
 *
 * <p><b>为什么单独一个类</b>：这段逻辑跑在<b>聊天事件的异步线程</b>上，
 * 而玩家完全可能在极短时间连发两条相同消息。如果用「先读时间戳，再判断，
 * 最后写回」的写法，两个线程会同时读到「上次是 6 秒前」，双双判定通过 ——
 * 于是连续劈出两道闪电。</p>
 *
 * <p>用 {@link ConcurrentHashMap#compute} 把「判断 + 更新」合成一次原子操作，
 * 就不会有这种漏放。而这样一来它也不再需要 {@code Player} 对象，
 * 可以脱离服务端被单元测试覆盖 —— 冷却边界与并发是实测中最容易错的部分。</p>
 */
public final class Cooldown {

    private final Map<UUID, Long> lastAllowed = new ConcurrentHashMap<>();

    /**
     * 判断本次是否允许放行；放行时同时记录当前时间。
     *
     * @param player         玩家
     * @param now            当前时间戳（毫秒）
     * @param cooldownMillis 冷却时长（毫秒）；&lt;= 0 表示不限制
     * @return true 表示放行
     */
    public boolean tryAcquire(UUID player, long now, long cooldownMillis) {
        if (cooldownMillis <= 0) {
            return true;
        }
        // compute 是原子的：同一个 key 的 lambda 不会并发执行，
        // 因此「读到旧值 → 决定放行 → 写入新值」不会被另一个线程插进来。
        // lambda 内不能直接返回布尔，用单元素数组把结论带出来。
        boolean[] allowed = {false};
        lastAllowed.compute(player, (uuid, previous) -> {
            if (previous == null || now - previous >= cooldownMillis) {
                allowed[0] = true;
                return now;
            }
            return previous;   // 仍在冷却：保留旧时间戳，本次不放行
        });
        return allowed[0];
    }

    /**
     * 剩余冷却毫秒数，用于给玩家提示「还要等多久」。
     *
     * @return 0 表示已可触发
     */
    public long remainingMillis(UUID player, long now, long cooldownMillis) {
        if (cooldownMillis <= 0) {
            return 0L;
        }
        Long previous = lastAllowed.get(player);
        if (previous == null) {
            return 0L;
        }
        long elapsed = now - previous;
        return elapsed >= cooldownMillis ? 0L : cooldownMillis - elapsed;
    }

    /** 忘记某个玩家（下线时调用，避免 Map 无限增长）。 */
    public void forget(UUID player) {
        lastAllowed.remove(player);
    }

    /** 当前记录数量（调试与测试用）。 */
    public int size() {
        return lastAllowed.size();
    }

    /** 清空（插件卸载时用）。 */
    public void clear() {
        lastAllowed.clear();
    }
}
