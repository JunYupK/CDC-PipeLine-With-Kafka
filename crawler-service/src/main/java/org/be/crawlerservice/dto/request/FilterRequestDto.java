package org.be.crawlerservice.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.be.crawlerservice.enums.CrawlerStatus;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FilterRequestDto {

    /**
     * 카테고리 필터
     */
    private String category;

    /**
     * 검색 키워드
     */
    private String keyword;

    /**
     * 시작 날짜
     */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate startDate;

    /**
     * 종료 날짜
     */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate endDate;

    /**
     * 정렬 기준 (title, createdAt, publishedAt)
     */
    @Builder.Default
    private String sortBy = "createdAt";

    /**
     * 정렬 방향 (asc, desc)
     */
    @Builder.Default
    private String sortDirection = "desc";

    /**
     * 포함할 필드들
     */
    private List<String> includeFields;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CrawlStatusDto {

        /**
         * 현재 크롤링 상태
         */
        private CrawlerStatus status;

        /**
         * 현재 처리 중인 카테고리
         */
        private String currentCategory;

        /**
         * 크롤링 시작 시간
         */
        private LocalDateTime startTime;

        /**
         * 진행률 (0-100)
         */
        private Double progressPercentage;

        /**
         * 처리된 기사 수
         */
        private Integer processedArticles;

        /**
         * 전체 예상 기사 수
         */
        private Integer totalEstimatedArticles;

        /**
         * 메시지
         */
        private String message;

        /**
         * 카테고리별 에러 횟수
         */
        private Map<String, Integer> errorCounts;

        /**
         * 카테고리별 마지막 실행 시간
         */
        private Map<String, LocalDateTime> lastExecutionTimes;

        // 편의 메서드들
        public static CrawlStatusDto running(String category) {
            return CrawlStatusDto.builder()
                    .status(CrawlerStatus.RUNNING)
                    .currentCategory(category)
                    .startTime(LocalDateTime.now())
                    .message("크롤링이 진행 중입니다")
                    .build();
        }

        public static CrawlStatusDto completed(int processedArticles) {
            return CrawlStatusDto.builder()
                    .status(CrawlerStatus.COMPLETED)
                    .processedArticles(processedArticles)
                    .progressPercentage(100.0)
                    .message("크롤링이 완료되었습니다")
                    .build();
        }

        public static CrawlStatusDto failed(String errorMessage) {
            return CrawlStatusDto.builder()
                    .status(CrawlerStatus.FAILED)
                    .message(errorMessage)
                    .build();
        }

        public static CrawlStatusDto idle() {
            return CrawlStatusDto.builder()
                    .status(CrawlerStatus.IDLE)
                    .message("크롤링 대기 중")
                    .build();
        }
    }
}