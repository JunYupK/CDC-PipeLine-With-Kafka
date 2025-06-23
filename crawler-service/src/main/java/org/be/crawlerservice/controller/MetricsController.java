package org.be.crawlerservice.controller;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.be.crawlerservice.metrics.CrawlerMetrics;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/v1/metrics")
@RequiredArgsConstructor
public class MetricsController {

    private final CrawlerMetrics crawlerMetrics;
    private final MeterRegistry meterRegistry;

    /**
     * 전체 메트릭 현황
     */
    @GetMapping("/dashboard")
    public ResponseEntity<Map<String, Object>> getDashboardMetrics() {
        Map<String, Object> dashboard = new HashMap<>();

        dashboard.put("crawling", getCrawlingMetrics());
        dashboard.put("system", getSystemMetrics());
        dashboard.put("daily", getDailyMetrics());
        dashboard.put("performance", getPerformanceMetrics());

        return ResponseEntity.ok(dashboard);
    }

    /**
     * 크롤링 상태 메트릭
     */
    @GetMapping("/crawling")
    public ResponseEntity<Map<String, Object>> getCrawlingMetrics() {
        Map<String, Object> metrics = new HashMap<>();

        // Deep Crawling 상태
        Double deepCrawlStatus = meterRegistry.get("crawler.deep_crawl.status").gauge().value();
        Double currentCycle = meterRegistry.get("crawler.deep_crawl.current_cycle").gauge().value();

        metrics.put("deep_crawl_status", deepCrawlStatus == 1.0 ? "RUNNING" : "IDLE");
        metrics.put("current_cycle", currentCycle.intValue());

        return ResponseEntity.ok(metrics);
    }

    /**
     * 시스템 리소스 메트릭
     */
    @GetMapping("/system")
    public ResponseEntity<Map<String, Object>> getSystemMetrics() {
        Map<String, Object> metrics = new HashMap<>();

        try {
            Double memoryBytes = meterRegistry.get("crawler.system.memory_usage_bytes").gauge().value();
            Double cpuPercent = meterRegistry.get("crawler.system.cpu_usage_percent").gauge().value();
            Double threadCount = meterRegistry.get("crawler.system.thread_count").gauge().value();

            metrics.put("memory_usage_mb", Math.round(memoryBytes / 1024 / 1024));
            metrics.put("cpu_usage_percent", Math.round(cpuPercent * 100) / 100.0);
            metrics.put("thread_count", threadCount.intValue());
        } catch (Exception e) {
            metrics.put("error", "시스템 메트릭 수집 실패: " + e.getMessage());
        }

        return ResponseEntity.ok(metrics);
    }

    /**
     * 오늘 일별 메트릭
     */
    @GetMapping("/daily")
    public ResponseEntity<Map<String, Object>> getDailyMetrics() {
        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        Map<String, Object> metrics = new HashMap<>();

        Map<String, Integer> savedByCategory = new HashMap<>();
        Map<String, Integer> duplicateByCategory = new HashMap<>();
        Map<String, Integer> nullContentByCategory = new HashMap<>();

        // 카테고리별 수집
        String[] categories = {"정치", "경제", "사회", "생활문화", "세계", "IT과학", "야구", "축구", "농구", "배구", "골프"};

        for (String category : categories) {
            try {
                Counter savedCounter = meterRegistry.get("crawler.articles.saved_daily")
                        .tag("date", today)
                        .tag("category", category)
                        .counter();
                savedByCategory.put(category, (int) savedCounter.count());

                Counter duplicateCounter = meterRegistry.get("crawler.articles.duplicate_daily")
                        .tag("date", today)
                        .tag("category", category)
                        .counter();
                duplicateByCategory.put(category, (int) duplicateCounter.count());

                Counter nullCounter = meterRegistry.get("crawler.articles.null_content_daily")
                        .tag("date", today)
                        .tag("category", category)
                        .counter();
                nullContentByCategory.put(category, (int) nullCounter.count());

            } catch (Exception e) {
                // 해당 카테고리 메트릭이 아직 생성되지 않음
                savedByCategory.put(category, 0);
                duplicateByCategory.put(category, 0);
                nullContentByCategory.put(category, 0);
            }
        }

        metrics.put("date", today);
        metrics.put("articles_saved", savedByCategory);
        metrics.put("articles_duplicate", duplicateByCategory);
        metrics.put("articles_null_content", nullContentByCategory);

        // 총합 계산
        int totalSaved = savedByCategory.values().stream().mapToInt(Integer::intValue).sum();
        int totalDuplicate = duplicateByCategory.values().stream().mapToInt(Integer::intValue).sum();
        int totalNullContent = nullContentByCategory.values().stream().mapToInt(Integer::intValue).sum();

        metrics.put("totals", Map.of(
                "saved", totalSaved,
                "duplicate", totalDuplicate,
                "null_content", totalNullContent,
                "total_processed", totalSaved + totalDuplicate + totalNullContent
        ));

        return ResponseEntity.ok(metrics);
    }

    /**
     * 사이클 성능 메트릭
     */
    @GetMapping("/performance")
    public ResponseEntity<Map<String, Object>> getPerformanceMetrics() {
        Map<String, Object> metrics = new HashMap<>();
        Map<String, Object> cycleTimes = new HashMap<>();

        // 최근 사이클 타이머들 조회
        meterRegistry.getMeters().stream()
                .filter(meter -> meter.getId().getName().equals("crawler.cycle.duration_ms"))
                .forEach(meter -> {
                    if (meter instanceof Timer) {
                        Timer timer = (Timer) meter;
                        String cycle = timer.getId().getTag("cycle");
                        String category = timer.getId().getTag("category");
                        String key = String.format("cycle_%s_%s", cycle, category);
                        cycleTimes.put(key, Math.round(timer.totalTime(TimeUnit.MILLISECONDS)));
                    }
                });

        metrics.put("cycle_durations_ms", cycleTimes);
        return ResponseEntity.ok(metrics);
    }

    /**
     * 메트릭 요약 (간단한 대시보드용)
     */
    @GetMapping("/summary")
    public ResponseEntity<Map<String, Object>> getMetricsSummary() {
        Map<String, Object> summary = new HashMap<>();

        try {
            // 상태
            Double deepCrawlStatus = meterRegistry.get("crawler.deep_crawl.status").gauge().value();
            summary.put("status", deepCrawlStatus == 1.0 ? "RUNNING" : "IDLE");

            // 오늘 총 처리량
            String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            int totalSaved = 0;
            int totalDuplicate = 0;

            try {
                totalSaved = (int) meterRegistry.get("crawler.articles.saved_daily")
                        .tag("date", today)
                        .counters().stream()
                        .mapToDouble(Counter::count)
                        .sum();

                totalDuplicate = (int) meterRegistry.get("crawler.articles.duplicate_daily")
                        .tag("date", today)
                        .counters().stream()
                        .mapToDouble(Counter::count)
                        .sum();
            } catch (Exception e) {
                // 메트릭이 아직 없음
            }

            summary.put("today_saved", totalSaved);
            summary.put("today_duplicate", totalDuplicate);

            // 시스템 리소스
            Double memoryMB = meterRegistry.get("crawler.system.memory_usage_bytes").gauge().value() / 1024 / 1024;
            Double cpuPercent = meterRegistry.get("crawler.system.cpu_usage_percent").gauge().value();

            summary.put("memory_mb", Math.round(memoryMB));
            summary.put("cpu_percent", Math.round(cpuPercent * 100) / 100.0);

        } catch (Exception e) {
            summary.put("error", "메트릭 수집 실패: " + e.getMessage());
        }

        return ResponseEntity.ok(summary);
    }

    /**
     * 메트릭 로그 출력 (디버깅용)
     */
    @PostMapping("/log-summary")
    public ResponseEntity<String> logMetricsSummary() {
        crawlerMetrics.logCurrentMetricsSummary();
        return ResponseEntity.ok("메트릭 요약이 로그에 출력되었습니다.");
    }
}