package com.danny.chunkloader.command;

import com.danny.chunkloader.model.ChunkState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LevelText} 的等级描述与遮蔽判定测试。
 *
 * <p>遮蔽模型很容易写错，而它直接决定玩家在 Lore 里看到什么，
 * 所以值得用测试固定住。
 */
class LevelTextTest {

    @Test
    @DisplayName("31=完整计算 32=方块计算 33=仅加载 其余=未加载")
    void describe() {
        assertTrue(LevelText.describe(31).contains("完整计算"));
        assertTrue(LevelText.describe(30).contains("完整计算"), "比 31 更强也算完整计算");
        assertTrue(LevelText.describe(32).contains("实体冻结"));
        assertTrue(LevelText.describe(33).contains("仅加载"));
        assertTrue(LevelText.describe(34).contains("未加载"));
        assertTrue(LevelText.describe(99).contains("未加载"));
    }

    @Test
    @DisplayName("等级到状态的映射")
    void asState() {
        assertEquals(ChunkState.STRONG, LevelText.asState(31));
        assertEquals(ChunkState.WEAK, LevelText.asState(32));
        assertEquals(ChunkState.UNCONTROLLED, LevelText.asState(33));
        assertEquals(ChunkState.UNCONTROLLED, LevelText.asState(40));
    }

    @Test
    @DisplayName("强加载永不被遮蔽 —— 31 已是最低档")
    void strongNeverShadowed() {
        assertTrue(LevelText.shadowNote(ChunkState.STRONG, 31).isEmpty());
        assertTrue(LevelText.shadowNote(ChunkState.STRONG, 32).isEmpty());
        assertTrue(LevelText.shadowNote(ChunkState.STRONG, 33).isEmpty());
    }

    @Test
    @DisplayName("弱加载被玩家抬到 31 时给出遮蔽说明")
    void weakShadowedByPlayer() {
        var note = LevelText.shadowNote(ChunkState.WEAK, 31);
        assertTrue(note.isPresent());
        assertTrue(note.get().contains("31"));
    }

    @Test
    @DisplayName("弱加载在 32 是正常状态，不算被遮蔽")
    void weakNotShadowedAt32() {
        assertTrue(LevelText.shadowNote(ChunkState.WEAK, 32).isEmpty());
    }

    @Test
    @DisplayName("未控制格不产生遮蔽标记 —— 它没有「意图」可以覆盖")
    void uncontrolledNeverShadowed() {
        assertFalse(LevelText.shadowNote(ChunkState.UNCONTROLLED, 31).isPresent());
        assertFalse(LevelText.shadowNote(ChunkState.UNCONTROLLED, 33).isPresent());
    }

    @Test
    @DisplayName("未控制格的实际等级说明覆盖四种情况")
    void uncontrolledNote() {
        assertTrue(LevelText.uncontrolledNote(31).contains("完整计算"));
        assertTrue(LevelText.uncontrolledNote(32).contains("方块计算"));
        assertTrue(LevelText.uncontrolledNote(33).contains("仅加载"));
        assertTrue(LevelText.uncontrolledNote(40).contains("未加载"));
    }

    @Test
    @DisplayName("短描述带颜色码，用于 GUI 紧凑显示")
    void describeShortHasColour() {
        assertTrue(LevelText.describeShort(31).contains("§a"));
        assertTrue(LevelText.describeShort(32).contains("§e"));
        assertTrue(LevelText.describeShort(33).contains("§7"));
    }

    @Test
    @DisplayName("等级常量固定为 31 / 32 / 33")
    void constants() {
        assertEquals(31, LevelText.STRONG);
        assertEquals(32, LevelText.WEAK);
        assertEquals(33, LevelText.BORDER);
        assertNull(null);
    }
}
