package io.github.frewily.campushub.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.frewily.campushub.dto.*;
import io.github.frewily.campushub.entity.*;
import io.github.frewily.campushub.exception.*;
import io.github.frewily.campushub.mapper.*;
import io.github.frewily.campushub.security.ResourceAuthorizationService;
import io.github.frewily.campushub.service.impl.*;
import io.github.frewily.campushub.utils.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mybatis.spring.SqlSessionTemplate;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.*;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.ServerSocket;
import java.nio.file.Path;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit IT: creates its own MySQL datadir/Redis process, never reads developer DB credentials/config. */
class OrderLifecycleMySqlRedisIT {
    @TempDir static Path directory;
    private static Process mysqlProcess, redisProcess;
    private static LettuceConnectionFactory redisFactory;
    private static RedissonClient redisson;
    private static StringRedisTemplate redis;
    private static DriverManagerDataSource datasource;
    private static JdbcTemplate jdbc;
    private static VoucherOrderMapper orders;
    private static VoucherMapper vouchers;
    private static OrderCancellationMapper cancellations;
    private static AccountAccessMapper accounts;
    private static ResourceAuthorizationService authorization;
    private static TransactionTemplate tx;
    private static VoucherOrderServiceImpl orderService;
    private static FlashSaleAdmissionService admission;
    private static OrderLifecycleService lifecycle;
    private OrderStreamQueue queue;
    private OrderStreamConsumer consumer;

    @BeforeAll
    static void startIsolatedServices() throws Exception {
        String mysqlBinary = System.getenv().getOrDefault("MYSQLD_SERVER_BINARY", "mysqld");
        Path data = directory.resolve("mysql-data");
        Process init = new ProcessBuilder(mysqlBinary, "--no-defaults", "--initialize-insecure", "--datadir=" + data)
                .redirectErrorStream(true).redirectOutput(directory.resolve("mysql-init.log").toFile()).start();
        if (!init.waitFor(30, TimeUnit.SECONDS)) { init.destroyForcibly(); fail("isolated MySQL init timed out"); }
        assertEquals(0, init.exitValue(), "isolated MySQL init failed; inspect test-owned mysql-init.log");
        int mysqlPort = freePort(), redisPort = freePort();
        mysqlProcess = new ProcessBuilder(mysqlBinary, "--no-defaults", "--datadir=" + data,
                "--bind-address=127.0.0.1", "--port=" + mysqlPort, "--mysqlx=OFF", "--skip-log-bin",
                "--socket=" + directory.resolve("mysql.sock"), "--pid-file=" + directory.resolve("mysql.pid"),
                "--log-error=" + directory.resolve("mysql.log"))
                .redirectErrorStream(true).redirectOutput(directory.resolve("mysql-console.log").toFile()).start();
        String baseUrl = "jdbc:mysql://127.0.0.1:" + mysqlPort +
                "/?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&forceConnectionTimeZoneToSession=true";
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
        boolean ready = false;
        while (System.nanoTime() < deadline && mysqlProcess.isAlive()) {
            try (Connection connection = DriverManager.getConnection(baseUrl, "root", "")) {
                connection.createStatement().execute("SET GLOBAL time_zone='+00:00'");
                connection.createStatement().execute("CREATE DATABASE campushub_test");
                ready = true; break;
            } catch (SQLException unavailable) { Thread.sleep(50); }
        }
        assertTrue(ready, "isolated MySQL startup failed; inspect test-owned mysql.log");
        datasource = new DriverManagerDataSource(baseUrl.replace("/?", "/campushub_test?"), "root", "");
        jdbc = new JdbcTemplate(datasource);
        try (Connection connection = datasource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/order-lifecycle-test-schema.sql"));
            for (int pass = 0; pass < 2; pass++) {
                for (String migration : Arrays.asList("V001__add_business_unique_constraints.sql",
                        "V002__add_identity_and_merchant_authorization.sql", "V003__add_order_cancellation_outbox.sql")) {
                    ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/" + migration));
                }
            }
        }
        redisProcess = new ProcessBuilder(System.getenv().getOrDefault("REDIS_SERVER_BINARY", "redis-server"),
                "--bind", "127.0.0.1", "--port", redisPort + "", "--save", "", "--appendonly", "no",
                "--dir", directory.toString()).redirectErrorStream(true)
                .redirectOutput(directory.resolve("redis.log").toFile()).start();
        redisFactory = new LettuceConnectionFactory("127.0.0.1", redisPort);
        redisFactory.afterPropertiesSet();
        redis = new StringRedisTemplate(redisFactory);
        redis.afterPropertiesSet();
        deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        ready = false;
        while (System.nanoTime() < deadline && redisProcess.isAlive()) {
            try { ready = "PONG".equals(redis.execute((RedisCallback<String>) connection -> connection.ping()));
                if (ready) break;
            } catch (RuntimeException unavailable) { Thread.sleep(20); }
        }
        assertTrue(ready, "isolated Redis failed to start");
        Config config = new Config();
        config.setThreads(2).setNettyThreads(2);
        config.useSingleServer().setAddress("redis://127.0.0.1:" + redisPort);
        redisson = org.redisson.Redisson.create(config);
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(datasource);
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        for (Class<?> mapper : Arrays.asList(VoucherOrderMapper.class, VoucherMapper.class,
                OrderCancellationMapper.class, AccountAccessMapper.class, SeckillVoucherMapper.class)) configuration.addMapper(mapper);
        factory.setConfiguration(configuration);
        SqlSessionTemplate sql = new SqlSessionTemplate(factory.getObject());
        orders = sql.getMapper(VoucherOrderMapper.class);
        vouchers = sql.getMapper(VoucherMapper.class);
        cancellations = sql.getMapper(OrderCancellationMapper.class);
        accounts = sql.getMapper(AccountAccessMapper.class);
        authorization = new ResourceAuthorizationService(accounts);
        tx = new TransactionTemplate(new DataSourceTransactionManager(datasource));
        SeckillVoucherServiceImpl stockService = new SeckillVoucherServiceImpl();
        ReflectionTestUtils.setField(stockService, "baseMapper", sql.getMapper(SeckillVoucherMapper.class));
        orderService = new VoucherOrderServiceImpl();
        ReflectionTestUtils.setField(orderService, "baseMapper", orders);
        ReflectionTestUtils.setField(orderService, "seckillVoucherService", stockService);
        ReflectionTestUtils.setField(orderService, "redissonClient", redisson);
        ReflectionTestUtils.setField(orderService, "transactionTemplate", tx);
        RedisIdWorker ids = new RedisIdWorker();
        ReflectionTestUtils.setField(ids, "stringRedisTemplate", redis);
        admission = new FlashSaleAdmissionService(vouchers, accounts, authorization, ids, redis);
        ReflectionTestUtils.setField(orderService, "flashSaleAdmissionService", admission);
        lifecycle = new OrderLifecycleService(orders, vouchers, cancellations, accounts, authorization, tx, redis, new ObjectMapper());
    }

    @AfterAll
    static void stopServices() throws Exception {
        clearSession();
        if (redisson != null) redisson.shutdown();
        if (redisFactory != null) redisFactory.destroy();
        stop(redisProcess);
        stop(mysqlProcess);
    }

    @BeforeEach
    void fixture() {
        jdbc.execute("DROP TRIGGER IF EXISTS force_order_failure");
        jdbc.execute("DROP TRIGGER IF EXISTS force_outbox_failure");
        for (String table : Arrays.asList("tb_order_cancellation", "tb_voucher_order", "tb_seckill_voucher",
                "tb_voucher", "tb_user_role", "tb_user")) jdbc.execute("DELETE FROM " + table);
        // This connection points ONLY to the process created above; never to shared developer Redis.
        redis.execute((RedisCallback<Object>) connection -> { connection.serverCommands().flushDb(); return null; });
        for (int id = 7; id <= 46; id++) {
            jdbc.update("INSERT INTO tb_user(id) VALUES(?)", id);
            jdbc.update("INSERT INTO tb_user_role(user_id,role) VALUES(?,'USER')", id);
        }
        jdbc.update("INSERT INTO tb_voucher(id,shop_id,title,pay_value,actual_value,type,status) VALUES(9,1,'Synthetic',100,200,1,1)");
        jdbc.update("INSERT INTO tb_seckill_voucher(voucher_id,stock,begin_time,end_time) " +
                "VALUES(9,3,DATE_SUB(UTC_TIMESTAMP(),INTERVAL 1 MINUTE),DATE_ADD(UTC_TIMESTAMP(),INTERVAL 10 MINUTE))");
        Voucher voucher = vouchers.findFlashSale(9L);
        FlashSaleRules rules = new FlashSaleRules(voucher);
        DefaultRedisScript<Long> init = new DefaultRedisScript<>();
        init.setLocation(new ClassPathResource("initialize-flash-sale.lua")); init.setResultType(Long.class);
        assertEquals(0L, redis.execute(init, FlashSaleRules.activityKeys(9L), "3", "" + rules.getBeginMillis(),
                "" + rules.getEndMillis(), "" + rules.getExpiresAtMillis()));
        queue = new OrderStreamQueue(redis, "stream.orders", "g1", "worker-" + UUID.randomUUID(), Duration.ZERO, 3);
        queue.initialize();
        consumer = new OrderStreamConsumer(orderService, queue);
        session(7L);
    }

    @AfterEach void cleanup() { consumer.stop(); clearSession(); }

    @Test
    void acceptedPersistedCancelledAndCompensatedAreDistinctDurableStates() {
        long id = accepted();
        assertEquals("ACCEPTED", lifecycle.queryMine(id, 9L).getStatus());
        processNew();
        assertEquals("PENDING_PAYMENT", lifecycle.queryMine(id, 9L).getStatus());
        assertEquals("PENDING", lifecycle.cancelMine(id, 9L).getCancellationCompensation());
        assertEquals(3, dbStock()); assertEquals("2", redisStock());
        reconciler().reconcileDue();
        assertEquals("COMPLETED", lifecycle.queryMine(id, 9L).getCancellationCompensation());
        lifecycle.cancelMine(id, 9L); reconciler().reconcileDue();
        assertEquals(3, dbStock()); assertEquals("3", redisStock());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM tb_order_cancellation", Integer.class));
        assertEquals(id, ((Number) admission.admit(9L).getData()).longValue()); // one-user-one-order survives cancellation
        appendDuplicate(id, 7L); processNew();
        assertEquals(3, dbStock()); assertEquals(4, orders.findOwned(id, 7L, 9L).getStatus());
        as(8L, () -> { accepted(); return null; }); processNew();
        assertEquals(2, dbStock()); assertEquals("2", redisStock());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM tb_voucher_order WHERE status<>4", Integer.class));
    }

    @Test
    void concurrentDifferentUsersCannotOversellRedisOrDatabaseAndDuplicatesDoNotDeductAgain() throws Exception {
        List<Callable<Long>> work = new ArrayList<>();
        for (long user = 7; user < 27; user++) {
            final long account = user;
            work.add(() -> as(account, () -> {
                try { return accepted(); }
                catch (BusinessException soldOut) { assertEquals(ErrorCode.SOLD_OUT, soldOut.getErrorCode()); return null; }
            }));
        }
        List<Long> ids = concurrent(work);
        assertEquals(3, ids.stream().filter(Objects::nonNull).count());
        for (int i = 0; i < 3; i++) processNew();
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM tb_voucher_order", Integer.class));
        assertEquals(0, dbStock()); assertEquals("0", redisStock());
        List<VoucherOrder> stored = orders.selectList(null);
        for (VoucherOrder order : stored) { appendDuplicate(order.getId(), order.getUserId()); processNew(); }
        assertEquals(0, dbStock()); assertEquals(3, orders.selectCount(null));
    }

    @Test
    void concurrentSameUserKeepsOneOriginalIdAndOneDatabaseOrder() throws Exception {
        List<Callable<Long>> work = new ArrayList<>();
        for (int i = 0; i < 20; i++) work.add(() -> as(7L, this::accepted));
        List<Long> ids = concurrent(work);
        assertEquals(1, new HashSet<>(ids).size());
        assertEquals(1L, redis.opsForStream().size("stream.orders"));
        processNew(); appendDuplicate(ids.get(0), 7L); processNew();
        assertEquals(1, orders.selectCount(null)); assertEquals(2, dbStock()); assertEquals("2", redisStock());
    }

    @Test
    void realInsertFailureRollsBackDatabaseStockAndSupportsSameIdRedrive() {
        long id = accepted();
        jdbc.execute("CREATE TRIGGER force_order_failure BEFORE INSERT ON tb_voucher_order FOR EACH ROW " +
                "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic fault'");
        OrderStreamQueue oneAttempt = new OrderStreamQueue(redis, "stream.orders", "g1", "failed", Duration.ZERO, 1);
        new OrderStreamConsumer(orderService, oneAttempt).process(oneAttempt.readNew(Duration.ZERO).get(0));
        assertEquals(3, dbStock()); assertEquals(0, orders.selectCount(null)); assertEquals("2", redisStock());
        assertEquals("REQUIRES_REVIEW", lifecycle.queryMine(id, 9L).getStatus());
        assertEquals(ErrorCode.CONFLICT, assertThrows(BusinessException.class, () -> lifecycle.cancelMine(id, 9L)).getErrorCode());
        jdbc.execute("DROP TRIGGER force_order_failure");
        String dead = (String) redis.opsForHash().get("stream.orders.failures", "order:" + id);
        DefaultRedisScript<String> redrive = new DefaultRedisScript<>();
        redrive.setLocation(new ClassPathResource("redrive-order.lua")); redrive.setResultType(String.class);
        redis.execute(redrive, Arrays.asList("stream.orders", "stream.orders.dead", "stream.orders.redrives"), dead, "test", "DB_REVIEWED");
        assertEquals("ACCEPTED", lifecycle.queryMine(id, 9L).getStatus());
        processNew();
        assertEquals(id, orders.findOwned(id, 7L, 9L).getId());
        assertEquals(2, dbStock()); assertEquals("2", redisStock());
    }

    @Test
    void restartAfterCommitBeforeAckDoesNotCreateOrDecrementTwice() {
        long id = accepted();
        MapRecord<String, Object, Object> record = queue.readNew(Duration.ZERO).get(0);
        assertEquals(1, queue.begin(record.getId()));
        orderService.handleVoucherOrder(OrderStreamConsumer.decode(record.getValue())); // simulated process exit before ACK
        OrderStreamQueue replacement = new OrderStreamQueue(redis, "stream.orders", "g1", "replacement", Duration.ZERO, 3);
        new OrderStreamConsumer(orderService, replacement).process(replacement.recoverPending().get(0));
        assertEquals(1, orders.selectCount(null)); assertEquals(2, dbStock());
        assertEquals(0L, redis.opsForStream().pending("stream.orders", "g1").getTotalPendingMessages());
        assertEquals("PENDING_PAYMENT", lifecycle.queryMine(id, 9L).getStatus());
    }

    @Test
    void concurrentCancellationAndRecoveryReturnInventoryExactlyOnce() throws Exception {
        long id = accepted(); processNew();
        List<Callable<String>> work = new ArrayList<>();
        for (int i = 0; i < 8; i++) work.add(() -> as(7L, () -> lifecycle.cancelMine(id, 9L).getStatus()));
        for (String state : concurrent(work)) assertEquals("CANCELLED", state);
        assertEquals(3, dbStock()); assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM tb_order_cancellation", Integer.class));
        List<Callable<Integer>> recovery = new ArrayList<>();
        for (int i = 0; i < 8; i++) recovery.add(() -> { reconciler().reconcileDue(); return 1; });
        concurrent(recovery);
        assertEquals("3", redisStock()); assertEquals("COMPLETED", cancellations.find(id).getStatus());
        assertEquals(1, cancellations.find(id).getAttempts());
    }

    @Test
    void outboxInsertFailureRollsBackCancellationAndStock() {
        long id = accepted(); processNew();
        jdbc.execute("CREATE TRIGGER force_outbox_failure BEFORE INSERT ON tb_order_cancellation FOR EACH ROW " +
                "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic fault'");
        assertEquals(ErrorCode.ORDER_STATE_UNAVAILABLE,
                assertThrows(BusinessException.class, () -> lifecycle.cancelMine(id, 9L)).getErrorCode());
        assertEquals(1, orders.findOwned(id, 7L, 9L).getStatus()); assertEquals(2, dbStock());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM tb_order_cancellation", Integer.class));
        assertEquals("2", redisStock());
    }

    @Test
    void crashAfterRedisReleaseBeforeOutboxCompletionRecoversWithoutAnotherReturn() {
        long id = accepted(); processNew(); lifecycle.cancelMine(id, 9L);
        OrderCancellation entry = cancellations.find(id);
        assertEquals(1, cancellations.claim(id, "dead-worker"));
        DefaultRedisScript<Long> release = new DefaultRedisScript<>();
        release.setLocation(new ClassPathResource("release-cancelled-order.lua")); release.setResultType(Long.class);
        assertEquals(0L, redis.execute(release, Arrays.asList("seckill:stock:9", "seckill:activity:9",
                "seckill:request:9", "seckill:released:9"), "7", id + "", entry.getExpiresAtMs() + ""));
        jdbc.update("UPDATE tb_order_cancellation SET lease_until=DATE_SUB(UTC_TIMESTAMP(),INTERVAL 1 SECOND) WHERE order_id=?", id);
        reconciler().reconcileDue();
        assertEquals("3", redisStock()); assertEquals(3, dbStock());
        assertEquals("ALREADY_RELEASED", cancellations.find(id).getRedisResult());
        assertEquals(2, cancellations.find(id).getAttempts());
    }

    @Test
    void damagedRedisIsReviewableWithoutUndoingCommittedCancellation() {
        long id = accepted(); processNew(); lifecycle.cancelMine(id, 9L);
        redis.opsForValue().set("seckill:stock:9", "broken");
        reconciler().reconcileDue();
        assertEquals("CANCELLED", lifecycle.queryMine(id, 9L).getStatus());
        assertEquals("REQUIRES_REVIEW", lifecycle.queryMine(id, 9L).getCancellationCompensation());
        assertEquals(3, dbStock()); assertEquals("broken", redisStock());
    }

    @Test
    void ownershipAndPaymentStateAreCheckedBeforeCancellation() {
        long id = accepted(); processNew();
        as(8L, () -> {
            assertEquals(ErrorCode.NOT_FOUND, assertThrows(BusinessException.class, () -> lifecycle.queryMine(id, 9L)).getErrorCode());
            assertEquals(ErrorCode.NOT_FOUND, assertThrows(BusinessException.class, () -> lifecycle.cancelMine(id, 9L)).getErrorCode());
            return null;
        });
        session(7L);
        jdbc.update("UPDATE tb_voucher_order SET status=2 WHERE id=?", id); // synthetic legacy paid fact, not a payment API
        assertEquals(ErrorCode.CONFLICT, assertThrows(BusinessException.class, () -> lifecycle.cancelMine(id, 9L)).getErrorCode());
        assertEquals(2, dbStock()); assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM tb_order_cancellation", Integer.class));
    }

    @Test
    void staleCancellationWorkerCannotOverwriteAReplacementLease() {
        long id = accepted(); processNew(); lifecycle.cancelMine(id, 9L);
        assertEquals(1, cancellations.claim(id, "old-worker"));
        jdbc.update("UPDATE tb_order_cancellation SET lease_until=DATE_SUB(UTC_TIMESTAMP(),INTERVAL 1 SECOND) WHERE order_id=?", id);
        assertEquals(1, cancellations.claim(id, "new-worker"));
        assertEquals(0, cancellations.complete(id, "old-worker", "RELEASED"));
        assertEquals(0, cancellations.retry(id, "old-worker", "SyntheticFault"));
        assertEquals(0, cancellations.review(id, "old-worker", "InvalidRedisState"));
        assertEquals("PENDING", cancellations.find(id).getStatus());
        assertEquals("new-worker", cancellations.find(id).getLeaseToken());
        assertEquals(1, cancellations.review(id, "new-worker", "SyntheticReview"));
        assertEquals("REQUIRES_REVIEW", cancellations.find(id).getStatus());
        assertEquals("2", redisStock());
    }

    @Test
    void exhaustedCrashedOutboxIsVisibleAndDoesNotRetryForever() {
        long id = accepted(); processNew(); lifecycle.cancelMine(id, 9L);
        jdbc.update("UPDATE tb_order_cancellation SET attempts=10,lease_token='dead'," +
                "lease_until=DATE_SUB(UTC_TIMESTAMP(),INTERVAL 1 SECOND) WHERE order_id=?", id);
        reconciler().reconcileDue();
        assertEquals("REQUIRES_REVIEW", cancellations.find(id).getStatus());
        assertEquals("RetryBudgetExhausted", cancellations.find(id).getLastError());
        assertEquals("2", redisStock());
    }

    @Test
    void outboxDueDoesNotDependOnServerLocalTimezone() {
        long id = accepted(); processNew();
        jdbc.execute("SET GLOBAL time_zone='+08:00'");
        try {
            lifecycle.cancelMine(id, 9L);
            assertEquals(1, cancellations.due().size(), "local CURRENT_TIMESTAMP must not delay a UTC recovery schedule");
            assertEquals("+00:00", jdbc.queryForObject("SELECT @@session.time_zone", String.class));
            reconciler().reconcileDue();
            assertEquals("COMPLETED", cancellations.find(id).getStatus());
            assertEquals("3", redisStock());
        } finally { jdbc.execute("SET GLOBAL time_zone='+00:00'"); }
    }

    @Test
    void httpAdmissionReadAndCancelUseRealTokenFilterRedisAndDatabase() throws Exception {
        io.github.frewily.campushub.controller.VoucherOrderController controller =
                new io.github.frewily.campushub.controller.VoucherOrderController();
        ReflectionTestUtils.setField(controller, "voucherOrderService", orderService);
        ReflectionTestUtils.setField(controller, "orderLifecycleService", lifecycle);
        org.springframework.test.web.servlet.MockMvc mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                .standaloneSetup(controller)
                .setControllerAdvice(new io.github.frewily.campushub.config.GlobalExceptionHandler())
                .addFilter(new io.github.frewily.campushub.security.RedisTokenAuthenticationFilter(redis, accounts)).build();
        Map<String, String> login = new HashMap<>(); login.put("id", "7"); login.put("nickName", "Synthetic");
        redis.opsForHash().putAll(RedisConstants.LOGIN_USER_KEY + "isolated-http", login);
        String acceptedJson = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/voucher-order/seckill/9").header("authorization", "isolated-http"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andReturn().getResponse().getContentAsString();
        long id = new ObjectMapper().readTree(acceptedJson).get("data").asLong();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/voucher-order/" + id)
                .param("voucherId", "9").header("authorization", "isolated-http"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.status").value("ACCEPTED"));
        processNew();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/voucher-order/" + id + "/cancel")
                .param("voucherId", "9").header("authorization", "isolated-http"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.cancellationCompensation").value("PENDING"));
        reconciler().reconcileDue();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/voucher-order/" + id)
                .param("voucherId", "9").header("authorization", "isolated-http"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.status").value("CANCELLED"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.cancellationCompensation").value("COMPLETED"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/voucher-order/" + id).param("voucherId", "9"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
        assertEquals(3, dbStock()); assertEquals("3", redisStock());
    }

    @Test
    void migrationsBackfillSyntheticRolesAndEnforceThePhysicalUniqueConstraint() throws Exception {
        jdbc.update("INSERT INTO tb_user(id) VALUES(200)");
        jdbc.update("INSERT INTO tb_shop(id,type_id) VALUES(10,1)");
        try (Connection connection = datasource.getConnection()) {
            for (int pass = 0; pass < 2; pass++) {
                ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V002__add_identity_and_merchant_authorization.sql"));
                ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V003__add_order_cancellation_outbox.sql"));
            }
        }
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM tb_user_role WHERE user_id=200 AND role='USER'", Integer.class));
        assertNull(jdbc.queryForObject("SELECT merchant_id FROM tb_shop WHERE id=10", Long.class));
        jdbc.update("INSERT INTO tb_voucher_order(id,user_id,voucher_id) VALUES(900,7,9)");
        assertThrows(org.springframework.dao.DuplicateKeyException.class,
                () -> jdbc.update("INSERT INTO tb_voucher_order(id,user_id,voucher_id) VALUES(901,7,9)"));
    }

    private long accepted() { return ((Number) admission.admit(9L).getData()).longValue(); }
    private void processNew() { consumer.process(queue.readNew(Duration.ZERO).get(0)); }
    private int dbStock() { return jdbc.queryForObject("SELECT stock FROM tb_seckill_voucher WHERE voucher_id=9", Integer.class); }
    private String redisStock() { return redis.opsForValue().get("seckill:stock:9"); }
    private OrderCancellationReconciler reconciler() { return new OrderCancellationReconciler(cancellations, redis, true); }
    private void appendDuplicate(long id, long user) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("id", id + ""); fields.put("userId", user + ""); fields.put("voucherId", "9");
        redis.opsForStream().add("stream.orders", fields);
    }
    private static int freePort() throws Exception { try (ServerSocket socket = new ServerSocket(0)) { return socket.getLocalPort(); } }
    private static void stop(Process process) throws Exception {
        if (process == null) return;
        process.destroy();
        if (!process.waitFor(10, TimeUnit.SECONDS)) { process.destroyForcibly(); assertTrue(process.waitFor(5, TimeUnit.SECONDS)); }
    }
    private static void session(long id) {
        UserDTO user = new UserDTO(); user.setId(id); UserHolder.saveUser(user);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null,
                Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER"))));
    }
    private static void clearSession() { UserHolder.removeUser(); SecurityContextHolder.clearContext(); }
    private static <T> T as(long user, Supplier<T> call) { session(user); try { return call.get(); } finally { clearSession(); } }
    private <T> List<T> concurrent(List<Callable<T>> calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<T> results = new ArrayList<>();
            for (Future<T> future : pool.invokeAll(calls, 20, TimeUnit.SECONDS)) results.add(future.get());
            return results;
        } finally { pool.shutdownNow(); }
    }
}
