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
    ERROR,

    // ── 群组相关 ──
    /** 输入不合法（群组名为空/过长/含非法字符，或批量设置传了「未控制」）。 */
    DENIED_BAD_INPUT,
    /** 该玩家已有同名群组。 */
    DENIED_DUPLICATE_GROUP,
    /** 该玩家没有这个群组。 */
    DENIED_NO_SUCH_GROUP,
    /** 该区块已在另一个群组里 —— 一个区块最多属于一个群组。 */
    DENIED_ALREADY_IN_GROUP
}
