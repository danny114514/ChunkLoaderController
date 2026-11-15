package com.danny.chunkloader.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ChunkState} 的三态语义测试。
 *
 * <p>这些测试不启动服务端，只验证纯逻辑 —— 这正是它们能在 CI 里跑得飞快、
 * 且不依赖平台的原因。
 */
class ChunkStateTest {

    @Nested
    @DisplayName("ticket 等级定义")
    class TicketLevels {

        @Test
        @DisplayName("weak = 32（BLOCK_TICKING），strong = 31（ENTITY_TICKING）")
        void ticketLevels() {
            // 注意：ticketLevel() 返回 Integer（可空，未控制时为 null）。
            // 直接写 assertEquals(32, ...ticketLevel()) 会产生重载歧义：
            // assertEquals(int,int) 需要拆箱、assertEquals(Object,Object) 需要装箱，
            // 两者在同一阶段都适用，javac 报 "对 assertEquals 的引用不明确"。
            // 因此显式拆箱（本测试语境下两个状态一定非 null）。
            assertEquals(32, ChunkState.WEAK.ticketLevel().intValue());
            assertEquals(31, ChunkState.STRONG.ticketLevel().intValue());
        }

        @Test
        @DisplayName("未控制没有 ticket 等级 —— 插件不加任何 ticket")
        void uncontrolledHasNoLevel() {
            assertNull(ChunkState.UNCONTROLLED.ticketLevel());
            assertFalse(ChunkState.UNCONTROLLED.isControlled());
        }

        @Test
        @DisplayName("弱加载和强加载都是受控状态")
        void controlledStates() {
            assertTrue(ChunkState.WEAK.isControlled());
            assertTrue(ChunkState.STRONG.isControlled());
        }
    }

    @Nested
    @DisplayName("数据库值往返")
    class DbValue {

        @Test
        @DisplayName("每个状态都能从数据库值还原")
        void roundTrip() {
            for (ChunkState state : ChunkState.values()) {
                assertEquals(state, ChunkState.fromDbValue(state.dbValue()));
            }
        }

        @Test
        @DisplayName("未知数据库值抛异常，而不是静默当成未控制")
        void unknownValueThrows() {
            assertThrows(IllegalArgumentException.class, () -> ChunkState.fromDbValue(99));
        }
    }

    @Nested
    @DisplayName("命令参数解析")
    class Parsing {

        @Test
        @DisplayName("英文、中文、别名都能解析")
        void parseAliases() {
            assertEquals(ChunkState.WEAK, ChunkState.parse("weak"));
            assertEquals(ChunkState.WEAK, ChunkState.parse("WEAK"));
            assertEquals(ChunkState.WEAK, ChunkState.parse("弱加载"));
            assertEquals(ChunkState.STRONG, ChunkState.parse("strong"));
            assertEquals(ChunkState.STRONG, ChunkState.parse("强"));
            assertEquals(ChunkState.UNCONTROLLED, ChunkState.parse("none"));
            assertEquals(ChunkState.UNCONTROLLED, ChunkState.parse("未控制"));
        }

        @Test
        @DisplayName("无法识别时返回 null，由调用方报错")
        void parseUnknownReturnsNull() {
            assertNull(ChunkState.parse("bogus"));
            assertNull(ChunkState.parse(null));
            assertNull(ChunkState.parse(""));
        }
    }

    @Nested
    @DisplayName("左键升级 / 右键降级")
    class Cycling {

        @Test
        @DisplayName("升级链：未控制 → 弱 → 强 → 停在强")
        void upgradeChain() {
            assertEquals(ChunkState.WEAK, ChunkState.UNCONTROLLED.upgraded());
            assertEquals(ChunkState.STRONG, ChunkState.WEAK.upgraded());
            assertEquals(ChunkState.STRONG, ChunkState.STRONG.upgraded());
        }

        @Test
        @DisplayName("降级链：强 → 弱 → 未控制 → 停在未控制")
        void downgradeChain() {
            assertEquals(ChunkState.WEAK, ChunkState.STRONG.downgraded());
            assertEquals(ChunkState.UNCONTROLLED, ChunkState.WEAK.downgraded());
            assertEquals(ChunkState.UNCONTROLLED, ChunkState.UNCONTROLLED.downgraded());
        }

        @Test
        @DisplayName("升到顶再降一级应回到弱加载，不能直接跳回未控制")
        void roundTripDoesNotSkip() {
            ChunkState top = ChunkState.UNCONTROLLED.upgraded().upgraded();
            assertEquals(ChunkState.STRONG, top);
            assertEquals(ChunkState.WEAK, top.downgraded());
        }
    }
}
