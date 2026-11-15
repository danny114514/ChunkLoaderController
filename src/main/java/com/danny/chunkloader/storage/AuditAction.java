package com.danny.chunkloader.storage;

/**
 * 审计日志的动作类型（{@code audit_log.action}）。
 *
 * <p>存字符串而非序号：日志是给人看的，库被外部工具读的时候 {@code SET_WEAK}
 * 比 {@code 1} 有用得多。代价是磁盘占用，可以忽略。
 */
public enum AuditAction {
    SET_WEAK,
    SET_STRONG,
    RELEASE,
    RELEASE_ALL,
    ADMIN_SET,
    ADMIN_RELEASE,
    RELOAD,
    SELFTEST
}
