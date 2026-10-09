package io.github.frewily.campushub.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import io.github.frewily.campushub.entity.*;
import io.github.frewily.campushub.mapper.*;
import io.github.frewily.campushub.service.impl.ShopServiceImpl;
import io.github.frewily.campushub.utils.CacheClient;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.sql.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Owns every process, port and row. Uses real Spring transactions/MyBatis, not shared dev credentials. */
class ShopCacheMySqlRedisIT {
    @TempDir static Path directory;
    static Process mysql, redisProcess;
    static LettuceConnectionFactory factory;
    static StringRedisTemplate redis;
    static DriverManagerDataSource datasource;
    static JdbcTemplate jdbc;
    static CacheClient cache;
    static ShopCacheInvalidationMapper outbox;
    static ShopCacheInvalidationService invalidations;
    static IShopService shops;
    static TransactionTemplate tx;

    @BeforeAll static void start() throws Exception {
        String binary=System.getenv().getOrDefault("MYSQLD_SERVER_BINARY","mysqld");
        Path data=directory.resolve("mysql-data");
        Process init=new ProcessBuilder(binary,"--no-defaults","--initialize-insecure","--datadir="+data)
                .redirectErrorStream(true).redirectOutput(directory.resolve("mysql-init.log").toFile()).start();
        if(!init.waitFor(30,TimeUnit.SECONDS)) { init.destroyForcibly(); fail("private MySQL init timed out"); }
        assertEquals(0,init.exitValue());
        int mysqlPort=port(), redisPort=port();
        mysql=new ProcessBuilder(binary,"--no-defaults","--datadir="+data,"--bind-address=127.0.0.1",
                "--port="+mysqlPort,"--mysqlx=OFF","--skip-log-bin","--socket="+directory.resolve("mysql.sock"),
                "--pid-file="+directory.resolve("mysql.pid"),"--log-error="+directory.resolve("mysql.log"))
                .redirectErrorStream(true).redirectOutput(directory.resolve("mysql-console.log").toFile()).start();
        String base="jdbc:mysql://127.0.0.1:"+mysqlPort+"/?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&forceConnectionTimeZoneToSession=true";
        boolean ready=false; long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(25);
        while(System.nanoTime()<until && mysql.isAlive()) {
            try(Connection connection=DriverManager.getConnection(base,"root","")) {
                connection.createStatement().execute("CREATE DATABASE cache_test"); ready=true; break;
            } catch(SQLException unavailable) { Thread.sleep(50); }
        }
        assertTrue(ready,"private MySQL did not start");
        datasource=new DriverManagerDataSource(base.replace("/?","/cache_test?"),"root","");
        jdbc=new JdbcTemplate(datasource);
        try(Connection connection=datasource.getConnection()) {
            ScriptUtils.executeSqlScript(connection,new ClassPathResource("db/shop-cache-test-schema.sql"));
            for(int pass=0;pass<2;pass++) ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("db/migration/V004__add_shop_cache_invalidation_outbox.sql"));
        }
        redisProcess=new ProcessBuilder(System.getenv().getOrDefault("REDIS_SERVER_BINARY","redis-server"),
                "--bind","127.0.0.1","--port",redisPort+"","--save","","--appendonly","no","--dir",directory.toString())
                .redirectErrorStream(true).redirectOutput(directory.resolve("redis.log").toFile()).start();
        factory=new LettuceConnectionFactory("127.0.0.1",redisPort); factory.afterPropertiesSet();
        redis=new StringRedisTemplate(factory); redis.afterPropertiesSet();
        ready=false; until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(System.nanoTime()<until && redisProcess.isAlive()) {
            try { ready="PONG".equals(redis.execute((RedisCallback<String>) c->c.ping())); if(ready)break; }
            catch(RuntimeException unavailable) { Thread.sleep(20); }
        }
        assertTrue(ready);
        MybatisSqlSessionFactoryBean bean=new MybatisSqlSessionFactoryBean(); bean.setDataSource(datasource);
        MybatisConfiguration config=new MybatisConfiguration(); config.setMapUnderscoreToCamelCase(true);
        config.addMapper(ShopMapper.class); config.addMapper(ShopCacheInvalidationMapper.class); bean.setConfiguration(config);
        SqlSessionTemplate sql=new SqlSessionTemplate(bean.getObject());
        outbox=sql.getMapper(ShopCacheInvalidationMapper.class);
        cache=new CacheClient(redis); invalidations=new ShopCacheInvalidationService(outbox,cache,true);
        ShopServiceImpl target=new ShopServiceImpl();
        ReflectionTestUtils.setField(target,"baseMapper",sql.getMapper(ShopMapper.class));
        ReflectionTestUtils.setField(target,"cacheClient",cache);
        ReflectionTestUtils.setField(target,"stringRedisTemplate",redis);
        ReflectionTestUtils.setField(target,"shopCacheInvalidationService",invalidations);
        DataSourceTransactionManager manager=new DataSourceTransactionManager(datasource); tx=new TransactionTemplate(manager);
        ProxyFactory proxy=new ProxyFactory(target);
        proxy.addAdvice(new TransactionInterceptor(manager,new AnnotationTransactionAttributeSource()));
        shops=(IShopService)proxy.getProxy(); // Method-security is NOT installed in this business-chain IT.
    }
    @AfterAll static void stop() throws Exception {
        if(factory!=null)factory.destroy(); stopProcess(redisProcess); stopProcess(mysql);
    }
    @BeforeEach void fixture() {
        jdbc.execute("DROP TRIGGER IF EXISTS force_cache_outbox_failure");
        jdbc.execute("DELETE FROM tb_shop_cache_invalidation"); jdbc.execute("DELETE FROM tb_shop");
        jdbc.execute("ALTER TABLE tb_shop AUTO_INCREMENT=111");
        redis.execute((RedisCallback<Object>) c->{c.serverCommands().flushDb();return null;});
        jdbc.update("INSERT INTO tb_shop(id,name,type_id,merchant_id) VALUES(1,'old',1,10)");
    }
    private String readName() { return ((Shop)shops.queryById(1L).getData()).getName(); }

    @Test void realCommitInvalidatesThenWorkerAcknowledgesAndPreservesOwnership() {
        assertEquals("old",readName());
        shops.updateShop(new Shop().setId(1L).setName("new").setMerchantId(99L));
        assertEquals("new",jdbc.queryForObject("SELECT name FROM tb_shop WHERE id=1",String.class));
        assertEquals(10L,jdbc.queryForObject("SELECT merchant_id FROM tb_shop WHERE id=1",Long.class));
        assertFalse(redis.hasKey(CacheClient.DATA_PREFIX+1)); assertNotNull(outbox.find(1L));
        assertEquals("new",readName()); invalidations.recoverPending();
        assertNull(outbox.find(1L)); assertEquals("new",readName());
    }
    @Test void realRollbackLeavesCacheAndOriginalDatabaseRowUntouched() {
        assertEquals("old",readName()); String old=redis.opsForValue().get(CacheClient.DATA_PREFIX+1);
        assertThrows(IllegalStateException.class,()->tx.execute(status->{
            shops.updateShop(new Shop().setId(1L).setName("uncommitted"));
            assertEquals(old,redis.opsForValue().get(CacheClient.DATA_PREFIX+1));
            throw new IllegalStateException("synthetic rollback");
        }));
        assertEquals("old",readName()); assertNull(outbox.find(1L));
        assertEquals("old",jdbc.queryForObject("SELECT name FROM tb_shop WHERE id=1",String.class));
    }
    @Test void actualOutboxInsertFailureRollsBackShopMutationWithoutEvictingCache() {
        assertEquals("old",readName());
        jdbc.execute("CREATE TRIGGER force_cache_outbox_failure BEFORE INSERT ON tb_shop_cache_invalidation FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic fault'");
        assertThrows(RuntimeException.class,()->shops.updateShop(new Shop().setId(1L).setName("new")));
        assertEquals("old",jdbc.queryForObject("SELECT name FROM tb_shop WHERE id=1",String.class));
        assertEquals("old",readName()); assertNull(outbox.find(1L));
    }
    @Test void committedWriteSurvivesFailedRedisCallbackAndDurableEventRecoversLater() {
        assertEquals("old",readName());
        redis.opsForList().leftPush(CacheClient.EPOCH_PREFIX+1,"broken-type");
        assertDoesNotThrow(()->shops.updateShop(new Shop().setId(1L).setName("new")));
        assertEquals("new",jdbc.queryForObject("SELECT name FROM tb_shop WHERE id=1",String.class));
        assertNotNull(outbox.find(1L)); invalidations.recoverPending();
        assertEquals(1,outbox.find(1L).getAttempts()); assertNotNull(outbox.find(1L).getLastError());
        redis.delete(CacheClient.EPOCH_PREFIX+1); // Repair only the intentionally corrupted test-owned key.
        invalidations.recoverPending(); assertNull(outbox.find(1L)); assertEquals("new",readName());
    }
    @Test void newerWriteCannotLoseItsPendingEventToAnOldWorkerAcknowledgement() {
        shops.updateShop(new Shop().setId(1L).setName("first")); ShopCacheInvalidation old=outbox.find(1L);
        shops.updateShop(new Shop().setId(1L).setName("second")); String newer=outbox.find(1L).getGeneration();
        assertNotEquals(old.getGeneration(),newer); invalidations.recover(old);
        assertEquals(newer,outbox.find(1L).getGeneration()); invalidations.recoverPending();
        assertNull(outbox.find(1L)); assertEquals("second",readName());
    }
    @Test void crashAfterRedisEvictionBeforeDbAckIsSafeToRepeat() {
        shops.updateShop(new Shop().setId(1L).setName("new"));
        cache.invalidateShop(1L); String first=redis.opsForValue().get(CacheClient.EPOCH_PREFIX+1);
        assertNotNull(outbox.find(1L)); invalidations.recoverPending();
        assertNull(outbox.find(1L)); assertNotEquals(first,redis.opsForValue().get(CacheClient.EPOCH_PREFIX+1));
        assertEquals("new",readName());
    }
    @Test void creationInvalidatesAnEarlierNegativeEntryUsingTheGeneratedDatabaseId() {
        assertNull(cache.queryShop(111L,Shop.class,shops::getById)); assertTrue(redis.hasKey(CacheClient.DATA_PREFIX+111));
        Shop shop=new Shop().setName("created").setTypeId(1L).setMerchantId(10L);
        shops.createShop(shop); assertEquals(111L,shop.getId());
        assertEquals("created",((Shop)shops.queryById(111L).getData()).getName());
        invalidations.recoverPending(); assertNull(outbox.find(111L));
    }
    @Test void concurrentOldDbReadCannotRepopulateAfterCommittedUpdateInvalidation() throws Exception {
        CountDownLatch loaded=new CountDownLatch(1), resume=new CountDownLatch(1);
        ExecutorService executor=Executors.newSingleThreadExecutor();
        Future<Shop> reader=executor.submit(()->cache.queryShop(1L,Shop.class,id->{
            Shop old=shops.getById(id);
            if("old".equals(old.getName())) {
                loaded.countDown(); try { assertTrue(resume.await(5,TimeUnit.SECONDS)); }
                catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new RuntimeException(interrupted); }
            }
            return old;
        }));
        try {
            assertTrue(loaded.await(5,TimeUnit.SECONDS)); shops.updateShop(new Shop().setId(1L).setName("new")); resume.countDown();
            try { assertEquals("new",reader.get(5,TimeUnit.SECONDS).getName()); }
            catch(ExecutionException failure) {
                assertTrue(failure.getCause() instanceof io.github.frewily.campushub.exception.BusinessException);
                assertEquals(io.github.frewily.campushub.exception.ErrorCode.SHOP_STATE_UNAVAILABLE,
                        ((io.github.frewily.campushub.exception.BusinessException)failure.getCause()).getErrorCode());
            }
            assertEquals("new",readName());
        } finally { resume.countDown(); executor.shutdownNow(); assertTrue(executor.awaitTermination(5,TimeUnit.SECONDS)); }
    }
    @Test void repeatedMigrationPreservesPendingRowsAndFailureCountIsBounded() throws Exception {
        shops.updateShop(new Shop().setId(1L).setName("new")); String generation=outbox.find(1L).getGeneration();
        try(Connection connection=datasource.getConnection()) { ScriptUtils.executeSqlScript(connection,
                new ClassPathResource("db/migration/V004__add_shop_cache_invalidation_outbox.sql")); }
        assertEquals(generation,outbox.find(1L).getGeneration());
        jdbc.update("UPDATE tb_shop_cache_invalidation SET attempts=1000000 WHERE shop_id=1");
        outbox.failed(1L,generation,"SyntheticFault"); assertEquals(1000000,outbox.find(1L).getAttempts());
    }
    static int port() throws Exception { try(ServerSocket socket=new ServerSocket(0)) { return socket.getLocalPort(); } }
    static void stopProcess(Process process) throws Exception {
        if(process==null)return; process.destroy();
        if(!process.waitFor(10,TimeUnit.SECONDS)) { process.destroyForcibly(); assertTrue(process.waitFor(5,TimeUnit.SECONDS)); }
    }
}
