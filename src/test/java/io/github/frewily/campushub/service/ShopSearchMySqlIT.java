package io.github.frewily.campushub.service;

import io.github.frewily.campushub.dto.ShopSearchCriteria;
import io.github.frewily.campushub.dto.request.ShopSearchRequest;
import io.github.frewily.campushub.dto.response.*;
import io.github.frewily.campushub.exception.*;
import io.github.frewily.campushub.mapper.ShopSearchMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.validation.Validation;
import javax.validation.ValidatorFactory;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** Real XML/MyBatis/transaction interception; all processes, schema and rows belong to this test. */
class ShopSearchMySqlIT {
    @TempDir static Path directory;
    static Process mysql;
    static DriverManagerDataSource datasource;
    static JdbcTemplate jdbc;
    static ShopSearchMapper mapper;
    static ShopSearchService service;
    static DataSourceTransactionManager manager;
    static ValidatorFactory validators;

    @BeforeAll static void start() throws Exception {
        String binary = System.getenv().getOrDefault("MYSQLD_SERVER_BINARY", "mysqld");
        Path data = directory.resolve("mysql-data");
        Process init = new ProcessBuilder(binary, "--no-defaults", "--initialize-insecure", "--datadir=" + data)
                .redirectErrorStream(true).redirectOutput(directory.resolve("init.log").toFile()).start();
        if (!init.waitFor(30, TimeUnit.SECONDS)) { init.destroyForcibly(); fail("private MySQL init timed out"); }
        assertEquals(0, init.exitValue());
        int port;
        try (ServerSocket socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        mysql = new ProcessBuilder(binary, "--no-defaults", "--datadir=" + data, "--bind-address=127.0.0.1",
                "--port=" + port, "--mysqlx=OFF", "--skip-log-bin", "--socket=" + directory.resolve("mysql.sock"),
                "--pid-file=" + directory.resolve("mysql.pid"), "--log-error=" + directory.resolve("mysql.log"))
                .redirectErrorStream(true).redirectOutput(directory.resolve("console.log").toFile()).start();
        String base = "jdbc:mysql://127.0.0.1:" + port
                + "/?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&forceConnectionTimeZoneToSession=true";
        boolean ready = false;
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
        while (System.nanoTime() < until && mysql.isAlive()) {
            try (Connection connection = DriverManager.getConnection(base, "root", "")) {
                connection.createStatement().execute("CREATE DATABASE search_test CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci");
                ready = true; break;
            } catch (SQLException unavailable) { Thread.sleep(50); }
        }
        assertTrue(ready, "private MySQL did not start");
        datasource = new DriverManagerDataSource(base.replace("/?", "/search_test?"), "root", "");
        jdbc = new JdbcTemplate(datasource);
        try (Connection connection = datasource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/shop-cache-test-schema.sql"));
            for (int pass = 0; pass < 2; pass++) ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("db/migration/V005__add_shop_search_indexes.sql"));
        }
        SqlSessionFactoryBean bean = new SqlSessionFactoryBean();
        bean.setDataSource(datasource);
        org.apache.ibatis.session.Configuration config = new org.apache.ibatis.session.Configuration();
        config.setMapUnderscoreToCamelCase(true);
        bean.setConfiguration(config);
        bean.setMapperLocations(new ClassPathResource("mapper/ShopSearchMapper.xml"));
        mapper = new SqlSessionTemplate(bean.getObject()).getMapper(ShopSearchMapper.class);
        validators = Validation.buildDefaultValidatorFactory();
        manager = new DataSourceTransactionManager(datasource);
        service = transactionalService(mapper);
    }

    private static ShopSearchService transactionalService(ShopSearchMapper searchMapper) {
        ProxyFactory proxy = new ProxyFactory(new ShopSearchService(searchMapper, validators.getValidator()));
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        return (ShopSearchService) proxy.getProxy();
    }

    @AfterAll static void stop() throws Exception {
        if (validators != null) validators.close();
        if (mysql != null) {
            mysql.destroy();
            if (!mysql.waitFor(10, TimeUnit.SECONDS)) {
                mysql.destroyForcibly(); assertTrue(mysql.waitFor(5, TimeUnit.SECONDS));
            }
        }
    }

    @BeforeEach void fixture() { jdbc.execute("DELETE FROM tb_shop"); }

    private void row(long id, String name, long type, Long price, Integer score, Double x, Double y) {
        jdbc.update("INSERT INTO tb_shop(id,name,type_id,merchant_id,avg_price,score,x,y,images,area,address,open_hours) "
                        + "VALUES(?,?,?,99,?,?,?,?, '/images/synthetic.png','Campus','Test Road','08:00-22:00')",
                id, name, type, price, score, x, y);
    }
    private List<String> ids(ShopSearchPage page) {
        return page.getItems().stream().map(ShopSearchItem::getId).collect(Collectors.toList());
    }
    private ShopSearchRequest located(double x, double y) {
        ShopSearchRequest request = new ShopSearchRequest(); request.setX(x); request.setY(y); return request;
    }

    @Test void combinedFiltersUseRealColumnsAndLosslessStringIdentifiers() {
        row(9007199254740993L, "校园咖啡馆", 9007199254740994L, 30L, 45, 120.0, 30.0);
        row(2, "校园咖啡馆", 2, 30L, 45, 120.0, 30.0);
        row(3, "校园咖啡馆", 9007199254740994L, 90L, 45, 120.0, 30.0);
        row(4, "校园咖啡馆", 9007199254740994L, 30L, 20, 120.0, 30.0);
        ShopSearchRequest request = new ShopSearchRequest();
        request.setKeyword("  咖啡  "); request.setTypeId(9007199254740994L);
        request.setMinPrice(20L); request.setMaxPrice(40L); request.setMinScore(40);
        ShopSearchPage page = service.search(request);
        assertEquals(1, page.getTotal()); assertEquals(Collections.singletonList("9007199254740993"), ids(page));
        ShopSearchItem item = page.getItems().get(0);
        assertEquals("9007199254740994", item.getTypeId()); assertEquals(30L, item.getAvgPrice());
        assertEquals("Test Road", item.getAddress()); assertNull(item.getDistanceMeters());
    }

    @Test void wildcardsEscapeCharacterBackslashAndInjectionTextAreLiteral() {
        String[] names = {"100% cafe", "under_score", "wow!cafe", "back\\slash", "' OR 1=1 --", "ordinary"};
        for (int i = 0; i < names.length; i++) row(i + 1, names[i], 1, 1L, 40, 0.0, 0.0);
        String[] keywords = {"%", "_", "!", "\\", "' OR 1=1 --"};
        for (int i = 0; i < keywords.length; i++) {
            ShopSearchRequest request = new ShopSearchRequest(); request.setKeyword(keywords[i]);
            assertEquals(Collections.singletonList(String.valueOf(i + 1)), ids(service.search(request)), keywords[i]);
        }
        ShopSearchRequest blank = new ShopSearchRequest(); blank.setKeyword("   ");
        assertEquals(6, service.search(blank).getTotal());
    }

    @Test void fixedSortsHaveStableIdTiesAndNullsLast() {
        row(3, "three", 1, null, null, 0.0, 0.0);
        row(2, "two", 1, 10L, 40, 0.0, 0.0);
        row(1, "one", 1, 10L, 40, 0.0, 0.0);
        row(4, "four", 1, 20L, 45, 0.0, 0.0);
        ShopSearchRequest request = new ShopSearchRequest();
        request.setSort("price_asc"); assertEquals(Arrays.asList("1", "2", "4", "3"), ids(service.search(request)));
        request.setSort("price_desc"); assertEquals(Arrays.asList("4", "1", "2", "3"), ids(service.search(request)));
        request.setSort("score_desc"); assertEquals(Arrays.asList("4", "1", "2", "3"), ids(service.search(request)));
        request.setSort("id"); assertEquals(Arrays.asList("1", "2", "3", "4"), ids(service.search(request)));
    }

    @Test void paginationCountsEmptyPagesAndCommittedUpdatesAreVisibleOnNextSearch() {
        for (long id = 1; id <= 3; id++) row(id, "old", 1, 1L, 40, 0.0, 0.0);
        ShopSearchRequest request = new ShopSearchRequest(); request.setSize(2);
        ShopSearchPage page = service.search(request);
        assertEquals(3, page.getTotal()); assertTrue(page.isHasNext()); assertEquals(Arrays.asList("1", "2"), ids(page));
        request.setPage(2); page = service.search(request);
        assertEquals(Collections.singletonList("3"), ids(page)); assertFalse(page.isHasNext());
        request.setPage(500); assertTrue(service.search(request).getItems().isEmpty());
        request.setKeyword("new"); assertEquals(0, service.search(request).getTotal());
        jdbc.update("UPDATE tb_shop SET name='new' WHERE id=1");
        request.setPage(1); assertEquals(Collections.singletonList("1"), ids(service.search(request)));
    }

    @Test void distanceAndRadiusIncludeOriginAndRespectMetreBoundary() {
        row(1, "origin", 1, 1L, 40, 0.0, 0.0);
        row(2, "inside", 1, 1L, 40, Math.toDegrees(999.9 / 6371008.8), 0.0);
        row(3, "outside", 1, 1L, 40, Math.toDegrees(1000.1 / 6371008.8), 0.0);
        ShopSearchRequest request = located(0, 0); request.setSort("distance"); request.setRadiusMeters(1000);
        ShopSearchPage page = service.search(request);
        assertEquals(2, page.getTotal()); assertEquals(Arrays.asList("1", "2"), ids(page));
        assertEquals(0.0, page.getItems().get(0).getDistanceMeters(), 0.0001);
        assertEquals(999.9, page.getItems().get(1).getDistanceMeters(), 0.001);
        request.setRadiusMeters(null); assertEquals(3, service.search(request).getTotal());
        // Coincident shops remain included by a positive radius and tie-break by ID.
        row(4, "exact-zero", 1, 1L, 40, 0.0, 0.0);
        request.setRadiusMeters(1); assertEquals(Arrays.asList("1", "4"), ids(service.search(request)));
    }

    @Test void antimeridianPolesAntipodesAndNegativeQueryCoordinatesRemainFinite() {
        row(1, "dateline", 1, 1L, 40, 179.999, 0.0);
        ShopSearchRequest request = located(-179.999, 0); request.setSort("distance"); request.setRadiusMeters(300);
        assertEquals(Collections.singletonList("1"), ids(service.search(request)));
        assertEquals(222.39, service.search(request).getItems().get(0).getDistanceMeters(), 0.1);
        row(2, "north pole", 1, 1L, 40, 180.0, 90.0);
        request = located(-180, 90); request.setRadiusMeters(1);
        assertEquals(Collections.singletonList("2"), ids(service.search(request)));
        request = located(0, -90); request.setSort("distance");
        double antipode = service.search(request).getItems().get(1).getDistanceMeters();
        assertTrue(Double.isFinite(antipode)); assertEquals(Math.PI * 6371008.8, antipode, 0.1);
    }

    @Test void badStoredCoordinatesOnlyExcludedForDistanceFilteringOrSorting() {
        row(1, "valid", 1, 1L, 40, 120.0, 30.0);
        row(2, "missing", 1, 1L, 40, null, null);
        row(3, "invalid", 1, 1L, 40, 181.0, 30.0);
        ShopSearchRequest request = located(120, 30);
        ShopSearchPage page = service.search(request);
        assertEquals(3, page.getTotal()); assertNull(page.getItems().get(1).getDistanceMeters());
        assertNull(page.getItems().get(2).getDistanceMeters());
        request.setSort("distance"); assertEquals(Collections.singletonList("1"), ids(service.search(request)));
        request.setSort("id"); request.setRadiusMeters(1000);
        assertEquals(Collections.singletonList("1"), ids(service.search(request)));
    }

    @Test void repeatedMigrationPreservesRowsAndActualIndexColumnOrder() throws Exception {
        row(1, "preserved", 1, 10L, 40, 0.0, 0.0);
        try (Connection connection = datasource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V005__add_shop_search_indexes.sql"));
        }
        assertEquals(1, service.search(new ShopSearchRequest()).getTotal());
        assertEquals(Arrays.asList("type_id", "avg_price", "id"), indexColumns("idx_shop_search_type_price"));
        assertEquals(Arrays.asList("score", "id"), indexColumns("idx_shop_search_score"));
    }
    private List<String> indexColumns(String index) {
        return jdbc.queryForList("SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.STATISTICS WHERE TABLE_SCHEMA=DATABASE() "
                + "AND TABLE_NAME='tb_shop' AND INDEX_NAME=? ORDER BY SEQ_IN_INDEX", String.class, index);
    }

    @Test void countAndItemsShareRealRepeatableReadSnapshotDuringConcurrentCommit() throws Exception {
        row(1, "original", 1, 10L, 40, 0.0, 0.0);
        CountDownLatch counted = new CountDownLatch(1), committed = new CountDownLatch(1);
        ShopSearchMapper pausedMapper = new ShopSearchMapper() {
            public long count(ShopSearchCriteria criteria) {
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                assertTrue(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
                assertEquals(Integer.valueOf(Connection.TRANSACTION_REPEATABLE_READ),
                        TransactionSynchronizationManager.getCurrentTransactionIsolationLevel());
                long total = mapper.count(criteria); counted.countDown();
                try { assertTrue(committed.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new RuntimeException(interrupted); }
                return total;
            }
            public List<ShopSearchItem> search(ShopSearchCriteria criteria) { return mapper.search(criteria); }
        };
        ShopSearchService paused = transactionalService(pausedMapper);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<ShopSearchPage> read = executor.submit(() -> paused.search(new ShopSearchRequest()));
        try {
            assertTrue(counted.await(5, TimeUnit.SECONDS));
            // JdbcTemplate here uses a separate main-thread connection, committing outside the reader transaction.
            row(2, "committed", 1, 10L, 40, 0.0, 0.0); committed.countDown();
            ShopSearchPage page = read.get(5, TimeUnit.SECONDS);
            assertEquals(1, page.getTotal()); assertEquals(Collections.singletonList("1"), ids(page));
            assertEquals(2, service.search(new ShopSearchRequest()).getTotal());
        } finally { committed.countDown(); executor.shutdownNow(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS)); }
    }

    @Test void actualMissingTableIsTypedUnavailableNotAnEmptyResult() {
        jdbc.execute("RENAME TABLE tb_shop TO tb_shop_temporarily_unavailable");
        try {
            BusinessException error = assertThrows(BusinessException.class, () -> service.search(new ShopSearchRequest()));
            assertEquals(ErrorCode.SHOP_STATE_UNAVAILABLE, error.getErrorCode());
        } finally { jdbc.execute("RENAME TABLE tb_shop_temporarily_unavailable TO tb_shop"); }
        assertEquals(0, service.search(new ShopSearchRequest()).getTotal());
    }
}
