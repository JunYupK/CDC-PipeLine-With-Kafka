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
    private Counter articlesProcessedCounter;
    private Counter duplicateCounter;
    private Counter nullContentCounter;
    private Counter crawlSuccessCounter;
    private Counter crawlFailureCounter;
    private final ConcurrentHashMap<Integer, Timer> crawlTimers = new ConcurrentHashMap<>();
    private final AtomicLong deepCrawlStatus = new AtomicLong(0);
    // Deep Crawling 전용 메트릭 추가
    private final ConcurrentHashMap<String, Counter> categoryArticlesCounter = new ConcurrentHashMap<>();
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
        log.info("Initializing crawler metrics...");

        // Counter 초기화
        articlesProcessedCounter = Counter.builder("crawler.articles.processed")
                .description("Total articles processed")
                .register(meterRegistry);

        duplicateCounter = Counter.builder("crawler.articles.duplicate")
                .description("Total duplicate articles")
                .register(meterRegistry);

        nullContentCounter = Counter.builder("crawler.articles.null_content")
                .description("Total null content articles")
                .register(meterRegistry);

        crawlSuccessCounter = Counter.builder("crawler.crawl.success")
                .description("Total successful crawls")
                .register(meterRegistry);

        crawlFailureCounter = Counter.builder("crawler.crawl.failure")
                .description("Total failed crawls")
                .register(meterRegistry);

        // Deep Crawling 상태 Gauge
        Gauge.builder("crawler.deep_crawl.status", deepCrawlStatus, AtomicLong::get)
                .description("Deep crawling status (0:idle, 1:running)")
                .register(meterRegistry);

        initializeSystemMetrics();
        log.info("Crawler metrics initialized successfully");

        // Deep Crawling 사이클 Gauge 추가
        Gauge.builder("crawler.deep_crawl.current_cycle", currentCycle, AtomicLong::get)
                .description("Current deep crawling cycle number")
                .register(meterRegistry);
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

    public void incrementArticlesProcessed(int count) {
        articlesProcessedCounter.increment(count);
    }

    public void incrementDuplicateCount() {
        duplicateCounter.increment();
    }

    public void incrementNullContentCount() {
        nullContentCounter.increment();
    }

    public void incrementCrawlSuccess() {
        crawlSuccessCounter.increment();
    }

    public void incrementCrawlFailure() {
        crawlFailureCounter.increment();
    }

    public void recordCrawlTime(int cycleCount, long timeInMillis) {
        Timer timer = crawlTimers.computeIfAbsent(cycleCount,
                cycle -> Timer.builder("crawler.crawl.time")
                        .tag("cycle", String.valueOf(cycle))
                        .register(meterRegistry));
        timer.record(timeInMillis, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    public void setDeepCrawlStatus(boolean running) {
        deepCrawlStatus.set(running ? 1 : 0);
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
    public void updateCurrentCycle(int cycle) {
        currentCycle.set(cycle);
    }
}