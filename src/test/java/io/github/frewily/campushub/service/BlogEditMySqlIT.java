package io.github.frewily.campushub.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import io.github.frewily.campushub.dto.UserDTO;
import io.github.frewily.campushub.dto.request.BlogEditRequest;
import io.github.frewily.campushub.exception.BusinessException;
import io.github.frewily.campushub.exception.ErrorCode;
import io.github.frewily.campushub.mapper.BlogMapper;
import io.github.frewily.campushub.service.impl.BlogServiceImpl;
import io.github.frewily.campushub.service.IFollowService;
import io.github.frewily.campushub.service.IUserService;
import io.github.frewily.campushub.utils.UserHolder;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.session.SqlSessionFactory;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import javax.validation.Validation;
import javax.validation.Validator;
import javax.validation.ValidatorFactory;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** Real MySQL/MyBatis XML and Spring transaction acceptance for author blog editing. */
class BlogEditMySqlIT {
    private static final long AUTHOR_ID = 1701L;
    private static final long OTHER_AUTHOR_ID = 1702L;

    @TempDir static Path directory;
    private static Process mysql;
    private static DriverManagerDataSource datasource;
    private static JdbcTemplate jdbc;
    private static AnnotationConfigApplicationContext context;
    private static BlogMapper mapper;
    private static BlogServiceImpl service;
    private static final AtomicBoolean RETURN_ZERO_AFTER_REAL_UPDATE = new AtomicBoolean();
    private static final AtomicInteger EDIT_UPDATE_CALLS = new AtomicInteger();
    private static final AtomicReference<CountDownLatch> UPDATE_REACHED = new AtomicReference<>();
    private static final AtomicReference<CountDownLatch> ALLOW_UPDATE_TO_RETURN = new AtomicReference<>();

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
                            "CREATE DATABASE blog_edit_test CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci");
                    ready = true;
                    break;
                } catch (SQLException unavailable) {
                    Thread.sleep(50);
                }
            }
            assertTrue(ready, "private MySQL did not start");

            datasource = new DriverManagerDataSource(base.replace("/?", "/blog_edit_test?"), "root", "");
            jdbc = new JdbcTemplate(datasource);
            importFreshSchemaAndRequiredMigration();

            context = new AnnotationConfigApplicationContext();
            context.register(TestConfiguration.class);
            context.refresh();
            mapper = context.getBean(BlogMapper.class);
            service = context.getBean(BlogServiceImpl.class);
            assertTrue(AopUtils.isAopProxy(service), "service must be advised by Spring transaction management");
        } catch (Exception | Error failure) {
            if (context != null) context.close();
            stopProcess(mysql);
            throw failure;
        }
    }

    private static void importFreshSchemaAndRequiredMigration() throws Exception {
        try (Connection connection = datasource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new FileSystemResource("deploy/mysql/schema.sql"));
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("db/migration/V006__allow_campus_posts_without_shop.sql"));
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("db/migration/V010__add_blog_author_withdrawal.sql"));
        }
    }

    @Configuration
    @EnableTransactionManagement(proxyTargetClass = true)
    static class TestConfiguration {
        @Bean
        DataSource dataSource() {
            return datasource;
        }

        @Bean
        SqlSessionFactory sqlSessionFactory(DataSource dataSource) throws Exception {
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            MybatisConfiguration configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true);
            configuration.addInterceptor(new EditTransactionContractInterceptor());
            factory.setConfiguration(configuration);
            factory.setMapperLocations(new ClassPathResource("mapper/BlogMapper.xml"));
            return factory.getObject();
        }

        @Bean
        MapperFactoryBean<BlogMapper> blogMapper(SqlSessionFactory sqlSessionFactory) {
            MapperFactoryBean<BlogMapper> factory = new MapperFactoryBean<>(BlogMapper.class);
            factory.setSqlSessionFactory(sqlSessionFactory);
            return factory;
        }

        @Bean
        ValidatorFactory validatorFactory() {
            return Validation.buildDefaultValidatorFactory();
        }

        @Bean
        Validator validator(ValidatorFactory validatorFactory) {
            return validatorFactory.getValidator();
        }

        @Bean
        IUserService userService() {
            return mock(IUserService.class);
        }

        @Bean
        IFollowService followService() {
            return mock(IFollowService.class);
        }

        @Bean
        StringRedisTemplate stringRedisTemplate() {
            return mock(StringRedisTemplate.class);
        }

        @Bean
        BlogServiceImpl blogService(BlogMapper blogMapper) {
            BlogServiceImpl blogService = new BlogServiceImpl();
            ReflectionTestUtils.setField(blogService, "baseMapper", blogMapper);
            return blogService;
        }

        @Bean
        DataSourceTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }
    }

    @Intercepts({
            @Signature(type = Executor.class, method = "query", args = {
                    MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class
            }),
            @Signature(type = Executor.class, method = "update", args = {MappedStatement.class, Object.class})
    })
    static class EditTransactionContractInterceptor implements Interceptor {
        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            MappedStatement statement = (MappedStatement) invocation.getArgs()[0];
            String id = statement.getId();
            boolean lockRead = id.endsWith(".findBlogForAuthorEdit") || id.endsWith(".findBlogForAuthorWithdrawal");
            boolean write = id.endsWith(".editBlogFields") || id.endsWith(".withdrawOwnedBlog");
            if (lockRead || write) {
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive(),
                        "author edit mapper call must run inside an actual Spring transaction");
                assertFalse(TransactionSynchronizationManager.isCurrentTransactionReadOnly(),
                        "author edit transaction must be writable");
            }
            if (write) EDIT_UPDATE_CALLS.incrementAndGet();
            Object result = invocation.proceed();
            if (write && RETURN_ZERO_AFTER_REAL_UPDATE.compareAndSet(true, false)) {
                assertEquals(Integer.valueOf(1), result,
                        "zero-row hook must run only after the real UPDATE changed a row");
                return Integer.valueOf(0);
            }
            if (write) {
                CountDownLatch reached = UPDATE_REACHED.get();
                CountDownLatch release = ALLOW_UPDATE_TO_RETURN.get();
                if (reached != null && release != null) {
                    reached.countDown();
                    if (!release.await(10, TimeUnit.SECONDS)) {
                        throw new AssertionError("concurrent observer did not release the real update");
                    }
                }
            }
            return result;
        }
    }

    @AfterAll
    static void stopPrivateMySql() throws Exception {
        if (context != null) context.close();
        stopProcess(mysql);
    }

    @BeforeEach
    void resetRowsAndSetAuthor() {
        dropTrigger("fail_blog_edit");
        jdbc.execute("DELETE FROM tb_blog_comments");
        jdbc.execute("DELETE FROM tb_blog");
        jdbc.execute("ALTER TABLE tb_blog_comments AUTO_INCREMENT=1");
        jdbc.execute("ALTER TABLE tb_blog AUTO_INCREMENT=1");
        RETURN_ZERO_AFTER_REAL_UPDATE.set(false);
        EDIT_UPDATE_CALLS.set(0);
        UserHolder.saveUser(user(AUTHOR_ID));
    }

    @AfterEach
    void clearHooksAndUser() {
        RETURN_ZERO_AFTER_REAL_UPDATE.set(false);
        EDIT_UPDATE_CALLS.set(0);
        UPDATE_REACHED.set(null);
        ALLOW_UPDATE_TO_RETURN.set(null);
        UserHolder.removeUser();
        dropTrigger("fail_blog_edit");
    }

    @Test
    void ownerCanEditStoreAndNonStorePostsWithoutChangingProtectedColumnsOrComments() {
        long nonStore = blog(AUTHOR_ID, null, "old-title", "old-image", "old-content");
        long commentId = comment(nonStore, "comment must remain");
        Map<String, Object> before = snapshot(nonStore);

        service.editBlog(nonStore, request(" new-title ", " /new-image.png ", " new-content "));

        Map<String, Object> after = snapshot(nonStore);
        assertEquals(AUTHOR_ID, ((Number) after.get("user_id")).longValue());
        assertNull(after.get("shop_id"));
        assertEquals(" new-title ", after.get("title"));
        assertEquals(" /new-image.png ", after.get("images"));
        assertEquals(" new-content ", after.get("content"));
        assertEquals(before.get("liked"), after.get("liked"));
        assertEquals(before.get("comments"), after.get("comments"));
        assertEquals(before.get("create_time"), after.get("create_time"));
        assertEquals("comment must remain", jdbc.queryForObject(
                "SELECT content FROM tb_blog_comments WHERE id=?", String.class, commentId));

        long store = blog(AUTHOR_ID, 31L, "store-old", "store-image", "store-content");
        service.editBlog(store, request("store-new", "store-new-image", "store-new-content"));
        assertEquals(31L, ((Number) snapshot(store).get("shop_id")).longValue());
        assertEquals("store-new", snapshot(store).get("title"));
    }

    @Test
    void exactRepeatDoesNotIssueUpdateOrChangeUpdateTime() {
        long id = blog(AUTHOR_ID, null, "same-title", "same-image", "same-content");
        jdbc.update("UPDATE tb_blog SET update_time='2020-01-02 03:04:05' WHERE id=?", id);
        Map<String, Object> before = snapshot(id);

        service.editBlog(id, request("same-title", "same-image", "same-content"));

        assertEquals(before, snapshot(id));
        assertEquals(0, EDIT_UPDATE_CALLS.get(), "exact repeat must not call the write mapper statement");
    }

    @Test
    void blankImagesBecomeEmptyOnlyForNonStoreAndInvalidIdentityOrInputLeavesRowsUnchanged() {
        long nonStore = blog(AUTHOR_ID, null, "title", "image", "content");
        service.editBlog(nonStore, request("title2", " \t ", "content2"));
        assertEquals("", snapshot(nonStore).get("images"));

        long store = blog(AUTHOR_ID, 31L, "store-title", "store-image", "store-content");
        Map<String, Object> storeBefore = snapshot(store);
        assertCode(ErrorCode.VALIDATION_FAILED,
                () -> service.editBlog(store, request("store-next", "  ", "store-next-content")));
        assertEquals(storeBefore, snapshot(store));

        Map<String, Object> before = snapshot(nonStore);
        for (UserDTO identity : Arrays.asList(null, user(null), user(0L), user(-4L))) {
            UserHolder.saveUser(identity);
            assertCode(ErrorCode.AUTHENTICATION_FAILED,
                    () -> service.editBlog(nonStore, request("unauthorized", "image", "content")));
            assertEquals(before, snapshot(nonStore));
        }
        UserHolder.saveUser(user(AUTHOR_ID));
        assertCode(ErrorCode.VALIDATION_FAILED, () -> service.editBlog(nonStore, request(" ", "", "content")));
        assertCode(ErrorCode.VALIDATION_FAILED, () -> service.editBlog(nonStore, request("title", "", "\n")));
        assertCode(ErrorCode.VALIDATION_FAILED,
                () -> service.editBlog(nonStore, request(repeat('t', 256), "", "content")));
        assertCode(ErrorCode.VALIDATION_FAILED,
                () -> service.editBlog(nonStore, request("title", repeat('i', 2049), "content")));
        assertCode(ErrorCode.VALIDATION_FAILED,
                () -> service.editBlog(nonStore, request("title", "", repeat('c', 2049))));
        assertEquals(before, snapshot(nonStore));
    }

    @Test
    void foreignAuthorAndMissingBlogReturn403And404WithoutChangingRows() {
        long id = blog(AUTHOR_ID, null, "title", "image", "content");
        Map<String, Object> before = snapshot(id);
        UserHolder.saveUser(user(OTHER_AUTHOR_ID));
        BusinessException forbidden = assertThrows(BusinessException.class,
                () -> service.editBlog(id, request("foreign", "image", "content")));
        assertEquals(ErrorCode.AUTHORIZATION_FAILED, forbidden.getErrorCode());
        assertEquals(403, forbidden.getErrorCode().getHttpStatus().value());
        assertEquals(before, snapshot(id));

        UserHolder.saveUser(user(AUTHOR_ID));
        BusinessException missing = assertThrows(BusinessException.class,
                () -> service.editBlog(999999L, request("missing", "image", "content")));
        assertEquals(ErrorCode.NOT_FOUND, missing.getErrorCode());
        assertEquals(404, missing.getErrorCode().getHttpStatus().value());
        assertEquals(before, snapshot(id));
    }

    @Test
    void databaseFailureIsGenericAndPreservesPostAndCommentRows() {
        long id = blog(AUTHOR_ID, null, "title", "image", "content");
        long commentId = comment(id, "retained comment");
        Map<String, Object> before = snapshot(id);
        jdbc.execute("CREATE TRIGGER fail_blog_edit BEFORE UPDATE ON tb_blog FOR EACH ROW "
                + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic edit failure'");

        BusinessException failure = assertThrows(BusinessException.class,
                () -> service.editBlog(id, request("changed", "new-image", "new-content")));

        assertEquals(ErrorCode.INTERNAL_ERROR, failure.getErrorCode());
        assertEquals(500, failure.getErrorCode().getHttpStatus().value());
        assertNull(failure.getCause(), "database details must not be exposed as the public cause");
        assertEquals(before, snapshot(id));
        assertEquals("retained comment", jdbc.queryForObject(
                "SELECT content FROM tb_blog_comments WHERE id=?", String.class, commentId));
    }

    @Test
    void zeroReportedAfterRealUpdateTriggers422AndRollsBackTheActualWrite() {
        long id = blog(AUTHOR_ID, null, "before", "old-image", "old-content");
        Map<String, Object> before = snapshot(id);
        RETURN_ZERO_AFTER_REAL_UPDATE.set(true);

        BusinessException failure = assertThrows(BusinessException.class,
                () -> service.editBlog(id, request("after", "new-image", "new-content")));

        assertEquals(ErrorCode.OPERATION_FAILED, failure.getErrorCode());
        assertEquals(422, failure.getErrorCode().getHttpStatus().value());
        assertEquals(before, snapshot(id), "the UPDATE really executed, then transaction rollback restored its row");
        assertFalse(RETURN_ZERO_AFTER_REAL_UPDATE.get());
    }

    @Test
    void independentConnectionCannotReadUncommittedEditButSeesItAfterCommit() throws Exception {
        final long id = blog(AUTHOR_ID, null, "before", "old-image", "old-content");
        final CountDownLatch updateReached = new CountDownLatch(1);
        final CountDownLatch allowReturn = new CountDownLatch(1);
        UPDATE_REACHED.set(updateReached);
        ALLOW_UPDATE_TO_RETURN.set(allowReturn);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<?> edit = null;
        try {
            edit = executor.submit(() -> {
                UserHolder.saveUser(user(AUTHOR_ID));
                try {
                    service.editBlog(id, request("after", "new-image", "new-content"));
                } finally {
                    UserHolder.removeUser();
                }
            });
            assertTrue(updateReached.await(10, TimeUnit.SECONDS), "real UPDATE did not reach the transaction hook");
            assertEquals("before", jdbc.queryForObject("SELECT title FROM tb_blog WHERE id=?", String.class, id),
                    "a separate connection must see the committed old version while UPDATE is uncommitted");
            allowReturn.countDown();
            edit.get(10, TimeUnit.SECONDS);
            assertEquals("after", jdbc.queryForObject("SELECT title FROM tb_blog WHERE id=?", String.class, id));
        } finally {
            allowReturn.countDown();
            UPDATE_REACHED.set(null);
            ALLOW_UPDATE_TO_RETURN.set(null);
            if (edit != null && !edit.isDone()) edit.cancel(true);
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS), "edit worker did not stop");
        }
    }

    @Test
    void withdrawalRetainsContentAndDescendantsAndRepeatDoesNotWrite() {
        long id = blog(AUTHOR_ID, 31L, "title", "image", "content");
        long commentId = comment(id, "retained original comment");
        Map<String, Object> before = snapshot(id);
        service.withdrawBlog(id);
        Map<String, Object> after = snapshot(id);
        Map<String, Object> protectedBefore = new java.util.HashMap<>(before);
        Map<String, Object> protectedAfter = new java.util.HashMap<>(after);
        protectedBefore.remove("update_time");
        protectedAfter.remove("update_time");
        assertEquals(protectedBefore, protectedAfter, "withdrawal must preserve original columns except update_time");
        assertEquals(1, jdbc.queryForObject("SELECT withdrawn FROM tb_blog WHERE id=?", Integer.class, id));
        assertEquals("retained original comment", jdbc.queryForObject(
                "SELECT content FROM tb_blog_comments WHERE id=?", String.class, commentId));
        jdbc.execute("CREATE TRIGGER fail_blog_edit BEFORE UPDATE ON tb_blog FOR EACH ROW "
                + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='repeat must not update'");
        service.withdrawBlog(id);
        assertEquals(after, snapshot(id));
        assertEquals(1, EDIT_UPDATE_CALLS.get());
        UserHolder.saveUser(user(OTHER_AUTHOR_ID));
        assertCode(ErrorCode.AUTHORIZATION_FAILED, () -> service.withdrawBlog(id));
    }

    @Test
    void generatedReadsAndUpdatesExcludeWithdrawnPostAndCustomEditAndLikesReturn404() {
        long id = blog(AUTHOR_ID, null, "hidden", "", "hidden body");
        long visible = blog(AUTHOR_ID, null, "visible", "", "visible body");
        service.withdrawBlog(id);
        assertNull(mapper.selectById(id));
        assertEquals(1, mapper.selectCount(new QueryWrapper<io.github.frewily.campushub.entity.Blog>()));
        assertEquals(visible, mapper.selectList(new QueryWrapper<io.github.frewily.campushub.entity.Blog>()
                .eq("user_id", AUTHOR_ID)).get(0).getId());
        assertEquals(0, mapper.update(null, new UpdateWrapper<io.github.frewily.campushub.entity.Blog>()
                .eq("id", id).set("title", "must not overwrite")));
        assertCode(ErrorCode.NOT_FOUND, () -> service.editBlog(id, request("x", "", "x")));
        assertCode(ErrorCode.NOT_FOUND, () -> service.queryBlogById(id));
        assertCode(ErrorCode.NOT_FOUND, () -> service.queryBlogLikes(id));
        assertCode(ErrorCode.NOT_FOUND, () -> service.likeBlog(id));
        assertEquals("hidden", snapshot(id).get("title"));
    }

    @Test
    void withdrawalAuthorizationMissingRowAndSqlFailureDoNotChangeStoredRows() {
        long id = blog(AUTHOR_ID, null, "title", "", "body");
        Map<String, Object> before = snapshot(id);
        UserHolder.saveUser(user(OTHER_AUTHOR_ID));
        assertCode(ErrorCode.AUTHORIZATION_FAILED, () -> service.withdrawBlog(id));
        UserHolder.saveUser(user(AUTHOR_ID));
        assertCode(ErrorCode.NOT_FOUND, () -> service.withdrawBlog(Long.MAX_VALUE));
        jdbc.execute("CREATE TRIGGER fail_blog_edit BEFORE UPDATE ON tb_blog FOR EACH ROW "
                + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic withdrawal failure'");
        BusinessException failure = assertThrows(BusinessException.class, () -> service.withdrawBlog(id));
        assertEquals(ErrorCode.INTERNAL_ERROR, failure.getErrorCode());
        assertNull(failure.getCause());
        assertEquals(before, snapshot(id));
        assertEquals(0, jdbc.queryForObject("SELECT withdrawn FROM tb_blog WHERE id=?", Integer.class, id));
    }

    @Test
    void zeroReportedAfterRealWithdrawalRollsBackFlagAndTimestamp() {
        long id = blog(AUTHOR_ID, null, "title", "", "body");
        Map<String, Object> before = snapshot(id);
        RETURN_ZERO_AFTER_REAL_UPDATE.set(true);
        assertCode(ErrorCode.OPERATION_FAILED, () -> service.withdrawBlog(id));
        assertEquals(0, jdbc.queryForObject("SELECT withdrawn FROM tb_blog WHERE id=?", Integer.class, id));
        assertEquals(before, snapshot(id));
        assertFalse(RETURN_ZERO_AFTER_REAL_UPDATE.get());
    }

    @Test
    void currentLockSerializesConcurrentRepeatAndEditBehindUncommittedWithdrawal() throws Exception {
        long id = blog(AUTHOR_ID, null, "title", "", "body");
        CountDownLatch reached = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        UPDATE_REACHED.set(reached);
        ALLOW_UPDATE_TO_RETURN.set(release);
        ExecutorService executor = Executors.newFixedThreadPool(3);
        try {
            Future<?> first = executor.submit(() -> asAuthor(() -> service.withdrawBlog(id)));
            assertTrue(reached.await(10, TimeUnit.SECONDS));
            assertNotNull(mapper.selectById(id), "ordinary independent read sees committed pre-withdrawal row");
            Future<?> repeat = executor.submit(() -> asAuthor(() -> service.withdrawBlog(id)));
            Future<?> edit = executor.submit(() -> asAuthor(() ->
                    assertCode(ErrorCode.NOT_FOUND, () -> service.editBlog(id, request("x", "", "x")))));
            assertThrows(TimeoutException.class, () -> repeat.get(200, TimeUnit.MILLISECONDS));
            assertThrows(TimeoutException.class, () -> edit.get(200, TimeUnit.MILLISECONDS));
            release.countDown();
            first.get(10, TimeUnit.SECONDS);
            repeat.get(10, TimeUnit.SECONDS);
            edit.get(10, TimeUnit.SECONDS);
            assertNull(mapper.selectById(id));
            assertEquals(1, EDIT_UPDATE_CALLS.get());
            assertEquals("title", snapshot(id).get("title"));
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void withdrawalMigrationRepeatPreservesExistingVisibleAndWithdrawnRows() throws Exception {
        long visible = blog(AUTHOR_ID, null, "visible", "", "body");
        long hidden = blog(AUTHOR_ID, null, "hidden", "", "body");
        service.withdrawBlog(hidden);
        try (Connection connection = datasource.getConnection()) {
            ClassPathResource migration = new ClassPathResource("db/migration/V010__add_blog_author_withdrawal.sql");
            ScriptUtils.executeSqlScript(connection, migration);
            ScriptUtils.executeSqlScript(connection, migration);
        }
        assertEquals(0, jdbc.queryForObject("SELECT withdrawn FROM tb_blog WHERE id=?", Integer.class, visible));
        assertEquals(1, jdbc.queryForObject("SELECT withdrawn FROM tb_blog WHERE id=?", Integer.class, hidden));
        assertEquals("tinyint unsigned", jdbc.queryForObject("SELECT COLUMN_TYPE FROM INFORMATION_SCHEMA.COLUMNS "
                + "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='tb_blog' AND COLUMN_NAME='withdrawn'", String.class));
    }

    private static void asAuthor(Runnable action) {
        UserHolder.saveUser(user(AUTHOR_ID));
        try { action.run(); } finally { UserHolder.removeUser(); }
    }

    @Test
    void withdrawalMigrationAddsVisibleDefaultToPopulatedLegacySchemaWithoutRewritingContent() throws Exception {
        try (Connection connection = datasource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE blog_withdrawal_legacy CHARACTER SET utf8mb4");
            statement.execute("USE blog_withdrawal_legacy");
            ScriptUtils.executeSqlScript(connection, new FileSystemResource("deploy/mysql/schema.sql"));
            statement.execute("INSERT INTO tb_blog(id,shop_id,user_id,title,images,content,liked,comments) "
                    + "VALUES (91,31,1701,'legacy title','legacy image','legacy content',7,3)");
            ClassPathResource migration = new ClassPathResource("db/migration/V010__add_blog_author_withdrawal.sql");
            ScriptUtils.executeSqlScript(connection, migration);
            ScriptUtils.executeSqlScript(connection, migration);
            try (java.sql.ResultSet row = statement.executeQuery("SELECT withdrawn,title,images,content,liked,comments "
                    + "FROM tb_blog WHERE id=91")) {
                assertTrue(row.next());
                assertEquals(0, row.getInt("withdrawn"));
                assertEquals("legacy title", row.getString("title"));
                assertEquals("legacy image", row.getString("images"));
                assertEquals("legacy content", row.getString("content"));
                assertEquals(7, row.getInt("liked"));
                assertEquals(3, row.getInt("comments"));
                assertFalse(row.next());
            }
        }
    }

    private static long blog(long authorId, Long shopId, String title, String images, String content) {
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO tb_blog (shop_id,user_id,title,images,content,liked,comments) VALUES (?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setObject(1, shopId);
            statement.setLong(2, authorId);
            statement.setString(3, title);
            statement.setString(4, images);
            statement.setString(5, content);
            statement.setInt(6, 7);
            statement.setInt(7, 3);
            return statement;
        }, key);
        return key.getKey().longValue();
    }

    private static long comment(long blogId, String content) {
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO tb_blog_comments (user_id,blog_id,parent_id,answer_id,content,liked,status) "
                            + "VALUES (?,?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, AUTHOR_ID);
            statement.setLong(2, blogId);
            statement.setLong(3, 0L);
            statement.setLong(4, 0L);
            statement.setString(5, content);
            statement.setInt(6, 2);
            statement.setInt(7, 0);
            return statement;
        }, key);
        return key.getKey().longValue();
    }

    private static Map<String, Object> snapshot(long id) {
        return jdbc.queryForMap("SELECT user_id,shop_id,title,images,content,liked,comments,create_time,update_time "
                + "FROM tb_blog WHERE id=?", id);
    }

    private static BlogEditRequest request(String title, String images, String content) {
        BlogEditRequest request = new BlogEditRequest();
        request.setTitle(title);
        request.setImages(images);
        request.setContent(content);
        return request;
    }

    private static UserDTO user(Long id) {
        UserDTO user = new UserDTO();
        user.setId(id);
        return user;
    }

    private static void assertCode(ErrorCode expected, Runnable operation) {
        BusinessException failure = assertThrows(BusinessException.class, operation::run);
        assertEquals(expected, failure.getErrorCode());
    }

    private static String repeat(char value, int count) {
        char[] chars = new char[count];
        Arrays.fill(chars, value);
        return new String(chars);
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
