package org.be.crawlerservice.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StatsResponseDto {

    /**
     * 전체 통계
     */
    private OverallStats overall;

    /**
     * 카테고리별 통계
     */
    private Map<String, CategoryStats> byCategory;

    /**
     * 최근 일별 통계
     */
    private Map<String, DailyStats> recentDaily;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OverallStats {
        private Long totalArticles;
        private Integer totalCategories;
        private Integer totalDates;
        private LocalDateTime lastCrawlTime;
        private Double averageSuccessRate;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CategoryStats {
        private String category;
        private Long articleCount;
        private String earliestDate;
        private String latestDate;
        private Double successRate;
        private Integer errorCount;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DailyStats {
        private String date;
        private Long articleCount;
        private Double averageCrawlTime;
        private Integer successCount;
        private Integer failureCount;
    }
}