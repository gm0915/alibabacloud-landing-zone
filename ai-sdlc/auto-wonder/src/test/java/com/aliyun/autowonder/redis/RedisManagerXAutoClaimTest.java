package com.aliyun.autowonder.redis;

import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.StreamEntryID;
import redis.clients.jedis.StreamPendingEntry;
import redis.clients.jedis.exceptions.JedisDataException;
import redis.clients.jedis.params.XClaimParams;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * XAUTOCLAIM 不可用时，只领取空闲够久的待确认消息.
 *
 * @author 宋光明
 * @since 2026/10/21
 */
class RedisManagerXAutoClaimTest {

    /**
     * Redis 返回 unknown command 后，跳过仍在处理的消息，只 XCLAIM 空闲消息，且不再调用 XAUTOCLAIM.
     */
    @Test
    void fallsBackToPendingClaimAndRemembersUnsupportedCommand() {
        JedisPool pool = mock(JedisPool.class);
        Jedis jedis = mock(Jedis.class);
        when(pool.getResource()).thenReturn(jedis);
        when(jedis.xautoclaim(anyString(), anyString(), anyString(), anyLong(), any(StreamEntryID.class), any()))
                .thenThrow(new JedisDataException(
                        "ERR unknown command `XAUTOCLAIM`, with args beginning with: `stream`"));
        StreamEntryID fresh = new StreamEntryID("1-0");
        StreamEntryID stale = new StreamEntryID("2-0");
        when(jedis.xpending(anyString(), anyString(), any(StreamEntryID.class), any(StreamEntryID.class),
                anyInt(), isNull()))
                .thenReturn(List.of(new StreamPendingEntry(fresh, "other", 1_000L, 1L)))
                .thenReturn(List.of(
                        new StreamPendingEntry(fresh, "other", 1_000L, 1L),
                        new StreamPendingEntry(stale, "other", 60_000L, 2L)));
        when(jedis.xclaim(anyString(), anyString(), anyString(), anyLong(), any(XClaimParams.class),
                any(StreamEntryID.class))).thenReturn(List.of());
        RedisManager redis = new RedisManager(pool, false);
        long minIdleMillis = 30_000L;

        assertThat(redis.xautoClaim("stream", "group", "worker", minIdleMillis, 1)).isEmpty();
        assertThat(redis.xautoClaim("stream", "group", "worker", minIdleMillis, 1)).isEmpty();

        verify(jedis, times(1)).xautoclaim(anyString(), anyString(), anyString(), anyLong(),
                any(StreamEntryID.class), any());
        verify(jedis, times(2)).xclaim(eq("stream"), eq("group"), eq("worker"), eq(minIdleMillis),
                any(XClaimParams.class), eq(stale));
        verify(jedis, never()).xclaim(eq("stream"), eq("group"), eq("worker"), eq(minIdleMillis),
                any(XClaimParams.class), eq(fresh));
    }

    /**
     * Redis 支持 XAUTOCLAIM 时，不读待确认列表.
     */
    @Test
    void usesXAutoClaimWhenTheCommandExists() {
        JedisPool pool = mock(JedisPool.class);
        Jedis jedis = mock(Jedis.class);
        when(pool.getResource()).thenReturn(jedis);
        when(jedis.xautoclaim(anyString(), anyString(), anyString(), anyLong(), any(StreamEntryID.class), any()))
                .thenReturn(Map.entry(new StreamEntryID("0-0"), List.of()));
        RedisManager redis = new RedisManager(pool, false);

        assertThat(redis.xautoClaim("stream", "group", "worker", 30_000L, 10)).isEmpty();

        verify(jedis, never()).xpending(anyString(), anyString(), any(StreamEntryID.class),
                any(StreamEntryID.class), anyInt(), isNull());
    }
}
