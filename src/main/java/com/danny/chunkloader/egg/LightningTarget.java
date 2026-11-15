package com.danny.chunkloader.egg;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 闪电落点选择：在上半球范围内找避雷针，找到就劈它，否则劈玩家自己。
 *
 * <h2>规则</h2>
 * <ul>
 *   <li><b>范围</b>：以玩家所在方块为中心、半径 5 的<b>上半球</b>
 *       （{@code dx² + dy² + dz² ≤ 25} 且 {@code dy ≥ 0}）。
 *       向下不搜 —— 玩家站在高处时去吸地下的避雷针没有意义。</li>
 *   <li><b>合格条件</b>：方块类型是避雷针，且<b>紧邻上方一格是空气</b>。</li>
 *   <li><b>选哪个</b>：范围内距离最近的那个（距离按方块偏移的平方和算，避免开方）。</li>
 *   <li><b>落点</b>：选中避雷针的上方一格（方块中心）。没有合格避雷针时用玩家坐标。</li>
 * </ul>
 *
 * <p><b>线程要求</b>：{@link #find} 会调用 {@code World#getBlockAt}，
 * 这在 Folia 上<b>必须</b>在 owning region 线程执行，否则抛
 * {@code Cannot read world asynchronously}。
 * 调用方负责先切回 region 线程（见 {@code EasterEggListener#strikeAt}）。</p>
 *
 * <p><b>性能</b>：半径 5 的上半球最多约 380 个方块，逐块 {@code getType}。
 * 触发有 5 秒冷却，因此这个开销可以忽略；但如果把半径调大很多，
 * 就该改成用高度图或按层扫描来减少查询。</p>
 */
public final class LightningTarget {

    /** 搜索半径（方块）。同时是水平与垂直向上 的半径。 */
    public static final int RADIUS = 5;

    private LightningTarget() {
    }

    /**
     * 生成上半球内所有方块偏移，按到中心的距离由近到远排序。
     *
     * <p>抽成独立方法是为了能脱离服务端做单元测试：范围公式写错的话，
     * 表现为「避雷针明明在旁边却吸不到」或「隔着一堵墙也能吸到」，
     * 这两种错误在游戏里都很难复现和定位。</p>
     *
     * @return 偏移数组，每项为 {@code {dx, dy, dz}}；中心 {@code {0,0,0}} 排在最前
     */
    public static List<int[]> candidateOffsets() {
        List<int[]> offsets = new ArrayList<>();
        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                // dy 从 0 开始：上半球，不含向下
                for (int dy = 0; dy <= RADIUS; dy++) {
                    // 球面方程。用平方和避免开方，且整型运算没有浮点误差。
                    if (dx * dx + dy * dy + dz * dz > RADIUS * RADIUS) {
                        continue;
                    }
                    offsets.add(new int[]{dx, dy, dz});
                }
            }
        }
        // 近的优先：这样找到第一个合格避雷针就可以停，天然满足「最近」的要求
        offsets.sort(Comparator.comparingInt(o -> o[0] * o[0] + o[1] * o[1] + o[2] * o[2]));
        return offsets;
    }

    /**
     * 找出闪电应该落下的位置。
     *
     * @param center 触发者位置（取其所在方块为中心）
     * @return 合格避雷针的上方一格；没有则返回 {@code center} 本身
     */
    public static Location find(Location center) {
        World world = center.getWorld();
        if (world == null) {
            return center;
        }
        int cx = center.getBlockX();
        int cy = center.getBlockY();
        int cz = center.getBlockZ();

        // 坐标合法性先挡一下：极端的 Y 值会让我们查世界之外的方块
        int minY = world.getMinHeight();
        int maxY = world.getMaxHeight();

        for (int[] offset : candidateOffsets()) {
            int x = cx + offset[0];
            int y = cy + offset[1];
            int z = cz + offset[2];
            if (y < minY || y >= maxY) {
                continue;   // 超出世界高度，跳过
            }
            Block block = world.getBlockAt(x, y, z);
            if (block.getType() != Material.LIGHTNING_ROD) {
                continue;
            }
            // 「上方没有方块遮挡」= 紧邻的上一格是空气（按需求确认的宽松口径）。
            // 注意：朝下悬挂的避雷针上方通常就是它挂着的方块，会被这条规则排除 ——
            // 这是符合直觉的，那种避雷针本来也接不到露天雷击。
            Block above = block.getRelative(0, 1, 0);
            if (!above.isEmpty()) {
                continue;
            }
            // 落点取避雷针上方一格，并在水平方向居中，视觉上才不偏
            return above.getLocation().add(0.5, 0.0, 0.5);
        }

        // 没有合格避雷针：劈玩家自己
        return center;
    }

    /**
     * 判断给定位置是否等于传入的中心（用于日志区分「劈到避雷针」还是「劈到玩家」）。
     */
    public static boolean isUserPosition(Location target, Location center) {
        return target.getBlockX() == center.getBlockX()
                && target.getBlockZ() == center.getBlockZ()
                && Math.abs(target.getBlockY() - center.getBlockY()) <= 1;
    }
}
