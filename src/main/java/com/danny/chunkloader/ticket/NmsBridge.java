package com.danny.chunkloader.ticket;

import org.bukkit.World;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 唯一的反射入口：把 NMS 的 ticket 操作隔离在这一个类里。
 *
 * <p><b>为什么全用反射</b>：本插件只编译期依赖 {@code folia-api}。NMS 类名/方法名
 * 会随版本与混淆映射变化，一旦硬链接，换个构建就是 {@code NoClassDefFoundError} ——
 * 整个插件连 {@code onEnable} 都进不去。改用反射后，最坏情况是
 * 「弱加载不可用」并可在 GUI/命令里明确告知。
 *
 * <p><b>已在 Folia 1.20.1 build 17 字节码上验证的调用链</b>：
 * <pre>
 * CraftWorld#getHandle()                    → WorldServer
 * WorldServer.k()                           → ChunkProviderServer
 * ChunkProviderServer.c                     → ChunkMapDistance   (private，按类型定位)
 * ChunkMapDistance#getChunkHolderManager()  → ChunkHolderManager (public)
 * ChunkHolderManager#addTicketAtLevel(TicketType, ChunkCoordIntPair, int, T)     (public)
 * ChunkHolderManager#removeTicketAtLevel(TicketType, ChunkCoordIntPair, int, T)  (public)
 * ChunkHolderManager#getChunkHolder(long)   → NewChunkHolder#getTicketLevel()    (public)
 * </pre>
 *
 * <p><b>为什么不用字段名/方法名硬编码</b>：spigot 映射把 {@code WorldServer.chunkSource}
 * 压成 {@code k()}、把 {@code ServerChunkCache.distanceManager} 压成 {@code c}。
 * 这些单字母名没有任何语义保障，换构建就可能变。因此这里一律<b>按类型</b>定位成员
 * （找返回 {@code ChunkProviderServer} 的无参方法、找类型为 {@code ChunkMapDistance} 的字段）。
 *
 * <p><b>线程安全</b>：ticket 增删走 {@code ConcurrentHashMap} + 区域锁，
 * {@code getChunkHolder} 走 SWMR 哈希表，均多线程安全，<b>不需要切 region 线程</b>
 * （Folia commit 99429cee：「ticket additions are mt-safe」）。
 */
public final class NmsBridge {

    private static final Logger LOG = Logger.getLogger("ChunkLoaderController");

    // ── 反射得到的类 ────────────────────────────────────────────────────────
    private Class<?> chunkProviderServerClass;
    private Class<?> chunkMapDistanceClass;
    private Class<?> chunkHolderManagerClass;
    private Class<?> chunkCoordIntPairClass;
    private Class<?> ticketTypeClass;
    private Class<?> newChunkHolderClass;

    // ── 反射得到的成员 ──────────────────────────────────────────────────────
    private Method getChunkSourceMethod;         // WorldServer → ChunkProviderServer
    private Field chunkMapDistanceField;         // ChunkProviderServer.c
    private Method getChunkHolderManagerMethod;  // ChunkMapDistance#getChunkHolderManager()
    private Method addTicketMethod;              // addTicketAtLevel(TicketType, ChunkCoordIntPair, int, T)
    private Method removeTicketMethod;           // removeTicketAtLevel(...)
    private Method getChunkHolderMethod;         // getChunkHolder(long)
    private Method getTicketLevelMethod;         // NewChunkHolder#getTicketLevel()
    private Constructor<?> chunkCoordIntPairCtor;
    private Object pluginTicketType;             // TicketType.PLUGIN_TICKET
    private Method getHandleMethod;              // CraftWorld#getHandle()，按需缓存

    /** 解析是否成功。false = 弱加载不可用，但强加载（公开 API）仍然可用。 */
    private volatile boolean available;
    /** 解析失败的原因，用于 GUI / 日志 / {@code admin status} 告知。 */
    private volatile String failureReason = "尚未初始化";

    /**
     * 尝试解析全部反射符号。可重复调用（幂等）；失败后再次调用会重试。
     *
     * @param probe 任意一个已加载的世界，用于反查 {@code CraftWorld} 的真实类名
     *              （类名带版本后缀 {@code v1_20_R1}，不能硬编码）
     * @return 是否可用
     */
    public synchronized boolean resolve(World probe) {
        if (available) {
            return true;
        }
        if (probe == null) {
            failureReason = "没有已加载的世界，无法定位服务端实现类";
            return false;
        }
        try {
            // 1. 基础类。全部用 Class.forName，允许整体失败而不影响插件加载。
            chunkProviderServerClass = Class.forName("net.minecraft.server.level.ChunkProviderServer");
            chunkMapDistanceClass = Class.forName("net.minecraft.server.level.ChunkMapDistance");
            chunkHolderManagerClass = Class.forName(
                    "io.papermc.paper.chunk.system.scheduling.ChunkHolderManager");
            chunkCoordIntPairClass = Class.forName("net.minecraft.world.level.ChunkCoordIntPair");
            ticketTypeClass = Class.forName("net.minecraft.server.level.TicketType");
            newChunkHolderClass = Class.forName(
                    "io.papermc.paper.chunk.system.scheduling.NewChunkHolder");

            // 2. World → WorldServer。
            //    ⚠ 坑：CraftWorld 里有【两个】无参 getHandle() ——
            //       public WorldServer getHandle()
            //       public GeneratorAccessSeed getHandle()   ← 协变返回的桥接方法
            //    getDeclaredMethods() 的顺序不保证，盲选会随机拿到桥接方法，
            //    表现为"有时能用有时不能用"（比稳定失败更难查）。
            //    因此这里不按名字选，而是逐个候选、【用后续能否解析出 ChunkProviderServer】来筛。
            getChunkSourceMethod = null;
            for (Method candidate : findAllNoArgMethods(probe.getClass(), "getHandle")) {
                Method source = findMethodByReturnType(candidate.getReturnType(), chunkProviderServerClass);
                if (source != null) {
                    candidate.setAccessible(true);
                    getHandleMethod = candidate;
                    getChunkSourceMethod = source;
                    break;
                }
            }
            if (getHandleMethod == null || getChunkSourceMethod == null) {
                throw new NoSuchMethodException(
                        "在 " + probe.getClass().getName() + " 上找不到能解析到 "
                                + chunkProviderServerClass.getName() + " 的 getHandle() 重载。"
                                + "候选返回类型: " + describeGetHandleCandidates(probe.getClass()));
            }
            getChunkSourceMethod.setAccessible(true);

            // 4. ChunkProviderServer → ChunkMapDistance：按字段类型找，不依赖 c 这个混淆名。
            chunkMapDistanceField = findFieldByType(chunkProviderServerClass, chunkMapDistanceClass);
            chunkMapDistanceField.setAccessible(true);

            // 5. ChunkMapDistance → ChunkHolderManager（public，名字有语义，直接用）
            getChunkHolderManagerMethod = chunkMapDistanceClass.getMethod("getChunkHolderManager");

            // 6. ChunkHolderManager 的方法
            addTicketMethod = chunkHolderManagerClass.getMethod("addTicketAtLevel",
                    ticketTypeClass, chunkCoordIntPairClass, int.class, Object.class);
            removeTicketMethod = chunkHolderManagerClass.getMethod("removeTicketAtLevel",
                    ticketTypeClass, chunkCoordIntPairClass, int.class, Object.class);
            getChunkHolderMethod = chunkHolderManagerClass.getMethod("getChunkHolder", long.class);
            getTicketLevelMethod = newChunkHolderClass.getMethod("getTicketLevel");
            chunkCoordIntPairCtor = chunkCoordIntPairClass.getConstructor(int.class, int.class);

            // 7. TicketType.PLUGIN_TICKET
            //    与公开 API addPluginChunkTicket 用的是同一个 type，靠 level（31 vs 32）区分，
            //    因此强弱 ticket 可共存，且 removeAllTicketsFor 能一次性清掉本插件的全部 ticket。
            pluginTicketType = ticketTypeClass.getField("PLUGIN_TICKET").get(null);

            available = true;
            failureReason = "";
            LOG.info("NMS 反射解析成功：弱加载（level 32）可用");
            return true;
        } catch (Throwable t) {
            available = false;
            failureReason = t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : ": " + t.getMessage());
            // 不是致命错误：强加载走公开 API 仍可用。
            LOG.warning("NMS 反射解析失败，弱加载将不可用（强加载不受影响）。原因: " + failureReason);
            LOG.log(Level.FINE, "NMS 解析失败详情", t);
            return false;
        }
    }

    public boolean isAvailable() {
        return available;
    }

    public String failureReason() {
        return failureReason;
    }

    /**
     * 加一个弱加载 ticket（level 32）。
     *
     * @return 是否新增成功；已存在同 type+level+key 的 ticket 时返回 false（不算错误）
     */
    public boolean addWeakTicket(World world, int chunkX, int chunkZ, int level, Object key) {
        requireAvailable();
        try {
            Object manager = chunkHolderManager(world);
            Object pos = chunkCoordIntPairCtor.newInstance(chunkX, chunkZ);
            Object result = addTicketMethod.invoke(manager, pluginTicketType, pos, level, key);
            return Boolean.TRUE.equals(result);
        } catch (TicketException e) {
            throw e;
        } catch (Throwable t) {
            throw new TicketException("加弱加载 ticket 失败 (" + chunkX + "," + chunkZ + ")", t);
        }
    }

    /** 移除弱加载 ticket。 */
    public boolean removeWeakTicket(World world, int chunkX, int chunkZ, int level, Object key) {
        if (!available) {
            return false;
        }
        try {
            Object manager = chunkHolderManager(world);
            Object pos = chunkCoordIntPairCtor.newInstance(chunkX, chunkZ);
            Object result = removeTicketMethod.invoke(manager, pluginTicketType, pos, level, key);
            return Boolean.TRUE.equals(result);
        } catch (TicketException e) {
            throw e;
        } catch (Throwable t) {
            throw new TicketException("移除弱加载 ticket 失败 (" + chunkX + "," + chunkZ + ")", t);
        }
    }

    /**
     * 读一个区块当前生效的 ticket level。
     *
     * <p><b>无锁、不触发区块加载</b>，因此可以在任意线程对任意远距离区块调用。
     * 这是 GUI 一次性读取 45 格等级的可行性基础。
     *
     * @return level；该区块当前没有任何 holder（完全未加载）时返回 {@link Optional#empty()}
     */
    public Optional<Integer> readTicketLevel(World world, int chunkX, int chunkZ) {
        if (!available) {
            return Optional.empty();
        }
        try {
            Object manager = chunkHolderManager(world);
            Object holder = getChunkHolderMethod.invoke(manager, chunkKey(chunkX, chunkZ));
            if (holder == null) {
                return Optional.empty();
            }
            return Optional.of((Integer) getTicketLevelMethod.invoke(holder));
        } catch (TicketException e) {
            throw e;
        } catch (Throwable t) {
            throw new TicketException("读取 ticket level 失败 (" + chunkX + "," + chunkZ + ")", t);
        }
    }

    // ── 内部工具 ────────────────────────────────────────────────────────────

    private void requireAvailable() {
        if (!available) {
            throw new TicketException("NMS 不可用：" + failureReason, null);
        }
    }

    /**
     * 在运行时按实例的类选 {@code getHandle}：类名带版本后缀，无法编译期确定。
     *
     * <p>因此这里做二次校验 —— 返回类型必须真的能解析出 {@code ChunkProviderServer}。
     * 光看名字会选中协变返回的桥接方法（见 {@link #resolve(World)} 里的说明）。
     */
    private Method chunkHolderManagerGetHandle(World world) throws ReflectiveOperationException {
        Method handle = getHandleMethod;
        if (handle != null && handle.getDeclaringClass().isInstance(world)) {
            return handle;
        }
        // 换了实现类（多世界类型不同）：重新按「能解析出 ChunkProviderServer」筛一遍
        for (Method candidate : findAllNoArgMethods(world.getClass(), "getHandle")) {
            if (findMethodByReturnType(candidate.getReturnType(), chunkProviderServerClass) != null) {
                candidate.setAccessible(true);
                getHandleMethod = candidate;
                return candidate;
            }
        }
        throw new NoSuchMethodException("在 " + world.getClass().getName() + " 上找不到可用的 getHandle()");
    }

    /** World → ChunkHolderManager。 */
    private Object chunkHolderManager(World world) throws ReflectiveOperationException {
        Object worldServer = chunkHolderManagerGetHandle(world).invoke(world);
        Object chunkProvider = getChunkSourceMethod.invoke(worldServer);
        Object distance = chunkMapDistanceField.get(chunkProvider);
        return getChunkHolderManagerMethod.invoke(distance);
    }

    /**
     * 复刻 {@code CoordinateUtils.getChunkKey(int, int)}：低 32 位 X，高 32 位 Z。
     */
    static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkZ << 32) | ((long) chunkX & 0xFFFFFFFFL);
    }

    /** 找名字为 name 的<b>所有</b>无参方法（含父类），用于需要逐个试候选的场景。 */
    private static java.util.List<Method> findAllNoArgMethods(Class<?> owner, String name) {
        java.util.List<Method> found = new java.util.ArrayList<>();
        for (Class<?> c = owner; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == 0) {
                    found.add(m);
                }
            }
        }
        return found;
    }

    /** 诊断用：列出所有 getHandle() 的返回类型。 */
    private static String describeGetHandleCandidates(Class<?> owner) {
        java.util.StringJoiner sj = new java.util.StringJoiner("; ");
        for (Method m : findAllNoArgMethods(owner, "getHandle")) {
            sj.add(m.getReturnType().getName() + "（声明于 " + m.getDeclaringClass().getSimpleName() + "）");
        }
        String s = sj.toString();
        return s.isEmpty() ? "没有任何 getHandle()" : s;
    }

    /** 找第一个无参且返回类型精确等于 target 的方法（不依赖方法名）。 */
    private static Method findMethodByReturnType(Class<?> owner, Class<?> target) {
        for (Class<?> c = owner; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getParameterCount() == 0 && m.getReturnType().equals(target)) {
                    return m;
                }
            }
        }
        return null;
    }

    /** 找第一个类型能接收 target 的字段（不依赖字段名）。 */
    private static Field findFieldByType(Class<?> owner, Class<?> target) throws NoSuchFieldException {
        for (Class<?> c = owner; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (target.isAssignableFrom(f.getType())) {
                    return f;
                }
            }
        }
        throw new NoSuchFieldException("在 " + owner.getName() + " 中找不到类型为 " + target.getName() + " 的字段");
    }

    /** ticket 操作失败。业务层捕获后转成 {@code DENIED_NO_NMS} 或 {@code ERROR}。 */
    public static final class TicketException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public TicketException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
