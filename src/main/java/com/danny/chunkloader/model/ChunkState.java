package com.danny.chunkloader.model;

/**
 * 本插件管理的区块状态。
 *
 * <p>与 Bukkit 的 {@link org.bukkit.Chunk.LoadLevel} 不同，这里表达的是「玩家意图」，
 * 而不是「当前实际等级」。实际等级要在运行时读 ticket level 才知道，
 * 二者的差异（例如玩家视距把弱加载抬成强加载）由
 * {@code com.danny.chunkloader.command.LevelText#shadowNote} 负责解释。</p>
 *
 * <h2>「有主」与「加载」是两个独立维度</h2>
 * <p>这是理解本枚举的关键。{@link #RESERVED} 让这两件事解耦了：</p>
 * <ul>
 *   <li>{@link #isControlled()} —— 这个区块在数据库里有一行、有主人。
 *       别人不能认领，且占主人的配额。</li>
 *   <li>{@link #isLoaded()} —— 插件是否为它加 ticket。只有弱/强加载才加。</li>
 * </ul>
 * <p>所以「保留」= 有主 + 不加载。它在界面上必须与「未控制」区分开：
 * 后者是无主，任何人可以认领。</p>
 */
public enum ChunkState {

    /**
     * 未控制：无主。插件不加 ticket，也不在数据库里占一行。
     * <p>其他人可以自由认领。</p>
     */
    UNCONTROLLED(0, null),

    /**
     * 弱加载：ticket level = 32（{@code BLOCK_TICKING}）。
     * <p>方块实体、计划刻、随机刻继续；<b>实体刻完全冻结</b>。</p>
     */
    WEAK(1, 32),

    /**
     * 强加载：ticket level = 31（{@code ENTITY_TICKING}）。
     * <p>走公开 API {@code World#addPluginChunkTicket}，任意线程可调。</p>
     */
    STRONG(2, 31),

    /**
     * 保留：<b>有主，但不加载</b>。
     *
     * <p>存在的理由：群组里的区块被设为「不加载」之后，仍然属于群组所有者 ——
     * 别人不能来认领。如果这时把它当作「未控制」删掉记录，归属就丢了，
     * 等于别人可以随手抢走你圈的地。</p>
     *
     * <p>所以它必须是一个独立状态：占配额、阻止他人认领、但不产生任何 ticket。
     * 想真正放弃这些区块，只能删群组或显式释放。</p>
     */
    RESERVED(3, null);

    /** 数据库中的持久化值，0 保留给 UNCONTROLLED（正常不会写库）。 */
    private final int dbValue;

    /** 对应的原版 ticket level；不加 ticket 的状态为 null。 */
    private final Integer ticketLevel;

    ChunkState(int dbValue, Integer ticketLevel) {
        this.dbValue = dbValue;
        this.ticketLevel = ticketLevel;
    }

    public int dbValue() {
        return dbValue;
    }

    /**
     * @return 对应的原版 ticket level；未控制与保留返回 {@code null}
     */
    public Integer ticketLevel() {
        return ticketLevel;
    }

    /**
     * 这个区块在数据库中是否「有主」。
     *
     * <p>决定三件事：是否写库、占用谁配额的计数、他人能否认领。
     * 注意 {@link #RESERVED} 也返回 true —— 它正是「有主但不加载」。</p>
     */
    public boolean isControlled() {
        return this != UNCONTROLLED;
    }

    /**
     * 插件是否要为这个区块加 ticket。
     *
     * <p>与 {@link #isControlled()} 的区别就是 {@link #RESERVED}：
     * 它有主，但不加载任何东西。</p>
     */
    public boolean isLoaded() {
        return ticketLevel != null;
    }

    /**
     * 从数据库值还原状态。
     *
     * @throws IllegalArgumentException 值不合法（说明库被外部改坏了）
     */
    public static ChunkState fromDbValue(int value) {
        for (ChunkState state : values()) {
            if (state.dbValue == value) {
                return state;
            }
        }
        throw new IllegalArgumentException("未知的 chunk_control.state 值: " + value);
    }

    /** 中文显示名（GUI 名称与命令输出统一用这个）。 */
    public String displayName() {
        return switch (this) {
            case UNCONTROLLED -> "未控制";
            case WEAK -> "弱加载";
            case STRONG -> "强加载";
            case RESERVED -> "保留";
        };
    }

    /** 带补充说明的完整名称，用于 GUI 名称。 */
    public String fullName() {
        return switch (this) {
            case UNCONTROLLED -> "未控制（原版自动加载）";
            case WEAK -> "弱加载";
            case STRONG -> "强加载";
            case RESERVED -> "保留（不加载·他人不可认领）";
        };
    }

    /**
     * 命令参数名（{@code /cl set weak|strong|reserve|none}）。
     */
    public String commandName() {
        return switch (this) {
            case UNCONTROLLED -> "none";
            case WEAK -> "weak";
            case STRONG -> "strong";
            case RESERVED -> "reserve";
        };
    }

    /**
     * 解析命令参数，大小写不敏感。
     *
     * @return 解析结果；无法识别时返回 {@code null}
     */
    public static ChunkState parse(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (s) {
            case "none", "off", "unset", "release", "未控制" -> UNCONTROLLED;
            case "weak", "lazy", "弱", "弱加载" -> WEAK;
            case "strong", "entity", "强", "强加载" -> STRONG;
            case "reserve", "reserved", "hold", "保留" -> RESERVED;
            default -> null;
        };
    }

    /**
     * 左键升级：未控制 → 弱 → 强 → 强（封顶）。
     * <p>保留态左键 = 开始加载，变成弱加载。</p>
     */
    public ChunkState upgraded() {
        return switch (this) {
            case UNCONTROLLED -> WEAK;
            case RESERVED -> WEAK;
            case WEAK -> STRONG;
            case STRONG -> STRONG;
        };
    }

    /**
     * 右键降级：强 → 弱 → 保留 → 保留（封底）。
     *
     * <p>为什么弱加载降级后是「保留」而不是「未控制」：
     * 降级到「未控制」= 删除记录 = 放弃归属，别人立刻能抢。
     * 这是个不可逆的破坏性操作，不应该由一次右键意外触发。
     * 想真正释放请用 {@code /cl set none} 或 {@code /cl release}。</p>
     */
    public ChunkState downgraded() {
        return switch (this) {
            case STRONG -> WEAK;
            case WEAK -> RESERVED;
            case RESERVED -> RESERVED;
            case UNCONTROLLED -> UNCONTROLLED;
        };
    }
}
