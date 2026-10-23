package io.github.frewily.campushub.identity.infrastructure;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.injector.DefaultSqlInjector;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.frewily.campushub.dto.UserDTO;
import io.github.frewily.campushub.dto.response.UserInfoResponse;
import io.github.frewily.campushub.identity.application.UserProfileQueryService;
import io.github.frewily.campushub.mapper.UserMapper;
import io.github.frewily.campushub.mapper.UserInfoMapper;
import org.junit.jupiter.api.*;
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
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Real independent profile lookups over a private temporary localhost MySQL; never uses shared data. */
class UserProfileMySqlIT {
    @TempDir static Path directory;
    private static Process mysql;
    private static JdbcTemplate jdbc;
    private static UserProfileQueryService service;
    private static final long ID = 9007199254744993L;

    @BeforeAll
    static void start() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) { port = socket.getLocalPort(); }
        System.out.println("USER_PROFILE_MYSQL_PORT=" + port);
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
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25); boolean ready = false;
            while (mysql.isAlive() && System.nanoTime() < deadline) {
                try (Connection c = DriverManager.getConnection(url(port, ""), "root", "")) {
                    c.createStatement().execute("CREATE DATABASE user_profile_test CHARACTER SET utf8mb4");
                    ready = true; break;
                } catch (SQLException unavailable) { Thread.sleep(50); }
            }
            assertTrue(ready, "private MySQL did not start");
            DriverManagerDataSource source = new DriverManagerDataSource(url(port, "user_profile_test"), "root", "");
            jdbc = new JdbcTemplate(source);
            jdbc.execute("CREATE TABLE tb_user(id BIGINT PRIMARY KEY,phone VARCHAR(32),password VARCHAR(128),"
                    + "nick_name VARCHAR(64),icon VARCHAR(255),status VARCHAR(16),create_time DATETIME,update_time DATETIME)");
            jdbc.execute("CREATE TABLE tb_user_info(user_id BIGINT PRIMARY KEY,city VARCHAR(64),introduce VARCHAR(128),"
                    + "fans INT,followee INT,gender TINYINT,birthday DATE,credits INT,level TINYINT,create_time DATETIME,update_time DATETIME)");
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean(); factory.setDataSource(source);
            MybatisConfiguration config = new MybatisConfiguration(); config.setMapUnderscoreToCamelCase(true);
            config.addMapper(UserMapper.class); config.addMapper(UserInfoMapper.class); factory.setConfiguration(config);
            GlobalConfig global = new GlobalConfig(); global.setSqlInjector(new DefaultSqlInjector()); factory.setGlobalConfig(global);
            SqlSessionTemplate sessions = new SqlSessionTemplate(factory.getObject());
            service = new UserProfileQueryService(new MyBatisUserProfileReader(
                    sessions.getMapper(UserMapper.class), sessions.getMapper(UserInfoMapper.class)));
        } catch (Exception | Error failure) { stopProcess(mysql); throw failure; }
    }

    private static String url(int port, String database) {
        return "jdbc:mysql://127.0.0.1:" + port + "/" + database + "?useSSL=false&allowPublicKeyRetrieval=true"
                + "&serverTimezone=UTC&forceConnectionTimeZoneToSession=true&connectTimeout=1000&socketTimeout=2000";
    }
    private static void stopProcess(Process process) throws Exception {
        if (process == null) return;
        if (process.isAlive()) {
            process.destroy();
            if (!process.waitFor(8, TimeUnit.SECONDS)) { process.destroyForcibly(); assertTrue(process.waitFor(5, TimeUnit.SECONDS)); }
        }
        assertFalse(process.isAlive(), "private process must stop");
    }
    @AfterAll static void stop() throws Exception { stopProcess(mysql); }
    @BeforeEach void reset() { jdbc.execute("DELETE FROM tb_user_info"); jdbc.execute("DELETE FROM tb_user"); }
    private void user() {
        jdbc.update("INSERT INTO tb_user VALUES(?, 'synthetic-phone','synthetic-password','retained','icon.svg','DISABLED',"
                + "'2020-01-02 03:04:05','2020-02-03 04:05:06')", ID);
    }
    private void info() {
        jdbc.update("INSERT INTO tb_user_info VALUES(?,'Xuancheng','Hello',3,2,0,'2001-02-03',10,1,"
                + "'2020-01-02 03:04:05','2020-02-03 04:05:06')", ID);
    }
    private List<Map<String,Object>> users() { return jdbc.queryForList("SELECT * FROM tb_user ORDER BY id"); }
    private List<Map<String,Object>> infos() { return jdbc.queryForList("SELECT * FROM tb_user_info ORDER BY user_id"); }

    @Test
    void retainsDisabledTargetsAndMapsOnlyLegacyResponseFieldsWithoutMutatingFacts() {
        user(); info(); List<Map<String,Object>> beforeUsers = users(), beforeInfos = infos();
        UserDTO dto = service.queryUserById(ID); assertEquals(ID, dto.getId()); assertEquals("retained", dto.getNickName());
        Set<String> fields = new HashSet<>(); new ObjectMapper().valueToTree(dto).fieldNames().forEachRemaining(fields::add);
        assertEquals(new HashSet<>(Arrays.asList("id","nickName","icon")), fields);
        UserInfoResponse details = service.info(ID); assertEquals(ID, details.getUserId());
        assertEquals("Xuancheng", details.getCity()); assertEquals("Hello", details.getIntroduce());
        assertEquals(3, details.getFans()); assertEquals(2, details.getFollowee());
        assertEquals(Boolean.FALSE, details.getGender()); assertEquals(Boolean.TRUE, details.getLevel());
        assertEquals(LocalDate.parse("2001-02-03"), details.getBirthday()); assertEquals(10, details.getCredits());
        assertEquals(beforeUsers, users()); assertEquals(beforeInfos, infos());
    }

    @Test
    void independentLookupsPreserveMissingRowsAndOrphanInfoWithoutJoiningOtherTable() {
        assertNull(service.queryUserById(ID)); assertNull(service.info(ID)); info();
        assertNull(service.queryUserById(ID)); assertNotNull(service.info(ID));
        jdbc.execute("RENAME TABLE tb_user TO phase7x_owned_user_hidden");
        try { assertNotNull(service.info(ID)); }
        finally { jdbc.execute("RENAME TABLE phase7x_owned_user_hidden TO tb_user"); }
        user(); jdbc.execute("RENAME TABLE tb_user_info TO phase7x_owned_info_hidden");
        try { assertNotNull(service.queryUserById(ID)); }
        finally { jdbc.execute("RENAME TABLE phase7x_owned_info_hidden TO tb_user_info"); }
        jdbc.execute("DELETE FROM tb_user_info"); assertNull(service.info(ID)); assertNotNull(service.queryUserById(ID));
    }

    @Test
    void sqlFailuresPropagateRatherThanMissingSuccessAndRecoverWithoutWrites() {
        user(); info(); List<Map<String,Object>> beforeUsers = users(), beforeInfos = infos();
        jdbc.execute("RENAME TABLE tb_user TO phase7x_owned_user_fault");
        try { assertThrows(DataAccessException.class, () -> service.queryUserById(ID)); }
        finally { jdbc.execute("RENAME TABLE phase7x_owned_user_fault TO tb_user"); }
        jdbc.execute("RENAME TABLE tb_user_info TO phase7x_owned_info_fault");
        try { assertThrows(DataAccessException.class, () -> service.info(ID)); }
        finally { jdbc.execute("RENAME TABLE phase7x_owned_info_fault TO tb_user_info"); }
        assertNotNull(service.queryUserById(ID)); assertNotNull(service.info(ID));
        assertEquals(beforeUsers, users()); assertEquals(beforeInfos, infos());
    }

    @Test
    void eachLookupReadsCurrentDatabaseWithoutCachingOrChangingIt() {
        user(); info(); assertEquals("retained", service.queryUserById(ID).getNickName());
        jdbc.execute("UPDATE tb_user SET nick_name='new-name'"); jdbc.execute("UPDATE tb_user_info SET city='new-city'");
        List<Map<String,Object>> beforeUsers = users(), beforeInfos = infos();
        assertEquals("new-name", service.queryUserById(ID).getNickName()); assertEquals("new-city", service.info(ID).getCity());
        assertEquals(beforeUsers, users()); assertEquals(beforeInfos, infos());
    }
}
