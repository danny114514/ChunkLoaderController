package com.danny.chunkloader.storage;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;
/**
 * SQLite 连接管理 + 建表。
 *
 * <p><b>并发模型</b>：Folia 是多线程的，写操作可能来自任意 region 线程。
 * SQLite 单文件写入本身是串行的，这里用一个 {@link ReentrantLock} 把所有访问串起来，
 * 比引入连接池更简单且没有池耗尽/事务隔离的坑。区块操作频率很低（人手点击级别），
 * 这是完全够用的设计。
 *
 * <p><b>驱动来源</b>：Folia 服务端的 {@code libraries.list} 已内置
 * {@code org.xerial:sqlite-jdbc:3.42.0.0}，因此插件<b>不需要</b>把驱动打进 jar。
 * 为了不依赖「恰好已加载」这种巧合，这里显式用独立 ClassLoader 尝试加载驱动，
 * 并在失败时给出可操作的报错。
 */
public final class Database implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger("ChunkLoaderController");

    /** 建表语句；全部 IF NOT EXISTS，可重复执行。 */
    private static final String[] SCHEMA = {
            """
            CREATE TABLE IF NOT EXISTS chunk_control (
              world      TEXT    NOT NULL,
              chunk_x    INTEGER NOT NULL,
              chunk_z    INTEGER NOT NULL,
              owner      TEXT    NOT NULL,
              state      INTEGER NOT NULL,
              created_at INTEGER NOT NULL,
              updated_at INTEGER NOT NULL,
              PRIMARY KEY (world, chunk_x, chunk_z)
            )""",
            "CREATE INDEX IF NOT EXISTS idx_owner ON chunk_control(owner)",
            "CREATE INDEX IF NOT EXISTS idx_world ON chunk_control(world)",
            """
            CREATE TABLE IF NOT EXISTS audit_log (
              id         INTEGER PRIMARY KEY AUTOINCREMENT,
              ts         INTEGER NOT NULL,
              actor      TEXT    NOT NULL,
              target     TEXT,
              world      TEXT,
              chunk_x    INTEGER,
              chunk_z    INTEGER,
              action     TEXT    NOT NULL,
              result     TEXT    NOT NULL,
              reason     TEXT
            )""",
            "CREATE INDEX IF NOT EXISTS idx_audit_ts ON audit_log(ts)",
            "CREATE INDEX IF NOT EXISTS idx_audit_actor ON audit_log(actor)"
    };

    private final ReentrantLock lock = new ReentrantLock();
    private final Connection connection;
    private final String jdbcUrl;

    private Database(String jdbcUrl, Connection connection) {
        this.jdbcUrl = jdbcUrl;
        this.connection = connection;
    }

    /**
     * 打开数据库并建表。
     *
     * <p>pragma 走 URL 查询参数而不是 {@code Properties}：sqlite-jdbc 会把 URL 里
     * 无法识别的参数直接报错，而 Properties 里名字不匹配的键会被<b>静默忽略</b>，
     * 导致 WAL 没开还没人知道。宁可启动就炸。
     *
     * @param filePath sqlite 文件绝对路径
     * @throws SQLException 驱动缺失、文件不可写、建表失败
     */
    public static Database open(String filePath) throws SQLException {
        loadDriver();
        // journal_mode=WAL：读写不互相阻塞，多 region 线程并发写入时明显更稳。
        // busy_timeout=5000：拿不到写锁时等 5 秒再报错，避免高峰期偶发 SQLITE_BUSY。
        String url = "jdbc:sqlite:" + filePath + "?journal_mode=WAL&synchronous=NORMAL&busy_timeout=5000";
        Connection conn = DriverManager.getConnection(url);
        conn.setAutoCommit(true);
        Database db = new Database(url, conn);
        db.createSchema();
        db.verifyPragmas();
        return db;
    }

    /**
     * 显式加载 sqlite 驱动。
     *
     * <p>Bukkit 插件类加载器是扁平的（plugin → libraries），正常 {@code Class.forName} 就够。
     * 但为了让报错可读（而不是一句 {@code No suitable driver}），这里区分三级原因。
     */
    private static void loadDriver() throws SQLException {
        try {
            Class.forName("org.sqlite.JDBC");
            return;
        } catch (ClassNotFoundException ignored) {
            // 落到下面的备用尝试
        }
        // 备用：很多服务端的库是通过 PluginClassLoader 的 addURL 注入的，
        // 若当前类加载器看不到，尝试系统类加载器。
        try {
            ClassLoader sys = ClassLoader.getSystemClassLoader();
            Driver d = (Driver) Class.forName("org.sqlite.JDBC", true, sys).getDeclaredConstructor().newInstance();
            DriverManager.registerDriver(new DriverShim(d));
            LOG.info("sqlite 驱动通过系统类加载器加载成功");
            return;
        } catch (ReflectiveOperationException | SQLException e) {
            throw new SQLException(
                    "找不到 SQLite 驱动 org.sqlite.JDBC。Folia 服务端正常自带 "
                            + "org.xerial:sqlite-jdbc（libraries.list 内），本机缺失说明服务端安装不完整。",
                    e);
        }
    }

    private void createSchema() throws SQLException {
        lock.lock();
        try (Statement st = connection.createStatement()) {
            for (String ddl : SCHEMA) {
                st.execute(ddl);
            }
        } catch (SQLException e) {
            throw new SQLException("建表失败: " + e.getMessage(), e);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 建完表后核对 pragma 是否真的生效。
     *
     * <p>WAL 在某些情况会静默回退成 delete 模式（例如数据库位于不支持共享内存的文件系统，
     * 或已有连接以别的模式打开）。这里不抛异常，只 warning —— 功能不受影响，只是并发性能差些。
     */
    private void verifyPragmas() {
        try {
            String mode = withConnection(conn -> {
                try (Statement st = conn.createStatement();
                     var rs = st.executeQuery("PRAGMA journal_mode")) {
                    return rs.next() ? rs.getString(1) : "unknown";
                }
            });
            if (!"wal".equalsIgnoreCase(mode)) {
                LOG.warning("SQLite journal_mode 期望 wal，实际为 " + mode
                        + "（数据库文件所在磁盘可能不支持共享内存；功能正常，只是并发写入性能下降）");
            }
        } catch (SQLException e) {
            LOG.log(Level.WARNING, "读取 journal_mode 失败", e);
        }
    }

    /** 在锁保护下取得连接执行一组操作。 */
    <T> T withConnection(SqlFunction<T> action) throws SQLException {
        lock.lock();
        try {
            return action.apply(connection);
        } finally {
            lock.unlock();
        }
    }

    /** 无返回值的连接操作。 */
    void withConnectionVoid(SqlConsumer action) throws SQLException {
        withConnection(conn -> {
            action.accept(conn);
            return null;
        });
    }

    public String jdbcUrl() {
        return jdbcUrl;
    }

    /** 供 selftest 汇报。 */
    public String driverVersion() {
        try {
            return connection.getMetaData().getDriverVersion();
        } catch (SQLException e) {
            return "未知(" + e.getMessage() + ")";
        }
    }

    /** 供 selftest 汇报：SQLite 库版本。 */
    public String sqliteVersion() {
        try {
            return withConnection(conn -> {
                try (Statement st = conn.createStatement();
                     var rs = st.executeQuery("SELECT sqlite_version()")) {
                    return rs.next() ? rs.getString(1) : "未知";
                }
            });
        } catch (SQLException e) {
            return "未知(" + e.getMessage() + ")";
        }
    }

    @Override
    public void close() {
        lock.lock();
        try {
            if (!connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException e) {
            LOG.log(Level.WARNING, "关闭数据库连接时出错", e);
        } finally {
            lock.unlock();
        }
    }

    /** 允许抛 SQLException 的函数式接口。 */
    @FunctionalInterface
    interface SqlFunction<T> {
        T apply(Connection conn) throws SQLException;
    }

    /** 无返回值的版本。 */
    @FunctionalInterface
    interface SqlConsumer {
        void accept(Connection conn) throws SQLException;
    }

    /**
     * 包一层 {@link Driver}，用于把「从别的类加载器加载的驱动」注册进 DriverManager。
     */
    private static final class DriverShim implements Driver {
        private final Driver delegate;

        DriverShim(Driver delegate) {
            this.delegate = delegate;
        }

        @Override
        public Connection connect(String url, Properties info) throws SQLException {
            return delegate.connect(url, info);
        }

        @Override
        public boolean acceptsURL(String url) throws SQLException {
            return delegate.acceptsURL(url);
        }

        @Override
        public java.sql.DriverPropertyInfo[] getPropertyInfo(String url, Properties info) throws SQLException {
            return delegate.getPropertyInfo(url, info);
        }

        @Override
        public int getMajorVersion() {
            return delegate.getMajorVersion();
        }

        @Override
        public int getMinorVersion() {
            return delegate.getMinorVersion();
        }

        @Override
        public boolean jdbcCompliant() {
            return delegate.jdbcCompliant();
        }

        @Override
        public java.util.logging.Logger getParentLogger() throws java.sql.SQLFeatureNotSupportedException {
            return delegate.getParentLogger();
        }
    }
}
