package org.be.crawlerservice.controller;

import lombok.RequiredArgsConstructor;
import org.be.crawlerservice.dto.response.MetricsResponseDto;
import org.be.crawlerservice.metrics.CrawlerMetrics;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/metrics")
@RequiredArgsConstructor
public class MetricsController {

    private final CrawlerMetrics crawlerMetrics;

    /**
     * 전체 메트릭 조회
     * GET /api/v1/metrics
     */
    @GetMapping
    public ResponseEntity<MetricsResponseDto> getAllMetrics() {
        MetricsResponseDto metrics = crawlerMetrics.getAllMetrics();
        return ResponseEntity.ok(metrics);
    }

    /**
     * 크롤링 성능 메트릭
     * GET /api/v1/metrics/performance
     */
    @GetMapping("/performance")
    public ResponseEntity<Map<String, Object>> getPerformanceMetrics() {
        Map<String, Object> metrics = crawlerMetrics.getPerformanceMetricsMap();
        return ResponseEntity.ok(metrics);
    }

    /**
     * 시스템 리소스 메트릭
     * GET /api/v1/metrics/system
     */
    @GetMapping("/system")
    public ResponseEntity<Map<String, Object>> getSystemMetrics() {
        Map<String, Object> metrics = crawlerMetrics.getSystemMetricsMap();
        return ResponseEntity.ok(metrics);
    }
}