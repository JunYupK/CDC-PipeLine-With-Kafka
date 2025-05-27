package org.be.crawlerservice.dto.crawl4ai;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Crawl4AIRequest {

    /**
     * 크롤링할 URL (단일 URL 문자열)
     */
    private String urls;

    /**
     * 추출 설정
     */
    private ExtractionConfig extractionConfig;

    /**
     * 크롤러 파라미터
     */
    private CrawlerParams crawlerParams;

    /**
     * 우선순위 (1-10, 높을수록 우선)
     */
    @Builder.Default
    private Integer priority = 5;

    /**
     * 추가 파라미터
     */
    private Map<String, Object> extra;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ExtractionConfig {
        /**
         * 추출 타입 ("json_css")
         */
        private String type;

        /**
         * 추출 파라미터 (스키마 포함)
         */
        private ExtractionParams params;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ExtractionParams {
        /**
         * CSS 추출 스키마
         */
        private Map<String, Object> schema;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CrawlerParams {
        /**
         * 헤드리스 모드 여부
         */
        @Builder.Default
        private Boolean headless = true;

        /**
         * 페이지 타임아웃 (밀리초)
         */
        @Builder.Default
        private Long pageTimeout = 60000L;

        /**
         * 대기 조건 ("domcontentloaded", "networkidle", "load")
         */
        @Builder.Default
        private String waitUntil = "domcontentloaded";

        /**
         * HTML 반환 전 지연 시간 (초)
         */
        @Builder.Default
        private Double delayBeforeReturnHtml = 0.0;

        /**
         * 스크린샷 캡처 여부
         */
        @Builder.Default
        private Boolean screenshot = false;

        /**
         * 대기할 CSS 선택자
         */
        private String waitFor;

        /**
         * 실행할 JavaScript 코드
         */
        private String jsCode;
    }

    // === 팩토리 메서드들 ===

    /**
     * URL 목록 크롤링용 요청 생성
     */
    public static Crawl4AIRequest forUrlList(String url, Map<String, Object> schema) {
        return Crawl4AIRequest.builder()
                .urls(url)
                .extractionConfig(ExtractionConfig.builder()
                        .type("json_css")
                        .params(ExtractionParams.builder()
                                .schema(schema)
                                .build())
                        .build())
                .crawlerParams(CrawlerParams.builder()
                        .headless(true)
                        .pageTimeout(60000L)
                        .waitUntil("domcontentloaded")
                        .build())
                .priority(10) // 목록 페이지는 높은 우선순위
                .build();
    }

    /**
     * 기사 내용 크롤링용 요청 생성
     */
    public static Crawl4AIRequest forArticleContent(String url, Map<String, Object> schema) {
        return Crawl4AIRequest.builder()
                .urls(url)
                .extractionConfig(ExtractionConfig.builder()
                        .type("json_css")
                        .params(ExtractionParams.builder()
                                .schema(schema)
                                .build())
                        .build())
                .crawlerParams(CrawlerParams.builder()
                        .headless(true)
                        .pageTimeout(60000L)
                        .delayBeforeReturnHtml(2.0)
                        .build())
                .priority(8) // 개별 기사는 보통 우선순위
                .build();
    }

    /**
     * 기본 크롤링 요청 생성 (추출 전략 없음)
     */
    public static Crawl4AIRequest forBasicCrawl(String url) {
        return Crawl4AIRequest.builder()
                .urls(url)
                .crawlerParams(CrawlerParams.builder()
                        .headless(true)
                        .pageTimeout(60000L)
                        .build())
                .priority(5)
                .build();
    }
}