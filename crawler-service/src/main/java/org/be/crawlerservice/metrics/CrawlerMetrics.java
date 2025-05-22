// CrawlerMetrics.java - 메트릭 수집 및 관리 서비스
package org.be.crawlerservice.metrics;

import io.micrometer.core.instrument.*;
import lombok.extern.slf4j.Slf4j;
import org.be.crawlerservice.dto.response.MetricsResponseDto;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.OperatingSystemMXBean;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
public class CrawlerMetrics {

    private final MeterRegistry meterRegistry;

    // 크롤링 메트릭
    private final Map<String, Counter> articlesProcessedCounters = new ConcurrentHashMap<>();
    private final Map<String, Counter> crawlSuccessCounters = new ConcurrentHashMap<>();
    private final Map<String, Counter> crawlFailureCounters = new ConcurrentHashMap<>();
    private final Map<String, Timer> crawlTimers = new ConcurrentHashMap<>();
    private final Map<String, Gauge> crawlStatusGauges = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> crawlStatusValues = new ConcurrentHashMap<>();

    // 시스템 메트릭
    private Gauge memoryUsageGauge;
    private Gauge cpuUsageGauge;
    private Counter apiRequestsCounter;
    private Timer dbOperationTimer;

    // 성능 메트릭
    private final Map<String, Timer> endpointTimers = new ConcurrentHashMap<>();
    private final Map<String, Counter> errorCounters = new ConcurrentHashMap<>();

    // 카테고리 매핑 (기존 FastAPI 코드와 동일)
    private final Map<String, String> categoryMapping = Map.of(
            "정치", "pol",
            "경제", "eco",
            "사회", "soc",
            "생활문화", "cul",
            "세계", "wld",
            "IT과학", "its",
            "스포츠", "sports"
    );

    public CrawlerMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry; // Spring이 자동으로 Prometheus MeterRegistry 주입
    }

    @PostConstruct
    public void initializeMetrics() {
        log.info("Initializing crawler metrics...");

        // 시스템 메트릭 초기화
        initializeSystemMetrics();

        // 카테고리별 메트릭 초기화
        initializeCategoryMetrics();

        log.info("Crawler metrics initialized successfully");
    }

    /**
     * 시스템 메트릭 초기화
     */
    private void initializeSystemMetrics() {
        // 메모리 사용량 게이지 - 올바른 API 사용
        memoryUsageGauge = Gauge.builder("crawler.memory.usage", this, CrawlerMetrics::getCurrentMemoryUsage)
                .description("Memory usage in bytes")
                .register(meterRegistry);

        // CPU 사용률 게이지 - 올바른 API 사용
        cpuUsageGauge = Gauge.builder("crawler.cpu.usage", this, CrawlerMetrics::getCurrentCpuUsage)
                .description("CPU usage percentage")
                .register(meterRegistry);

        // API 요청 카운터 - 올바른 API 사용
        apiRequestsCounter = Counter.builder("crawler.api.requests")
                .description("Total API requests")
                .register(meterRegistry);

        // DB 작업 타이머 - 올바른 API 사용
        dbOperationTimer = Timer.builder("crawler.db.operation.time")
                .description("Database operation time")
                .register(meterRegistry);
    }

    /**
     * 카테고리별 메트릭 초기화
     */
    private void initializeCategoryMetrics() {
        categoryMapping.forEach((fullName, shortId) -> {
            // 처리된 기사 수 카운터 - 올바른 API 사용
            Counter articlesCounter = Counter.builder("crawler.articles.processed")
                    .description("Number of articles processed per category")
                    .tag("category", shortId)
                    .register(meterRegistry);
            articlesProcessedCounters.put(shortId, articlesCounter);

            // 성공 카운터 - 올바른 API 사용
            Counter successCounter = Counter.builder("crawler.crawl.success")
                    .description("Number of successful crawls per category")
                    .tag("category", shortId)
                    .register(meterRegistry);
            crawlSuccessCounters.put(shortId, successCounter);

            // 실패 카운터 - 올바른 API 사용
            Counter failureCounter = Counter.builder("crawler.crawl.failure")
                    .description("Number of failed crawls per category")
                    .tag("category", shortId)
                    .register(meterRegistry);
            crawlFailureCounters.put(shortId, failureCounter);

            // 크롤링 시간 타이머 - 올바른 API 사용
            Timer crawlTimer = Timer.builder("crawler.crawl.time")
                    .description("Time taken to crawl each category")
                    .tag("category", shortId)
                    .register(meterRegistry);
            crawlTimers.put(shortId, crawlTimer);

            // 크롤링 상태 게이지 (0: 대기, 1: 실행 중) - 올바른 API 사용
            AtomicLong statusValue = new AtomicLong(0);
            crawlStatusValues.put(shortId, statusValue);

            Gauge statusGauge = Gauge.builder("crawler.crawl.status", statusValue, AtomicLong::doubleValue)
                    .description("Crawler status for different categories")
                    .tag("category", shortId)
                    .register(meterRegistry);
            crawlStatusGauges.put(shortId, statusGauge);
        });
    }

    // === 크롤링 메트릭 업데이트 메서드들 ===

    /**
     * 처리된 기사 수 증가
     */
    public void incrementArticlesProcessed(String category, int count) {
        String categoryId = categoryMapping.getOrDefault(category, category);
        Counter counter = articlesProcessedCounters.get(categoryId);
        if (counter != null) {
            counter.increment(count);
        }
    }

    /**
     * 크롤링 성공 횟수 증가
     */
    public void incrementCrawlSuccess(String category) {
        String categoryId = categoryMapping.getOrDefault(category, category);
        Counter counter = crawlSuccessCounters.get(categoryId);
        if (counter != null) {
            counter.increment();
        }
    }

    /**
     * 크롤링 실패 횟수 증가
     */
    public void incrementCrawlFailure(String category) {
        String categoryId = categoryMapping.getOrDefault(category, category);
        Counter counter = crawlFailureCounters.get(categoryId);
        if (counter != null) {
            counter.increment();
        }
    }

    /**
     * 크롤링 시간 기록
     */
    public void recordCrawlTime(String category, long timeInMillis) {
        String categoryId = categoryMapping.getOrDefault(category, category);
        Timer timer = crawlTimers.get(categoryId);
        if (timer != null) {
            timer.record(timeInMillis, java.util.concurrent.TimeUnit.MILLISECONDS);
        }
    }

    /**
     * 크롤링 상태 업데이트 (0: 대기, 1: 실행 중)
     */
    public void updateCrawlStatus(String category, boolean isRunning) {
        String categoryId = categoryMapping.getOrDefault(category, category);
        AtomicLong statusValue = crawlStatusValues.get(categoryId);
        if (statusValue != null) {
            statusValue.set(isRunning ? 1 : 0);
        }
    }

    /**
     * API 요청 수 증가
     */
    public void incrementApiRequests() {
        apiRequestsCounter.increment();
    }

    /**
     * DB 작업 시간 기록
     */
    public void recordDbOperationTime(long timeInMillis) {
        dbOperationTimer.record(timeInMillis, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    /**
     * 엔드포인트별 응답 시간 기록
     */
    public void recordEndpointTime(String endpoint, long timeInMillis) {
        Timer timer = endpointTimers.computeIfAbsent(endpoint,
                ep -> Timer.builder("crawler.endpoint.response.time")
                        .description("Response time for each endpoint")
                        .tag("endpoint", ep)
                        .register(meterRegistry));

        timer.record(timeInMillis, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    /**
     * 에러 카운트 증가
     */
    public void incrementErrorCount(String errorType) {
        Counter counter = errorCounters.computeIfAbsent(errorType,
                et -> Counter.builder("crawler.errors")
                        .description("Error count by type")
                        .tag("type", et)
                        .register(meterRegistry));

        counter.increment();
    }

    // === 메트릭 조회 메서드들 ===

    /**
     * 전체 메트릭 반환
     */
    public MetricsResponseDto getAllMetrics() {
        return MetricsResponseDto.builder()
                .crawling(getCrawlingMetrics())
                .system(getSystemMetrics())
                .performance(getPerformanceMetrics())
                .build();
    }

    /**
     * 크롤링 메트릭 반환
     */
    public MetricsResponseDto.CrawlingMetrics getCrawlingMetrics() {
        Map<String, Long> articlesProcessed = new HashMap<>();
        Map<String, Long> crawlSuccess = new HashMap<>();
        Map<String, Long> crawlFailure = new HashMap<>();
        Map<String, Double> crawlTime = new HashMap<>();
        Map<String, Double> successRate = new HashMap<>();

        categoryMapping.forEach((fullName, shortId) -> {
            Counter articlesCounter = articlesProcessedCounters.get(shortId);
            Counter successCounter = crawlSuccessCounters.get(shortId);
            Counter failureCounter = crawlFailureCounters.get(shortId);
            Timer timer = crawlTimers.get(shortId);

            if (articlesCounter != null) {
                articlesProcessed.put(fullName, (long) articlesCounter.count());
            }
            if (successCounter != null) {
                crawlSuccess.put(fullName, (long) successCounter.count());
            }
            if (failureCounter != null) {
                crawlFailure.put(fullName, (long) failureCounter.count());
            }
            if (timer != null) {
                crawlTime.put(fullName, timer.mean(java.util.concurrent.TimeUnit.MILLISECONDS));
            }

            // 성공률 계산
            long success = successCounter != null ? (long) successCounter.count() : 0;
            long failure = failureCounter != null ? (long) failureCounter.count() : 0;
            long total = success + failure;
            double rate = total > 0 ? (double) success / total * 100 : 0.0;
            successRate.put(fullName, rate);
        });

        return MetricsResponseDto.CrawlingMetrics.builder()
                .articlesProcessed(articlesProcessed)
                .crawlSuccess(crawlSuccess)
                .crawlFailure(crawlFailure)
                .crawlTime(crawlTime)
                .successRate(successRate)
                .build();
    }

    /**
     * 시스템 메트릭 반환
     */
    public MetricsResponseDto.SystemMetrics getSystemMetrics() {
        return MetricsResponseDto.SystemMetrics.builder()
                .memoryUsage(getCurrentMemoryUsage())
                .cpuUsage(getCurrentCpuUsage())
                .openFiles(getCurrentOpenFiles())
                .uptime(getUptime())
                .build();
    }

    /**
     * 성능 메트릭 반환
     */
    public MetricsResponseDto.PerformanceMetrics getPerformanceMetrics() {
        Map<String, Double> endpointResponseTimes = new HashMap<>();
        Map<String, Integer> errorRates = new HashMap<>();

        endpointTimers.forEach((endpoint, timer) -> {
            endpointResponseTimes.put(endpoint, timer.mean(java.util.concurrent.TimeUnit.MILLISECONDS));
        });

        errorCounters.forEach((errorType, counter) -> {
            errorRates.put(errorType, (int) counter.count());
        });

        return MetricsResponseDto.PerformanceMetrics.builder()
                .averageResponseTime(calculateAverageResponseTime())
                .totalRequests((long) apiRequestsCounter.count())
                .endpointResponseTimes(endpointResponseTimes)
                .errorRates(errorRates)
                .build();
    }

    /**
     * 성능 메트릭 반환 (Map 형태)
     */
    public Map<String, Object> getPerformanceMetricsMap() {
        Map<String, Object> metrics = new HashMap<>();

        // 전체 평균 응답 시간
        metrics.put("averageResponseTime", calculateAverageResponseTime());

        // 총 요청 수
        metrics.put("totalRequests", (long) apiRequestsCounter.count());

        // 엔드포인트별 응답 시간
        Map<String, Double> endpointTimes = new HashMap<>();
        endpointTimers.forEach((endpoint, timer) -> {
            endpointTimes.put(endpoint, timer.mean(java.util.concurrent.TimeUnit.MILLISECONDS));
        });
        metrics.put("endpointResponseTimes", endpointTimes);

        // 에러율
        Map<String, Integer> errors = new HashMap<>();
        errorCounters.forEach((errorType, counter) -> {
            errors.put(errorType, (int) counter.count());
        });
        metrics.put("errorRates", errors);

        return metrics;
    }

    /**
     * 시스템 메트릭 반환 (Map 형태)
     */
    public Map<String, Object> getSystemMetricsMap() {
        Map<String, Object> metrics = new HashMap<>();

        metrics.put("memoryUsage", getCurrentMemoryUsage());
        metrics.put("cpuUsage", getCurrentCpuUsage());
        metrics.put("openFiles", getCurrentOpenFiles());
        metrics.put("uptime", getUptime());

        return metrics;
    }

    // === Private 헬퍼 메서드들 ===

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

    private int getCurrentOpenFiles() {
        // Java에서 열린 파일 수를 정확히 측정하기는 어려움
        // 대신 활성 스레드 수를 반환
        return Thread.activeCount();
    }

    private long getUptime() {
        return ManagementFactory.getRuntimeMXBean().getUptime();
    }

    private double calculateAverageResponseTime() {
        if (endpointTimers.isEmpty()) {
            return 0.0;
        }

        double totalTime = endpointTimers.values().stream()
                .mapToDouble(timer -> timer.mean(java.util.concurrent.TimeUnit.MILLISECONDS))
                .sum();

        return totalTime / endpointTimers.size();
    }
}