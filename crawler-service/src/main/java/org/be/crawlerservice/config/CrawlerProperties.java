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

    /**
     * 폴링 간격 (초)
     */
    private int pollIntervalSeconds = 3;

    /**
     * 최대 재시도 횟수
     */
    private int maxRetries = 3;

    /**
     * 기사 간 딜레이 (밀리초)
     */
    private long articleDelayMs = 1500;

    /**
     * 카테고리 간 딜레이 (밀리초)
     */
    private long categoryDelayMs = 2000;

    /**
     * HTTP 연결 타임아웃 (밀리초)
     */
    private int httpConnectTimeoutMs = 30000;

    /**
     * HTTP 읽기 타임아웃 (밀리초)
     */
    private int httpReadTimeoutMs = 60000;

    /**
     * 개발 모드 여부 (더 자세한 로깅)
     */
    private boolean developmentMode = false;

    /**
     * 헬스체크 활성화 여부
     */
    private boolean healthCheckEnabled = true;

    /**
     * 헬스체크 간격 (초)
     */
    private int healthCheckIntervalSeconds = 300;

    // === 네이버 뉴스 특정 설정 ===

    /**
     * 최소 기사 수 (이보다 적으면 재시도)
     */
    private int minArticleCount = 10;

    /**
     * 최대 기사 수 (페이지 수 제한)
     */
    private int maxArticleCount = 100;

    /**
     * 콘텐츠 최소 길이 (글자 수)
     */
    private int minContentLength = 100;

    /**
     * User-Agent 문자열
     */
    private String userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36";

    // === 유효성 검사 메서드 ===

    /**
     * 설정값 유효성 검사
     */
    public void validate() {
        if (crawl4aiUrl == null || crawl4aiUrl.trim().isEmpty()) {
            throw new IllegalArgumentException("Crawl4AI URL is required");
        }

        if (intervalHours < 1) {
            throw new IllegalArgumentException("Interval hours must be at least 1");
        }

        if (timeoutSeconds < 30) {
            throw new IllegalArgumentException("Timeout must be at least 30 seconds");
        }

        if (pollIntervalSeconds < 1) {
            throw new IllegalArgumentException("Poll interval must be at least 1 second");
        }

        if (maxRetries < 1) {
            throw new IllegalArgumentException("Max retries must be at least 1");
        }
    }

    // === 편의 메서드 ===

    /**
     * Crawl4AI URL에서 프로토콜 제거한 호스트명 반환
     */
    public String getCrawl4aiHost() {
        if (crawl4aiUrl == null) return "unknown";
        return crawl4aiUrl.replaceAll("^https?://", "").split(":")[0];
    }

    /**
     * 개발 환경 여부 체크 (로컬호스트 URL 기준)
     */
    public boolean isLocalEnvironment() {
        return crawl4aiUrl != null &&
                (crawl4aiUrl.contains("localhost") || crawl4aiUrl.contains("127.0.0.1"));
    }

    /**
     * 설정 요약 정보 반환 (로깅용)
     */
    public String getConfigSummary() {
        return String.format(
                "Crawl4AI[url=%s, timeout=%ds, interval=%dh, batch=%d]",
                getCrawl4aiHost(),
                timeoutSeconds,
                intervalHours,
                batchSize
        );
    }
}