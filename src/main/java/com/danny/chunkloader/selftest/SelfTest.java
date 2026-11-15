package com.danny.chunkloader.selftest;

import com.danny.chunkloader.ChunkLoaderPlugin;
import com.danny.chunkloader.model.ChunkKey;
import com.danny.chunkloader.model.ChunkState;
import com.danny.chunkloader.platform.FoliaSupport;
import com.danny.chunkloader.storage.ChunkControlDao;
import com.danny.chunkloader.storage.Database;
import com.danny.chunkloader.ticket.NmsBridge;
import com.danny.chunkloader.ticket.TicketController;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * {@code /cl admin selftest} 的实现。
 *
 * <p><b>为什么必须有这个东西</b>：本插件的弱加载依赖 NMS 反射，而 Folia 1.20.1
 * 已停止更新、构建被冻结。管理员换一个构建后，"插件还能不能用"这个问题
 * 只能靠实测回答：真的加一个 32 级 ticket，真的读回等级，再移除。
 * 只看「反射解析成功」是不够的 —— 那只能证明名字对得上。
 *
 * <p><b>测试点选在远离出生点与玩家的位置</b>：玩家附近的原版 ticket 会给出
 * ≤31 的等级，把我们的 32 遮蔽掉，读回来的就不是我们加的 ticket 了。
 *
 * <p><b>副作用</b>：会在测试坐标处触发一次区块加载（可能生成地形）。
 * 报告里会明确写出坐标，便于管理员知晓。
 */
public final class SelfTest {

    private static final Logger LOG = Logger.getLogger("ChunkLoaderController");

    /** 测试点相对出生点的区块偏移，避开 spawn chunks（默认 5×5 强加载）。 */
    private static final int TEST_OFFSET_CHUNKS = 200;
    /** 轮询间隔 tick：ticket 变更在 tick 中处理，不是瞬时生效。 */
    private static final long RETRY_INTERVAL_TICKS = 20L;
    /** 最多轮询次数。 */
    private static final int MAX_ATTEMPTS = 8;

    private final ChunkLoaderPlugin plugin;
    private final Database db;
    private final ChunkControlDao chunkDao;
    private final NmsBridge nms;
    private final TicketController tickets;

    public SelfTest(ChunkLoaderPlugin plugin, Database db, ChunkControlDao chunkDao,
                    NmsBridge nms, TicketController tickets) {
        this.plugin = plugin;
        this.db = db;
        this.chunkDao = chunkDao;
        this.nms = nms;
        this.tickets = tickets;
    }

    /** 一条检查结果。 */
    private record Check(String name, Outcome outcome, String detail) {
        enum Outcome { PASS, FAIL, SKIP }
    }

    /** 立即可以判定的检查（不涉及 ticket）。异步部分随后补充。 */
    public void run(CommandSender viewer) {
        List<Check> results = new ArrayList<>();

        // 1. Folia 环境
        boolean folia = FoliaSupport.isFolia();
        results.add(new Check("Folia 环境", folia ? Check.Outcome.PASS : Check.Outcome.FAIL,
                folia ? FoliaSupport.describeServer()
                        : "不是 Folia —— 本插件只在 Folia 上运行"));

        // 2. 服务端版本
        results.add(new Check("服务端版本", Check.Outcome.PASS, FoliaSupport.describeServer()));

        // 3. SQLite 驱动
        results.add(new Check("SQLite 驱动", Check.Outcome.PASS,
                "sqlite-jdbc " + db.driverVersion() + " / SQLite " + db.sqliteVersion()));

        // 4. 数据库读写往返
        results.add(checkDatabaseRoundTrip());

        // 5. GUI 槽位映射（纯逻辑，可脱离玩家验证）
        String layoutIssue = com.danny.chunkloader.gui.ChunkMapGui.selfCheckLayout();
        results.add(new Check("GUI 槽位映射",
                layoutIssue == null ? Check.Outcome.PASS : Check.Outcome.FAIL,
                layoutIssue == null
                        ? "45 格 slot↔坐标 往返一致，中心格与信息栏正确"
                        : layoutIssue));

        // 6. NMS 反射解析
        results.add(new Check("NMS 反射解析",
                nms.isAvailable() ? Check.Outcome.PASS : Check.Outcome.FAIL,
                nms.isAvailable()
                        ? "ChunkHolderManager.addTicketAtLevel 可解析，弱加载可用"
                        : "弱加载不可用：" + nms.failureReason()));

        // 7 & 8. ticket 实测：需要异步等待，所以先把已有的打印出来，实测完成后追加
        World world = pickTestWorld();
        if (world == null) {
            results.add(new Check("ticket 实测", Check.Outcome.SKIP, "没有已加载的世界"));
            print(viewer, results);
            return;
        }

        Location spawn = world.getSpawnLocation();
        int baseChunkX = spawn.getBlockX() >> 4;
        int baseChunkZ = spawn.getBlockZ() >> 4;
        // 强加载测试点与弱加载测试点隔开 8 个区块：
        // level 31 传两步就到 33，8 格距离不会互相干扰。
        int strongX = baseChunkX + TEST_OFFSET_CHUNKS;
        int strongZ = baseChunkZ;
        int weakX = baseChunkX + TEST_OFFSET_CHUNKS;
        int weakZ = baseChunkZ + 8;

        viewer.sendMessage("§7自检进行中：正在实测 ticket（需要几秒）…");

        // 强加载：公开 API，level 31
        boolean strongAdded = tryAddStrong(world, strongX, strongZ);
        // 弱加载：NMS，level 32（不可用则跳过）
        boolean weakAdded = tryAddWeak(world, weakX, weakZ);

        long start = System.nanoTime();
        pollQuietly(viewer, results, world, strongX, strongZ, 31, strongAdded,
                weakX, weakZ, 32, weakAdded, 1, start);
    }

    /**
     * 轮询实测等级。用 GlobalRegionScheduler 延迟执行，避免占用 region 线程。
     */
    private void pollQuietly(CommandSender viewer, List<Check> results, World world,
                             int strongX, int strongZ, int strongExpected, boolean strongAdded,
                             int weakX, int weakZ, int weakExpected, boolean weakAdded,
                             int attempt, long startNanos) {
        Bukkit.getGlobalRegionScheduler().runDelayed(plugin, task -> {
            Integer strongLevel = readLevelOrNull(world, strongX, strongZ);
            Integer weakLevel = readLevelOrNull(world, weakX, weakZ);

            boolean strongOk = !strongAdded || Integer.valueOf(strongExpected).equals(strongLevel);
            boolean weakOk = !weakAdded || Integer.valueOf(weakExpected).equals(weakLevel);
            // NMS 不可用时等级读不出来，轮询也没意义（永远 null），直接出报告。
            boolean cannotVerify = !nms.isAvailable();

            if ((strongOk && weakOk) || cannotVerify || attempt >= MAX_ATTEMPTS) {
                long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;

                // 强加载
                // 注意：等级读取本身依赖 NMS。NMS 不可用时我们无法「验证」强加载是否真的生效，
                // 此时不能算成功、也不能算失败 —— 只能如实报告「加了 ticket 但无法读取验证」。
                if (!strongAdded) {
                    results.add(new Check("强加载 ticket (31)", Check.Outcome.FAIL,
                            "加 ticket 时抛异常，详见日志"));
                } else if (!nms.isAvailable()) {
                    results.add(new Check("强加载 ticket (31)", Check.Outcome.SKIP,
                            "addPluginChunkTicket 未抛异常，但读取实际等级依赖 NMS，无法验证"));
                } else if (strongOk) {
                    results.add(new Check("强加载 ticket (31)", Check.Outcome.PASS,
                            "实测 level " + strongLevel + "（" + strongX + "," + strongZ
                                    + "，耗时 " + elapsedMs + " ms）"));
                } else {
                    results.add(new Check("强加载 ticket (31)", Check.Outcome.FAIL,
                            "期望 " + strongExpected + "，实测 " + describe(strongLevel)
                                    + "（" + strongX + "," + strongZ + "）"));
                }

                // 弱加载
                if (!nms.isAvailable()) {
                    results.add(new Check("弱加载 ticket (32)", Check.Outcome.SKIP,
                            "NMS 不可用，已降级运行（强加载不受影响）"));
                } else if (!weakAdded) {
                    results.add(new Check("弱加载 ticket (32)", Check.Outcome.FAIL,
                            "加 ticket 时抛异常，详见日志"));
                } else if (weakOk) {
                    results.add(new Check("弱加载 ticket (32)", Check.Outcome.PASS,
                            "实测 level " + weakLevel + "（" + weakX + "," + weakZ
                                    + "，耗时 " + elapsedMs + " ms）"));
                } else {
                    results.add(new Check("弱加载 ticket (32)", Check.Outcome.FAIL,
                            "期望 " + weakExpected + "，实测 " + describe(weakLevel)
                                    + "（" + weakX + "," + weakZ + "）"));
                }

                // 无论成败都必须清理，避免留下孤儿 ticket
                cleanup(world, strongX, strongZ, strongAdded, weakX, weakZ, weakAdded);

                print(viewer, results);
                log(results);
                return;
            }

            pollQuietly(viewer, results, world, strongX, strongZ, strongExpected, strongAdded,
                    weakX, weakZ, weakExpected, weakAdded, attempt + 1, startNanos);
        }, RETRY_INTERVAL_TICKS);
    }

    private boolean tryAddStrong(World world, int x, int z) {
        try {
            tickets.setStrong(world, x, z);
            return true;
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "自检：加强加载 ticket 失败", e);
            return false;
        }
    }

    private boolean tryAddWeak(World world, int x, int z) {
        if (!nms.isAvailable()) {
            return false;
        }
        try {
            tickets.setWeak(world, x, z);
            return true;
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "自检：加弱加载 ticket 失败", e);
            return false;
        }
    }

    private void cleanup(World world, int strongX, int strongZ, boolean strongAdded,
                         int weakX, int weakZ, boolean weakAdded) {
        try {
            if (strongAdded) {
                world.removePluginChunkTicket(strongX, strongZ, plugin);
            }
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "自检：清理强加载 ticket 失败", e);
        }
        try {
            if (weakAdded && nms.isAvailable()) {
                nms.removeWeakTicket(world, weakX, weakZ, 32, plugin);
            }
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "自检：清理弱加载 ticket 失败", e);
        }
    }

    private Integer readLevelOrNull(World world, int x, int z) {
        try {
            OptionalInt level = tickets.actualLevel(world, x, z);
            return level.isPresent() ? level.getAsInt() : null;
        } catch (RuntimeException e) {
            LOG.log(Level.FINE, "自检：读等级失败", e);
            return null;
        }
    }

    private static String describe(Integer level) {
        return level == null ? "未跟踪（区块无 holder）" : String.valueOf(level);
    }

    /**
     * 数据库读写往返：用独立的世界名，不碰真实数据。
     */
    private Check checkDatabaseRoundTrip() {
        ChunkKey key = new ChunkKey("__selftest__", 0, 0);
        UUID owner = new UUID(0L, 0L);
        try {
            chunkDao.upsert(key, owner, ChunkState.WEAK, System.currentTimeMillis());
            var found = chunkDao.find(key);
            if (found.isEmpty()) {
                return new Check("数据库读写", Check.Outcome.FAIL, "写入后读不到记录");
            }
            if (!found.get().owner().equals(owner) || found.get().state() != ChunkState.WEAK) {
                return new Check("数据库读写", Check.Outcome.FAIL,
                        "读回的内容与写入不一致: " + found.get());
            }
            chunkDao.release(key);
            if (chunkDao.find(key).isPresent()) {
                return new Check("数据库读写", Check.Outcome.FAIL, "删除后仍能读到记录");
            }
            return new Check("数据库读写", Check.Outcome.PASS,
                    db.jdbcUrl().replaceAll("\\?.*$", "") + "（写入/读取/删除往返正常）");
        } catch (Exception e) {
            LOG.log(Level.WARNING, "自检：数据库往返失败", e);
            try {
                chunkDao.release(key);
            } catch (Exception ignored) {
                // 清理失败不影响结论
            }
            return new Check("数据库读写", Check.Outcome.FAIL, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private World pickTestWorld() {
        List<World> worlds = Bukkit.getWorlds();
        return worlds.isEmpty() ? null : worlds.get(0);
    }

    private void print(CommandSender viewer, List<Check> results) {
        var lines = new ArrayList<String>();
        lines.add("§8§m                                                 ");
        lines.add("§6§lChunkLoaderController 自检报告");
        lines.add("§8§m                                                 ");

        int index = 1;
        int passCount = 0;
        int failCount = 0;
        int skipCount = 0;
        for (Check c : results) {
            String mark;
            switch (c.outcome()) {
                case PASS -> {
                    mark = "§a[通过]";
                    passCount++;
                }
                case FAIL -> {
                    mark = "§c[失败]";
                    failCount++;
                }
                default -> {
                    mark = "§e[跳过]";
                    skipCount++;
                }
            }
            lines.add("§7" + (index++) + ". " + mark + " §f" + c.name());
            lines.add("      §8" + c.detail());
        }

        lines.add("§8§m                                                 ");
        if (failCount == 0) {
            lines.add("§a结论：全部通过（" + passCount + " 通过"
                    + (skipCount > 0 ? "，" + skipCount + " 跳过" : "") + "）"
                    + (nms.isAvailable() ? "，弱加载与强加载均可用" : "，但弱加载不可用"));
        } else {
            lines.add("§c结论：有 " + failCount + " 项失败"
                    + (skipCount > 0 ? "，" + skipCount + " 项跳过" : "")
                    + "。弱加载不可用时请检查服务端构建是否与插件匹配");
        }
        lines.forEach(viewer::sendMessage);
    }

    private void log(List<Check> results) {
        StringBuilder sb = new StringBuilder("自检结果: ");
        for (Check c : results) {
            sb.append('[').append(c.outcome()).append("] ").append(c.name())
                    .append(" — ").append(c.detail()).append("; ");
        }
        boolean anyFail = results.stream().anyMatch(c -> c.outcome() == Check.Outcome.FAIL);
        if (anyFail) {
            LOG.warning(sb.toString());
        } else {
            LOG.info(sb.toString());
        }

        // 机器可读的 ASCII 摘要行。
        // 存在的理由：Windows 上 JDK 17 的 file.encoding 默认是 GBK，
        // 日志文件里中文会以 GBK 落盘；外部脚本按 UTF-8 读时会匹配不到中文关键字。
        // 这一行只有 ASCII，任何编码下都能稳定匹配，方便管理员/CI 判断结果。
        int pass = 0;
        int fail = 0;
        int skip = 0;
        for (Check c : results) {
            switch (c.outcome()) {
                case PASS -> pass++;
                case FAIL -> fail++;
                default -> skip++;
            }
        }
        LOG.info("SELFTEST_RESULT pass=" + pass + " fail=" + fail + " skip=" + skip
                + " weak=" + (nms.isAvailable() ? "available" : "unavailable"));
    }
}
