package com.danny.chunkloader.model;

import java.util.UUID;

/**
 * 一行 {@code chunk_control} 记录。
 *
 * <p>不可变值对象。「未控制」不会出现在这里 —— 删除行即等于未控制。
 *
 * <p>注意 {@link ChunkState#RESERVED} 是<b>会</b>出现在这里的：它有主、
 * 不加载、占配额、他人不可认领。所以本类里 {@code state} 的合法值是
 * {@code WEAK / STRONG / RESERVED} 三种，判定统一走 {@link ChunkState#isControlled()}。</p>
 *
 * @param key       区块坐标
 * @param owner     持有者 UUID
 * @param state     意图状态（WEAK / STRONG / RESERVED）
 * @param groupId   所属群组 ID；{@code null} 表示不属于任何群组
 * @param createdAt 首次被控制的毫秒时间戳
 * @param updatedAt 最后一次变更的毫秒时间戳
 */
public record ChunkControl(ChunkKey key, UUID owner, ChunkState state,
                          Long groupId, long createdAt, long updatedAt) {

    public ChunkControl {
        if (key == null) {
            throw new IllegalArgumentException("key 不能为空");
        }
        if (owner == null) {
            throw new IllegalArgumentException("owner 不能为空");
        }
        if (state == null || !state.isControlled()) {
            throw new IllegalArgumentException(
                    "chunk_control 记录的状态必须是有主状态（弱加载/强加载/保留），收到: " + state);
        }
    }

    /** 不属于任何群组时的便捷构造。 */
    public ChunkControl(ChunkKey key, UUID owner, ChunkState state, long createdAt, long updatedAt) {
        this(key, owner, state, null, createdAt, updatedAt);
    }

    public String world() {
        return key.world();
    }

    public int chunkX() {
        return key.x();
    }

    public int chunkZ() {
        return key.z();
    }

    public boolean isOwnedBy(UUID player) {
        return owner.equals(player);
    }

    /** 是否属于某个群组。 */
    public boolean hasGroup() {
        return groupId != null;
    }

    /** 状态变更后的新记录（保留 createdAt 与 groupId，刷新 updatedAt）。 */
    public ChunkControl withState(ChunkState newState, long now) {
        return new ChunkControl(key, owner, newState, groupId, createdAt, now);
    }

    /** 加入/移出群组后的新记录（groupId 传 {@code null} 表示移出）。 */
    public ChunkControl withGroup(Long newGroupId, long now) {
        return new ChunkControl(key, owner, state, newGroupId, createdAt, now);
    }
}
