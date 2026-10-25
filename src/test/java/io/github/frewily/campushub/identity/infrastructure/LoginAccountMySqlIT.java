package io.github.frewily.campushub.identity.infrastructure;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.injector.DefaultSqlInjector;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import io.github.frewily.campushub.dto.LoginFormDTO;
import io.github.frewily.campushub.identity.application.LoginAccount;
import io.github.frewily.campushub.identity.application.LoginAccountRepository;
import io.github.frewily.campushub.mapper.UserMapper;
import io.github.frewily.campushub.service.impl.UserServiceImpl;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Real UserMapper and Spring JDBC transactions against an owned, temporary localhost MySQL. */
class LoginAccountMySqlIT {
    private static final long LARGE_ID = 9007199254744993L;
    private static final String PHONE = "13800009991";

    @TempDir static Path directory;
    private static Process mysql;
    private static JdbcTemplate jdbc;
    private static LoginAccountRepository accounts;
    private static UserMapper mapper;
    private static TransactionTemplate transactions;

    @BeforeAll
    static void startPrivateMySql() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = socket.getLocalPort();
        }
        System.out.println("LOGIN_ACCOUNT_MYSQL_PORT=" + port);

        String binary = System.getenv().getOrDefault("MYSQLD_SERVER_BINARY", "mysqld");
        Path data = directory.resolve("mysql-data");
        Process init = new ProcessBuilder(binary, "--no-defaults", "--initialize-insecure", "--datadir=" + data)
                .redirectErrorStream(true).redirectOutput(directory.resolve("mysql-init.log").toFile()).start();
        try {
            assertTrue(init.waitFor(30, TimeUnit.SECONDS), "private MySQL initializer timed out");
            assertEquals(0, init.exitValue(), "private MySQL initialization failed");
        } finally {
            stopProcess(init);
        }

        try {
            mysql = new ProcessBuilder(binary, "--no-defaults", "--datadir=" + data, "--bind-address=127.0.0.1",
                    "--port=" + port, "--mysqlx=OFF", "--skip-log-bin", "--socket=" + directory.resolve("mysql.sock"),
                    "--pid-file=" + directory.resolve("mysql.pid"), "--log-error=" + directory.resolve("mysql.log"))
                    .redirectErrorStream(true).redirectOutput(directory.resolve("mysql-console.log").toFile()).start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
            boolean ready = false;
            while (mysql.isAlive() && System.nanoTime() < deadline) {
                try (Connection connection = DriverManager.getConnection(url(port, ""), "root", "")) {
                    connection.createStatement().execute("CREATE DATABASE login_account_test CHARACTER SET utf8mb4");
                    ready = true;
                    break;
                } catch (SQLException unavailable) {
                    Thread.sleep(50);
                }
            }
            assertTrue(ready, "private MySQL did not start");

            DataSource source = new DriverManagerDataSource(url(port, "login_account_test"), "root", "");
            jdbc = new JdbcTemplate(source);
            jdbc.execute("CREATE TABLE tb_user (id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, phone VARCHAR(32), " +
                    "password VARCHAR(128), nick_name VARCHAR(64), icon VARCHAR(255) NULL, status VARCHAR(16) NULL, " +
                    "create_time DATETIME NULL, update_time DATETIME NULL, UNIQUE KEY uk_user_phone(phone)) " +
                    "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            jdbc.execute("CREATE TABLE tb_user_role (user_id BIGINT NOT NULL, role VARCHAR(32) NOT NULL, " +
                    "UNIQUE KEY uk_user_role(user_id, role)) ENGINE=InnoDB");

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
            mapper = sessions.getMapper(UserMapper.class);
            accounts = new MyBatisLoginAccountRepository(mapper);

            DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(source);
            transactions = new TransactionTemplate(transactionManager);
            transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        } catch (Exception | Error failure) {
            stopProcess(mysql);
            throw failure;
        }
    }

    private static String url(int port, String database) {
        return "jdbc:mysql://127.0.0.1:" + port + "/" + database
                + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
                + "&forceConnectionTimeZoneToSession=true&connectTimeout=1000&socketTimeout=2000";
    }

    private static void stopProcess(Process process) throws Exception {
        if (process == null) return;
        if (process.isAlive()) {
            process.destroy();
            if (!process.waitFor(8, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                assertTrue(process.waitFor(5, TimeUnit.SECONDS), "private process did not stop");
            }
        }
        assertFalse(process.isAlive(), "private process must stop");
    }

    @AfterAll
    static void stopPrivateMySql() throws Exception { stopProcess(mysql); }

    @BeforeEach
    void clearOwnedRows() {
        jdbc.execute("DROP TRIGGER IF EXISTS reject_default_role");
        jdbc.execute("DELETE FROM tb_user_role");
        jdbc.execute("DELETE FROM tb_user");
    }

    private List<Map<String, Object>> userRows() {
        return jdbc.queryForList("SELECT id, phone, nick_name, icon, status FROM tb_user ORDER BY id");
    }

    @Test
    void phoneLookupReturnsDisabledAccountAndNullableColumnsWithoutWritingOrCaching() {
        jdbc.update("INSERT INTO tb_user(id, phone, nick_name, icon, status) VALUES (?, ?, ?, NULL, NULL)",
                LARGE_ID, PHONE, "legacy-name");
        List<Map<String, Object>> before = userRows();

        LoginAccount found = accounts.findByPhone(PHONE);
        assertNotNull(found);
        assertEquals(LARGE_ID, found.getId());
        assertEquals("legacy-name", found.getNickName());
        // Preserve the old entity's empty icon initializer when MyBatis skips null setters.
        assertEquals(mapper.selectOne(new QueryWrapper<io.github.frewily.campushub.entity.User>()
                .eq("phone", PHONE)).getIcon(), found.getIcon());
        assertEquals("", found.getIcon());
        assertNull(found.getStatus());
        assertNull(accounts.findByPhone("13800009992"));
        assertEquals(before, userRows());

        jdbc.update("UPDATE tb_user SET nick_name = 'changed-in-db', status = 'DISABLED' WHERE phone = ?", PHONE);
        LoginAccount refreshed = accounts.findByPhone(PHONE);
        assertEquals("changed-in-db", refreshed.getNickName());
        assertEquals("DISABLED", refreshed.getStatus());
        assertEquals(1, userRows().size());
    }

    @Test
    void createWritesUserAndDefaultRoleInsideTheRealOuterTransaction() {
        LoginAccount created = transactions.execute(status -> {
            LoginAccount account = accounts.createWithPhone(PHONE);
            assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM tb_user", Integer.class) == 1);
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM tb_user_role", Integer.class));
            assertTrue(status.isNewTransaction());
            return account;
        });

        assertNotNull(created);
        assertNotNull(created.getId());
        assertEquals("user_".length() + 10, created.getNickName().length());
        assertTrue(created.getNickName().startsWith("user_"));
        assertEquals("", created.getIcon());
        assertEquals("ACTIVE", created.getStatus());
        assertEquals(created.getId(), jdbc.queryForObject("SELECT id FROM tb_user WHERE phone = ?", Long.class, PHONE));
        assertEquals("USER", jdbc.queryForObject("SELECT role FROM tb_user_role WHERE user_id = ?", String.class,
                created.getId()));
    }

    @Test
    void defaultRoleInsertFailureRollsBackTheEarlierUserInsert() {
        jdbc.execute("CREATE TRIGGER reject_default_role BEFORE INSERT ON tb_user_role FOR EACH ROW " +
                "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'synthetic role insert failure'");

        assertThrows(DataAccessException.class,
                () -> transactions.execute(status -> accounts.createWithPhone(PHONE)));

        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM tb_user", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM tb_user_role", Integer.class));
    }

    @Test
    void adapterHasNoTransactionAdviceAndLoginRetainsItsTransactionalBoundary() throws Exception {
        assertEquals(0, MyBatisLoginAccountRepository.class.getAnnotationsByType(Transactional.class).length);
        assertEquals(0, MyBatisLoginAccountRepository.class
                .getMethod("createWithPhone", String.class).getAnnotationsByType(Transactional.class).length);
        assertNotNull(UserServiceImpl.class.getMethod("login", LoginFormDTO.class).getAnnotation(Transactional.class));
    }
}
