package org.be.crawlerservice.dto.crawl4ai;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Deep Crawling 스트리밍 결과를 처리하기 위한 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StreamingCrawlResult {

    /**
     * 작업 ID
     */
    private String taskId;

    /**
     * 현재 처리 중인 URL
     */
    private String currentUrl;

    /**
     * 현재 크롤링 깊이
     */
    private Integer currentDepth;

    /**
     * 전체 진행률 (0-100)
     */
    private Double progressPercentage;

    /**
     * 처리된 페이지 수
     */
    private Integer processedPages;

    /**
     * 전체 예상 페이지 수
     */
    private Integer totalEstimatedPages;

    /**
     * 발견된 새로운 URL들
     */
    private List<String> discoveredUrls;

    /**
     * 현재 페이지의 크롤링 결과
     */
    private Crawl4AIResult.CrawlResult pageResult;

    /**
     * Deep Crawling 메타데이터
     */
    private DeepCrawlMetadata metadata;

    /**
     * 스트림 상태 ("processing", "completed", "failed")
     */
    private String streamStatus;

    /**
     * 타임스탬프
     */
    private LocalDateTime timestamp;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DeepCrawlMetadata {
        /**
         * 페이지 스코어
         */
        private Double score;

        /**
         * URL 카테고리 (예: "news", "article", "list")
         */
        private String urlCategory;

        /**
         * 부모 URL
         */
        private String parentUrl;

        /**
         * 크롤링 시간 (밀리초)
         */
        private Long crawlTime;

        /**
         * 추출된 링크 수
         */
        private Integer extractedLinkCount;

        /**
         * 콘텐츠 품질 지표
         */
        private Map<String, Object> qualityMetrics;
    }

    // === 편의 메서드들 ===

    /**
     * 스트림이 완료되었는지 확인
     */
    public boolean isStreamCompleted() {
        return "completed".equals(streamStatus);
    }

    /**
     * 스트림이 실패했는지 확인
     */
    public boolean isStreamFailed() {
        return "failed".equals(streamStatus);
    }

    /**
     * 현재 페이지가 성공적으로 크롤링되었는지 확인
     */
    public boolean isPageSuccessful() {
        return pageResult != null && Boolean.TRUE.equals(pageResult.getSuccess());
    }

    /**
     * 현재 페이지에 콘텐츠가 있는지 확인
     */
    public boolean hasPageContent() {
        return pageResult != null &&
                pageResult.getMarkdown() != null &&
                !pageResult.getMarkdown().trim().isEmpty();
    }

    /**
     * 진행 중인 스트림 결과 생성
     */
    public static StreamingCrawlResult processing(String taskId, String currentUrl,
                                                  int currentDepth, int processedPages) {
        return StreamingCrawlResult.builder()
                .taskId(taskId)
                .currentUrl(currentUrl)
                .currentDepth(currentDepth)
                .processedPages(processedPages)
                .streamStatus("processing")
                .timestamp(LocalDateTime.now())
                .build();
    }

    /**
     * 완료된 스트림 결과 생성
     */
    public static StreamingCrawlResult completed(String taskId, int totalProcessedPages) {
        return StreamingCrawlResult.builder()
                .taskId(taskId)
                .processedPages(totalProcessedPages)
                .progressPercentage(100.0)
                .streamStatus("completed")
                .timestamp(LocalDateTime.now())
                .build();
    }

    /**
     * 실패한 스트림 결과 생성
     */
    public static StreamingCrawlResult failed(String taskId, String currentUrl, String reason) {
        return StreamingCrawlResult.builder()
                .taskId(taskId)
                .currentUrl(currentUrl)
                .streamStatus("failed")
                .timestamp(LocalDateTime.now())
                .build();
    }
}