package com.danny.chunkloader.service;

import com.danny.chunkloader.config.PluginConfig;
import com.danny.chunkloader.model.ChunkControl;
import com.danny.chunkloader.model.ChunkKey;
import com.danny.chunkloader.model.ChunkState;
import com.danny.chunkloader.storage.AuditAction;
import com.danny.chunkloader.storage.AuditLogDao;
import com.danny.chunkloader.storage.AuditResult;
import com.danny.chunkloader.storage.ChunkControlDao;
import com.danny.chunkloader.ticket.TicketController;
import org.bukkit.World;

import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 区块控制的唯一业务入口：校验 → 执行 → 落库 → 审计。
 *
 * <p><b>校验顺序是硬性约定</b>，顺序变了会产生误导性报错：
 * <ol>
 *   <li><b>归属</b>：别人的区块 → {@code DENIED_NOT_OWNER}</li>
 *   <li><b>能力</b>：弱加载但 NMS 不可用 → {@code DENIED_NO_NMS}</li>
 *   <li><b>配额</b>：四道闸门（本世界总量/强加载、全服总量/强加载）</li>
 *   <li><b>执行</b>：先落库，再动 ticket</li>
 *   <li><b>回填</b>：由 GUI 层读取实际等级</li>
 * </ol>
 *
 * <p><b>为什么先落库再动 ticket</b>：ticket 生效是异步的（下一个 tick 才处理），
 * 而数据库写入是同步的。如果顺序反过来，出现「ticket 已加但库没写成功」就无处可查；
 * 反过来则是「库有记录但 ticket 未生效」，重启时全量重建会自然修复。
 * 数据库是<b>事实来源</b>，ticket 是它的投影。
 */
public final class ChunkControlService {

    private static final Logger LOG = Logger.getLogger("ChunkLoaderController");

    private final ChunkControlDao chunkDao;
    private final AuditLogDao auditDao;
    private final QuotaService quota;
    private final TicketController tickets;
    private final Supplier<PluginConfig> configSupplier;

    public ChunkControlService(ChunkControlDao chunkDao, AuditLogDao auditDao, QuotaService quota,
                               TicketController tickets, Supplier<PluginConfig> configSupplier) {
        this.chunkDao = chunkDao;
        this.auditDao = auditDao;
        this.quota = quota;
        this.tickets = tickets;
        this.configSupplier = configSupplier;
    }

    /**
     * 操作结果。
     *
     * @param result   审计结果码
     * @param message  给玩家/管理员看的说明
     * @param changed  是否真的改动了状态
     */
    public record Outcome(AuditResult result, String message, boolean changed) {
        public boolean ok() {
            return result == AuditResult.OK;
        }
    }

    /**
     * 设置某区块的状态。
     *
     * @param actor          操作者（玩家 UUID；控制台为 null）
     * @param owner          该区块要归属的玩家（通常等于 actor；管理员代操作时不同）
     * @param world          目标世界
     * @param chunkX         区块 X
     * @param chunkZ         区块 Z
     * @param target         目标状态
     * @param bypassOwnership 是否跳过归属检查（需要 {@code chunkloader.admin.others}）
     * @return 操作结果
     */
    public Outcome setState(UUID actor, UUID owner, World world, int chunkX, int chunkZ,
                            ChunkState target, boolean bypassOwnership) {
        PluginConfig cfg = configSupplier.get();
        ChunkKey key = new ChunkKey(world.getName(), chunkX, chunkZ);
        AuditAction action = resolveAction(target, bypassOwnership);

        try {
            // ── 1. 归属检查─────────────────────────────
            Optional<ChunkControl> existing = chunkDao.find(key);
            if (existing.isPresent()) {
                ChunkControl cur = existing.get();
                if (!cur.isOwnedBy(owner)) {
                    if (!bypassOwnership) {
                        return deny(actor, owner, key, action, AuditResult.DENIED_NOT_OWNER,
                                "该区块已被其他玩家控制（" + shortUuid(cur.owner()) + "）。"
                                        + "一个区块只有一个持有者，先到先得。");
                    }
                    // 管理员代操作：允许接管，但要记录
                    LOG.info("管理员 " + actor + " 接管了 " + key + "（原持有者 " + cur.owner() + "）");
                }
            }
            ChunkState currentState = existing.map(ChunkControl::state).orElse(null);
            boolean sameOwner = existing.map(ChunkControl::owner).map(owner::equals).orElse(false);

            // 状态未变化：直接返回，不写库、不写审计（避免点击噪声刷日志）
            if (currentState == target) {
                if (target.isControlled()) {
                    return new Outcome(AuditResult.OK,
                            "该区块已经是" + target.displayName() + "，无需改动。", false);
                }
                return new Outcome(AuditResult.OK, "该区块本来就是未控制状态。", false);
            }

            // ── 2. 能力检查────────────────────────────
            if (target == ChunkState.WEAK && !tickets.weakAvailable()) {
                return deny(actor, owner, key, action, AuditResult.DENIED_NO_NMS,
                        "弱加载当前不可用（插件与服务端构建不匹配）。"
                                + "强加载不受影响，仍可正常使用。原因: "
                                + tickets.weakUnavailableReason());
            }

            // ── 3. 配额检查────────────────────────────
            QuotaService.QuotaCheck quotaCheck = quota.check(
                    owner, world.getName(), target, sameOwner, currentState, cfg);
            if (!quotaCheck.allowed()) {
                return deny(actor, owner, key, action, quotaCheck.reason(), quotaCheck.detail());
            }

            // ── 4. 执行────────────────────────────────
            long now = System.currentTimeMillis();

            // 4a. 先落库（数据库是事实来源）
            if (target.isControlled()) {
                chunkDao.upsert(key, owner, target, now);
            } else {
                chunkDao.release(key);
            }

            // 4b. 再动 ticket（投影）。这一步失败不影响一致性：
            //     库是权威，重启时全量重建会修复。
            applyTicket(world, chunkX, chunkZ, target);

            // ── 5. 审计 ────────────────────────────────────────────────────
            log(actor, owner, key, action, AuditResult.OK, null);

            String msg = switch (target) {
                case WEAK -> "已设为弱加载（32）：方块刻继续，实体刻冻结。";
                case STRONG -> "已设为强加载（31）：实体刻与方块刻全部运行。";
                case UNCONTROLLED -> "已释放该区块，恢复为原版自动加载。";
            };
            return new Outcome(AuditResult.OK, msg, true);
        } catch (SQLException e) {
            LOG.log(Level.SEVERE, "设置区块状态时数据库出错 " + key, e);
            log(actor, owner, key, action, AuditResult.ERROR, e.getClass().getSimpleName() + ": " + e.getMessage());
            return new Outcome(AuditResult.ERROR, "数据库错误，操作未完成：" + e.getMessage(), false);
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "设置区块状态时 ticket 出错 " + key, e);
            log(actor, owner, key, action, AuditResult.ERROR, e.getClass().getSimpleName() + ": " + e.getMessage());
            return new Outcome(AuditResult.ERROR, "ticket 操作失败：" + e.getMessage(), false);
        }
    }

    /**
     * 释放某玩家在指定世界的全部区块。
     */
    public Outcome releaseAll(UUID actor, UUID owner, World world, boolean bypassOwnership) {
        ChunkKey noKey = null;
        try {
            if (!bypassOwnership && actor != null && !actor.equals(owner)) {
                return deny(actor, owner, noKey, AuditAction.ADMIN_RELEASE,
                        AuditResult.DENIED_NO_PERM, "只能释放自己的区块（需要 chunkloader.admin.others 才能操作他人）");
            }

            var list = chunkDao.listByOwner(owner, world.getName());
            if (list.isEmpty()) {
                return new Outcome(AuditResult.OK, "该玩家在本世界没有受控区块。", false);
            }

            int released = chunkDao.releaseAll(owner, world.getName());
            // 逐个清 ticket。这里必须逐个做，因为 ticket 是按区块管理的。
            for (ChunkControl c : list) {
                try {
                    tickets.clear(world, c.chunkX(), c.chunkZ());
                } catch (RuntimeException e) {
                    LOG.log(Level.WARNING, "清理 ticket 失败 " + c.key(), e);
                }
            }

            AuditAction action = bypassOwnership ? AuditAction.ADMIN_RELEASE : AuditAction.RELEASE_ALL;
            log(actor, owner, noKey, action, AuditResult.OK,
                    "world=" + world.getName() + ", count=" + released);

            return new Outcome(AuditResult.OK,
                    "已释放 " + released + " 个区块（" + world.getName() + "）。", true);
        } catch (SQLException e) {
            LOG.log(Level.SEVERE, "释放全部区块时数据库出错", e);
            log(actor, owner, noKey, AuditAction.RELEASE_ALL, AuditResult.ERROR, e.getMessage());
            return new Outcome(AuditResult.ERROR, "数据库错误：" + e.getMessage(), false);
        }
    }

    /**
     * 启动恢复：按数据库记录全量重建 ticket。
     *
     * @return 成功重建的数量
     */
    public int restoreAll(java.util.function.Function<String, World> worldLookup) {
        int restored = 0;
        int skippedWorld = 0;
        try {
            for (ChunkControl c : chunkDao.listAll()) {
                World world = worldLookup.apply(c.world());
                if (world == null) {
                    // 世界被删掉/改名：记录留着但无从恢复。不删记录，避免误删数据。
                    skippedWorld++;
                    continue;
                }
                try {
                    applyTicket(world, c.chunkX(), c.chunkZ(), c.state());
                    restored++;
                } catch (RuntimeException e) {
                    LOG.log(Level.WARNING, "恢复 ticket 失败 " + c.key(), e);
                }
            }
        } catch (SQLException e) {
            LOG.log(Level.SEVERE, "启动恢复时读取数据库失败", e);
        }
        if (skippedWorld > 0) {
            LOG.warning("有 " + skippedWorld + " 条记录所属的世界当前未加载，已跳过（记录保留）");
        }
        return restored;
    }

    /** 读某区块的实际等级（供 GUI / info 用）。 */
    public Optional<Integer> actualLevel(World world, int chunkX, int chunkZ) {
        var level = tickets.actualLevel(world, chunkX, chunkZ);
        return level.isPresent() ? Optional.of(level.getAsInt()) : Optional.empty();
    }

    /** 查某区块的记录。 */
    public Optional<ChunkControl> find(String world, int chunkX, int chunkZ) {
        try {
            return chunkDao.find(new ChunkKey(world, chunkX, chunkZ));
        } catch (SQLException e) {
            LOG.log(Level.WARNING, "查询区块记录失败", e);
            return Optional.empty();
        }
    }

    // ── 内部 ────────────────────────────────────────────────────────────────

    /**
     * 把状态投影到 ticket 上。
     *
     * <p>先 clear 再 set：避免历史遗留的另一种 ticket 残留
     * （例如把强改成弱时，若只加 32 不删 31，实际等级仍是 31）。
     */
    private void applyTicket(World world, int chunkX, int chunkZ, ChunkState state) {
        tickets.clear(world, chunkX, chunkZ);
        if (state == ChunkState.WEAK) {
            tickets.setWeak(world, chunkX, chunkZ);
        } else if (state == ChunkState.STRONG) {
            tickets.setStrong(world, chunkX, chunkZ);
        }
        // UNCONTROLLED：clear 已经完成全部工作
    }

    private static AuditAction resolveAction(ChunkState target, boolean bypassOwnership) {
        if (bypassOwnership) {
            return AuditAction.ADMIN_SET;
        }
        return target == ChunkState.STRONG ? AuditAction.SET_STRONG
                : target == ChunkState.WEAK ? AuditAction.SET_WEAK
                : AuditAction.RELEASE;
    }

    private Outcome deny(UUID actor, UUID owner, ChunkKey key, AuditAction action,
                         AuditResult result, String message) {
        log(actor, owner, key, action, result, message);
        return new Outcome(result, message, false);
    }

    /**
     * 写审计。失败只记 warning，<b>不影响</b>本次操作的结论 ——
     * 日志表出问题不该让玩家的操作失败。
     */
    private void log(UUID actor, UUID target, ChunkKey key, AuditAction action,
                     AuditResult result, String reason) {
        try {
            auditDao.log(actor, target, key, action, result, reason);
        } catch (SQLException e) {
            LOG.log(Level.WARNING, "写审计日志失败（不影响本次操作）", e);
        }
    }

    private static String shortUuid(UUID uuid) {
        String s = uuid.toString();
        return s.length() >= 8 ? s.substring(0, 8) + "…" : s;
    }
}
