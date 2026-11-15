package com.danny.chunkloader.storage;

import com.danny.chunkloader.model.ChunkKey;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * {@code audit_log} 表的数据访问。
 *
 * <p>写审计<b>不能影响主流程</b>：日志表写失败时只记 warning，绝不因此让玩家的操作失败。
 * 但拒绝原因必须落库 —— 否则管理员查不到「为什么当时被拒绝」。
 */
public final class AuditLogDao {

    /**
     * 控制台等非玩家主体的固定标识。
     *
     * <p>不能存 null：{@code actor NOT NULL}，而且用 'CONSOLE' 比用全零 UUID 更可读。
     */
    public static final String CONSOLE_ACTOR = "CONSOLE";

    private final Database db;

    public AuditLogDao(Database db) {
        this.db = db;
    }

    /**
     * 写一条审计。
     *
     * @param actor  操作者 UUID；非玩家传 {@code null} → 记为 {@link #CONSOLE_ACTOR}
     * @param target 受影响玩家 UUID，可为 null
     * @param key    目标区块，可为 null（如 reload 这类与区块无关的操作）
     * @param reason 补充说明，可为 null
     */
    public void log(UUID actor, UUID target, ChunkKey key, AuditAction action,
                    AuditResult result, String reason) throws SQLException {
        String actorText = actor == null ? CONSOLE_ACTOR : actor.toString();
        String targetText = target == null ? null : target.toString();
        db.withConnectionVoid(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO audit_log (ts, actor, target, world, chunk_x, chunk_z, action, result, reason) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                ps.setLong(1, System.currentTimeMillis());
                ps.setString(2, actorText);
                if (targetText == null) {
                    ps.setNull(3, Types.VARCHAR);
                } else {
                    ps.setString(3, targetText);
                }
                if (key == null) {
                    ps.setNull(4, Types.VARCHAR);
                    ps.setNull(5, Types.INTEGER);
                    ps.setNull(6, Types.INTEGER);
                } else {
                    ps.setString(4, key.world());
                    ps.setInt(5, key.x());
                    ps.setInt(6, key.z());
                }
                ps.setString(7, action.name());
                ps.setString(8, result.name());
                ps.setString(9, reason);
                ps.executeUpdate();
            }
        });
    }

    /**
     * 分页查询审计日志，按时间倒序。
     *
     * @param actor  只看某操作者；null 表示全部
     * @param offset 从第几条开始（0 基）
     * @param limit  取几条
     */
    public List<AuditEntry> page(UUID actor, int offset, int limit) throws SQLException {
        return db.withConnection(conn -> {
            String sql = "SELECT ts, actor, target, world, chunk_x, chunk_z, action, result, reason "
                    + "FROM audit_log "
                    + (actor == null ? "" : "WHERE actor = ? ")
                    + "ORDER BY id DESC LIMIT ? OFFSET ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                int idx = 1;
                if (actor != null) {
                    ps.setString(idx++, actor.toString());
                }
                ps.setInt(idx++, limit);
                ps.setInt(idx, offset);
                List<AuditEntry> list = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Integer cx = rs.getObject("chunk_x") == null ? null : rs.getInt("chunk_x");
                        Integer cz = rs.getObject("chunk_z") == null ? null : rs.getInt("chunk_z");
                        list.add(new AuditEntry(
                                rs.getLong("ts"),
                                rs.getString("actor"),
                                rs.getString("target"),
                                rs.getString("world"),
                                cx,
                                cz,
                                rs.getString("action"),
                                rs.getString("result"),
                                rs.getString("reason")));
                    }
                }
                return list;
            }
        });
    }

    /** 审计总数（分页用）。 */
    public int count(UUID actor) throws SQLException {
        return db.withConnection(conn -> {
            String sql = "SELECT COUNT(*) FROM audit_log" + (actor == null ? "" : " WHERE actor = ?");
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                if (actor != null) {
                    ps.setString(1, actor.toString());
                }
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        });
    }

    /**
     * 删除早于指定时间戳的日志。由插件每天调用一次。
     *
     * @return 删除条数
     */
    public int pruneBefore(long timestamp) throws SQLException {
        return db.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM audit_log WHERE ts < ?")) {
                ps.setLong(1, timestamp);
                return ps.executeUpdate();
            }
        });
    }

    /**
     * 一行审计记录。
     *
     * <p>坐标用可空的 {@code Integer}：{@code reload}、{@code selftest} 这类操作没有目标区块。
     */
    public record AuditEntry(long ts, String actor, String target, String world,
                             Integer chunkX, Integer chunkZ, String action, String result,
                             String reason) {
    }
}
