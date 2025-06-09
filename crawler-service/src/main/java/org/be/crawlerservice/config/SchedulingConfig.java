package org.be.crawlerservice.config;


import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.be.crawlerservice.dto.request.CrawlRequestDto;
import org.be.crawlerservice.service.crawler.CrawlerService;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Slf4j
@Configuration
@EnableScheduling
@RequiredArgsConstructor
public class SchedulingConfig {

    private final CrawlerService crawlerService;

    /**
     * 10초마다 시스템 메트릭 업데이트 (Python 코드와 동일)
     */
    @Scheduled(fixedRate = 10000) // 10초
    public void updateSystemMetrics() {
        try {
            // CrawlerMetrics에서 시스템 메트릭은 자동으로 업데이트되므로
            // 여기서는 추가적인 작업이 필요한 경우에만 구현
            log.trace("시스템 메트릭 업데이트");

        } catch (Exception e) {
            log.warn("시스템 메트릭 업데이트 실패", e);
        }
    }
}