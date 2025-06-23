package org.be.crawlerservice.metrics;

import io.micrometer.core.instrument.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.OperatingSystemMXBean;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
public class CrawlerMetrics {

    private final MeterRegistry meterRegistry;

    // 크롤링 메트릭
    private final ConcurrentHashMap<String, Counter> articlesProcessedCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> duplicateCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> nullContentCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Timer> crawlTimers = new ConcurrentHashMap<>();

    // 시스템 메트릭
    private Gauge memoryUsageGauge;
    private Gauge cpuUsageGauge;
    private Gauge threadCountGauge;

    public CrawlerMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    public void initializeMetrics() {
        log.info("Initializing crawler metrics...");
        initializeSystemMetrics();
        log.info("Crawler metrics initialized successfully");
    }

    private void initializeSystemMetrics() {
        memoryUsageGauge = Gauge.builder("crawler.memory.usage", this, CrawlerMetrics::getCurrentMemoryUsage)
                .description("Memory usage in bytes")
                .register(meterRegistry);

        cpuUsageGauge = Gauge.builder("crawler.cpu.usage", this, CrawlerMetrics::getCurrentCpuUsage)
                .description("CPU usage percentage")
                .register(meterRegistry);

        threadCountGauge = Gauge.builder("crawler.thread.count", this, CrawlerMetrics::getCurrentThreadCount)
                .description("Current thread count")
                .register(meterRegistry);
    }

    // === 메트릭 업데이트 메서드들 ===

    public void incrementArticlesProcessed(String category, int count) {
        Counter counter = articlesProcessedCounters.computeIfAbsent(category,
                cat -> Counter.builder("crawler.articles.processed")
                        .tag("category", cat)
                        .register(meterRegistry));
        counter.increment(count);
    }

    public void incrementDuplicateCount(String category) {
        Counter counter = duplicateCounters.computeIfAbsent(category,
                cat -> Counter.builder("crawler.articles.duplicate")
                        .tag("category", cat)
                        .register(meterRegistry));
        counter.increment();
    }

    public void incrementNullContentCount(String category) {
        Counter counter = nullContentCounters.computeIfAbsent(category,
                cat -> Counter.builder("crawler.articles.null_content")
                        .tag("category", cat)
                        .register(meterRegistry));
        counter.increment();
    }

    public void recordCrawlTime(String category, long timeInMillis) {
        Timer timer = crawlTimers.computeIfAbsent(category,
                cat -> Timer.builder("crawler.crawl.time")
                        .tag("category", cat)
                        .register(meterRegistry));
        timer.record(timeInMillis, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    // === 시스템 메트릭 헬퍼 메서드들 ===

    private long getCurrentMemoryUsage() {
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        return memoryBean.getHeapMemoryUsage().getUsed();
    }

    private double getCurrentCpuUsage() {
        OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
        if (osBean instanceof com.sun.management.OperatingSystemMXBean) {
            return ((com.sun.management.OperatingSystemMXBean) osBean).getProcessCpuLoad() * 100;
        }
        return 0.0;
    }

    private int getCurrentThreadCount() {
        return Thread.activeCount();
    }
}
