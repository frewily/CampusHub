package io.github.frewily.campushub.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
import io.github.frewily.campushub.dto.ApiModelMapper;
import io.github.frewily.campushub.dto.Result;
import io.github.frewily.campushub.dto.UserDTO;
import io.github.frewily.campushub.dto.request.BlogCreateRequest;
import io.github.frewily.campushub.entity.Blog;
import io.github.frewily.campushub.entity.Follow;
import io.github.frewily.campushub.entity.User;
import io.github.frewily.campushub.exception.BusinessException;
import io.github.frewily.campushub.mapper.BlogMapper;
import io.github.frewily.campushub.service.impl.BlogServiceImpl;
import io.github.frewily.campushub.service.IFollowService;
import io.github.frewily.campushub.service.IUserService;
import io.github.frewily.campushub.utils.UserHolder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.ServerSocket;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real MySQL/MyBatis and BlogService acceptance; followService and Redis are mocked. */
class BlogPublicationMySqlIT {
    private static final long AUTHOR_ID = 701L;

    @TempDir static Path directory;
    private static Process mysql;
    private static DriverManagerDataSource datasource;
    private static JdbcTemplate jdbc;
    private static BlogMapper mapper;
    private static BlogServiceImpl blogService;
    private static IUserService userService;
    private static IFollowService followService;
    private static QueryChainWrapper<Follow> followQuery;
    private static StringRedisTemplate redis;
    private static ZSetOperations<String, String> zset;

    @BeforeAll
    static void startPrivateMySql() throws Exception {
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

        int port = freePort();
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
                    connection.createStatement().execute("CREATE DATABASE blog_publication_test CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci");
                    ready = true;
                    break;
                } catch (SQLException unavailable) {
                    Thread.sleep(50);
                }
            }
            assertTrue(ready, "private MySQL did not start");

            datasource = new DriverManagerDataSource(base.replace("/?", "/blog_publication_test?"), "root", "");
            jdbc = new JdbcTemplate(datasource);
            importProductionSchemaAndMigrations();
            configureMyBatisAndService();
        } catch (Exception | Error failure) {
            stopProcess(mysql);
            throw failure;
        }
    }

    private static void importProductionSchemaAndMigrations() throws Exception {
        try (Connection connection = datasource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new FileSystemResource("deploy/mysql/schema.sql"));
            for (int version = 1; version <= 5; version++) {
                String name = String.format("db/migration/V%03d__%s.sql", version, migrationName(version));
                ScriptUtils.executeSqlScript(connection, new ClassPathResource(name));
            }
        }
    }

    private static String migrationName(int version) {
        switch (version) {
            case 1: return "add_business_unique_constraints";
            case 2: return "add_identity_and_merchant_authorization";
            case 3: return "add_order_cancellation_outbox";
            case 4: return "add_shop_cache_invalidation_outbox";
            case 5: return "add_shop_search_indexes";
            default: throw new IllegalArgumentException("Unknown migration: " + version);
        }
    }

    private static void configureMyBatisAndService() throws Exception {
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(datasource);
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(BlogMapper.class);
        MybatisPlusInterceptor pagination = new MybatisPlusInterceptor();
        pagination.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        factory.setConfiguration(configuration);
        factory.setPlugins(pagination);
        SqlSessionTemplate sessions = new SqlSessionTemplate(factory.getObject());
        mapper = sessions.getMapper(BlogMapper.class);

        blogService = new BlogServiceImpl();
        ReflectionTestUtils.setField(blogService, "baseMapper", mapper);
        userService = mock(IUserService.class);
        followService = mock(IFollowService.class);
        followQuery = mock(QueryChainWrapper.class);
        redis = mock(StringRedisTemplate.class);
        zset = mock(ZSetOperations.class);
        ReflectionTestUtils.setField(blogService, "userService", userService);
        ReflectionTestUtils.setField(blogService, "followService", followService);
        ReflectionTestUtils.setField(blogService, "stringRedisTemplate", redis);
        when(followService.query()).thenReturn(followQuery);
        when(followQuery.eq("follow_user_id", AUTHOR_ID)).thenReturn(followQuery);
        when(followQuery.list()).thenReturn(Collections.emptyList());
        when(redis.opsForZSet()).thenReturn(zset);
        when(zset.score(anyString(), anyString())).thenReturn(null);

        User author = new User().setNickName("Synthetic author").setIcon("/synthetic-author.png");
        when(userService.getById(AUTHOR_ID)).thenReturn(author);
    }

    @BeforeEach
    void seedLegacyPostThenRunNullableMigrationTwice() throws Exception {
        jdbc.execute("DELETE FROM tb_blog");
        jdbc.execute("ALTER TABLE tb_blog AUTO_INCREMENT=1");
        // Restore only this test-owned table so every case exercises the first migration too.
        jdbc.execute("ALTER TABLE tb_blog MODIFY COLUMN shop_id BIGINT NOT NULL COMMENT '商户id'");
        assertEquals("NO", jdbc.queryForObject(
                "SELECT IS_NULLABLE FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA=DATABASE() "
                        + "AND TABLE_NAME='tb_blog' AND COLUMN_NAME='shop_id'", String.class));
        jdbc.update("INSERT INTO tb_blog (shop_id,user_id,title,images,content,liked,comments) "
                        + "VALUES (?,?,?,?,?,0,0)",
                321L, AUTHOR_ID, "legacy post", "/legacy.png", "legacy content");
        try (Connection connection = datasource.getConnection()) {
            ClassPathResource migration = new ClassPathResource("db/migration/V006__allow_campus_posts_without_shop.sql");
            ScriptUtils.executeSqlScript(connection, migration);
            ScriptUtils.executeSqlScript(connection, migration);
        }
        UserDTO author = new UserDTO();
        author.setId(AUTHOR_ID);
        UserHolder.saveUser(author);
    }

    @AfterEach
    void clearSession() {
        UserHolder.removeUser();
    }

    @AfterAll
    static void stopPrivateMySql() throws Exception {
        stopProcess(mysql);
    }

    @Test
    void migrationKeepsSignedBigintAndLegacyDataAndServiceSavesNullAndPositiveShopIds() {
        assertEquals("bigint", jdbc.queryForObject(
                "SELECT COLUMN_TYPE FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA=DATABASE() "
                        + "AND TABLE_NAME='tb_blog' AND COLUMN_NAME='shop_id'", String.class));
        assertEquals("YES", jdbc.queryForObject(
                "SELECT IS_NULLABLE FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA=DATABASE() "
                        + "AND TABLE_NAME='tb_blog' AND COLUMN_NAME='shop_id'", String.class));
        Blog legacy = mapper.selectById(1L);
        assertNotNull(legacy);
        assertEquals(321L, legacy.getShopId());
        assertEquals("legacy post", legacy.getTitle());
        assertEquals("legacy content", legacy.getContent());

        assertThrows(BusinessException.class,
                () -> blogService.saveBlog(ApiModelMapper.toBlog(request(0L, "zero shop", "invalid"))));
        assertThrows(BusinessException.class,
                () -> blogService.saveBlog(ApiModelMapper.toBlog(request(-1L, "negative shop", "invalid"))));

        BlogCreateRequest campusRequest = request(null, "campus post", "campus content");
        Blog campusPost = ApiModelMapper.toBlog(campusRequest);
        Result campusResult = blogService.saveBlog(campusPost);
        assertTrue(campusResult.getSuccess());
        assertTrue(campusPost.getId() > 1L, "the inserted campus post should receive the MySQL auto ID");
        assertEquals(campusPost.getId(), campusResult.getData());
        Blog storedCampusPost = mapper.selectById(campusPost.getId());
        assertNotNull(storedCampusPost);
        assertNull(storedCampusPost.getShopId());
        assertEquals(AUTHOR_ID, storedCampusPost.getUserId());
        assertEquals(0, storedCampusPost.getLiked());
        assertEquals(0, storedCampusPost.getComments());

        BlogCreateRequest shopRequest = request(654L, "shop post", "shop content");
        Blog shopPost = ApiModelMapper.toBlog(shopRequest);
        Result shopResult = blogService.saveBlog(shopPost);
        assertTrue(shopResult.getSuccess());
        assertTrue(shopPost.getId() > campusPost.getId());
        assertEquals(shopPost.getId(), shopResult.getData());
        Blog storedShopPost = mapper.selectById(shopPost.getId());
        assertEquals(654L, storedShopPost.getShopId());
        assertEquals(AUTHOR_ID, storedShopPost.getUserId());
        assertEquals(0, storedShopPost.getLiked());
        assertEquals(0, storedShopPost.getComments());
        assertEquals("legacy content", mapper.selectById(1L).getContent());
    }

    @Test
    void detailHotAndMyPostsReadNullableShopIdThroughMyBatis() {
        BlogCreateRequest nullShopRequest = request(null, "nullable detail", "nullable detail content");
        Blog nullShop = ApiModelMapper.toBlog(nullShopRequest);
        blogService.saveBlog(nullShop);
        BlogCreateRequest shopRequest = request(789L, "shop detail", "shop detail content");
        Blog shopBlog = ApiModelMapper.toBlog(shopRequest);
        blogService.saveBlog(shopBlog);

        Result detailResult = blogService.queryBlogById(nullShop.getId());
        assertTrue(detailResult.getSuccess());
        Blog detail = (Blog) detailResult.getData();
        assertNull(detail.getShopId());
        assertEquals("nullable detail content", detail.getContent());
        assertEquals("Synthetic author", detail.getName());

        Result hotResult = blogService.queryHotBlog(1);
        assertTrue(hotResult.getSuccess());
        @SuppressWarnings("unchecked")
        List<Blog> hot = (List<Blog>) hotResult.getData();
        Blog hotNullShop = hot.stream().filter(blog -> nullShop.getId().equals(blog.getId())).findFirst().orElseThrow(AssertionError::new);
        assertNull(hotNullShop.getShopId());
        assertEquals(AUTHOR_ID, hotNullShop.getUserId());

        IPage<Blog> ownPage = blogService.query().eq("user_id", AUTHOR_ID).page(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, 10));
        Blog ownNullShop = ownPage.getRecords().stream().filter(blog -> nullShop.getId().equals(blog.getId())).findFirst().orElseThrow(AssertionError::new);
        assertNull(ownNullShop.getShopId());
        assertEquals("nullable detail", ownNullShop.getTitle());
        Blog ownPositiveShop = ownPage.getRecords().stream().filter(blog -> shopBlog.getId().equals(blog.getId())).findFirst().orElseThrow(AssertionError::new);
        assertEquals(789L, ownPositiveShop.getShopId());
    }

    private static BlogCreateRequest request(Long shopId, String title, String content) {
        BlogCreateRequest request = new BlogCreateRequest();
        request.setShopId(shopId);
        request.setTitle(title);
        request.setImages("/synthetic.png");
        request.setContent(content);
        return request;
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
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
