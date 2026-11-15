package com.danny.chunkloader.gui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SessionStore} 的行为测试。
 *
 * <p>这些测试固定住的是<b>真实发生过的 bug</b>，不是假想的边界情况。
 * 每个方法名都写明它防的是哪一种回归。
 */
class SessionStoreTest {

    private static final UUID PLAYER = UUID.fromString("2a00dd87-8bc5-3e41-9cb9-806b8d229872");

    /** 用一个裸 Object 冒充界面实例 —— 这正是把逻辑与 Bukkit 解耦的收益。 */
    private static final Object INVENTORY_A = new Object();
    private static final Object INVENTORY_B = new Object();

    @Test
    @DisplayName("登记后可以取回同一个会话对象")
    void putThenGet() {
        SessionStore<String> store = new SessionStore<>();
        store.put(PLAYER, "page-1", INVENTORY_A);

        assertEquals("page-1", store.get(PLAYER));
        assertEquals(1, store.size());
    }

    /**
     * 这是「切换一次状态后，第二次点击必报会话丢失」的直接回归测试。
     *
     * <p>真实事件序列：
     * <ol>
     *   <li>玩家点了一下，状态改变，代码决定重绘；</li>
     *   <li>代码用<b>新</b>界面登记会话（此时旧界面还开着）；</li>
     *   <li>{@code openInventory(新界面)} 关闭旧界面 → 触发关闭事件，携带<b>旧</b>界面；</li>
     *   <li>若无条件按玩家清理，新会话在此时被删除 → 之后每次点击都发现会话为空。</li>
     * </ol>
     */
    @Test
    @DisplayName("回归：重开界面时，旧界面的关闭事件不得删除新会话")
    void closeOfOldInventoryMustNotRemoveNewSession() {
        SessionStore<String> store = new SessionStore<>();

        // 1. 初次打开
        store.put(PLAYER, "page-1", INVENTORY_A);

        // 2. 重绘：用新界面重新登记（旧界面此刻仍然开着）
        store.put(PLAYER, "page-1-redrawn", INVENTORY_B);

        // 3. 旧界面被关闭 —— 这个事件携带的是 INVENTORY_A
        boolean removed = store.removeIfCurrent(PLAYER, INVENTORY_A);

        assertFalse(removed, "关闭旧界面不应移除会话");
        assertEquals("page-1-redrawn", store.get(PLAYER),
                "新会话必须存活，否则之后每次点击都会报会话丢失");
        assertEquals(1, store.size());
    }

    @Test
    @DisplayName("真正关闭当前界面时，会话必须被移除（否则内存泄漏）")
    void closeOfCurrentInventoryRemovesSession() {
        SessionStore<String> store = new SessionStore<>();
        store.put(PLAYER, "page-1", INVENTORY_B);

        boolean removed = store.removeIfCurrent(PLAYER, INVENTORY_B);

        assertTrue(removed);
        assertNull(store.get(PLAYER));
        assertEquals(0, store.size());
    }

    @Test
    @DisplayName("关闭一个从未登记过界面的玩家不会抛异常")
    void closeUnknownPlayerIsSafe() {
        SessionStore<String> store = new SessionStore<>();
        assertFalse(store.removeIfCurrent(PLAYER, INVENTORY_A));
        assertTrue(store.size() == 0);
    }

    @Test
    @DisplayName("传 null 界面表示强制清理（玩家下线路径）")
    void nullInventoryForcesRemoval() {
        SessionStore<String> store = new SessionStore<>();
        store.put(PLAYER, "page-1", INVENTORY_A);

        assertTrue(store.removeIfCurrent(PLAYER, null));
        assertNull(store.get(PLAYER));
    }

    @Test
    @DisplayName("isCurrentInventory 能识别点击来自旧界面")
    void staleClickDetection() {
        SessionStore<String> store = new SessionStore<>();
        store.put(PLAYER, "page-1", INVENTORY_B);

        assertTrue(store.isCurrentInventory(PLAYER, INVENTORY_B));
        assertFalse(store.isCurrentInventory(PLAYER, INVENTORY_A),
                "来自旧界面的点击必须被识别，否则会操作到错位的格子");
        assertFalse(store.isCurrentInventory(PLAYER, null));
    }

    @Test
    @DisplayName("同一玩家重复登记只保留最新一条")
    void putReplacesPrevious() {
        SessionStore<String> store = new SessionStore<>();
        store.put(PLAYER, "page-1", INVENTORY_A);
        store.put(PLAYER, "page-2", INVENTORY_B);

        assertEquals("page-2", store.get(PLAYER));
        assertEquals(1, store.size());
        assertTrue(store.isCurrentInventory(PLAYER, INVENTORY_B));
    }

    @Test
    @DisplayName("remove 返回被移除的会话，便于清理外部资源")
    void removeReturnsValue() {
        SessionStore<String> store = new SessionStore<>();
        store.put(PLAYER, "page-1", INVENTORY_A);

        String removed = store.remove(PLAYER);

        assertEquals("page-1", removed);
        assertNull(store.get(PLAYER));
    }

    @Test
    @DisplayName("相同内容的两个界面 token 若为不同实例，也算不同界面")
    void identityNotEquality() {
        // 用 isCurrentInventory 保证比较的是引用而非 equals：
        // 两个内容相同的 String 若按 equals 比较会被误判为同一界面。
        SessionStore<String> store = new SessionStore<>();
        Object inventoryOne = new String("same-title");
        Object inventoryTwo = new String("same-title");
        store.put(PLAYER, "page-1", inventoryOne);

        assertTrue(store.isCurrentInventory(PLAYER, inventoryOne));
        assertFalse(store.isCurrentInventory(PLAYER, inventoryTwo),
                "不同实例必须视为不同界面，哪怕 equals 返回 true");
    }

    @Test
    @DisplayName("参数校验：null 会话或 null 界面直接拒绝，避免静默写入坏数据")
    void nullArgumentsRejected() {
        SessionStore<String> store = new SessionStore<>();
        assertThrows(IllegalArgumentException.class, () -> store.put(null, "x", INVENTORY_A));
        assertThrows(IllegalArgumentException.class, () -> store.put(PLAYER, null, INVENTORY_A));
        assertThrows(IllegalArgumentException.class, () -> store.put(PLAYER, "x", null));
    }

    @Test
    @DisplayName("多个玩家互不干扰")
    void multiplePlayers() {
        UUID other = UUID.randomUUID();
        SessionStore<String> store = new SessionStore<>();
        store.put(PLAYER, "mine", INVENTORY_A);
        store.put(other, "theirs", INVENTORY_B);

        assertEquals("mine", store.get(PLAYER));
        assertEquals("theirs", store.get(other));
        assertEquals(2, store.size());

        store.removeIfCurrent(PLAYER, INVENTORY_A);
        assertEquals(1, store.size());
        assertEquals("theirs", store.get(other));
    }

    @Test
    @DisplayName("clear 清空全部（插件卸载路径）")
    void clearAll() {
        SessionStore<String> store = new SessionStore<>();
        store.put(PLAYER, "a", INVENTORY_A);
        store.put(UUID.randomUUID(), "b", INVENTORY_B);

        store.clear();

        assertEquals(0, store.size());
        assertNull(store.get(PLAYER));
    }

    @Test
    @DisplayName("取回的会话必须是同一个引用（不能复制，否则外部改不到真实数据）")
    void getReturnsSameReference() {
        SessionStore<Object> store = new SessionStore<>();
        Object session = new Object();
        store.put(PLAYER, session, INVENTORY_A);

        assertSame(session, store.get(PLAYER));
    }
}
