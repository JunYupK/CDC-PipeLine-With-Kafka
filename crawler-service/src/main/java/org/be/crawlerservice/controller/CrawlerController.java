package org.be.crawlerservice.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.be.crawlerservice.dto.request.CrawlRequestDto;
import org.be.crawlerservice.dto.response.CrawlStatusDto;
import org.be.crawlerservice.service.crawler.CrawlerService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;

@Slf4j
@RestController
@RequestMapping("/api/v1/crawl")
@RequiredArgsConstructor
public class CrawlerController {

    private final CrawlerService crawlerService;

    /**
     * 크롤링 작업 수동 트리거
     * POST /api/v1/crawl
     */
    @PostMapping
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
     * 특정 카테고리 크롤링 트리거 (간단한 방식)
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
}