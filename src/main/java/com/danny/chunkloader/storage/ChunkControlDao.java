package com.danny.chunkloader.storage;

import com.danny.chunkloader.model.ChunkControl;
import com.danny.chunkloader.model.ChunkKey;
import com.danny.chunkloader.model.ChunkState;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code chunk_control} 表的数据访问。
 *
 * <p>设计要点：
 * <ul>
 *   <li><b>没有「未控制」的行</b>。{@link #release} 就是 DELETE，{@link #find} 找不到即为未控制。</li>
 *   <li>写入用 {@code INSERT ... ON CONFLICT DO UPDATE}，这样「我改自己区块的状态」和
 *       「先到先得抢占」由主键唯一性天然保证，不需要先查询再写入（避免竞态）。</li>
 * </ul>
 */
public final class ChunkControlDao {

    private final Database db;

    public ChunkControlDao(Database db) {
        this.db = db;
    }

    /** 查单个区块的归属。 */
    public Optional<ChunkControl> find(ChunkKey key) throws SQLException {
        return db.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT owner, state, created_at, updated_at FROM chunk_control "
                            + "WHERE world = ? AND chunk_x = ? AND chunk_z = ?")) {
                ps.setString(1, key.world());
                ps.setInt(2, key.x());
                ps.setInt(3, key.z());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.empty();
                    }
                    return Optional.of(new ChunkControl(
                            key,
                            UUID.fromString(rs.getString("owner")),
                            ChunkState.fromDbValue(rs.getInt("state")),
                            rs.getLong("created_at"),
                            rs.getLong("updated_at")));
                }
            }
        });
    }

    /**
     * 写入或更新一个区块的控制状态。
     *
     * <p><b>不检查归属</b> —— 归属校验属于业务层（校验顺序属于业务层职责）。
     * 本方法只负责持久化。若要抢占他人的区块，调用方必须先确认权限。
     *
     * @param key   区块
     * @param owner 新的持有者
     * @param state 弱加载或强加载
     * @param now   当前毫秒时间戳
     */
    public void upsert(ChunkKey key, UUID owner, ChunkState state, long now) throws SQLException {
        if (!state.isControlled()) {
            throw new IllegalArgumentException("不能把「未控制」写入 chunk_control，请调用 release()");
        }
        db.withConnectionVoid(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO chunk_control (world, chunk_x, chunk_z, owner, state, created_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?) "
                            + "ON CONFLICT(world, chunk_x, chunk_z) DO UPDATE SET "
                            + "  owner = excluded.owner, "
                            + "  state = excluded.state, "
                            + "  updated_at = excluded.updated_at")) {
                ps.setString(1, key.world());
                ps.setInt(2, key.x());
                ps.setInt(3, key.z());
                ps.setString(4, owner.toString());
                ps.setInt(5, state.dbValue());
                ps.setLong(6, now);
                ps.setLong(7, now);
                ps.executeUpdate();
            }
        });
    }

    /**
     * 释放单个区块（DELETE）。
     *
     * @return true 表示确实删除了一行；false 表示该区块本来就不受控
     */
    public boolean release(ChunkKey key) throws SQLException {
        return db.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM chunk_control WHERE world = ? AND chunk_x = ? AND chunk_z = ?")) {
                ps.setString(1, key.world());
                ps.setInt(2, key.x());
                ps.setInt(3, key.z());
                return ps.executeUpdate() > 0;
            }
        });
    }

    /**
     * 释放某玩家在指定世界的全部区块。
     *
     * @return 删除的行数
     */
    public int releaseAll(UUID owner, String world) throws SQLException {
        return db.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM chunk_control WHERE owner = ? AND world = ?")) {
                ps.setString(1, owner.toString());
                ps.setString(2, world);
                return ps.executeUpdate();
            }
        });
    }

    /** 释放某玩家在所有世界的全部区块。 */
    public int releaseAllWorlds(UUID owner) throws SQLException {
        return db.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM chunk_control WHERE owner = ?")) {
                ps.setString(1, owner.toString());
                return ps.executeUpdate();
            }
        });
    }

    /** 某玩家在指定世界的全部受控区块，按创建时间排序（GUI 视图 B / {@code /cl list} 用）。 */
    public List<ChunkControl> listByOwner(UUID owner, String world) throws SQLException {
        return db.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT world, chunk_x, chunk_z, owner, state, created_at, updated_at "
                            + "FROM chunk_control WHERE owner = ? AND world = ? "
                            + "ORDER BY created_at ASC, chunk_x ASC, chunk_z ASC")) {
                ps.setString(1, owner.toString());
                ps.setString(2, world);
                return readAll(ps);
            }
        });
    }

    /** 某玩家在所有世界的全部受控区块。 */
    public List<ChunkControl> listByOwnerAllWorlds(UUID owner) throws SQLException {
        return db.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT world, chunk_x, chunk_z, owner, state, created_at, updated_at "
                            + "FROM chunk_control WHERE owner = ? "
                            + "ORDER BY world ASC, created_at ASC")) {
                ps.setString(1, owner.toString());
                return readAll(ps);
            }
        });
    }

    /** 指定世界内的全部记录（启动恢复用）。 */
    public List<ChunkControl> listByWorld(String world) throws SQLException {
        return db.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT world, chunk_x, chunk_z, owner, state, created_at, updated_at "
                            + "FROM chunk_control WHERE world = ? ORDER BY chunk_x, chunk_z")) {
                ps.setString(1, world);
                return readAll(ps);
            }
        });
    }

    /** 全部世界的全部记录（启动恢复 + 全服配额统计）。 */
    public List<ChunkControl> listAll() throws SQLException {
        return db.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT world, chunk_x, chunk_z, owner, state, created_at, updated_at "
                            + "FROM chunk_control ORDER BY world, chunk_x, chunk_z")) {
                return readAll(ps);
            }
        });
    }

    /** 某玩家在某世界的受控总数（配额校验）。 */
    public int countByOwner(UUID owner, String world) throws SQLException {
        return db.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT COUNT(*) FROM chunk_control WHERE owner = ? AND world = ?")) {
                ps.setString(1, owner.toString());
                ps.setString(2, world);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        });
    }

    /** 某玩家在某世界的强加载数量（配额校验）。 */
    public int countStrongByOwner(UUID owner, String world) throws SQLException {
        return db.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT COUNT(*) FROM chunk_control WHERE owner = ? AND world = ? AND state = ?")) {
                ps.setString(1, owner.toString());
                ps.setString(2, world);
                ps.setInt(3, ChunkState.STRONG.dbValue());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        });
    }

    /** 全服跨世界受控总数。 */
    public int countGlobal() throws SQLException {
        return db.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM chunk_control");
                 ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        });
    }

    /** 全服跨世界强加载总数。 */
    public int countGlobalStrong() throws SQLException {
        return db.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT COUNT(*) FROM chunk_control WHERE state = ?")) {
                ps.setInt(1, ChunkState.STRONG.dbValue());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        });
    }

    /** 某世界内的受控总数（{@code /cl admin status} 用）。 */
    public int countByWorld(String world) throws SQLException {
        return db.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT COUNT(*) FROM chunk_control WHERE world = ?")) {
                ps.setString(1, world);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        });
    }

    /**
     * 长期未上线的持有者报告，供 {@code /cl admin status} 使用。
     *
     * <p>注意：本方法<b>只报告</b>，不释放任何东西。释放永远由管理员手动发起。
     *
     * @param sinceMillis 只统计 {@code updated_at} 早于该时间的记录
     * @return 每项为 {@code [ownerUuid, world, 区块数]}
     */
    public List<OwnerLoad> reportStaleOwners(long sinceMillis) throws SQLException {
        return db.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT owner, world, COUNT(*) AS cnt, MAX(updated_at) AS last_ts "
                            + "FROM chunk_control WHERE updated_at < ? "
                            + "GROUP BY owner, world ORDER BY cnt DESC")) {
                ps.setLong(1, sinceMillis);
                List<OwnerLoad> result = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        result.add(new OwnerLoad(
                                UUID.fromString(rs.getString("owner")),
                                rs.getString("world"),
                                rs.getInt("cnt"),
                                rs.getLong("last_ts")));
                    }
                }
                return result;
            }
        });
    }

    /** {@link #reportStaleOwners} 的行。 */
    public record OwnerLoad(UUID owner, String world, int chunkCount, long lastTouchedAt) {
    }

    private static List<ChunkControl> readAll(PreparedStatement ps) throws SQLException {
        List<ChunkControl> list = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                list.add(new ChunkControl(
                        new ChunkKey(rs.getString("world"), rs.getInt("chunk_x"), rs.getInt("chunk_z")),
                        UUID.fromString(rs.getString("owner")),
                        ChunkState.fromDbValue(rs.getInt("state")),
                        rs.getLong("created_at"),
                        rs.getLong("updated_at")));
            }
        }
        return list;
    }
}
