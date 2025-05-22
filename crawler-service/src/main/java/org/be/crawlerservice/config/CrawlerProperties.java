package org.be.crawlerservice.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "crawler")
public class CrawlerProperties {

    /**
     * Crawl4AI 서버 URL
     */
    private String crawl4aiUrl = "http://crawl4ai-server:11235";

    /**
     * API 토큰
     */
    private String apiToken = "home";

    /**
     * 크롤링 간격 (시간)
     */
    private int intervalHours = 3;

    /**
     * 배치 크기
     */
    private int batchSize = 10;

    /**
     * 최대 동시 작업 수
     */
    private int maxConcurrentTasks = 5;

    /**
     * 타임아웃 (초)
     */
    private int timeoutSeconds = 180;
}