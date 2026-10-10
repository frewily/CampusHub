package io.github.frewily.campushub.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import io.github.frewily.campushub.dto.UserDTO;
import io.github.frewily.campushub.dto.request.BlogCommentCreateRequest;
import io.github.frewily.campushub.dto.request.BlogCommentPageRequest;
import io.github.frewily.campushub.dto.request.BlogCommentReplyRequest;
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
        jdbc.execute("DELETE FROM tb_blog_comments");
        jdbc.execute("DELETE FROM tb_blog");
        jdbc.execute("ALTER TABLE tb_blog_comments AUTO_INCREMENT=1");
        jdbc.execute("ALTER TABLE tb_blog AUTO_INCREMENT=1");
        UserHolder.saveUser(user(AUTHOR_ID));
    }

    @AfterEach
    void clearUser() {
        AFTER_VISIBLE_ROOT_QUERY.set(null);
        EXPECT_READ_ONLY_REPLY_LIST.remove();
        UserHolder.removeUser();
        dropTrigger("fail_comment_insert");
        dropTrigger("fail_blog_counter_update");
        dropTrigger("remove_blog_before_comment_insert");
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
