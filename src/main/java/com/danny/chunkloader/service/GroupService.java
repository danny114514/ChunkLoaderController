package com.danny.chunkloader.service;

import com.danny.chunkloader.config.PluginConfig;
import com.danny.chunkloader.model.ChunkControl;
import com.danny.chunkloader.model.ChunkGroup;
import com.danny.chunkloader.model.ChunkKey;
import com.danny.chunkloader.model.ChunkState;
import com.danny.chunkloader.storage.AuditAction;
import com.danny.chunkloader.storage.AuditLogDao;
import com.danny.chunkloader.storage.AuditResult;
import com.danny.chunkloader.storage.ChunkControlDao;
import com.danny.chunkloader.storage.ChunkGroupDao;
import com.danny.chunkloader.ticket.TicketController;
import org.bukkit.World;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 群组业务：创建/删除/改名、把区块加进群组、批量改状态。
 *
 * <h2>群组解决的唯一问题</h2>
 * <p>「一次把这一批区块改成某个状态」。它不是权限系统、不是领地保护 ——
 * 归属仍然是「一个区块一个主人」，群组只是给这批区块起了个名字方便批量操作。</p>
 *
 * <h2>为什么需要一个「保留」状态</h2>
 * <p>玩家把群组区块设为「不加载」时，如果按旧规则把它当成「未控制」删掉记录，
 * 归属就丢了 —— 别人立刻能抢走。所以必须区分：</p>
 * <ul>
 *   <li>{@link ChunkState#UNCONTROLLED} —— 无主，谁都能认领</li>
 *   <li>{@link ChunkState#RESERVED} —— 有主、不加载、占配额、别人抢不走</li>
 * </ul>
 *
 * <h2>配额</h2>
 * <p>保留态一样占 {@code max-total-chunks}（否则把区块设成保留就能无限圈地），
 * 但不占 {@code max-strong-chunks}（它确实没在跑实体刻）。</p>
 */
public final class GroupService {

    private static final Logger LOG = Logger.getLogger("ChunkLoaderController");

    private final ChunkGroupDao groupDao;
    private final ChunkControlDao chunkDao;
    private final AuditLogDao auditDao;
    private final QuotaService quota;
    private final TicketController tickets;
    private final Supplier<PluginConfig> configSupplier;

    public GroupService(ChunkGroupDao groupDao, ChunkControlDao chunkDao, AuditLogDao auditDao,
                        QuotaService quota, TicketController tickets,
                        Supplier<PluginConfig> configSupplier) {
        this.groupDao = groupDao;
        this.chunkDao = chunkDao;
        this.auditDao = auditDao;
        this.quota = quota;
        this.tickets = tickets;
        this.configSupplier = configSupplier;
    }

    /** 群组操作结果。复用 {@link ChunkControlService.Outcome} 的语义，便于命令层统一处理。 */
    public record Outcome(AuditResult result, String message, boolean changed) {
        public boolean ok() {
            return result == AuditResult.OK;
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  创建 / 删除 / 改名
    // ══════════════════════════════════════════════════════════════════════

    /**
     * 创建群组。
     *
     * @param actor 操作者（玩家 UUID）
     * @param rawName 原始群组名，内部会规范化
     */
    public Outcome create(UUID actor, String rawName) {
        String name = ChunkGroup.normalizeName(rawName);
        String invalid = ChunkGroup.validateName(name);
        if (invalid != null) {
            return new Outcome(AuditResult.DENIED_BAD_INPUT, invalid, false);
        }
        try {
            if (groupDao.find(actor, name).isPresent()) {
                return new Outcome(AuditResult.DENIED_DUPLICATE_GROUP,
                        "你已经有一个叫「" + name + "」的群组了。", false);
            }
            groupDao.create(actor, name, System.currentTimeMillis());
            log(actor, null, AuditAction.GROUP_CREATE, AuditResult.OK, "name=" + name);
            return new Outcome(AuditResult.OK, "已创建群组「" + name + "」。", true);
        } catch (SQLException e) {
            LOG.log(Level.WARNING, "创建群组失败", e);
            return new Outcome(AuditResult.ERROR, "创建群组失败：" + e.getMessage(), false);
        }
    }

    /**
     * 删除群组，并<b>释放组内全部区块</b>。
     *
     * <p>这是唯一会让群组区块变成「无主」的操作 —— 玩家显式要求放弃它们。
     * 删除前必须把 ticket 一并清掉，否则记录没了但区块还在加载。</p>
     */
    public Outcome delete(UUID actor, String rawName, java.util.function.Function<String, World> worldLookup) {
        String name = ChunkGroup.normalizeName(rawName);
        try {
            Optional<ChunkGroup> found = groupDao.find(actor, name);
            if (found.isEmpty()) {
                return new Outcome(AuditResult.DENIED_NO_SUCH_GROUP,
                        "你没有叫「" + name + "」的群组。", false);
            }
            ChunkGroup group = found.get();
            List<ChunkControl> chunks = chunkDao.listByGroup(group.id());

            // 先清 ticket 再删记录：顺序反了会留下孤儿 ticket
            for (ChunkControl c : chunks) {
                World world = worldLookup.apply(c.world());
                if (world != null) {
                    try {
                        tickets.clear(world, c.chunkX(), c.chunkZ());
                    } catch (RuntimeException e) {
                        LOG.log(Level.WARNING, "删除群组时清 ticket 失败 " + c.key(), e);
                    }
                }
            }

            int released = chunkDao.releaseByGroup(group.id());
            groupDao.delete(group.id());
            log(actor, null, AuditAction.GROUP_DELETE, AuditResult.OK,
                    "name=" + name + ", released=" + released);
            return new Outcome(AuditResult.OK,
                    "已删除群组「" + name + "」，组内 " + released
                            + " 个区块已释放为未控制（他人可以认领）。", true);
        } catch (SQLException e) {
            LOG.log(Level.WARNING, "删除群组失败", e);
            return new Outcome(AuditResult.ERROR, "删除群组失败：" + e.getMessage(), false);
        }
    }

    public Outcome rename(UUID actor, String rawOld, String rawNew) {
        String oldName = ChunkGroup.normalizeName(rawOld);
        String newName = ChunkGroup.normalizeName(rawNew);
        String invalid = ChunkGroup.validateName(newName);
        if (invalid != null) {
            return new Outcome(AuditResult.DENIED_BAD_INPUT, invalid, false);
        }
        try {
            Optional<ChunkGroup> found = groupDao.find(actor, oldName);
            if (found.isEmpty()) {
                return new Outcome(AuditResult.DENIED_NO_SUCH_GROUP,
                        "你没有叫「" + oldName + "」的群组。", false);
            }
            if (groupDao.find(actor, newName).isPresent()) {
                return new Outcome(AuditResult.DENIED_DUPLICATE_GROUP,
                        "你已经有一个叫「" + newName + "」的群组了。", false);
            }
            groupDao.rename(found.get().id(), newName);
            log(actor, null, AuditAction.GROUP_RENAME, AuditResult.OK,
                    oldName + " -> " + newName);
            return new Outcome(AuditResult.OK,
                    "已把群组「" + oldName + "」改名为「" + newName + "」。", true);
        } catch (SQLException e) {
            LOG.log(Level.WARNING, "重命名群组失败", e);
            return new Outcome(AuditResult.ERROR, "重命名失败：" + e.getMessage(), false);
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  加入 / 移出
    // ══════════════════════════════════════════════════════════════════════

    /**
     * 把一个区块加入群组。
     *
     * <p>如果该区块还没有主人，会顺带创建一条记录（状态为 {@link ChunkState#RESERVED}）——
     * 「加入群组」本身就是一种认领动作。若已被他人持有则拒绝。</p>
     */
    public Outcome addChunk(UUID actor, String rawName, World world, int chunkX, int chunkZ) {
        String name = ChunkGroup.normalizeName(rawName);
        ChunkKey key = new ChunkKey(world.getName(), chunkX, chunkZ);
        try {
            Optional<ChunkGroup> found = groupDao.find(actor, name);
            if (found.isEmpty()) {
                return new Outcome(AuditResult.DENIED_NO_SUCH_GROUP,
                        "你没有叫「" + name + "」的群组。先创建它："
                                + "/cl group create " + name, false);
            }
            ChunkGroup group = found.get();
            Optional<ChunkControl> existing = chunkDao.find(key);
            long now = System.currentTimeMillis();

            if (existing.isPresent()) {
                ChunkControl cur = existing.get();
                if (!cur.isOwnedBy(actor)) {
                    return new Outcome(AuditResult.DENIED_NOT_OWNER,
                            "该区块已被其他玩家控制，你无法把它加入群组。", false);
                }
                if (cur.hasGroup() && cur.groupId() == group.id()) {
                    return new Outcome(AuditResult.OK,
                            "该区块已经在这个群组里了。", false);
                }
                if (cur.hasGroup()) {
                    return new Outcome(AuditResult.DENIED_ALREADY_IN_GROUP,
                            "该区块已属于另一个群组，请先把它移出。", false);
                }
                chunkDao.setGroup(key, group.id(), now);
                log(actor, null, AuditAction.GROUP_ADD, AuditResult.OK,
                        "group=" + name + ", chunk=" + key + ", state=" + cur.state());
                return new Outcome(AuditResult.OK,
                        "已把区块 [" + chunkX + ", " + chunkZ + "] 加入群组「" + name
                                + "」（当前状态：" + cur.state().displayName() + "）。", true);
            }

            // 无主区块：加入群组 = 认领并设为保留
            // 配额检查：这是一次新增占用
            PluginConfig cfg = configSupplier.get();
            QuotaService.QuotaCheck check = quota.check(
                    actor, world.getName(), ChunkState.RESERVED, false, null, cfg);
            if (!check.allowed()) {
                return new Outcome(check.reason(), check.detail(), false);
            }
            chunkDao.upsert(key, actor, ChunkState.RESERVED, now);
            chunkDao.setGroup(key, group.id(), now);
            log(actor, null, AuditAction.GROUP_ADD, AuditResult.OK,
                    "group=" + name + ", chunk=" + key + ", state=RESERVED(new)");
            return new Outcome(AuditResult.OK,
                    "已把区块 [" + chunkX + ", " + chunkZ + "] 加入群组「" + name
                            + "」并设为保留（不加载，但归你所有）。", true);
        } catch (SQLException e) {
            LOG.log(Level.WARNING, "加入群组失败", e);
            return new Outcome(AuditResult.ERROR, "加入群组失败：" + e.getMessage(), false);
        }
    }

    /**
     * 把一个区块移出群组。
     *
     * <p>只断开群组关系，<b>不释放归属</b> —— 区块仍然是你的，只是不再属于那个群组。
     * 想连归属一起放弃请用 {@code /cl set none}。</p>
     */
    public Outcome removeChunk(UUID actor, World world, int chunkX, int chunkZ) {
        ChunkKey key = new ChunkKey(world.getName(), chunkX, chunkZ);
        try {
            Optional<ChunkControl> existing = chunkDao.find(key);
            if (existing.isEmpty()) {
                return new Outcome(AuditResult.DENIED_NOT_OWNER,
                        "该区块没有任何控制记录。", false);
            }
            ChunkControl cur = existing.get();
            if (!cur.isOwnedBy(actor)) {
                return new Outcome(AuditResult.DENIED_NOT_OWNER,
                        "该区块不是你的，无法操作。", false);
            }
            if (!cur.hasGroup()) {
                return new Outcome(AuditResult.OK, "该区块本来就不在任何群组里。", false);
            }
            chunkDao.clearGroup(key, System.currentTimeMillis());
            log(actor, null, AuditAction.GROUP_REMOVE, AuditResult.OK, "chunk=" + key);
            return new Outcome(AuditResult.OK,
                    "已把区块 [" + chunkX + ", " + chunkZ + "] 移出群组。"
                            + "它仍然归你所有（当前：" + cur.state().displayName() + "）。", true);
        } catch (SQLException e) {
            LOG.log(Level.WARNING, "移出群组失败", e);
            return new Outcome(AuditResult.ERROR, "移出群组失败：" + e.getMessage(), false);
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  批量改状态
    // ══════════════════════════════════════════════════════════════════════

    /**
     * 把群组内所有区块一次性改成某个状态。
     *
     * <p>注意这里<b>只接受有主状态</b>（弱/强/保留）。「未控制」意味着释放归属，
     * 那是删除群组才能做的事 —— 用一个批量命令就把地全丢了太危险。</p>
     */
    public Outcome setGroupState(UUID actor, String rawName, ChunkState target,
                                 java.util.function.Function<String, World> worldLookup) {
        String name = ChunkGroup.normalizeName(rawName);
        if (!target.isControlled()) {
            return new Outcome(AuditResult.DENIED_BAD_INPUT,
                    "批量设置不支持「未控制」—— 那等于放弃这些区块。"
                            + "要释放整组请用 /cl group delete " + name, false);
        }
        if (target == ChunkState.WEAK && !tickets.weakAvailable()) {
            return new Outcome(AuditResult.DENIED_NO_NMS,
                    "弱加载当前不可用：" + tickets.weakUnavailableReason(), false);
        }

        try {
            Optional<ChunkGroup> found = groupDao.find(actor, name);
            if (found.isEmpty()) {
                return new Outcome(AuditResult.DENIED_NO_SUCH_GROUP,
                        "你没有叫「" + name + "」的群组。", false);
            }
            ChunkGroup group = found.get();
            List<ChunkControl> chunks = chunkDao.listByGroup(group.id());
            if (chunks.isEmpty()) {
                return new Outcome(AuditResult.OK,
                        "群组「" + name + "」里还没有区块。", false);
            }

            // ── 配额检查 ──────────────────────────────────────────────────
            // 只有当目标状态会新增占用时才需要检查：
            //   任何状态 -> 保留/弱     : 总量不变（本来就有记录）
            //   保留/弱   -> 强         : 强加载计数会增加，需要逐个确认余量
            PluginConfig cfg = configSupplier.get();
            if (target == ChunkState.STRONG) {
                int becomingStrong = 0;
                for (ChunkControl c : chunks) {
                    if (c.state() != ChunkState.STRONG) {
                        becomingStrong++;
                    }
                }
                int usedStrong = chunkDao.countStrongByOwner(actor, chunks.get(0).world());
                if (usedStrong + becomingStrong > cfg.maxStrongChunks()) {
                    return new Outcome(AuditResult.DENIED_QUOTA_STRONG,
                            "把整组设为强加载会超出你的强加载上限：当前 " + usedStrong
                                    + " 个，本组还要新增 " + becomingStrong
                                    + " 个，上限 " + cfg.maxStrongChunks() + "。", false);
                }
                int globalStrong = chunkDao.countGlobalStrong();
                if (globalStrong + becomingStrong > cfg.globalMaxStrongChunks()) {
                    return new Outcome(AuditResult.DENIED_GLOBAL_STRONG,
                            "全服强加载上限不足：当前 " + globalStrong + "，本组需新增 "
                                    + becomingStrong + "，全服上限 " + cfg.globalMaxStrongChunks() + "。",
                            false);
                }
            }

            long now = System.currentTimeMillis();
            int updated = chunkDao.updateStateByGroup(group.id(), target, now);

            // ticket 必须逐个改：它按区块管理，没有批量接口
            int ticketFailed = 0;
            for (ChunkControl c : chunks) {
                World world = worldLookup.apply(c.world());
                if (world == null) {
                    continue;
                }
                try {
                    tickets.clear(world, c.chunkX(), c.chunkZ());
                    if (target == ChunkState.WEAK) {
                        tickets.setWeak(world, c.chunkX(), c.chunkZ());
                    } else if (target == ChunkState.STRONG) {
                        tickets.setStrong(world, c.chunkX(), c.chunkZ());
                    }
                } catch (RuntimeException e) {
                    ticketFailed++;
                    LOG.log(Level.WARNING, "批量改状态时 ticket 操作失败 " + c.key(), e);
                }
            }

            log(actor, null, AuditAction.GROUP_SET_STATE, AuditResult.OK,
                    "group=" + name + ", state=" + target + ", count=" + updated);
            String suffix = ticketFailed > 0
                    ? "（其中 " + ticketFailed + " 个区块的 ticket 操作失败，详见日志）" : "";
            return new Outcome(AuditResult.OK,
                    "已把群组「" + name + "」的 " + updated + " 个区块设为"
                            + target.displayName() + suffix + "。", true);
        } catch (SQLException e) {
            LOG.log(Level.WARNING, "批量改状态失败", e);
            return new Outcome(AuditResult.ERROR, "批量改状态失败：" + e.getMessage(), false);
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  查询
    // ══════════════════════════════════════════════════════════════════════

    public Optional<ChunkGroup> find(UUID owner, String rawName) {
        try {
            return groupDao.find(owner, ChunkGroup.normalizeName(rawName));
        } catch (SQLException e) {
            LOG.log(Level.WARNING, "查询群组失败", e);
            return Optional.empty();
        }
    }

    public List<ChunkGroup> listGroups(UUID owner) {
        try {
            return groupDao.listByOwner(owner);
        } catch (SQLException e) {
            LOG.log(Level.WARNING, "列出群组失败", e);
            return List.of();
        }
    }

    public List<ChunkControl> listChunks(long groupId) {
        try {
            return chunkDao.listByGroup(groupId);
        } catch (SQLException e) {
            LOG.log(Level.WARNING, "列出群组区块失败", e);
            return List.of();
        }
    }

    public int countChunks(long groupId) {
        try {
            return chunkDao.countByGroup(groupId);
        } catch (SQLException e) {
            LOG.log(Level.WARNING, "统计群组区块失败", e);
            return 0;
        }
    }

    // ── 内部 ────────────────────────────────────────────────────────────────

    private void log(UUID actor, UUID target, AuditAction action, AuditResult result, String reason) {
        try {
            auditDao.log(actor, target, null, action, result, reason);
        } catch (SQLException e) {
            LOG.log(Level.WARNING, "写审计日志失败（不影响本次操作）", e);
        }
    }
}
