package com.danny.chunkloader.model;

import java.util.UUID;

/**
 * 一行 {@code chunk_control} 记录。
 *
 * <p>不可变值对象。「未控制」不会出现在这里 —— 删除行即等于未控制。
 *
 * @param key       区块坐标
 * @param owner     持有者 UUID
 * @param state     意图状态（必为 WEAK 或 STRONG）
 * @param createdAt 首次被控制的毫秒时间戳
 * @param updatedAt 最后一次变更的毫秒时间戳
 */
public record ChunkControl(ChunkKey key, UUID owner, ChunkState state, long createdAt, long updatedAt) {

    public ChunkControl {
        if (key == null) {
            throw new IllegalArgumentException("key 不能为空");
        }
        if (owner == null) {
            throw new IllegalArgumentException("owner 不能为空");
        }
        if (state == null || !state.isControlled()) {
            throw new IllegalArgumentException("chunk_control 记录的状态必须是弱加载或强加载，收到: " + state);
        }
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

    /** 状态变更后的新记录（保留 createdAt，刷新 updatedAt）。 */
    public ChunkControl withState(ChunkState newState, long now) {
        return new ChunkControl(key, owner, newState, createdAt, now);
    }
}
