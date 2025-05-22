package org.be.crawlerservice.service.article;

import org.be.crawlerservice.dto.request.FilterRequestDto;
import org.be.crawlerservice.dto.response.ArticleResponseDto;
import org.be.crawlerservice.dto.response.StatsResponseDto;
import org.be.crawlerservice.entity.Article;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface ArticleService {

    /**
     * 기사 목록 조회 (필터링 및 페이징)
     */
    Page<ArticleResponseDto> getArticles(FilterRequestDto filter, Pageable pageable);

    /**
     * 기사 ID로 단일 기사 조회
     */
    Optional<ArticleResponseDto> getArticleById(Long id);

    /**
     * 기사 통계 정보 조회
     */
    StatsResponseDto getArticleStats();

    /**
     * 카테고리별 기사 수 조회
     */
    Map<String, Long> getCountByCategory();

    /**
     * 기사 저장 (단일)
     */
    Article saveArticle(Article article);

    /**
     * 기사 일괄 저장
     */
    List<Article> saveArticles(List<Article> articles);

    /**
     * 기사 삭제
     */
    void deleteArticle(Long id);

    /**
     * URL로 기사 존재 여부 확인
     */
    boolean existsByUrl(String url);

    /**
     * 기사 검색
     */
    Page<ArticleResponseDto> searchArticles(String keyword, Pageable pageable);
}