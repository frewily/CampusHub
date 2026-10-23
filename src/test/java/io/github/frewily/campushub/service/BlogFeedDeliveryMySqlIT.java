package io.github.frewily.campushub.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import io.github.frewily.campushub.dto.Result;
import io.github.frewily.campushub.dto.UserDTO;
import io.github.frewily.campushub.dto.request.BlogCreateRequest;
import io.github.frewily.campushub.dto.request.BlogEditRequest;
import io.github.frewily.campushub.content.application.BlogUserReader;
import io.github.frewily.campushub.entity.Blog;
import io.github.frewily.campushub.entity.BlogFeedDelivery;
import io.github.frewily.campushub.exception.BusinessException;
import io.github.frewily.campushub.exception.ErrorCode;
import io.github.frewily.campushub.mapper.BlogPublicationRequestMapper;
import io.github.frewily.campushub.mapper.BlogFeedDeliveryMapper;
import io.github.frewily.campushub.mapper.BlogMapper;
import io.github.frewily.campushub.mapper.FollowMapper;
import io.github.frewily.campushub.service.BlogPublicationService;
import io.github.frewily.campushub.service.impl.BlogServiceImpl;
import io.github.frewily.campushub.social.application.FollowerReader;
import io.github.frewily.campushub.social.infrastructure.MyBatisFollowerReader;
import io.github.frewily.campushub.utils.RedisConstants;
import io.github.frewily.campushub.utils.UserHolder;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import javax.validation.Validation;
import javax.validation.Validator;
import javax.validation.ValidatorFactory;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import org.mockito.ArgumentCaptor;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Real MySQL/MyBatis and Spring transaction acceptance for the Blog Feed delivery outbox. */
class BlogFeedDeliveryMySqlIT {
    private static final long AUTHOR_ID = 7301L;
    private static final long RECIPIENT_A = 7302L;
    private static final long RECIPIENT_B = 7303L;

    @TempDir static Path directory;
    private static Process mysql;
    private static DriverManagerDataSource datasource;
    private static JdbcTemplate jdbc;
    private static AnnotationConfigApplicationContext context;
    private static BlogMapper blogMapper;
    private static BlogFeedDeliveryMapper deliveryMapper;
    private static BlogServiceImpl blogService;
    private static BlogPublicationService publicationService;
    private static BlogFeedDeliveryService deliveryService;
    private static StringRedisTemplate redis;
    private static ZSetOperations<String, String> feed;
    private static final AtomicBoolean REPORT_ZERO_AFTER_REAL_ENQUEUE = new AtomicBoolean();
    private static final AtomicBoolean REPORT_ZERO_AFTER_REAL_PUBLICATION_COMPLETE = new AtomicBoolean();
    private static final String DELIVERY_TABLE = "tb_blog_feed_delivery";
    private static final String PUBLICATION_TABLE = "tb_blog_publication_request";

    @BeforeAll
    static void startPrivateMySqlAndSpringContext() throws Exception {
        String binary = System.getenv().getOrDefault("MYSQLD_SERVER_BINARY", "mysqld");
        Path data = directory.resolve("mysql-data");
        Process init = new ProcessBuilder(binary, "--no-defaults", "--initialize-insecure", "--datadir=" + data)
                .redirectErrorStream(true).redirectOutput(directory.resolve("mysql-init.log").toFile()).start();
        try {
            if (!init.waitFor(30, TimeUnit.SECONDS)) {
                init.destroyForcibly();
                fail("private MySQL initialization timed out");
            }
            assertEquals(0, init.exitValue(), "private MySQL initialization failed; inspect mysql-init.log");
        } finally {
            if (init.isAlive()) stopProcess(init);
        }

        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        try {
            mysql = new ProcessBuilder(binary, "--no-defaults", "--datadir=" + data,
                    "--bind-address=127.0.0.1", "--port=" + port, "--mysqlx=OFF", "--skip-log-bin",
                    "--socket=" + directory.resolve("mysql.sock"), "--pid-file=" + directory.resolve("mysql.pid"),
                    "--log-error=" + directory.resolve("mysql.log"))
                    .redirectErrorStream(true).redirectOutput(directory.resolve("mysql-console.log").toFile()).start();
            String base = "jdbc:mysql://127.0.0.1:" + port
                    + "/?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&forceConnectionTimeZoneToSession=true";
            boolean ready = false;
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
            while (System.nanoTime() < until && mysql.isAlive()) {
                try (Connection connection = DriverManager.getConnection(base, "root", "")) {
                    connection.createStatement().execute(
                            "CREATE DATABASE blog_feed_delivery_test CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci");
                    ready = true;
                    break;
                } catch (SQLException unavailable) {
                    Thread.sleep(50);
                }
            }
            assertTrue(ready, "private MySQL did not start");

            datasource = new DriverManagerDataSource(base.replace("/?", "/blog_feed_delivery_test?"), "root", "");
            jdbc = new JdbcTemplate(datasource);
            importFreshSchemaAndRequiredMigrations();

            context = new AnnotationConfigApplicationContext();
            context.register(TestConfiguration.class);
            context.refresh();
            blogMapper = context.getBean(BlogMapper.class);
            deliveryMapper = context.getBean(BlogFeedDeliveryMapper.class);
            blogService = context.getBean(BlogServiceImpl.class);
            deliveryService = context.getBean(BlogFeedDeliveryService.class);
            publicationService = context.getBean(BlogPublicationService.class);
            redis = context.getBean(StringRedisTemplate.class);
            feed = context.getBean("feedOperations", ZSetOperations.class);
            assertTrue(AopUtils.isAopProxy(blogService), "blog service must use Spring transaction AOP");
            assertTrue(AopUtils.isAopProxy(publicationService), "publication service must use Spring transaction AOP");
        } catch (Exception | Error failure) {
            try {
                if (context != null) context.close();
            } finally {
                stopProcess(mysql);
            }
            throw failure;
        }
    }

    private static void importFreshSchemaAndRequiredMigrations() throws Exception {
        try (Connection connection = datasource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new FileSystemResource("deploy/mysql/schema.sql"));
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("db/migration/V001__add_business_unique_constraints.sql"));
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("db/migration/V006__allow_campus_posts_without_shop.sql"));
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("db/migration/V010__add_blog_author_withdrawal.sql"));
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("db/migration/V011__add_blog_feed_delivery_outbox.sql"));
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("db/migration/V012__add_blog_publication_requests.sql"));
        }
    }

    @Configuration
    @EnableTransactionManagement(proxyTargetClass = true)
    static class TestConfiguration {
        @Bean
        DataSource dataSource() { return datasource; }

        @Bean
        org.apache.ibatis.session.SqlSessionFactory sqlSessionFactory(DataSource dataSource) throws Exception {
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            MybatisConfiguration configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true);
            configuration.addInterceptor(new EnqueueContractInterceptor());
            factory.setConfiguration(configuration);
            factory.setMapperLocations(new ClassPathResource("mapper/BlogMapper.xml"));
            return factory.getObject();
        }

        @Bean MapperFactoryBean<BlogMapper> blogMapper(org.apache.ibatis.session.SqlSessionFactory factory) {
            return mapperFactory(BlogMapper.class, factory);
        }

        @Bean MapperFactoryBean<FollowMapper> followMapper(org.apache.ibatis.session.SqlSessionFactory factory) {
            return mapperFactory(FollowMapper.class, factory);
        }

        @Bean MapperFactoryBean<BlogFeedDeliveryMapper> blogFeedDeliveryMapper(
                org.apache.ibatis.session.SqlSessionFactory factory) {
            return mapperFactory(BlogFeedDeliveryMapper.class, factory);
        }

        @Bean MapperFactoryBean<BlogPublicationRequestMapper> blogPublicationRequestMapper(
                org.apache.ibatis.session.SqlSessionFactory factory) {
            return mapperFactory(BlogPublicationRequestMapper.class, factory);
        }

        private static <T> MapperFactoryBean<T> mapperFactory(
                Class<T> mapperType, org.apache.ibatis.session.SqlSessionFactory factory) {
            MapperFactoryBean<T> bean = new MapperFactoryBean<>(mapperType);
            bean.setSqlSessionFactory(factory);
            return bean;
        }

        @Bean ValidatorFactory validatorFactory() { return Validation.buildDefaultValidatorFactory(); }

        @Bean Validator validator(ValidatorFactory factory) { return factory.getValidator(); }

        @Bean BlogUserReader blogUserReader() { return mock(BlogUserReader.class); }

        @Bean StringRedisTemplate stringRedisTemplate() { return mock(StringRedisTemplate.class); }

        @Bean(name = "feedOperations")
        @SuppressWarnings("unchecked")
        ZSetOperations<String, String> feedOperations() { return mock(ZSetOperations.class); }

        @Bean FollowerReader followerReader(FollowMapper mapper) {
            return new MyBatisFollowerReader(mapper);
        }

        @Bean BlogFeedDeliveryService blogFeedDeliveryService(
                BlogFeedDeliveryMapper mapper, StringRedisTemplate redis) {
            return new BlogFeedDeliveryService(mapper, redis, true);
        }

        @Bean BlogServiceImpl blogService(BlogMapper mapper) {
            BlogServiceImpl service = new BlogServiceImpl();
            ReflectionTestUtils.setField(service, "baseMapper", mapper);
            return service;
        }

        @Bean BlogPublicationService blogPublicationService(IBlogService blogs,
                BlogPublicationRequestMapper requests, Validator validator) {
            return new BlogPublicationService(blogs, requests, validator);
        }

        @Bean DataSourceTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }
    }

    @Intercepts(@Signature(type = Executor.class, method = "update", args = {MappedStatement.class, Object.class}))
    static class EnqueueContractInterceptor implements Interceptor {
        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            MappedStatement statement = (MappedStatement) invocation.getArgs()[0];
            boolean enqueue = statement.getId().endsWith(".enqueue");
            boolean publicationComplete = statement.getId().endsWith(".complete")
                    && statement.getId().contains("BlogPublicationRequestMapper");
            if (!enqueue && !publicationComplete) return invocation.proceed();
            assertTrue(org.springframework.transaction.support.TransactionSynchronizationManager
                            .isActualTransactionActive(), "database write requires an actual transaction");
            assertTrue(org.springframework.transaction.support.TransactionSynchronizationManager
                            .isSynchronizationActive(), "database write requires transaction synchronization");
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager
                            .isCurrentTransactionReadOnly(), "database write requires a writable transaction");
            Object result = invocation.proceed();
            AtomicBoolean hook = enqueue ? REPORT_ZERO_AFTER_REAL_ENQUEUE
                    : REPORT_ZERO_AFTER_REAL_PUBLICATION_COMPLETE;
            if (hook.compareAndSet(true, false)) {
                assertEquals(Integer.valueOf(1), result, "zero hook runs only after a real MySQL write");
                return Integer.valueOf(0);
            }
            return result;
        }
    }

    @AfterAll
    static void stopPrivateMySql() throws Exception {
        try {
            if (context != null) context.close();
        } finally {
            stopProcess(mysql);
        }
    }

    @BeforeEach
    void resetDatabaseAndMocks() {
        dropTrigger("fail_second_delivery");
        dropTrigger("fail_delivery_ack");
        dropTrigger("fail_publication_complete");
        jdbc.execute("DELETE FROM " + PUBLICATION_TABLE);
        jdbc.execute("DELETE FROM " + DELIVERY_TABLE);
        jdbc.execute("DELETE FROM tb_follow");
        jdbc.execute("DELETE FROM tb_blog");
        REPORT_ZERO_AFTER_REAL_ENQUEUE.set(false);
        REPORT_ZERO_AFTER_REAL_PUBLICATION_COMPLETE.set(false);
        reset(redis, feed);
        when(redis.opsForZSet()).thenReturn(feed);
        doReturn(Boolean.TRUE).when(feed).add(anyString(), anyString(), anyDouble());
        UserHolder.saveUser(user(AUTHOR_ID));
    }

    @AfterEach
    void clearHooksAndUser() {
        REPORT_ZERO_AFTER_REAL_ENQUEUE.set(false);
        REPORT_ZERO_AFTER_REAL_PUBLICATION_COMPLETE.set(false);
        UserHolder.removeUser();
        dropTrigger("fail_second_delivery");
        dropTrigger("fail_delivery_ack");
        dropTrigger("fail_publication_complete");
    }

    @Test
    void committedPublishSnapshotsRealFollowRowsWithOneFixedScoreAndUsesTransactionAop() {
        addFollow(RECIPIENT_A);
        addFollow(RECIPIENT_B);

        Result result = blogService.saveBlog(post("multi-target"));

        long blogId = resultBlogId(result);
        List<BlogFeedDelivery> intents = deliveryMapper.pending();
        assertEquals(2, intents.size());
        assertEquals(new java.util.HashSet<>(Arrays.asList(RECIPIENT_A, RECIPIENT_B)),
                new java.util.HashSet<>(Arrays.asList(
                        intents.get(0).getRecipientUserId(), intents.get(1).getRecipientUserId())));
        Long fixedScore = intents.get(0).getScoreMillis();
        assertTrue(fixedScore > 0);
        assertEquals(fixedScore, intents.get(1).getScoreMillis());
        assertEquals(blogId, intents.get(0).getBlogId());
        verify(feed).add(RedisConstants.FEED_KEY + RECIPIENT_A, Long.toString(blogId), fixedScore.doubleValue());
        verify(feed).add(RedisConstants.FEED_KEY + RECIPIENT_B, Long.toString(blogId), fixedScore.doubleValue());
    }

    @Test
    void afterCommitRedisCallbackSeesPostAndIntentsThroughIndependentConnection() {
        addFollow(RECIPIENT_A);
        AtomicBoolean independentlyObservedCommittedRows = new AtomicBoolean();
        doAnswer(invocation -> {
            String key = invocation.getArgument(0);
            String member = invocation.getArgument(1);
            long id = Long.parseLong(member);
            assertEquals(RedisConstants.FEED_KEY + RECIPIENT_A, key);
            try (Connection independent = datasource.getConnection()) {
                assertEquals(1, count(independent, "SELECT COUNT(*) FROM tb_blog WHERE id=?", id));
                assertEquals(1, count(independent, "SELECT COUNT(*) FROM " + DELIVERY_TABLE
                        + " WHERE blog_id=? AND recipient_user_id=?", id, RECIPIENT_A));
            }
            independentlyObservedCommittedRows.set(true);
            return Boolean.TRUE;
        }).when(feed).add(anyString(), anyString(), anyDouble());

        Result result = blogService.saveBlog(post("visible-at-callback"));

        assertTrue(independentlyObservedCommittedRows.get());
        assertTrue(blogExists(resultBlogId(result)));
    }

    @Test
    void laterRecipientInsertTriggerRollsBackPostAndEarlierIntentWithoutRedis() {
        addFollow(RECIPIENT_A);
        addFollow(RECIPIENT_B);
        jdbc.execute("CREATE TRIGGER fail_second_delivery BEFORE INSERT ON " + DELIVERY_TABLE
                + " FOR EACH ROW BEGIN SET @delivery_insert_count=COALESCE(@delivery_insert_count,0)+1; "
                + "IF @delivery_insert_count=2 THEN SIGNAL SQLSTATE '45000' "
                + "SET MESSAGE_TEXT='synthetic delivery failure'; END IF; END");

        BusinessException failure = assertThrows(BusinessException.class,
                () -> blogService.saveBlog(post("must-rollback")));

        assertEquals(ErrorCode.INTERNAL_ERROR, failure.getErrorCode());
        assertNull(failure.getCause());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM tb_blog", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM " + DELIVERY_TABLE, Integer.class));
        org.mockito.Mockito.verifyNoInteractions(feed);
    }

    @Test
    void reportedZeroAfterRealOutboxInsertReturns422AndRollsBackPostAndIntent() {
        addFollow(RECIPIENT_A);
        REPORT_ZERO_AFTER_REAL_ENQUEUE.set(true);

        BusinessException failure = assertThrows(BusinessException.class,
                () -> blogService.saveBlog(post("reported-zero")));

        assertEquals(ErrorCode.OPERATION_FAILED, failure.getErrorCode());
        assertEquals(422, failure.getErrorCode().getHttpStatus().value());
        assertFalse(REPORT_ZERO_AFTER_REAL_ENQUEUE.get());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM tb_blog", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM " + DELIVERY_TABLE, Integer.class));
        org.mockito.Mockito.verifyNoInteractions(feed);
    }

    @Test
    void noFollowersCommitsPostWithoutCreatingIntentOrCallingRedis() {
        Result result = blogService.saveBlog(post("no-followers"));

        assertTrue(blogExists(resultBlogId(result)));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM " + DELIVERY_TABLE, Integer.class));
        org.mockito.Mockito.verifyNoInteractions(feed);
    }

    @Test
    void redisFailureLeavesAcceptablePublishForDueRecoveryAndMatchingAck() {
        addFollow(RECIPIENT_A);
        doThrow(new IllegalStateException("secret redis response must not be persisted"))
                .when(feed).add(anyString(), anyString(), anyDouble());

        Result result = assertDoesNotThrow(() -> blogService.saveBlog(post("redis-down")));
        long blogId = resultBlogId(result);
        long fixedScore = scoreFor(blogId, RECIPIENT_A);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM " + DELIVERY_TABLE
                + " WHERE blog_id=? AND recipient_user_id=? AND attempts=0", Integer.class, blogId, RECIPIENT_A));

        makeDue(blogId, RECIPIENT_A);
        deliveryService.recoverPending();
        assertEquals(1, jdbc.queryForObject("SELECT attempts FROM " + DELIVERY_TABLE
                + " WHERE blog_id=? AND recipient_user_id=?", Integer.class, blogId, RECIPIENT_A));
        assertEquals("IllegalStateException", jdbc.queryForObject("SELECT last_error FROM " + DELIVERY_TABLE
                + " WHERE blog_id=? AND recipient_user_id=?", String.class, blogId, RECIPIENT_A));
        assertTrue(jdbc.queryForObject("SELECT next_attempt_at > UTC_TIMESTAMP() FROM " + DELIVERY_TABLE
                + " WHERE blog_id=? AND recipient_user_id=?", Boolean.class, blogId, RECIPIENT_A));
        assertFalse(jdbc.queryForObject("SELECT last_error LIKE '%secret%' FROM " + DELIVERY_TABLE
                + " WHERE blog_id=? AND recipient_user_id=?", Boolean.class, blogId, RECIPIENT_A));

        doReturn(Boolean.FALSE).when(feed).add(anyString(), anyString(), anyDouble());
        makeDue(blogId, RECIPIENT_A);
        deliveryService.recoverPending();
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM " + DELIVERY_TABLE
                + " WHERE blog_id=? AND recipient_user_id=?", Integer.class, blogId, RECIPIENT_A));
        ArgumentCaptor<Double> replayedScores = ArgumentCaptor.forClass(Double.class);
        verify(feed, org.mockito.Mockito.times(3)).add(eq(RedisConstants.FEED_KEY + RECIPIENT_A),
                eq(Long.toString(blogId)), replayedScores.capture());
        assertEquals(Arrays.asList((double) fixedScore, (double) fixedScore, (double) fixedScore),
                replayedScores.getAllValues());
    }

    @Test
    void redisSuccessWithDeleteFailureRetainsAndReplaysSameMemberAndScore() {
        addFollow(RECIPIENT_A);
        long blogId = resultBlogId(blogService.saveBlog(post("ack-retry")));
        long score = scoreFor(blogId, RECIPIENT_A);
        jdbc.execute("CREATE TRIGGER fail_delivery_ack BEFORE DELETE ON " + DELIVERY_TABLE
                + " FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic ack failure'");

        makeDue(blogId, RECIPIENT_A);
        assertDoesNotThrow(() -> deliveryService.recoverPending());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM " + DELIVERY_TABLE
                + " WHERE blog_id=? AND recipient_user_id=? AND score_millis=?", Integer.class,
                blogId, RECIPIENT_A, score));
        dropTrigger("fail_delivery_ack");
        makeDue(blogId, RECIPIENT_A);
        deliveryService.recoverPending();

        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM " + DELIVERY_TABLE
                + " WHERE blog_id=? AND recipient_user_id=?", Integer.class, blogId, RECIPIENT_A));
        verify(feed, org.mockito.Mockito.times(3)).add(RedisConstants.FEED_KEY + RECIPIENT_A,
                Long.toString(blogId), (double) score);
    }

    @Test
    void outerRollbackNeverRunsPublisherCallbackOrPersistsPostAndIntent() {
        addFollow(RECIPIENT_A);
        PlatformTransactionManager transactionManager = context.getBean(PlatformTransactionManager.class);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        assertThrows(IllegalStateException.class, () -> transaction.execute(status -> {
            blogService.saveBlog(post("outer-rollback"));
            throw new IllegalStateException("force outer rollback");
        }));

        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM tb_blog", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM " + DELIVERY_TABLE, Integer.class));
        org.mockito.Mockito.verifyNoInteractions(feed);
    }

    @Test
    void rerunningV011PreservesPendingIntentAndExactIndexColumns() throws Exception {
        addFollow(RECIPIENT_A);
        long blogId = resultBlogId(blogService.saveBlog(post("migration-repeat")));
        long score = scoreFor(blogId, RECIPIENT_A);
        ClassPathResource migration = new ClassPathResource("db/migration/V011__add_blog_feed_delivery_outbox.sql");
        try (Connection connection = datasource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, migration);
            ScriptUtils.executeSqlScript(connection, migration);
        }

        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM " + DELIVERY_TABLE
                + " WHERE blog_id=? AND recipient_user_id=? AND score_millis=?", Integer.class,
                blogId, RECIPIENT_A, score));
        assertEquals("blog_id,recipient_user_id", indexColumns("PRIMARY"));
        assertEquals("next_attempt_at,blog_id,recipient_user_id", indexColumns("idx_blog_feed_delivery_due"));
        assertEquals(Arrays.asList("blog_id", "recipient_user_id", "score_millis", "attempts", "last_error",
                        "next_attempt_at", "create_time", "update_time"),
                jdbc.queryForList("SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA=DATABASE() "
                        + "AND TABLE_NAME=? ORDER BY ORDINAL_POSITION", String.class, DELIVERY_TABLE));
    }

    @Test
    void fastPathAndEachRecoveryPollAreCappedAtThirtyTwoWithoutDroppingTheRemainder() {
        for (long recipient = 7400L; recipient < 7433L; recipient++) addFollow(recipient);

        long blogId = resultBlogId(blogService.saveBlog(post("thirty-three-targets")));

        assertEquals(33, jdbc.queryForObject("SELECT COUNT(*) FROM " + DELIVERY_TABLE
                + " WHERE blog_id=?", Integer.class, blogId), "transaction must persist the complete recipient snapshot");
        org.mockito.Mockito.verify(feed, org.mockito.Mockito.times(32))
                .add(anyString(), eq(Long.toString(blogId)), anyDouble());
        deliveryService.recoverPending();
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM " + DELIVERY_TABLE
                + " WHERE blog_id=?", Integer.class, blogId), "one poll handles at most 32 pending rows");
        deliveryService.recoverPending();
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM " + DELIVERY_TABLE
                + " WHERE blog_id=?", Integer.class, blogId));
        verify(feed, org.mockito.Mockito.times(65)).add(anyString(), eq(Long.toString(blogId)), anyDouble());
    }

    @Test
    void sameKeyAndNormalizedBodyReplayOneBlogAndOutboxWithoutSecondRedisFastPath() {
        addFollow(RECIPIENT_A);
        long blogId = resultBlogId(publicationService.publish(
                request(null, "same post", null, "same content"), "replay_key"));

        long replayed = resultBlogId(publicationService.publish(
                request(null, "same post", "  \t", "same content"), "replay_key"));

        assertEquals(blogId, replayed);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM tb_blog", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM " + DELIVERY_TABLE, Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM " + PUBLICATION_TABLE, Integer.class));
        verify(feed).add(eq(RedisConstants.FEED_KEY + RECIPIENT_A), eq(Long.toString(blogId)), anyDouble());
    }

    @Test
    void sameKeyWithChangedBodyConflictsWithoutCreatingPostOrOutbox() {
        addFollow(RECIPIENT_A);
        publicationService.publish(request(null, "original", null, "body"), "fixed-key");

        BusinessException conflict = assertThrows(BusinessException.class,
                () -> publicationService.publish(request(null, "changed", null, "body"), "fixed-key"));

        assertEquals(ErrorCode.CONFLICT, conflict.getErrorCode());
        assertEquals(409, conflict.getErrorCode().getHttpStatus().value());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM tb_blog", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM " + DELIVERY_TABLE, Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM " + PUBLICATION_TABLE, Integer.class));
        verify(feed, org.mockito.Mockito.times(1)).add(eq(RedisConstants.FEED_KEY + RECIPIENT_A),
                anyString(), anyDouble());
    }

    @Test
    void requestKeysAreScopedByAuthorAndRemainAsciiCaseSensitive() {
        publicationService.publish(request(null, "author a", null, "body"), "SameKey");
        UserHolder.saveUser(user(AUTHOR_ID + 1));
        publicationService.publish(request(null, "author b", null, "body"), "SameKey");
        UserHolder.saveUser(user(AUTHOR_ID));
        publicationService.publish(request(null, "case split", null, "body"), "samekey");

        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM tb_blog", Integer.class));
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM " + PUBLICATION_TABLE, Integer.class));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(DISTINCT user_id) FROM " + PUBLICATION_TABLE,
                Integer.class));
    }

    @Test
    void firstRedisFailureStillReplaysCommittedIntentAndSameBlogWithoutFastPathRetry() {
        addFollow(RECIPIENT_A);
        doThrow(new IllegalStateException("synthetic private Redis failure"))
                .when(feed).add(anyString(), anyString(), anyDouble());

        long blogId = resultBlogId(publicationService.publish(
                request(null, "redis failure", null, "body"), "redis-replay"));
        doReturn(Boolean.TRUE).when(feed).add(anyString(), anyString(), anyDouble());
        long replayed = resultBlogId(publicationService.publish(
                request(null, "redis failure", "", "body"), "redis-replay"));

        assertEquals(blogId, replayed);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM tb_blog", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM " + DELIVERY_TABLE, Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM " + PUBLICATION_TABLE, Integer.class));
        verify(feed, org.mockito.Mockito.times(1)).add(eq(RedisConstants.FEED_KEY + RECIPIENT_A),
                eq(Long.toString(blogId)), anyDouble());
    }

    @Test
    void failedPublicationCompletionUpdateRollsBackPostReservationAndOutboxThenAllowsRetry() {
        addFollow(RECIPIENT_A);
        jdbc.execute("CREATE TRIGGER fail_publication_complete BEFORE UPDATE ON " + PUBLICATION_TABLE
                + " FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic completion failure'");

        BusinessException failure = assertThrows(BusinessException.class,
                () -> publicationService.publish(request(null, "retry after rollback", null, "body"), "rollback"));

        assertEquals(ErrorCode.INTERNAL_ERROR, failure.getErrorCode());
        assertNull(failure.getCause());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM tb_blog", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM " + DELIVERY_TABLE, Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM " + PUBLICATION_TABLE, Integer.class));
        org.mockito.Mockito.verifyNoInteractions(feed);

        dropTrigger("fail_publication_complete");
        long blogId = resultBlogId(publicationService.publish(
                request(null, "retry after rollback", null, "body"), "rollback"));
        assertTrue(blogExists(blogId));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM " + PUBLICATION_TABLE, Integer.class));
    }

    @Test
    void reportedZeroAfterRealPublicationCompletionReturns422AndRollsBackEverything() {
        addFollow(RECIPIENT_A);
        REPORT_ZERO_AFTER_REAL_PUBLICATION_COMPLETE.set(true);

        BusinessException failure = assertThrows(BusinessException.class,
                () -> publicationService.publish(request(null, "completion zero", null, "body"), "zero"));

        assertEquals(ErrorCode.OPERATION_FAILED, failure.getErrorCode());
        assertEquals(422, failure.getErrorCode().getHttpStatus().value());
        assertFalse(REPORT_ZERO_AFTER_REAL_PUBLICATION_COMPLETE.get());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM tb_blog", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM " + DELIVERY_TABLE, Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM " + PUBLICATION_TABLE, Integer.class));
        org.mockito.Mockito.verifyNoInteractions(feed);
    }

    @Test
    void laterOutboxInsertFailureRollsBackPublicationReservationAndCanRetry() {
        addFollow(RECIPIENT_A);
        addFollow(RECIPIENT_B);
        jdbc.execute("CREATE TRIGGER fail_second_delivery BEFORE INSERT ON " + DELIVERY_TABLE
                + " FOR EACH ROW BEGIN SET @delivery_insert_count=COALESCE(@delivery_insert_count,0)+1; "
                + "IF @delivery_insert_count=2 THEN SIGNAL SQLSTATE '45000' "
                + "SET MESSAGE_TEXT='synthetic delivery failure'; END IF; END");

        BusinessException failure = assertThrows(BusinessException.class,
                () -> publicationService.publish(request(null, "outbox rollback", null, "body"), "outbox-retry"));
        assertEquals(ErrorCode.INTERNAL_ERROR, failure.getErrorCode());
        assertNull(failure.getCause());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM tb_blog", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM " + DELIVERY_TABLE, Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM " + PUBLICATION_TABLE, Integer.class));
        org.mockito.Mockito.verifyNoInteractions(feed);

        dropTrigger("fail_second_delivery");
        long blogId = resultBlogId(publicationService.publish(
                request(null, "outbox rollback", null, "body"), "outbox-retry"));
        assertTrue(blogExists(blogId));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM " + DELIVERY_TABLE, Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM " + PUBLICATION_TABLE, Integer.class));
    }

    @Test
    void concurrentSameAuthorAndKeyCreatesOneBlogAndOneIntent() throws Exception {
        addFollow(RECIPIENT_A);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Result>> calls = new java.util.ArrayList<>();
            for (int i = 0; i < 4; i++) {
                calls.add(pool.submit(() -> publishAs(AUTHOR_ID, start,
                        request(null, "concurrent", null, "same body"), "parallel-key")));
            }
            start.countDown();
            long firstId = resultBlogId(calls.get(0).get(15, TimeUnit.SECONDS));
            for (Future<Result> call : calls) assertEquals(firstId, resultBlogId(call.get(15, TimeUnit.SECONDS)));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM tb_blog", Integer.class));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM " + PUBLICATION_TABLE, Integer.class));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM " + DELIVERY_TABLE, Integer.class));
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS), "concurrent publisher threads must terminate");
            String engineStatus = jdbc.queryForMap("SHOW ENGINE INNODB STATUS").get("Status").toString();
            boolean receiptDeadlock = engineStatus.contains("LATEST DETECTED DEADLOCK")
                    && engineStatus.contains(PUBLICATION_TABLE);
            // Classify only; never print the engine's SQL/record dump.
            System.out.println("Synthetic four-caller publication receiptDeadlock=" + receiptDeadlock);
            assertFalse(receiptDeadlock, "immutable receipt replay must not upgrade shared INSERT locks");
        }
    }

    @Test
    void duplicateLockingReadSeesWinnerBeyondRepeatableReadSnapshot() throws Exception {
        addFollow(RECIPIENT_A);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        CountDownLatch oldSnapshotRead = new CountDownLatch(1);
        try {
            TransactionTemplate transaction = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
            transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
            long replayed = transaction.execute(status -> {
                assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM " + PUBLICATION_TABLE, Integer.class));
                Future<Result> winner = pool.submit(() -> {
                    assertTrue(oldSnapshotRead.await(5, TimeUnit.SECONDS));
                    UserHolder.saveUser(user(AUTHOR_ID));
                    try {
                        return publicationService.publish(request(null, "rr current read", null, "body"), "rr-key");
                    } finally {
                        UserHolder.removeUser();
                    }
                });
                oldSnapshotRead.countDown();
                long winnerId = resultBlogId(awaitResult(winner));
                assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM " + PUBLICATION_TABLE, Integer.class),
                        "the outer transaction keeps its earlier consistent-read snapshot");
                long replayId = resultBlogId(publicationService.publish(
                        request(null, "rr current read", "", "body"), "rr-key"));
                assertEquals(winnerId, replayId);
                return replayId;
            });

            assertNotNull(replayed);
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM tb_blog", Integer.class));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM " + PUBLICATION_TABLE, Integer.class));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM " + DELIVERY_TABLE, Integer.class));
            verify(feed).add(eq(RedisConstants.FEED_KEY + RECIPIENT_A), anyString(), anyDouble());
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS), "RR winner thread must terminate");
        }
    }

    @Test
    void malformedReservationFailsClosedAndEditOrWithdrawDoesNotRecreateIt() {
        BlogCreateRequest original = request(null, "original title", null, "original body");
        long blogId = resultBlogId(publicationService.publish(original, "stable-request"));
        jdbc.update("UPDATE " + PUBLICATION_TABLE + " SET blog_id=NULL WHERE user_id=? AND request_key=?",
                AUTHOR_ID, "stable-request");
        BusinessException malformed = assertThrows(BusinessException.class,
                () -> publicationService.publish(original, "stable-request"));
        assertEquals(ErrorCode.INTERNAL_ERROR, malformed.getErrorCode());

        jdbc.update("UPDATE " + PUBLICATION_TABLE + " SET blog_id=? WHERE user_id=? AND request_key=?",
                blogId, AUTHOR_ID, "stable-request");
        BlogEditRequest edit = new BlogEditRequest();
        edit.setTitle("edited title");
        edit.setImages("image");
        edit.setContent("edited body");
        blogService.editBlog(blogId, edit);
        blogService.withdrawBlog(blogId);

        assertEquals(blogId, resultBlogId(publicationService.publish(original, "stable-request")));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM tb_blog", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM " + PUBLICATION_TABLE, Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT withdrawn FROM tb_blog WHERE id=?", Integer.class, blogId));
        org.mockito.Mockito.verifyNoInteractions(feed);
    }

    @Test
    void invalidKeyOrBodyIsRejectedBeforeReservationSql() {
        assertEquals(ErrorCode.VALIDATION_FAILED, assertThrows(BusinessException.class,
                () -> publicationService.publish(request(null, "valid", null, "body"), "bad key"))
                .getErrorCode());
        assertEquals(ErrorCode.VALIDATION_FAILED, assertThrows(BusinessException.class,
                () -> publicationService.publish(request(null, " ", null, "body"), "valid-key"))
                .getErrorCode());
        UserHolder.saveUser(user(0));
        assertEquals(ErrorCode.AUTHENTICATION_FAILED, assertThrows(BusinessException.class,
                () -> publicationService.publish(request(null, "valid", null, "body"), "valid-key"))
                .getErrorCode());
        UserHolder.removeUser();
        assertEquals(ErrorCode.AUTHENTICATION_FAILED, assertThrows(BusinessException.class,
                () -> publicationService.publish(request(null, "valid", null, "body"), "valid-key"))
                .getErrorCode());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM " + PUBLICATION_TABLE, Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM tb_blog", Integer.class));
        org.mockito.Mockito.verifyNoInteractions(feed);
    }

    @Test
    void publicationRejectsMissingOrReadOnlyTransactionBeforeAnySqlWrite() {
        BlogPublicationService unproxied = new BlogPublicationService(blogService,
                context.getBean(BlogPublicationRequestMapper.class), context.getBean(Validator.class));
        assertThrows(IllegalStateException.class, () -> unproxied.publish(
                request(null, "outside transaction", null, "body"), "no-tx"));

        TransactionTemplate readOnly = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        readOnly.setReadOnly(true);
        assertThrows(IllegalStateException.class, () -> readOnly.execute(status -> unproxied.publish(
                request(null, "read only", null, "body"), "read-only")));

        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM " + PUBLICATION_TABLE, Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM tb_blog", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM " + DELIVERY_TABLE, Integer.class));
        org.mockito.Mockito.verifyNoInteractions(feed);
    }

    @Test
    void rerunningV012PreservesReservationAndExactPrimaryKeyAndAsciiBinaryColumns() throws Exception {
        long blogId = resultBlogId(publicationService.publish(
                request(null, "migration row", null, "body"), "MigrationKey"));
        ClassPathResource migration = new ClassPathResource("db/migration/V012__add_blog_publication_requests.sql");
        try (Connection connection = datasource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, migration);
            ScriptUtils.executeSqlScript(connection, migration);
        }

        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM " + PUBLICATION_TABLE
                + " WHERE user_id=? AND request_key=? AND blog_id=?", Integer.class,
                AUTHOR_ID, "MigrationKey", blogId));
        assertEquals("user_id,request_key", indexColumns(PUBLICATION_TABLE, "PRIMARY"));
        assertEquals(Arrays.asList("user_id", "request_key", "request_hash", "blog_id", "create_time"),
                jdbc.queryForList("SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA=DATABASE() "
                        + "AND TABLE_NAME=? ORDER BY ORDINAL_POSITION", String.class, PUBLICATION_TABLE));
        assertEquals("varchar(64)", jdbc.queryForObject("SELECT COLUMN_TYPE FROM INFORMATION_SCHEMA.COLUMNS "
                + "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND COLUMN_NAME='request_key'",
                String.class, PUBLICATION_TABLE));
        assertEquals("ascii_bin", jdbc.queryForObject("SELECT COLLATION_NAME FROM INFORMATION_SCHEMA.COLUMNS "
                + "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND COLUMN_NAME='request_key'",
                String.class, PUBLICATION_TABLE));
        assertEquals("char(64)", jdbc.queryForObject("SELECT COLUMN_TYPE FROM INFORMATION_SCHEMA.COLUMNS "
                + "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND COLUMN_NAME='request_hash'",
                String.class, PUBLICATION_TABLE));
        assertEquals("ascii_bin", jdbc.queryForObject("SELECT COLLATION_NAME FROM INFORMATION_SCHEMA.COLUMNS "
                + "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND COLUMN_NAME='request_hash'",
                String.class, PUBLICATION_TABLE));
    }

    private static Result publishAs(long authorId, CountDownLatch start, BlogCreateRequest request, String key)
            throws Exception {
        assertTrue(start.await(5, TimeUnit.SECONDS));
        UserHolder.saveUser(user(authorId));
        try {
            return publicationService.publish(request, key);
        } finally {
            UserHolder.removeUser();
        }
    }

    private static BlogCreateRequest request(Long shopId, String title, String images, String content) {
        BlogCreateRequest request = new BlogCreateRequest();
        request.setShopId(shopId);
        request.setTitle(title);
        request.setImages(images);
        request.setContent(content);
        return request;
    }

    private static void addFollow(long recipientId) {
        jdbc.update("INSERT INTO tb_follow(user_id,follow_user_id) VALUES (?,?)", recipientId, AUTHOR_ID);
    }

    @Test
    void bypassedTransactionProxyIsRejectedBeforeAnyPostOrIntentWrite() {
        BlogServiceImpl unproxied = new BlogServiceImpl();
        ReflectionTestUtils.setField(unproxied, "baseMapper", blogMapper);
        ReflectionTestUtils.setField(unproxied, "followerReader", context.getBean(FollowerReader.class));
        ReflectionTestUtils.setField(unproxied, "blogFeedDeliveryService", deliveryService);
        assertThrows(IllegalStateException.class, () -> unproxied.saveBlog(post("must require transaction first")));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM tb_blog", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM " + DELIVERY_TABLE, Integer.class));
        org.mockito.Mockito.verifyNoInteractions(feed);
    }

    private static Blog post(String title) {
        return new Blog().setTitle(title).setImages("").setContent("body");
    }

    private static UserDTO user(long id) {
        UserDTO user = new UserDTO();
        user.setId(id);
        return user;
    }

    private static long resultBlogId(Result result) {
        assertNotNull(result);
        assertEquals(Boolean.TRUE, result.getSuccess());
        Object data = result.getData();
        assertTrue(data instanceof Long, "a successful publication returns a Long blog ID");
        long blogId = (Long) data;
        assertTrue(blogId > 0, "a successful publication returns a positive blog ID");
        return blogId;
    }

    private static boolean blogExists(long blogId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM tb_blog WHERE id=?", Integer.class, blogId) == 1;
    }

    private static long scoreFor(long blogId, long recipientId) {
        return jdbc.queryForObject("SELECT score_millis FROM " + DELIVERY_TABLE
                + " WHERE blog_id=? AND recipient_user_id=?", Long.class, blogId, recipientId);
    }

    private static int count(Connection connection, String sql, long... parameters) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) statement.setLong(i + 1, parameters[i]);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getInt(1);
            }
        }
    }

    private static void makeDue(long blogId, long recipientId) {
        jdbc.update("UPDATE " + DELIVERY_TABLE + " SET next_attempt_at=DATE_SUB(UTC_TIMESTAMP(), INTERVAL 1 SECOND) "
                + "WHERE blog_id=? AND recipient_user_id=?", blogId, recipientId);
    }

    private static String indexColumns(String index) {
        return indexColumns(DELIVERY_TABLE, index);
    }

    private static String indexColumns(String table, String index) {
        return jdbc.queryForObject("SELECT GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) "
                + "FROM INFORMATION_SCHEMA.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND INDEX_NAME=?",
                String.class, table, index);
    }

    private static Result awaitResult(Future<Result> future) {
        try {
            return future.get(15, TimeUnit.SECONDS);
        } catch (Exception failure) {
            throw new IllegalStateException("concurrent publication did not finish", failure);
        }
    }

    private static void dropTrigger(String name) {
        jdbc.execute("DROP TRIGGER IF EXISTS " + name);
    }

    private static void stopProcess(Process process) throws Exception {
        if (process == null || !process.isAlive()) return;
        process.destroy();
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
        }
    }
}
