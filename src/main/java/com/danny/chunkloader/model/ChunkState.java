package com.danny.chunkloader.model;

/**
 * 本插件管理的三种区块状态。
 *
 * <p>与 Bukkit 的 {@link org.bukkit.Chunk.LoadLevel} 不同，这里表达的是「玩家意图」，
 * 而不是「当前实际等级」。实际等级要在运行时读 ticket level 才知道，
 * 二者的差异（例如玩家视距把弱加载抬成强加载）由
 * {@code com.danny.chunkloader.command.LevelText#shadowNote} 负责解释。
 */
public enum ChunkState {

    /**
     * 未控制：插件不加任何 ticket，完全交给原版自动加载。
     * <p>数据库中不存储该状态 —— 删除行即等于未控制。
     */
    UNCONTROLLED(0, null),

    /**
     * 弱加载：ticket level = 32（{@code BLOCK_TICKING}）。
     * <p>方块实体、计划刻、随机刻继续；<b>实体刻完全冻结</b>。
     */
    WEAK(1, 32),

    /**
     * 强加载：ticket level = 31（{@code ENTITY_TICKING}）。
     * <p>走公开 API {@code World#addPluginChunkTicket}，任意线程可调。
     */
    STRONG(2, 31);

    /** 数据库中的持久化值，0 保留给 UNCONTROLLED（正常不会写库）。 */
    private final int dbValue;

    /** 对应的原版 ticket level；UNCONTROLLED 为 null（不加 ticket）。 */
    private final Integer ticketLevel;

    ChunkState(int dbValue, Integer ticketLevel) {
        this.dbValue = dbValue;
        this.ticketLevel = ticketLevel;
    }

    public int dbValue() {
        return dbValue;
    }

    /**
     * @return 对应的原版 ticket level；未控制返回 {@code null}
     */
    public Integer ticketLevel() {
        return ticketLevel;
    }

    public boolean isControlled() {
        return this != UNCONTROLLED;
    }

    /**
     * 从数据库值还原状态。
     *
     * @throws IllegalArgumentException 值不在 0..2 内（说明库被外部改坏了）
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
        };
    }

    /** 带补充说明的完整名称，用于 GUI 名称。 */
    public String fullName() {
        return switch (this) {
            case UNCONTROLLED -> "未控制（原版自动加载）";
            case WEAK -> "弱加载";
            case STRONG -> "强加载";
        };
    }

    /**
     * 命令参数名（{@code /cl set weak|strong|none}）。
     */
    public String commandName() {
        return switch (this) {
            case UNCONTROLLED -> "none";
            case WEAK -> "weak";
            case STRONG -> "strong";
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
            case "none", "off", "unset", "未控制" -> UNCONTROLLED;
            case "weak", "lazy", "弱", "弱加载" -> WEAK;
            case "strong", "entity", "强", "强加载" -> STRONG;
            default -> null;
        };
    }

    /**
     * 左键升级：未控制 → 弱 → 强 → 强（封顶）。
     */
    public ChunkState upgraded() {
        return switch (this) {
            case UNCONTROLLED -> WEAK;
            case WEAK -> STRONG;
            case STRONG -> STRONG;
        };
    }

    /**
     * 右键降级：强 → 弱 → 未控制 → 未控制（封底）。
     */
    public ChunkState downgraded() {
        return switch (this) {
            case STRONG -> WEAK;
            case WEAK -> UNCONTROLLED;
            case UNCONTROLLED -> UNCONTROLLED;
        };
    }
}
