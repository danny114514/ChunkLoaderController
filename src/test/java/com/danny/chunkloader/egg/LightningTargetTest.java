package com.danny.chunkloader.egg;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LightningTarget} 的范围计算测试。
 *
 * <p>只测纯数学部分（{@link LightningTarget#candidateOffsets()}）—— 真实方块查询需要
 * 运行中的服务端，无法在单元测试里做。但范围公式恰恰是最容易写错、且
 * 在游戏里最难复现的部分：算错了会表现为「避雷针就在旁边却吸不到」，
 * 玩家只会觉得「有时候有用有时候没用」。</p>
 */
class LightningTargetTest {

    @Test
    @DisplayName("所有候选点都在半径内（球面方程）")
    void allWithinRadius() {
        int r2 = LightningTarget.RADIUS * LightningTarget.RADIUS;
        for (int[] o : LightningTarget.candidateOffsets()) {
            int d2 = o[0] * o[0] + o[1] * o[1] + o[2] * o[2];
            assertTrue(d2 <= r2,
                    "偏移 [" + o[0] + "," + o[1] + "," + o[2] + "] 的距离平方 " + d2
                            + " 超出半径平方 " + r2);
        }
    }

    @Test
    @DisplayName("是上半球：不包含任何向下（dy < 0）的点")
    void noDownwardOffsets() {
        for (int[] o : LightningTarget.candidateOffsets()) {
            assertTrue(o[1] >= 0,
                    "出现向下的偏移 dy=" + o[1] + "，那不是上半球");
        }
    }

    @Test
    @DisplayName("边界点必须被包含：正上方 5 格、水平 5 格")
    void boundaryIncluded() {
        List<int[]> offsets = LightningTarget.candidateOffsets();
        assertTrue(contains(offsets, 0, 5, 0), "正上方 5 格（恰在半径上）应当被包含");
        assertTrue(contains(offsets, 5, 0, 0), "水平 +X 方向 5 格应当被包含");
        assertTrue(contains(offsets, 0, 0, -5), "水平 -Z 方向 5 格应当被包含");
        assertTrue(contains(offsets, 3, 4, 0), "3²+4²=25 恰好在球面上，应当被包含");
    }

    @Test
    @DisplayName("球面之外必须被排除")
    void outsideExcluded() {
        List<int[]> offsets = LightningTarget.candidateOffsets();
        assertFalse(contains(offsets, 4, 4, 0), "4²+4²=32 > 25，应当排除");
        assertFalse(contains(offsets, 5, 1, 0), "5²+1²=26 > 25，应当排除");
        assertFalse(contains(offsets, 0, 6, 0), "正上方 6 格超出半径，应当排除");
        assertFalse(contains(offsets, 6, 0, 0), "水平 6 格超出半径，应当排除");
    }

    @Test
    @DisplayName("中心点存在且排在第一位（最近的优先）")
    void centerFirst() {
        List<int[]> offsets = LightningTarget.candidateOffsets();
        int[] first = offsets.get(0);
        assertEquals(0, first[0]);
        assertEquals(0, first[1]);
        assertEquals(0, first[2]);
    }

    @Test
    @DisplayName("按距离非递减排序（保证「找到第一个合格的就是最近的」）")
    void sortedByDistance() {
        List<int[]> offsets = LightningTarget.candidateOffsets();
        int previous = -1;
        for (int[] o : offsets) {
            int d2 = o[0] * o[0] + o[1] * o[1] + o[2] * o[2];
            assertTrue(d2 >= previous,
                    "排序被破坏：出现距离平方 " + d2 + " 小于前一项 " + previous);
            previous = d2;
        }
    }

    @Test
    @DisplayName("候选数量为 298（半径 5 上半球的实测值）")
    void countIsExact() {
        // 这个数字是实测出来的，不是估算：
        //   赤道面（dy=0）是半径 5 的圆盘，81 个
        //   往上每层按 dx²+dz² <= 25-dy² 递减，累计 298
        // 最初我按「约 380」写断言，被这个测试纠正了 —— 保留精确值，
        // 这样任何人改 RADIUS 或改球面公式都会立刻被这里逮到。
        assertEquals(298, LightningTarget.candidateOffsets().size(),
                "候选点数量变化说明搜索范围被改动了");
    }

    private static boolean contains(List<int[]> offsets, int dx, int dy, int dz) {
        for (int[] o : offsets) {
            if (o[0] == dx && o[1] == dy && o[2] == dz) {
                return true;
            }
        }
        return false;
    }
}
