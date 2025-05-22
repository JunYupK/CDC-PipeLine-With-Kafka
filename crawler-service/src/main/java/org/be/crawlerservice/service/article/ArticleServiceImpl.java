package org.be.crawlerservice.service.article;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.be.crawlerservice.dto.request.FilterRequestDto;
import org.be.crawlerservice.dto.response.ArticleResponseDto;
import org.be.crawlerservice.dto.response.StatsResponseDto;
import org.be.crawlerservice.entity.Article;
import org.be.crawlerservice.entity.Media;
import org.be.crawlerservice.exception.ArticleNotFoundException;
import org.be.crawlerservice.repository.ArticleRepository;
import org.be.crawlerservice.repository.MediaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import jakarta.persistence.criteria.Predicate;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ArticleServiceImpl implements ArticleService {

    private final ArticleRepository articleRepository;
    private final MediaRepository mediaRepository;

    @Override
    public Page<ArticleResponseDto> getArticles(FilterRequestDto filter, Pageable pageable) {
        log.debug("Getting articles with filter: {}", filter);

        // 동적 쿼리 생성
        Specification<Article> spec = createSpecification(filter);

        // 정렬 처리
        Pageable sortedPageable = createSortedPageable(filter, pageable);

        // 조회 및 변환
        Page<Article> articles = articleRepository.findAll(spec, sortedPageable);

        return articles.map(this::convertToDto);
    }

    @Override
    public Optional<ArticleResponseDto> getArticleById(Long id) {
        log.debug("Getting article by id: {}", id);

        return articleRepository.findById(id)
                .map(this::convertToDto);
    }

    @Override
    public StatsResponseDto getArticleStats() {
        log.debug("Getting article statistics");

        // 전체 통계
        long totalArticles = articleRepository.count();
        long totalCategories = articleRepository.countDistinctCategories();
        long totalDates = articleRepository.countDistinctStoredDates();
        LocalDateTime lastCrawlTime = articleRepository.findLastCreatedAt();

        StatsResponseDto.OverallStats overallStats = StatsResponseDto.OverallStats.builder()
                .totalArticles(totalArticles)
                .totalCategories((int) totalCategories)
                .totalDates((int) totalDates)
                .lastCrawlTime(lastCrawlTime)
                .averageSuccessRate(calculateAverageSuccessRate())
                .build();

        // 카테고리별 통계
        Map<String, StatsResponseDto.CategoryStats> categoryStats = getCategoryStats();

        // 최근 일별 통계
        Map<String, StatsResponseDto.DailyStats> dailyStats = getDailyStats();

        return StatsResponseDto.builder()
                .overall(overallStats)
                .byCategory(categoryStats)
                .recentDaily(dailyStats)
                .build();
    }

    @Override
    public Map<String, Long> getCountByCategory() {
        log.debug("Getting count by category");

        List<Object[]> results = articleRepository.countByCategory();

        return results.stream()
                .collect(Collectors.toMap(
                        result -> (String) result[0], // category
                        result -> (Long) result[1]    // count
                ));
    }

    @Override
    @Transactional
    public Article saveArticle(Article article) {
        log.debug("Saving article: {}", article.getTitle());

        // 중복 체크
        if (StringUtils.hasText(article.getLink()) &&
                articleRepository.existsByLink(article.getLink())) {
            log.warn("Article with URL already exists: {}", article.getLink());
            throw new IllegalArgumentException("Article with this URL already exists");
        }

        // 생성 시간 설정
        if (article.getCreatedAt() == null) {
            article.setCreatedAt(LocalDateTime.now());
        }
        article.setUpdatedAt(LocalDateTime.now());

        return articleRepository.save(article);
    }

    @Override
    @Transactional
    public List<Article> saveArticles(List<Article> articles) {
        log.debug("Saving {} articles", articles.size());

        // 중복 제거
        List<Article> uniqueArticles = articles.stream()
                .filter(article -> !articleRepository.existsByLink(article.getLink()))
                .collect(Collectors.toList());

        log.debug("After deduplication: {} unique articles", uniqueArticles.size());

        // 생성/수정 시간 설정
        LocalDateTime now = LocalDateTime.now();
        uniqueArticles.forEach(article -> {
            if (article.getCreatedAt() == null) {
                article.setCreatedAt(now);
            }
            article.setUpdatedAt(now);
        });

        return articleRepository.saveAll(uniqueArticles);
    }

    @Override
    @Transactional
    public void deleteArticle(Long id) {
        log.debug("Deleting article with id: {}", id);

        if (!articleRepository.existsById(id)) {
            throw new ArticleNotFoundException("Article not found with id: " + id);
        }

        articleRepository.deleteById(id);
    }

    @Override
    public boolean existsByUrl(String url) {
        if (!StringUtils.hasText(url)) {
            return false;
        }

        return articleRepository.existsByLink(url);
    }

    @Override
    public Page<ArticleResponseDto> searchArticles(String keyword, Pageable pageable) {
        log.debug("Searching articles with keyword: {}", keyword);

        if (!StringUtils.hasText(keyword)) {
            return Page.empty(pageable);
        }

        Page<Article> articles = articleRepository.findByTitleContainingIgnoreCaseOrContentContainingIgnoreCase(
                keyword, keyword, pageable);

        return articles.map(this::convertToDto);
    }

    // === Private 헬퍼 메서드들 ===

    /**
     * 동적 쿼리 스펙 생성
     */
    private Specification<Article> createSpecification(FilterRequestDto filter) {
        return (root, query, criteriaBuilder) -> {
            List<Predicate> predicates = new ArrayList<>();

            // 카테고리 필터
            if (StringUtils.hasText(filter.getCategory())) {
                predicates.add(criteriaBuilder.equal(root.get("category"), filter.getCategory()));
            }

            // 키워드 검색 (제목 또는 내용)
            if (StringUtils.hasText(filter.getKeyword())) {
                String keyword = "%" + filter.getKeyword().toLowerCase() + "%";
                Predicate titlePredicate = criteriaBuilder.like(
                        criteriaBuilder.lower(root.get("title")), keyword);
                Predicate contentPredicate = criteriaBuilder.like(
                        criteriaBuilder.lower(root.get("content")), keyword);
                predicates.add(criteriaBuilder.or(titlePredicate, contentPredicate));
            }

            // 날짜 범위 필터
            if (filter.getStartDate() != null) {
                LocalDateTime startDateTime = filter.getStartDate().atStartOfDay();
                predicates.add(criteriaBuilder.greaterThanOrEqualTo(root.get("createdAt"), startDateTime));
            }

            if (filter.getEndDate() != null) {
                LocalDateTime endDateTime = filter.getEndDate().atTime(23, 59, 59);
                predicates.add(criteriaBuilder.lessThanOrEqualTo(root.get("createdAt"), endDateTime));
            }

            return criteriaBuilder.and(predicates.toArray(new Predicate[0]));
        };
    }

    /**
     * 정렬된 Pageable 생성
     */
    private Pageable createSortedPageable(FilterRequestDto filter, Pageable pageable) {
        if (!StringUtils.hasText(filter.getSortBy())) {
            return pageable;
        }

        Sort.Direction direction = "asc".equalsIgnoreCase(filter.getSortDirection()) ?
                Sort.Direction.ASC : Sort.Direction.DESC;

        Sort sort = Sort.by(direction, filter.getSortBy());

        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), sort);
    }

    /**
     * Entity를 DTO로 변환
     */
    private ArticleResponseDto convertToDto(Article article) {
        // 미디어 파일 변환
        List<ArticleResponseDto.MediaDto> mediaDtos = Optional.ofNullable(article.getMedia())
                .orElse(Collections.emptyList())
                .stream()
                .map(this::convertMediaToDto)
                .collect(Collectors.toList());

        // 키워드 파싱 (콤마로 구분된 문자열을 리스트로)
        List<String> keywords = Optional.ofNullable(article.getKeywords())
                .map(keywordStr -> Arrays.asList(keywordStr.split(",")))
                .orElse(Collections.emptyList())
                .stream()
                .map(String::trim)
                .filter(StringUtils::hasText)
                .collect(Collectors.toList());

        return ArticleResponseDto.builder()
                .id(article.getId())
                .title(article.getTitle())
                .content(article.getContent())
                .link(article.getLink())
                .category(article.getCategory())
                .source(article.getSource())
                .author(article.getAuthor())
                .publishedAt(article.getPublishedAt())
                .storedDate(article.getStoredDate())
                .viewsCount(article.getViewsCount())
                .sentimentScore(article.getSentimentScore())
                .articleTextLength(article.getArticleTextLength())
                .createdAt(article.getCreatedAt())
                .updatedAt(article.getUpdatedAt())
                .media(mediaDtos)
                .keywords(keywords)
                .build();
    }

    /**
     * Media Entity를 DTO로 변환
     */
    private ArticleResponseDto.MediaDto convertMediaToDto(Media media) {
        return ArticleResponseDto.MediaDto.builder()
                .id(media.getId())
                .type(media.getType())
                .url(media.getUrl())
                .caption(media.getCaption())
                .build();
    }

    /**
     * 카테고리별 통계 조회
     */
    private Map<String, StatsResponseDto.CategoryStats> getCategoryStats() {
        List<Object[]> categoryResults = articleRepository.getStatsByCategory();

        return categoryResults.stream()
                .collect(Collectors.toMap(
                        result -> (String) result[0], // category
                        result -> StatsResponseDto.CategoryStats.builder()
                                .category((String) result[0])
                                .articleCount((Long) result[1])
                                .earliestDate((String) result[2])
                                .latestDate((String) result[3])
                                .successRate(95.0) // TODO: 실제 성공률 계산 로직 추가
                                .errorCount(0)      // TODO: 실제 에러 카운트 조회
                                .build()
                ));
    }

    /**
     * 최근 일별 통계 조회
     */
    private Map<String, StatsResponseDto.DailyStats> getDailyStats() {
        List<Object[]> dailyResults = articleRepository.getRecentDailyStats(7);

        return dailyResults.stream()
                .collect(Collectors.toMap(
                        result -> (String) result[0], // date
                        result -> StatsResponseDto.DailyStats.builder()
                                .date((String) result[0])
                                .articleCount((Long) result[1])
                                .averageCrawlTime(0.0)    // TODO: 실제 크롤링 시간 계산
                                .successCount(((Long) result[1]).intValue()) // 임시로 전체 수
                                .failureCount(0)          // TODO: 실제 실패 수 계산
                                .build()
                ));
    }

    /**
     * 평균 성공률 계산
     */
    private double calculateAverageSuccessRate() {
        // TODO: 실제 성공률 계산 로직 구현
        // 현재는 임시값 반환
        return 95.0;
    }
}