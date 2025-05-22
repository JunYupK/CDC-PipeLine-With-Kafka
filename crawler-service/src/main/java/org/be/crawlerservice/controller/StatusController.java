package org.be.crawlerservice.controller;

import lombok.RequiredArgsConstructor;
import org.be.crawlerservice.dto.response.CrawlStatusDto;
import org.be.crawlerservice.dto.response.StatsResponseDto;
import org.be.crawlerservice.service.crawler.CrawlerService;
import org.be.crawlerservice.service.article.ArticleService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/status")
@RequiredArgsConstructor
public class StatusController {

    private final CrawlerService crawlerService;
    private final ArticleService articleService;

    /**
     * 현재 크롤링 상태 조회
     * GET /api/v1/status
     */
    @GetMapping
    public ResponseEntity<CrawlStatusDto> getCrawlingStatus() {
        CrawlStatusDto status = crawlerService.getCurrentStatus();
        return ResponseEntity.ok(status);
    }

    /**
     * 크롤링 통계 정보 조회
     * GET /api/v1/status/stats
     */
    @GetMapping("/stats")
    public ResponseEntity<StatsResponseDto> getCrawlingStats() {
        StatsResponseDto stats = crawlerService.getCrawlingStats();
        return ResponseEntity.ok(stats);
    }

    /**
     * 각 카테고리별 마지막 실행 시간
     * GET /api/v1/status/last-execution
     */
    @GetMapping("/last-execution")
    public ResponseEntity<Map<String, String>> getLastExecutionTimes() {
        Map<String, String> lastExecutions = crawlerService.getLastExecutionTimes();
        return ResponseEntity.ok(lastExecutions);
    }

    /**
     * 성공률 통계
     * GET /api/v1/status/success-rate
     */
    @GetMapping("/success-rate")
    public ResponseEntity<Map<String, Double>> getSuccessRates() {
        Map<String, Double> successRates = crawlerService.getSuccessRates();
        return ResponseEntity.ok(successRates);
    }
}