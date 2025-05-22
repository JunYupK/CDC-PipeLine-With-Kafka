package org.be.crawlerservice.controller;

import lombok.RequiredArgsConstructor;
import org.be.crawlerservice.dto.request.FilterRequestDto;
import org.be.crawlerservice.dto.response.ArticleResponseDto;
import org.be.crawlerservice.dto.response.StatsResponseDto;
import org.be.crawlerservice.service.article.ArticleService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/articles")
@RequiredArgsConstructor
public class ArticleController {

    private final ArticleService articleService;

    /**
     * 기사 목록 조회 (페이징)
     * GET /api/v1/articles
     */
    @GetMapping
    public ResponseEntity<Page<ArticleResponseDto>> getArticles(
            @Valid FilterRequestDto filter,
            @PageableDefault(size = 20) Pageable pageable) {

        Page<ArticleResponseDto> articles = articleService.getArticles(filter, pageable);
        return ResponseEntity.ok(articles);
    }

    /**
     * 특정 기사 조회
     * GET /api/v1/articles/{id}
     */
    @GetMapping("/{id}")
    public ResponseEntity<ArticleResponseDto> getArticle(@PathVariable Long id) {
        return articleService.getArticleById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * 기사 통계 정보
     * GET /api/v1/articles/stats
     */
    @GetMapping("/stats")
    public ResponseEntity<StatsResponseDto> getArticleStats() {
        StatsResponseDto stats = articleService.getArticleStats();
        return ResponseEntity.ok(stats);
    }

    /**
     * 카테고리별 기사 수
     * GET /api/v1/articles/count-by-category
     */
    @GetMapping("/count-by-category")
    public ResponseEntity<Map<String, Long>> getCountByCategory() {
        Map<String, Long> counts = articleService.getCountByCategory();
        return ResponseEntity.ok(counts);
    }
}