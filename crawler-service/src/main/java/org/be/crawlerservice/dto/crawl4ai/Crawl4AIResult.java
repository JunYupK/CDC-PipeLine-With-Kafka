package org.be.crawlerservice.dto.crawl4ai;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Crawl4AI API 응답 DTO
 * Python crawl4ai_helper.py의 결과 구조를 Java로 구현
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Crawl4AIResult {

    /**
     * 작업 ID
     */
    private String taskId;

    /**
     * 작업 상태 ("pending", "processing", "completed", "failed")
     */
    private String status;

    /**
     * 에러 메시지 (실패 시)
     */
    private String error;

    /**
     * 크롤링 결과 데이터
     */
    private CrawlResult result;

    /**
     * 요청 시간
     */
    private LocalDateTime requestTime;

    /**
     * 완료 시간
     */
    private LocalDateTime completedTime;

    private String extracted_content;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CrawlResult {
        /**
         * 원본 HTML
         */
        private String html;

        /**
         * 정제된 HTML
         */
        private String cleanedHtml;

        /**
         * 마크다운 형태의 텍스트
         */
        private String markdown;

        /**
         * 추출된 콘텐츠 (JSON 문자열)
         */
        private String extractedContent;

        /**
         * 성공 여부
         */
        private Boolean success;

        /**
         * HTTP 상태 코드
         */
        private Integer statusCode;

        /**
         * 응답 헤더
         */
        private Map<String, String> headers;

        /**
         * 미디어 파일 정보 (이미지, 비디오 등)
         */
        private List<MediaInfo> media;

        /**
         * 링크 정보
         */
        private LinkInfo links;

        /**
         * 스크린샷 (Base64 인코딩)
         */
        private String screenshot;

        /**
         * PDF 데이터 (Base64 인코딩)
         */
        private String pdf;

        /**
         * 메타데이터
         */
        private Map<String, Object> metadata;

        /**
         * 크롤링 시간 (밀리초)
         */
        private Long crawlTime;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MediaInfo {
        private String type; // "image", "video", "audio"
        private String url;
        private String alt;
        private String description;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LinkInfo {
        /**
         * 내부 링크
         */
        private List<String> internal;

        /**
         * 외부 링크
         */
        private List<String> external;

        /**
         * 소셜 미디어 링크
         */
        private List<String> social;
    }

    // === 편의 메서드들 ===

    /**
     * 작업이 완료되었는지 확인
     */
    public boolean isCompleted() {
        return "completed".equals(status);
    }

    /**
     * 작업이 실패했는지 확인
     */
    public boolean isFailed() {
        return "failed".equals(status);
    }

    /**
     * 작업이 진행 중인지 확인
     */
    public boolean isProcessing() {
        return "processing".equals(status) || "pending".equals(status);
    }

    /**
     * 크롤링이 성공했는지 확인
     */
    public boolean isCrawlSuccessful() {
        return isCompleted() && result != null && Boolean.TRUE.equals(result.getSuccess());
    }

    /**
     * 추출된 콘텐츠가 있는지 확인
     */
    public boolean hasExtractedContent() {
        return result != null && result.getExtractedContent() != null && !result.getExtractedContent().trim().isEmpty();
    }

    /**
     * 실패한 작업 결과 생성
     */
    public static Crawl4AIResult failed(String taskId, String error) {
        return Crawl4AIResult.builder()
                .taskId(taskId)
                .status("failed")
                .error(error)
                .requestTime(LocalDateTime.now())
                .completedTime(LocalDateTime.now())
                .build();
    }

    /**
     * 진행 중인 작업 결과 생성
     */
    public static Crawl4AIResult processing(String taskId) {
        return Crawl4AIResult.builder()
                .taskId(taskId)
                .status("processing")
                .requestTime(LocalDateTime.now())
                .build();
    }

    /**
     * 완료된 작업 결과 생성
     */
    public static Crawl4AIResult completed(String taskId, CrawlResult result) {
        return Crawl4AIResult.builder()
                .taskId(taskId)
                .status("completed")
                .result(result)
                .requestTime(LocalDateTime.now())
                .completedTime(LocalDateTime.now())
                .build();
    }
}