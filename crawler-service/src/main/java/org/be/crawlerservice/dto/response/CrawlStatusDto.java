package org.be.crawlerservice.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.be.crawlerservice.enums.CrawlerStatus;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CrawlStatusDto {

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