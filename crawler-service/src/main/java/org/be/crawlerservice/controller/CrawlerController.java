package org.be.crawlerservice.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.be.crawlerservice.dto.request.CrawlRequestDto;
import org.be.crawlerservice.dto.response.CrawlStatusDto;
import org.be.crawlerservice.service.crawler.CrawlerService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/crawl")
@RequiredArgsConstructor
public class CrawlerController {

    private final CrawlerService crawlerService;

    /**
     * 일반 크롤링 작업 수동 트리거
     * POST /api/v1/crawl/basic
     */
    @PostMapping("/basic")
    public ResponseEntity<CrawlStatusDto> triggerCrawl(
            @Valid @RequestBody CrawlRequestDto request) {

        log.info("Manual crawl triggered for category: {}", request.getCategory());

        try {
            CrawlStatusDto status = crawlerService.startCrawling(request);
            return ResponseEntity.ok(status);
        } catch (Exception e) {
            log.error("Failed to start crawling", e);
            return ResponseEntity.internalServerError()
                    .body(CrawlStatusDto.failed("Failed to start crawling: " + e.getMessage()));
        }
    }

    /**
     * BFS Deep Crawling 작업 수동 트리거
     * POST /api/v1/crawl/deep
     */
    @PostMapping("/deep")
    public ResponseEntity<CrawlStatusDto> triggerDeepCrawl(
            @Valid @RequestBody CrawlRequestDto request) {

        log.info("BFS Deep Crawling triggered for category: {}", request.getCategory());

        try {
            CrawlStatusDto status = crawlerService.startDeepCrawling();
            return ResponseEntity.ok(status);
        } catch (RuntimeException e) {
            log.warn("Failed to start BFS Deep Crawling: {}", e.getMessage());
            return ResponseEntity.badRequest()
                    .body(CrawlStatusDto.failed(e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to start BFS Deep Crawling", e);
            return ResponseEntity.internalServerError()
                    .body(CrawlStatusDto.failed("Failed to start BFS Deep Crawling: " + e.getMessage()));
        }
    }
    /**
     * 특정 카테고리 기본 크롤링 트리거 (간단한 방식)
     * POST /api/v1/crawl/{category}
     */
    @PostMapping("/{category}")
    public ResponseEntity<CrawlStatusDto> triggerCrawlByCategory(
            @PathVariable String category) {

        log.info("Manual crawl triggered for category: {}", category);

        CrawlRequestDto request = CrawlRequestDto.builder()
                .category(category)
                .build();

        try {
            CrawlStatusDto status = crawlerService.startCrawling(request);
            return ResponseEntity.ok(status);
        } catch (Exception e) {
            log.error("Failed to start crawling for category: {}", category, e);
            return ResponseEntity.internalServerError()
                    .body(CrawlStatusDto.failed("Failed to start crawling: " + e.getMessage()));
        }
    }

    /**
     * 실행 중인 크롤링 작업 중지
     * POST /api/v1/crawl/stop
     */
    @PostMapping("/stop")
    public ResponseEntity<CrawlStatusDto> stopCrawl() {
        log.info("Crawling stop requested");

        try {
            CrawlStatusDto status = crawlerService.stopCrawling();
            return ResponseEntity.ok(status);
        } catch (RuntimeException e) {
            log.warn("No running crawling job found");
            return ResponseEntity.badRequest()
                    .body(CrawlStatusDto.failed(e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to stop crawling", e);
            return ResponseEntity.internalServerError()
                    .body(CrawlStatusDto.failed("Failed to stop crawling"));
        }
    }
    /**
     * 크롤링 방법 비교 정보 (개발/테스트용)
     * GET /api/v1/crawl/compare-methods
     */
    @GetMapping("/compare-methods")
    public ResponseEntity<Map<String, Object>> compareMethodsInfo() {
        log.info("크롤링 방법 비교 정보 요청");

        try {
            Map<String, Object> comparison = Map.of(
                    "basic_crawling", Map.of(
                            "description", "기본 크롤링 방식",
                            "method", "단일 페이지 → 개별 기사",
                            "advantages", Map.of(
                                    "simplicity", "단순한 구조",
                                    "speed", "단일 페이지 처리 속도",
                                    "resource_usage", "메모리 사용량 적음"
                            ),
                            "suitable_for", "단순 구조 사이트, 빠른 샘플링"
                    ),
                    "bfs_deep_crawling", Map.of(
                            "description", "BFS Deep Crawling 방식",
                            "method", "계층적 탐색 (넓이 우선)",
                            "advantages", Map.of(
                                    "breadth_first", "넓이 우선으로 더 많은 기사 발견",
                                    "streaming", "실시간 진행 상황 확인",
                                    "depth_control", "최대 깊이 제어 가능",
                                    "scalability", "대용량 사이트에 적합"
                            ),
                            "suitable_for", "네이버 뉴스같은 계층 구조 사이트, 포괄적 수집"
                    ),
                    "recommendation", Map.of(
                            "for_naver_news", "BFS Deep Crawling 권장",
                            "for_quick_test", "기본 크롤링 권장",
                            "for_comprehensive", "BFS Deep Crawling 권장"
                    )
            );

            return ResponseEntity.ok(comparison);
        } catch (Exception e) {
            log.error("크롤링 방법 비교 정보 조회 중 오류", e);
            return ResponseEntity.internalServerError().build();
        }
    }
}