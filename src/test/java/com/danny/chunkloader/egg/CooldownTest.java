package com.danny.chunkloader.egg;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Cooldown} 的行为测试。
 *
 * <p>这段逻辑跑在异步线程上，写错的后果是「刷屏能连劈闪电」——
 * 既可能劈死玩家，也可能因为瞬间生成大量实体拖慢服务端。
 * 所以边界与并发都要测。</p>
 */
class CooldownTest {

    private static final UUID PLAYER = UUID.fromString("2a00dd87-8bc5-3e41-9cb9-806b8d229872");

    @Test
    @DisplayName("首次触发总是放行")
    void firstTimeAllowed() {
        Cooldown cooldown = new Cooldown();
        assertTrue(cooldown.tryAcquire(PLAYER, 1000L, 5000L));
    }

    @Test
    @DisplayName("冷却期内第二次被拒绝")
    void secondWithinCooldownRejected() {
        Cooldown cooldown = new Cooldown();
        assertTrue(cooldown.tryAcquire(PLAYER, 1000L, 5000L));
        assertFalse(cooldown.tryAcquire(PLAYER, 2000L, 5000L), "1 秒后仍在 5 秒冷却内");
        assertFalse(cooldown.tryAcquire(PLAYER, 5999L, 5000L), "差 1 毫秒仍算冷却中");
    }

    @Test
    @DisplayName("冷却边界：正好等于冷却时长时放行")
    void exactBoundaryAllowed() {
        Cooldown cooldown = new Cooldown();
        assertTrue(cooldown.tryAcquire(PLAYER, 1000L, 5000L));
        assertTrue(cooldown.tryAcquire(PLAYER, 6000L, 5000L),
                "间隔正好 5000ms 应当放行（>= 而非 >）");
    }

    @Test
    @DisplayName("冷却为 0 或负数时完全不限制")
    void zeroCooldownNeverBlocks() {
        Cooldown cooldown = new Cooldown();
        assertTrue(cooldown.tryAcquire(PLAYER, 1000L, 0L));
        assertTrue(cooldown.tryAcquire(PLAYER, 1000L, 0L), "同一毫秒也放行");
        assertTrue(cooldown.tryAcquire(PLAYER, 1000L, -5L), "负数同样视为不限制");
    }

    @Test
    @DisplayName("被拒绝时不延长冷却（不能因为反复尝试而一直锁死）")
    void rejectedAttemptDoesNotResetTimer() {
        Cooldown cooldown = new Cooldown();
        assertTrue(cooldown.tryAcquire(PLAYER, 1000L, 5000L));
        assertFalse(cooldown.tryAcquire(PLAYER, 4000L, 5000L));  // 被拒，时间戳应保持 1000
        assertTrue(cooldown.tryAcquire(PLAYER, 6000L, 5000L),
                "从第一次成功起算 5000ms 后应当放行，而不是从被拒那次起算");
    }

    @Test
    @DisplayName("不同玩家互不影响")
    void playersIndependent() {
        UUID other = UUID.randomUUID();
        Cooldown cooldown = new Cooldown();
        assertTrue(cooldown.tryAcquire(PLAYER, 1000L, 5000L));
        assertTrue(cooldown.tryAcquire(other, 1000L, 5000L), "其他玩家不该被牵连");
        assertFalse(cooldown.tryAcquire(PLAYER, 2000L, 5000L));
    }

    @Test
    @DisplayName("remainingMillis 正确反映剩余时间")
    void remaining() {
        Cooldown cooldown = new Cooldown();
        assertEquals(0L, cooldown.remainingMillis(PLAYER, 1000L, 5000L), "没记录时应为 0");

        cooldown.tryAcquire(PLAYER, 1000L, 5000L);
        assertEquals(4000L, cooldown.remainingMillis(PLAYER, 2000L, 5000L));
        assertEquals(0L, cooldown.remainingMillis(PLAYER, 6000L, 5000L), "冷却结束后为 0");
        assertEquals(0L, cooldown.remainingMillis(PLAYER, 9000L, 0L), "无冷却时恒为 0");
    }

    @Test
    @DisplayName("forget 清掉记录后可以立刻再触发")
    void forgetAllowsImmediately() {
        Cooldown cooldown = new Cooldown();
        assertTrue(cooldown.tryAcquire(PLAYER, 1000L, 5000L));
        assertFalse(cooldown.tryAcquire(PLAYER, 2000L, 5000L));

        cooldown.forget(PLAYER);

        assertTrue(cooldown.tryAcquire(PLAYER, 2000L, 5000L),
                "下线清理后再上线应当可以立刻触发");
        assertEquals(1, cooldown.size());
    }

    @Test
    @DisplayName("clear 清空全部记录")
    void clearAll() {
        Cooldown cooldown = new Cooldown();
        cooldown.tryAcquire(PLAYER, 1000L, 5000L);
        cooldown.tryAcquire(UUID.randomUUID(), 1000L, 5000L);
        assertEquals(2, cooldown.size());

        cooldown.clear();

        assertEquals(0, cooldown.size());
    }

    /**
     * 并发测试：这是这段逻辑存在的根本理由。
     *
     * <p>玩家连发两条消息时，两个聊天事件可能被<b>不同线程</b>同时处理。
     * 如果实现是「先读时间戳 → 判断 → 再写回」，两条都会读到「没有记录」
     * 从而双双放行 —— 结果是连劈两道闪电。</p>
     */
    @Test
    @DisplayName("并发：20 个线程同时抢同一个冷却，必须只有 1 个通过")
    void concurrentOnlyOneWins() throws InterruptedException {
        for (int round = 0; round < 20; round++) {
            Cooldown cooldown = new Cooldown();
            int threads = 20;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threads);
            AtomicInteger allowed = new AtomicInteger();

            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        if (cooldown.tryAcquire(PLAYER, 1000L, 5000L)) {
                            allowed.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }

            start.countDown();
            assertTrue(done.await(10, TimeUnit.SECONDS), "并发任务未在超时内完成");
            pool.shutdownNow();

            assertEquals(1, allowed.get(),
                    "同一时刻只应放行一次，实际放行 " + allowed.get() + " 次（第 " + round + " 轮）");
        }
    }
}
