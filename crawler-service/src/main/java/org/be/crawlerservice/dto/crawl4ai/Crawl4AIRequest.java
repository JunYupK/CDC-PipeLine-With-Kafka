package org.be.crawlerservice.dto.crawl4ai;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Crawl4AIRequest {

    /**
     * 크롤링할 URL 배열 (Crawl4AI는 배열을 기대함)
     */
    private List<String> urls;

    /**
     * 브라우저 설정 (Crawl4AI Docker API 형식)
     */
    @JsonProperty("browser_config")
    private ConfigWrapper browserConfig;

    /**
     * 크롤러 설정 (Crawl4AI Docker API 형식)
     */
    @JsonProperty("crawler_config")
    private ConfigWrapper crawlerConfig;

    /**
     * 우선순위 (1-10, 높을수록 우선)
     */
    @Builder.Default
    private Integer priority = 5;

    /**
     * Crawl4AI Docker API가 기대하는 설정 래퍼 클래스
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ConfigWrapper {
        /**
         * 설정 타입 ("BrowserConfig" 또는 "CrawlerRunConfig")
         */
        private String type;

        /**
         * 실제 파라미터들
         */
        private Map<String, Object> params;
    }

    // === 팩토리 메서드들 ===

    /**
     * URL 목록 크롤링용 요청 생성 (CSS 추출 포함)
     */
    public static Crawl4AIRequest forUrlList(String url, Map<String, Object> schema) {
        // 브라우저 설정
        ConfigWrapper browserConfig = ConfigWrapper.builder()
                .type("BrowserConfig")
                .params(Map.of(
                        "headless", true,
                        "viewport_width", 1920,
                        "viewport_height", 1080
                ))
                .build();

        // 크롤러 설정 (추출 전략 포함)
        ConfigWrapper crawlerConfig = ConfigWrapper.builder()
                .type("CrawlerRunConfig")
                .params(Map.of(
                        "cache_mode", "bypass",
                        "wait_until", "domcontentloaded",
                        "page_timeout", 60000,
                        "extraction_strategy", Map.of(
                                "type", "JsonCssExtractionStrategy",
                                "params", Map.of("schema", schema)
                        )
                ))
                .build();

        return Crawl4AIRequest.builder()
                .urls(List.of(url))
                .browserConfig(browserConfig)
                .crawlerConfig(crawlerConfig)
                .priority(10)
                .build();
    }

    /**
     * 기사 내용 크롤링용 요청 생성
     */
    public static Crawl4AIRequest forArticleContent(String url, Map<String, Object> schema) {
        // 브라우저 설정
        ConfigWrapper browserConfig = ConfigWrapper.builder()
                .type("BrowserConfig")
                .params(Map.of(
                        "headless", true,
                        "viewport_width", 1920,
                        "viewport_height", 1080
                ))
                .build();

        // 크롤러 설정
        ConfigWrapper crawlerConfig = ConfigWrapper.builder()
                .type("CrawlerRunConfig")
                .params(Map.of(
                        "cache_mode", "bypass",
                        "wait_until", "domcontentloaded",
                        "page_timeout", 60000,
                        "delay_before_return_html", 2.0,
                        "extraction_strategy", Map.of(
                                "type", "JsonCssExtractionStrategy",
                                "params", Map.of("schema", schema)
                        )
                ))
                .build();

        return Crawl4AIRequest.builder()
                .urls(List.of(url))
                .browserConfig(browserConfig)
                .crawlerConfig(crawlerConfig)
                .priority(8)
                .build();
    }

    /**
     * 기본 크롤링 요청 생성 (추출 전략 없음)
     */
    public static Crawl4AIRequest forBasicCrawl(String url) {
        // 브라우저 설정
        ConfigWrapper browserConfig = ConfigWrapper.builder()
                .type("BrowserConfig")
                .params(Map.of(
                        "headless", true,
                        "viewport_width", 1920,
                        "viewport_height", 1080
                ))
                .build();

        // 크롤러 설정 (추출 전략 없음)
        ConfigWrapper crawlerConfig = ConfigWrapper.builder()
                .type("CrawlerRunConfig")
                .params(Map.of(
                        "cache_mode", "bypass",
                        "wait_until", "domcontentloaded",
                        "page_timeout", 60000
                ))
                .build();

        return Crawl4AIRequest.builder()
                .urls(List.of(url))
                .browserConfig(browserConfig)
                .crawlerConfig(crawlerConfig)
                .priority(5)
                .build();
    }

    /**
     * 단순 텍스트 크롤링 (마크다운만)
     */
    public static Crawl4AIRequest forTextOnly(String url) {
        ConfigWrapper browserConfig = ConfigWrapper.builder()
                .type("BrowserConfig")
                .params(Map.of(
                        "headless", true,
                        "text_mode", true  // 이미지 비활성화
                ))
                .build();

        ConfigWrapper crawlerConfig = ConfigWrapper.builder()
                .type("CrawlerRunConfig")
                .params(Map.of(
                        "cache_mode", "bypass",
                        "word_count_threshold", 50,
                        "excluded_tags", List.of("script", "style", "nav", "footer")
                ))
                .build();

        return Crawl4AIRequest.builder()
                .urls(List.of(url))
                .browserConfig(browserConfig)
                .crawlerConfig(crawlerConfig)
                .priority(5)
                .build();
    }
}