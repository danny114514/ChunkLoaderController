package com.danny.chunkloader.ticket;

import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.util.OptionalInt;
import java.util.logging.Logger;

/**
 * {@link TicketController} 的 Folia 1.20.1 实现。
 *
 * <p><b>强加载走公开 API</b>：{@code World#addPluginChunkTicket} 内部是
 * {@code addRegionTicketAtDistance(PLUGIN_TICKET, pos, 2, plugin)}，而
 * {@code addRegionTicketAtDistance} 展开为 {@code ChunkLevel.fullStatus - distance}
 * = {@code 33 - 2} = <b>31</b>。这正是 {@code ENTITY_TICKING}，也就是本插件定义的「强加载」。
 *
 * <p><b>弱加载必须走 NMS</b>：公开 API 无法指定 32 这个等级，所以只能反射
 * {@code ChunkHolderManager#addTicketAtLevel}。共用 {@code PLUGIN_TICKET} 类型，
 * 以 level 区分（31 强 / 32 弱），并以本插件实例为 key —— 这样服务端卸载插件时
 * 能按 plugin 一次性清理，不会留下孤儿 ticket。
 *
 * <p><b>为什么不调 {@code World#setChunkForceLoaded}</b>：它在 Folia 上要求全局 region
 * 线程，并且会写入世界的 {@code forcedchunks} 持久数据，反而让区块脱离本插件管理
 * 。
 */
public final class FoliaTicketController implements TicketController {

    private static final Logger LOG = Logger.getLogger("ChunkLoaderController");

    /** 弱加载等级：{@code ChunkLevel.BLOCK_TICKING_LEVEL}（已对字节码核实 = 32）。 */
    static final int WEAK_LEVEL = 32;

    private final Plugin plugin;
    private final NmsBridge nms;

    public FoliaTicketController(Plugin plugin, NmsBridge nms) {
        this.plugin = plugin;
        this.nms = nms;
    }

    @Override
    public void setWeak(World world, int chunkX, int chunkZ) {
        if (!nms.isAvailable()) {
            throw new NotAvailableException(nms.failureReason());
        }
        nms.addWeakTicket(world, chunkX, chunkZ, WEAK_LEVEL, plugin);
    }

    @Override
    public void setStrong(World world, int chunkX, int chunkZ) {
        // 公开 API：level 31，任意线程可调，不写持久数据。
        world.addPluginChunkTicket(chunkX, chunkZ, plugin);
    }

    @Override
    public void clear(World world, int chunkX, int chunkZ) {
        // 先清强（公开 API），再尝试清弱（NMS）。两条都做，因为一格可能历史上被设为过两种状态。
        try {
            world.removePluginChunkTicket(chunkX, chunkZ, plugin);
        } catch (RuntimeException e) {
            LOG.warning("移除强加载 ticket 失败 (" + world.getName() + " " + chunkX + "," + chunkZ
                    + "): " + e.getMessage());
        }
        if (nms.isAvailable()) {
            try {
                nms.removeWeakTicket(world, chunkX, chunkZ, WEAK_LEVEL, plugin);
            } catch (RuntimeException e) {
                LOG.warning("移除弱加载 ticket 失败 (" + world.getName() + " " + chunkX + "," + chunkZ
                        + "): " + e.getMessage());
            }
        }
    }

    @Override
    public OptionalInt actualLevel(World world, int chunkX, int chunkZ) {
        var level = nms.readTicketLevel(world, chunkX, chunkZ);
        return level.isPresent() ? OptionalInt.of(level.get()) : OptionalInt.empty();
    }

    @Override
    public boolean weakAvailable() {
        return nms.isAvailable();
    }

    @Override
    public String weakUnavailableReason() {
        return nms.failureReason();
    }
}
