package org.be.crawlerservice.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ArticleResponseDto {

    private Long id;
    private String title;
    private String content;
    private String link;
    private String category;
    private String source;
    private String author;
    private LocalDateTime publishedAt;
    private String storedDate;
    private Integer viewsCount;
    private Double sentimentScore;
    private Integer articleTextLength;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /**
     * 미디어 파일들
     */
    private List<MediaDto> media;

    /**
     * 키워드들
     */
    private List<String> keywords;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MediaDto {
        private Long id;
        private String type;
        private String url;
        private String caption;
    }
}