package com.danny.chunkloader.storage;

/**
 * 审计日志的结果（{@code audit_log.result}）。
 *
 * <p>拒绝原因必须精确到「哪一层配额」—— 这决定了玩家看到的是「你满了」
 * 还是「全服满了」，也决定管理员该去改哪个配置项。
 */
public enum AuditResult {
    /** 成功。 */
    OK,
    /** 该区块已被他人持有。 */
    DENIED_NOT_OWNER,
    /** 缺少权限节点。 */
    DENIED_NO_PERM,
    /** 需要弱加载，但 NMS 自检未通过。 */
    DENIED_NO_NMS,
    /** 本世界总量已满（{@code max-total-chunks}）。 */
    DENIED_QUOTA_TOTAL,
    /** 本世界强加载已满（{@code max-strong-chunks}）。 */
    DENIED_QUOTA_STRONG,
    /** 全服跨世界总量已满（{@code global-max-total-chunks}）。 */
    DENIED_GLOBAL_TOTAL,
    /** 全服跨世界强加载已满（{@code global-max-strong-chunks}）。 */
    DENIED_GLOBAL_STRONG,
    /** 目标区块所在的世界不存在（多半是玩家在错误的世界名上操作）。 */
    DENIED_NO_WORLD,
    /** 执行阶段抛异常（数据库/NMS）。原因写在 reason。 */
    ERROR
}
