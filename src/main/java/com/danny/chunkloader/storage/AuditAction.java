package com.danny.chunkloader.storage;

/**
 * 审计日志的动作类型（{@code audit_log.action}）。
 *
 * <p>存字符串而非序号：日志是给人看的，库被外部工具读的时候 {@code SET_WEAK}
 * 比 {@code 1} 有用得多。代价是磁盘占用，可以忽略。</p>
 */
public enum AuditAction {
    SET_WEAK,
    SET_STRONG,
    /** 设为「保留」：有主、不加载、他人不可认领。 */
    SET_RESERVED,
    RELEASE,
    RELEASE_ALL,
    ADMIN_SET,
    ADMIN_RELEASE,
    RELOAD,
    SELFTEST,

    // ── 群组操作 ──
    /** 创建群组。 */
    GROUP_CREATE,
    /** 删除群组（组内区块一并释放）。 */
    GROUP_DELETE,
    /** 重命名群组。 */
    GROUP_RENAME,
    /** 把区块加入群组。 */
    GROUP_ADD,
    /** 把区块移出群组（不会释放归属）。 */
    GROUP_REMOVE,
    /** 批量改变群组内所有区块的加载状态。 */
    GROUP_SET_STATE
}
