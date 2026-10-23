package io.github.frewily.campushub.content.infrastructure;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.injector.DefaultSqlInjector;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import io.github.frewily.campushub.content.application.BlogUserReader;
import io.github.frewily.campushub.dto.UserDTO;
import io.github.frewily.campushub.mapper.UserMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Real user display lookups over a private temporary localhost MySQL; never uses shared data. */
class BlogUserMySqlIT {
    @TempDir static Path directory;
    private static Process mysql;
    private static JdbcTemplate jdbc;
    private static BlogUserReader reader;
    private static final long LARGE_ID = 9007199254740993L;

    @BeforeAll
    static void start() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) { port = socket.getLocalPort(); }
        System.out.println("BLOG_USER_MYSQL_PORT=" + port);
        String binary = System.getenv().getOrDefault("MYSQLD_SERVER_BINARY", "mysqld");
        Path data = directory.resolve("mysql-data");
        Process init = new ProcessBuilder(binary, "--no-defaults", "--initialize-insecure", "--datadir=" + data)
                .redirectErrorStream(true).redirectOutput(directory.resolve("init.log").toFile()).start();
        try {
            assertTrue(init.waitFor(30, TimeUnit.SECONDS), "private initializer timed out");
            assertEquals(0, init.exitValue(), "inspect private init.log");
        } finally { stopProcess(init); }
        try {
            mysql = new ProcessBuilder(binary, "--no-defaults", "--datadir=" + data, "--bind-address=127.0.0.1",
                    "--port=" + port, "--mysqlx=OFF", "--skip-log-bin", "--socket=" + directory.resolve("mysql.sock"),
                    "--pid-file=" + directory.resolve("mysql.pid"), "--log-error=" + directory.resolve("mysql.log"))
                    .redirectErrorStream(true).redirectOutput(directory.resolve("console.log").toFile()).start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
            boolean ready = false;
            while (mysql.isAlive() && System.nanoTime() < deadline) {
                try (Connection connection = DriverManager.getConnection(url(port, ""), "root", "")) {
                    connection.createStatement().execute("CREATE DATABASE blog_user_test CHARACTER SET utf8mb4");
                    ready = true;
                    break;
                } catch (SQLException unavailable) { Thread.sleep(50); }
            }
            assertTrue(ready, "private MySQL did not start");
            DriverManagerDataSource source = new DriverManagerDataSource(url(port, "blog_user_test"), "root", "");
            jdbc = new JdbcTemplate(source);
            jdbc.execute("CREATE TABLE tb_user(id BIGINT PRIMARY KEY,phone VARCHAR(32),password VARCHAR(128),"
                    + "nick_name VARCHAR(64),icon VARCHAR(255),status VARCHAR(16),create_time DATETIME,update_time DATETIME)");
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(source);
            MybatisConfiguration configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true);
            configuration.addMapper(UserMapper.class);
            factory.setConfiguration(configuration);
            GlobalConfig global = new GlobalConfig();
            global.setSqlInjector(new DefaultSqlInjector());
            factory.setGlobalConfig(global);
            SqlSessionTemplate sessions = new SqlSessionTemplate(factory.getObject());
            reader = new MyBatisBlogUserReader(sessions.getMapper(UserMapper.class));
        } catch (Exception | Error failure) {
            stopProcess(mysql);
            throw failure;
        }
    }

    private static String url(int port, String database) {
        return "jdbc:mysql://127.0.0.1:" + port + "/" + database + "?useSSL=false&allowPublicKeyRetrieval=true"
                + "&serverTimezone=UTC&forceConnectionTimeZoneToSession=true&connectTimeout=1000&socketTimeout=2000";
    }

    private static void stopProcess(Process process) throws Exception {
        if (process == null) return;
        if (process.isAlive()) {
            process.destroy();
            if (!process.waitFor(8, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                assertTrue(process.waitFor(5, TimeUnit.SECONDS));
            }
        }
        assertFalse(process.isAlive(), "private process must stop");
    }

    @AfterAll static void stop() throws Exception { stopProcess(mysql); }

    @BeforeEach void reset() { jdbc.execute("DELETE FROM tb_user"); }

    private void insert(long id, String name, String status) {
        jdbc.update("INSERT INTO tb_user VALUES(?,?,?,?,?,?,?,?)", id, "synthetic-phone", "synthetic-password",
                name, name + ".svg", status, "2020-01-02 03:04:05", "2020-02-03 04:05:06");
    }

    private List<Map<String, Object>> rows() { return jdbc.queryForList("SELECT * FROM tb_user ORDER BY id"); }

    @Test
    void authorLookupMapsOnlyDisplayFieldsIncludingDisabledLargeIdWithoutChangingRows() {
        insert(LARGE_ID, "synthetic author", "DISABLED");
        List<Map<String, Object>> before = rows();

        UserDTO dto = reader.findAuthor(LARGE_ID);

        assertEquals(LARGE_ID, dto.getId());
        assertEquals("synthetic author", dto.getNickName());
        assertEquals("synthetic author.svg", dto.getIcon());
        assertEquals(3, UserDTO.class.getDeclaredFields().length);
        assertEquals(before, rows());
    }

    @Test
    void lookupsReturnMissingRowsAndLikersKeepRequestedDatabaseOrderWithoutFilteringDisabled() {
        insert(LARGE_ID, "first", "DISABLED");
        insert(41L, "second", "ACTIVE");
        List<Map<String, Object>> before = rows();

        assertNull(reader.findAuthor(777L));
        List<UserDTO> likers = reader.findLikers(Arrays.asList(LARGE_ID, 999L, 41L));

        assertEquals(Arrays.asList(LARGE_ID, 41L), Arrays.asList(likers.get(0).getId(), likers.get(1).getId()));
        assertEquals(Arrays.asList("first", "second"), Arrays.asList(likers.get(0).getNickName(), likers.get(1).getNickName()));
        assertEquals(before, rows());
    }

    @Test
    void eachCallReadsCurrentDatabaseAndDoesNotCacheOrWrite() {
        insert(LARGE_ID, "before", "ACTIVE");
        assertEquals("before", reader.findAuthor(LARGE_ID).getNickName());
        jdbc.update("UPDATE tb_user SET nick_name='after' WHERE id=?", LARGE_ID);
        List<Map<String, Object>> beforeLookup = rows();

        assertEquals("after", reader.findAuthor(LARGE_ID).getNickName());
        assertEquals(beforeLookup, rows());
    }

    @Test
    void databaseFailuresPropagateAndSchemaRecoveryLeavesRowsIntact() {
        insert(LARGE_ID, "synthetic", "ACTIVE");
        List<Map<String, Object>> before = rows();
        jdbc.execute("RENAME TABLE tb_user TO phase7z_owned_user_fault");
        try {
            assertThrows(DataAccessException.class, () -> reader.findAuthor(LARGE_ID));
            assertThrows(DataAccessException.class, () -> reader.findLikers(Collections.singletonList(LARGE_ID)));
        } finally {
            jdbc.execute("RENAME TABLE phase7z_owned_user_fault TO tb_user");
        }
        assertEquals(before, rows());
        assertNotNull(reader.findAuthor(LARGE_ID));
    }
}
