package org.be.crawlerservice.metrics;

import io.micrometer.core.instrument.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.OperatingSystemMXBean;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
public class CrawlerMetrics {

    private final MeterRegistry meterRegistry;
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    // 크롤링 메트릭 (일별 태그 포함)
    private final ConcurrentHashMap<String, Counter> dailyArticlesSavedCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> dailyDuplicateCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> dailyNullContentCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> dailyCrawlSuccessCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> dailyCrawlFailureCounters = new ConcurrentHashMap<>();

    // 사이클별 타이머
    private final ConcurrentHashMap<String, Timer> cycleCrawlTimers = new ConcurrentHashMap<>();

    // 상태 메트릭
    private final AtomicLong deepCrawlStatus = new AtomicLong(0);
    private final AtomicLong currentCycle = new AtomicLong(0);

    // 시스템 메트릭
    private Gauge memoryUsageGauge;
    private Gauge cpuUsageGauge;
    private Gauge threadCountGauge;

    public CrawlerMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    public void initializeMetrics() {
        log.info("Initializing enhanced crawler metrics...");

        // Deep Crawling 상태 Gauge
        Gauge.builder("crawler.deep_crawl.status", deepCrawlStatus, AtomicLong::get)
                .description("Deep crawling status (0:idle, 1:running)")
                .register(meterRegistry);

        // 현재 사이클 Gauge
        Gauge.builder("crawler.deep_crawl.current_cycle", currentCycle, AtomicLong::get)
                .description("Current deep crawling cycle number")
                .register(meterRegistry);

        initializeSystemMetrics();
        log.info("Enhanced crawler metrics initialized successfully");
    }

    private void initializeSystemMetrics() {
        memoryUsageGauge = Gauge.builder("crawler.system.memory_usage_bytes", this, CrawlerMetrics::getCurrentMemoryUsage)
                .description("JVM heap memory usage in bytes")
                .register(meterRegistry);

        cpuUsageGauge = Gauge.builder("crawler.system.cpu_usage_percent", this, CrawlerMetrics::getCurrentCpuUsage)
                .description("Process CPU usage percentage")
                .register(meterRegistry);

        threadCountGauge = Gauge.builder("crawler.system.thread_count", this, CrawlerMetrics::getCurrentThreadCount)
                .description("Current active thread count")
                .register(meterRegistry);
    }

    // === 1. 크롤링 사이클 소요 시간 ===
    public void recordCycleCrawlTime(int cycleNumber, String category, long timeInMillis) {
        String today = LocalDate.now().format(DATE_FORMATTER);
        String key = String.format("%s_%d_%s", today, cycleNumber, category);

        Timer timer = cycleCrawlTimers.computeIfAbsent(key,
                k -> Timer.builder("crawler.cycle.duration_ms")
                        .tag("date", today)
                        .tag("cycle", String.valueOf(cycleNumber))
                        .tag("category", category)
                        .description("Time taken for one crawling cycle")
                        .register(meterRegistry));

        timer.record(timeInMillis, java.util.concurrent.TimeUnit.MILLISECONDS);
        log.debug("Recorded cycle time: {}ms for cycle {} category {}", timeInMillis, cycleNumber, category);
    }

    // === 2. 일별 기사 수집량 (실제 DB 저장 성공) ===
    public void incrementDailyArticlesSaved(String category) {
        String today = LocalDate.now().format(DATE_FORMATTER);
        String key = today + "_" + category;

        Counter counter = dailyArticlesSavedCounters.computeIfAbsent(key,
                k -> Counter.builder("crawler.articles.saved_daily")
                        .tag("date", today)
                        .tag("category", category)
                        .description("Daily count of successfully saved articles")
                        .register(meterRegistry));

        counter.increment();
        log.trace("Incremented daily articles saved: {} - {}", category, today);
    }

    // === 3. 일별 기사 중복 수집량 ===
    public void incrementDailyDuplicateCount(String category) {
        String today = LocalDate.now().format(DATE_FORMATTER);
        String key = today + "_" + category;

        Counter counter = dailyDuplicateCounters.computeIfAbsent(key,
                k -> Counter.builder("crawler.articles.duplicate_daily")
                        .tag("date", today)
                        .tag("category", category)
                        .description("Daily count of duplicate articles encountered")
                        .register(meterRegistry));

        counter.increment();
        log.trace("Incremented daily duplicate count: {} - {}", category, today);
    }

    // === 4. 일별 기사 내용 누락 수 ===
    public void incrementDailyNullContentCount(String category) {
        String today = LocalDate.now().format(DATE_FORMATTER);
        String key = today + "_" + category;

        Counter counter = dailyNullContentCounters.computeIfAbsent(key,
                k -> Counter.builder("crawler.articles.null_content_daily")
                        .tag("date", today)
                        .tag("category", category)
                        .description("Daily count of articles with null/empty content")
                        .register(meterRegistry));

        counter.increment();
        log.trace("Incremented daily null content count: {} - {}", category, today);
    }

    // === 성공/실패 카운터도 일별로 개선 ===
    public void incrementDailyCrawlSuccess(String category) {
        String today = LocalDate.now().format(DATE_FORMATTER);
        String key = today + "_" + category;

        Counter counter = dailyCrawlSuccessCounters.computeIfAbsent(key,
                k -> Counter.builder("crawler.crawl.success_daily")
                        .tag("date", today)
                        .tag("category", category)
                        .description("Daily successful crawl operations")
                        .register(meterRegistry));

        counter.increment();
    }

    public void incrementDailyCrawlFailure(String category, String errorType) {
        String today = LocalDate.now().format(DATE_FORMATTER);
        String key = today + "_" + category + "_" + errorType;

        Counter counter = dailyCrawlFailureCounters.computeIfAbsent(key,
                k -> Counter.builder("crawler.crawl.failure_daily")
                        .tag("date", today)
                        .tag("category", category)
                        .tag("error_type", errorType)
                        .description("Daily failed crawl operations")
                        .register(meterRegistry));

        counter.increment();
    }

    // === Deep Crawling 상태 관리 ===
    public void setDeepCrawlStatus(boolean running) {
        deepCrawlStatus.set(running ? 1 : 0);
        log.debug("Deep crawl status updated: {}", running ? "RUNNING" : "IDLE");
    }

    public void updateCurrentCycle(int cycle) {
        currentCycle.set(cycle);
        log.debug("Current cycle updated: {}", cycle);
    }

    // === 시스템 메트릭 (기존과 동일하지만 로깅 개선) ===
    private long getCurrentMemoryUsage() {
        try {
            MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
            return memoryBean.getHeapMemoryUsage().getUsed();
        } catch (Exception e) {
            log.warn("Failed to get memory usage", e);
            return 0L;
        }
    }

    private double getCurrentCpuUsage() {
        try {
            OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
            if (osBean instanceof com.sun.management.OperatingSystemMXBean) {
                double cpuLoad = ((com.sun.management.OperatingSystemMXBean) osBean).getProcessCpuLoad();
                return cpuLoad >= 0 ? cpuLoad * 100 : 0.0;
            }
            return 0.0;
        } catch (Exception e) {
            log.warn("Failed to get CPU usage", e);
            return 0.0;
        }
    }

    private int getCurrentThreadCount() {
        return Thread.activeCount();
    }

    // === 편의 메서드 ===
    /**
     * 여러 카테고리 동시 처리용
     */
    public void recordBulkArticleProcessing(String category, int savedCount, int duplicateCount, int nullContentCount) {
        // 저장 성공
        for (int i = 0; i < savedCount; i++) {
            incrementDailyArticlesSaved(category);
        }

        // 중복
        for (int i = 0; i < duplicateCount; i++) {
            incrementDailyDuplicateCount(category);
        }

        // 내용 누락
        for (int i = 0; i < nullContentCount; i++) {
            incrementDailyNullContentCount(category);
        }

        log.info("Recorded bulk processing for {}: saved={}, duplicate={}, nullContent={}",
                category, savedCount, duplicateCount, nullContentCount);
    }

    /**
     * 메트릭 요약 정보 로깅 (디버깅용)
     */
    public void logCurrentMetricsSummary() {
        String today = LocalDate.now().format(DATE_FORMATTER);
        log.info("=== 오늘({}) 크롤링 메트릭 요약 ===", today);
        log.info("Deep Crawl Status: {}, Current Cycle: {}",
                deepCrawlStatus.get() == 1 ? "RUNNING" : "IDLE", currentCycle.get());
        log.info("Memory Usage: {}MB, CPU Usage: {}%, Thread Count: {}",
                getCurrentMemoryUsage() / 1024 / 1024, getCurrentCpuUsage(), getCurrentThreadCount());
    }

    // === 레거시 호환성 메서드들 (기존 코드와의 호환성 유지) ===
    @Deprecated
    public void incrementArticlesProcessed(int count) {
        // 카테고리 정보 없이 호출된 경우 "unknown"으로 처리
        for (int i = 0; i < count; i++) {
            incrementDailyArticlesSaved("unknown");
        }
    }

    @Deprecated
    public void incrementDuplicateCount() {
        incrementDailyDuplicateCount("unknown");
    }

    @Deprecated
    public void incrementNullContentCount() {
        incrementDailyNullContentCount("unknown");
    }

    @Deprecated
    public void incrementCrawlSuccess() {
        incrementDailyCrawlSuccess("unknown");
    }

    @Deprecated
    public void incrementCrawlFailure() {
        incrementDailyCrawlFailure("unknown", "general");
    }

    @Deprecated
    public void recordCrawlTime(int cycleCount, long timeInMillis) {
        recordCycleCrawlTime(cycleCount, "unknown", timeInMillis);
    }
}