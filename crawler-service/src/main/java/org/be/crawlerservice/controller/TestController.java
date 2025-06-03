package org.be.crawlerservice.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.be.crawlerservice.client.Crawl4AIClient;
import org.be.crawlerservice.client.schema.NaverNewsSchemas;
import org.be.crawlerservice.config.CrawlerProperties;
import org.be.crawlerservice.dto.crawl4ai.Crawl4AIRequest;
import org.be.crawlerservice.dto.crawl4ai.Crawl4AIResult;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/test")
@RequiredArgsConstructor
public class TestController {

    private final Crawl4AIClient crawl4AIClient;
    private final CrawlerProperties crawlerProperties;

    /**
     * Crawl4AI 서버 헬스 체크
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> testHealth() {
        try {
            boolean healthy = crawl4AIClient.isHealthy();

            Map<String, Object> response = new HashMap<>();
            response.put("crawl4ai_healthy", healthy);
            response.put("message", healthy ? "Crawl4AI 서버 정상" : "Crawl4AI 서버 오류");

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("헬스체크 실패", e);
            Map<String, Object> response = new HashMap<>();
            response.put("crawl4ai_healthy", false);
            response.put("error", e.getMessage());
            return ResponseEntity.status(503).body(response);
        }
    }

    /**
     * 기본 크롤링 테스트 (추출 전략 없음)
     */
    @PostMapping("/basic-crawl")
    public ResponseEntity<Map<String, Object>> testBasicCrawl(@RequestParam String url) {
        try {
            log.info("기본 크롤링 테스트 시작: {}", url);

            Crawl4AIRequest request = Crawl4AIRequest.forBasicCrawl(url);
            log.debug("생성된 요청: {}", request);

            Crawl4AIResult result = crawl4AIClient.crawl(request);

            // 안전한 Map 생성 (null 값 방지)
            Map<String, Object> response = new HashMap<>();

            if (result.isCrawlSuccessful()) {
                response.put("success", true);
                response.put("url", url);
                response.put("task_id", result.getTaskId());
                response.put("status", result.getStatus());

                // 결과 안전 처리
                if (result.getResult() != null) {
                    Crawl4AIResult.CrawlResult crawlResult = result.getResult();

                    // 마크다운 처리
                    String markdown = crawlResult.getMarkdown();
                    response.put("markdown_length", markdown != null ? markdown.length() : 0);
                    response.put("content_preview", markdown != null ?
                            markdown.substring(0, Math.min(200, markdown.length())) : "마크다운 없음");

                    // 추가 정보
                    response.put("html_length", crawlResult.getHtml() != null ? crawlResult.getHtml().length() : 0);
                    response.put("cleaned_html_length", crawlResult.getCleanedHtml() != null ? crawlResult.getCleanedHtml().length() : 0);
                    response.put("success_flag", crawlResult.getSuccess());
                    response.put("status_code", crawlResult.getStatusCode());

                    log.info("크롤링 성공: URL={}, 마크다운 길이={}", url, markdown != null ? markdown.length() : 0);
                } else {
                    response.put("markdown_length", 0);
                    response.put("content_preview", "결과 없음");
                    log.warn("크롤링 완료되었지만 결과가 없음: {}", url);
                }

                return ResponseEntity.ok(response);
            } else {
                response.put("success", false);
                response.put("error", result.getError() != null ? result.getError() : "알 수 없는 오류");
                response.put("status", result.getStatus());
                response.put("task_id", result.getTaskId());

                return ResponseEntity.status(500).body(response);
            }

        } catch (Exception e) {
            log.error("기본 크롤링 테스트 실패", e);
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("error", e.getMessage());
            errorResponse.put("exception_type", e.getClass().getSimpleName());
            return ResponseEntity.status(500).body(errorResponse);
        }
    }

    /**
     * 네이버 뉴스 목록 크롤링 테스트 (다양한 스키마)
     */
    @PostMapping("/naver-news-list")
    public ResponseEntity<Map<String, Object>> testNaverNewsList() {
        try {
            String url = "https://news.naver.com/section/100"; // 정치 카테고리

            // 개선된 스키마 사용
            Map<String, Object> testSchema = NaverNewsSchemas.getImprovedUrlListSchema();

            log.info("네이버 뉴스 목록 크롤링 테스트 시작 (개선된 스키마): {}", url);

            Crawl4AIRequest request = Crawl4AIRequest.forUrlList(url, testSchema);
            log.debug("생성된 요청: {}", request);

            Crawl4AIResult result = crawl4AIClient.crawl(request);

            Map<String, Object> response = new HashMap<>();

            if (result.isCrawlSuccessful()) {
                response.put("success", true);
                response.put("url", url);
                response.put("task_id", result.getTaskId());
                response.put("has_extracted_content", result.hasExtractedContent());

                if (result.hasExtractedContent()) {
                    String extractedContent = result.getResult().getExtractedContent();
                    response.put("extracted_content_preview", extractedContent.substring(0, Math.min(500, extractedContent.length())));
                    response.put("extracted_content_length", extractedContent.length());
                } else {
                    response.put("extracted_content_preview", "추출된 콘텐츠 없음");
                    response.put("extracted_content_length", 0);
                }

                return ResponseEntity.ok(response);
            } else {
                response.put("success", false);
                response.put("error", result.getError() != null ? result.getError() : "알 수 없는 오류");
                response.put("status", result.getStatus());
                return ResponseEntity.status(500).body(response);
            }

        } catch (Exception e) {
            log.error("네이버 뉴스 목록 크롤링 테스트 실패", e);
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("error", e.getMessage());
            return ResponseEntity.status(500).body(errorResponse);
        }
    }

    /**
     * 다양한 네이버 뉴스 스키마 테스트
     */
    @PostMapping("/naver-news-schemas")
    public ResponseEntity<Map<String, Object>> testNaverNewsSchemas() {
        try {
            String url = "https://news.naver.com/section/100";
            Map<String, Object> results = new HashMap<>();

            // 1. 개선된 스키마 테스트
            log.info("스키마 1: 개선된 네이버 뉴스 스키마 테스트");
            Map<String, Object> schema1 = NaverNewsSchemas.getSimpleLinkSchema();
            Crawl4AIRequest request1 = Crawl4AIRequest.forUrlList(url, schema1);

            Crawl4AIResult result1 = crawl4AIClient.crawl(request1);
            //Crawl4AIResult.CrawlResult crawlResult = result1.getResult();

//            System.out.println("으아악 : " + result1.getExtracted_content());
//            System.out.println("으아악 : " + crawlResult.getExtractedContent());
//            System.out.println("으아악 : " + crawlResult.getLinks());

            results.put("schema1_improved", Map.of(
                    "success", result1.isCrawlSuccessful(),
                    "has_content", result1.hasExtractedContent(),
                    "content_length", result1.hasExtractedContent() ?
                            result1.getResult().getExtractedContent().length() : 0
            ));



            return ResponseEntity.ok(results);

        } catch (Exception e) {
            log.error("네이버 뉴스 스키마 테스트 실패", e);
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("error", e.getMessage());
            return ResponseEntity.status(500).body(errorResponse);
        }
    }

    /**
     * HTML 구조 분석 (스키마 없이)
     */
    @PostMapping("/analyze-html")
    public ResponseEntity<Map<String, Object>> analyzeHtml(@RequestParam String url) {
        try {
            log.info("HTML 구조 분석: {}", url);

            // 스키마 없이 기본 크롤링
            Crawl4AIRequest request = Crawl4AIRequest.forBasicCrawl(url);
            Crawl4AIResult result = crawl4AIClient.crawl(request);

            Map<String, Object> response = new HashMap<>();

            if (result.isCrawlSuccessful() && result.getResult() != null) {
                Crawl4AIResult.CrawlResult crawlResult = result.getResult();

                response.put("success", true);
                response.put("url", url);
                response.put("status_code", crawlResult.getStatusCode());
                response.put("html_length", crawlResult.getHtml() != null ? crawlResult.getHtml().length() : 0);
                response.put("cleaned_html_length", crawlResult.getCleanedHtml() != null ? crawlResult.getCleanedHtml().length() : 0);
                response.put("markdown_length", crawlResult.getMarkdown() != null ? crawlResult.getMarkdown().length() : 0);

                // 마크다운에서 링크 패턴 분석
                if (crawlResult.getMarkdown() != null) {
                    String markdown = crawlResult.getMarkdown();
                    long newsLinks = markdown.lines()
                            .filter(line -> line.contains("news.naver.com"))
                            .count();

                    response.put("news_links_found", newsLinks);
                    response.put("markdown_preview", markdown.substring(0, Math.min(1000, markdown.length())));
                }

                return ResponseEntity.ok(response);
            } else {
                response.put("success", false);
                response.put("error", result.getError());
                return ResponseEntity.status(500).body(response);
            }

        } catch (Exception e) {
            log.error("HTML 구조 분석 실패", e);
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("error", e.getMessage());
            return ResponseEntity.status(500).body(errorResponse);
        }
    }

    /**
     * 단순 텍스트 크롤링 테스트
     */
    @PostMapping("/text-only")
    public ResponseEntity<Map<String, Object>> testTextOnly(@RequestParam String url) {
        try {
            log.info("텍스트 전용 크롤링 테스트 시작: {}", url);

            Crawl4AIRequest request = Crawl4AIRequest.forTextOnly(url);
            Crawl4AIResult result = crawl4AIClient.crawl(request);

            Map<String, Object> response = new HashMap<>();

            if (result.isCrawlSuccessful()) {
                response.put("success", true);
                response.put("url", url);
                response.put("task_id", result.getTaskId());

                if (result.getResult() != null) {
                    Crawl4AIResult.CrawlResult crawlResult = result.getResult();
                    response.put("markdown_length", crawlResult.getMarkdown() != null ? crawlResult.getMarkdown().length() : 0);
                    response.put("cleaned_html_length", crawlResult.getCleanedHtml() != null ? crawlResult.getCleanedHtml().length() : 0);
                } else {
                    response.put("markdown_length", 0);
                    response.put("cleaned_html_length", 0);
                }

                return ResponseEntity.ok(response);
            } else {
                response.put("success", false);
                response.put("error", result.getError() != null ? result.getError() : "알 수 없는 오류");
                return ResponseEntity.status(500).body(response);
            }

        } catch (Exception e) {
            log.error("텍스트 전용 크롤링 테스트 실패", e);
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("error", e.getMessage());
            return ResponseEntity.status(500).body(errorResponse);
        }
    }

    /**
     * 크롤링 결과 상세 정보 (디버깅용)
     */
    @PostMapping("/debug-crawl")
    public ResponseEntity<Map<String, Object>> debugCrawl(@RequestParam String url) {
        try {
            log.info("디버깅 크롤링 시작: {}", url);

            Crawl4AIRequest request = Crawl4AIRequest.forBasicCrawl(url);
            Crawl4AIResult result = crawl4AIClient.crawl(request);

            Map<String, Object> response = new HashMap<>();
            response.put("task_id", result.getTaskId());
            response.put("status", result.getStatus());
            response.put("completed", result.isCompleted());
            response.put("crawl_successful", result.isCrawlSuccessful());
            response.put("has_extracted_content", result.hasExtractedContent());
            response.put("error", result.getError());

            if (result.getResult() != null) {
                Crawl4AIResult.CrawlResult crawlResult = result.getResult();

                Map<String, Object> resultInfo = new HashMap<>();
                resultInfo.put("success", crawlResult.getSuccess());
                resultInfo.put("status_code", crawlResult.getStatusCode());
                resultInfo.put("html_length", crawlResult.getHtml() != null ? crawlResult.getHtml().length() : 0);
                resultInfo.put("cleaned_html_length", crawlResult.getCleanedHtml() != null ? crawlResult.getCleanedHtml().length() : 0);
                resultInfo.put("markdown_length", crawlResult.getMarkdown() != null ? crawlResult.getMarkdown().length() : 0);
                resultInfo.put("extracted_content_length", crawlResult.getExtractedContent() != null ? crawlResult.getExtractedContent().length() : 0);
                resultInfo.put("screenshot_available", crawlResult.getScreenshot() != null);
                resultInfo.put("pdf_available", crawlResult.getPdf() != null);

                // 마크다운 미리보기 (처음 300자)
                if (crawlResult.getMarkdown() != null) {
                    resultInfo.put("markdown_preview", crawlResult.getMarkdown().substring(0, Math.min(300, crawlResult.getMarkdown().length())));
                }

                response.put("result_details", resultInfo);
            } else {
                response.put("result_details", "결과 없음");
            }

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("디버깅 크롤링 실패", e);
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("error", e.getMessage());
            errorResponse.put("exception_type", e.getClass().getSimpleName());
            return ResponseEntity.status(500).body(errorResponse);
        }
    }

    /**
     * 간단한 마크다운 크롤링 테스트
     */
    @PostMapping("/simple-markdown")
    public ResponseEntity<Map<String, Object>> simpleMarkdown(@RequestParam String url) {
        try {
            log.info("간단 마크다운 테스트: {}", url);

            // 더 간단한 설정으로 테스트
            Crawl4AIRequest request = Crawl4AIRequest.builder()
                    .urls(List.of(url))
                    .browserConfig(Crawl4AIRequest.ConfigWrapper.builder()
                            .type("BrowserConfig")
                            .params(Map.of("headless", true))
                            .build())
                    .crawlerConfig(Crawl4AIRequest.ConfigWrapper.builder()
                            .type("CrawlerRunConfig")
                            .params(Map.of("cache_mode", "bypass"))
                            .build())
                    .build();

            Crawl4AIResult result = crawl4AIClient.crawl(request);

            Map<String, Object> response = new HashMap<>();
            response.put("task_id", result.getTaskId());
            response.put("status", result.getStatus());
            response.put("completed", result.isCompleted());

            if (result.getResult() != null && result.getResult().getMarkdown() != null) {
                String markdown = result.getResult().getMarkdown();
                response.put("success", true);
                response.put("markdown_length", markdown.length());
                response.put("markdown_preview", markdown.substring(0, Math.min(500, markdown.length())));
            } else {
                response.put("success", false);
                response.put("message", "마크다운 결과 없음");
            }

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("간단 마크다운 테스트 실패", e);
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("error", e.getMessage());
            return ResponseEntity.status(500).body(errorResponse);
        }
    }
}