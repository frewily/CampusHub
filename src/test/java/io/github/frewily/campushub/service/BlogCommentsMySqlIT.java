package io.github.frewily.campushub.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import io.github.frewily.campushub.dto.UserDTO;
import io.github.frewily.campushub.dto.request.BlogCommentCreateRequest;
import io.github.frewily.campushub.dto.request.BlogCommentPageRequest;
import io.github.frewily.campushub.dto.request.BlogCommentReplyRequest;
import io.github.frewily.campushub.dto.request.BlogCommentHideRequest;
import io.github.frewily.campushub.dto.response.BlogCommentItem;
import io.github.frewily.campushub.dto.response.BlogCommentPage;
import io.github.frewily.campushub.dto.response.BlogCommentHideAuditResponse;
import io.github.frewily.campushub.dto.request.BlogCommentHideAuditPageRequest;
import io.github.frewily.campushub.dto.response.BlogCommentHideAuditPage;
import io.github.frewily.campushub.dto.response.BlogReplyHideAuditResponse;
import io.github.frewily.campushub.dto.response.BlogReplyHideAuditPage;
import io.github.frewily.campushub.exception.BusinessException;
import io.github.frewily.campushub.exception.ErrorCode;
import io.github.frewily.campushub.mapper.BlogCommentsMapper;
import io.github.frewily.campushub.service.impl.BlogCommentsServiceImpl;
import io.github.frewily.campushub.utils.UserHolder;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import javax.validation.Validation;
import javax.validation.Validator;
import javax.validation.ValidatorFactory;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Real MySQL, MyBatis-Plus XML and Spring transaction acceptance for blog comments and replies. */
class BlogCommentsMySqlIT {
    private static final long AUTHOR_ID = 701L;
    private static final long LARGE_ID = 9007199254740993L;

    @TempDir static Path directory;
    private static Process mysql;
    private static DriverManagerDataSource datasource;
    private static JdbcTemplate jdbc;
    private static AnnotationConfigApplicationContext context;
    private static BlogCommentsMapper mapper;
    private static BlogCommentsServiceImpl service;
    private static final AtomicReference<Runnable> AFTER_VISIBLE_ROOT_QUERY = new AtomicReference<>();
    private static final AtomicReference<Runnable> AFTER_OWNED_REPLY_QUERY = new AtomicReference<>();
    private static final AtomicReference<Runnable> AFTER_OWNED_COMMENT_QUERY = new AtomicReference<>();
    private static final AtomicReference<Runnable> AFTER_MODERATABLE_COMMENT_QUERY = new AtomicReference<>();
    private static final AtomicReference<Runnable> AFTER_MODERATABLE_REPLY_QUERY = new AtomicReference<>();
    private static final AtomicReference<Callable<Integer>> AFTER_HIDE_AUDIT_INSERT = new AtomicReference<>();
    private static final AtomicReference<Callable<Integer>> AFTER_REPLY_HIDE_AUDIT_INSERT = new AtomicReference<>();
    private static final AtomicReference<Callable<Integer>> AFTER_DIRECT_REPLY_STATUS_UPDATE = new AtomicReference<>();
    private static final AtomicReference<Callable<Integer>> AFTER_DIRECT_REPLY_COUNTER_UPDATE = new AtomicReference<>();
    private static final ThreadLocal<Boolean> EXPECT_READ_ONLY_REPLY_LIST =
            ThreadLocal.withInitial(() -> Boolean.FALSE);
    private static final ThreadLocal<Boolean> EXPECT_NO_MAPPER_SQL =
            ThreadLocal.withInitial(() -> Boolean.FALSE);

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
                            "CREATE DATABASE blog_comments_test CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci");
                    ready = true;
                    break;
                } catch (SQLException unavailable) {
                    Thread.sleep(50);
                }
            }
            assertTrue(ready, "private MySQL did not start");

            datasource = new DriverManagerDataSource(base.replace("/?", "/blog_comments_test?"), "root", "");
            jdbc = new JdbcTemplate(datasource);
            importSchemaAndMigrations();

            context = new AnnotationConfigApplicationContext();
            context.register(TestConfiguration.class);
            context.refresh();
            mapper = context.getBean(BlogCommentsMapper.class);
            service = context.getBean(BlogCommentsServiceImpl.class);
            assertTrue(AopUtils.isAopProxy(service), "service must be advised by Spring transaction management");
        } catch (Exception | Error failure) {
            if (context != null) context.close();
            stopProcess(mysql);
            throw failure;
        }
    }

    private static void importSchemaAndMigrations() throws Exception {
        try (Connection connection = datasource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new FileSystemResource("deploy/mysql/schema.sql"));
            ClassPathResource nullableBlog = new ClassPathResource(
                    "db/migration/V006__allow_campus_posts_without_shop.sql");
            ClassPathResource commentIndex = new ClassPathResource(
                    "db/migration/V007__add_blog_comment_page_index.sql");
            ScriptUtils.executeSqlScript(connection, nullableBlog);
            ScriptUtils.executeSqlScript(connection, nullableBlog);
            ScriptUtils.executeSqlScript(connection, commentIndex);
            ScriptUtils.executeSqlScript(connection, commentIndex);
            ClassPathResource hideAudit = new ClassPathResource("db/migration/V008__add_blog_comment_hide_audit.sql");
            ScriptUtils.executeSqlScript(connection, hideAudit);
            ScriptUtils.executeSqlScript(connection, hideAudit);
            ClassPathResource auditPageIndex = new ClassPathResource(
                    "db/migration/V009__add_blog_comment_hide_audit_page_index.sql");
            ScriptUtils.executeSqlScript(connection, auditPageIndex);
            ScriptUtils.executeSqlScript(connection, auditPageIndex);
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("db/migration/V010__add_blog_author_withdrawal.sql"));
            ClassPathResource replyHideAudit = new ClassPathResource(
                    "db/migration/V013__add_blog_reply_hide_audit.sql");
            ScriptUtils.executeSqlScript(connection, replyHideAudit);
            ScriptUtils.executeSqlScript(connection, replyHideAudit);
            ClassPathResource replyAuditPageIndex = new ClassPathResource(
                    "db/migration/V014__add_blog_reply_hide_audit_page_index.sql");
            ScriptUtils.executeSqlScript(connection, replyAuditPageIndex);
            ScriptUtils.executeSqlScript(connection, replyAuditPageIndex);
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
            configuration.addInterceptor(new ReadTransactionContractInterceptor());
            factory.setConfiguration(configuration);
            factory.setMapperLocations(new ClassPathResource("mapper/BlogCommentsMapper.xml"));
            return factory.getObject();
        }

        @Bean
        MapperFactoryBean<BlogCommentsMapper> blogCommentsMapper(SqlSessionFactory sqlSessionFactory) {
            MapperFactoryBean<BlogCommentsMapper> factory = new MapperFactoryBean<>(BlogCommentsMapper.class);
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
        BlogCommentsServiceImpl blogCommentsService(Validator validator) {
            return new BlogCommentsServiceImpl(validator);
        }

        @Bean
        DataSourceTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }
    }

    @Intercepts({@Signature(type = Executor.class, method = "query", args = {
            MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class
    }), @Signature(type = Executor.class, method = "update", args = {MappedStatement.class, Object.class})})
    static class ReadTransactionContractInterceptor implements Interceptor {
        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            MappedStatement statement = (MappedStatement) invocation.getArgs()[0];
            String id = statement.getId();
            if (EXPECT_NO_MAPPER_SQL.get() && id.startsWith(BlogCommentsMapper.class.getName() + ".")) {
                fail("transaction gate must reject before the first mapper statement");
            }
            if (id.endsWith(".findBlog") || id.endsWith(".findFirstLevelComments")
                    || id.endsWith(".findDirectReplies")
                    || id.endsWith(".findFirstLevelHideAuditDetails")
                    || id.endsWith(".findDirectReplyHideAuditDetails")
                    || id.endsWith(".findHideAuditsByBlog")
                    || id.endsWith(".findReplyHideAuditsByBlog")
                    || (id.endsWith(".findVisibleRoot") && EXPECT_READ_ONLY_REPLY_LIST.get())) {
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive(),
                        "comment listing must run inside a Spring transaction");
                assertTrue(TransactionSynchronizationManager.isCurrentTransactionReadOnly(),
                        "comment listing transaction must be read-only");
                assertEquals(Integer.valueOf(Connection.TRANSACTION_REPEATABLE_READ),
                        TransactionSynchronizationManager.getCurrentTransactionIsolationLevel(),
                        "comment listing must use a repeatable-read snapshot");
            }
            Object result = invocation.proceed();
            if (id.endsWith(".findVisibleRoot")) {
                Runnable hook = AFTER_VISIBLE_ROOT_QUERY.getAndSet(null);
                if (hook != null) hook.run();
            }
            if (id.endsWith(".findOwnedDirectReply")) {
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                assertFalse(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
                Runnable hook = AFTER_OWNED_REPLY_QUERY.getAndSet(null);
                if (hook != null) hook.run();
            }
            if (id.endsWith(".findOwnedFirstLevelComment")) {
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                assertFalse(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
                Runnable hook = AFTER_OWNED_COMMENT_QUERY.getAndSet(null);
                if (hook != null) hook.run();
            }
            if (id.endsWith(".findModeratableFirstLevelComment")) {
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                assertFalse(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
                Runnable hook = AFTER_MODERATABLE_COMMENT_QUERY.getAndSet(null);
                if (hook != null) hook.run();
            }
            if (id.endsWith(".findModeratableDirectReply")) {
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                assertFalse(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
                Runnable hook = AFTER_MODERATABLE_REPLY_QUERY.getAndSet(null);
                if (hook != null) hook.run();
            }
            if (id.endsWith(".insertFirstLevelHideAudit")) {
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                assertFalse(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
                Callable<Integer> hook = AFTER_HIDE_AUDIT_INSERT.getAndSet(null);
                if (hook != null) {
                    assertEquals(Integer.valueOf(1), result, "hook requires a successful real audit INSERT");
                    return hook.call();
                }
            }
            if (id.endsWith(".insertDirectReplyHideAudit")) {
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                assertFalse(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
                Callable<Integer> hook = AFTER_REPLY_HIDE_AUDIT_INSERT.getAndSet(null);
                if (hook != null) {
                    assertEquals(Integer.valueOf(1), result, "hook requires a successful real reply audit INSERT");
                    return hook.call();
                }
            }
            if (id.endsWith(".hideDirectReply")) {
                Callable<Integer> hook = AFTER_DIRECT_REPLY_STATUS_UPDATE.getAndSet(null);
                if (hook != null) {
                    assertEquals(Integer.valueOf(1), result, "hook requires a successful real status UPDATE");
                    return hook.call();
                }
            }
            if (id.endsWith(".decrementCommentCount")) {
                Callable<Integer> hook = AFTER_DIRECT_REPLY_COUNTER_UPDATE.getAndSet(null);
                if (hook != null) {
                    assertEquals(Integer.valueOf(1), result, "hook requires a successful real counter UPDATE");
                    return hook.call();
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
        dropTrigger("fail_comment_insert");
        dropTrigger("fail_blog_counter_update");
        dropTrigger("remove_blog_before_comment_insert");
        dropTrigger("fail_reply_update");
        dropTrigger("remove_blog_before_reply_update");
        dropTrigger("fail_hide_audit_insert");
        dropTrigger("fail_reply_hide_status_update");
        dropTrigger("fail_reply_hide_audit_insert");
        dropTrigger("reject_repeat_reply_comment_update");
        dropTrigger("reject_repeat_reply_blog_update");
        dropTrigger("reject_repeat_reply_audit_update");
        jdbc.execute("DELETE FROM tb_blog_reply_hide_audit");
        jdbc.execute("DELETE FROM tb_blog_comment_hide_audit");
        jdbc.execute("DELETE FROM tb_blog_comments");
        jdbc.execute("DELETE FROM tb_blog");
        jdbc.execute("ALTER TABLE tb_blog_comments AUTO_INCREMENT=1");
        jdbc.execute("ALTER TABLE tb_blog AUTO_INCREMENT=1");
        UserHolder.saveUser(user(AUTHOR_ID));
    }

    @AfterEach
    void clearUser() {
        AFTER_VISIBLE_ROOT_QUERY.set(null);
        AFTER_OWNED_REPLY_QUERY.set(null);
        AFTER_OWNED_COMMENT_QUERY.set(null);
        AFTER_MODERATABLE_COMMENT_QUERY.set(null);
        AFTER_MODERATABLE_REPLY_QUERY.set(null);
        AFTER_HIDE_AUDIT_INSERT.set(null);
        AFTER_REPLY_HIDE_AUDIT_INSERT.set(null);
        AFTER_DIRECT_REPLY_STATUS_UPDATE.set(null);
        AFTER_DIRECT_REPLY_COUNTER_UPDATE.set(null);
        EXPECT_READ_ONLY_REPLY_LIST.remove();
        EXPECT_NO_MAPPER_SQL.remove();
        UserHolder.removeUser();
        dropTrigger("fail_comment_insert");
        dropTrigger("fail_blog_counter_update");
        dropTrigger("remove_blog_before_comment_insert");
        dropTrigger("fail_reply_update");
        dropTrigger("remove_blog_before_reply_update");
        dropTrigger("fail_hide_audit_insert");
        dropTrigger("fail_reply_hide_status_update");
        dropTrigger("fail_reply_hide_audit_insert");
        dropTrigger("reject_repeat_reply_comment_update");
        dropTrigger("reject_repeat_reply_blog_update");
        dropTrigger("reject_repeat_reply_audit_update");
    }

    @Test
    void migrationIsIdempotentAndCreatesTheExpectedCompositeIndex() {
        List<String> columns = jdbc.queryForList(
                "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.STATISTICS WHERE TABLE_SCHEMA=DATABASE() "
                        + "AND TABLE_NAME='tb_blog_comments' AND INDEX_NAME='idx_blog_comments_page' "
                        + "ORDER BY SEQ_IN_INDEX", String.class);
        assertEquals(Arrays.asList("blog_id", "parent_id", "answer_id", "status", "id"), columns);
    }

    @Test
    void directReplyHideMigrationRepeatsPreservesRowsAndHasExpectedUniqueAuditShape() throws Exception {
        long blogId = blog(AUTHOR_ID, 1);
        long root = comment(blogId, AUTHOR_ID, 0, 0, 0, "migration root");
        long reply = comment(blogId, AUTHOR_ID + 1, root, root, 0, "migration reply");
        service.hideReply(reply, hideRequest("migration receipt"));
        ClassPathResource migration = new ClassPathResource("db/migration/V013__add_blog_reply_hide_audit.sql");
        try (Connection connection = datasource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, migration);
            ScriptUtils.executeSqlScript(connection, migration);
        }
        assertEquals(1, replyAuditCount(reply));
        assertEquals(Arrays.asList("id", "reply_id", "blog_id", "root_comment_id", "operator_user_id", "reason",
                        "from_status", "to_status", "create_time"), jdbc.queryForList(
                "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA=DATABASE() "
                        + "AND TABLE_NAME='tb_blog_reply_hide_audit' ORDER BY ORDINAL_POSITION", String.class));
        assertEquals(Integer.valueOf(0), jdbc.queryForObject(
                "SELECT NON_UNIQUE FROM INFORMATION_SCHEMA.STATISTICS WHERE TABLE_SCHEMA=DATABASE() "
                        + "AND TABLE_NAME='tb_blog_reply_hide_audit' "
                        + "AND INDEX_NAME='uk_blog_reply_hide_audit_reply'", Integer.class));
        assertEquals(Collections.singletonList("reply_id"), jdbc.queryForList(
                "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.STATISTICS WHERE TABLE_SCHEMA=DATABASE() "
                        + "AND TABLE_NAME='tb_blog_reply_hide_audit' "
                        + "AND INDEX_NAME='uk_blog_reply_hide_audit_reply' ORDER BY SEQ_IN_INDEX", String.class));
    }

    @Test
    void createUsesRealRowsAndCoalescesLegacyNullCounter() {
        long blogId = blog(AUTHOR_ID, null);
        BlogCommentItem item = service.createComment(createRequest(blogId, "first-level comment"));

        assertNotNull(item.getId());
        assertEquals(Long.toString(blogId), item.getBlogId());
        assertEquals(Long.toString(AUTHOR_ID), item.getUserId());
        assertEquals("first-level comment", item.getContent());
        assertNotNull(item.getCreateTime());
        assertEquals(1, countComments(blogId));
        assertEquals(Integer.valueOf(1), blogCounter(blogId));
        assertEquals(0L, jdbc.queryForObject(
                "SELECT parent_id + answer_id + liked + status FROM tb_blog_comments WHERE id=?",
                Long.class, Long.valueOf(item.getId())));
    }

    @Test
    void commentInsertAndCounterFailuresRollbackTheWholeTransaction() {
        long blogId = blog(AUTHOR_ID, 4);
        jdbc.execute("CREATE TRIGGER fail_comment_insert BEFORE INSERT ON tb_blog_comments FOR EACH ROW "
                + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic insert failure'");
        BusinessException insertFailure = assertThrows(BusinessException.class,
                () -> service.createComment(createRequest(blogId, "must roll back")));
        assertEquals(ErrorCode.INTERNAL_ERROR, insertFailure.getErrorCode());
        assertNull(insertFailure.getCause(), "driver details must not be exposed as the public cause");
        assertEquals(0, countComments(blogId));
        assertEquals(Integer.valueOf(4), blogCounter(blogId));

        dropTrigger("fail_comment_insert");
        jdbc.execute("CREATE TRIGGER remove_blog_before_comment_insert BEFORE INSERT ON tb_blog_comments "
                + "FOR EACH ROW DELETE FROM tb_blog WHERE id=NEW.blog_id");
        BusinessException affectedRowsFailure = assertThrows(BusinessException.class,
                () -> service.createComment(createRequest(blogId, "zero-row counter update")));
        assertEquals(ErrorCode.OPERATION_FAILED, affectedRowsFailure.getErrorCode());
        assertEquals(422, affectedRowsFailure.getErrorCode().getHttpStatus().value());
        assertEquals(0, countComments(blogId));
        assertEquals(Integer.valueOf(4), blogCounter(blogId), "the trigger's blog deletion must roll back");

        dropTrigger("remove_blog_before_comment_insert");
        jdbc.execute("CREATE TRIGGER fail_blog_counter_update BEFORE UPDATE ON tb_blog FOR EACH ROW "
                + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic counter failure'");
        BusinessException counterFailure = assertThrows(BusinessException.class,
                () -> service.createComment(createRequest(blogId, "insert must roll back")));
        assertEquals(ErrorCode.INTERNAL_ERROR, counterFailure.getErrorCode());
        assertNull(counterFailure.getCause(), "driver details must not be exposed as the public cause");
        assertEquals(0, countComments(blogId), "the inserted comment must roll back with the counter update");
        assertEquals(Integer.valueOf(4), blogCounter(blogId));
    }

    @Test
    void concurrentCreatorsUseTheirOwnThreadLocalAuthorsAndKeepCounterAccurate() throws Exception {
        final long blogId = blog(AUTHOR_ID, 0);
        final int writers = 12;
        final CountDownLatch ready = new CountDownLatch(writers);
        final CountDownLatch start = new CountDownLatch(1);
        // Each participant must reach ready before release; a smaller pool would queue half the participants.
        ExecutorService executor = Executors.newFixedThreadPool(writers);
        List<Future<BlogCommentItem>> results = new ArrayList<>();
        try {
            for (int i = 0; i < writers; i++) {
                final long authorId = 800L + i;
                results.add(executor.submit(new Callable<BlogCommentItem>() {
                    @Override
                    public BlogCommentItem call() throws Exception {
                        UserHolder.saveUser(user(authorId));
                        ready.countDown();
                        try {
                            assertTrue(start.await(10, TimeUnit.SECONDS), "concurrent start was not released");
                            return service.createComment(createRequest(blogId, "writer-" + authorId));
                        } finally {
                            UserHolder.removeUser();
                        }
                    }
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS), "writers did not become ready");
            start.countDown();
            List<String> seenAuthors = new ArrayList<>();
            for (Future<BlogCommentItem> result : results) {
                BlogCommentItem item = result.get(15, TimeUnit.SECONDS);
                seenAuthors.add(item.getUserId());
            }
            for (int i = 0; i < writers; i++) assertTrue(seenAuthors.contains(Long.toString(800L + i)));
            assertEquals(writers, countComments(blogId));
            assertEquals(Integer.valueOf(writers), blogCounter(blogId));
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS), "comment writers did not stop");
        }
    }

    @Test
    void listingIncludesOnlyVisibleFirstLevelCommentsAndPreservesNullableLegacyStatus() {
        long blogId = blog(AUTHOR_ID, 0);
        long visible = comment(blogId, AUTHOR_ID, 0, 0, 0, "visible");
        long reported = comment(blogId, AUTHOR_ID, 0, 0, 1, "reported");
        long hidden = comment(blogId, AUTHOR_ID, 0, 0, 2, "hidden");
        long legacy = comment(blogId, AUTHOR_ID, 0, 0, null, "legacy null status");
        comment(blogId, AUTHOR_ID, visible, visible, 0, "reply");
        comment(blogId, AUTHOR_ID, 0, visible, 0, "answer-only legacy reply");
        long otherBlog = blog(AUTHOR_ID, 0);
        comment(otherBlog, AUTHOR_ID, 0, 0, 0, "other blog");
        assertEquals(Integer.valueOf(1), mapper.selectById(reported).getStatus());
        assertEquals(Integer.valueOf(2), mapper.selectById(hidden).getStatus());
        assertNull(mapper.selectById(legacy).getStatus());

        BlogCommentPage page = service.listComments(blogId, pageRequest(null, 20));
        assertEquals(Collections.singletonList(Long.toString(visible)), itemIds(page));
        assertEquals(Collections.singletonList("visible"), itemContents(page));
        assertFalse(page.isHasNext());
        assertNull(page.getNextBeforeId());
        // A foreign comment's cursor is only a boundary; it must not remove the blog predicate.
        assertEquals(Collections.singletonList(Long.toString(visible)),
                itemIds(service.listComments(blogId, pageRequest(Long.toString(legacy + 100), 50))));
    }

    @Test
    void cursorPagesAreBoundedAndNewerInsertsDoNotDuplicateOlderResults() {
        long blogId = blog(AUTHOR_ID, 0);
        for (int i = 1; i <= 5; i++) comment(blogId, AUTHOR_ID, 0, 0, 0, "comment-" + i);

        BlogCommentPage first = service.listComments(blogId, pageRequest(null, 2));
        assertEquals(Arrays.asList("5", "4"), itemIds(first));
        assertTrue(first.isHasNext());
        assertEquals("4", first.getNextBeforeId());

        comment(blogId, AUTHOR_ID, 0, 0, 0, "newer-after-first-page");
        BlogCommentPage second = service.listComments(blogId, pageRequest(first.getNextBeforeId(), 2));
        assertEquals(Arrays.asList("3", "2"), itemIds(second));
        assertTrue(second.isHasNext());
        assertEquals("2", second.getNextBeforeId());

        BlogCommentPage third = service.listComments(blogId, pageRequest(second.getNextBeforeId(), 2));
        assertEquals(Collections.singletonList("1"), itemIds(third));
        assertFalse(third.isHasNext());
        assertNull(third.getNextBeforeId());
    }

    @Test
    void missingBlogMissingAuthorAndInvalidRequestsReturnTheirTypedErrors() {
        long blogId = blog(AUTHOR_ID, 0);
        BusinessException missingBlog = assertThrows(BusinessException.class,
                () -> service.createComment(createRequest(999999L, "missing blog")));
        assertEquals(ErrorCode.NOT_FOUND, missingBlog.getErrorCode());

        UserHolder.removeUser();
        BusinessException missingAuthor = assertThrows(BusinessException.class,
                () -> service.createComment(createRequest(blogId, "no author")));
        assertEquals(ErrorCode.AUTHENTICATION_FAILED, missingAuthor.getErrorCode());
        UserHolder.saveUser(user(AUTHOR_ID));

        BusinessException blankContent = assertThrows(BusinessException.class,
                () -> service.createComment(createRequest(blogId, "   ")));
        assertEquals(ErrorCode.VALIDATION_FAILED, blankContent.getErrorCode());
        BusinessException tooLong = assertThrows(BusinessException.class,
                () -> service.createComment(createRequest(blogId, String.join("", Collections.nCopies(256, "x")))));
        assertEquals(ErrorCode.VALIDATION_FAILED, tooLong.getErrorCode());
        BusinessException invalidSize = assertThrows(BusinessException.class,
                () -> service.listComments(blogId, pageRequest(null, 0)));
        assertEquals(ErrorCode.VALIDATION_FAILED, invalidSize.getErrorCode());
        BusinessException missingListBlog = assertThrows(BusinessException.class,
                () -> service.listComments(999999L, pageRequest(null, 20)));
        assertEquals(ErrorCode.NOT_FOUND, missingListBlog.getErrorCode());
        BusinessException invalidBlogId = assertThrows(BusinessException.class,
                () -> service.listComments(0L, pageRequest(null, 20)));
        assertEquals(ErrorCode.VALIDATION_FAILED, invalidBlogId.getErrorCode());
    }

    @Test
    void responseIdentifiersAboveJavaScriptSafeIntegerRemainExactStrings() {
        long blogId = blog(LARGE_ID, AUTHOR_ID, null);
        jdbc.execute("ALTER TABLE tb_blog_comments AUTO_INCREMENT=" + LARGE_ID);
        long largeAuthorId = LARGE_ID + 2;
        UserHolder.saveUser(user(largeAuthorId));

        BlogCommentItem created = service.createComment(createRequest(blogId, "large identifiers"));
        assertEquals(Long.toString(LARGE_ID), created.getId());
        assertEquals(Long.toString(blogId), created.getBlogId());
        assertEquals(Long.toString(largeAuthorId), created.getUserId());

        BlogCommentPage page = service.listComments(blogId, pageRequest(null, 20));
        assertEquals(Collections.singletonList(Long.toString(LARGE_ID)), itemIds(page));
        assertEquals(Long.toString(LARGE_ID), page.getItems().get(0).getId());
    }

    @Test
    void replyUsesVisibleRootAndStoresRootAsParentAndAnswerWithAuthorAndCounter() {
        long blogId = blog(AUTHOR_ID, null);
        long rootId = comment(blogId, 702L, 0, 0, 0, "root");
        comment(blogId, 703L, rootId, rootId, 0, "existing direct reply");

        BlogCommentItem created = service.createReply(rootId, replyRequest("new direct reply"));

        assertNotNull(created.getId());
        assertEquals(Long.toString(blogId), created.getBlogId());
        assertEquals(Long.toString(AUTHOR_ID), created.getUserId());
        assertEquals("new direct reply", created.getContent());
        assertNotNull(created.getCreateTime());
        assertEquals(3, countComments(blogId));
        assertEquals(Integer.valueOf(1), blogCounter(blogId), "NULL legacy counter must coalesce to one");
        assertEquals(0L, jdbc.queryForObject(
                "SELECT liked + status FROM tb_blog_comments WHERE id=?", Long.class,
                Long.valueOf(created.getId())));
        assertEquals(Long.valueOf(rootId), jdbc.queryForObject(
                "SELECT parent_id FROM tb_blog_comments WHERE id=?", Long.class, Long.valueOf(created.getId())));
        assertEquals(Long.valueOf(rootId), jdbc.queryForObject(
                "SELECT answer_id FROM tb_blog_comments WHERE id=?", Long.class, Long.valueOf(created.getId())));
    }

    @Test
    void repliesRejectInvalidTargetsForBothReadAndWriteWithoutChangingRows() {
        long blogId = blog(AUTHOR_ID, 9);
        long reported = comment(blogId, AUTHOR_ID, 0, 0, 1, "reported root");
        long hidden = comment(blogId, AUTHOR_ID, 0, 0, 2, "hidden root");
        long legacyNull = comment(blogId, AUTHOR_ID, 0, 0, null, "null status root");
        long root = comment(blogId, AUTHOR_ID, 0, 0, 0, "visible root");
        long reply = comment(blogId, AUTHOR_ID, root, root, 0, "reply is not a root");
        long orphanBlogId = blog(AUTHOR_ID, 0);
        long orphanRoot = comment(orphanBlogId, AUTHOR_ID, 0, 0, 0, "orphan root");
        jdbc.update("DELETE FROM tb_blog WHERE id=?", orphanBlogId);
        int rowsBefore = jdbc.queryForObject("SELECT COUNT(*) FROM tb_blog_comments", Integer.class);

        for (Long invalidId : Arrays.asList(999999L, reported, hidden, legacyNull, reply, orphanRoot)) {
            BusinessException write = assertThrows(BusinessException.class,
                    () -> service.createReply(invalidId, replyRequest("must not be inserted")));
            assertEquals(ErrorCode.NOT_FOUND, write.getErrorCode());
            assertEquals(404, write.getErrorCode().getHttpStatus().value());
            BusinessException read = assertThrows(BusinessException.class,
                    () -> listReplies(invalidId, pageRequest(null, 20)));
            assertEquals(ErrorCode.NOT_FOUND, read.getErrorCode());
            assertEquals(404, read.getErrorCode().getHttpStatus().value());
        }

        assertEquals(rowsBefore, jdbc.queryForObject("SELECT COUNT(*) FROM tb_blog_comments", Integer.class));
        assertEquals(Integer.valueOf(9), blogCounter(blogId));
        BusinessException blank = assertThrows(BusinessException.class,
                () -> service.createReply(root, replyRequest("  ")));
        assertEquals(ErrorCode.VALIDATION_FAILED, blank.getErrorCode());
        BusinessException tooLong = assertThrows(BusinessException.class,
                () -> service.createReply(root, replyRequest(String.join("", Collections.nCopies(256, "x")))));
        assertEquals(ErrorCode.VALIDATION_FAILED, tooLong.getErrorCode());
        BusinessException badPage = assertThrows(BusinessException.class,
                () -> listReplies(root, pageRequest(null, 0)));
        assertEquals(ErrorCode.VALIDATION_FAILED, badPage.getErrorCode());
        BusinessException oversizedPage = assertThrows(BusinessException.class,
                () -> listReplies(root, pageRequest(null, 51)));
        assertEquals(ErrorCode.VALIDATION_FAILED, oversizedPage.getErrorCode());
        assertEquals(rowsBefore, jdbc.queryForObject("SELECT COUNT(*) FROM tb_blog_comments", Integer.class));
        assertEquals(Integer.valueOf(9), blogCounter(blogId));
    }

    @Test
    void replyListingFiltersByBlogRootAnswerAndVisibilityAndUsesStableBoundedCursorPages() {
        long blogId = blog(AUTHOR_ID, 0);
        long root = comment(blogId, AUTHOR_ID, 0, 0, 0, "root");
        long otherRoot = comment(blogId, AUTHOR_ID, 0, 0, 0, "other root");
        long foreignBlog = blog(AUTHOR_ID, 0);
        long validOld = comment(blogId, AUTHOR_ID, root, root, 0, "valid-old");
        comment(blogId, AUTHOR_ID, root, otherRoot, 0, "answer mismatch");
        comment(blogId, AUTHOR_ID, root, root, 1, "reported reply");
        comment(blogId, AUTHOR_ID, root, root, 2, "hidden reply");
        comment(blogId, AUTHOR_ID, root, root, null, "null status reply");
        comment(foreignBlog, AUTHOR_ID, root, root, 0, "cross-blog reply");
        comment(blogId, AUTHOR_ID, otherRoot, otherRoot, 0, "cross-root reply");
        jdbc.execute("ALTER TABLE tb_blog_comments AUTO_INCREMENT=" + LARGE_ID);
        long high1 = comment(blogId, 810L, root, root, 0, "valid-high-1");
        long high2 = comment(blogId, 811L, root, root, 0, "valid-high-2");
        long high3 = comment(blogId, 812L, root, root, 0, "valid-high-3");

        BlogCommentPage first = listReplies(root, pageRequest(null, 1));
        assertEquals(Collections.singletonList(Long.toString(high3)), itemIds(first));
        assertTrue(first.isHasNext());
        assertEquals(Long.toString(high3), first.getNextBeforeId());
        comment(blogId, AUTHOR_ID, root, root, 0, "inserted-between-pages");
        BlogCommentPage second = listReplies(root, pageRequest(first.getNextBeforeId(), 1));
        assertEquals(Collections.singletonList(Long.toString(high2)), itemIds(second));
        assertTrue(second.isHasNext());
        BlogCommentPage rest = listReplies(root, pageRequest(second.getNextBeforeId(), 50));
        assertEquals(Arrays.asList(Long.toString(high1), Long.toString(validOld)), itemIds(rest));
        assertFalse(rest.isHasNext());
        assertTrue(Long.parseLong(first.getItems().get(0).getId()) > 9007199254740992L);
        assertEquals(50, pageRequest(null, 50).getSize());
        assertEquals(Arrays.asList(Long.toString(otherRoot), Long.toString(root)),
                itemIds(service.listComments(blogId, pageRequest(null, 50))));
    }

    @Test
    void replyInsertCounterAndZeroAffectedFailuresRollbackTogether() {
        long blogId = blog(AUTHOR_ID, 4);
        long root = comment(blogId, AUTHOR_ID, 0, 0, 0, "root");
        jdbc.execute("CREATE TRIGGER fail_comment_insert BEFORE INSERT ON tb_blog_comments FOR EACH ROW "
                + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic insert failure'");
        BusinessException insertFailure = assertThrows(BusinessException.class,
                () -> service.createReply(root, replyRequest("must roll back")));
        assertEquals(ErrorCode.INTERNAL_ERROR, insertFailure.getErrorCode());
        assertNull(insertFailure.getCause());
        assertEquals(1, countComments(blogId));
        assertEquals(Integer.valueOf(4), blogCounter(blogId));

        dropTrigger("fail_comment_insert");
        jdbc.execute("CREATE TRIGGER remove_blog_before_comment_insert BEFORE INSERT ON tb_blog_comments "
                + "FOR EACH ROW DELETE FROM tb_blog WHERE id=NEW.blog_id");
        BusinessException zeroRows = assertThrows(BusinessException.class,
                () -> service.createReply(root, replyRequest("zero-row counter update")));
        assertEquals(ErrorCode.OPERATION_FAILED, zeroRows.getErrorCode());
        assertEquals(422, zeroRows.getErrorCode().getHttpStatus().value());
        assertEquals(1, countComments(blogId));
        assertEquals(Integer.valueOf(4), blogCounter(blogId), "trigger deletion must roll back");

        dropTrigger("remove_blog_before_comment_insert");
        jdbc.execute("CREATE TRIGGER fail_blog_counter_update BEFORE UPDATE ON tb_blog FOR EACH ROW "
                + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic counter failure'");
        BusinessException counterFailure = assertThrows(BusinessException.class,
                () -> service.createReply(root, replyRequest("insert must roll back")));
        assertEquals(ErrorCode.INTERNAL_ERROR, counterFailure.getErrorCode());
        assertNull(counterFailure.getCause());
        assertEquals(1, countComments(blogId));
        assertEquals(Integer.valueOf(4), blogCounter(blogId));
    }

    @Test
    void concurrentFirstLevelAndReplyWritersSerializeOnBlogAndKeepAuthorsAndCountAccurate() throws Exception {
        final long blogId = blog(AUTHOR_ID, 0);
        final long rootId = comment(blogId, AUTHOR_ID, 0, 0, 0, "reply target");
        jdbc.update("UPDATE tb_blog SET comments=1 WHERE id=?", blogId);
        final int writers = 12;
        final CountDownLatch ready = new CountDownLatch(writers);
        final CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(writers);
        List<Future<BlogCommentItem>> results = new ArrayList<>();
        try {
            for (int i = 0; i < writers; i++) {
                final long authorId = 900L + i;
                final boolean reply = i % 2 == 0;
                results.add(executor.submit(new Callable<BlogCommentItem>() {
                    @Override
                    public BlogCommentItem call() throws Exception {
                        UserHolder.saveUser(user(authorId));
                        ready.countDown();
                        try {
                            assertTrue(start.await(10, TimeUnit.SECONDS));
                            return reply
                                    ? service.createReply(rootId, replyRequest("writer-" + authorId))
                                    : service.createComment(createRequest(blogId, "writer-" + authorId));
                        } finally {
                            UserHolder.removeUser();
                        }
                    }
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS), "writers did not become ready");
            start.countDown();
            Set<String> seenAuthors = new HashSet<>();
            for (Future<BlogCommentItem> result : results) {
                BlogCommentItem item = result.get(15, TimeUnit.SECONDS);
                seenAuthors.add(item.getUserId());
            }
            for (int i = 0; i < writers; i++) assertTrue(seenAuthors.contains(Long.toString(900L + i)));
            assertEquals(writers + 1, countComments(blogId));
            assertEquals(Integer.valueOf(writers + 1), blogCounter(blogId));
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void replyWriteRechecksRootVisibilityAfterInitialSnapshotLookup() {
        long blogId = blog(AUTHOR_ID, 6);
        long root = comment(blogId, AUTHOR_ID, 0, 0, 0, "root becomes hidden");
        int before = countComments(blogId);
        AFTER_VISIBLE_ROOT_QUERY.set(() -> {
            try (Connection connection = datasource.getConnection();
                 PreparedStatement statement = connection.prepareStatement(
                         "UPDATE tb_blog_comments SET status=2 WHERE id=?")) {
                statement.setLong(1, root);
                assertEquals(1, statement.executeUpdate());
            } catch (SQLException error) {
                throw new AssertionError("could not commit concurrent root visibility change", error);
            }
        });

        BusinessException rejected = assertThrows(BusinessException.class,
                () -> service.createReply(root, replyRequest("must not be inserted")));

        assertEquals(ErrorCode.NOT_FOUND, rejected.getErrorCode());
        assertEquals(404, rejected.getErrorCode().getHttpStatus().value());
        assertEquals(before, countComments(blogId));
        assertEquals(Integer.valueOf(6), blogCounter(blogId));
        assertEquals(Integer.valueOf(2), mapper.selectById(root).getStatus());
        assertNull(AFTER_VISIBLE_ROOT_QUERY.get());
    }

    @Test
    void withdrawalRetainsContentHidesReplyAndDecrementsOnlyOnce() {
        long blogId = blog(AUTHOR_ID, 2);
        long root = comment(blogId, AUTHOR_ID, 0, 0, 0, "root");
        jdbc.execute("ALTER TABLE tb_blog_comments AUTO_INCREMENT=" + LARGE_ID);
        long reply = comment(blogId, AUTHOR_ID, root, root, 0, "retained original content");
        assertEquals(Collections.singletonList(Long.toString(reply)), itemIds(listReplies(root, pageRequest(null, 50))));
        service.withdrawReply(reply);
        service.withdrawReply(reply);
        assertEquals(Integer.valueOf(3), mapper.selectById(reply).getStatus());
        assertEquals("retained original content", mapper.selectById(reply).getContent());
        assertEquals(Integer.valueOf(1), blogCounter(blogId));
        assertEquals(2, countComments(blogId));
        assertTrue(listReplies(root, pageRequest(null, 50)).getItems().isEmpty());
        assertEquals(Collections.singletonList(Long.toString(root)), itemIds(service.listComments(blogId, pageRequest(null, 50))));
        UserHolder.saveUser(user(AUTHOR_ID + 1));
        assertEquals(ErrorCode.NOT_FOUND, assertThrows(BusinessException.class, () -> service.withdrawReply(reply)).getErrorCode());
        assertEquals(Integer.valueOf(1), blogCounter(blogId));
    }

    @Test
    void withdrawalRejectsForeignRootsUnsupportedStatesRelationsAndMissingBlog() {
        long blogId = blog(AUTHOR_ID, 8);
        long root = comment(blogId, AUTHOR_ID, 0, 0, 0, "root");
        List<Long> rejected = new ArrayList<>(Arrays.asList(root, 999999L));
        rejected.add(comment(blogId, AUTHOR_ID + 1, root, root, 0, "foreign reply"));
        for (Integer state : Arrays.asList(1, 2, null, 4)) {
            rejected.add(comment(blogId, AUTHOR_ID, root, root, state, "unsupported state"));
        }
        rejected.add(comment(blogId, AUTHOR_ID, root, root + 1, 0, "wrong answer"));
        rejected.add(comment(blogId, AUTHOR_ID, 0, root, 0, "answer only"));
        rejected.add(comment(999999L, AUTHOR_ID, root, root, 0, "missing post"));
        int before = countComments(blogId);
        for (Long id : rejected) {
            assertEquals(ErrorCode.NOT_FOUND, assertThrows(BusinessException.class, () -> service.withdrawReply(id)).getErrorCode());
        }
        assertEquals(Integer.valueOf(8), blogCounter(blogId));
        assertEquals(before, countComments(blogId));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM tb_blog_comments WHERE status=3", Integer.class));
    }

    @Test
    void withdrawalDoesNotRequireRootVisibilityOrRootExistenceButDoesRequirePost() {
        long blogId = blog(AUTHOR_ID, 3);
        long hiddenRoot = comment(blogId, AUTHOR_ID, 0, 0, 2, "hidden root");
        long own = comment(blogId, AUTHOR_ID, hiddenRoot, hiddenRoot, 0, "own under hidden root");
        long orphan = comment(blogId, AUTHOR_ID, 888888L, 888888L, 0, "own root missing");
        service.withdrawReply(own);
        service.withdrawReply(orphan);
        assertEquals(Integer.valueOf(3), mapper.selectById(own).getStatus());
        assertEquals(Integer.valueOf(3), mapper.selectById(orphan).getStatus());
        assertEquals(Integer.valueOf(2), mapper.selectById(hiddenRoot).getStatus());
        assertEquals(Integer.valueOf(1), blogCounter(blogId));
    }

    @Test
    void withdrawalClampsNullAndZeroLegacyCountersWithoutRecounting() {
        for (Integer counter : Arrays.asList(null, 0, 1, 7)) {
            long blogId = blog(AUTHOR_ID, counter);
            long root = comment(blogId, AUTHOR_ID, 0, 0, 0, "root");
            long reply = comment(blogId, AUTHOR_ID, root, root, 0, "own reply");
            service.withdrawReply(reply);
            service.withdrawReply(reply);
            assertEquals(Integer.valueOf(counter == null || counter == 0 ? 0 : counter - 1), blogCounter(blogId));
            assertEquals(Integer.valueOf(3), mapper.selectById(reply).getStatus());
            assertEquals(2, countComments(blogId));
        }
    }

    @Test
    void withdrawalUpdateCounterAndZeroRowFailuresRollBackBothRows() {
        long blogId = blog(AUTHOR_ID, 4);
        long root = comment(blogId, AUTHOR_ID, 0, 0, 0, "root");
        long reply = comment(blogId, AUTHOR_ID, root, root, 0, "must remain visible");
        jdbc.execute("CREATE TRIGGER fail_reply_update BEFORE UPDATE ON tb_blog_comments FOR EACH ROW "
                + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic reply update failure'");
        BusinessException update = assertThrows(BusinessException.class, () -> service.withdrawReply(reply));
        assertEquals(ErrorCode.INTERNAL_ERROR, update.getErrorCode());
        assertNull(update.getCause());
        assertEquals(Integer.valueOf(0), mapper.selectById(reply).getStatus());
        assertEquals(Integer.valueOf(4), blogCounter(blogId));
        dropTrigger("fail_reply_update");
        jdbc.execute("CREATE TRIGGER fail_blog_counter_update BEFORE UPDATE ON tb_blog FOR EACH ROW "
                + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic withdrawal counter failure'");
        BusinessException count = assertThrows(BusinessException.class, () -> service.withdrawReply(reply));
        assertEquals(ErrorCode.INTERNAL_ERROR, count.getErrorCode());
        assertNull(count.getCause());
        assertEquals(Integer.valueOf(0), mapper.selectById(reply).getStatus());
        assertEquals(Integer.valueOf(4), blogCounter(blogId));
        dropTrigger("fail_blog_counter_update");
        jdbc.execute("CREATE TRIGGER remove_blog_before_reply_update BEFORE UPDATE ON tb_blog_comments "
                + "FOR EACH ROW DELETE FROM tb_blog WHERE id=NEW.blog_id");
        BusinessException rows = assertThrows(BusinessException.class, () -> service.withdrawReply(reply));
        assertEquals(ErrorCode.OPERATION_FAILED, rows.getErrorCode());
        assertEquals(Integer.valueOf(0), mapper.selectById(reply).getStatus());
        assertEquals(Integer.valueOf(4), blogCounter(blogId), "trigger deletion must roll back");
        assertEquals(2, countComments(blogId));
    }

    @Test
    void concurrentDuplicateWithdrawalsAndNewRepliesKeepCounterAccurate() throws Exception {
        long blogId = blog(AUTHOR_ID, 2);
        long root = comment(blogId, AUTHOR_ID, 0, 0, 0, "root");
        long reply = comment(blogId, AUTHOR_ID, root, root, 0, "withdraw target");
        int participants = 12;
        CountDownLatch ready = new CountDownLatch(participants);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(participants);
        List<Future<?>> results = new ArrayList<>();
        try {
            for (int i = 0; i < participants; i++) {
                final boolean withdraw = i < 8;
                results.add(executor.submit(() -> {
                    UserHolder.saveUser(user(AUTHOR_ID));
                    ready.countDown();
                    try {
                        assertTrue(start.await(10, TimeUnit.SECONDS));
                        if (withdraw) service.withdrawReply(reply);
                        else service.createReply(root, replyRequest("concurrent new reply"));
                        return null;
                    } finally {
                        UserHolder.removeUser();
                    }
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            for (Future<?> result : results) result.get(20, TimeUnit.SECONDS);
            assertEquals(Integer.valueOf(5), blogCounter(blogId));
            assertEquals(6, countComments(blogId));
            assertEquals(Integer.valueOf(3), mapper.selectById(reply).getStatus());
            assertEquals(4, listReplies(root, pageRequest(null, 50)).getItems().size());
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void withdrawalCurrentReadRechecksOwnershipStatusRelationAndBlogAfterLookup() {
        long blogId = blog(AUTHOR_ID, 9);
        long root = comment(blogId, AUTHOR_ID, 0, 0, 0, "root");
        long otherBlog = blog(AUTHOR_ID, 0);
        for (String change : Arrays.asList("status=2", "user_id=" + (AUTHOR_ID + 1),
                "answer_id=" + (root + 1000), "blog_id=" + otherBlog)) {
            long reply = comment(blogId, AUTHOR_ID, root, root, 0, "changed after lookup");
            AFTER_OWNED_REPLY_QUERY.set(() -> {
                try (Connection connection = datasource.getConnection();
                     PreparedStatement statement = connection.prepareStatement(
                             "UPDATE tb_blog_comments SET " + change + " WHERE id=?")) {
                    statement.setLong(1, reply);
                    assertEquals(1, statement.executeUpdate());
                } catch (SQLException error) {
                    throw new AssertionError("could not commit concurrent reply eligibility change", error);
                }
            });
            assertEquals(ErrorCode.NOT_FOUND, assertThrows(BusinessException.class, () -> service.withdrawReply(reply)).getErrorCode());
            assertEquals(Integer.valueOf(9), blogCounter(blogId));
            assertNull(AFTER_OWNED_REPLY_QUERY.get());
        }
    }

    @Test
    void rootWithdrawalRetainsRepliesClosesReplyAccessAndKeepsCursorAndAuthorWithdrawal() {
        long blogId = blog(AUTHOR_ID, 3);
        long otherRoot = comment(blogId, AUTHOR_ID, 0, 0, 0, "other visible root");
        jdbc.execute("ALTER TABLE tb_blog_comments AUTO_INCREMENT=" + LARGE_ID);
        long root = comment(blogId, AUTHOR_ID, 0, 0, 0, "retained root content");
        long reply = comment(blogId, AUTHOR_ID + 1, root, root, 0, "retained child content");
        BlogCommentPage first = service.listComments(blogId, pageRequest(null, 1));
        assertEquals(Collections.singletonList(Long.toString(root)), itemIds(first));
        assertTrue(first.isHasNext());

        service.withdrawComment(root);
        service.withdrawComment(root);

        assertEquals(Integer.valueOf(3), mapper.selectById(root).getStatus());
        assertEquals("retained root content", mapper.selectById(root).getContent());
        assertEquals(Integer.valueOf(0), mapper.selectById(reply).getStatus());
        assertEquals("retained child content", mapper.selectById(reply).getContent());
        assertEquals(Integer.valueOf(2), blogCounter(blogId), "only the root decrement, not the hidden descendants");
        assertEquals(3, countComments(blogId));
        BlogCommentPage next = service.listComments(blogId, pageRequest(first.getNextBeforeId(), 1));
        assertEquals(Collections.singletonList(Long.toString(otherRoot)), itemIds(next));
        assertFalse(next.isHasNext());
        assertNull(next.getNextBeforeId());
        assertEquals(ErrorCode.NOT_FOUND, assertThrows(BusinessException.class,
                () -> listReplies(root, pageRequest(null, 50))).getErrorCode());
        assertEquals(ErrorCode.NOT_FOUND, assertThrows(BusinessException.class,
                () -> service.createReply(root, replyRequest("no new reply"))).getErrorCode());
        assertEquals(ErrorCode.NOT_FOUND, assertThrows(BusinessException.class,
                () -> service.withdrawReply(reply)).getErrorCode());
        UserHolder.saveUser(user(AUTHOR_ID + 1));
        assertEquals(ErrorCode.NOT_FOUND, assertThrows(BusinessException.class,
                () -> service.withdrawComment(root)).getErrorCode());
        service.withdrawReply(reply);
        service.withdrawReply(reply);
        assertEquals(Integer.valueOf(3), mapper.selectById(reply).getStatus());
        assertEquals(Integer.valueOf(1), blogCounter(blogId));
    }

    @Test
    void rootWithdrawalRejectsForeignUnsupportedRelationsStatesAndMissingPost() {
        long blogId = blog(AUTHOR_ID, 7);
        long root = comment(blogId, AUTHOR_ID, 0, 0, 0, "root");
        List<Long> rejected = new ArrayList<>(Collections.singletonList(999999L));
        rejected.add(comment(blogId, AUTHOR_ID + 1, 0, 0, 0, "foreign root"));
        for (Integer state : Arrays.asList(1, 2, null, 4)) {
            rejected.add(comment(blogId, AUTHOR_ID, 0, 0, state, "unsupported root"));
        }
        rejected.add(comment(blogId, AUTHOR_ID, root, root, 0, "reply"));
        rejected.add(comment(blogId, AUTHOR_ID, 0, root, 0, "answer only"));
        rejected.add(comment(blogId, AUTHOR_ID, root, 0, 0, "parent only"));
        rejected.add(comment(999999L, AUTHOR_ID, 0, 0, 0, "post missing"));
        int before = countComments(blogId);
        for (Long id : rejected) {
            assertEquals(ErrorCode.NOT_FOUND, assertThrows(BusinessException.class,
                    () -> service.withdrawComment(id)).getErrorCode());
        }
        assertEquals(Integer.valueOf(7), blogCounter(blogId));
        assertEquals(before, countComments(blogId));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM tb_blog_comments WHERE status=3", Integer.class));
    }

    @Test
    void rootWithdrawalClampsNullAndZeroCountersAndDoesNotRecountChildRows() {
        for (Integer counter : Arrays.asList(null, 0, 1, 7)) {
            long blogId = blog(AUTHOR_ID, counter);
            long root = comment(blogId, AUTHOR_ID, 0, 0, 0, "root");
            long reply = comment(blogId, AUTHOR_ID + 1, root, root, 0, "unaccounted legacy child");
            service.withdrawComment(root);
            service.withdrawComment(root);
            assertEquals(Integer.valueOf(counter == null || counter == 0 ? 0 : counter - 1), blogCounter(blogId));
            assertEquals(Integer.valueOf(3), mapper.selectById(root).getStatus());
            assertEquals(Integer.valueOf(0), mapper.selectById(reply).getStatus());
            assertEquals(2, countComments(blogId));
        }
    }

    @Test
    void rootWithdrawalStatusCounterAndZeroRowFailuresRollbackRootAndPreserveReplies() {
        long blogId = blog(AUTHOR_ID, 5);
        long root = comment(blogId, AUTHOR_ID, 0, 0, 0, "root must stay visible");
        long reply = comment(blogId, AUTHOR_ID + 1, root, root, 0, "reply must stay unchanged");
        for (String trigger : Arrays.asList(
                "CREATE TRIGGER fail_reply_update BEFORE UPDATE ON tb_blog_comments FOR EACH ROW "
                        + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic root update failure'",
                "CREATE TRIGGER fail_blog_counter_update BEFORE UPDATE ON tb_blog FOR EACH ROW "
                        + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic root counter failure'",
                "CREATE TRIGGER remove_blog_before_reply_update BEFORE UPDATE ON tb_blog_comments "
                        + "FOR EACH ROW DELETE FROM tb_blog WHERE id=NEW.blog_id")) {
            jdbc.execute(trigger);
            BusinessException error = assertThrows(BusinessException.class, () -> service.withdrawComment(root));
            assertEquals(trigger.contains("DELETE FROM") ? ErrorCode.OPERATION_FAILED : ErrorCode.INTERNAL_ERROR,
                    error.getErrorCode());
            assertNull(error.getCause());
            assertEquals(Integer.valueOf(0), mapper.selectById(root).getStatus());
            assertEquals(Integer.valueOf(0), mapper.selectById(reply).getStatus());
            assertEquals(Integer.valueOf(5), blogCounter(blogId));
            assertEquals(2, countComments(blogId));
            assertEquals(Collections.singletonList(Long.toString(reply)), itemIds(listReplies(root, pageRequest(null, 50))));
            dropTrigger("fail_reply_update");
            dropTrigger("fail_blog_counter_update");
            dropTrigger("remove_blog_before_reply_update");
        }
    }

    @Test
    void concurrentRootWithdrawalsAndNewFirstLevelCommentsDecrementOnlyOnce() throws Exception {
        long blogId = blog(AUTHOR_ID, 2);
        long root = comment(blogId, AUTHOR_ID, 0, 0, 0, "withdrawn root");
        long reply = comment(blogId, AUTHOR_ID + 1, root, root, 0, "preserved child");
        int participants = 12;
        CountDownLatch ready = new CountDownLatch(participants);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(participants);
        List<Future<?>> results = new ArrayList<>();
        try {
            for (int i = 0; i < participants; i++) {
                final boolean withdraw = i < 8;
                results.add(executor.submit(() -> {
                    UserHolder.saveUser(user(AUTHOR_ID));
                    ready.countDown();
                    try {
                        assertTrue(start.await(10, TimeUnit.SECONDS));
                        if (withdraw) service.withdrawComment(root);
                        else service.createComment(createRequest(blogId, "new first-level comment"));
                        return null;
                    } finally {
                        UserHolder.removeUser();
                    }
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            for (Future<?> result : results) result.get(20, TimeUnit.SECONDS);
            assertEquals(Integer.valueOf(5), blogCounter(blogId));
            assertEquals(6, countComments(blogId));
            assertEquals(Integer.valueOf(3), mapper.selectById(root).getStatus());
            assertEquals(Integer.valueOf(0), mapper.selectById(reply).getStatus());
            assertEquals(4, service.listComments(blogId, pageRequest(null, 50)).getItems().size());
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void rootWithdrawalCurrentReadRechecksStatusOwnerRelationAndBlogAfterLookup() {
        long blogId = blog(AUTHOR_ID, 8);
        long otherBlog = blog(AUTHOR_ID, 0);
        for (String change : Arrays.asList("status=2", "user_id=" + (AUTHOR_ID + 1),
                "parent_id=9", "answer_id=9", "blog_id=" + otherBlog)) {
            long root = comment(blogId, AUTHOR_ID, 0, 0, 0, "root changed after lookup");
            AFTER_OWNED_COMMENT_QUERY.set(() -> {
                try (Connection connection = datasource.getConnection();
                     PreparedStatement statement = connection.prepareStatement(
                             "UPDATE tb_blog_comments SET " + change + " WHERE id=?")) {
                    statement.setLong(1, root);
                    assertEquals(1, statement.executeUpdate());
                } catch (SQLException error) {
                    throw new AssertionError("could not commit concurrent root eligibility change", error);
                }
            });
            assertEquals(ErrorCode.NOT_FOUND, assertThrows(BusinessException.class,
                    () -> service.withdrawComment(root)).getErrorCode());
            assertEquals(Integer.valueOf(8), blogCounter(blogId));
            assertNull(AFTER_OWNED_COMMENT_QUERY.get());
        }
    }

    @Test
    void replyCreationRejectsRootWithdrawnByRealServiceAfterInitialVisibleLookup() throws Exception {
        long blogId = blog(AUTHOR_ID, 1);
        long root = comment(blogId, AUTHOR_ID, 0, 0, 0, "root withdrawn during reply create");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            AFTER_VISIBLE_ROOT_QUERY.set(() -> {
                Future<?> withdrawn = executor.submit(() -> {
                    UserHolder.saveUser(user(AUTHOR_ID));
                    try { service.withdrawComment(root); }
                    finally { UserHolder.removeUser(); }
                });
                try { withdrawn.get(10, TimeUnit.SECONDS); }
                catch (Exception error) { throw new AssertionError("independent service withdrawal failed", error); }
            });
            assertEquals(ErrorCode.NOT_FOUND, assertThrows(BusinessException.class,
                    () -> service.createReply(root, replyRequest("must not be inserted"))).getErrorCode());
            assertEquals(Integer.valueOf(3), mapper.selectById(root).getStatus());
            assertEquals(Integer.valueOf(0), blogCounter(blogId));
            assertEquals(1, countComments(blogId));
            assertNull(AFTER_VISIBLE_ROOT_QUERY.get());
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void hideAuditMigrationRepeatsWithoutLosingRowsAndHasUniqueCommentKey() throws Exception {
        long blogId = blog(AUTHOR_ID, 1);
        long root = comment(blogId, AUTHOR_ID, 0, 0, 0, "root");
        service.hideComment(root, hideRequest("first audit reason"));
        try (Connection connection = datasource.getConnection()) {
            ClassPathResource migration = new ClassPathResource("db/migration/V008__add_blog_comment_hide_audit.sql");
            ScriptUtils.executeSqlScript(connection, migration);
            ScriptUtils.executeSqlScript(connection, migration);
        }
        assertEquals(1, auditCount(root));
        assertEquals(Arrays.asList("id", "comment_id", "blog_id", "operator_user_id", "reason", "from_status",
                        "to_status", "create_time"), jdbc.queryForList(
                "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA=DATABASE() "
                        + "AND TABLE_NAME='tb_blog_comment_hide_audit' ORDER BY ORDINAL_POSITION", String.class));
        assertEquals(Integer.valueOf(0), jdbc.queryForObject(
                "SELECT NON_UNIQUE FROM INFORMATION_SCHEMA.STATISTICS WHERE TABLE_SCHEMA=DATABASE() "
                        + "AND TABLE_NAME='tb_blog_comment_hide_audit' AND INDEX_NAME='uk_blog_comment_hide_audit_comment'",
                Integer.class));
    }

    @Test
    void hideReplyKeepsContentRelationsAndFirstReceiptSeparateFromRootAuditPages() {
        long blogId = blog(AUTHOR_ID, 6);
        jdbc.update("UPDATE tb_blog SET liked=17 WHERE id=?", blogId);
        long root = comment(blogId, AUTHOR_ID, 0, 0, 0, "root retained independently");
        long reply = comment(blogId, AUTHOR_ID + 1, root, root, 0, "reply private content");
        long sibling = comment(blogId, AUTHOR_ID + 2, root, root, 0, "sibling stays visible");
        long ownReply = comment(blogId, AUTHOR_ID + 10, root, root, 0, "operator authored reply");
        service.hideComment(root, hideRequest("root receipt remains root-only"));

        UserHolder.saveUser(user(AUTHOR_ID + 10));
        String firstReason = "first reply reason ' 合成";
        service.hideReply(reply, hideRequest(firstReason));
        service.hideReply(ownReply, hideRequest("operator may hide own reply"));
        UserHolder.saveUser(user(AUTHOR_ID + 11));
        service.hideReply(reply, hideRequest("repeat must not replace receipt"));

        assertEquals(Integer.valueOf(2), mapper.selectById(reply).getStatus());
        assertEquals("reply private content", mapper.selectById(reply).getContent());
        assertEquals(Long.valueOf(root), mapper.selectById(reply).getParentId());
        assertEquals(Long.valueOf(root), mapper.selectById(reply).getAnswerId());
        assertEquals(Integer.valueOf(0), mapper.selectById(reply).getLiked());
        assertEquals(Integer.valueOf(0), mapper.selectById(sibling).getStatus());
        assertEquals("sibling stays visible", mapper.selectById(sibling).getContent());
        assertEquals(Integer.valueOf(2), mapper.selectById(ownReply).getStatus());
        assertEquals(Integer.valueOf(2), mapper.selectById(root).getStatus());
        assertEquals("root retained independently", mapper.selectById(root).getContent());
        assertEquals(4, countComments(blogId));
        assertEquals(Integer.valueOf(3), blogCounter(blogId));
        assertEquals(Integer.valueOf(17), jdbc.queryForObject("SELECT liked FROM tb_blog WHERE id=?", Integer.class, blogId));
        assertEquals(1, replyAuditCount(reply));
        assertEquals(1, replyAuditCount(ownReply));
        assertEquals(Long.valueOf(AUTHOR_ID + 10), jdbc.queryForObject(
                "SELECT operator_user_id FROM tb_blog_reply_hide_audit WHERE reply_id=?", Long.class, reply));
        assertEquals(Long.valueOf(AUTHOR_ID + 10), jdbc.queryForObject(
                "SELECT operator_user_id FROM tb_blog_reply_hide_audit WHERE reply_id=?", Long.class, ownReply));
        assertEquals(firstReason, jdbc.queryForObject(
                "SELECT reason FROM tb_blog_reply_hide_audit WHERE reply_id=?", String.class, reply));
        assertEquals(Long.valueOf(blogId), jdbc.queryForObject(
                "SELECT blog_id FROM tb_blog_reply_hide_audit WHERE reply_id=?", Long.class, reply));
        assertEquals(Long.valueOf(root), jdbc.queryForObject(
                "SELECT root_comment_id FROM tb_blog_reply_hide_audit WHERE reply_id=?", Long.class, reply));
        assertEquals(Integer.valueOf(2), jdbc.queryForObject(
                "SELECT from_status + to_status FROM tb_blog_reply_hide_audit WHERE reply_id=?", Integer.class, reply));
        assertNotNull(jdbc.queryForObject(
                "SELECT create_time FROM tb_blog_reply_hide_audit WHERE reply_id=?", java.sql.Timestamp.class, reply));
        assertEquals(1, auditCount(root));
        assertEquals("root receipt remains root-only", service.queryHideAudit(root).getReason());
        BlogCommentHideAuditPage rootAudits = service.listHideAudits(blogId, hideAuditPageRequest(null, 20));
        assertEquals(1, rootAudits.getItems().size(), "direct reply receipts must not enter the root audit page");
        assertEquals(Long.toString(root), rootAudits.getItems().get(0).getCommentId());
    }

    @Test
    void hideReplyAllowsHiddenOrWithdrawnRootsAndWithdrawnPosts() {
        long blogId = blog(AUTHOR_ID, 5);
        long hiddenRoot = comment(blogId, AUTHOR_ID, 0, 0, 0, "hidden root");
        long hiddenRootReply = comment(blogId, AUTHOR_ID + 1, hiddenRoot, hiddenRoot, 0, "reply under hidden root");
        service.hideComment(hiddenRoot, hideRequest("hide root first"));
        service.hideReply(hiddenRootReply, hideRequest("hide reply under hidden root"));

        long withdrawnRoot = comment(blogId, AUTHOR_ID, 0, 0, 0, "withdrawn root");
        long withdrawnRootReply = comment(blogId, AUTHOR_ID + 2, withdrawnRoot, withdrawnRoot, 0,
                "reply under withdrawn root");
        service.withdrawComment(withdrawnRoot);
        service.hideReply(withdrawnRootReply, hideRequest("hide reply under withdrawn root"));

        long postReply = comment(blogId, AUTHOR_ID + 3, hiddenRoot, hiddenRoot, 0, "reply on withdrawn post");
        jdbc.update("UPDATE tb_blog SET withdrawn=1 WHERE id=?", blogId);
        service.hideReply(postReply, hideRequest("manage reply on withdrawn post"));
        assertEquals(Integer.valueOf(2), mapper.selectById(hiddenRootReply).getStatus());
        assertEquals(Integer.valueOf(2), mapper.selectById(withdrawnRootReply).getStatus());
        assertEquals(Integer.valueOf(2), mapper.selectById(postReply).getStatus());
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM tb_blog_reply_hide_audit WHERE blog_id=?",
                Integer.class, blogId));
        assertEquals(Integer.valueOf(0), blogCounter(blogId));
    }

    @Test
    void hideReplyRejectsMissingCrossBlogMalformedAndUnsupportedRowsIncludingLegacyHidden() {
        long blogId = blog(AUTHOR_ID, 12);
        long root = comment(blogId, AUTHOR_ID, 0, 0, 0, "root");
        long otherRoot = comment(blogId, AUTHOR_ID, 0, 0, 0, "other root");
        List<Long> rejected = new ArrayList<>();
        rejected.add(999999L);
        rejected.add(comment(blogId, AUTHOR_ID, root, otherRoot, 0, "answer mismatch"));
        long parentOnly = comment(blogId, AUTHOR_ID, root, 0, 0, "parent only");
        rejected.add(parentOnly);
        rejected.add(comment(blogId, AUTHOR_ID, 0, root, 0, "answer only"));
        long nested = comment(blogId, AUTHOR_ID, parentOnly, parentOnly, 0, "nested direct shape is not root reply");
        rejected.add(nested);
        for (Integer state : Arrays.asList(1, 3, null, 4)) {
            rejected.add(comment(blogId, AUTHOR_ID, root, root, state, "excluded state"));
        }
        long legacyHidden = comment(blogId, AUTHOR_ID, root, root, 2, "legacy hidden without receipt");
        rejected.add(legacyHidden);
        long hiddenWithWrongReceipt = comment(blogId, AUTHOR_ID, root, root, 2, "hidden with mismatched receipt");
        jdbc.update("INSERT INTO tb_blog_reply_hide_audit(reply_id,blog_id,root_comment_id,operator_user_id,reason,"
                        + "from_status,to_status) VALUES(?,?,?,?,'wrong root context',0,2)",
                hiddenWithWrongReceipt, blogId, otherRoot, AUTHOR_ID);
        rejected.add(hiddenWithWrongReceipt);
        long foreignBlog = blog(AUTHOR_ID, 0);
        rejected.add(comment(foreignBlog, AUTHOR_ID, root, root, 0, "cross-blog reply"));
        long orphanBlog = blog(AUTHOR_ID, 0);
        long orphanRoot = comment(orphanBlog, AUTHOR_ID, 0, 0, 0, "orphan root");
        long orphanReply = comment(orphanBlog, AUTHOR_ID, orphanRoot, orphanRoot, 0, "orphan reply");
        jdbc.update("DELETE FROM tb_blog WHERE id=?", orphanBlog);
        long missingRoot = comment(blogId, AUTHOR_ID, 0, 0, 0, "root removed after reply creation");
        long missingRootReply = comment(blogId, AUTHOR_ID + 1, missingRoot, missingRoot, 0, "reply with absent root");
        jdbc.update("DELETE FROM tb_blog_comments WHERE id=?", missingRoot);
        for (Long replyId : rejected) {
            assertEquals(ErrorCode.NOT_FOUND, assertThrows(BusinessException.class,
                    () -> service.hideReply(replyId, hideRequest("must not apply"))).getErrorCode(),
                    "ineligible reply id " + replyId);
        }
        assertEquals(ErrorCode.NOT_FOUND, assertThrows(BusinessException.class,
                () -> service.hideReply(orphanReply, hideRequest("missing post"))).getErrorCode());
        assertEquals(ErrorCode.NOT_FOUND, assertThrows(BusinessException.class,
                () -> service.hideReply(missingRootReply, hideRequest("missing root"))).getErrorCode());
        assertEquals(Integer.valueOf(12), blogCounter(blogId));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM tb_blog_reply_hide_audit", Integer.class));
    }

    @Test
    void hideReplyClampsNullAndLowCountersAndRepeatNeverDecrementsAgain() {
        for (Integer counter : Arrays.asList(null, 0, 1, 7)) {
            long blogId = blog(AUTHOR_ID, counter);
            long root = comment(blogId, AUTHOR_ID, 0, 0, 0, "counter root");
            long reply = comment(blogId, AUTHOR_ID + 1, root, root, 0, "counter reply");
            service.hideReply(reply, hideRequest("first"));
            service.hideReply(reply, hideRequest("repeat"));
            assertEquals(Integer.valueOf(counter == null || counter == 0 ? 0 : counter - 1), blogCounter(blogId));
            assertEquals(Integer.valueOf(2), mapper.selectById(reply).getStatus());
            assertEquals(2, countComments(blogId));
            assertEquals(1, replyAuditCount(reply));
        }
    }

    @Test
    void hideReplyStatusCounterAndAuditFailuresRollbackAndHideInsertZeroCountIs422() {
        long blogId = blog(AUTHOR_ID, 5);
        long root = comment(blogId, AUTHOR_ID, 0, 0, 0, "failure root");
        long reply = comment(blogId, AUTHOR_ID + 1, root, root, 0, "failure reply");
        List<String> triggers = Arrays.asList(
                "CREATE TRIGGER fail_reply_hide_status_update BEFORE UPDATE ON tb_blog_comments FOR EACH ROW "
                        + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='private direct reply status failure'",
                "CREATE TRIGGER fail_blog_counter_update BEFORE UPDATE ON tb_blog FOR EACH ROW "
                        + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='private direct reply counter failure'",
                "CREATE TRIGGER fail_reply_hide_audit_insert BEFORE INSERT ON tb_blog_reply_hide_audit FOR EACH ROW "
                        + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='private direct reply audit failure'");
        for (String trigger : triggers) {
            jdbc.execute(trigger);
            BusinessException error = assertThrows(BusinessException.class,
                    () -> service.hideReply(reply, hideRequest("rollback all")));
            assertEquals(ErrorCode.INTERNAL_ERROR, error.getErrorCode());
            assertNull(error.getCause());
            assertFalse(error.getMessage().contains("private"));
            assertEquals(Integer.valueOf(0), mapper.selectById(reply).getStatus());
            assertEquals(Integer.valueOf(5), blogCounter(blogId));
            assertEquals(0, replyAuditCount(reply));
            dropTrigger("fail_reply_hide_status_update");
            dropTrigger("fail_blog_counter_update");
            dropTrigger("fail_reply_hide_audit_insert");
        }

        AFTER_REPLY_HIDE_AUDIT_INSERT.set(() -> 0);
        BusinessException affectedRows = assertThrows(BusinessException.class,
                () -> service.hideReply(reply, hideRequest("real insert then synthetic count")));
        assertEquals(ErrorCode.OPERATION_FAILED, affectedRows.getErrorCode());
        assertEquals(422, affectedRows.getErrorCode().getHttpStatus().value());
        assertNull(affectedRows.getCause());
        assertEquals(Integer.valueOf(0), mapper.selectById(reply).getStatus());
        assertEquals(Integer.valueOf(5), blogCounter(blogId));
        assertEquals(0, replyAuditCount(reply));
        assertNull(AFTER_REPLY_HIDE_AUDIT_INSERT.get());

        AFTER_DIRECT_REPLY_STATUS_UPDATE.set(() -> 0);
        BusinessException statusRows = assertThrows(BusinessException.class,
                () -> service.hideReply(reply, hideRequest("status mapper reports zero")));
        assertEquals(ErrorCode.OPERATION_FAILED, statusRows.getErrorCode());
        assertEquals(Integer.valueOf(0), mapper.selectById(reply).getStatus());
        assertEquals(Integer.valueOf(5), blogCounter(blogId));
        assertEquals(0, replyAuditCount(reply));

        AFTER_DIRECT_REPLY_COUNTER_UPDATE.set(() -> 0);
        BusinessException counterRows = assertThrows(BusinessException.class,
                () -> service.hideReply(reply, hideRequest("counter mapper reports zero")));
        assertEquals(ErrorCode.OPERATION_FAILED, counterRows.getErrorCode());
        assertEquals(Integer.valueOf(0), mapper.selectById(reply).getStatus());
        assertEquals(Integer.valueOf(5), blogCounter(blogId));
        assertEquals(0, replyAuditCount(reply));
        assertNull(AFTER_DIRECT_REPLY_STATUS_UPDATE.get());
        assertNull(AFTER_DIRECT_REPLY_COUNTER_UPDATE.get());
    }

    @Test
    void hideReplyRepeatDoesNotExecuteStatusUpdateEvenWhenAnUpdateTriggerWouldRejectIt() {
        long blogId = blog(AUTHOR_ID, 1);
        long root = comment(blogId, AUTHOR_ID, 0, 0, 0, "repeat root");
        long reply = comment(blogId, AUTHOR_ID + 1, root, root, 0, "repeat reply");
        service.hideReply(reply, hideRequest("first receipt"));
        jdbc.execute("CREATE TRIGGER fail_reply_hide_status_update BEFORE UPDATE ON tb_blog_comments FOR EACH ROW "
                + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='repeat must not update status'");
        service.hideReply(reply, hideRequest("must not replace"));
        assertEquals(Integer.valueOf(2), mapper.selectById(reply).getStatus());
        assertEquals(Integer.valueOf(0), blogCounter(blogId));
        assertEquals(1, replyAuditCount(reply));
        assertEquals("first receipt", jdbc.queryForObject(
                "SELECT reason FROM tb_blog_reply_hide_audit WHERE reply_id=?", String.class, reply));
    }

    @Test
    void hideReplyRequiresAnActualWritableSynchronizedTransactionBeforeAnyWrite() {
        long blogId = blog(AUTHOR_ID, 1);
        long root = comment(blogId, AUTHOR_ID, 0, 0, 0, "gate root");
        long reply = comment(blogId, AUTHOR_ID + 1, root, root, 0, "gate reply");
        BlogCommentsServiceImpl unproxied = new BlogCommentsServiceImpl(context.getBean(Validator.class));
        assertEquals(ErrorCode.VALIDATION_FAILED, assertThrows(BusinessException.class,
                () -> service.hideReply(0L, hideRequest("invalid id"))).getErrorCode());
        assertEquals(ErrorCode.VALIDATION_FAILED, assertThrows(BusinessException.class,
                () -> service.hideReply(null, hideRequest("null id"))).getErrorCode());
        assertEquals(ErrorCode.VALIDATION_FAILED, assertThrows(BusinessException.class,
                () -> service.hideReply(reply, hideRequest("  "))).getErrorCode());
        assertEquals(ErrorCode.VALIDATION_FAILED, assertThrows(BusinessException.class,
                () -> service.hideReply(reply, null)).getErrorCode());
        UserHolder.removeUser();
        assertEquals(ErrorCode.AUTHENTICATION_FAILED, assertThrows(BusinessException.class,
                () -> service.hideReply(reply, hideRequest("missing identity"))).getErrorCode());
        UserHolder.saveUser(user(AUTHOR_ID));
        EXPECT_NO_MAPPER_SQL.set(Boolean.TRUE);
        try {
            assertThrows(IllegalStateException.class,
                    () -> unproxied.hideReply(reply, hideRequest("unproxied call")));

            TransactionTemplate readOnly = new TransactionTemplate(context.getBean(DataSourceTransactionManager.class));
            readOnly.setReadOnly(true);
            assertThrows(IllegalStateException.class, () -> readOnly.execute(status -> {
                service.hideReply(reply, hideRequest("read-only call"));
                return null;
            }));
        } finally {
            EXPECT_NO_MAPPER_SQL.remove();
        }
        assertEquals(Integer.valueOf(0), mapper.selectById(reply).getStatus());
        assertEquals(Integer.valueOf(1), blogCounter(blogId));
        assertEquals(0, replyAuditCount(reply));
    }

    @Test
    void hideReplyUsesCurrentLockedRelationshipAfterCandidateLookup() {
        long blogId = blog(AUTHOR_ID, 8);
        long otherRoot = comment(blogId, AUTHOR_ID, 0, 0, 0, "other root");
        long otherBlog = blog(AUTHOR_ID, 0);
        for (String change : Arrays.asList("status", "answer", "blog")) {
            long root = comment(blogId, AUTHOR_ID, 0, 0, 0, "current root");
            long reply = comment(blogId, AUTHOR_ID + 1, root, root, 0, "changed reply");
            AFTER_MODERATABLE_REPLY_QUERY.set(() -> {
                String column = "status".equals(change) ? "status"
                        : "answer".equals(change) ? "answer_id" : "blog_id";
                long value = "status".equals(change) ? 3L : "answer".equals(change) ? otherRoot : otherBlog;
                try (Connection connection = datasource.getConnection();
                     PreparedStatement statement = connection.prepareStatement(
                             "UPDATE tb_blog_comments SET " + column + "=? WHERE id=?")) {
                    statement.setLong(1, value);
                    statement.setLong(2, reply);
                    assertEquals(1, statement.executeUpdate());
                } catch (SQLException error) {
                    throw new AssertionError("independent reply eligibility change failed", error);
                }
            });
            assertEquals(ErrorCode.NOT_FOUND, assertThrows(BusinessException.class,
                    () -> service.hideReply(reply, hideRequest("stale candidate must not write"))).getErrorCode());
            assertEquals(Integer.valueOf(8), blogCounter(blogId));
            assertEquals(0, replyAuditCount(reply));
        }
        long root = comment(blogId, AUTHOR_ID, 0, 0, 0, "root whose relation changes");
        long reply = comment(blogId, AUTHOR_ID + 1, root, root, 0, "root lock recheck reply");
        AFTER_MODERATABLE_REPLY_QUERY.set(() -> {
            try (Connection connection = datasource.getConnection();
                 PreparedStatement statement = connection.prepareStatement(
                         "UPDATE tb_blog_comments SET parent_id=? WHERE id=?")) {
                statement.setLong(1, otherRoot);
                statement.setLong(2, root);
                assertEquals(1, statement.executeUpdate());
            } catch (SQLException error) {
                throw new AssertionError("independent root relationship change failed", error);
            }
        });
        assertEquals(ErrorCode.NOT_FOUND, assertThrows(BusinessException.class,
                () -> service.hideReply(reply, hideRequest("root ceased being canonical"))).getErrorCode());
        assertEquals(Integer.valueOf(8), blogCounter(blogId));
        assertEquals(0, replyAuditCount(reply));
        assertNull(AFTER_MODERATABLE_REPLY_QUERY.get());
    }

    @Test
    void hideReplyConcurrentRetriesWriteOneReceiptAndDecrementOnce() throws Exception {
        long blogId = blog(AUTHOR_ID, 2);
        long root = comment(blogId, AUTHOR_ID, 0, 0, 0, "parallel root");
        long reply = comment(blogId, AUTHOR_ID + 1, root, root, 0, "parallel reply");
        int participants = 10;
        CountDownLatch ready = new CountDownLatch(participants);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(participants);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < participants; i++) {
                final int index = i;
                futures.add(executor.submit(() -> {
                    UserHolder.saveUser(user(AUTHOR_ID + 10 + index));
                    ready.countDown();
                    try {
                        assertTrue(start.await(10, TimeUnit.SECONDS));
                        service.hideReply(reply, hideRequest("parallel reason " + index));
                        return null;
                    } finally { UserHolder.removeUser(); }
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            for (Future<?> future : futures) future.get(20, TimeUnit.SECONDS);
            assertEquals(Integer.valueOf(2), mapper.selectById(reply).getStatus());
            assertEquals(Integer.valueOf(1), blogCounter(blogId));
            assertEquals(1, replyAuditCount(reply));
            assertTrue(jdbc.queryForObject("SELECT reason FROM tb_blog_reply_hide_audit WHERE reply_id=?", String.class,
                    reply).startsWith("parallel reason "));
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void hideReplyCurrentReadsWinnerReceiptCommittedAfterItsInitialSnapshot() throws Exception {
        long blogId = blog(AUTHOR_ID, 1);
        long root = comment(blogId, AUTHOR_ID, 0, 0, 0, "snapshot root");
        long reply = comment(blogId, AUTHOR_ID + 1, root, root, 0, "snapshot reply");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            TransactionTemplate repeatableRead = new TransactionTemplate(
                    context.getBean(DataSourceTransactionManager.class));
            repeatableRead.setIsolationLevel(Connection.TRANSACTION_REPEATABLE_READ);
            repeatableRead.execute(status -> {
                assertEquals(0, replyAuditCount(reply), "establish an older ordinary RR snapshot");
                AFTER_MODERATABLE_REPLY_QUERY.set(() -> {
                    runIndependent(executor, AUTHOR_ID + 10,
                            () -> service.hideReply(reply, hideRequest("independent winner")));
                    assertEquals(0, replyAuditCount(reply),
                            "ordinary snapshot must still miss the independently committed receipt");
                });
                service.hideReply(reply, hideRequest("outer losing reason"));
                return null;
            });
            assertEquals(Integer.valueOf(2), mapper.selectById(reply).getStatus());
            assertEquals(Integer.valueOf(0), blogCounter(blogId));
            assertEquals(1, replyAuditCount(reply));
            assertEquals("independent winner", jdbc.queryForObject(
                    "SELECT reason FROM tb_blog_reply_hide_audit WHERE reply_id=?", String.class, reply));
            assertNull(AFTER_MODERATABLE_REPLY_QUERY.get());
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void hideReplyAndAuthorWithdrawalCompetitionHasOneTerminalStateAndOneDecrement() throws Exception {
        long blogId = blog(AUTHOR_ID, 1);
        long root = comment(blogId, AUTHOR_ID, 0, 0, 0, "competing root");
        long reply = comment(blogId, AUTHOR_ID + 1, root, root, 0, "competing reply");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<Boolean> hide;
        Future<Boolean> withdraw;
        try {
            hide = executor.submit(() -> {
                UserHolder.saveUser(user(AUTHOR_ID + 10));
                ready.countDown();
                try {
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    try { service.hideReply(reply, hideRequest("competing admin")); return true; }
                    catch (BusinessException error) { assertEquals(ErrorCode.NOT_FOUND, error.getErrorCode()); return false; }
                } finally { UserHolder.removeUser(); }
            });
            withdraw = executor.submit(() -> {
                UserHolder.saveUser(user(AUTHOR_ID + 1));
                ready.countDown();
                try {
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    try { service.withdrawReply(reply); return true; }
                    catch (BusinessException error) { assertEquals(ErrorCode.NOT_FOUND, error.getErrorCode()); return false; }
                } finally { UserHolder.removeUser(); }
            });
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            boolean hidden = hide.get(20, TimeUnit.SECONDS);
            boolean withdrawn = withdraw.get(20, TimeUnit.SECONDS);
            assertNotEquals(hidden, withdrawn, "exactly one terminal operation should win");
            Integer state = mapper.selectById(reply).getStatus();
            assertEquals(hidden ? Integer.valueOf(2) : Integer.valueOf(3), state);
            assertEquals(hidden ? 1 : 0, replyAuditCount(reply));
            assertEquals(Integer.valueOf(0), blogCounter(blogId));
            assertEquals(2, countComments(blogId));
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void hidePreservesContentChildrenCursorAndFirstOperatorReasonOnRepeat() {
        long blogId = blog(AUTHOR_ID, 3);
        long otherRoot = comment(blogId, AUTHOR_ID, 0, 0, 0, "other root");
        jdbc.execute("ALTER TABLE tb_blog_comments AUTO_INCREMENT=" + LARGE_ID);
        long root = comment(blogId, AUTHOR_ID, 0, 0, 0, "retained root");
        long child = comment(blogId, AUTHOR_ID + 1, root, root, 0, "retained reply");
        BlogCommentPage page = service.listComments(blogId, pageRequest(null, 1));
        UserHolder.saveUser(user(AUTHOR_ID + 10));
        String reason = "synthetic reason ' 合成审核";
        service.hideComment(root, hideRequest(reason));
        UserHolder.saveUser(user(AUTHOR_ID + 11));
        service.hideComment(root, hideRequest("must not replace first reason"));
        assertEquals(Integer.valueOf(2), mapper.selectById(root).getStatus());
        assertEquals("retained root", mapper.selectById(root).getContent());
        assertEquals(Integer.valueOf(0), mapper.selectById(child).getStatus());
        assertEquals("retained reply", mapper.selectById(child).getContent());
        assertEquals(3, countComments(blogId));
        assertEquals(Integer.valueOf(2), blogCounter(blogId));
        assertEquals(1, auditCount(root));
        assertEquals(reason, jdbc.queryForObject("SELECT reason FROM tb_blog_comment_hide_audit WHERE comment_id=?",
                String.class, root));
        assertEquals(Long.valueOf(AUTHOR_ID + 10), jdbc.queryForObject(
                "SELECT operator_user_id FROM tb_blog_comment_hide_audit WHERE comment_id=?", Long.class, root));
        assertEquals(Long.valueOf(blogId), jdbc.queryForObject(
                "SELECT blog_id FROM tb_blog_comment_hide_audit WHERE comment_id=?", Long.class, root));
        assertEquals(Integer.valueOf(2), jdbc.queryForObject(
                "SELECT from_status + to_status FROM tb_blog_comment_hide_audit WHERE comment_id=?", Integer.class, root));
        assertNotNull(jdbc.queryForObject("SELECT create_time FROM tb_blog_comment_hide_audit WHERE comment_id=?",
                java.sql.Timestamp.class, root));
        assertEquals(Collections.singletonList(Long.toString(otherRoot)),
                itemIds(service.listComments(blogId, pageRequest(page.getNextBeforeId(), 1))));
        assertEquals(ErrorCode.NOT_FOUND, assertThrows(BusinessException.class,
                () -> listReplies(root, pageRequest(null, 50))).getErrorCode());
        assertEquals(ErrorCode.NOT_FOUND, assertThrows(BusinessException.class,
                () -> service.createReply(root, replyRequest("blocked"))).getErrorCode());
        UserHolder.saveUser(user(AUTHOR_ID));
        assertEquals(ErrorCode.NOT_FOUND, assertThrows(BusinessException.class,
                () -> service.withdrawComment(root)).getErrorCode());
        UserHolder.saveUser(user(AUTHOR_ID + 1));
        service.withdrawReply(child);
        assertEquals(Integer.valueOf(1), blogCounter(blogId));
        assertEquals(1, auditCount(root));
    }

    @Test
    void hideRejectsUnsupportedStatesRelationsMissingPostAndUnauditedLegacyHiddenRoot() {
        long blogId = blog(AUTHOR_ID, 7);
        long root = comment(blogId, AUTHOR_ID, 0, 0, 0, "root");
        List<Long> rejected = new ArrayList<>(Collections.singletonList(999999L));
        for (Integer state : Arrays.asList(1, 2, 3, null, 4)) {
            rejected.add(comment(blogId, AUTHOR_ID, 0, 0, state, "excluded root"));
        }
        rejected.add(comment(blogId, AUTHOR_ID, root, root, 0, "reply"));
        rejected.add(comment(blogId, AUTHOR_ID, 0, root, 0, "answer only"));
        rejected.add(comment(blogId, AUTHOR_ID, root, 0, 0, "parent only"));
        rejected.add(comment(999999L, AUTHOR_ID, 0, 0, 0, "post missing"));
        long wrongBlogAudit = comment(blogId, AUTHOR_ID, 0, 0, 2, "hidden with wrong audit");
        jdbc.update("INSERT INTO tb_blog_comment_hide_audit(comment_id,blog_id,operator_user_id,reason,from_status,to_status) "
                + "VALUES(?,999999,?,'mismatched context',0,2)", wrongBlogAudit, AUTHOR_ID);
        rejected.add(wrongBlogAudit);
        for (Long id : rejected) {
            assertEquals(ErrorCode.NOT_FOUND, assertThrows(BusinessException.class,
                    () -> service.hideComment(id, hideRequest("must not apply"))).getErrorCode());
        }
        assertEquals(Integer.valueOf(7), blogCounter(blogId));
        assertEquals(Integer.valueOf(0), mapper.selectById(root).getStatus());
        assertEquals(Integer.valueOf(1), jdbc.queryForObject("SELECT COUNT(*) FROM tb_blog_comment_hide_audit", Integer.class));
    }

    @Test
    void hideClampsLegacyCounterWithoutRecountingAndDoesNotDoubleDecrement() {
        for (Integer counter : Arrays.asList(null, 0, 1, 7)) {
            long blogId = blog(AUTHOR_ID, counter);
            long root = comment(blogId, AUTHOR_ID, 0, 0, 0, "root");
            long reply = comment(blogId, AUTHOR_ID + 1, root, root, 0, "legacy uncounted child");
            service.hideComment(root, hideRequest("synthetic reason"));
            service.hideComment(root, hideRequest("repeat"));
            assertEquals(Integer.valueOf(counter == null || counter == 0 ? 0 : counter - 1), blogCounter(blogId));
            assertEquals(Integer.valueOf(0), mapper.selectById(reply).getStatus());
            assertEquals(2, countComments(blogId));
            assertEquals(1, auditCount(root));
        }
    }

    @Test
    void hideStatusCounterAuditAndZeroRowFailuresRollBackAllThreeWrites() {
        long blogId = blog(AUTHOR_ID, 5);
        long root = comment(blogId, AUTHOR_ID, 0, 0, 0, "must stay visible");
        long reply = comment(blogId, AUTHOR_ID + 1, root, root, 0, "unchanged child");
        for (String trigger : Arrays.asList(
                "CREATE TRIGGER fail_reply_update BEFORE UPDATE ON tb_blog_comments FOR EACH ROW "
                        + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic private status failure'",
                "CREATE TRIGGER fail_blog_counter_update BEFORE UPDATE ON tb_blog FOR EACH ROW "
                        + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic private counter failure'",
                "CREATE TRIGGER fail_hide_audit_insert BEFORE INSERT ON tb_blog_comment_hide_audit FOR EACH ROW "
                        + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic private audit failure'",
                "CREATE TRIGGER remove_blog_before_reply_update BEFORE UPDATE ON tb_blog_comments FOR EACH ROW "
                        + "DELETE FROM tb_blog WHERE id=NEW.blog_id")) {
            jdbc.execute(trigger);
            BusinessException error = assertThrows(BusinessException.class,
                    () -> service.hideComment(root, hideRequest("synthetic audit")));
            assertEquals(trigger.contains("DELETE FROM") ? ErrorCode.OPERATION_FAILED : ErrorCode.INTERNAL_ERROR,
                    error.getErrorCode());
            assertNull(error.getCause());
            assertFalse(error.getMessage().contains("private"));
            assertEquals(Integer.valueOf(0), mapper.selectById(root).getStatus());
            assertEquals(Integer.valueOf(0), mapper.selectById(reply).getStatus());
            assertEquals(Integer.valueOf(5), blogCounter(blogId));
            assertEquals(0, auditCount(root));
            dropTrigger("fail_reply_update");
            dropTrigger("fail_blog_counter_update");
            dropTrigger("fail_hide_audit_insert");
            dropTrigger("remove_blog_before_reply_update");
        }
        jdbc.update("INSERT INTO tb_blog_comment_hide_audit(comment_id,blog_id,operator_user_id,reason,from_status,to_status) "
                + "VALUES(?,?,?,'synthetic stale audit',0,2)", root, blogId, AUTHOR_ID);
        assertEquals(ErrorCode.INTERNAL_ERROR, assertThrows(BusinessException.class,
                () -> service.hideComment(root, hideRequest("conflicting audit"))).getErrorCode());
        assertEquals(Integer.valueOf(0), mapper.selectById(root).getStatus());
        assertEquals(Integer.valueOf(5), blogCounter(blogId));
        assertEquals(1, auditCount(root));
    }

    @Test
    void hideCurrentReadRechecksStateRelationshipAndBlogAfterLookup() {
        long blogId = blog(AUTHOR_ID, 8);
        long otherBlog = blog(AUTHOR_ID, 0);
        for (String change : Arrays.asList("status=3", "parent_id=9", "answer_id=9", "blog_id=" + otherBlog)) {
            long root = comment(blogId, AUTHOR_ID, 0, 0, 0, "changed after lookup");
            AFTER_MODERATABLE_COMMENT_QUERY.set(() -> {
                try (Connection connection = datasource.getConnection();
                     PreparedStatement statement = connection.prepareStatement(
                             "UPDATE tb_blog_comments SET " + change + " WHERE id=?")) {
                    statement.setLong(1, root);
                    assertEquals(1, statement.executeUpdate());
                } catch (SQLException error) { throw new AssertionError("concurrent eligibility change failed", error); }
            });
            assertEquals(ErrorCode.NOT_FOUND, assertThrows(BusinessException.class,
                    () -> service.hideComment(root, hideRequest("must not hide changed row"))).getErrorCode());
            assertEquals(Integer.valueOf(8), blogCounter(blogId));
            assertEquals(0, auditCount(root));
            assertNull(AFTER_MODERATABLE_COMMENT_QUERY.get());
        }
    }

    @Test
    void hideRepeatReadsAuditCommittedAfterItsInitialRepeatableReadSnapshot() throws Exception {
        long blogId = blog(AUTHOR_ID, 1);
        long root = comment(blogId, AUTHOR_ID, 0, 0, 0, "audit snapshot race");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            AFTER_MODERATABLE_COMMENT_QUERY.set(() -> runIndependent(executor, AUTHOR_ID + 10,
                    () -> service.hideComment(root, hideRequest("first committed reason"))));
            service.hideComment(root, hideRequest("losing reason"));
            assertEquals(Integer.valueOf(2), mapper.selectById(root).getStatus());
            assertEquals(Integer.valueOf(0), blogCounter(blogId));
            assertEquals(1, auditCount(root));
            assertEquals("first committed reason", jdbc.queryForObject(
                    "SELECT reason FROM tb_blog_comment_hide_audit WHERE comment_id=?", String.class, root));
            assertNull(AFTER_MODERATABLE_COMMENT_QUERY.get());
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void mixedHideWithdrawalAndCreateCompetitionKeepsSingleTerminalActionAndCounter() throws Exception {
        long blogId = blog(AUTHOR_ID, 2);
        long root = comment(blogId, AUTHOR_ID, 0, 0, 0, "competing root");
        long child = comment(blogId, AUTHOR_ID + 1, root, root, 0, "preserved child");
        int participants = 12;
        CountDownLatch ready = new CountDownLatch(participants);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(participants);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < participants; i++) {
                final int action = i;
                futures.add(executor.submit(() -> {
                    UserHolder.saveUser(user(action < 6 ? AUTHOR_ID + 10 : AUTHOR_ID));
                    ready.countDown();
                    try {
                        assertTrue(start.await(10, TimeUnit.SECONDS));
                        if (action >= 10) service.createComment(createRequest(blogId, "new root"));
                        else {
                            try {
                                if (action < 6) service.hideComment(root, hideRequest("first winning audit"));
                                else service.withdrawComment(root);
                            } catch (BusinessException error) { assertEquals(ErrorCode.NOT_FOUND, error.getErrorCode()); }
                        }
                        return null;
                    } finally { UserHolder.removeUser(); }
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            for (Future<?> future : futures) future.get(20, TimeUnit.SECONDS);
            Integer state = mapper.selectById(root).getStatus();
            assertTrue(state == 2 || state == 3);
            assertEquals(state == 2 ? 1 : 0, auditCount(root));
            assertEquals(Integer.valueOf(3), blogCounter(blogId));
            assertEquals(Integer.valueOf(0), mapper.selectById(child).getStatus());
            assertEquals(4, countComments(blogId));
            assertEquals(2, service.listComments(blogId, pageRequest(null, 50)).getItems().size());
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void replyCreateRejectsRootHiddenByRealServiceAfterVisibleLookup() throws Exception {
        long blogId = blog(AUTHOR_ID, 1);
        long root = comment(blogId, AUTHOR_ID, 0, 0, 0, "root hidden during reply create");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            AFTER_VISIBLE_ROOT_QUERY.set(() -> runIndependent(executor, AUTHOR_ID + 10,
                    () -> service.hideComment(root, hideRequest("synthetic hide"))));
            assertEquals(ErrorCode.NOT_FOUND, assertThrows(BusinessException.class,
                    () -> service.createReply(root, replyRequest("must not insert"))).getErrorCode());
            assertEquals(Integer.valueOf(2), mapper.selectById(root).getStatus());
            assertEquals(Integer.valueOf(0), blogCounter(blogId));
            assertEquals(1, countComments(blogId));
            assertEquals(1, auditCount(root));
            assertNull(AFTER_VISIBLE_ROOT_QUERY.get());
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void hideRejectsRootWithdrawnByRealServiceAfterModeratableLookup() throws Exception {
        long blogId = blog(AUTHOR_ID, 1);
        long root = comment(blogId, AUTHOR_ID, 0, 0, 0, "root withdrawn during hide");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            AFTER_MODERATABLE_COMMENT_QUERY.set(() -> runIndependent(executor, AUTHOR_ID,
                    () -> service.withdrawComment(root)));
            assertEquals(ErrorCode.NOT_FOUND, assertThrows(BusinessException.class,
                    () -> service.hideComment(root, hideRequest("must not supersede withdrawal"))).getErrorCode());
            assertEquals(Integer.valueOf(3), mapper.selectById(root).getStatus());
            assertEquals(Integer.valueOf(0), blogCounter(blogId));
            assertEquals(0, auditCount(root));
            assertNull(AFTER_MODERATABLE_COMMENT_QUERY.get());
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void queryHideAuditMapsAllLargeIdsAndRetainsFirstSuccessfulMetadataWithoutWrites() {
        long blogId = blog(LARGE_ID + 2, AUTHOR_ID, 1);
        jdbc.execute("ALTER TABLE tb_blog_comments AUTO_INCREMENT=" + (LARGE_ID + 4));
        jdbc.execute("ALTER TABLE tb_blog_comment_hide_audit AUTO_INCREMENT=" + (LARGE_ID + 6));
        long root = comment(blogId, AUTHOR_ID, 0, 0, 0, "private original content");
        UserHolder.saveUser(user(LARGE_ID + 8));
        String reason = "synthetic ' bound reason 合成";
        service.hideComment(root, hideRequest(reason));
        BlogCommentHideAuditResponse first = service.queryHideAudit(root);
        assertEquals(Long.toString(LARGE_ID + 6), first.getId());
        assertEquals(Long.toString(root), first.getCommentId());
        assertEquals(Long.toString(blogId), first.getBlogId());
        assertEquals(Long.toString(LARGE_ID + 8), first.getOperatorUserId());
        assertEquals(reason, first.getReason());
        assertEquals(Integer.valueOf(0), first.getFromStatus());
        assertEquals(Integer.valueOf(2), first.getToStatus());
        // This fixture pins the JDBC session to UTC. Timestamp.toLocalDateTime() uses the JVM zone instead.
        assertEquals(jdbc.queryForObject("SELECT create_time FROM tb_blog_comment_hide_audit WHERE comment_id=?",
                java.sql.Timestamp.class, root).toInstant().atOffset(java.time.ZoneOffset.UTC).toLocalDateTime(),
                first.getCreateTime());
        UserHolder.saveUser(user(AUTHOR_ID + 10));
        service.hideComment(root, hideRequest("different repeat reason"));
        assertEquals(first, service.queryHideAudit(root));
        assertEquals(first, service.queryHideAudit(root));
        assertEquals(1, auditCount(root));
        assertEquals(Integer.valueOf(0), blogCounter(blogId));
        assertEquals(Integer.valueOf(2), mapper.selectById(root).getStatus());
        assertEquals("private original content", mapper.selectById(root).getContent());
    }

    @Test
    void queryHideAuditDoesNotInferAuditFromLegacyHiddenWithdrawnOrMissingComment() {
        long blogId = blog(AUTHOR_ID, 1);
        long legacy = comment(blogId, AUTHOR_ID, 0, 0, 2, "legacy no audit");
        long withdrawn = comment(blogId, AUTHOR_ID, 0, 0, 0, "author withdrawal no audit");
        service.withdrawComment(withdrawn);
        for (long id : new long[]{legacy, withdrawn, Long.MAX_VALUE}) {
            assertEquals(ErrorCode.NOT_FOUND, assertThrows(BusinessException.class,
                    () -> service.queryHideAudit(id)).getErrorCode());
        }
        assertEquals(Integer.valueOf(0), jdbc.queryForObject("SELECT COUNT(*) FROM tb_blog_comment_hide_audit", Integer.class));
        assertEquals(Integer.valueOf(0), blogCounter(blogId));
        assertEquals(Integer.valueOf(2), mapper.selectById(legacy).getStatus());
        assertEquals(Integer.valueOf(3), mapper.selectById(withdrawn).getStatus());
    }

    @Test
    void queryRetainedAuditDoesNotDependOnCurrentCommentBlogOrOperatorRows() {
        long operator = AUTHOR_ID + 20;
        jdbc.update("INSERT INTO tb_user(id,phone,nick_name) VALUES(?,'00000000721','synthetic audit operator')", operator);
        long blogId = blog(AUTHOR_ID, 1);
        long root = comment(blogId, AUTHOR_ID, 0, 0, 0, "synthetic root");
        UserHolder.saveUser(user(operator));
        service.hideComment(root, hideRequest("retained metadata"));
        BlogCommentHideAuditResponse audit = service.queryHideAudit(root);
        // Only fixture-owned rows in this private MySQL instance; no production deletion use case.
        jdbc.update("DELETE FROM tb_blog_comments WHERE id=?", root);
        jdbc.update("DELETE FROM tb_blog WHERE id=?", blogId);
        jdbc.update("DELETE FROM tb_user WHERE id=?", operator);
        UserHolder.saveUser(user(AUTHOR_ID));
        assertEquals(audit, service.queryHideAudit(root));
        assertEquals(1, auditCount(root));
    }

    @Test
    void queryDoesNotBlockOrExposeUncommittedHideAuditAndSeesItAfterCommit() throws Exception {
        long blogId = blog(AUTHOR_ID, 1);
        long root = comment(blogId, AUTHOR_ID, 0, 0, 0, "commit boundary root");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            AFTER_HIDE_AUDIT_INSERT.set(() -> {
                runIndependent(executor, AUTHOR_ID + 10,
                        () -> assertEquals(ErrorCode.NOT_FOUND, assertThrows(BusinessException.class,
                                () -> service.queryHideAudit(root)).getErrorCode()));
                return 1;
            });
            service.hideComment(root, hideRequest("committed audit"));
            assertNull(AFTER_HIDE_AUDIT_INSERT.get(), "hook must run after the real audit INSERT");
            runIndependent(executor, AUTHOR_ID + 10,
                    () -> assertEquals("committed audit", service.queryHideAudit(root).getReason()));
            assertEquals(1, auditCount(root));
            assertEquals(Integer.valueOf(0), blogCounter(blogId));
        } finally {
            AFTER_HIDE_AUDIT_INSERT.set(null);
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void queryNeverExposesHideAuditWhenWriterRollsBackAfterAuditInsert() throws Exception {
        long blogId = blog(AUTHOR_ID, 1);
        long root = comment(blogId, AUTHOR_ID, 0, 0, 0, "rollback boundary root");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            AFTER_HIDE_AUDIT_INSERT.set(() -> {
                runIndependent(executor, AUTHOR_ID + 10,
                        () -> assertEquals(ErrorCode.NOT_FOUND, assertThrows(BusinessException.class,
                                () -> service.queryHideAudit(root)).getErrorCode()));
                // Test-only zero affected-row result AFTER the real INSERT: production row-count guard aborts.
                return 0;
            });
            assertEquals(ErrorCode.OPERATION_FAILED, assertThrows(BusinessException.class,
                    () -> service.hideComment(root, hideRequest("must roll back"))).getErrorCode());
            assertNull(AFTER_HIDE_AUDIT_INSERT.get());
            runIndependent(executor, AUTHOR_ID + 10,
                    () -> assertEquals(ErrorCode.NOT_FOUND, assertThrows(BusinessException.class,
                            () -> service.queryHideAudit(root)).getErrorCode()));
            assertEquals(0, auditCount(root));
            assertEquals(Integer.valueOf(1), blogCounter(blogId));
            assertEquals(Integer.valueOf(0), mapper.selectById(root).getStatus());
            assertEquals("rollback boundary root", mapper.selectById(root).getContent());
        } finally {
            AFTER_HIDE_AUDIT_INSERT.set(null);
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void queryReplyHideAuditMapsAllLargeIdsUtcTimeAndKeepsFirstReceiptWithoutWrites() {
        long blogId = LARGE_ID + 2;
        jdbc.execute("ALTER TABLE tb_blog AUTO_INCREMENT=" + (LARGE_ID + 2));
        blog(blogId, AUTHOR_ID, 1);
        jdbc.execute("ALTER TABLE tb_blog_comments AUTO_INCREMENT=" + (LARGE_ID + 4));
        long root = comment(blogId, AUTHOR_ID, 0, 0, 0, "private root");
        long reply = comment(blogId, AUTHOR_ID + 1, root, root, 0, "private reply");
        jdbc.execute("ALTER TABLE tb_blog_reply_hide_audit AUTO_INCREMENT=" + (LARGE_ID + 8));
        long operator = LARGE_ID + 10;
        String reason = "reply receipt ' 保留原文";
        UserHolder.saveUser(user(operator));

        service.hideReply(reply, hideRequest(reason));
        BlogReplyHideAuditResponse first = service.queryReplyHideAudit(reply);

        assertEquals(Long.toString(LARGE_ID + 8), first.getId());
        assertEquals(Long.toString(reply), first.getReplyId());
        assertEquals(Long.toString(blogId), first.getBlogId());
        assertEquals(Long.toString(root), first.getRootCommentId());
        assertEquals(Long.toString(operator), first.getOperatorUserId());
        assertEquals(reason, first.getReason());
        assertEquals(Integer.valueOf(0), first.getFromStatus());
        assertEquals(Integer.valueOf(2), first.getToStatus());
        assertEquals(jdbc.queryForObject("SELECT create_time FROM tb_blog_reply_hide_audit WHERE reply_id=?",
                        java.sql.Timestamp.class, reply).toInstant().atOffset(java.time.ZoneOffset.UTC).toLocalDateTime(),
                first.getCreateTime());

        try {
            jdbc.execute("CREATE TRIGGER reject_repeat_reply_comment_update BEFORE UPDATE ON tb_blog_comments "
                    + "FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='repeat must not update comment'");
            jdbc.execute("CREATE TRIGGER reject_repeat_reply_blog_update BEFORE UPDATE ON tb_blog "
                    + "FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='repeat must not update blog'");
            jdbc.execute("CREATE TRIGGER reject_repeat_reply_audit_update BEFORE UPDATE ON tb_blog_reply_hide_audit "
                    + "FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='repeat must not update audit'");
            UserHolder.saveUser(user(operator + 1));
            service.hideReply(reply, hideRequest("different operator and reason"));
            assertEquals(first, service.queryReplyHideAudit(reply));
            assertEquals(Integer.valueOf(2), mapper.selectById(reply).getStatus());
            assertEquals(Integer.valueOf(0), blogCounter(blogId));
            assertEquals(1, replyAuditCount(reply));
        } finally {
            dropTrigger("reject_repeat_reply_comment_update");
            dropTrigger("reject_repeat_reply_blog_update");
            dropTrigger("reject_repeat_reply_audit_update");
        }
    }

    @Test
    void replyHideAuditRemainsSeparateFromRootAuditLookupAndPages() {
        long blogId = blog(AUTHOR_ID, 3);
        long root = comment(blogId, AUTHOR_ID, 0, 0, 0, "root receipt");
        long reply = comment(blogId, AUTHOR_ID + 1, root, root, 0, "reply receipt");
        service.hideComment(root, hideRequest("V008 root receipt"));
        service.hideReply(reply, hideRequest("V013 reply receipt"));

        assertEquals("V013 reply receipt", service.queryReplyHideAudit(reply).getReason());
        assertEquals("V008 root receipt", service.queryHideAudit(root).getReason());
        BlogCommentHideAuditPage rootPage = service.listHideAudits(blogId, hideAuditPageRequest(null, 20));
        assertEquals(1, rootPage.getItems().size());
        assertEquals(Long.toString(root), rootPage.getItems().get(0).getCommentId());
        assertEquals(1, auditCount(root));
        assertEquals(1, replyAuditCount(reply));
        BusinessException rootAsReply = assertThrows(BusinessException.class,
                () -> service.queryReplyHideAudit(root));
        assertEquals(ErrorCode.NOT_FOUND, rootAsReply.getErrorCode());
        assertEquals(404, rootAsReply.getErrorCode().getHttpStatus().value());
    }

    @Test
    void replyHideAuditDoesNotInventReceiptsForLegacyHiddenWithdrawnNormalOrMissingReplies() {
        long blogId = blog(AUTHOR_ID, 3);
        long root = comment(blogId, AUTHOR_ID, 0, 0, 0, "root");
        long hidden = comment(blogId, AUTHOR_ID, root, root, 2, "legacy hidden reply");
        long withdrawn = comment(blogId, AUTHOR_ID, root, root, 0, "withdrawn reply");
        long normal = comment(blogId, AUTHOR_ID, root, root, 0, "normal reply");
        service.withdrawReply(withdrawn);

        for (long id : new long[]{hidden, withdrawn, normal, Long.MAX_VALUE}) {
            BusinessException missing = assertThrows(BusinessException.class,
                    () -> service.queryReplyHideAudit(id));
            assertEquals(ErrorCode.NOT_FOUND, missing.getErrorCode());
            assertEquals(404, missing.getErrorCode().getHttpStatus().value());
        }
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM tb_blog_reply_hide_audit", Integer.class));
        assertEquals(Integer.valueOf(2), mapper.selectById(hidden).getStatus());
        assertEquals(Integer.valueOf(3), mapper.selectById(withdrawn).getStatus());
        assertEquals(Integer.valueOf(0), mapper.selectById(normal).getStatus());
    }

    @Test
    void replyHideAuditSurvivesDeletionOfReplyRootPostAndOperatorRows() {
        long operator = LARGE_ID + 50;
        jdbc.update("INSERT INTO tb_user(id,phone,nick_name) VALUES(?,'00000000739','reply audit operator')", operator);
        long blogId = blog(AUTHOR_ID, 1);
        long root = comment(blogId, AUTHOR_ID, 0, 0, 0, "deleted root");
        long reply = comment(blogId, AUTHOR_ID + 1, root, root, 0, "deleted reply");
        UserHolder.saveUser(user(operator));
        service.hideReply(reply, hideRequest("retained after deletion"));
        BlogReplyHideAuditResponse expected = service.queryReplyHideAudit(reply);

        jdbc.update("DELETE FROM tb_blog_comments WHERE id=?", reply);
        jdbc.update("DELETE FROM tb_blog_comments WHERE id=?", root);
        jdbc.update("DELETE FROM tb_blog WHERE id=?", blogId);
        jdbc.update("DELETE FROM tb_user WHERE id=?", operator);
        UserHolder.saveUser(user(AUTHOR_ID));

        assertEquals(expected, service.queryReplyHideAudit(reply));
        assertEquals(1, replyAuditCount(reply));
    }

    @Test
    void replyAuditQueryDoesNotWaitForOrExposeUncommittedInsertAndSeesReceiptAfterCommit() throws Exception {
        long blogId = blog(AUTHOR_ID, 1);
        long root = comment(blogId, AUTHOR_ID, 0, 0, 0, "commit boundary root");
        long reply = comment(blogId, AUTHOR_ID + 1, root, root, 0, "commit boundary reply");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            AFTER_REPLY_HIDE_AUDIT_INSERT.set(() -> {
                runIndependent(executor, AUTHOR_ID + 10, () -> {
                    BusinessException invisible = assertThrows(BusinessException.class,
                            () -> service.queryReplyHideAudit(reply));
                    assertEquals(ErrorCode.NOT_FOUND, invisible.getErrorCode());
                });
                return 1;
            });
            service.hideReply(reply, hideRequest("committed reply receipt"));
            assertNull(AFTER_REPLY_HIDE_AUDIT_INSERT.get());
            runIndependent(executor, AUTHOR_ID + 10,
                    () -> assertEquals("committed reply receipt", service.queryReplyHideAudit(reply).getReason()));
            assertEquals(1, replyAuditCount(reply));
            assertEquals(Integer.valueOf(2), mapper.selectById(reply).getStatus());
            assertEquals(Integer.valueOf(0), blogCounter(blogId));
        } finally {
            AFTER_REPLY_HIDE_AUDIT_INSERT.set(null);
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void replyAuditQueryNeverExposesReceiptWhenRealInsertIsRolledBack() throws Exception {
        long blogId = blog(AUTHOR_ID, 1);
        long root = comment(blogId, AUTHOR_ID, 0, 0, 0, "rollback boundary root");
        long reply = comment(blogId, AUTHOR_ID + 1, root, root, 0, "rollback boundary reply");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            AFTER_REPLY_HIDE_AUDIT_INSERT.set(() -> {
                runIndependent(executor, AUTHOR_ID + 10, () -> {
                    BusinessException invisible = assertThrows(BusinessException.class,
                            () -> service.queryReplyHideAudit(reply));
                    assertEquals(ErrorCode.NOT_FOUND, invisible.getErrorCode());
                });
                return 0;
            });
            BusinessException affectedRows = assertThrows(BusinessException.class,
                    () -> service.hideReply(reply, hideRequest("must roll back")));
            assertEquals(ErrorCode.OPERATION_FAILED, affectedRows.getErrorCode());
            assertEquals(422, affectedRows.getErrorCode().getHttpStatus().value());
            assertNull(AFTER_REPLY_HIDE_AUDIT_INSERT.get());
            runIndependent(executor, AUTHOR_ID + 10, () -> {
                BusinessException absent = assertThrows(BusinessException.class,
                        () -> service.queryReplyHideAudit(reply));
                assertEquals(ErrorCode.NOT_FOUND, absent.getErrorCode());
            });
            assertEquals(0, replyAuditCount(reply));
            assertEquals(Integer.valueOf(0), mapper.selectById(reply).getStatus());
            assertEquals(Integer.valueOf(1), blogCounter(blogId));
        } finally {
            AFTER_REPLY_HIDE_AUDIT_INSERT.set(null);
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void replyAuditQueryUsesItsRepeatableReadSnapshotAndNewTransactionSeesCommittedReceipt() throws Exception {
        long blogId = blog(AUTHOR_ID, 1);
        long root = comment(blogId, AUTHOR_ID, 0, 0, 0, "snapshot root");
        long reply = comment(blogId, AUTHOR_ID + 1, root, root, 0, "snapshot reply");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            TransactionTemplate repeatableRead = new TransactionTemplate(
                    context.getBean(DataSourceTransactionManager.class));
            repeatableRead.setReadOnly(true);
            repeatableRead.setIsolationLevel(Connection.TRANSACTION_REPEATABLE_READ);
            repeatableRead.execute(status -> {
                assertEquals(0, replyAuditCount(reply), "establish the old ordinary RR snapshot");
                runIndependent(executor, AUTHOR_ID + 10,
                        () -> service.hideReply(reply, hideRequest("snapshot winner")));
                BusinessException oldSnapshot = assertThrows(BusinessException.class,
                        () -> service.queryReplyHideAudit(reply));
                assertEquals(ErrorCode.NOT_FOUND, oldSnapshot.getErrorCode());
                status.setRollbackOnly();
                return null;
            });
            runIndependent(executor, AUTHOR_ID + 10, () ->
                    assertEquals("snapshot winner", service.queryReplyHideAudit(reply).getReason()));
            assertEquals(1, replyAuditCount(reply));
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void replyAuditOrdinarySelectDoesNotWaitOnUncommittedAuditRowUpdate() throws Exception {
        long blogId = blog(AUTHOR_ID, 1);
        long root = comment(blogId, AUTHOR_ID, 0, 0, 0, "locked audit root");
        long reply = comment(blogId, AUTHOR_ID + 1, root, root, 0, "locked audit reply");
        service.hideReply(reply, hideRequest("committed old metadata"));
        BlogReplyHideAuditResponse committed = service.queryReplyHideAudit(reply);
        CountDownLatch updateHeld = new CountDownLatch(1);
        CountDownLatch releaseUpdate = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<?> locker = null;
        try {
            locker = executor.submit(() -> {
                UserHolder.saveUser(user(AUTHOR_ID + 20));
                try (Connection connection = datasource.getConnection();
                     PreparedStatement update = connection.prepareStatement(
                             "UPDATE tb_blog_reply_hide_audit SET reason='uncommitted replacement' WHERE reply_id=?")) {
                    connection.setAutoCommit(false);
                    update.setLong(1, reply);
                    assertEquals(1, update.executeUpdate());
                    updateHeld.countDown();
                    assertTrue(releaseUpdate.await(10, TimeUnit.SECONDS));
                    connection.rollback();
                } catch (Exception error) {
                    throw new AssertionError("could not hold the private audit row update", error);
                } finally {
                    UserHolder.removeUser();
                }
            });
            assertTrue(updateHeld.await(5, TimeUnit.SECONDS), "audit UPDATE did not acquire its row lock");
            Future<BlogReplyHideAuditResponse> query = executor.submit(() -> {
                UserHolder.saveUser(user(AUTHOR_ID + 21));
                try { return service.queryReplyHideAudit(reply); }
                finally { UserHolder.removeUser(); }
            });
            assertEquals(committed, query.get(5, TimeUnit.SECONDS),
                    "ordinary SELECT should read the committed version without waiting for the updater");
        } finally {
            releaseUpdate.countDown();
            try {
                if (locker != null) locker.get(10, TimeUnit.SECONDS);
            } finally {
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
            }
        }
        assertEquals(committed, service.queryReplyHideAudit(reply));
    }

    @Test
    void replyAuditSqlFailureIsMappedToGenericInternalErrorAndTableIsRestored() {
        long blogId = blog(AUTHOR_ID, 1);
        long root = comment(blogId, AUTHOR_ID, 0, 0, 0, "table rename root");
        long reply = comment(blogId, AUTHOR_ID + 1, root, root, 0, "table rename reply");
        service.hideReply(reply, hideRequest("retained while table unavailable"));
        jdbc.execute("RENAME TABLE tb_blog_reply_hide_audit TO tb_blog_reply_hide_audit_saved");
        boolean renamed = true;
        try {
            BusinessException failure = assertThrows(BusinessException.class,
                    () -> service.queryReplyHideAudit(reply));
            assertEquals(ErrorCode.INTERNAL_ERROR, failure.getErrorCode());
            assertEquals(500, failure.getErrorCode().getHttpStatus().value());
            assertNull(failure.getCause());
            assertFalse(failure.getMessage().contains("tb_blog_reply_hide_audit"));
        } finally {
            if (renamed) {
                jdbc.execute("RENAME TABLE tb_blog_reply_hide_audit_saved TO tb_blog_reply_hide_audit");
                renamed = false;
            }
        }
        assertEquals("retained while table unavailable", service.queryReplyHideAudit(reply).getReason());
    }

    @Test
    void auditPageIndexRepeatsPreservesRowsAndDoesNotRepairSameNameWrongShape() throws Exception {
        long blogId = blog(AUTHOR_ID, 1);
        long root = comment(blogId, AUTHOR_ID, 0, 0, 0, "index fixture root");
        service.hideComment(root, hideRequest("index fixture audit"));
        BlogCommentHideAuditResponse before = service.queryHideAudit(root);
        ClassPathResource migration = new ClassPathResource("db/migration/V009__add_blog_comment_hide_audit_page_index.sql");
        try (Connection connection = datasource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, migration);
            ScriptUtils.executeSqlScript(connection, migration);
        }
        assertEquals(Arrays.asList("blog_id", "id"), auditPageIndexColumns());
        assertEquals(before, service.queryHideAudit(root));
        // An empty second database belongs to this private mysqld; V009 must not silently pass without V008.
        try (Connection connection = datasource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE audit_page_missing_table_test");
            try {
                statement.execute("USE audit_page_missing_table_test");
                assertThrows(org.springframework.jdbc.datasource.init.ScriptStatementFailedException.class,
                        () -> ScriptUtils.executeSqlScript(connection, migration));
            } finally {
                statement.execute("DROP DATABASE audit_page_missing_table_test");
            }
        }
        // Private fixture-only DDL verifies the migration's documented name-only idempotence boundary.
        jdbc.execute("DROP INDEX idx_blog_comment_hide_audit_page ON tb_blog_comment_hide_audit");
        try {
            jdbc.execute("CREATE INDEX idx_blog_comment_hide_audit_page ON tb_blog_comment_hide_audit(id)");
            try (Connection connection = datasource.getConnection()) {
                ScriptUtils.executeSqlScript(connection, migration);
            }
            assertEquals(Collections.singletonList("id"), auditPageIndexColumns());
            assertEquals(before, service.queryHideAudit(root));
        } finally {
            jdbc.execute("DROP INDEX idx_blog_comment_hide_audit_page ON tb_blog_comment_hide_audit");
            jdbc.execute("CREATE INDEX idx_blog_comment_hide_audit_page ON tb_blog_comment_hide_audit(blog_id,id)");
        }
    }

    @Test
    void auditPagesUseAuditIdCursorAndPostFilterWithLosslessMetadataAndNoWrites() {
        long blogId = blog(LARGE_ID + 20, AUTHOR_ID, 3);
        long otherBlog = blog(LARGE_ID + 21, AUTHOR_ID, 1);
        jdbc.execute("ALTER TABLE tb_blog_comments AUTO_INCREMENT=" + (LARGE_ID + 200));
        jdbc.execute("ALTER TABLE tb_blog_comment_hide_audit AUTO_INCREMENT=" + (LARGE_ID + 100));
        long firstRoot = comment(blogId, AUTHOR_ID, 0, 0, 0, "first root original");
        long secondRoot = comment(blogId, AUTHOR_ID, 0, 0, 0, "second root original");
        long thirdRoot = comment(blogId, AUTHOR_ID, 0, 0, 0, "third root original");
        long otherRoot = comment(otherBlog, AUTHOR_ID, 0, 0, 0, "other post root");
        UserHolder.saveUser(user(LARGE_ID + 30));
        service.hideComment(thirdRoot, hideRequest("third root hidden first"));
        BlogCommentHideAuditResponse oldest = service.queryHideAudit(thirdRoot);
        service.hideComment(otherRoot, hideRequest("other post excluded"));
        service.hideComment(firstRoot, hideRequest("first root hidden second"));
        BlogCommentHideAuditResponse middle = service.queryHideAudit(firstRoot);
        service.hideComment(secondRoot, hideRequest("second root hidden last"));
        BlogCommentHideAuditResponse newest = service.queryHideAudit(secondRoot);

        BlogCommentHideAuditPage first = service.listHideAudits(blogId, hideAuditPageRequest(null, 2));
        assertEquals(Arrays.asList(newest, middle), first.getItems());
        assertTrue(first.isHasNext());
        assertEquals(middle.getId(), first.getNextBeforeId(), "cursor is last returned AUDIT id, not comment/lookahead id");
        assertNotEquals(middle.getCommentId(), first.getNextBeforeId());
        BlogCommentHideAuditPage second = service.listHideAudits(blogId,
                hideAuditPageRequest(Long.valueOf(first.getNextBeforeId()), 2));
        assertEquals(Collections.singletonList(oldest), second.getItems());
        assertFalse(second.isHasNext());
        assertNull(second.getNextBeforeId());
        assertEquals(Arrays.asList(newest, middle, oldest),
                service.listHideAudits(blogId, hideAuditPageRequest(null, 3)).getItems());
        assertEquals(Long.toString(blogId), newest.getBlogId());
        assertEquals(Long.toString(LARGE_ID + 30), newest.getOperatorUserId());
        assertEquals(first.getItems(), service.listHideAudits(blogId, hideAuditPageRequest(null, 2)).getItems());
        assertEquals(Integer.valueOf(0), blogCounter(blogId));
        assertEquals(Integer.valueOf(0), blogCounter(otherBlog));
        assertEquals(Integer.valueOf(4), jdbc.queryForObject("SELECT COUNT(*) FROM tb_blog_comment_hide_audit", Integer.class));
        assertEquals("first root original", mapper.selectById(firstRoot).getContent());
        assertEquals(Integer.valueOf(2), mapper.selectById(firstRoot).getStatus());
    }

    @Test
    void auditPagesReturnEmptyOrRetainedRecordsWithoutCurrentPostCommentOrActorLookup() {
        long blogId = blog(AUTHOR_ID, 2);
        long root = comment(blogId, AUTHOR_ID, 0, 0, 0, "retained list root");
        long legacy = comment(blogId, AUTHOR_ID, 0, 0, 2, "no invented audit");
        long operator = AUTHOR_ID + 31;
        jdbc.update("INSERT INTO tb_user(id,phone,nick_name) VALUES(?,'00000000732','synthetic page operator')", operator);
        UserHolder.saveUser(user(operator));
        service.hideComment(root, hideRequest("retained list metadata"));
        BlogCommentHideAuditResponse audit = service.queryHideAudit(root);
        assertEquals(Collections.singletonList(audit), service.listHideAudits(blogId, hideAuditPageRequest(null, 50)).getItems());
        assertEquals(0, auditCount(legacy));
        for (long missing : new long[]{Long.MAX_VALUE, blogId + 9999}) {
            BlogCommentHideAuditPage empty = service.listHideAudits(missing, hideAuditPageRequest(null, 20));
            assertTrue(empty.getItems().isEmpty());
            assertFalse(empty.isHasNext());
            assertNull(empty.getNextBeforeId());
        }
        assertTrue(service.listHideAudits(blogId, hideAuditPageRequest(1L, 20)).getItems().isEmpty());
        // Only this test's synthetic rows, not an application deletion API.
        jdbc.update("DELETE FROM tb_blog_comments WHERE blog_id=?", blogId);
        jdbc.update("DELETE FROM tb_blog WHERE id=?", blogId);
        jdbc.update("DELETE FROM tb_user WHERE id=?", operator);
        UserHolder.saveUser(user(AUTHOR_ID));
        assertEquals(Collections.singletonList(audit), service.listHideAudits(blogId, hideAuditPageRequest(null, 50)).getItems());
    }

    @Test
    void auditPageDoesNotWaitForOrExposeUncommittedHideAndReadsItAfterCommit() throws Exception {
        long blogId = blog(AUTHOR_ID, 1);
        long root = comment(blogId, AUTHOR_ID, 0, 0, 0, "page commit boundary root");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            AFTER_HIDE_AUDIT_INSERT.set(() -> {
                runIndependent(executor, AUTHOR_ID + 10, () -> {
                    BlogCommentHideAuditPage page = service.listHideAudits(blogId, hideAuditPageRequest(null, 50));
                    assertTrue(page.getItems().isEmpty());
                    assertFalse(page.isHasNext());
                });
                return 1;
            });
            service.hideComment(root, hideRequest("page committed metadata"));
            assertNull(AFTER_HIDE_AUDIT_INSERT.get());
            runIndependent(executor, AUTHOR_ID + 10, () -> {
                BlogCommentHideAuditPage page = service.listHideAudits(blogId, hideAuditPageRequest(null, 50));
                assertEquals(1, page.getItems().size());
                assertEquals(Long.toString(root), page.getItems().get(0).getCommentId());
                assertEquals("page committed metadata", page.getItems().get(0).getReason());
            });
        } finally {
            AFTER_HIDE_AUDIT_INSERT.set(null);
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void auditPageNeverExposesHideThatRollsBackAfterRealAuditInsert() throws Exception {
        long blogId = blog(AUTHOR_ID, 1);
        long root = comment(blogId, AUTHOR_ID, 0, 0, 0, "page rollback boundary root");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            AFTER_HIDE_AUDIT_INSERT.set(() -> {
                runIndependent(executor, AUTHOR_ID + 10,
                        () -> assertTrue(service.listHideAudits(blogId, hideAuditPageRequest(null, 50)).getItems().isEmpty()));
                return 0;
            });
            assertEquals(ErrorCode.OPERATION_FAILED, assertThrows(BusinessException.class,
                    () -> service.hideComment(root, hideRequest("page must not appear"))).getErrorCode());
            assertNull(AFTER_HIDE_AUDIT_INSERT.get());
            runIndependent(executor, AUTHOR_ID + 10,
                    () -> assertTrue(service.listHideAudits(blogId, hideAuditPageRequest(null, 50)).getItems().isEmpty()));
            assertEquals(0, auditCount(root));
            assertEquals(Integer.valueOf(1), blogCounter(blogId));
            assertEquals(Integer.valueOf(0), mapper.selectById(root).getStatus());
        } finally {
            AFTER_HIDE_AUDIT_INSERT.set(null);
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void auditPagesBoundRealMySqlRowsAtFiftyAndReturnOneRemainingFixture() {
        long blogId = LARGE_ID + 700;
        // Synthetic imported metadata, not 51 claims of executing the application hide transaction.
        for (int index = 0; index < 51; index++) {
            jdbc.update("INSERT INTO tb_blog_comment_hide_audit(id,comment_id,blog_id,operator_user_id,reason,from_status,to_status) "
                            + "VALUES(?,?,?,?,'synthetic bounded audit fixture',0,2)",
                    LARGE_ID + 900 + index, LARGE_ID + 800 + index, blogId, LARGE_ID + 600);
        }
        BlogCommentHideAuditPage first = service.listHideAudits(blogId, hideAuditPageRequest(Long.MAX_VALUE, 50));
        assertEquals(50, first.getItems().size());
        assertTrue(first.isHasNext());
        assertEquals(Long.toString(LARGE_ID + 950), first.getItems().get(0).getId());
        assertEquals(Long.toString(LARGE_ID + 901), first.getNextBeforeId());
        BlogCommentHideAuditPage last = service.listHideAudits(blogId,
                hideAuditPageRequest(Long.valueOf(first.getNextBeforeId()), 50));
        assertEquals(1, last.getItems().size());
        assertEquals(Long.toString(LARGE_ID + 900), last.getItems().get(0).getId());
        assertFalse(last.isHasNext());
        assertNull(last.getNextBeforeId());
        assertEquals(Integer.valueOf(51), jdbc.queryForObject(
                "SELECT COUNT(*) FROM tb_blog_comment_hide_audit WHERE blog_id=?", Integer.class, blogId));
    }

    @Test
    void replyAuditPagesUseAuditIdCursorAcrossBlogsAndStaySeparateFromRootAuditPages() {
        long blogId = blog(AUTHOR_ID, 3);
        long otherBlog = blog(AUTHOR_ID, 1);
        jdbc.execute("ALTER TABLE tb_blog_reply_hide_audit AUTO_INCREMENT=" + (LARGE_ID + 1100));
        long root = comment(blogId, AUTHOR_ID, 0, 0, 0, "reply page root");
        long createdFirst = comment(blogId, AUTHOR_ID + 1, root, root, 0, "created first");
        long createdSecond = comment(blogId, AUTHOR_ID + 2, root, root, 0, "created second");
        long createdLast = comment(blogId, AUTHOR_ID + 3, root, root, 0, "created last");
        long otherRoot = comment(otherBlog, AUTHOR_ID, 0, 0, 0, "other blog root");
        long otherReply = comment(otherBlog, AUTHOR_ID + 4, otherRoot, otherRoot, 0, "other blog reply");

        service.hideComment(root, hideRequest("V008 root receipt"));
        UserHolder.saveUser(user(AUTHOR_ID + 20));
        service.hideReply(createdLast, hideRequest("hidden first"));
        BlogReplyHideAuditResponse oldestAudit = service.queryReplyHideAudit(createdLast);
        service.hideReply(otherReply, hideRequest("other blog receipt"));
        service.hideReply(createdFirst, hideRequest("hidden second"));
        BlogReplyHideAuditResponse middleAudit = service.queryReplyHideAudit(createdFirst);
        service.hideReply(createdSecond, hideRequest("hidden last"));
        BlogReplyHideAuditResponse newestAudit = service.queryReplyHideAudit(createdSecond);

        BlogReplyHideAuditPage first = service.listReplyHideAudits(blogId, hideAuditPageRequest(null, 2));
        assertEquals(Arrays.asList(newestAudit, middleAudit), first.getItems());
        assertTrue(first.isHasNext());
        assertEquals(middleAudit.getId(), first.getNextBeforeId());
        assertNotEquals(middleAudit.getReplyId(), first.getNextBeforeId(),
                "the cursor must identify an audit row, not its reply");
        BlogReplyHideAuditPage second = service.listReplyHideAudits(blogId,
                hideAuditPageRequest(Long.valueOf(first.getNextBeforeId()), 2));
        assertEquals(Collections.singletonList(oldestAudit), second.getItems());
        assertFalse(second.isHasNext());
        assertNull(second.getNextBeforeId());
        assertEquals(Arrays.asList(newestAudit, middleAudit, oldestAudit),
                service.listReplyHideAudits(blogId, hideAuditPageRequest(null, 3)).getItems());
        assertNotEquals(newestAudit.getReplyId(), newestAudit.getId());
        assertEquals(0, service.listReplyHideAudits(blogId,
                hideAuditPageRequest(Long.valueOf(oldestAudit.getId()), 20)).getItems().size());
        BlogCommentHideAuditPage rootPage = service.listHideAudits(blogId, hideAuditPageRequest(null, 20));
        assertEquals(1, rootPage.getItems().size());
        assertEquals(Long.toString(root), rootPage.getItems().get(0).getCommentId());
        assertEquals(3, service.listReplyHideAudits(blogId, hideAuditPageRequest(null, 20)).getItems().size());
        assertEquals(1, service.listReplyHideAudits(otherBlog, hideAuditPageRequest(null, 20)).getItems().size());
    }

    @Test
    void replyAuditPagesDefaultToTwentyAndCapAtFiftyUsingImportedMetadata() {
        long blogId = LARGE_ID + 710;
        for (int index = 0; index < 51; index++) {
            jdbc.update("INSERT INTO tb_blog_reply_hide_audit(id,reply_id,blog_id,root_comment_id,operator_user_id,reason,from_status,to_status) "
                            + "VALUES(?,?,?,?,?,'synthetic imported reply audit',0,2)",
                    LARGE_ID + 1200 + index, LARGE_ID + 1300 + index, blogId,
                    LARGE_ID + 1400 + index, LARGE_ID + 1500);
        }
        BlogCommentHideAuditPageRequest defaultRequest = new BlogCommentHideAuditPageRequest();
        BlogReplyHideAuditPage defaultPage = service.listReplyHideAudits(blogId, defaultRequest);
        assertEquals(Integer.valueOf(20), defaultRequest.getSize());
        assertEquals(20, defaultPage.getItems().size());
        assertTrue(defaultPage.isHasNext());
        assertEquals(defaultPage.getItems().get(19).getId(), defaultPage.getNextBeforeId());

        BlogCommentHideAuditPageRequest maximumRequest = hideAuditPageRequest(null, 50);
        BlogReplyHideAuditPage maximumPage = service.listReplyHideAudits(blogId, maximumRequest);
        assertEquals(50, maximumPage.getItems().size());
        assertTrue(maximumPage.isHasNext());
        assertEquals(maximumPage.getItems().get(49).getId(), maximumPage.getNextBeforeId());
        BlogReplyHideAuditPage last = service.listReplyHideAudits(blogId,
                hideAuditPageRequest(Long.valueOf(maximumPage.getNextBeforeId()), 50));
        assertEquals(1, last.getItems().size());
        assertFalse(last.isHasNext());
        assertNull(last.getNextBeforeId());
        assertEquals(Integer.valueOf(51), jdbc.queryForObject(
                "SELECT COUNT(*) FROM tb_blog_reply_hide_audit WHERE blog_id=?", Integer.class, blogId));
    }

    @Test
    void replyAuditPagesReturnEmptyForMissingBlogAndSurviveDeletingEveryRelatedEntity() {
        long blogId = blog(AUTHOR_ID, 2);
        long root = comment(blogId, AUTHOR_ID, 0, 0, 0, "removable audit root");
        long reply = comment(blogId, AUTHOR_ID + 1, root, root, 0, "removable audit reply");
        long actor = LARGE_ID + 1600;
        jdbc.update("INSERT INTO tb_user(id,phone,nick_name) VALUES(?,'00000000816','reply page actor')", actor);
        UserHolder.saveUser(user(actor));
        service.hideReply(reply, hideRequest("retained independent reply metadata"));
        BlogReplyHideAuditResponse audit = service.queryReplyHideAudit(reply);

        for (long missing : new long[]{Long.MAX_VALUE, blogId + 9999}) {
            BlogReplyHideAuditPage empty = service.listReplyHideAudits(missing, hideAuditPageRequest(null, 20));
            assertTrue(empty.getItems().isEmpty());
            assertFalse(empty.isHasNext());
            assertNull(empty.getNextBeforeId());
        }
        assertTrue(service.listReplyHideAudits(blogId, hideAuditPageRequest(1L, 20)).getItems().isEmpty());
        jdbc.update("DELETE FROM tb_blog_comments WHERE id=?", reply);
        jdbc.update("DELETE FROM tb_blog_comments WHERE id=?", root);
        jdbc.update("DELETE FROM tb_blog WHERE id=?", blogId);
        jdbc.update("DELETE FROM tb_user WHERE id=?", actor);
        UserHolder.saveUser(user(AUTHOR_ID));
        BlogReplyHideAuditPage retained = service.listReplyHideAudits(blogId, hideAuditPageRequest(null, 20));
        assertEquals(Collections.singletonList(audit), retained.getItems());
        assertEquals("retained independent reply metadata", retained.getItems().get(0).getReason());
    }

    @Test
    void replyAuditPageIsReadOnlyAndDoesNotChangeStatusCounterOrAudit() {
        long blogId = blog(AUTHOR_ID, 2);
        long root = comment(blogId, AUTHOR_ID, 0, 0, 0, "read-only page root");
        long reply = comment(blogId, AUTHOR_ID + 1, root, root, 0, "read-only page reply");
        service.hideReply(reply, hideRequest("stable page metadata"));
        BlogReplyHideAuditResponse before = service.queryReplyHideAudit(reply);
        Integer statusBefore = mapper.selectById(reply).getStatus();
        Integer counterBefore = blogCounter(blogId);
        jdbc.execute("CREATE TRIGGER reject_reply_audit_page_update BEFORE UPDATE ON tb_blog_reply_hide_audit "
                + "FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='reply audit page is read only'");
        try {
            BlogReplyHideAuditPage page = service.listReplyHideAudits(blogId, hideAuditPageRequest(null, 20));
            assertEquals(Collections.singletonList(before), page.getItems());
            assertEquals(before, service.queryReplyHideAudit(reply));
            assertEquals(statusBefore, mapper.selectById(reply).getStatus());
            assertEquals(counterBefore, blogCounter(blogId));
            assertEquals(1, replyAuditCount(reply));
        } finally {
            dropTrigger("reject_reply_audit_page_update");
        }
    }

    @Test
    void replyAuditPageSqlFailureIsGenericAndV013IsRestored() {
        long blogId = blog(AUTHOR_ID, 1);
        long root = comment(blogId, AUTHOR_ID, 0, 0, 0, "page SQL failure root");
        long reply = comment(blogId, AUTHOR_ID + 1, root, root, 0, "page SQL failure reply");
        service.hideReply(reply, hideRequest("retained during table rename"));
        jdbc.execute("RENAME TABLE tb_blog_reply_hide_audit TO tb_blog_reply_hide_audit_saved");
        boolean renamed = true;
        try {
            BusinessException failure = assertThrows(BusinessException.class,
                    () -> service.listReplyHideAudits(blogId, hideAuditPageRequest(null, 20)));
            assertEquals(ErrorCode.INTERNAL_ERROR, failure.getErrorCode());
            assertEquals(500, failure.getErrorCode().getHttpStatus().value());
            assertNull(failure.getCause());
            assertFalse(failure.getMessage().contains("tb_blog_reply_hide_audit"));
        } finally {
            if (renamed) {
                jdbc.execute("RENAME TABLE tb_blog_reply_hide_audit_saved TO tb_blog_reply_hide_audit");
                renamed = false;
            }
        }
        assertEquals("retained during table rename",
                service.listReplyHideAudits(blogId, hideAuditPageRequest(null, 20)).getItems().get(0).getReason());
    }

    @Test
    void replyAuditPageDoesNotWaitForOrExposeUncommittedHideAndReadsItAfterCommit() throws Exception {
        long blogId = blog(AUTHOR_ID, 1);
        long root = comment(blogId, AUTHOR_ID, 0, 0, 0, "page commit boundary root");
        long reply = comment(blogId, AUTHOR_ID + 1, root, root, 0, "page commit boundary reply");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            AFTER_REPLY_HIDE_AUDIT_INSERT.set(() -> {
                runIndependent(executor, AUTHOR_ID + 10, () -> {
                    BlogReplyHideAuditPage page = service.listReplyHideAudits(blogId, hideAuditPageRequest(null, 20));
                    assertTrue(page.getItems().isEmpty());
                    assertFalse(page.isHasNext());
                });
                return 1;
            });
            service.hideReply(reply, hideRequest("page committed reply metadata"));
            assertNull(AFTER_REPLY_HIDE_AUDIT_INSERT.get());
            runIndependent(executor, AUTHOR_ID + 10, () -> {
                BlogReplyHideAuditPage page = service.listReplyHideAudits(blogId, hideAuditPageRequest(null, 20));
                assertEquals(1, page.getItems().size());
                assertEquals(Long.toString(reply), page.getItems().get(0).getReplyId());
                assertEquals("page committed reply metadata", page.getItems().get(0).getReason());
            });
            assertEquals(Integer.valueOf(2), mapper.selectById(reply).getStatus());
            assertEquals(Integer.valueOf(0), blogCounter(blogId));
        } finally {
            AFTER_REPLY_HIDE_AUDIT_INSERT.set(null);
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void replyAuditPageNeverExposesAuditWhenHideInsertReportsZeroAndRollsBack() throws Exception {
        long blogId = blog(AUTHOR_ID, 1);
        long root = comment(blogId, AUTHOR_ID, 0, 0, 0, "page rollback root");
        long reply = comment(blogId, AUTHOR_ID + 1, root, root, 0, "page rollback reply");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            AFTER_REPLY_HIDE_AUDIT_INSERT.set(() -> {
                runIndependent(executor, AUTHOR_ID + 10,
                        () -> assertTrue(service.listReplyHideAudits(blogId,
                                hideAuditPageRequest(null, 20)).getItems().isEmpty()));
                return 0;
            });
            BusinessException failure = assertThrows(BusinessException.class,
                    () -> service.hideReply(reply, hideRequest("synthetic insert count zero")));
            assertEquals(ErrorCode.OPERATION_FAILED, failure.getErrorCode());
            assertEquals(422, failure.getErrorCode().getHttpStatus().value());
            assertNull(AFTER_REPLY_HIDE_AUDIT_INSERT.get());
            runIndependent(executor, AUTHOR_ID + 10,
                    () -> assertTrue(service.listReplyHideAudits(blogId,
                            hideAuditPageRequest(null, 20)).getItems().isEmpty()));
            assertEquals(0, replyAuditCount(reply));
            assertEquals(Integer.valueOf(0), mapper.selectById(reply).getStatus());
            assertEquals(Integer.valueOf(1), blogCounter(blogId));
        } finally {
            AFTER_REPLY_HIDE_AUDIT_INSERT.set(null);
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void replyAuditPageKeepsOldRepeatableReadSnapshotAfterIndependentHideCommit() throws Exception {
        long blogId = blog(AUTHOR_ID, 1);
        long root = comment(blogId, AUTHOR_ID, 0, 0, 0, "page snapshot root");
        long reply = comment(blogId, AUTHOR_ID + 1, root, root, 0, "page snapshot reply");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            TransactionTemplate repeatableRead = new TransactionTemplate(
                    context.getBean(DataSourceTransactionManager.class));
            repeatableRead.setReadOnly(true);
            repeatableRead.setIsolationLevel(Connection.TRANSACTION_REPEATABLE_READ);
            repeatableRead.execute(status -> {
                assertTrue(service.listReplyHideAudits(blogId, hideAuditPageRequest(null, 20)).getItems().isEmpty(),
                        "establish the old repeatable-read snapshot");
                runIndependent(executor, AUTHOR_ID + 10,
                        () -> service.hideReply(reply, hideRequest("snapshot page winner")));
                assertTrue(service.listReplyHideAudits(blogId, hideAuditPageRequest(null, 20)).getItems().isEmpty(),
                        "the old page snapshot must not reveal the later commit");
                status.setRollbackOnly();
                return null;
            });
            runIndependent(executor, AUTHOR_ID + 10, () -> {
                BlogReplyHideAuditPage page = service.listReplyHideAudits(blogId, hideAuditPageRequest(null, 20));
                assertEquals(1, page.getItems().size());
                assertEquals("snapshot page winner", page.getItems().get(0).getReason());
            });
            assertEquals(1, replyAuditCount(reply));
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void replyAuditPageOrdinarySelectReadsCommittedMetadataWhileAnotherConnectionHoldsUpdateLock() throws Exception {
        long blogId = blog(AUTHOR_ID, 1);
        long root = comment(blogId, AUTHOR_ID, 0, 0, 0, "page locked metadata root");
        long reply = comment(blogId, AUTHOR_ID + 1, root, root, 0, "page locked metadata reply");
        service.hideReply(reply, hideRequest("committed page reason"));
        BlogReplyHideAuditResponse committed = service.queryReplyHideAudit(reply);
        CountDownLatch updateHeld = new CountDownLatch(1);
        CountDownLatch releaseUpdate = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<?> locker = null;
        try {
            locker = executor.submit(() -> {
                UserHolder.saveUser(user(AUTHOR_ID + 20));
                try (Connection connection = datasource.getConnection();
                     PreparedStatement update = connection.prepareStatement(
                             "UPDATE tb_blog_reply_hide_audit SET reason='uncommitted page replacement' WHERE reply_id=?")) {
                    connection.setAutoCommit(false);
                    update.setLong(1, reply);
                    assertEquals(1, update.executeUpdate());
                    updateHeld.countDown();
                    assertTrue(releaseUpdate.await(10, TimeUnit.SECONDS));
                    connection.rollback();
                } catch (Exception error) {
                    throw new AssertionError("could not hold the private reply audit row update", error);
                } finally {
                    UserHolder.removeUser();
                }
            });
            assertTrue(updateHeld.await(5, TimeUnit.SECONDS), "audit UPDATE did not acquire its row lock");
            Future<BlogReplyHideAuditPage> query = executor.submit(() -> {
                UserHolder.saveUser(user(AUTHOR_ID + 21));
                try { return service.listReplyHideAudits(blogId, hideAuditPageRequest(null, 20)); }
                finally { UserHolder.removeUser(); }
            });
            BlogReplyHideAuditPage page = query.get(5, TimeUnit.SECONDS);
            assertEquals(Collections.singletonList(committed), page.getItems(),
                    "ordinary page SELECT should see committed metadata without waiting for the updater");
        } finally {
            releaseUpdate.countDown();
            try {
                if (locker != null) locker.get(10, TimeUnit.SECONDS);
            } finally {
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
            }
        }
        assertEquals(committed, service.queryReplyHideAudit(reply));
    }

    @Test
    void replyAuditPageIndexMigrationRepeatsPreservesDataAndDoesNotRepairWrongShape() throws Exception {
        long blogId = blog(AUTHOR_ID, 1);
        long root = comment(blogId, AUTHOR_ID, 0, 0, 0, "V014 fixture root");
        long reply = comment(blogId, AUTHOR_ID + 1, root, root, 0, "V014 fixture reply");
        service.hideReply(reply, hideRequest("V014 preserves this audit"));
        BlogReplyHideAuditResponse before = service.queryReplyHideAudit(reply);
        ClassPathResource migration = new ClassPathResource("db/migration/V014__add_blog_reply_hide_audit_page_index.sql");
        try (Connection connection = datasource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, migration);
            ScriptUtils.executeSqlScript(connection, migration);
        }
        assertEquals(Arrays.asList("blog_id", "id"), replyAuditPageIndexColumns());
        assertEquals(before, service.queryReplyHideAudit(reply));

        try (Connection connection = datasource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE reply_audit_page_missing_v013_test");
            try {
                statement.execute("USE reply_audit_page_missing_v013_test");
                assertThrows(org.springframework.jdbc.datasource.init.ScriptStatementFailedException.class,
                        () -> ScriptUtils.executeSqlScript(connection, migration));
            } finally {
                statement.execute("DROP DATABASE reply_audit_page_missing_v013_test");
            }
        }

        jdbc.execute("DROP INDEX idx_blog_reply_hide_audit_page ON tb_blog_reply_hide_audit");
        try {
            jdbc.execute("CREATE INDEX idx_blog_reply_hide_audit_page ON tb_blog_reply_hide_audit(id)");
            try (Connection connection = datasource.getConnection()) {
                ScriptUtils.executeSqlScript(connection, migration);
                ScriptUtils.executeSqlScript(connection, migration);
            }
            assertEquals(Collections.singletonList("id"), replyAuditPageIndexColumns());
            assertEquals(before, service.queryReplyHideAudit(reply));
        } finally {
            jdbc.execute("DROP INDEX idx_blog_reply_hide_audit_page ON tb_blog_reply_hide_audit");
            jdbc.execute("CREATE INDEX idx_blog_reply_hide_audit_page ON tb_blog_reply_hide_audit(blog_id,id)");
        }
    }

    private static List<String> replyAuditPageIndexColumns() {
        return jdbc.queryForList("SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.STATISTICS WHERE TABLE_SCHEMA=DATABASE() "
                + "AND TABLE_NAME='tb_blog_reply_hide_audit' AND INDEX_NAME='idx_blog_reply_hide_audit_page' "
                + "ORDER BY SEQ_IN_INDEX", String.class);
    }

    private static List<String> auditPageIndexColumns() {
        return jdbc.queryForList("SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.STATISTICS WHERE TABLE_SCHEMA=DATABASE() "
                + "AND TABLE_NAME='tb_blog_comment_hide_audit' AND INDEX_NAME='idx_blog_comment_hide_audit_page' "
                + "ORDER BY SEQ_IN_INDEX", String.class);
    }

    private static BlogCommentHideAuditPageRequest hideAuditPageRequest(Long beforeId, int size) {
        BlogCommentHideAuditPageRequest request = new BlogCommentHideAuditPageRequest();
        request.setBeforeId(beforeId);
        request.setSize(size);
        return request;
    }

    @Test
    void withdrawnPostClosesNewCommentReplyAndListsButRetainsIndependentWithdrawalAndModeration() {
        long blogId = blog(AUTHOR_ID, 4);
        long root = comment(blogId, AUTHOR_ID, 0, 0, 0, "retained root");
        long reply = comment(blogId, AUTHOR_ID, root, root, 0, "retained reply");
        long moderatable = comment(blogId, AUTHOR_ID + 1, 0, 0, 0, "retained moderation");
        jdbc.update("UPDATE tb_blog SET withdrawn=1 WHERE id=?", blogId);
        assertEquals(ErrorCode.NOT_FOUND, assertThrows(BusinessException.class,
                () -> service.createComment(createRequest(blogId, "must not insert"))).getErrorCode());
        assertEquals(ErrorCode.NOT_FOUND, assertThrows(BusinessException.class,
                () -> service.createReply(root, replyRequest("must not insert"))).getErrorCode());
        assertEquals(ErrorCode.NOT_FOUND, assertThrows(BusinessException.class,
                () -> service.listComments(blogId, pageRequest(null, 20))).getErrorCode());
        assertEquals(ErrorCode.NOT_FOUND, assertThrows(BusinessException.class,
                () -> service.listReplies(root, pageRequest(null, 20))).getErrorCode());
        assertEquals(3, countComments(blogId));
        service.withdrawReply(reply);
        service.withdrawComment(root);
        service.hideComment(moderatable, hideRequest("retained audit on withdrawn post"));
        assertEquals(Integer.valueOf(1), blogCounter(blogId));
        assertEquals("retained reply", mapper.selectById(reply).getContent());
        assertEquals(Integer.valueOf(3), mapper.selectById(reply).getStatus());
        assertEquals(Integer.valueOf(3), mapper.selectById(root).getStatus());
        assertEquals("retained audit on withdrawn post", service.queryHideAudit(moderatable).getReason());
        assertEquals(1, service.listHideAudits(blogId, hideAuditPageRequest(null, 20)).getItems().size());
        assertEquals(1, jdbc.queryForObject("SELECT withdrawn FROM tb_blog WHERE id=?", Integer.class, blogId));
    }

    @Test
    void replyCreateUsesCurrentPostLockAfterWithdrawalCommitsBehindEarlierRepeatableReadSnapshot() {
        long blogId = blog(AUTHOR_ID, 1);
        long root = comment(blogId, AUTHOR_ID, 0, 0, 0, "root stays normal");
        // An explicitly independent connection commits behind the earlier RR root snapshot.
        AFTER_VISIBLE_ROOT_QUERY.set(() -> {
            try (Connection connection = datasource.getConnection();
                 java.sql.PreparedStatement write = connection.prepareStatement("UPDATE tb_blog SET withdrawn=1 WHERE id=?")) {
                write.setLong(1, blogId);
                assertEquals(1, write.executeUpdate());
            } catch (SQLException error) { throw new AssertionError(error); }
        });
        assertEquals(ErrorCode.NOT_FOUND, assertThrows(BusinessException.class,
                () -> service.createReply(root, replyRequest("must not insert"))).getErrorCode());
        assertNull(AFTER_VISIBLE_ROOT_QUERY.get());
        assertEquals(1, countComments(blogId));
        assertEquals(Integer.valueOf(1), blogCounter(blogId));
        assertEquals(Integer.valueOf(0), mapper.selectById(root).getStatus());
    }

    private static void runIndependent(ExecutorService executor, long userId, Runnable action) {
        Future<?> result = executor.submit(() -> {
            UserHolder.saveUser(user(userId));
            try { action.run(); }
            finally { UserHolder.removeUser(); }
        });
        try { result.get(10, TimeUnit.SECONDS); }
        catch (Exception error) { throw new AssertionError("independent service action failed", error); }
    }

    private static int auditCount(long commentId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM tb_blog_comment_hide_audit WHERE comment_id=?",
                Integer.class, commentId);
    }

    private static int replyAuditCount(long replyId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM tb_blog_reply_hide_audit WHERE reply_id=?",
                Integer.class, replyId);
    }

    private static BlogCommentHideRequest hideRequest(String reason) {
        BlogCommentHideRequest request = new BlogCommentHideRequest();
        request.setReason(reason);
        return request;
    }

    private static BlogCommentPage listReplies(Long rootId, BlogCommentPageRequest request) {
        EXPECT_READ_ONLY_REPLY_LIST.set(Boolean.TRUE);
        try {
            return service.listReplies(rootId, request);
        } finally {
            EXPECT_READ_ONLY_REPLY_LIST.remove();
        }
    }

    private static BlogCommentReplyRequest replyRequest(String content) {
        BlogCommentReplyRequest request = new BlogCommentReplyRequest();
        request.setContent(content);
        return request;
    }

    private static long blog(long authorId, Integer comments) {
        return blog(null, authorId, comments);
    }

    private static long blog(Long explicitId, long authorId, Integer comments) {
        if (explicitId == null) {
            GeneratedKeyHolder key = new GeneratedKeyHolder();
            jdbc.update(connection -> {
                PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO tb_blog(shop_id,user_id,title,images,content,liked,comments) "
                                + "VALUES(NULL,?,'synthetic blog','/synthetic.png','test content',0,?)",
                        Statement.RETURN_GENERATED_KEYS);
                statement.setLong(1, authorId);
                statement.setObject(2, comments);
                return statement;
            }, key);
            assertNotNull(key.getKey());
            return key.getKey().longValue();
        }
        jdbc.update("INSERT INTO tb_blog(id,shop_id,user_id,title,images,content,liked,comments) "
                        + "VALUES(?,NULL,?,'synthetic blog','/synthetic.png','test content',0,?)",
                explicitId, authorId, comments);
        return explicitId;
    }

    private static long comment(long blogId, long authorId, long parentId, long answerId,
                                Integer status, String content) {
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO tb_blog_comments(user_id,blog_id,parent_id,answer_id,content,liked,status) "
                            + "VALUES(?,?,?,?,?,0,?)", Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, authorId); statement.setLong(2, blogId);
            statement.setLong(3, parentId); statement.setLong(4, answerId);
            statement.setString(5, content); statement.setObject(6, status);
            return statement;
        }, key);
        assertNotNull(key.getKey());
        return key.getKey().longValue();
    }

    private static int countComments(long blogId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM tb_blog_comments WHERE blog_id=?", Integer.class, blogId);
    }

    private static Integer blogCounter(long blogId) {
        return jdbc.queryForObject("SELECT comments FROM tb_blog WHERE id=?", Integer.class, blogId);
    }

    private static BlogCommentCreateRequest createRequest(long blogId, String content) {
        BlogCommentCreateRequest request = new BlogCommentCreateRequest();
        request.setBlogId(blogId);
        request.setContent(content);
        return request;
    }

    private static BlogCommentPageRequest pageRequest(String beforeId, int size) {
        BlogCommentPageRequest request = new BlogCommentPageRequest();
        if (beforeId != null) request.setBeforeId(Long.valueOf(beforeId));
        request.setSize(size);
        return request;
    }

    private static UserDTO user(long id) {
        UserDTO user = new UserDTO();
        user.setId(id);
        return user;
    }

    private static List<String> itemIds(BlogCommentPage page) {
        List<String> ids = new ArrayList<>();
        for (BlogCommentItem item : page.getItems()) ids.add(item.getId());
        return ids;
    }

    private static List<String> itemContents(BlogCommentPage page) {
        List<String> contents = new ArrayList<>();
        for (BlogCommentItem item : page.getItems()) contents.add(item.getContent());
        return contents;
    }

    private static void dropTrigger(String name) {
        if (jdbc != null) jdbc.execute("DROP TRIGGER IF EXISTS " + name);
    }

    private static void stopProcess(Process process) throws Exception {
        if (process == null) return;
        process.destroy();
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            assertTrue(process.waitFor(5, TimeUnit.SECONDS), "private MySQL process did not stop");
        }
    }
}
