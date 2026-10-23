package io.github.frewily.campushub.service;

import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
import io.github.frewily.campushub.dto.Result;
import io.github.frewily.campushub.dto.ScrollResult;
import io.github.frewily.campushub.dto.UserDTO;
import io.github.frewily.campushub.entity.Blog;
import io.github.frewily.campushub.content.application.BlogUserReader;
import io.github.frewily.campushub.service.impl.BlogServiceImpl;
import io.github.frewily.campushub.utils.UserHolder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static io.github.frewily.campushub.utils.RedisConstants.FEED_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Uses a private non-persistent Redis; the SQL query chain and user profile are fixtures. */
class BlogFeedRedisIT {
    @TempDir
    static Path directory;

    private static Process process;
    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate redis;

    private BlogServiceImpl service;
    private QueryChainWrapper<Blog> query;
    private BlogUserReader blogUserReader;
    private final Map<Long, Blog> visibleFixtures = new LinkedHashMap<>();
    private final List<Long> queriedIds = new ArrayList<>();
    private final List<String> orderClauses = new ArrayList<>();
    private String feedKey;
    private UserDTO profile;
    private long userId;

    @BeforeAll
    static void startRedis() throws Exception {
        try {
            int port;
            try (ServerSocket socket = new ServerSocket(0)) {
                port = socket.getLocalPort();
            }
            String binary = System.getenv().getOrDefault("REDIS_SERVER_BINARY", "redis-server");
            process = new ProcessBuilder(binary, "--bind", "127.0.0.1", "--port", Integer.toString(port),
                    "--save", "", "--appendonly", "no", "--dir", directory.toString())
                    .redirectErrorStream(true)
                    .redirectOutput(directory.resolve("redis.log").toFile())
                    .start();

            factory = new LettuceConnectionFactory("127.0.0.1", port);
            factory.afterPropertiesSet();
            redis = new StringRedisTemplate(factory);
            redis.afterPropertiesSet();

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (System.nanoTime() < deadline && process.isAlive()) {
                try {
                    String pong = redis.execute((RedisCallback<String>) connection -> connection.ping());
                    if ("PONG".equals(pong)) return;
                } catch (RuntimeException unavailable) {
                    Thread.sleep(25);
                }
            }
            throw new IllegalStateException("Isolated Redis did not become ready; see "
                    + directory.resolve("redis.log"));
        } catch (Exception | Error startupFailure) {
            closeRedisResources(false);
            throw startupFailure;
        }
    }

    @AfterAll
    static void stopRedis() {
        closeRedisResources(true);
    }

    private static void closeRedisResources(boolean assertProcessStopped) {
        try {
            if (factory != null) factory.destroy();
        } catch (RuntimeException ignored) {
            // Always continue to stop the child process, even if Lettuce shutdown fails.
        } finally {
            factory = null;
            redis = null;
            if (process != null) {
                Process child = process;
                process = null;
                child.destroy();
                boolean stopped = false;
                try {
                    stopped = child.waitFor(5, TimeUnit.SECONDS);
                    if (!stopped) {
                        child.destroyForcibly();
                        stopped = child.waitFor(5, TimeUnit.SECONDS);
                    }
                } catch (InterruptedException interrupted) {
                    child.destroyForcibly();
                    Thread.currentThread().interrupt();
                    stopped = !child.isAlive();
                }
                if (assertProcessStopped) {
                    assertTrue(stopped && !child.isAlive(), "isolated Redis process did not stop");
                }
            }
        }
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        userId = UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE;
        if (userId == 0) userId = 1;
        feedKey = FEED_KEY + userId;

        service = spy(new BlogServiceImpl());
        query = mock(QueryChainWrapper.class);
        blogUserReader = mock(BlogUserReader.class);
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redis);
        ReflectionTestUtils.setField(service, "blogUserReader", blogUserReader);
        doReturn(query).when(service).query();
        doAnswer(invocation -> {
            queriedIds.clear();
            queriedIds.addAll((java.util.Collection<Long>) invocation.getArgument(1));
            return query;
        }).when(query).in(eq("id"), anyCollection());
        doAnswer(invocation -> {
            orderClauses.add(invocation.getArgument(0));
            return query;
        }).when(query).last(anyString());
        when(query.list()).thenAnswer(invocation -> {
            List<Blog> rows = new ArrayList<>();
            for (Long id : queriedIds) {
                Blog visible = visibleFixtures.get(id);
                if (visible != null) rows.add(visible);
            }
            return rows;
        });

        profile = new UserDTO();
        profile.setId(userId);
        profile.setNickName("synthetic-profile");
        profile.setIcon("synthetic-icon");
        when(blogUserReader.findAuthor(userId)).thenReturn(profile);
        UserDTO identity = new UserDTO();
        identity.setId(userId);
        UserHolder.saveUser(identity);
    }

    @AfterEach
    void clearUserHolder() {
        UserHolder.removeUser();
    }

    @Test
    void traversesSevenEqualScoreMembersAcrossCumulativeOffsetsWithoutChangingFeed() {
        List<String> members = Arrays.asList("101", "102", "103", "104", "105", "106", "9007199254740993");
        for (String member : members) {
            add(member, 1000);
            makeVisible(Long.valueOf(member));
        }
        List<String> expectedRedisOrder = redisMembers(0, 1000, 0, 20);
        assertEquals(members.size(), expectedRedisOrder.size());
        List<String> initialSnapshot = redisSnapshot();
        List<String> returned = new ArrayList<>();

        int[] incomingOffsets = {0, 2, 4, 6};
        int[] expectedNextOffsets = {2, 4, 6, 7};
        for (int i = 0; i < incomingOffsets.length; i++) {
            ScrollResult page = scroll(service.queryBlogOfFollow(1000L, incomingOffsets[i]));
            List<String> expectedPage = redisMembers(0, 1000, incomingOffsets[i], 2);
            assertEquals(expectedPage, blogIds(page));
            returned.addAll(blogIds(page));
            assertEquals(1000L, page.getMinTime());
            assertEquals(expectedNextOffsets[i], page.getOffset());
        }

        assertEquals(expectedRedisOrder, returned);
        assertEquals(returned.size(), new LinkedHashSet<>(returned).size());
        assertEquals(initialSnapshot, redisSnapshot());
        assertTrue(redisMembers(0, 1000, 7, 2).isEmpty());
        Result terminal = service.queryBlogOfFollow(1000L, 7);
        assertTrue(terminal.getSuccess());
        assertNull(terminal.getData());
        assertEquals(initialSnapshot, redisSnapshot());
    }

    @Test
    void endingOnOlderScoreResetsOffsetToOnlyThatScoreGroupCount() {
        add("201", 2000);
        add("202", 2000);
        add("203", 1000);
        add("204", 1000);
        add("205", 1000);
        for (long id = 201; id <= 205; id++) makeVisible(id);
        List<String> before = redisSnapshot();

        ScrollResult first = scroll(service.queryBlogOfFollow(2000L, 0));
        assertEquals(redisMembers(0, 2000, 0, 2), blogIds(first));
        assertEquals(2000L, first.getMinTime());
        assertEquals(2, first.getOffset());

        ScrollResult second = scroll(service.queryBlogOfFollow(2000L, 2));
        assertEquals(redisMembers(0, 2000, 2, 2), blogIds(second));
        assertEquals(1000L, second.getMinTime());
        assertEquals(2, second.getOffset());

        ScrollResult lowerScoreContinuation = scroll(service.queryBlogOfFollow(second.getMinTime(), second.getOffset()));
        assertEquals(Collections.singletonList("203"), blogIds(lowerScoreContinuation));
        assertEquals(1000L, lowerScoreContinuation.getMinTime());
        assertEquals(3, lowerScoreContinuation.getOffset());
        assertEquals(before, redisSnapshot());
    }

    @Test
    void upperBoundExcludesFutureScoresAndAOneMemberLastPageAdvancesCursor() {
        add("301", 1000);
        add("302", 1000);
        add("303", 900);
        add("304", 900);
        add("305", 800);
        for (long id = 301; id <= 305; id++) makeVisible(id);
        List<String> before = redisSnapshot();

        ScrollResult first = scroll(service.queryBlogOfFollow(900L, 0));
        assertEquals(redisMembers(0, 900, 0, 2), blogIds(first));
        assertFalse(blogIds(first).contains("301"));
        assertEquals(900L, first.getMinTime());
        assertEquals(2, first.getOffset());

        ScrollResult last = scroll(service.queryBlogOfFollow(900L, 2));
        assertEquals(Collections.singletonList("305"), blogIds(last));
        assertEquals(800L, last.getMinTime());
        assertEquals(1, last.getOffset());
        assertEquals(before, redisSnapshot());
    }

    @Test
    void twoMemberLastPageReturnsBothVisibleRowsInRedisOrder() {
        add("401", 700);
        add("402", 700);
        makeVisible(401);
        makeVisible(402);
        List<String> before = redisSnapshot();

        ScrollResult page = scroll(service.queryBlogOfFollow(700L, 0));
        List<String> expectedRedisOrder = redisMembers(0, 700, 0, 2);
        assertEquals(2, expectedRedisOrder.size());
        assertEquals(expectedRedisOrder, blogIds(page));
        assertEquals(2, page.getOffset());
        assertEquals(before, redisSnapshot());
    }

    @Test
    void databaseFilteredEmptyBatchRetainsRawCursorAndTheNextBatchCanBeVisible() {
        add("501", 1000);
        add("502", 1000);
        add("503", 1000);
        add("504", 1000);
        makeVisible(501);
        makeVisible(502);
        List<String> before = redisSnapshot();

        ScrollResult filtered = scroll(service.queryBlogOfFollow(1000L, 0));
        assertTrue(filtered.getList().isEmpty());
        assertEquals(1000L, filtered.getMinTime());
        assertEquals(2, filtered.getOffset());
        assertEquals(Arrays.asList(504L, 503L), queriedIds);

        ScrollResult visible = scroll(service.queryBlogOfFollow(filtered.getMinTime(), filtered.getOffset()));
        assertEquals(Arrays.asList("502", "501"), blogIds(visible));
        assertEquals(4, visible.getOffset());
        assertEquals(before, redisSnapshot());
    }

    @Test
    void emptyKeyAndEndOfFeedReturnEmptySuccessWithoutDatabaseReads() {
        Result emptyKey = service.queryBlogOfFollow(1000L, 0);
        assertTrue(emptyKey.getSuccess());
        assertNull(emptyKey.getData());
        verifyNoInteractions(query, blogUserReader);

        add("601", 1000);
        add("602", 1000);
        clearInvocations(query, blogUserReader);
        Result end = service.queryBlogOfFollow(1000L, 2);
        assertTrue(end.getSuccess());
        assertNull(end.getData());
        verifyNoInteractions(query, blogUserReader);
    }

    @Test
    void largeLongMemberRoundTripsAsStringAndTheDatabaseOrderClauseIsLossless() {
        String id = "9007199254740993";
        add(id, 500);
        makeVisible(Long.valueOf(id));
        List<String> before = redisSnapshot();

        ScrollResult page = scroll(service.queryBlogOfFollow(500L, 0));
        assertEquals(Collections.singletonList(id), redisMembers(0, 500, 0, 2));
        assertEquals(Collections.singletonList(id), blogIds(page));
        assertEquals(Collections.singletonList(Long.valueOf(id)), queriedIds);
        assertEquals("ORDER BY FIELD(id," + id + ")", orderClauses.get(orderClauses.size() - 1));
        assertEquals(before, redisSnapshot());
    }

    private void add(String member, double score) {
        redis.opsForZSet().add(feedKey, member, score);
    }

    private void makeVisible(long id) {
        Blog blog = new Blog();
        blog.setId(id);
        blog.setUserId(userId);
        visibleFixtures.put(id, blog);
    }

    private ScrollResult scroll(Result result) {
        assertTrue(result.getSuccess());
        assertTrue(result.getData() instanceof ScrollResult);
        return (ScrollResult) result.getData();
    }

    private List<String> blogIds(ScrollResult result) {
        List<String> ids = new ArrayList<>();
        for (Object row : result.getList()) ids.add(((Blog) row).getId().toString());
        return ids;
    }

    private List<String> redisMembers(double min, double max, long offset, long count) {
        Set<ZSetOperations.TypedTuple<String>> tuples = redis.opsForZSet()
                .reverseRangeByScoreWithScores(feedKey, min, max, offset, count);
        List<String> members = new ArrayList<>();
        if (tuples != null) {
            for (ZSetOperations.TypedTuple<String> tuple : tuples) members.add(tuple.getValue());
        }
        return members;
    }

    private List<String> redisSnapshot() {
        Set<ZSetOperations.TypedTuple<String>> tuples = redis.opsForZSet().reverseRangeWithScores(feedKey, 0, -1);
        List<String> snapshot = new ArrayList<>();
        if (tuples != null) {
            for (ZSetOperations.TypedTuple<String> tuple : tuples) {
                snapshot.add(tuple.getValue() + "=" + tuple.getScore());
            }
        }
        return snapshot;
    }
}
