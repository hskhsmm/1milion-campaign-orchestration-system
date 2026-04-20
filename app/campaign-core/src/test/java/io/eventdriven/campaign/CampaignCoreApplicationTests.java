package io.eventdriven.campaign;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
@Disabled("로컬 환경에서는 DB/Kafka 연결이 없어 컨텍스트 로딩 실패 — CI 환경에서만 실행")
class CampaignCoreApplicationTests {

	@Test
	void contextLoads() {
	}

}
