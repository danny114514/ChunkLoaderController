package com.danny.chunkloader.command;

import com.danny.chunkloader.model.ChunkState;

import java.util.Optional;

/**
 * 实际等级 → 中文描述的映射。
 *
 * <p>这一层刻意与 {@link ChunkState} 分开：{@code ChunkState} 表达「玩家意图」，
 * 只有三种；而实际等级有四种（31/32/33/未加载），且 33 不可手动设置。
 * 二者混用一个枚举会造成「Lore 里出现可选但不可设的状态」这种混乱。
 */
public final class LevelText {

    private LevelText() {
    }

    /** 强加载等级（ENTITY_TICKING）。 */
    public static final int STRONG = 31;
    /** 弱加载等级（BLOCK_TICKING）。 */
    public static final int WEAK = 32;
    /** 边界区块（FULL：仅加载不计算）。 */
    public static final int BORDER = 33;

    /**
     * 原版 ticket level 的中文行为描述。
     *
     * <p>注意 33 在 Bukkit 枚举里叫 {@code BORDER}，很容易被误读成"边界区块"，
     * 但它对应的原版常量其实是 {@code FULL_CHUNK_LEVEL}，含义是"区块数据已完整加载"。
     * 这里按<b>行为</b>描述，不用枚举名。
     */
    public static String describe(int level) {
        if (level <= STRONG) {
            return "完整计算（" + level + "）";
        }
        if (level == WEAK) {
            return "方块计算·实体冻结（" + level + "）";
        }
        if (level == BORDER) {
            return "仅加载不计算（" + level + "）";
        }
        return "未加载（" + level + "）";
    }

    /** 短描述，用于 GUI 的紧凑显示。 */
    public static String describeShort(int level) {
        if (level <= STRONG) {
            return "§a完整计算 §8(" + level + ")";
        }
        if (level == WEAK) {
            return "§e方块计算·实体冻结 §8(" + level + ")";
        }
        if (level == BORDER) {
            return "§7仅加载不计算 §8(" + level + ")";
        }
        return "§8未加载 §8(" + level + ")";
    }

    /** 实际等级 → 对应的「最接近的意图状态」，用于判断是否被遮蔽。 */
    public static ChunkState asState(int level) {
        if (level <= STRONG) {
            return ChunkState.STRONG;
        }
        if (level <= WEAK) {
            return ChunkState.WEAK;
        }
        return ChunkState.UNCONTROLLED;
    }

    /**
     * 判断意图状态是否被「提升」了（遮蔽模型）。
     *
     * @param intended 玩家设置的状态
     * @param actual   实测等级
     * @return 被提升时返回实际等级的描述，否则 empty
     */
    public static Optional<String> shadowNote(ChunkState intended, int actual) {
        if (intended == ChunkState.STRONG) {
            // 强加载是 31，原版没有更强的来源，永不被遮蔽
            return Optional.empty();
        }
        if (intended == ChunkState.WEAK && actual <= STRONG) {
            return Optional.of("被玩家覆盖·实际" + actual);
        }
        return Optional.empty();
    }

    /** 未控制格的实际等级说明（照实显示 + 图例解释）。 */
    public static String uncontrolledNote(int actual) {
        return switch (actual) {
            case STRONG -> "原版玩家视距内，正在完整计算";
            case WEAK -> "原版玩家视距内，方块计算";
            case BORDER -> "原版视距边缘，仅加载不计算";
            default -> "该处无任何 ticket，未加载";
        };
    }
}
