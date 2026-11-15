package com.danny.chunkloader.platform;

/**
 * Folia 环境检测（非 Folia 启动即拒绝）。
 *
 * <p>只在 Folia 上构建这个插件不是洁癖：ticket 反射链、{@code RegionScheduler}、
 * 「ticket 增删 MT-safe」这些前提都是 Folia 特有的。在 Paper 上运行时，
 * 反射可能「碰巧能跑」，但线程模型完全不同（Paper 是单主线程 + 异步），
 * 结果会是偶发、难复现的数据竞争 —— 那比直接拒绝启动糟得多。
 */
public final class FoliaSupport {

    private FoliaSupport() {
    }

    /**
     * 当前服务端是否为 Folia。
     *
     * <p>用「RegionizedServer 类存在」而不是品牌字符串判断：品牌可被改，
     * 而类存在意味着 region 线程模型真的在。
     */
    public static boolean isFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /** 用于报错信息：尽量给出可读的服务端版本。 */
    public static String describeServer() {
        try {
            var server = org.bukkit.Bukkit.getServer();
            if (server == null) {
                return "未知（服务端尚未初始化）";
            }
            return server.getName() + " " + server.getVersion() + " / Bukkit " + server.getBukkitVersion();
        } catch (Throwable t) {
            return "未知（" + t.getClass().getSimpleName() + "）";
        }
    }
}
