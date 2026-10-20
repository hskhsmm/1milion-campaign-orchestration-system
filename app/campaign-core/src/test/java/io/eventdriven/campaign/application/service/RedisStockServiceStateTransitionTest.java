package io.eventdriven.campaign.application.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings({"rawtypes", "unchecked"})
class RedisStockServiceStateTransitionTest {

    private static final Long CAMPAIGN_ID = 62L;
    private static final String ACTIVE_SET = "active:campaigns";
    private static final String FLAG = "active:campaign:{62}";
    private static final String QUEUE = "queue:campaign:{62}";
    private static final String LOCK = "lock:campaign:{62}:state";

    @Mock private RedisTemplate<String, String> redisTemplate;
    @Mock private DefaultRedisScript<List> checkDecrEnqueueScript;
    @Mock private ValueOperations<String, String> valueOperations;
    @Mock private SetOperations<String, String> setOperations;
    @Mock private ListOperations<String, String> listOperations;

    private RedisStockService service;

    @BeforeEach
    void setUp() {
        service = new RedisStockService(redisTemplate, checkDecrEnqueueScript);
    }

    @Test
    @DisplayName("재활성화는 캠페인 상태 잠금을 얻은 뒤 Set과 flag를 등록한다")
    void activationUsesStateLock() {
        lockAcquired();
        when(redisTemplate.opsForSet()).thenReturn(setOperations);

        service.activateCampaign(CAMPAIGN_ID);

        InOrder order = inOrder(valueOperations, setOperations);
        order.verify(valueOperations).setIfAbsent(eq(LOCK), anyString(), eq(Duration.ofSeconds(60)));
        order.verify(setOperations).add(ACTIVE_SET, "62");
        order.verify(valueOperations).set(FLAG, "1");
    }

    @Test
    @DisplayName("잠금 안에서 flag와 LLEN이 비어 있으면 Set만 제거한다")
    void inactiveAndEmptyRemovesOnlySet() {
        lockAcquired();
        queueSizes(0L, 0L);
        when(redisTemplate.hasKey(FLAG)).thenReturn(false);
        when(redisTemplate.opsForSet()).thenReturn(setOperations);

        assertTrue(service.deactivateIfQueueEmpty(CAMPAIGN_ID));

        verify(setOperations).remove(ACTIVE_SET, "62");
        verify(redisTemplate, never()).delete(FLAG);
    }

    @Test
    @DisplayName("잠금 대기 중 복구로 flag가 생기면 Set을 제거하지 않는다")
    void reactivatedBeforeCleanupKeepsSet() {
        lockAcquired();
        queueSizes(0L);
        when(redisTemplate.hasKey(FLAG)).thenReturn(true);

        assertFalse(service.deactivateIfQueueEmpty(CAMPAIGN_ID));

        verify(redisTemplate, never()).opsForSet();
    }

    @Test
    @DisplayName("큐에 잔량이 있으면 Set을 제거하지 않는다")
    void nonEmptyQueueKeepsSet() {
        lockAcquired();
        queueSizes(206_000L);
        when(redisTemplate.hasKey(FLAG)).thenReturn(false);

        assertFalse(service.deactivateIfQueueEmpty(CAMPAIGN_ID));

        verify(redisTemplate, never()).opsForSet();
    }

    @Test
    @DisplayName("LLEN 결과가 불확실하면 Set을 제거하지 않는다")
    void unknownQueueSizeKeepsSet() {
        lockAcquired();
        queueSizes((Long) null);
        when(redisTemplate.hasKey(FLAG)).thenReturn(false);

        assertFalse(service.deactivateIfQueueEmpty(CAMPAIGN_ID));

        verify(redisTemplate, never()).opsForSet();
    }

    @Test
    @DisplayName("Set 제거 직후 큐가 다시 채워지면 Set을 복구한다")
    void queueChangedAfterRemovalRestoresSet() {
        lockAcquired();
        queueSizes(0L, 1L);
        when(redisTemplate.hasKey(FLAG)).thenReturn(false);
        when(redisTemplate.opsForSet()).thenReturn(setOperations);

        assertFalse(service.deactivateIfQueueEmpty(CAMPAIGN_ID));

        InOrder order = inOrder(setOperations);
        order.verify(setOperations).remove(ACTIVE_SET, "62");
        order.verify(setOperations).add(ACTIVE_SET, "62");
    }

    @Test
    @DisplayName("Set 제거 후 active flag가 생기면 Set을 복구한다")
    void flagChangedAfterRemovalRestoresSet() {
        lockAcquired();
        queueSizes(0L, 0L);
        when(redisTemplate.hasKey(FLAG)).thenReturn(false, true);
        when(redisTemplate.opsForSet()).thenReturn(setOperations);

        assertFalse(service.deactivateIfQueueEmpty(CAMPAIGN_ID));

        InOrder order = inOrder(setOperations);
        order.verify(setOperations).remove(ACTIVE_SET, "62");
        order.verify(setOperations).add(ACTIVE_SET, "62");
    }

    @Test
    @DisplayName("다른 인스턴스가 잠금을 잡고 있으면 이번 Bridge 사이클은 건너뛴다")
    void busyLockSkipsCleanup() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(eq(LOCK), anyString(), eq(Duration.ofSeconds(60)))).thenReturn(false);

        assertFalse(service.deactivateIfQueueEmpty(CAMPAIGN_ID));

        verify(redisTemplate, never()).opsForSet();
        verify(redisTemplate, never()).opsForList();
    }

    @Test
    @DisplayName("Bridge 정리 중 복구가 재활성화되면 잠금 해제 후 Set과 flag가 모두 유지된다")
    void activationWaitsForCleanupAndRestoresActiveState() throws Exception {
        AtomicReference<String> lockOwner = new AtomicReference<>();
        AtomicBoolean inSet = new AtomicBoolean(true);
        AtomicBoolean activeFlag = new AtomicBoolean(false);
        CountDownLatch cleanupHoldingLock = new CountDownLatch(1);
        CountDownLatch activationContended = new CountDownLatch(1);
        CountDownLatch allowCleanup = new CountDownLatch(1);

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(eq(LOCK), anyString(), eq(Duration.ofSeconds(60))))
                .thenAnswer(invocation -> {
                    boolean acquired = lockOwner.compareAndSet(null, invocation.getArgument(1));
                    if (!acquired) {
                        activationContended.countDown();
                    }
                    return acquired;
                });
        when(redisTemplate.opsForList()).thenReturn(listOperations);
        when(listOperations.size(QUEUE)).thenAnswer(invocation -> {
            cleanupHoldingLock.countDown();
            assertTrue(allowCleanup.await(5, TimeUnit.SECONDS));
            return 0L;
        });
        when(redisTemplate.hasKey(FLAG)).thenAnswer(invocation -> activeFlag.get());
        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        when(setOperations.remove(ACTIVE_SET, "62")).thenAnswer(invocation -> {
            inSet.set(false);
            return 1L;
        });
        when(setOperations.add(ACTIVE_SET, "62")).thenAnswer(invocation -> {
            inSet.set(true);
            return 1L;
        });
        doAnswer(invocation -> {
            activeFlag.set(true);
            return null;
        }).when(valueOperations).set(FLAG, "1");
        when(redisTemplate.execute(any(DefaultRedisScript.class), eq(List.of(LOCK)), anyString()))
                .thenAnswer(invocation -> lockOwner.compareAndSet(invocation.getArgument(2), null) ? 1L : 0L);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Boolean> cleanup = executor.submit(() -> service.deactivateIfQueueEmpty(CAMPAIGN_ID));
            assertTrue(cleanupHoldingLock.await(5, TimeUnit.SECONDS));
            Future<?> activation = executor.submit(() -> service.activateCampaign(CAMPAIGN_ID));
            assertTrue(activationContended.await(5, TimeUnit.SECONDS));
            allowCleanup.countDown();

            assertTrue(cleanup.get(5, TimeUnit.SECONDS));
            activation.get(5, TimeUnit.SECONDS);
            assertTrue(inSet.get());
            assertTrue(activeFlag.get());
        } finally {
            allowCleanup.countDown();
        }
    }

    private void lockAcquired() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(eq(LOCK), anyString(), eq(Duration.ofSeconds(60)))).thenReturn(true);
    }

    private void queueSizes(Long... sizes) {
        when(redisTemplate.opsForList()).thenReturn(listOperations);
        when(listOperations.size(QUEUE)).thenReturn(sizes[0], java.util.Arrays.copyOfRange(sizes, 1, sizes.length));
    }
}
