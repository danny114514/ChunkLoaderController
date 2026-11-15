package com.danny.chunkloader;

import com.danny.chunkloader.command.ChunkLoaderCommand;
import com.danny.chunkloader.config.PluginConfig;
import com.danny.chunkloader.egg.EasterEggListener;
import com.danny.chunkloader.gui.ChunkMapGui;
import com.danny.chunkloader.gui.GuiListener;
import com.danny.chunkloader.gui.MyChunksGui;
import com.danny.chunkloader.model.ChunkControl;
import com.danny.chunkloader.platform.FoliaSupport;
import com.danny.chunkloader.selftest.SelfTest;
import com.danny.chunkloader.service.ChunkControlService;
import com.danny.chunkloader.service.QuotaService;
import com.danny.chunkloader.storage.AuditLogDao;
import com.danny.chunkloader.storage.ChunkControlDao;
import com.danny.chunkloader.storage.Database;
import com.danny.chunkloader.ticket.FoliaTicketController;
import com.danny.chunkloader.ticket.NmsBridge;
import com.danny.chunkloader.ticket.TicketController;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.SQLException;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 插件主类（Folia 1.20.1）。
 *
 * <p>启动顺序刻意固定为：环境检查 → 配置 → 数据库 → NMS 探测 → 调度任务 → 命令。
 * 任何一步失败都要给出可操作的报错，而不是让插件处于半初始化状态。
 */
public final class ChunkLoaderPlugin extends JavaPlugin {

    private static final Logger LOG = Logger.getLogger("ChunkLoaderController");

    private PluginConfig config;
    private Database database;
    private ChunkControlDao chunkDao;
    private AuditLogDao auditDao;
    private NmsBridge nmsBridge;
    private TicketController tickets;
    private QuotaService quota;
    private ChunkControlService control;
    private ChunkMapGui mapGui;
    private MyChunksGui myChunksGui;
    private EasterEggListener easterEgg;

    @Override
    public void onEnable() {
        // ── 0. 环境检查：非 Folia 直接拒绝──────────────
        if (!FoliaSupport.isFolia()) {
            String server = FoliaSupport.describeServer();
            LOG.severe("==========================================================");
            LOG.severe("ChunkLoaderController 拒绝启动：当前服务端不是 Folia。");
            LOG.severe("检测到的服务端: " + server);
            LOG.severe("本插件依赖 Folia 的 region 线程模型与 ticket 语义，");
            LOG.severe("在 Paper/Spigot 上运行会产生偶发数据竞争（比直接拒绝更糟）。");
            LOG.severe("已自动禁用本插件。请改用 Folia 1.20.1 运行。");
            LOG.severe("==========================================================");
            // 用异常把插件踢出 enabled 状态，避免后续注册出半残的命令。
            throw new IllegalStateException(
                    "ChunkLoaderController 只支持 Folia，当前服务端为 " + server);
        }

        // ── 1. 配置 ─────────────────────────────────────────────────────────
        saveDefaultConfig();
        this.config = PluginConfig.from(this);
        if (!"sqlite".equalsIgnoreCase(config.databaseType())) {
            LOG.warning("database.type=" + config.databaseType()
                    + " 暂不支持（当前版本只实现 sqlite），已回退为 sqlite");
        }

        // ── 2. 数据库 ───────────────────────────────────────────────────────
        if (!getDataFolder().exists() && !getDataFolder().mkdirs()) {
            throw new IllegalStateException("无法创建插件数据目录: " + getDataFolder());
        }
        File dbFile = new File(getDataFolder(), config.databaseFile());
        try {
            this.database = Database.open(dbFile.getAbsolutePath());
            this.chunkDao = new ChunkControlDao(database);
            this.auditDao = new AuditLogDao(database);
        } catch (SQLException e) {
            LOG.log(Level.SEVERE, "数据库初始化失败，插件无法工作", e);
            throw new IllegalStateException("数据库初始化失败: " + e.getMessage(), e);
        }
        LOG.info("数据库就绪: " + dbFile.getAbsolutePath()
                + "（SQLite " + database.sqliteVersion() + "）");

        // ── 3. NMS 探测（弱加载可用性）────────────────────────────────────────
        this.nmsBridge = new NmsBridge();
        World probe = Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);
        boolean weakOk = nmsBridge.resolve(probe);
        this.tickets = new FoliaTicketController(this, nmsBridge);
        if (!weakOk) {
            LOG.warning("弱加载不可用，将降级运行：强加载仍正常，弱加载在 GUI 中置灰并给出原因");
        }

        // ── 4. 业务服务 ─────────────────────────────────────────────────────
        this.quota = new QuotaService(chunkDao);
        this.control = new ChunkControlService(chunkDao, auditDao, quota, tickets, () -> config);

        // ── 5. 启动恢复：按数据库记录全量重建 ticket ────────────────────────
        // 这一步是「重启后区块仍然受控」的实现：数据库是事实来源，启动时全量重建 ticket。
        int restored = control.restoreAll(Bukkit::getWorld);
        if (restored > 0) {
            LOG.info("启动恢复：已重建 " + restored + " 个区块的 ticket");
        }

        // ── 6. 定时任务：审计日志清理 ────────────────────────────────────────
        scheduleLogPruning();

        // ── 7. 命令与 GUI ───────────────────────────────────────────────────
        this.mapGui = new ChunkMapGui(this);
        this.myChunksGui = new MyChunksGui(this);
        getServer().getPluginManager().registerEvents(new GuiListener(this), this);

        // 彩蛋：玩家聊到触发词时在其位置召唤闪电。
        // 即使配置里关掉了也照常注册监听器 —— 检查放在事件回调里，
        // 这样 /cl admin reload 改了开关能立刻生效，不需要重启。
        this.easterEgg = new EasterEggListener(this);
        getServer().getPluginManager().registerEvents(easterEgg, this);
        if (config.easterEggEnabled()) {
            // 日志里不写触发词：服务端日志会被很多人看到（包括备份、工单附件），
            // 写出来就等于公开剧透。只声明「有这么一个东西、它是开的」。
            LOG.info("彩蛋已启用（" + config.easterEggMode()
                    + " 模式，冷却 " + config.easterEggCooldownSeconds() + " 秒）");
        }

        PluginCommand command = getCommand("chunkloader");
        if (command == null) {
            throw new IllegalStateException("plugin.yml 未声明命令 chunkloader");
        }
        ChunkLoaderCommand handler = new ChunkLoaderCommand(this);
        command.setExecutor(handler);
        command.setTabCompleter(handler);

        // ── 8. 完成 ─────────────────────────────────────────────────────────
        LOG.info("ChunkLoaderController 已启用（Folia 1.20.1）。"
                + "弱加载=" + (weakOk ? "可用" : "不可用")
                + "，配额 " + config.maxTotalChunks() + "/世界，强加载 " + config.maxStrongChunks()
                + "，全服上限 " + config.globalMaxTotalChunks() + "/" + config.globalMaxStrongChunks());
        if (weakOk) {
            LOG.info("提示：弱加载（32）冻结实体刻 —— 掉落物不动、怪物冻结、刷怪塔不产出；"
                    + "作物/熔炉/漏斗/区块内红石正常。");
        }
    }

    @Override
    public void onDisable() {
        // 清理本插件加过的所有 ticket：服务端卸载插件后不应留下孤儿 ticket 继续占资源。
        // 注意：这里只清本插件实例的 ticket，不会影响别的插件。
        if (tickets != null && !Bukkit.getWorlds().isEmpty()) {
            int cleared = 0;
            try {
                List<ChunkControl> all = chunkDao == null ? List.of() : chunkDao.listAll();
                for (ChunkControl c : all) {
                    World w = Bukkit.getWorld(c.world());
                    if (w == null) {
                        continue;
                    }
                    tickets.clear(w, c.chunkX(), c.chunkZ());
                    cleared++;
                }
            } catch (SQLException e) {
                LOG.log(Level.WARNING, "关闭时读取受控区块列表失败，可能有 ticket 未清理", e);
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "关闭时清理 ticket 出错", e);
            }
            LOG.info("关闭清理：已移除 " + cleared + " 个区块的 ticket（记录保留在数据库中）");
        }
        if (database != null) {
            database.close();
        }
    }

    /**
     * 每天检查并删除过期的审计日志（保留天数由 {@code log-retention-days} 决定）。
     *
     * <p>用 {@code GlobalRegionScheduler} 而<b>不是</b> {@code BukkitScheduler}：
     * 后者在 Folia 上直接抛 {@code UnsupportedOperationException}。
     */
    private void scheduleLogPruning() {
        int days = config.logRetentionDays();
        if (days <= 0) {
            LOG.info("log-retention-days=0，审计日志永不自动清理");
            return;
        }
        long periodTicks = 24L * 60L * 60L * 20L; // 一天 = 72000 tick
        Bukkit.getGlobalRegionScheduler().runAtFixedRate(this, task -> {
            long cutoff = System.currentTimeMillis() - (long) days * 24L * 60L * 60L * 1000L;
            try {
                int removed = auditDao.pruneBefore(cutoff);
                if (removed > 0) {
                    LOG.info("审计日志清理：删除 " + removed + " 条早于 " + days + " 天前的记录");
                }
            } catch (SQLException e) {
                LOG.log(Level.WARNING, "审计日志清理失败", e);
            }
        }, 20L * 60L, periodTicks); // 首次延迟 1 分钟，之后每天一次
    }

    // ── 访问器 ─────────────────────────────────────────────────────────────

    public PluginConfig cfg() {
        return config;
    }

    public Database database() {
        return database;
    }

    public ChunkControlDao chunks() {
        return chunkDao;
    }

    public AuditLogDao audit() {
        return auditDao;
    }

    public NmsBridge nms() {
        return nmsBridge;
    }

    public TicketController tickets() {
        return tickets;
    }

    public QuotaService quota() {
        return quota;
    }

    public ChunkControlService control() {
        return control;
    }

    public ChunkMapGui mapGui() {
        return mapGui;
    }

    /** 彩蛋监听器（仅用于玩家下线时清理冷却记录）。 */
    public EasterEggListener easterEgg() {
        return easterEgg;
    }

    public MyChunksGui myChunksGui() {
        return myChunksGui;
    }

    /** 重载配置。只换配置快照，不触碰数据库中已有的控制记录。 */
    public void reloadPluginConfig() {
        reloadConfig();
        this.config = PluginConfig.from(this);
    }

    // ── 自检：把所需依赖打成一个对象，命令层直接调用 ──────────────────────

    /** 执行 {@code /cl admin selftest}。 */
    public void runSelfTest(org.bukkit.command.CommandSender viewer) {
        new SelfTest(this, database, chunkDao, nmsBridge, tickets).run(viewer);
    }
}
