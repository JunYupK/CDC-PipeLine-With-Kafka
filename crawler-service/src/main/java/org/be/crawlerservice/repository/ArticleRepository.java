package org.be.crawlerservice.repository;

import org.be.crawlerservice.entity.Article;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface ArticleRepository extends JpaRepository<Article, Long>, JpaSpecificationExecutor<Article> {

    /**
     * URL로 기사 존재 여부 확인
     */
    boolean existsByLink(String link);

    /**
     * 제목 또는 내용으로 검색
     */
    Page<Article> findByTitleContainingIgnoreCaseOrContentContainingIgnoreCase(
            String title, String content, Pageable pageable);

    /**
     * 카테고리별 기사 수 조회
     */
    @Query("SELECT a.category, COUNT(a) FROM Article a WHERE a.isDeleted = false GROUP BY a.category")
    List<Object[]> countByCategory();

    /**
     * 전체 카테고리 수
     */
    @Query("SELECT COUNT(DISTINCT a.category) FROM Article a WHERE a.isDeleted = false")
    long countDistinctCategories();

    /**
     * 전체 저장 날짜 수
     */
    @Query("SELECT COUNT(DISTINCT a.storedDate) FROM Article a WHERE a.isDeleted = false")
    long countDistinctStoredDates();

    /**
     * 최근 생성 시간
     */
    @Query("SELECT MAX(a.createdAt) FROM Article a WHERE a.isDeleted = false")
    LocalDateTime findLastCreatedAt();

    /**
     * 카테고리별 상세 통계
     */
    @Query("""
        SELECT a.category, 
               COUNT(a), 
               MIN(a.storedDate), 
               MAX(a.storedDate)
        FROM Article a 
        WHERE a.isDeleted = false 
        GROUP BY a.category
        """)
    List<Object[]> getStatsByCategory();

    /**
     * 최근 일별 통계
     */
    @Query(value = """
        SELECT a.stored_date, 
               COUNT(*) 
        FROM articles a 
        WHERE a.is_deleted = false 
        GROUP BY a.stored_date 
        ORDER BY a.stored_date DESC 
        LIMIT :days
        """, nativeQuery = true)
    List<Object[]> getRecentDailyStats(@Param("days") int days);

    /**
     * 카테고리별 기사 조회
     */
    Page<Article> findByCategoryAndIsDeletedFalse(String category, Pageable pageable);

    /**
     * 날짜 범위로 기사 조회
     */
    @Query("SELECT a FROM Article a WHERE a.createdAt BETWEEN :startDate AND :endDate AND a.isDeleted = false")
    Page<Article> findByDateRange(
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate,
            Pageable pageable);
}