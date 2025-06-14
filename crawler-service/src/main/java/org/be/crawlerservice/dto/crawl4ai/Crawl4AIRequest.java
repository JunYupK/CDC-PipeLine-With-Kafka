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
//        // 브라우저 설정
        ConfigWrapper browserConfig = ConfigWrapper.builder()
                .type("BrowserConfig")
                .params(Map.of(
                        "headless", true
                ))
                .build();
//
//        // 크롤러 설정 (추출 전략 포함)
        ConfigWrapper crawlerConfig = ConfigWrapper.builder()
                .type("CrawlerRunConfig")
                .params(Map.of(
                        "cache_mode", "bypass",
                        "page_timeout", 60000,
                        "delay_before_return_html", 2.0,
                        "extraction_strategy", Map.of(
                                "type", "JsonCssExtractionStrategy",
                                "params", schema
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
     * 네이버 뉴스 전용 BFS Deep Crawling
     */
    public static Crawl4AIRequest forNaverNewsBFS(String categoryUrl) {
        // 네이버 뉴스에 특화된 설정
        ConfigWrapper browserConfig = ConfigWrapper.builder()
                .type("BrowserConfig")
                .params(Map.of(
                        "headless", true,
                        "viewport_width", 1920,
                        "viewport_height", 1080,
                        "headers", Map.of(
                                "Accept-Language", "ko-KR,ko;q=0.9,en;q=0.8"
                        )
                ))
                .build();

        ConfigWrapper crawlerConfig = ConfigWrapper.builder()
                .type("CrawlerRunConfig")
                .params(Map.of(
                        "cache_mode", "bypass",
                        "wait_until", "domcontentloaded",
                        "page_timeout", 45000,
                        "stream", false,
                        "deep_crawl_strategy", Map.of(
                                "type", "BFSDeepCrawlStrategy",
                                "params", Map.of(
                                        "max_depth", 2,        // 목록 페이지 -> 기사 페이지
                                        "max_pages", 30,       // 적당한 개수로 제한
                                        "include_external", false,
                                        "score_threshold", 0.2
                                )
                        ),
                        // 네이버 뉴스 구조에 맞는 추출 설정
                        "css_selector", "body",
                        "excluded_tags", List.of("script", "style", "nav", "header", "footer"),
                        "word_count_threshold", 50
                ))
                .build();

        return Crawl4AIRequest.builder()
                .urls(List.of(categoryUrl))
                .browserConfig(browserConfig)
                .crawlerConfig(crawlerConfig)
                .priority(9)
                .build();
    }
    /**
     * BFS Deep Crawling 요청 생성 (뉴스 사이트 최적화)
     */
    public static Crawl4AIRequest forBFSDeepCrawl(String startUrl, int maxDepth, int maxPages) {
        // 브라우저 설정
        ConfigWrapper browserConfig = ConfigWrapper.builder()
                .type("BrowserConfig")
                .params(Map.of(
                        "headless", true,
                        "viewport_width", 1920,
                        "viewport_height", 1080,
                        "text_mode", false  // 이미지도 수집
                ))
                .build();

        // BFS Deep Crawling 전략이 포함된 크롤러 설정
        ConfigWrapper crawlerConfig = ConfigWrapper.builder()
                .type("CrawlerRunConfig")
                .params(Map.of(
                        "cache_mode", "bypass",
                        "wait_until", "networkidle",
                        "page_timeout", 60000,
                        "stream", false,  // 스트리밍 모드 활성화
                        "deep_crawl_strategy", Map.of(
                                "type", "BFSDeepCrawlStrategy",
                                "params", Map.of(
                                        "max_depth", maxDepth,
                                        "max_pages", maxPages,
                                        "include_external", false,
                                        "score_threshold", 0.3
                                )
                        ),
                        // 뉴스 사이트 최적화 설정
                        "excluded_tags", List.of("script", "style", "nav", "footer", "aside"),
                        "word_count_threshold", 100
                ))
                .build();

        return Crawl4AIRequest.builder()
                .urls(List.of(startUrl))
                .browserConfig(browserConfig)
                .crawlerConfig(crawlerConfig)
                .priority(8)
                .build();
    }

}