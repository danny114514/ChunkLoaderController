package com.danny.chunkloader.storage;

import com.danny.chunkloader.model.ChunkGroup;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code chunk_group} 表的数据访问。
 *
 * <p>群组名在同一所有者下唯一（数据库层有唯一索引兜底），不同玩家可以重名 ——
 * 群组是私有的，两个人各自有一个「农场」互不干扰。</p>
 */
public final class ChunkGroupDao {

    private final Database db;

    public ChunkGroupDao(Database db) {
        this.db = db;
    }

    /**
     * 创建群组。
     *
     * @param owner 所有者
     * @param name  已规范化的群组名
     * @return 新建的群组（含生成的 ID）
     * @throws SQLException 名字重复时抛出（唯一索引冲突）
     */
    public ChunkGroup create(UUID owner, String name, long now) throws SQLException {
        return db.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO chunk_group (owner, name, created_at) VALUES (?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, owner.toString());
                ps.setString(2, name);
                ps.setLong(3, now);
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    long id = keys.next() ? keys.getLong(1) : -1L;
                    return new ChunkGroup(id, owner, name, now);
                }
            }
        });
    }

    /** 按所有者 + 名字查找。 */
    public Optional<ChunkGroup> find(UUID owner, String name) throws SQLException {
        return db.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT id, owner, name, created_at FROM chunk_group "
                            + "WHERE owner = ? AND name = ?")) {
                ps.setString(1, owner.toString());
                ps.setString(2, name);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(read(rs)) : Optional.empty();
                }
            }
        });
    }

    /** 按 ID 查找（不校验所有者，调用方负责权限）。 */
    public Optional<ChunkGroup> findById(long id) throws SQLException {
        return db.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT id, owner, name, created_at FROM chunk_group WHERE id = ?")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(read(rs)) : Optional.empty();
                }
            }
        });
    }

    /** 某玩家的全部群组，按创建时间排序。 */
    public List<ChunkGroup> listByOwner(UUID owner) throws SQLException {
        return db.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT id, owner, name, created_at FROM chunk_group "
                            + "WHERE owner = ? ORDER BY created_at ASC, id ASC")) {
                ps.setString(1, owner.toString());
                List<ChunkGroup> list = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        list.add(read(rs));
                    }
                }
                return list;
            }
        });
    }

    /**
     * 删除群组本身。
     *
     * <p><b>不负责处理组内区块</b> —— 那些区块的归属变更必须由业务层用
     * {@link ChunkControlDao#releaseByGroup} 显式完成，且要在同一个事务语义下调用。
     * 这里只删群组行。</p>
     *
     * @return 是否真的删除了
     */
    public boolean delete(long id) throws SQLException {
        return db.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM chunk_group WHERE id = ?")) {
                ps.setLong(1, id);
                return ps.executeUpdate() > 0;
            }
        });
    }

    /** 改名。 */
    public boolean rename(long id, String newName) throws SQLException {
        return db.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE chunk_group SET name = ? WHERE id = ?")) {
                ps.setString(1, newName);
                ps.setLong(2, id);
                return ps.executeUpdate() > 0;
            }
        });
    }

    private static ChunkGroup read(ResultSet rs) throws SQLException {
        return new ChunkGroup(
                rs.getLong("id"),
                UUID.fromString(rs.getString("owner")),
                rs.getString("name"),
                rs.getLong("created_at"));
    }
}
