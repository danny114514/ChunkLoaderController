package com.danny.chunkloader.service;

import com.danny.chunkloader.config.PluginConfig;
import com.danny.chunkloader.model.ChunkControl;
import com.danny.chunkloader.model.ChunkState;
import com.danny.chunkloader.storage.AuditResult;
import com.danny.chunkloader.storage.ChunkControlDao;

import java.sql.SQLException;
import java.util.UUID;

/**
 * 配额校验。
 *
 * <p><b>四道闸门，任何一道不过就拒绝</b>：
 * <ol>
 *   <li>本世界总量 {@code max-total-chunks}（默认 45）</li>
 *   <li>本世界强加载 {@code max-strong-chunks}（默认 9）</li>
 *   <li>全服跨世界总量 {@code global-max-total-chunks}（默认 500）</li>
 *   <li>全服跨世界强加载 {@code global-max-strong-chunks}（默认 100）</li>
 * </ol>
 *
 * <p><b>为什么配额按「设置数」而不是「实测已加载数」计</b>：
 * 实测已加载数受玩家视距、遮蔽、卸载延迟影响，会不停波动 —— 用波动值做配额
 * 会导致玩家在视野里走一圈配额就变了。设置数是玩家可理解、可预期的量。
 * 实测值另在 {@code /cl admin status} 报告（只告警，不拒绝）。
 *
 * <p><b>配额收紧不追溯</b>：判断「这次操作会不会超」时，
 * 如果目标区块<b>已经属于该玩家</b>，则先扣除它自己，避免玩家
 * 「已经 45 个了，改一下自己某个区块的状态」被误拒。
 */
public final class QuotaService {

    private final ChunkControlDao dao;

    public QuotaService(ChunkControlDao dao) {
        this.dao = dao;
    }

    /**
     * 配额检查结果。
     *
     * @param allowed 是否放行
     * @param reason  拒绝原因（放行时为 {@link AuditResult#OK}）
     * @param detail  给玩家看的说明
     */
    public record QuotaCheck(boolean allowed, AuditResult reason, String detail) {
        static QuotaCheck ok() {
            return new QuotaCheck(true, AuditResult.OK, "");
        }

        static QuotaCheck deny(AuditResult reason, String detail) {
            return new QuotaCheck(false, reason, detail);
        }
    }

    /**
     * 检查「把某区块设为某状态」是否超出配额。
     *
     * @param owner       操作者
     * @param world       世界名
     * @param targetState 目标状态（WEAK 或 STRONG；UNCONTROLLED 不消耗配额）
     * @param isSameChunk 该区块当前是否已属于同一玩家（是则不计新增）
     * @param currentState 该区块当前状态（可为 null 表示原本未控制）
     */
    public QuotaCheck check(UUID owner, String world, ChunkState targetState,
                            boolean isSameChunk, ChunkState currentState,
                            PluginConfig cfg) throws SQLException {
        // 释放/未控制不消耗配额，也不会因为配额满而被拒
        if (!targetState.isControlled()) {
            return QuotaCheck.ok();
        }

        // ── 计算本次操作的实际增量 ────────────────────────────────────────
        // 已是自己的区块：只在「弱→强」时增加强加载计数，总量不变。
        boolean countedAsNewTotal = !isSameChunk;
        boolean countedAsNewStrong;
        if (!isSameChunk) {
            countedAsNewStrong = targetState == ChunkState.STRONG;
        } else {
            // 自己的区块：原本不是强加载、目标是强加载 → 强加载 +1
            countedAsNewStrong = targetState == ChunkState.STRONG && currentState != ChunkState.STRONG;
        }

        // ── 1. 本世界总量 ─────────────────────────────────────────────────
        if (cfg.quotaPerWorld() && countedAsNewTotal) {
            int used = dao.countByOwner(owner, world);
            if (used >= cfg.maxTotalChunks()) {
                return QuotaCheck.deny(AuditResult.DENIED_QUOTA_TOTAL,
                        "你在本世界的受控区块已达上限 " + cfg.maxTotalChunks()
                                + "（" + used + "/" + cfg.maxTotalChunks()
                                + "）。请先释放一些区块再试。");
            }
        }

        // ── 2. 本世界强加载 ───────────────────────────────────────────────
        if (countedAsNewStrong) {
            int usedStrong = dao.countStrongByOwner(owner, world);
            if (usedStrong >= cfg.maxStrongChunks()) {
                return QuotaCheck.deny(AuditResult.DENIED_QUOTA_STRONG,
                        "你在本世界的强加载区块已达上限 " + cfg.maxStrongChunks()
                                + "（" + usedStrong + "/" + cfg.maxStrongChunks()
                                + "）。强加载会带动周围 12 格进入计算，请先降级或释放。");
            }
        }

        // ── 3. 全服跨世界总量 ─────────────────────────────────────────────
        if (countedAsNewTotal) {
            int globalUsed = dao.countGlobal();
            if (globalUsed >= cfg.globalMaxTotalChunks()) {
                return QuotaCheck.deny(AuditResult.DENIED_GLOBAL_TOTAL,
                        "全服受控区块总量已达上限 " + cfg.globalMaxTotalChunks()
                                + "（" + globalUsed + "/" + cfg.globalMaxTotalChunks()
                                + "）。这是防止整服被拖慢的硬上限，请联系管理员。");
            }
        }

        // ── 4. 全服跨世界强加载 ───────────────────────────────────────────
        if (countedAsNewStrong) {
            int globalStrong = dao.countGlobalStrong();
            if (globalStrong >= cfg.globalMaxStrongChunks()) {
                return QuotaCheck.deny(AuditResult.DENIED_GLOBAL_STRONG,
                        "全服强加载区块总量已达上限 " + cfg.globalMaxStrongChunks()
                                + "（" + globalStrong + "/" + cfg.globalMaxStrongChunks()
                                + "）。请联系管理员调整或等待他人释放。");
            }
        }

        return QuotaCheck.ok();
    }

    /** 用量快照，给 GUI 信息格与 {@code /cl info} 用（只显示当前世界）。 */
    public record Usage(int worldTotal, int worldStrong, int maxTotal, int maxStrong,
                        int globalTotal, int globalStrong, int globalMaxTotal, int globalMaxStrong) {
    }

    public Usage usage(UUID owner, String world, PluginConfig cfg) throws SQLException {
        return new Usage(
                dao.countByOwner(owner, world),
                dao.countStrongByOwner(owner, world),
                cfg.maxTotalChunks(),
                cfg.maxStrongChunks(),
                dao.countGlobal(),
                dao.countGlobalStrong(),
                cfg.globalMaxTotalChunks(),
                cfg.globalMaxStrongChunks());
    }

    /**
     * 该玩家是否已超出配额（配置被调小后可能出现；不追溯，但需要提示）。
     *
     * @return 超额的说明；未超额返回 {@code null}
     */
    public String overQuotaWarning(UUID owner, String world, PluginConfig cfg) throws SQLException {
        int used = dao.countByOwner(owner, world);
        int usedStrong = dao.countStrongByOwner(owner, world);
        if (used > cfg.maxTotalChunks()) {
            return "你在本世界有 " + used + " 个受控区块，已超过上限 " + cfg.maxTotalChunks()
                    + "（配置被调小后不会自动撤销，但无法再新增）";
        }
        if (usedStrong > cfg.maxStrongChunks()) {
            return "你在本世界有 " + usedStrong + " 个强加载区块，已超过上限 " + cfg.maxStrongChunks()
                    + "（配置被调小后不会自动撤销，但无法再新增）";
        }
        return null;
    }

    /** 全服实测已加载数是否超过告警阈值（只警告，不拒绝）。 */
    public boolean overLoadedWarnThreshold(int measuredLoaded, PluginConfig cfg) {
        return cfg.globalLoadedWarnThreshold() > 0
                && measuredLoaded > cfg.globalLoadedWarnThreshold();
    }
}
