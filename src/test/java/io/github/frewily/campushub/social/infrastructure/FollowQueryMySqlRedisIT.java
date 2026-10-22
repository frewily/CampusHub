package io.github.frewily.campushub.social.infrastructure;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.injector.DefaultSqlInjector;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import io.github.frewily.campushub.dto.UserDTO;
import io.github.frewily.campushub.mapper.FollowMapper;
import io.github.frewily.campushub.mapper.UserMapper;
import io.github.frewily.campushub.service.IUserService;
import io.github.frewily.campushub.service.impl.UserServiceImpl;
import io.github.frewily.campushub.social.application.FollowQueryService;
import io.github.frewily.campushub.social.application.FollowCommandService;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.data.redis.connection.RedisZSetCommands;
import org.springframework.data.redis.connection.DefaultTuple;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.mybatis.spring.SqlSessionTemplate;

import java.net.ServerSocket;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Integration coverage for the real MySQL, Redis, and user-service adapters. */
class FollowQueryMySqlRedisIT {
    @TempDir
    static Path directory;

    private static final Set<Long> OWNED_REDIS_IDS = new HashSet<>();
    private static Process mysql;
    private static Process redisProcess;
    private static LettuceConnectionFactory redisFactory;
    private static StringRedisTemplate redis;
    private static DriverManagerDataSource datasource;
    private static JdbcTemplate jdbc;
    private static MybatisSqlSessionFactoryBean sessionFactoryBean;
    private static SqlSessionFactory sessionFactory;
    private static FollowQueryService service;
    private static FollowCommandService commandService;
    private static MyBatisFollowCommandRepository commandRepository;
    private static final long COMMAND_CLOCK_MILLIS = Instant.parse("2026-10-21T12:34:56.789Z").toEpochMilli();

    @BeforeAll
    static void startPrivateComponents() throws Exception {
        int mysqlPort = freePort();
        int redisPort = freePort();
        System.out.println("FOLLOW_QUERY_MYSQL_PORT=" + mysqlPort);
        System.out.println("FOLLOW_QUERY_REDIS_PORT=" + redisPort);

        try {
            startMySql(mysqlPort);
            startRedis(redisPort);
            datasource = new DriverManagerDataSource(jdbcUrl(mysqlPort, "follow_query_test"), "root", "");
            jdbc = new JdbcTemplate(datasource);
            createSyntheticSchema();

            sessionFactoryBean = new MybatisSqlSessionFactoryBean();
            sessionFactoryBean.setDataSource(datasource);
            MybatisConfiguration configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true);
            configuration.addMapper(FollowMapper.class);
            configuration.addMapper(UserMapper.class);
            sessionFactoryBean.setConfiguration(configuration);
            GlobalConfig globalConfig = new GlobalConfig();
            globalConfig.setSqlInjector(new DefaultSqlInjector());
            sessionFactoryBean.setGlobalConfig(globalConfig);
            sessionFactory = sessionFactoryBean.getObject();
            SqlSessionTemplate sessions = new SqlSessionTemplate(sessionFactory);
            FollowMapper followMapper = sessions.getMapper(FollowMapper.class);
            commandRepository = new MyBatisFollowCommandRepository(followMapper);
            commandService = new FollowCommandService(commandRepository, new RedisFollowProjectionWriter(redis),
                    Clock.fixed(Instant.ofEpochMilli(COMMAND_CLOCK_MILLIS), ZoneOffset.UTC));
            UserMapper userMapper = sessions.getMapper(UserMapper.class);
            IUserService userService = new UserServiceImpl();
            ReflectionTestUtils.setField(userService, "baseMapper", userMapper);
            service = new FollowQueryService(
                    new MyBatisFollowLookupRepository(followMapper),
                    new RedisFollowProjectionReader(redis),
                    new LegacyFollowUserReader(userService));
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
                        "CREATE DATABASE follow_query_test CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci");
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
        jdbc.execute("CREATE TABLE tb_follow ("
                + "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, user_id BIGINT NOT NULL, "
                + "follow_user_id BIGINT NOT NULL, create_time DATETIME NULL, "
                + "UNIQUE KEY uk_user_follow(user_id,follow_user_id)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        jdbc.execute("CREATE TABLE tb_user ("
                + "id BIGINT NOT NULL PRIMARY KEY, phone VARCHAR(32), password VARCHAR(128), "
                + "nick_name VARCHAR(64), icon VARCHAR(255), status VARCHAR(16), "
                + "create_time DATETIME NULL, update_time DATETIME NULL"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
    }

    @AfterAll
    static void stop() throws Exception {
        stopOwnedResources();
    }

    private static void stopOwnedResources() throws Exception {
        Throwable firstFailure = null;
        // SqlSessionTemplate owns short-lived operation sessions; the datasource has no destroy API.
        sessionFactoryBean = null;
        sessionFactory = null;
        service = null;
        commandService = null;
        commandRepository = null;
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
        jdbc.execute("DELETE FROM tb_follow");
        jdbc.execute("DELETE FROM tb_user");
        for (Long id : OWNED_REDIS_IDS) redis.delete("follows:" + id);
        OWNED_REDIS_IDS.clear();
    }

    private void insertFollow(long actor, long target) {
        jdbc.update("INSERT INTO tb_follow(user_id,follow_user_id,create_time) VALUES(?,?,?)",
                actor, target, LocalDateTime.parse("2026-10-20T12:00:00"));
    }

    private void insertUser(long id, String nickName, String icon) {
        jdbc.update("INSERT INTO tb_user(id,phone,password,nick_name,icon,status,create_time,update_time) "
                        + "VALUES(?,?,?,?,?,?,?,?)",
                id, "phone-" + id, "private-password-" + id, nickName, icon, "ACTIVE",
                LocalDateTime.parse("2026-01-02T03:04:05"), LocalDateTime.parse("2026-02-03T04:05:06"));
    }

    private void setFollows(long owner, String... members) {
        OWNED_REDIS_IDS.add(owner);
        redis.delete("follows:" + owner);
        if (members.length == 0) return;
        Set<RedisZSetCommands.Tuple> tuples = new HashSet<>();
        for (int i = 0; i < members.length; i++) {
            tuples.add(new DefaultTuple(members[i].getBytes(java.nio.charset.StandardCharsets.UTF_8), i + 1.0));
        }
        redis.execute((RedisCallback<Long>) connection -> connection.zAdd(
                ("follows:" + owner).getBytes(java.nio.charset.StandardCharsets.UTF_8), tuples));
    }

    private Map<String, Double> zset(String key) {
        Set<org.springframework.data.redis.core.ZSetOperations.TypedTuple<String>> entries =
                redis.opsForZSet().rangeWithScores(key, 0, -1);
        Map<String, Double> snapshot = new LinkedHashMap<>();
        if (entries != null) for (org.springframework.data.redis.core.ZSetOperations.TypedTuple<String> entry : entries) {
            snapshot.put(entry.getValue(), entry.getScore());
        }
        return snapshot;
    }

    private List<Map<String, Object>> followRows() {
        return jdbc.queryForList("SELECT id,user_id,follow_user_id,create_time FROM tb_follow ORDER BY id");
    }

    @Test
    void isFollowUsesMySqlTruthWhenRedisProjectionDisagreesAndDoesNotRepairIt() {
        long actor = 101L;
        long target = 202L;
        insertFollow(actor, target);
        setFollows(actor, "999");
        Map<String, Double> before = zset("follows:" + actor);
        List<Map<String, Object>> rows = followRows();

        assertTrue(service.isFollow(actor, target));
        assertEquals(rows, followRows());
        assertEquals(before, zset("follows:" + actor));
        assertFalse(service.isFollow(actor, 303L));
    }

    @Test
    void commonUsesRedisIntersectionConvertsLargeIdsAndExposesOnlyUserDtoFields() {
        long shared = 8_765_432_109_876L;
        insertUser(shared, "共同好友", "avatar.svg");
        insertFollow(1L, 77L); // SQL relationship data is deliberately unrelated to the Redis result.
        setFollows(11L, Long.toString(shared), "9001");
        setFollows(22L, "9001", Long.toString(shared));
        List<Map<String, Object>> followRows = followRows();
        Map<String, Double> first = zset("follows:11");
        Map<String, Double> second = zset("follows:22");

        List<UserDTO> result = service.common(11L, 22L);

        assertEquals(1, result.size());
        UserDTO dto = result.get(0);
        assertEquals(shared, dto.getId());
        assertEquals("共同好友", dto.getNickName());
        assertEquals("avatar.svg", dto.getIcon());
        assertEquals(new HashSet<>(Arrays.asList("id", "nickName", "icon")),
                cn.hutool.core.bean.BeanUtil.beanToMap(dto).keySet());
        assertEquals(followRows, followRows());
        assertEquals(first, zset("follows:11"));
        assertEquals(second, zset("follows:22"));
    }

    @Test
    void emptyAndDisjointRedisSetsReturnWithoutQueryingUserTable() {
        setFollows(31L);
        setFollows(32L, "4001");
        jdbc.execute("RENAME TABLE tb_user TO tb_user_hidden");
        try {
            assertTrue(service.common(31L, 32L).isEmpty());
            setFollows(31L, "4002");
            assertTrue(service.common(31L, 32L).isEmpty());
        } finally {
            jdbc.execute("RENAME TABLE tb_user_hidden TO tb_user");
        }
    }

    @Test
    void commonDoesNotInferRedisIntersectionFromMySqlFollowRows() {
        insertFollow(41L, 700L);
        insertFollow(42L, 700L);
        insertUser(700L, "db-only", "db.svg");
        setFollows(41L, "800");
        setFollows(42L, "801");
        assertTrue(service.common(41L, 42L).isEmpty());

        setFollows(41L, "900");
        setFollows(42L, "900");
        assertTrue(service.common(41L, 42L).isEmpty(), "a Redis overlap for a missing SQL user is omitted naturally");
    }

    @Test
    void commonOmitsMembersWhoseUsersDoNotExist() {
        insertUser(501L, "present", "present.svg");
        setFollows(51L, "501", "502");
        setFollows(52L, "502", "501");

        List<UserDTO> result = service.common(51L, 52L);

        assertEquals(1, result.size());
        assertEquals(501L, result.get(0).getId());
        assertEquals("present", result.get(0).getNickName());
    }

    @Test
    void commonMakesNoOrderingPromiseForTheIntersection() {
        insertUser(601L, "one", "one.svg");
        insertUser(602L, "two", "two.svg");
        insertUser(603L, "three", "three.svg");
        setFollows(61L, "603", "601", "602");
        setFollows(62L, "602", "603", "601");

        List<UserDTO> result = service.common(61L, 62L);

        assertEquals(3, result.size());
        assertEquals(new HashSet<>(Arrays.asList(601L, 602L, 603L)),
                new HashSet<>(Arrays.asList(result.get(0).getId(), result.get(1).getId(), result.get(2).getId())));
    }

    @Test
    void malformedRedisMemberWrongTypeAndUnavailableSqlPropagateWithoutWrites() {
        insertUser(701L, "safe", "safe.svg");
        insertFollow(71L, 701L);
        setFollows(71L, "not-a-number");
        setFollows(72L, "not-a-number");
        List<Map<String, Object>> rows = followRows();
        Map<String, Double> malformedA = zset("follows:71");
        Map<String, Double> malformedB = zset("follows:72");
        assertThrows(RuntimeException.class, () -> service.common(71L, 72L));
        assertEquals(rows, followRows());
        assertEquals(malformedA, zset("follows:71"));
        assertEquals(malformedB, zset("follows:72"));

        OWNED_REDIS_IDS.add(73L);
        redis.opsForValue().set("follows:73", "wrong-type");
        setFollows(74L, "701");
        String wrongType = redis.opsForValue().get("follows:73");
        Map<String, Double> rightSide = zset("follows:74");
        assertThrows(RuntimeException.class, () -> service.common(73L, 74L));
        assertEquals(wrongType, redis.opsForValue().get("follows:73"));
        assertEquals(rightSide, zset("follows:74"));
        assertEquals(rows, followRows());

        Map<String, Double> followProjection = zset("follows:71");
        jdbc.execute("RENAME TABLE tb_follow TO tb_follow_hidden");
        try {
            assertThrows(RuntimeException.class, () -> service.isFollow(71L, 701L));
            assertEquals(followProjection, zset("follows:71"));
        } finally {
            jdbc.execute("RENAME TABLE tb_follow_hidden TO tb_follow");
        }
        assertEquals(rows, followRows());
    }

    @Test
    void queryPreservesExistingZsetMembersScoresAndExpiration() throws Exception {
        insertUser(801L, "ttl", "ttl.svg");
        setFollows(81L, "801", "802");
        setFollows(82L, "801", "803");
        redis.expire("follows:81", 1200, TimeUnit.SECONDS);
        redis.expire("follows:82", 1300, TimeUnit.SECONDS);
        Map<String, Double> firstBefore = zset("follows:81");
        Map<String, Double> secondBefore = zset("follows:82");
        Long firstTtlBefore = redis.getExpire("follows:81", TimeUnit.SECONDS);
        Long secondTtlBefore = redis.getExpire("follows:82", TimeUnit.SECONDS);

        assertEquals(1, service.common(81L, 82L).size());

        assertEquals(firstBefore, zset("follows:81"));
        assertEquals(secondBefore, zset("follows:82"));
        Long firstTtlAfter = redis.getExpire("follows:81", TimeUnit.SECONDS);
        Long secondTtlAfter = redis.getExpire("follows:82", TimeUnit.SECONDS);
        assertNotNull(firstTtlAfter);
        assertNotNull(secondTtlAfter);
        assertTrue(firstTtlAfter <= firstTtlBefore && firstTtlAfter >= firstTtlBefore - 2);
        assertTrue(secondTtlAfter <= secondTtlBefore && secondTtlAfter >= secondTtlBefore - 2);
    }

    @Test
    void queryWorksAgainAfterUnavailableTableIsRestored() {
        insertFollow(91L, 901L);
        jdbc.execute("RENAME TABLE tb_follow TO tb_follow_hidden");
        try {
            assertThrows(RuntimeException.class, () -> service.isFollow(91L, 901L));
        } finally {
            jdbc.execute("RENAME TABLE tb_follow_hidden TO tb_follow");
        }
        assertTrue(service.isFollow(91L, 901L));
    }

    @Test
    void mysqlUniqueRelationshipRecoversDuplicateCreateAndProjectsSuccessfulFollow() {
        long actor = 1001L;
        long target = 2002L;
        OWNED_REDIS_IDS.add(actor);

        commandService.follow(actor, target, true);
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM tb_follow WHERE user_id=? AND follow_user_id=?", Integer.class, actor, target));
        assertTrue(commandRepository.create(actor, target), "duplicate unique-key insert is recovered as success");
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM tb_follow WHERE user_id=? AND follow_user_id=?", Integer.class, actor, target));
        assertEquals(Double.valueOf((double) COMMAND_CLOCK_MILLIS), zset("follows:" + actor).get(Long.toString(target)));
    }

    @Test
    void successfulUnfollowDeletesMysqlRelationshipAndRedisProjection() {
        long actor = 1101L;
        long target = 2202L;
        insertFollow(actor, target);
        setFollows(actor, Long.toString(target));

        commandService.follow(actor, target, false);

        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM tb_follow WHERE user_id=? AND follow_user_id=?", Integer.class, actor, target));
        assertTrue(zset("follows:" + actor).isEmpty());
    }

    @Test
    void redisWrongTypeFailureLeavesCommittedMysqlFollowRelationship() {
        long actor = 1201L;
        long target = 2302L;
        OWNED_REDIS_IDS.add(actor);
        redis.opsForValue().set("follows:" + actor, "synthetic-wrong-type");

        assertThrows(RuntimeException.class, () -> commandService.follow(actor, target, true));

        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM tb_follow WHERE user_id=? AND follow_user_id=?", Integer.class, actor, target));
        assertEquals("synthetic-wrong-type", redis.opsForValue().get("follows:" + actor));
    }

    @Test
    void repeatedUnfollowDoesNotRepairAStaleRedisProjection() {
        long actor = 1301L;
        long target = 2402L;
        setFollows(actor, Long.toString(target));
        Map<String, Double> projectionBefore = zset("follows:" + actor);

        commandService.follow(actor, target, false);

        assertTrue(followRows().isEmpty());
        assertEquals(projectionBefore, zset("follows:" + actor));
    }

    @Test
    void followProjectionRefreshesScoreWithoutChangingExistingZsetTtl() {
        long actor = 1401L;
        long target = 2502L;
        insertFollow(actor, target);
        setFollows(actor, Long.toString(target));
        redis.expire("follows:" + actor, 1200, TimeUnit.SECONDS);
        Long ttlBefore = redis.getExpire("follows:" + actor, TimeUnit.SECONDS);

        commandService.follow(actor, target, true);

        assertEquals(Double.valueOf((double) COMMAND_CLOCK_MILLIS), zset("follows:" + actor).get(Long.toString(target)));
        Long ttlAfter = redis.getExpire("follows:" + actor, TimeUnit.SECONDS);
        assertNotNull(ttlBefore);
        assertNotNull(ttlAfter);
        assertTrue(ttlAfter <= ttlBefore && ttlAfter >= ttlBefore - 2,
                "ZSET add refreshes the score but preserves the existing expiration");
    }
}
