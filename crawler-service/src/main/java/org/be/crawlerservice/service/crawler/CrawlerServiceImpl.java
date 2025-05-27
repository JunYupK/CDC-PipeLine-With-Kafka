package org.be.crawlerservice.service.crawler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.be.crawlerservice.client.Crawl4AIClient;
import org.be.crawlerservice.client.schema.NaverNewsSchemas;
import org.be.crawlerservice.dto.crawl4ai.Crawl4AIRequest;
import org.be.crawlerservice.dto.crawl4ai.Crawl4AIResult;
import org.be.crawlerservice.dto.request.CrawlRequestDto;
import org.be.crawlerservice.dto.response.CrawlStatusDto;
import org.be.crawlerservice.dto.response.StatsResponseDto;
import org.be.crawlerservice.entity.Article;
import org.be.crawlerservice.enums.CrawlerStatus;
import org.be.crawlerservice.metrics.CrawlerMetrics;
import org.be.crawlerservice.service.article.ArticleService;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.net.MalformedURLException;
import java.net.URL;
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
    private final Crawl4AIClient crawl4AIClient;
    private final ObjectMapper objectMapper;

    // 크롤링 상태 관리
    private final AtomicBoolean isCrawling = new AtomicBoolean(false);
    private final AtomicReference<String> currentCategory = new AtomicReference<>();
    private final AtomicReference<LocalDateTime> crawlStartTime = new AtomicReference<>();
    private final Map<String, Integer> errorCounts = new ConcurrentHashMap<>();
    private final Map<String, LocalDateTime> lastExecutionTimes = new ConcurrentHashMap<>();
    private CompletableFuture<Void> currentCrawlTask;

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

        NaverNewsSchemas.getCategoryUrls().keySet().forEach(category -> {
            double successRate = calculateSuccessRate(category);
            successRates.put(category, successRate);
        });

        return successRates;
    }

    /**
     * 비동기 크롤링 작업 실행
     */
    @Async("crawlerExecutor")
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
     * 🔥 실제 Crawl4AIClient 사용으로 업데이트
     */
    private void crawlJob(String targetCategory) {
        log.info("크롤링 작업 시작: targetCategory={}", targetCategory);

        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));

        // 대상 URL 필터링
        Map<String, String> categoryUrls = NaverNewsSchemas.getCategoryUrls();
        Map<String, String> urlsToProcess = new HashMap<>();

        if (targetCategory == null) {
            // 모든 카테고리 크롤링
            urlsToProcess.putAll(categoryUrls);
        } else if (categoryUrls.containsKey(targetCategory)) {
            // 특정 카테고리만 크롤링
            urlsToProcess.put(targetCategory, categoryUrls.get(targetCategory));
        } else {
            log.warn("Unknown category: {}", targetCategory);
            return;
        }

        for (Map.Entry<String, String> entry : urlsToProcess.entrySet()) {
            if (Thread.currentThread().isInterrupted()) {
                log.info("크롤링 작업이 중단되었습니다");
                break;
            }

            String category = entry.getKey();
            String url = entry.getValue();

            try {
                log.info("카테고리 크롤링 시작: {} - {}", category, timestamp);

                // 메트릭 업데이트: 크롤링 시작
                crawlerMetrics.updateCrawlStatus(category, true);
                currentCategory.set(category);

                long startTime = System.currentTimeMillis();

                // 1. 메타데이터 크롤링 (실제 구현)
                List<Article> articles = crawlNewsMetadata(url, category, timestamp);

                if (articles != null && !articles.isEmpty()) {
                    // 2. 내용 크롤링 (실제 구현)
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
     * 뉴스 메타데이터 크롤링 (실제 Crawl4AIClient 사용)
     * 🔥 실제 구현으로 업데이트
     */
    private List<Article> crawlNewsMetadata(String url, String category, String timestamp) {
        log.debug("메타데이터 크롤링 시작: {}", url);

        try {
            // Crawl4AI 요청 생성
            Map<String, Object> schema = NaverNewsSchemas.getSchemaForCategory(category, true);
            Crawl4AIRequest request = Crawl4AIRequest.forUrlList(url, schema);

            // 실제 크롤링 실행
            Crawl4AIResult result = crawl4AIClient.crawl(request);

            if (!result.isCrawlSuccessful()) {
                log.error("메타데이터 크롤링 실패: {} - {}", url, result.getError());
                return null;
            }

            if (!result.hasExtractedContent()) {
                log.warn("추출된 콘텐츠가 없음: {}", url);
                return null;
            }

            // JSON 파싱
            List<Map<String, Object>> extractedItems = objectMapper.readValue(
                    result.getResult().getExtractedContent(),
                    new TypeReference<List<Map<String, Object>>>() {}
            );

            // Article 엔티티로 변환
            List<Article> articles = new ArrayList<>();
            for (Map<String, Object> item : extractedItems) {
                String title = (String) item.get("title");
                String link = (String) item.get("link");

                // 유효성 검사
                if (!StringUtils.hasText(title) || !StringUtils.hasText(link)) {
                    continue;
                }

                // 상대 URL을 절대 URL로 변환
                String absoluteLink = convertToAbsoluteUrl(link, url);

                Article article = Article.builder()
                        .title(title.trim())
                        .link(absoluteLink)
                        .category(category)
                        .storedDate(timestamp.substring(0, 8)) // YYYYMMDD 형식
                        .source("네이버뉴스")
                        .content("") // 나중에 채움
                        .build();

                articles.add(article);
            }

            log.info("메타데이터 크롤링 완료: {}개 기사", articles.size());
            return articles;

        } catch (JsonProcessingException e) {
            log.error("JSON 파싱 실패: {}", url, e);
            return null;
        } catch (Exception e) {
            log.error("메타데이터 크롤링 실패: {}", url, e);
            return null;
        }
    }

    /**
     * 기사 내용 크롤링 (실제 Crawl4AIClient 사용)
     * 🔥 실제 구현으로 업데이트
     */
    private List<Article> crawlArticleContents(List<Article> articles, String category, String timestamp) {
        log.debug("기사 내용 크롤링 시작: {}개 기사", articles.size());

        Map<String, Object> contentSchema = NaverNewsSchemas.getSchemaForCategory(category, false);
        List<Article> successfulArticles = new ArrayList<>();

        for (Article article : articles) {
            if (Thread.currentThread().isInterrupted()) {
                break;
            }

            try {
                // Crawl4AI 요청 생성
                Crawl4AIRequest request = Crawl4AIRequest.forArticleContent(article.getLink(), contentSchema);

                // 실제 크롤링 실행
                Crawl4AIResult result = crawl4AIClient.crawl(request);

                if (result.isCrawlSuccessful() && result.hasExtractedContent()) {
                    // JSON 파싱
                    List<Map<String, Object>> extractedContent = objectMapper.readValue(
                            result.getResult().getExtractedContent(),
                            new TypeReference<List<Map<String, Object>>>() {}
                    );

                    if (!extractedContent.isEmpty()) {
                        Map<String, Object> contentData = extractedContent.get(0);
                        String content = (String) contentData.get("content");

                        if (StringUtils.hasText(content)) {
                            // 기사 내용 업데이트
                            article.setContent(content.trim());
                            article.setArticleTextLength(content.length());
                            successfulArticles.add(article);

                            log.debug("기사 내용 크롤링 성공: {}", article.getTitle());
                        }
                    }
                } else {
                    log.warn("기사 내용 크롤링 실패: {} - {}", article.getLink(), result.getError());
                }

                // 딜레이 (봇 감지 방지)
                Thread.sleep(1500); // 1.5초 대기

            } catch (JsonProcessingException e) {
                log.error("JSON 파싱 실패: {}", article.getLink(), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("기사 내용 크롤링 실패: {}", article.getLink(), e);
            }
        }

        log.info("기사 내용 크롤링 완료: {}/{}개 성공", successfulArticles.size(), articles.size());
        return successfulArticles;
    }

    /**
     * 상대 URL을 절대 URL로 변환
     */
    private String convertToAbsoluteUrl(String link, String baseUrl) {
        if (link.startsWith("http")) {
            return link;
        }

        try {
            URL base = new URL(baseUrl);
            if (link.startsWith("/")) {
                return base.getProtocol() + "://" + base.getHost() + link;
            } else {
                return baseUrl + "/" + link;
            }
        } catch (MalformedURLException e) {
            log.warn("URL 변환 실패: {} + {}", baseUrl, link);
            return link;
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
     * 성공률 계산
     */
    private double calculateSuccessRate(String category) {
        int errorCount = errorCounts.getOrDefault(category, 0);
        return errorCount == 0 ? 95.0 : Math.max(50.0, 95.0 - (errorCount * 10));
    }
}