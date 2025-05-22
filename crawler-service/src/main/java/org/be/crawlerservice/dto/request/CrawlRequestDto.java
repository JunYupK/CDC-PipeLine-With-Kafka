package org.be.crawlerservice.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CrawlRequestDto {

    /**
     * 크롤링할 카테고리 (선택적)
     * null이면 모든 카테고리 크롤링
     */
    private String category;

    /**
     * 크롤링할 페이지 수 (기본값: 3)
     */
    @Positive(message = "페이지 수는 1 이상이어야 합니다")
    @Builder.Default
    private Integer maxPages = 3;

    /**
     * 우선순위 (1-10, 높을수록 우선)
     */
    @Builder.Default
    private Integer priority = 5;

    /**
     * 특정 URL들만 크롤링 (선택적)
     */
    private List<String> targetUrls;

    /**
     * 강제 재크롤링 여부
     */
    @Builder.Default
    private Boolean forceRecrawl = false;
}
