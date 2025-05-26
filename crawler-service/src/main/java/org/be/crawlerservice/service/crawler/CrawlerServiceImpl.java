package org.be.crawlerservice.service.crawler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.be.crawlerservice.dto.request.CrawlRequestDto;
import org.be.crawlerservice.dto.response.CrawlStatusDto;
import org.be.crawlerservice.dto.response.StatsResponseDto;
import org.be.crawlerservice.entity.Article;
import org.be.crawlerservice.enums.CrawlerStatus;
import org.be.crawlerservice.metrics.CrawlerMetrics;
import org.be.crawlerservice.service.article.ArticleService;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
@RequiredArgsConstructor
public class CrawlerServiceImpl implements CrawlerService {

    private final ArticleService articleService;
    private final CrawlerMetrics crawlerMetrics;

    // 크롤링 상태 관리
    private final AtomicBoolean isCrawling = new AtomicBoolean(false);
    private final AtomicReference<String> currentCategory = new AtomicReference<>();
    private final AtomicReference<LocalDateTime> crawlStartTime = new AtomicReference<>();
    private final Map<String, Integer> errorCounts = new ConcurrentHashMap<>();
    private final Map<String, LocalDateTime> lastExecutionTimes = new ConcurrentHashMap<>();
    private CompletableFuture<Void> currentCrawlTask;

    // 크롤링할 URL 및 카테고리 정의 (Python 코드와 동일)
    private final List<CategoryUrl> CATEGORY_URLS = Arrays.asList(
            new CategoryUrl("정치", "https://news.naver.com/section/100"),
            new CategoryUrl("경제", "https://news.naver.com/section/101"),
            new CategoryUrl("사회", "https://news.naver.com/section/102"),
            new CategoryUrl("생활문화", "https://news.naver.com/section/103"),
            new CategoryUrl("세계", "https://news.naver.com/section/104"),
            new CategoryUrl("IT과학", "https://news.naver.com/section/105")
    );

    @Override
    public CrawlStatusDto startCrawling(CrawlRequestDto request) {
        if (isCrawling.get()) {
            throw new RuntimeException("크롤링이 이미 진행 중입니다");
        }

        log.info("크롤링 시작 요청: category={}", request.getCategory());

        // 크롤링 상태 설정
        isCrawling.set(true);
        currentCategory.set(request.getCategory());
        crawlStartTime.set(LocalDateTime.now());

        // 비동기로 크롤링 작업 시작
        currentCrawlTask = crawlAsync(request);

        return CrawlStatusDto.builder()
                .status(CrawlerStatus.RUNNING)
                .currentCategory(request.getCategory())
                .startTime(crawlStartTime.get())
                .message("크롤링이 시작되었습니다")
                .build();
    }

    @Override
    public CrawlStatusDto stopCrawling() {
        if (!isCrawling.get()) {
            throw new RuntimeException("실행 중인 크롤링 작업이 없습니다");
        }

        log.info("크롤링 중지 요청");

        // 현재 작업 취소
        if (currentCrawlTask != null && !currentCrawlTask.isDone()) {
            currentCrawlTask.cancel(true);
        }

        // 상태 초기화
        resetCrawlingState();

        return CrawlStatusDto.builder()
                .status(CrawlerStatus.IDLE)
                .message("크롤링이 중지되었습니다")
                .build();
    }

    @Override
    public CrawlStatusDto getCurrentStatus() {
        if (isCrawling.get()) {
            return CrawlStatusDto.builder()
                    .status(CrawlerStatus.RUNNING)
                    .currentCategory(currentCategory.get())
                    .startTime(crawlStartTime.get())
                    .errorCounts(new HashMap<>(errorCounts))
                    .lastExecutionTimes(new HashMap<>(lastExecutionTimes))
                    .message("크롤링이 진행 중입니다")
                    .build();
        } else {
            return CrawlStatusDto.builder()
                    .status(CrawlerStatus.IDLE)
                    .errorCounts(new HashMap<>(errorCounts))
                    .lastExecutionTimes(new HashMap<>(lastExecutionTimes))
                    .message("크롤링 대기 중")
                    .build();
        }
    }

    @Override
    public StatsResponseDto getCrawlingStats() {
        // ArticleService에서 통계 가져오기
        return articleService.getArticleStats();
    }

    @Override
    public Map<String, String> getLastExecutionTimes() {
        Map<String, String> result = new HashMap<>();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

        lastExecutionTimes.forEach((category, time) -> {
            result.put(category, time.format(formatter));
        });

        return result;
    }

    @Override
    public Map<String, Double> getSuccessRates() {
        Map<String, Double> successRates = new HashMap<>();

        // 각 카테고리별 성공률 계산 (임시로 메트릭에서 가져오기)
        CATEGORY_URLS.forEach(categoryUrl -> {
            // TODO: 실제 성공률 계산 로직 구현
            double successRate = calculateSuccessRate(categoryUrl.getCategory());
            successRates.put(categoryUrl.getCategory(), successRate);
        });

        return successRates;
    }

    /**
     * 비동기 크롤링 작업 실행
     */
    @Async
    protected CompletableFuture<Void> crawlAsync(CrawlRequestDto request) {
        return CompletableFuture.runAsync(() -> {
            try {
                crawlJob(request.getCategory());
            } catch (Exception e) {
                log.error("크롤링 작업 중 오류 발생", e);
                handleCrawlingError(currentCategory.get(), e);
            } finally {
                resetCrawlingState();
            }
        });
    }

    /**
     * 실제 크롤링 작업 수행 (Python의 crawling_job과 동일한 로직)
     */
    private void crawlJob(String targetCategory) {
        log.info("크롤링 작업 시작: targetCategory={}", targetCategory);

        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));

        // 대상 URL 필터링
        List<CategoryUrl> urlsToProcess = CATEGORY_URLS.stream()
                .filter(categoryUrl -> targetCategory == null || targetCategory.equals(categoryUrl.getCategory()))
                .toList();

        for (CategoryUrl categoryUrl : urlsToProcess) {
            if (Thread.currentThread().isInterrupted()) {
                log.info("크롤링 작업이 중단되었습니다");
                break;
            }

            String category = categoryUrl.getCategory();
            String url = categoryUrl.getUrl();

            try {
                log.info("카테고리 크롤링 시작: {} - {}", category, timestamp);

                // 메트릭 업데이트: 크롤링 시작
                crawlerMetrics.updateCrawlStatus(category, true);
                currentCategory.set(category);

                long startTime = System.currentTimeMillis();

                // 1. 메타데이터 크롤링 (Python의 crawl_news와 동일)
                List<Article> articles = crawlNewsMetadata(url, category, timestamp);

                if (articles != null && !articles.isEmpty()) {
                    // 2. 내용 크롤링 (Python의 crawl_content와 동일)
                    List<Article> articlesWithContent = crawlArticleContents(articles, category, timestamp);

                    if (articlesWithContent != null && !articlesWithContent.isEmpty()) {
                        // 3. 데이터베이스 저장
                        saveArticlesToDatabase(articlesWithContent, category);

                        // 4. 메트릭 업데이트
                        long crawlTime = System.currentTimeMillis() - startTime;
                        updateSuccessMetrics(category, articlesWithContent.size(), crawlTime);

                        log.info("{} 카테고리 크롤링 완료: {}개 기사 처리", category, articlesWithContent.size());
                    } else {
                        log.warn("{} 카테고리 내용 크롤링 실패", category);
                        updateFailureMetrics(category);
                    }
                } else {
                    log.warn("{} 카테고리 메타데이터 크롤링 실패", category);
                    updateFailureMetrics(category);
                }

            } catch (Exception e) {
                log.error("{} 카테고리 크롤링 중 오류 발생", category, e);
                handleCrawlingError(category, e);
            } finally {
                // 크롤링 상태 초기화
                crawlerMetrics.updateCrawlStatus(category, false);
                lastExecutionTimes.put(category, LocalDateTime.now());
            }

            // 카테고리 간 딜레이 (서버 부하 방지)
            try {
                Thread.sleep(2000); // 2초 대기
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        log.info("전체 크롤링 작업 완료");
    }

    /**
     * 뉴스 메타데이터 크롤링 (Python의 crawl_news와 동일)
     */
    private List<Article> crawlNewsMetadata(String url, String category, String timestamp) {
        log.debug("메타데이터 크롤링 시작: {}", url);

        try {
            // TODO: Crawl4AIClient를 사용하여 메타데이터 크롤링
            // TODO: ParsingStrategy를 사용하여 결과 파싱

            // 임시 구현 - 실제로는 Crawl4AIClient와 ParsingStrategy 사용
            List<Article> articles = new ArrayList<>();

            // 테스트용 더미 데이터
            for (int i = 1; i <= 5; i++) {
                Article article = Article.builder()
                        .title(category + " 테스트 기사 " + i + " - " + timestamp)
                        .content("테스트 내용 " + i)
                        .link("https://test.com/" + category + "/" + i)
                        .category(category)
                        .storedDate(timestamp.substring(0, 8)) // YYYYMMDD 형식
                        .source("테스트 언론사")
                        .build();
                articles.add(article);
            }

            log.info("메타데이터 크롤링 완료: {}개 기사", articles.size());
            return articles;

        } catch (Exception e) {
            log.error("메타데이터 크롤링 실패: {}", url, e);
            return null;
        }
    }

    /**
     * 기사 내용 크롤링 (Python의 crawl_content와 동일)
     */
    private List<Article> crawlArticleContents(List<Article> articles, String category, String timestamp) {
        log.debug("기사 내용 크롤링 시작: {}개 기사", articles.size());

        try {
            // TODO: ProcessingPipeline을 사용하여 내용 크롤링

            // 임시 구현 - 실제로는 각 기사의 내용을 개별적으로 크롤링
            for (Article article : articles) {
                if (Thread.currentThread().isInterrupted()) {
                    break;
                }

                // 내용 업데이트 (임시)
                article.setContent(article.getContent() + " - 상세 내용이 추가되었습니다.");
                article.setArticleTextLength(article.getContent().length());

                // 딜레이 (봇 감지 방지)
                try {
                    Thread.sleep(1000); // 1초 대기
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            log.info("기사 내용 크롤링 완료: {}개 기사", articles.size());
            return articles;

        } catch (Exception e) {
            log.error("기사 내용 크롤링 실패", e);
            return null;
        }
    }

    /**
     * 데이터베이스에 기사 저장
     */
    private void saveArticlesToDatabase(List<Article> articles, String category) {
        try {
            long startTime = System.currentTimeMillis();

            List<Article> savedArticles = articleService.saveArticles(articles);

            long saveTime = System.currentTimeMillis() - startTime;
            crawlerMetrics.recordDbOperationTime(saveTime);

            log.info("{} 카테고리 {}개 기사 DB 저장 완료 ({}ms)",
                    category, savedArticles.size(), saveTime);

        } catch (Exception e) {
            log.error("{} 카테고리 DB 저장 실패", category, e);
            throw new RuntimeException("DB 저장 실패", e);
        }
    }

    /**
     * 성공 메트릭 업데이트
     */
    private void updateSuccessMetrics(String category, int articleCount, long crawlTime) {
        crawlerMetrics.incrementCrawlSuccess(category);
        crawlerMetrics.incrementArticlesProcessed(category, articleCount);
        crawlerMetrics.recordCrawlTime(category, crawlTime);

        // 에러 카운트 리셋
        errorCounts.put(category, 0);
    }

    /**
     * 실패 메트릭 업데이트
     */
    private void updateFailureMetrics(String category) {
        crawlerMetrics.incrementCrawlFailure(category);

        // 에러 카운트 증가
        errorCounts.merge(category, 1, Integer::sum);
    }

    /**
     * 크롤링 오류 처리
     */
    private void handleCrawlingError(String category, Exception e) {
        updateFailureMetrics(category);
        log.error("크롤링 오류 처리: category={}", category, e);
    }

    /**
     * 크롤링 상태 초기화
     */
    private void resetCrawlingState() {
        isCrawling.set(false);
        currentCategory.set(null);
        crawlStartTime.set(null);
        currentCrawlTask = null;
    }

    /**
     * 성공률 계산 (임시 구현)
     */
    private double calculateSuccessRate(String category) {
        // TODO: 실제 성공률 계산 로직 구현
        int errorCount = errorCounts.getOrDefault(category, 0);
        return errorCount == 0 ? 95.0 : Math.max(50.0, 95.0 - (errorCount * 10));
    }

    /**
     * 카테고리와 URL을 묶는 내부 클래스
     */
    private static class CategoryUrl {
        private final String category;
        private final String url;

        public CategoryUrl(String category, String url) {
            this.category = category;
            this.url = url;
        }

        public String getCategory() { return category; }
        public String getUrl() { return url; }
    }
}