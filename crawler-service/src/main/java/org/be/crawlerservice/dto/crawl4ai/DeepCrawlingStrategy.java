package org.be.crawlerservice.dto.crawl4ai;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Crawl4AI Deep Crawling 전략 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeepCrawlingStrategy {

    /**
     * 전략 타입 ("BFSDeepCrawlStrategy", "DFSDeepCrawlStrategy", "BestFirstCrawlingStrategy")
     */
    private String type;

    /**
     * 전략별 파라미터
     */
    private Map<String, Object> params;

    // === BFS 전략 팩토리 메서드 ===

    /**
     * BFS Deep Crawling 전략 생성
     */
    public static DeepCrawlingStrategy createBFS(int maxDepth, int maxPages, boolean includeExternal, double scoreThreshold) {
        return DeepCrawlingStrategy.builder()
                .type("BFSDeepCrawlStrategy")
                .params(Map.of(
                        "max_depth", maxDepth,
                        "max_pages", maxPages,
                        "include_external", includeExternal,
                        "score_threshold", scoreThreshold
                ))
                .build();
    }

    /**
     * Best-First 전략 생성 (고급)
     */
    public static DeepCrawlingStrategy createBestFirst(int maxDepth, int maxPages,
                                                       List<String> keywords,
                                                       List<String> allowedDomains) {
        return DeepCrawlingStrategy.builder()
                .type("BestFirstCrawlingStrategy")
                .params(Map.of(
                        "max_depth", maxDepth,
                        "max_pages", maxPages,
                        "include_external", false,
                        "scorer", Map.of(
                                "type", "KeywordRelevanceScorer",
                                "keywords", keywords,
                                "weight", 0.7
                        ),
                        "filter_chain", Map.of(
                                "filters", List.of(
                                        Map.of(
                                                "type", "DomainFilter",
                                                "allowed_domains", allowedDomains
                                        )
                                )
                        )
                ))
                .build();
    }
}