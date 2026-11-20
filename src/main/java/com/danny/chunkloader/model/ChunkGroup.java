package com.danny.chunkloader.model;

import java.util.UUID;

/**
 * 一个区块群组：某一玩家的「一组区块」。
 *
 * <p>设计取舍（都不显而易见，写在这里避免以后反复讨论）：</p>
 * <ul>
 *   <li><b>私有</b>：群组只属于创建它的玩家。没有成员、没有共享 ——
 *       多人协作会牵出邀请/踢出/权限/配额分摊一整套东西，
 *       而它要解决的问题（批量切换加载状态）完全不需要这些。</li>
 *   <li><b>一个区块最多属于一个群组</b>：像文件夹而不是标签。
 *       多个群组包含同一区块时，「批量改状态」的语义会互相覆盖。</li>
 *   <li><b>群组不持有状态</b>：加载状态仍然记在 {@code chunk_control} 上。
 *       群组只提供「把这批区块一次性改成某个状态」的入口。</li>
 * </ul>
 *
 * @param id        群组 ID（自增主键）
 * @param owner     所有者 UUID（群组私有）
 * @param name      群组名，同一所有者下唯一
 * @param createdAt 创建时间戳（毫秒）
 */
public record ChunkGroup(long id, UUID owner, String name, long createdAt) {

    /** 群组名长度上限。太长会让 GUI 与命令输出难以阅读。 */
    public static final int MAX_NAME_LENGTH = 24;

    public ChunkGroup {
        if (owner == null) {
            throw new IllegalArgumentException("群组所有者不能为空");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("群组名不能为空");
        }
        if (name.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("群组名最长 " + MAX_NAME_LENGTH + " 个字符");
        }
    }

    /**
     * 规范化群组名：去掉首尾空白，并把内部空白压成一个下划线。
     *
     * <p>群组名会被用在命令参数与数据库唯一键里，所以不能有歧义 ——
     * {@code "我的 农场"} 与 {@code "我的  农场"} 必须视为同一个名字。</p>
     */
    public static String normalizeName(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.trim().replaceAll("\\s+", "_");
    }

    /**
     * 校验群组名是否合法。
     *
     * @return 不合法时返回原因；合法返回 {@code null}
     */
    public static String validateName(String normalized) {
        if (normalized == null || normalized.isBlank()) {
            return "群组名不能为空";
        }
        if (normalized.length() > MAX_NAME_LENGTH) {
            return "群组名最长 " + MAX_NAME_LENGTH + " 个字符";
        }
        // 只允许中文、字母、数字、下划线、连字符：
        // 名称会出现在命令与 GUI 里，避免引号/空格造成的解析歧义。
        if (!normalized.matches("[\\w\\u4e00-\\u9fa5-]+")) {
            return "群组名只能包含中文、字母、数字、下划线和连字符";
        }
        return null;
    }
}
