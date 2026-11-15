package com.danny.chunkloader.ticket;

import org.bukkit.World;

/**
 * 区块 ticket 的统一抽象。
 *
 * <p>两条完全不同的实现路径，因此必须抽象：
 * <ul>
 *   <li><b>强加载（31）</b>：公开 API {@code World#addPluginChunkTicket}，任意线程可调，
 *       ticket 归插件所有，且不写世界持久数据。</li>
 *   <li><b>弱加载（32）</b>：公开 API 不支持指定等级，只能走 NMS 反射。</li>
 * </ul>
 */
public interface TicketController {

    /**
     * 加弱加载 ticket（level 32）。
     *
     * @throws NotAvailableException NMS 反射不可用
     */
    void setWeak(World world, int chunkX, int chunkZ);

    /** 加强加载 ticket（level 31，公开 API）。 */
    void setStrong(World world, int chunkX, int chunkZ);

    /** 移除该区块上本插件加的 ticket（弱和强都清）。 */
    void clear(World world, int chunkX, int chunkZ);

    /**
     * 读取该区块当前实际生效的 ticket level。
     *
     * @return 原版 level（31/32/33…），完全未加载时为 {@link java.util.OptionalInt#empty()}
     */
    java.util.OptionalInt actualLevel(World world, int chunkX, int chunkZ);

    /** 弱加载是否可用。false 时 GUI 必须置灰并给出原因。 */
    boolean weakAvailable();

    /** 弱加载不可用的原因，用于展示。 */
    String weakUnavailableReason();

    /** 弱加载不可用。 */
    class NotAvailableException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public NotAvailableException(String message) {
            super(message);
        }
    }
}
