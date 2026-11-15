package com.danny.chunkloader.gui;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * GUI 会话簿记：玩家 → (会话数据, 所属界面实例)。
 *
 * <p><b>为什么单独抽出来</b>：这段逻辑bug太多了
 *  —— 开发过程中在这里连续踩了三次坑：
 * <ol>
 *   <li>用一个 {@code new} 出来的 GUI 实例登记会话，事件层查的却是插件单例 → 点击全部失效；</li>
 *   <li>重开界面时旧界面的关闭事件把<b>新</b>会话删掉 → 打开正常、之后每次点击都报会话丢失；</li>
 *   <li>同上，但因为没有界面绑定，连「自动重开」都救不回来，形成每次点击都重开的死循环。</li>
 * </ol>
 *
 * <p>三次的共同点：它们是<b>纯事件顺序逻辑</b>，却因为直接依赖 Bukkit 的
 * {@code Inventory}/{@code Player} 而无法写单元测试，只能靠手动点 GUI 发现。
 *
 * <p>抽出来之后，界面实例只以一个不透明的 token（{@code Object}）参与比较，
 * 于是「打开 → 关闭旧界面 → 再点击」这个致命序列可以在测试里完整复现。
 *
 * @param <S> 会话数据类型（两个视图各自定义）
 */
public final class SessionStore<S> {

    /** 一条登记：会话数据 + 它属于哪个界面实例。 */
    private record Entry<S>(S value, Object inventoryToken) {
    }

    private final Map<UUID, Entry<S>> entries = new ConcurrentHashMap<>();

    /**
     * 登记（或替换）会话。
     *
     * <p>重开界面时应当传入<b>新</b>的界面 token，这样随后到来的
     * 「旧界面关闭」事件就不会误删这条新会话。
     *
     * @param inventoryToken 界面实例标识，仅用于相等比较，不可为 null
     */
    public void put(UUID player, S value, Object inventoryToken) {
        if (player == null || value == null || inventoryToken == null) {
            throw new IllegalArgumentException("put 的三个参数都不能为 null");
        }
        entries.put(player, new Entry<>(value, inventoryToken));
    }

    /** 取会话；不存在返回 {@code null}。 */
    public S get(UUID player) {
        Entry<S> entry = entries.get(player);
        return entry == null ? null : entry.value();
    }

    /** 该会话的界面是否就是给定实例（用于判断某次点击/关闭是否属于当前界面）。 */
    public boolean isCurrentInventory(UUID player, Object inventoryToken) {
        Entry<S> entry = entries.get(player);
        return entry != null && entry.inventoryToken() == inventoryToken;
    }

    /**
     * 界面关闭：仅当被关闭的界面<b>正是</b>当前登记的界面时才移除会话。
     *
     * <p>这是修复「重开后会话丢失」的关键。{@code player.openInventory(新界面)}
     * 会先关闭旧界面并触发关闭事件；若无条件移除，新会话在那一瞬间就被删了。
     *
     * @param inventoryToken 被关闭的界面实例
     * @return true 表示确实移除了会话
     */
    public boolean removeIfCurrent(UUID player, Object inventoryToken) {
        Entry<S> entry = entries.get(player);
        if (entry == null) {
            return false;
        }
        if (inventoryToken != null && entry.inventoryToken() != inventoryToken) {
            // 关掉的是旧界面（重开过程中的中间态）→ 保留新会话
            return false;
        }
        entries.remove(player);
        return true;
    }

    /**
     * 无条件移除会话。
     *
     * <p>用于玩家下线：此时不关心是哪个界面，必须确保不留残余引用。
     */
    public S remove(UUID player) {
        Entry<S> entry = entries.remove(player);
        return entry == null ? null : entry.value();
    }

    /** 当前登记的会话数量（测试与调试用）。 */
    public int size() {
        return entries.size();
    }

    /** 清空（插件卸载时用）。 */
    public void clear() {
        entries.clear();
    }
}
