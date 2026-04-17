-- participation_history 테이블에 캠페인+유저 조합 유니크 제약 추가                                                                                                               -- 동일 유저가 같은 캠페인에 중복 참여 방지 (멱등성 보장)
-- INSERT 재시도 시 DuplicateKeyException으로 중복 감지

ALTER TABLE participation_history
    ADD CONSTRAINT uk_campaign_user UNIQUE (campaign_id, user_id);


