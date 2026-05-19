package io.eventdriven.campaign.application.service;

import io.eventdriven.campaign.api.exception.business.DuplicateParticipationException;
import io.eventdriven.campaign.api.exception.business.QueueFullException;
import io.eventdriven.campaign.api.exception.business.RateLimitExceededException;
import io.eventdriven.campaign.api.exception.business.StockExhaustedException;
import io.eventdriven.campaign.domain.entity.CampaignStatus;
import io.eventdriven.campaign.domain.repository.CampaignRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ParticipationServiceTest {

    @Mock private CampaignRepository campaignRepository;
    @Mock private RateLimitService rateLimitService;
    @Mock private RedisStockService redisStockService;

    @InjectMocks private ParticipationService participationService;

    private static final Long CAMPAIGN_ID = 1L;
    private static final Long USER_ID = 42L;

    @Test
    @DisplayName("Rate Limit 초과 시 Lua 스크립트 호출 없이 즉시 차단")
    void participate_rateLimitExceeded_doesNotCallLua() {
        when(rateLimitService.isAllowed(CAMPAIGN_ID, USER_ID)).thenReturn(false);

        assertThatThrownBy(() -> participationService.participate(CAMPAIGN_ID, USER_ID))
                .isInstanceOf(RateLimitExceededException.class);

        verifyNoInteractions(redisStockService);
    }

    @Test
    @DisplayName("비활성 캠페인(-999) → StockExhaustedException")
    void participate_inactiveCampaign_throwsStockExhausted() {
        when(rateLimitService.isAllowed(CAMPAIGN_ID, USER_ID)).thenReturn(true);
        when(redisStockService.checkDecrEnqueue(CAMPAIGN_ID, USER_ID))
                .thenReturn(new long[]{RedisStockService.INACTIVE_CAMPAIGN, 0L});

        assertThatThrownBy(() -> participationService.participate(CAMPAIGN_ID, USER_ID))
                .isInstanceOf(StockExhaustedException.class);
    }

    @Test
    @DisplayName("중복 참여(-997) → DuplicateParticipationException")
    void participate_alreadyParticipated_throwsDuplicate() {
        when(rateLimitService.isAllowed(CAMPAIGN_ID, USER_ID)).thenReturn(true);
        when(redisStockService.checkDecrEnqueue(CAMPAIGN_ID, USER_ID))
                .thenReturn(new long[]{RedisStockService.ALREADY_PARTICIPATED, 0L});

        assertThatThrownBy(() -> participationService.participate(CAMPAIGN_ID, USER_ID))
                .isInstanceOf(DuplicateParticipationException.class);
    }

    @Test
    @DisplayName("Queue 만원(-998) → QueueFullException + Rate Limit 키 즉시 해제 (재시도 허용)")
    void participate_queueFull_releasesRateLimitForRetry() {
        when(rateLimitService.isAllowed(CAMPAIGN_ID, USER_ID)).thenReturn(true);
        when(redisStockService.checkDecrEnqueue(CAMPAIGN_ID, USER_ID))
                .thenReturn(new long[]{RedisStockService.QUEUE_FULL, 0L});

        assertThatThrownBy(() -> participationService.participate(CAMPAIGN_ID, USER_ID))
                .isInstanceOf(QueueFullException.class);

        // Queue full은 일시적 상태 → 사용자가 10초 대기 없이 재시도 가능해야 함
        verify(rateLimitService).release(CAMPAIGN_ID, USER_ID);
    }

    @Test
    @DisplayName("재고 소진(remaining < 0) → StockExhaustedException")
    void participate_stockExhausted_throwsStockExhausted() {
        when(rateLimitService.isAllowed(CAMPAIGN_ID, USER_ID)).thenReturn(true);
        when(redisStockService.checkDecrEnqueue(CAMPAIGN_ID, USER_ID))
                .thenReturn(new long[]{-1L, 1000L});

        assertThatThrownBy(() -> participationService.participate(CAMPAIGN_ID, USER_ID))
                .isInstanceOf(StockExhaustedException.class);
    }

    @Test
    @DisplayName("정상 참여(remaining > 0) → 예외 없이 완료")
    void participate_success_noException() {
        when(rateLimitService.isAllowed(CAMPAIGN_ID, USER_ID)).thenReturn(true);
        when(redisStockService.checkDecrEnqueue(CAMPAIGN_ID, USER_ID))
                .thenReturn(new long[]{500L, 1000L});

        assertThatCode(() -> participationService.participate(CAMPAIGN_ID, USER_ID))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("마지막 재고 소진(remaining == 0) → 캠페인 CLOSED DB 업데이트 호출")
    void participate_lastStock_closesCampaign() {
        when(rateLimitService.isAllowed(CAMPAIGN_ID, USER_ID)).thenReturn(true);
        when(redisStockService.checkDecrEnqueue(CAMPAIGN_ID, USER_ID))
                .thenReturn(new long[]{0L, 1000L});

        participationService.participate(CAMPAIGN_ID, USER_ID);

        verify(campaignRepository).closeAndResetStock(CAMPAIGN_ID, CampaignStatus.CLOSED);
    }

    @Test
    @DisplayName("T06 버그 수정 검증: 마지막 재고 소진 시 DB 호출 실패해도 500 전파 없이 정상 완료")
    void participate_lastStock_dbFailure_doesNotPropagate() {
        // Lua가 이미 active flag를 DEL → DB 실패해도 ConsistencyJob이 CLOSED 처리
        when(rateLimitService.isAllowed(CAMPAIGN_ID, USER_ID)).thenReturn(true);
        when(redisStockService.checkDecrEnqueue(CAMPAIGN_ID, USER_ID))
                .thenReturn(new long[]{0L, 1000L});
        doThrow(new RuntimeException("RDS 연결 실패"))
                .when(campaignRepository).closeAndResetStock(CAMPAIGN_ID, CampaignStatus.CLOSED);

        assertThatCode(() -> participationService.participate(CAMPAIGN_ID, USER_ID))
                .doesNotThrowAnyException();
    }
}
