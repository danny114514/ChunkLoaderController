package com.danny.chunkloader.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;

/**
 * 强类型配置快照。
 *
 * <p>启动/重载时构造一次不可变快照，运行期只读。这样避免了「运行中配置被改成半新半旧」
 * 这类难查的问题 —— {@code /cl admin reload} 就是换一个新快照。
 */
public record PluginConfig(
        // ── 配额 ──
        int maxTotalChunks,
        int maxStrongChunks,
        int globalMaxTotalChunks,
        int globalMaxStrongChunks,
        int globalLoadedWarnThreshold,
        boolean quotaPerWorld,
        boolean quotaStrictOnShrink,
        // ── 生命周期 ──
        boolean releaseOnLogout,
        int autoReleaseIdleDays,
        // ── 日志 ──
        int logRetentionDays,
        // ── GUI ──
        int refreshInterval,
        String blockTickingBuffer,
        boolean warnOnWeakSet,
        // ── 存储 ──
        String writeMode,
        String databaseType,
        String databaseFile,
        // ── 杂项 ──
        String language,
        int weakTicketLevel,
        int strongTicketLevel,
        boolean easterEggEnabled,
        String easterEggMode,
        int easterEggCooldownSeconds
) {

    /** 验证并规范化数值：免得管理员配成负数导致配额全放行。 */
    public PluginConfig {
        maxTotalChunks = Math.max(0, maxTotalChunks);
        maxStrongChunks = Math.max(0, maxStrongChunks);
        globalMaxTotalChunks = Math.max(0, globalMaxTotalChunks);
        globalMaxStrongChunks = Math.max(0, globalMaxStrongChunks);
        globalLoadedWarnThreshold = Math.max(0, globalLoadedWarnThreshold);
        autoReleaseIdleDays = Math.max(0, autoReleaseIdleDays);
        logRetentionDays = Math.max(0, logRetentionDays);
        easterEggCooldownSeconds = Math.max(0, easterEggCooldownSeconds);
    }

    /**
     * 从 Bukkit 配置读取，缺失项使用与 {@code config.yml} 一致的默认值。
     *
     * <p>默认值写死在这里而不是靠 {@code saveDefaultConfig()}：若管理员删掉了某个键，
     * 我们仍然按内置默认值运行，而不是静默变成 0（0 会让所有操作被拒绝）。
     */
    public static PluginConfig from(Plugin plugin) {
        FileConfiguration c = plugin.getConfig();
        return new PluginConfig(
                c.getInt("max-total-chunks", 45),
                c.getInt("max-strong-chunks", 9),
                c.getInt("global-max-total-chunks", 500),
                c.getInt("global-max-strong-chunks", 100),
                c.getInt("global-loaded-warn-threshold", 300),
                c.getBoolean("quota-per-world", true),
                c.getBoolean("quota-strict-on-shrink", false),
                c.getBoolean("release-on-logout", false),
                c.getInt("auto-release-idle-days", 0),
                c.getInt("log-retention-days", 90),
                c.getInt("refresh-interval", 0),
                c.getString("block-ticking-buffer", "none"),
                c.getBoolean("warn-on-weak-set", true),
                c.getString("write-mode", "immediate"),
                c.getString("database.type", "sqlite"),
                c.getString("database.file", "data.db"),
                c.getString("language", "zh_CN"),
                c.getInt("weak-ticket-level", 32),
                c.getInt("strong-ticket-level", 31),
                c.getBoolean("easter-egg.enabled", true),
                c.getString("easter-egg.mode", "lightning"),
                c.getInt("easter-egg.cooldown-seconds", 5)
        );
    }
}
