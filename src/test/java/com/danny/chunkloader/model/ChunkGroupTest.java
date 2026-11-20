package com.danny.chunkloader.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ChunkGroup} 的名字规范化与校验测试。
 *
 * <p>群组名会同时出现在命令参数与数据库唯一键里，所以「什么算同一个名字」
 * 必须确定：{@code "我的 农场"} 与 {@code "我的  农场"} 不能变成两个群组。</p>
 */
class ChunkGroupTest {

    @Test
    @DisplayName("规范化：去首尾空白，内部空白压成下划线")
    void normalize() {
        assertEquals("农场", ChunkGroup.normalizeName("  农场  "));
        assertEquals("我的_农场", ChunkGroup.normalizeName("我的 农场"));
        assertEquals("我的_农场", ChunkGroup.normalizeName("我的   农场"),
                "多个空格必须压成一个，否则会出现两个看起来一样的群组");
        assertEquals("a_b_c", ChunkGroup.normalizeName("a\tb\nc"),
                "制表符与换行也要规范掉");
    }

    @Test
    @DisplayName("规范化 null 与空串得到空串，便于统一校验")
    void normalizeNull() {
        assertEquals("", ChunkGroup.normalizeName(null));
        assertEquals("", ChunkGroup.normalizeName("   "));
    }

    @Test
    @DisplayName("合法名字通过校验")
    void validNames() {
        assertNull(ChunkGroup.validateName("农场"));
        assertNull(ChunkGroup.validateName("my_farm"));
        assertNull(ChunkGroup.validateName("Farm-01"));
        assertNull(ChunkGroup.validateName("农场2号"));
        assertNull(ChunkGroup.validateName("a"));
    }

    @Test
    @DisplayName("空名字被拒绝")
    void emptyRejected() {
        assertNotNull(ChunkGroup.validateName(""));
        assertNotNull(ChunkGroup.validateName("   "));
        assertNotNull(ChunkGroup.validateName(null));
    }

    @Test
    @DisplayName("超长名字被拒绝")
    void tooLongRejected() {
        String longName = "a".repeat(ChunkGroup.MAX_NAME_LENGTH + 1);
        assertNotNull(ChunkGroup.validateName(longName));

        String exactLimit = "a".repeat(ChunkGroup.MAX_NAME_LENGTH);
        assertNull(ChunkGroup.validateName(exactLimit), "正好等于上限应当允许");
    }

    @Test
    @DisplayName("含非法字符的名字被拒绝")
    void illegalCharsRejected() {
        // 这些字符会破坏命令参数解析或 GUI 显示
        assertNotNull(ChunkGroup.validateName("农场;drop"));
        assertNotNull(ChunkGroup.validateName("a/b"));
        assertNotNull(ChunkGroup.validateName("a\\b"));
        assertNotNull(ChunkGroup.validateName("a\"b"));
        assertNotNull(ChunkGroup.validateName("a b"), "规范化后不该还有空格");
    }

    @Test
    @DisplayName("构造时校验所有者与名字")
    void constructorValidation() {
        java.util.UUID owner = java.util.UUID.randomUUID();
        assertThrows(IllegalArgumentException.class,
                () -> new ChunkGroup(1L, owner, "", 0L));
        assertThrows(IllegalArgumentException.class,
                () -> new ChunkGroup(1L, owner, null, 0L));
        assertThrows(IllegalArgumentException.class,
                () -> new ChunkGroup(1L, null, "农场", 0L));
    }

    @Test
    @DisplayName("规范化后仍超长的情况也要被拦住")
    void normalizeThenValidate() {
        // 空格压成下划线不会缩短超过上限太多，但要确认流程正确：
        // 先 normalize 再 validate 是调用方的约定
        String raw = "  " + "a".repeat(ChunkGroup.MAX_NAME_LENGTH) + "  ";
        String normalized = ChunkGroup.normalizeName(raw);
        assertNull(ChunkGroup.validateName(normalized),
                "首尾空白去掉后正好等于上限，应当通过");
        assertTrue(normalized.length() == ChunkGroup.MAX_NAME_LENGTH);
    }
}
