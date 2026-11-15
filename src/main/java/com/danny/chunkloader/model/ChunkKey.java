package com.danny.chunkloader.model;

/**
 * 区块坐标三元组（世界名 + chunkX + chunkZ），作为 Map 键使用。
 *
 * <p>世界名用字符串而非 {@code World} 对象：区块控制是<b>持久化</b>的，
 * 而 {@code World} 实例会在重载世界时失效。
 */
public record ChunkKey(String world, int x, int z) {

    public ChunkKey {
        if (world == null || world.isBlank()) {
            throw new IllegalArgumentException("world 不能为空");
        }
    }

    /** 从方块坐标换算到区块坐标（对负数正确向下取整）。 */
    public static ChunkKey fromBlock(String world, int blockX, int blockZ) {
        return new ChunkKey(world, blockX >> 4, blockZ >> 4);
    }

    @Override
    public String toString() {
        return world + ":" + x + "," + z;
    }
}
