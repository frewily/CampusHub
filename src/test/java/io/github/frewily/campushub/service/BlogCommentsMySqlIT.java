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
    private static final ThreadLocal<Boolean> EXPECT_READ_ONLY_REPLY_LIST =
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

    @Intercepts(@Signature(type = Executor.class, method = "query", args = {
            MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class
    }))
    static class ReadTransactionContractInterceptor implements Interceptor {
        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            MappedStatement statement = (MappedStatement) invocation.getArgs()[0];
            String id = statement.getId();
            if (id.endsWith(".findBlog") || id.endsWith(".findFirstLevelComments")
                    || id.endsWith(".findDirectReplies")
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
        EXPECT_READ_ONLY_REPLY_LIST.remove();
        UserHolder.removeUser();
        dropTrigger("fail_comment_insert");
        dropTrigger("fail_blog_counter_update");
        dropTrigger("remove_blog_before_comment_insert");
        dropTrigger("fail_reply_update");
        dropTrigger("remove_blog_before_reply_update");
        dropTrigger("fail_hide_audit_insert");
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
