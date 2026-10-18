package io.github.frewily.campushub.merchant.infrastructure;

import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.injector.DefaultSqlInjector;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import io.github.frewily.campushub.entity.ShopType;
import io.github.frewily.campushub.mapper.ShopTypeMapper;
import io.github.frewily.campushub.merchant.application.ShopTypeQueryService;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionTemplate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.net.ServerSocket;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Component acceptance over an owned MySQL schema and an owned, non-persistent Redis. */
class ShopTypeCatalogMySqlRedisIT {
    private static final String CACHE_KEY = "cache:shop:type";

    @TempDir
    static Path directory;

    private static Process mysql;
    private static Process redisProcess;
    private static LettuceConnectionFactory redisFactory;
    private static StringRedisTemplate redis;
    private static DriverManagerDataSource datasource;
    private static JdbcTemplate jdbc;
    private static MybatisSqlSessionFactoryBean sessionFactoryBean;
    private static SqlSessionFactory sessionFactory;
    private static ShopTypeMapper mapper;
    private static ShopTypeQueryService service;

    @BeforeAll
    static void startPrivateComponents() throws Exception {
        int mysqlPort = freePort();
        int redisPort = freePort();
        System.out.println("SHOP_TYPE_MYSQL_PORT=" + mysqlPort);
        System.out.println("SHOP_TYPE_REDIS_PORT=" + redisPort);

        try {
            startMySql(mysqlPort);
            startRedis(redisPort);
            datasource = new DriverManagerDataSource(jdbcUrl(mysqlPort, "shop_type_test"), "root", "");
            jdbc = new JdbcTemplate(datasource);
            createSyntheticSchema();

            sessionFactoryBean = new MybatisSqlSessionFactoryBean();
            sessionFactoryBean.setDataSource(datasource);
            MybatisConfiguration configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true);
            configuration.addMapper(ShopTypeMapper.class);
            sessionFactoryBean.setConfiguration(configuration);
            GlobalConfig globalConfig = new GlobalConfig();
            globalConfig.setSqlInjector(new DefaultSqlInjector());
            sessionFactoryBean.setGlobalConfig(globalConfig);
            sessionFactory = sessionFactoryBean.getObject();
            // Match production's Spring-managed per-operation sessions, not a cross-test first-level cache.
            mapper = new SqlSessionTemplate(sessionFactory).getMapper(ShopTypeMapper.class);
            service = new ShopTypeQueryService(new MyBatisShopTypeRepository(mapper), new RedisShopTypeCache(redis));
        } catch (Exception | Error failure) {
            stopOwnedResources();
            throw failure;
        }
    }

    private static void startMySql(int port) throws Exception {
        String binary = System.getenv().getOrDefault("MYSQLD_SERVER_BINARY", "mysqld");
        Path data = directory.resolve("mysql-data");
        Process init = new ProcessBuilder(binary, "--no-defaults", "--initialize-insecure", "--datadir=" + data)
                .redirectErrorStream(true).redirectOutput(directory.resolve("mysql-init.log").toFile()).start();
        try {
            if (!init.waitFor(30, TimeUnit.SECONDS)) {
                init.destroyForcibly();
                assertTrue(init.waitFor(5, TimeUnit.SECONDS), "private MySQL initializer must stop");
                fail("private MySQL initialization timed out");
            }
            assertEquals(0, init.exitValue(), "private MySQL initialization failed; inspect mysql-init.log");
        } finally {
            stopProcess(init, "private MySQL initializer");
        }

        mysql = new ProcessBuilder(binary, "--no-defaults", "--datadir=" + data, "--bind-address=127.0.0.1",
                "--port=" + port, "--mysqlx=OFF", "--skip-log-bin", "--socket=" + directory.resolve("mysql.sock"),
                "--pid-file=" + directory.resolve("mysql.pid"), "--log-error=" + directory.resolve("mysql.log"))
                .redirectErrorStream(true).redirectOutput(directory.resolve("mysql-console.log").toFile()).start();
        String serverUrl = jdbcUrl(port, "");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
        boolean ready = false;
        while (System.nanoTime() < deadline && mysql.isAlive()) {
            try (Connection connection = DriverManager.getConnection(serverUrl, "root", "")) {
                connection.createStatement().execute(
                        "CREATE DATABASE shop_type_test CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci");
                ready = true;
                break;
            } catch (SQLException unavailable) {
                Thread.sleep(50);
            }
        }
        assertTrue(ready, "private MySQL did not start");
    }

    private static String jdbcUrl(int port, String database) {
        return "jdbc:mysql://127.0.0.1:" + port + "/" + database
                + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&forceConnectionTimeZoneToSession=true";
    }

    private static void startRedis(int port) throws Exception {
        redisProcess = new ProcessBuilder(System.getenv().getOrDefault("REDIS_SERVER_BINARY", "redis-server"),
                "--bind", "127.0.0.1", "--port", Integer.toString(port), "--save", "", "--appendonly", "no",
                "--dir", directory.toString()).redirectErrorStream(true)
                .redirectOutput(directory.resolve("redis.log").toFile()).start();
        redisFactory = new LettuceConnectionFactory("127.0.0.1", port);
        redisFactory.afterPropertiesSet();
        redis = new StringRedisTemplate(redisFactory);
        redis.afterPropertiesSet();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline && redisProcess.isAlive()) {
            try {
                if ("PONG".equals(redis.execute((RedisCallback<String>) connection -> connection.ping()))) return;
            } catch (RuntimeException unavailable) {
                Thread.sleep(25);
            }
        }
        throw new IllegalStateException("private Redis did not start");
    }

    private static void createSyntheticSchema() {
        jdbc.execute("CREATE TABLE tb_shop_type ("
                + "id BIGINT NOT NULL PRIMARY KEY, name VARCHAR(128), icon VARCHAR(512), sort INT NULL, "
                + "create_time DATETIME NULL, update_time DATETIME NULL"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
    }

    @AfterAll
    static void stop() throws Exception {
        stopOwnedResources();
    }

    private static void stopOwnedResources() throws Exception {
        Throwable firstFailure = null;
        // SqlSessionTemplate closes operation sessions; this unpooled datasource/factory has no destroy API.
        sessionFactoryBean = null;
        sessionFactory = null;
        if (redisFactory != null) {
            try { redisFactory.destroy(); }
            catch (Exception | Error failure) { firstFailure = failure; }
            redisFactory = null;
            redis = null;
        }
        try { stopProcess(redisProcess, "private Redis"); }
        catch (Exception | Error failure) { if (firstFailure == null) firstFailure = failure; }
        finally { redisProcess = null; }
        try { stopProcess(mysql, "private MySQL"); }
        catch (Exception | Error failure) { if (firstFailure == null) firstFailure = failure; }
        finally { mysql = null; }
        if (firstFailure instanceof Error) throw (Error) firstFailure;
        if (firstFailure instanceof Exception) throw (Exception) firstFailure;
    }

    private static void stopProcess(Process process, String label) throws Exception {
        if (process == null) return;
        if (process.isAlive()) {
            process.destroy();
            if (!process.waitFor(8, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                assertTrue(process.waitFor(5, TimeUnit.SECONDS), label + " must stop after forceful termination");
            }
        }
        assertFalse(process.isAlive(), label + " must not remain running");
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) { return socket.getLocalPort(); }
    }

    @BeforeEach
    void clearOwnedFixture() {
        jdbc.execute("DELETE FROM tb_shop_type");
        redis.delete(CACHE_KEY);
    }

    private void insert(long id, String name, String icon, Integer sort, String created, String updated) {
        jdbc.update("INSERT INTO tb_shop_type(id,name,icon,sort,create_time,update_time) VALUES(?,?,?,?,?,?)",
                id, name, icon, sort, LocalDateTime.parse(created), LocalDateTime.parse(updated));
    }

    @Test
    void coldMissMapsRealColumnsAndOrdersAscendingWithNullSortFirst() {
        insert(1, "bakery", "bread.svg", 4, "2026-01-02T03:04:05", "2026-01-03T04:05:06");
        insert(2, "tea", "tea.svg", 1, "2026-02-03T04:05:06", "2026-02-04T05:06:07");
        insert(3, "coffee", "coffee.svg", 1, "2026-03-04T05:06:07", "2026-03-05T06:07:08");
        insert(4, "other", "other.svg", null, "2026-04-05T06:07:08", "2026-04-06T07:08:09");

        List<ShopType> result = service.queryList();

        assertEquals(4, result.size());
        assertEquals(4L, result.get(0).getId()); // MySQL ascending order puts NULL first.
        assertEquals(1L, result.get(3).getId());
        assertEquals(new HashSet<>(Arrays.asList(2L, 3L)), new HashSet<>(Arrays.asList(
                result.get(1).getId(), result.get(2).getId())), "equal sort values have no promised tie order");
        ShopType mapped = result.get(2).getId().equals(2L) ? result.get(2) : result.get(1);
        assertEquals("tea", mapped.getName());
        assertEquals("tea.svg", mapped.getIcon());
        assertEquals(Integer.valueOf(1), mapped.getSort());
        assertEquals(LocalDateTime.parse("2026-02-03T04:05:06"), mapped.getCreateTime());
        assertEquals(LocalDateTime.parse("2026-02-04T05:06:07"), mapped.getUpdateTime());
        assertNotNull(redis.opsForValue().get(CACHE_KEY));
    }

    @Test
    void validLegacyJsonCacheHitSucceedsWhileTheSqlTableIsUnavailable() {
        String legacyJson = "[{\"id\":71,\"name\":\"legacy\",\"icon\":\"old.svg\",\"sort\":8}]";
        redis.opsForValue().set(CACHE_KEY, legacyJson);
        jdbc.execute("RENAME TABLE tb_shop_type TO tb_shop_type_hidden");
        try {
            List<ShopType> result = service.queryList();
            assertEquals(1, result.size());
            assertEquals(71L, result.get(0).getId());
            assertEquals("legacy", result.get(0).getName());
            assertEquals(legacyJson, redis.opsForValue().get(CACHE_KEY));
        } finally {
            jdbc.execute("RENAME TABLE tb_shop_type_hidden TO tb_shop_type");
        }
    }

    @Test
    void blankCacheValuesFallBackToRealSqlAndAreReplaced() {
        insert(9, "fallback", "fallback.svg", 2, "2026-05-06T07:08:09", "2026-05-07T08:09:10");
        for (String blank : Arrays.asList("", "   ")) {
            redis.opsForValue().set(CACHE_KEY, blank);
            List<ShopType> result = service.queryList();
            assertEquals(1, result.size());
            assertEquals(9L, result.get(0).getId());
            assertTrue(redis.opsForValue().get(CACHE_KEY).contains("fallback"));
        }
    }

    @Test
    void serializedEmptyListIsAHitAndDoesNotQuerySql() {
        redis.opsForValue().set(CACHE_KEY, "[]");
        jdbc.execute("RENAME TABLE tb_shop_type TO tb_shop_type_hidden");
        try {
            assertTrue(service.queryList().isEmpty());
            assertEquals("[]", redis.opsForValue().get(CACHE_KEY));
        } finally {
            jdbc.execute("RENAME TABLE tb_shop_type_hidden TO tb_shop_type");
        }
    }

    @Test
    void emptyDatabaseResultIsCachedAsAnEmptyList() {
        assertTrue(service.queryList().isEmpty());
        assertEquals("[]", redis.opsForValue().get(CACHE_KEY));
    }

    @Test
    void malformedCachedJsonPropagatesAndIsNotReplacedBySqlFallback() {
        insert(10, "must-not-be-read", "x.svg", 1, "2026-06-07T08:09:10", "2026-06-08T09:10:11");
        String malformed = "{not-json";
        redis.opsForValue().set(CACHE_KEY, malformed);
        assertThrows(RuntimeException.class, service::queryList);
        assertEquals(malformed, redis.opsForValue().get(CACHE_KEY));
    }

    @Test
    void realCacheWriteUsesThirtyMinuteTtlAndCacheHitDoesNotRefreshIt() throws Exception {
        insert(11, "ttl", "ttl.svg", 3, "2026-07-08T09:10:11", "2026-07-09T10:11:12");
        service.queryList();
        Long writtenTtl = redis.getExpire(CACHE_KEY, TimeUnit.SECONDS);
        assertNotNull(writtenTtl);
        assertTrue(writtenTtl >= 1790 && writtenTtl <= 1800, "expected the configured 30 minute TTL");

        redis.expire(CACHE_KEY, 1200, TimeUnit.SECONDS);
        Long beforeHit = redis.getExpire(CACHE_KEY, TimeUnit.SECONDS);
        assertEquals(1, service.queryList().size());
        Long afterHit = redis.getExpire(CACHE_KEY, TimeUnit.SECONDS);
        assertNotNull(afterHit);
        assertTrue(afterHit <= beforeHit && afterHit >= beforeHit - 2,
                "a cache hit must leave the existing expiration counting down");
    }

    @Test
    void realSqlFailurePropagatesWithoutWritingSuccessfulCache() {
        jdbc.execute("RENAME TABLE tb_shop_type TO tb_shop_type_hidden");
        try {
            assertThrows(RuntimeException.class, service::queryList);
            assertNull(redis.opsForValue().get(CACHE_KEY));
        } finally {
            jdbc.execute("RENAME TABLE tb_shop_type_hidden TO tb_shop_type");
        }
    }
}
