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
 * {@link ChunkState} 的四态语义测试。
 *
 * <p>重点在 {@link ChunkState#RESERVED} —— 它是「有主但不加载」，
 * 而「有主」与「加载」在本插件里是<b>两个独立维度</b>。这里把两者的组合全部固定住，
 * 因为这些判断一旦搞错，后果是「别人的地被抢走」或「自己的地被白占」。</p>
 */
class ChunkStateTest {

    @Nested
    @DisplayName("「有主」与「加载」是两个维度")
    class TwoDimensions {

        @Test
        @DisplayName("未控制：无主、不加载")
        void uncontrolled() {
            assertFalse(ChunkState.UNCONTROLLED.isControlled(), "无主");
            assertFalse(ChunkState.UNCONTROLLED.isLoaded(), "不加载");
            assertNull(ChunkState.UNCONTROLLED.ticketLevel());
        }

        @Test
        @DisplayName("保留：有主、但不加载 —— 这正是它存在的理由")
        void reserved() {
            assertTrue(ChunkState.RESERVED.isControlled(),
                    "保留态必须有主，否则别人就能认领");
            assertFalse(ChunkState.RESERVED.isLoaded(),
                    "保留态不加任何 ticket");
            assertNull(ChunkState.RESERVED.ticketLevel());
        }

        @Test
        @DisplayName("弱加载/强加载：有主、且加载")
        void loadedStates() {
            assertTrue(ChunkState.WEAK.isControlled());
            assertTrue(ChunkState.WEAK.isLoaded());
            assertEquals(32, ChunkState.WEAK.ticketLevel().intValue());

            assertTrue(ChunkState.STRONG.isControlled());
            assertTrue(ChunkState.STRONG.isLoaded());
            assertEquals(31, ChunkState.STRONG.ticketLevel().intValue());
        }

        @Test
        @DisplayName("isLoaded() 恰好等价于 ticketLevel != null")
        void isLoadedMatchesTicketLevel() {
            for (ChunkState s : ChunkState.values()) {
                assertEquals(s.ticketLevel() != null, s.isLoaded(),
                        s + " 的 isLoaded() 与 ticketLevel() 不一致");
            }
        }
    }

    @Nested
    @DisplayName("数据库值往返（含新增的保留态）")
    class DbValue {

        @Test
        @DisplayName("每个状态都能从数据库值还原")
        void roundTrip() {
            for (ChunkState state : ChunkState.values()) {
                assertEquals(state, ChunkState.fromDbValue(state.dbValue()));
            }
        }

        @Test
        @DisplayName("保留态的持久化值是 3，不与旧值冲突")
        void reservedDbValue() {
            assertEquals(3, ChunkState.RESERVED.dbValue());
            assertEquals(0, ChunkState.UNCONTROLLED.dbValue());
            assertEquals(1, ChunkState.WEAK.dbValue());
            assertEquals(2, ChunkState.STRONG.dbValue());
        }

        @Test
        @DisplayName("未知值抛异常，而不是静默当成未控制")
        void unknownValueThrows() {
            assertThrows(IllegalArgumentException.class, () -> ChunkState.fromDbValue(99));
        }
    }

    @Nested
    @DisplayName("命令参数解析")
    class Parsing {

        @Test
        @DisplayName("保留态有多个别名")
        void parseReserved() {
            assertEquals(ChunkState.RESERVED, ChunkState.parse("reserve"));
            assertEquals(ChunkState.RESERVED, ChunkState.parse("reserved"));
            assertEquals(ChunkState.RESERVED, ChunkState.parse("hold"));
            assertEquals(ChunkState.RESERVED, ChunkState.parse("保留"));
        }

        @Test
        @DisplayName("原有的状态解析不受影响")
        void parseExisting() {
            assertEquals(ChunkState.WEAK, ChunkState.parse("weak"));
            assertEquals(ChunkState.STRONG, ChunkState.parse("strong"));
            assertEquals(ChunkState.UNCONTROLLED, ChunkState.parse("none"));
            assertEquals(ChunkState.UNCONTROLLED, ChunkState.parse("release"));
        }

        @Test
        @DisplayName("无法识别时返回 null")
        void parseUnknown() {
            assertNull(ChunkState.parse("bogus"));
            assertNull(ChunkState.parse(null));
        }
    }

    @Nested
    @DisplayName("左键升级 / 右键降级（含保留态）")
    class Cycling {

        @Test
        @DisplayName("升级链：未控制 → 弱 → 强 → 停在强")
        void upgradeChain() {
            assertEquals(ChunkState.WEAK, ChunkState.UNCONTROLLED.upgraded());
            assertEquals(ChunkState.STRONG, ChunkState.WEAK.upgraded());
            assertEquals(ChunkState.STRONG, ChunkState.STRONG.upgraded());
        }

        @Test
        @DisplayName("保留态左键 = 开始加载，变成弱加载")
        void upgradeReserved() {
            assertEquals(ChunkState.WEAK, ChunkState.RESERVED.upgraded());
        }

        @Test
        @DisplayName("降级链：强 → 弱 → 保留 → 停在保留")
        void downgradeChain() {
            assertEquals(ChunkState.WEAK, ChunkState.STRONG.downgraded());
            assertEquals(ChunkState.RESERVED, ChunkState.WEAK.downgraded(),
                    "弱加载降级后是「保留」而不是「未控制」");
            assertEquals(ChunkState.RESERVED, ChunkState.RESERVED.downgraded());
        }

        @Test
        @DisplayName("右键降级永远不会把有主区块变成无主")
        void downgradeNeverReleases() {
            for (ChunkState s : ChunkState.values()) {
                if (!s.isControlled()) {
                    continue;
                }
                ChunkState lower = s.downgraded();
                assertTrue(lower.isControlled(),
                        s + " 右键降级后变成了 " + lower + "，那会让区块变无主、被他人抢走");
            }
        }

        @Test
        @DisplayName("未控制右键仍然停在未控制")
        void uncontrolledStaysUncontrolled() {
            assertEquals(ChunkState.UNCONTROLLED, ChunkState.UNCONTROLLED.downgraded());
        }
    }

    @Nested
    @DisplayName("显示名")
    class Display {

        @Test
        @DisplayName("保留态有明确的中文名与补充说明")
        void reservedNames() {
            assertEquals("保留", ChunkState.RESERVED.displayName());
            assertTrue(ChunkState.RESERVED.fullName().contains("不可认领"),
                    "GUI 名称里要能看出「别人拿不到」");
        }

        @Test
        @DisplayName("未控制与保留的显示名必须不同，否则玩家无法区分")
        void uncontrolledDiffersFromReserved() {
            assertFalse(ChunkState.UNCONTROLLED.displayName()
                            .equals(ChunkState.RESERVED.displayName()),
                    "两者含义完全不同：一个是无主，一个是别人抢不走");
        }
    }
}
