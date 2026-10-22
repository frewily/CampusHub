package io.github.frewily.campushub.promotion.infrastructure;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.injector.DefaultSqlInjector;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import io.github.frewily.campushub.entity.Voucher;
import io.github.frewily.campushub.mapper.VoucherMapper;
import io.github.frewily.campushub.promotion.application.VoucherQueryService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** Real legacy XML and MyBatis over a private, temporary localhost MySQL; no shop or Redis needed. */
class VoucherCatalogMySqlIT {
    @TempDir static Path directory;
    private static Process mysql;
    private static JdbcTemplate jdbc;
    private static VoucherQueryService service;
    private static final long SHOP = 9007199254740993L;

    @BeforeAll
    static void start() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = socket.getLocalPort();
        }
        System.out.println("VOUCHER_CATALOG_MYSQL_PORT=" + port);
        String binary = System.getenv().getOrDefault("MYSQLD_SERVER_BINARY", "mysqld");
        Path data = directory.resolve("mysql-data");
        Process init = new ProcessBuilder(binary, "--no-defaults", "--initialize-insecure", "--datadir=" + data)
                .redirectErrorStream(true).redirectOutput(directory.resolve("init.log").toFile()).start();
        try {
            assertTrue(init.waitFor(30, TimeUnit.SECONDS), "private initializer timed out");
            assertEquals(0, init.exitValue(), "inspect private init.log");
        } finally { stopProcess(init); }
        try {
            mysql = new ProcessBuilder(binary, "--no-defaults", "--datadir=" + data, "--bind-address=127.0.0.1",
                    "--port=" + port, "--mysqlx=OFF", "--skip-log-bin", "--socket=" + directory.resolve("mysql.sock"),
                    "--pid-file=" + directory.resolve("mysql.pid"), "--log-error=" + directory.resolve("mysql.log"))
                    .redirectErrorStream(true).redirectOutput(directory.resolve("console.log").toFile()).start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
            boolean ready = false;
            while (mysql.isAlive() && System.nanoTime() < deadline) {
                try (Connection c = DriverManager.getConnection(url(port, ""), "root", "")) {
                    c.createStatement().execute("CREATE DATABASE voucher_catalog_test CHARACTER SET utf8mb4");
                    ready = true; break;
                } catch (SQLException unavailable) { Thread.sleep(50); }
            }
            assertTrue(ready, "private MySQL did not start");
            DriverManagerDataSource source = new DriverManagerDataSource(url(port, "voucher_catalog_test"), "root", "");
            jdbc = new JdbcTemplate(source);
            jdbc.execute("CREATE TABLE tb_voucher(id BIGINT PRIMARY KEY,shop_id BIGINT,title VARCHAR(255),"
                    + "sub_title VARCHAR(255),rules VARCHAR(1024),pay_value BIGINT,actual_value BIGINT,type INT,status INT,"
                    + "create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                    + "update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP)");
            jdbc.execute("CREATE TABLE tb_seckill_voucher(voucher_id BIGINT PRIMARY KEY,stock INT,begin_time DATETIME,end_time DATETIME)");
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(source);
            MybatisConfiguration config = new MybatisConfiguration();
            config.setMapUnderscoreToCamelCase(true); config.addMapper(VoucherMapper.class);
            factory.setConfiguration(config);
            factory.setMapperLocations(new ClassPathResource("mapper/VoucherMapper.xml"));
            GlobalConfig global = new GlobalConfig(); global.setSqlInjector(new DefaultSqlInjector());
            factory.setGlobalConfig(global);
            VoucherMapper mapper = new SqlSessionTemplate(factory.getObject()).getMapper(VoucherMapper.class);
            service = new VoucherQueryService(new MyBatisVoucherQueryRepository(mapper));
        } catch (Exception | Error failure) { stopProcess(mysql); throw failure; }
    }

    private static String url(int port, String database) {
        return "jdbc:mysql://127.0.0.1:" + port + "/" + database + "?useSSL=false&allowPublicKeyRetrieval=true"
                + "&serverTimezone=UTC&forceConnectionTimeZoneToSession=true&connectTimeout=1000&socketTimeout=2000";
    }

    private static void stopProcess(Process process) throws Exception {
        if (process == null) return;
        if (process.isAlive()) {
            process.destroy();
            if (!process.waitFor(8, TimeUnit.SECONDS)) {
                process.destroyForcibly(); assertTrue(process.waitFor(5, TimeUnit.SECONDS));
            }
        }
        assertFalse(process.isAlive(), "private process must stop");
    }

    @AfterAll static void stop() throws Exception { stopProcess(mysql); }
    @BeforeEach void reset() { jdbc.execute("DELETE FROM tb_seckill_voucher"); jdbc.execute("DELETE FROM tb_voucher"); }

    private void voucher(long id, long shop, int status, int type) {
        jdbc.update("INSERT INTO tb_voucher VALUES(?,?,?,?,?,?,?,?,?,?,?)", id, shop, "synthetic-" + id,
                "subtitle", "rules", 9007199254741993L, 9007199254742993L, type, status,
                LocalDateTime.parse("2026-01-02T03:04:05"), LocalDateTime.parse("2026-02-03T04:05:06"));
    }

    private List<Map<String, Object>> facts() { return jdbc.queryForList("SELECT * FROM tb_voucher ORDER BY id"); }

    @Test
    void mapsLegacyColumnsAndLeftJoinWithoutShopTimeOrStockRules() {
        voucher(9007199254743001L, SHOP, 1, 0);
        voucher(2, SHOP, 1, 1); voucher(3, SHOP, 1, 1); voucher(4, SHOP, 1, 1);
        voucher(5, SHOP, 0, 1); voucher(6, SHOP + 1, 1, 1);
        jdbc.execute("INSERT INTO tb_seckill_voucher VALUES(2,0,'2000-01-01 01:02:03','2000-01-02 02:03:04'),"
                + "(3,7,'2099-01-01 03:04:05','2099-01-02 04:05:06')");
        List<Map<String, Object>> before = facts();
        List<Voucher> result = service.queryVoucherOfShop(SHOP);
        Map<Long, Voucher> byId = result.stream().collect(Collectors.toMap(Voucher::getId, v -> v));
        assertEquals(new HashSet<>(Arrays.asList(9007199254743001L, 2L, 3L, 4L)), byId.keySet());
        assertEquals(4, result.size());
        Voucher ordinary = byId.get(9007199254743001L);
        assertEquals(SHOP, ordinary.getShopId()); assertEquals(9007199254741993L, ordinary.getPayValue());
        assertEquals(9007199254742993L, ordinary.getActualValue()); assertEquals("subtitle", ordinary.getSubTitle());
        assertEquals("rules", ordinary.getRules()); assertEquals(0, ordinary.getType());
        assertNull(ordinary.getStock()); assertNull(ordinary.getBeginTime()); assertNull(ordinary.getEndTime());
        for (Voucher v : result) { assertNull(v.getStatus()); assertNull(v.getCreateTime()); assertNull(v.getUpdateTime()); }
        assertEquals(0, byId.get(2L).getStock());
        assertEquals(LocalDateTime.parse("2000-01-01T01:02:03"), byId.get(2L).getBeginTime());
        assertEquals(LocalDateTime.parse("2099-01-02T04:05:06"), byId.get(3L).getEndTime());
        assertNull(byId.get(4L).getStock()); // Listed flash sale lacking a stock row is retained by LEFT JOIN.
        assertEquals(before, facts());
    }

    @Test
    void emptyAndStatusChangesReadCurrentSqlWithoutCaching() {
        assertTrue(service.queryVoucherOfShop(SHOP).isEmpty());
        voucher(11, SHOP, 1, 0);
        jdbc.execute("UPDATE tb_voucher SET update_time='2000-01-01 00:00:00' WHERE id=11");
        List<Map<String, Object>> before = facts();
        assertEquals(1, service.queryVoucherOfShop(SHOP).size());
        assertEquals(before, facts(), "read preserves all columns, including the automatic timestamp");
        jdbc.execute("UPDATE tb_voucher SET status=0 WHERE id=11");
        assertNotEquals(before, facts(), "fixture UPDATE, unlike SELECT, advances ON UPDATE timestamp");
        List<Map<String, Object>> hidden = facts();
        assertTrue(service.queryVoucherOfShop(SHOP).isEmpty());
        assertEquals(hidden, facts());
        assertTrue(service.queryVoucherOfShop(SHOP + 1).isEmpty());
    }

    @Test
    void missingVoucherTableFailureDoesNotBecomeEmptySuccessAndRecovers() {
        voucher(12, SHOP, 1, 0); List<Map<String, Object>> before = facts();
        jdbc.execute("RENAME TABLE tb_voucher TO phase7w_owned_voucher_fault");
        try { assertThrows(DataAccessException.class, () -> service.queryVoucherOfShop(SHOP)); }
        finally { jdbc.execute("RENAME TABLE phase7w_owned_voucher_fault TO tb_voucher"); }
        assertEquals(before, facts()); assertEquals(1, service.queryVoucherOfShop(SHOP).size());
    }

    @Test
    void leftJoinStillRequiresStockTableEvenForOrdinaryVouchers() {
        voucher(13, SHOP, 1, 0); List<Map<String, Object>> before = facts();
        jdbc.execute("RENAME TABLE tb_seckill_voucher TO phase7w_owned_stock_fault");
        try { assertThrows(DataAccessException.class, () -> service.queryVoucherOfShop(SHOP)); }
        finally { jdbc.execute("RENAME TABLE phase7w_owned_stock_fault TO tb_seckill_voucher"); }
        assertEquals(before, facts()); assertEquals(1, service.queryVoucherOfShop(SHOP).size());
    }
}
