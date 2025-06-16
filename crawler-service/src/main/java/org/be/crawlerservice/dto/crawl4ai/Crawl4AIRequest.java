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
                        "stream", true,
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
    public static Crawl4AIRequest forBFSDeepCrawl(String startUrl, int maxDepth, int maxPages, Map<String, Object> schema) {
        // 브라우저 설정
        ConfigWrapper browserConfig = ConfigWrapper.builder()
                .type("BrowserConfig")
                .params(Map.of(
                        "headless", true,
                        "viewport_width", 1920,
                        "viewport_height", 1080,
                        "text_mode", false
                ))
                .build();

        // BFS Deep Crawling 전략이 포함된 크롤러 설정
        ConfigWrapper crawlerConfig = ConfigWrapper.builder()
                .type("CrawlerRunConfig")
                .params(Map.of(
                        "cache_mode", "bypass",
                        "wait_until", "networkidle",
                        "page_timeout", 30000,
                        "delay_before_return_html", 2.0,
                        "extraction_strategy", Map.of(
                                "type", "JsonCssExtractionStrategy",
                                "params", Map.of("schema", schema)
                        ),
                        "deep_crawl_strategy", Map.of(
                                "type", "BFSDeepCrawlStrategy",
                                "params", Map.of(
                                        "max_depth", maxDepth,
                                        "include_external", true,
                                        "max_pages", maxPages,
                                        "score_threshold", 0.0,
                                        "filter_chain", Map.of(
                                                "type", "FilterChain",
                                                "params", Map.of(
                                                        "filters", List.of(
                                                                Map.of(
                                                                        "type", "URLPatternFilter",
                                                                        "params", Map.of(
                                                                                "patterns", List.of("*article*")
                                                                        )
                                                                )
                                                        )
                                                )
                                        )
                                )
                        )
                ))
                .build();

        return Crawl4AIRequest.builder()
                .urls(List.of(startUrl))
                .browserConfig(browserConfig)
                .crawlerConfig(crawlerConfig)
                .priority(8)
                .build();
    }
    /**
     * BestFirstCrawlingStrategy를 사용한 네이버 뉴스 전용 크롤링
     * 더 똑똑한 필터링과 스코어링으로 고품질 기사 우선 수집
     */
    public static Crawl4AIRequest forBestFirstNaverNews(String startUrl, int maxDepth, int maxPages, Map<String, Object> schema) {

        // 브라우저 설정
        ConfigWrapper browserConfig = ConfigWrapper.builder()
                .type("BrowserConfig")
                .params(Map.of(
                        "headless", true,
                        "viewport_width", 1920,
                        "viewport_height", 1080,
                        "text_mode", false,
                        "headers", Map.of(
                                "Accept-Language", "ko-KR,ko;q=0.9,en;q=0.8",
                                "User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                        )
                ))
                .build();

        // BestFirst Deep Crawling 전략 설정
        ConfigWrapper crawlerConfig = ConfigWrapper.builder()
                .type("CrawlerRunConfig")
                .params(Map.of(
                        "cache_mode", "bypass",
                        "wait_until", "networkidle",
                        "page_timeout", 45000,  // 네이버 뉴스는 좀 더 긴 타임아웃
                        "delay_before_return_html", 2.0,
                        "extraction_strategy", Map.of(
                                "type", "JsonCssExtractionStrategy",
                                "params", Map.of("schema", schema)
                        ),
                        "deep_crawl_strategy", Map.of(
                                "type", "BestFirstCrawlingStrategy",
                                "params", Map.of(
                                        "max_depth", maxDepth,
                                        "max_pages", maxPages,
                                        "include_external", false,  // 네이버 도메인 내에서만

                                        // 🎯 네이버 뉴스 특화 KeywordRelevanceScorer
                                        "url_scorer", Map.of(
                                                "type", "KeywordRelevanceScorer",
                                                "params", Map.of(
                                                        "keywords", List.of(
                                                                // 네이버 뉴스 관련 키워드
                                                                "뉴스", "기사", "article", "mnews",
                                                                // 카테고리별 키워드
                                                                "정치", "경제", "사회", "문화", "세계", "IT", "과학", "스포츠",
                                                                // 스포츠 세부 키워드
                                                                "야구", "축구", "농구", "배구", "골프", "kbaseball", "kfootball"
                                                        ),
                                                        "weight", 0.8  // 높은 가중치로 관련성 강조
                                                )
                                        ),

                                        // 🔍 정교한 FilterChain 설정
                                        "filter_chain", Map.of(
                                                "type", "FilterChain",
                                                "params", Map.of(
                                                        "filters", List.of(
                                                                // 1. 도메인 필터 - 네이버 뉴스 도메인만 허용
                                                                Map.of(
                                                                        "type", "DomainFilter",
                                                                        "params", Map.of(
                                                                                "allowed_domains", List.of(
                                                                                        "news.naver.com",
                                                                                        "n.news.naver.com",
                                                                                        "m.news.naver.com",
                                                                                        "sports.naver.com",
                                                                                        "m.sports.naver.com"
                                                                                ),
                                                                                "blocked_domains", List.of(
                                                                                        "ad.naver.com",
                                                                                        "shopping.naver.com"
                                                                                )
                                                                        )
                                                                ),

                                                                // 2. URL 패턴 필터 - 실제 기사 URL만 허용
                                                                Map.of(
                                                                        "type", "URLPatternFilter",
                                                                        "params", Map.of(
                                                                                "patterns", List.of(
                                                                                        // 일반 뉴스 패턴
                                                                                        "*/mnews/article/*/*",
                                                                                        "*news.naver.com/mnews/article*",

                                                                                        // 스포츠 뉴스 패턴
                                                                                        "*sports.naver.com/*/article/*/*",
                                                                                        "*sports.naver.com/kbaseball/article*",
                                                                                        "*sports.naver.com/kfootball/article*",
                                                                                        "*sports.naver.com/wbaseball/article*",
                                                                                        "*sports.naver.com/basketball/article*",
                                                                                        "*sports.naver.com/volleyball/article*",
                                                                                        "*sports.naver.com/golf/article*",
                                                                                        "*sports.naver.com/esports/article*",
                                                                                        "*sports.naver.com/general/article*",

                                                                                        // 섹션 페이지도 포함 (링크 발견용)
                                                                                        "*/section/*",
                                                                                        "*news.naver.com/section*"
                                                                                )
                                                                        )
                                                                ),

                                                                // 3. 콘텐츠 타입 필터
                                                                Map.of(
                                                                        "type", "ContentTypeFilter",
                                                                        "params", Map.of(
                                                                                "allowed_types", List.of("text/html")
                                                                        )
                                                                ),

                                                                // 4. SEO 필터 - 고품질 기사 우선
                                                                Map.of(
                                                                        "type", "SEOFilter",
                                                                        "params", Map.of(
                                                                                "threshold", 0.3,  // 낮은 임계값으로 설정
                                                                                "keywords", List.of(
                                                                                        "뉴스", "기사", "보도", "취재", "독점"
                                                                                )
                                                                        )
                                                                )
                                                        )
                                                )
                                        )
                                )
                        )
                ))
                .build();

        return Crawl4AIRequest.builder()
                .urls(List.of(startUrl))
                .browserConfig(browserConfig)
                .crawlerConfig(crawlerConfig)
                .priority(9)  // 높은 우선순위
                .build();
    }


}