package org.be.crawlerservice.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MetricsResponseDto {

    /**
     * 크롤링 메트릭
     */
    private CrawlingMetrics crawling;

    /**
     * 시스템 메트릭
     */
    private SystemMetrics system;

    /**
     * 성능 메트릭
     */
    private PerformanceMetrics performance;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CrawlingMetrics {
        private Map<String, Long> articlesProcessed;
        private Map<String, Long> crawlSuccess;
        private Map<String, Long> crawlFailure;
        private Map<String, Double> crawlTime;
        private Map<String, Double> successRate;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SystemMetrics {
        private Long memoryUsage;
        private Double cpuUsage;
        private Integer openFiles;
        private Long uptime;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PerformanceMetrics {
        private Double averageResponseTime;
        private Long totalRequests;
        private Map<String, Double> endpointResponseTimes;
        private Map<String, Integer> errorRates;
    }
}
