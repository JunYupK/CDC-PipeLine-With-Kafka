package org.be.crawlerservice.service.crawler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.be.crawlerservice.client.Crawl4AIClient;
import org.be.crawlerservice.client.schema.NaverNewsSchemas;
import org.be.crawlerservice.dto.crawl4ai.Crawl4AIRequest;
import org.be.crawlerservice.dto.crawl4ai.Crawl4AIResult;
import org.be.crawlerservice.dto.crawl4ai.StreamingCrawlResult;
import org.be.crawlerservice.dto.request.CrawlRequestDto;
import org.be.crawlerservice.dto.response.CrawlStatusDto;
import org.be.crawlerservice.dto.response.StatsResponseDto;
import org.be.crawlerservice.entity.Article;
import org.be.crawlerservice.entity.Media;
import org.be.crawlerservice.enums.CrawlerStatus;
import org.be.crawlerservice.metrics.CrawlerMetrics;
import org.be.crawlerservice.repository.ArticleRepository;
import org.be.crawlerservice.repository.MediaRepository;
import org.be.crawlerservice.service.article.ArticleService;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.net.MalformedURLException;
import java.net.URL;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

@Slf4j
@Service
@RequiredArgsConstructor
public class CrawlerServiceImpl implements CrawlerService {

    private final ArticleService articleService;
    private final CrawlerMetrics crawlerMetrics;
    private final Crawl4AIClient crawl4AIClient;
    private final ObjectMapper objectMapper;
    private final ArticleRepository articleRepository;
    private final MediaRepository mediaRepository;

    // 크롤링 상태 관리
    private final AtomicBoolean isCrawling = new AtomicBoolean(false);
    private final AtomicBoolean isDeepCrawling = new AtomicBoolean(false);
    private final AtomicReference<String> currentCategory = new AtomicReference<>();
    private final AtomicReference<LocalDateTime> crawlStartTime = new AtomicReference<>();
    private final Map<String, Integer> errorCounts = new ConcurrentHashMap<>();
    private final Map<String, LocalDateTime> lastExecutionTimes = new ConcurrentHashMap<>();
    private CompletableFuture<Void> currentCrawlTask;
    private final AtomicInteger processedCount = new AtomicInteger(0);
    private final AtomicInteger totalCount = new AtomicInteger(0);

    // Deep Crawling 전용 상태 관리
    private final AtomicInteger deepCrawlProcessedCount = new AtomicInteger(0);
    private final AtomicInteger deepCrawlSavedCount = new AtomicInteger(0);

    // 하이브리드 크롤링을 위한 추가 필드
    private final ConcurrentHashMap<String, Set<String>> visitedUrls = new ConcurrentHashMap<>();
    private static final int WORKER_COUNT = 3; // Consumer 스레드 수

    @Override
    public CrawlStatusDto startCrawling(CrawlRequestDto request) {
        if (isCrawling.get()) {
            throw new RuntimeException("크롤링이 이미 진행 중입니다");
        }

        log.info("기본 크롤링 시작 요청: category={}", request.getCategory());

        // 크롤링 상태 설정
        isCrawling.set(true);
        currentCategory.set(request.getCategory());
        crawlStartTime.set(LocalDateTime.now());
        processedCount.set(0);
        totalCount.set(0);

        // 🔥 CompletableFuture로 비동기 실행
        CompletableFuture.runAsync(() -> {
            try {
                log.info("비동기 기본 크롤링 작업 시작");
                if (request.getCategory() == null || "전체".equals(request.getCategory())) {
                    crawlBasic(); // 전체 카테고리
                } else {
                    crawlCategory(request.getCategory()); // 특정 카테고리
                }
                log.info("기본 크롤링 작업 완료 - 총 처리: {}개", processedCount.get());
            } catch (Exception e) {
                log.error("기본 크롤링 작업 중 에러 발생", e);
            } finally {
                isCrawling.set(false);
            }
        });

        return CrawlStatusDto.builder()
                .status(CrawlerStatus.RUNNING)
                .currentCategory(request.getCategory())
                .startTime(crawlStartTime.get())
                .message("기본 크롤링이 시작되었습니다")
                .build();
    }

    @Override
    public CrawlStatusDto startDeepCrawling(CrawlRequestDto request) {
        if (isDeepCrawling.get()) {
            throw new RuntimeException("딥 크롤링이 이미 진행 중입니다");
        }
        if (request.getCategory() == null) {
            throw new RuntimeException("카테고리가 null 입니다.");
        }

        log.info("BFS Deep Crawling 시작 요청: category={}", request.getCategory());

        // Deep Crawling 상태 설정
        isDeepCrawling.set(true);
        currentCategory.set(request.getCategory());
        crawlStartTime.set(LocalDateTime.now());
        deepCrawlProcessedCount.set(0);
        deepCrawlSavedCount.set(0);

        // 🔥 CompletableFuture로 비동기 실행
        CompletableFuture.runAsync(() -> {
            try {
                log.info("비동기 BFS Deep Crawling 작업 시작");
                crawlDeep(request);
                log.info("BFS Deep Crawling 작업 완료 - 처리: {}개, 저장: {}개",
                        deepCrawlProcessedCount.get(), deepCrawlSavedCount.get());
            } catch (Exception e) {
                log.error("BFS Deep Crawling 작업 중 에러 발생", e);
            } finally {
                isDeepCrawling.set(false);
            }
        });

        return CrawlStatusDto.builder()
                .status(CrawlerStatus.RUNNING)
                .currentCategory(request.getCategory())
                .startTime(crawlStartTime.get())
                .message("BFS Deep Crawling이 시작되었습니다")
                .build();
    }

    @Override
    public CrawlStatusDto stopCrawling() {
        boolean wasCrawling = isCrawling.get() || isDeepCrawling.get();

        if (!wasCrawling) {
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
                    .processedArticles(processedCount.get())
                    .errorCounts(new HashMap<>(errorCounts))
                    .lastExecutionTimes(new HashMap<>(lastExecutionTimes))
                    .message("기본 크롤링이 진행 중입니다")
                    .build();
        } else if (isDeepCrawling.get()) {
            return CrawlStatusDto.builder()
                    .status(CrawlerStatus.RUNNING)
                    .currentCategory(currentCategory.get())
                    .startTime(crawlStartTime.get())
                    .processedArticles(deepCrawlProcessedCount.get())
                    .errorCounts(new HashMap<>(errorCounts))
                    .lastExecutionTimes(new HashMap<>(lastExecutionTimes))
                    .message("BFS Deep Crawling이 진행 중입니다")
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

    // ===== 기본 크롤링 메서드들 =====

    private void crawlBasic() {
        NaverNewsSchemas.getCategoryUrls().keySet().forEach(category -> {
            try {
                log.info("카테고리 {} 기본 크롤링 시작", category);
                crawlCategory(category);
                log.info("카테고리 {} 기본 크롤링 완료", category);
            } catch (Exception e) {
                log.error("카테고리 {} 기본 크롤링 중 에러 발생", category, e);
                // 한 카테고리 실패해도 다른 카테고리는 계속 진행
            }
        });
    }

    // ===== BFS Deep Crawling 메서드들 =====

    /**
     * BFS Deep Crawling 실행
     */
    private void crawlDeep(CrawlRequestDto request) {
        String category = request.getCategory();

        if ("전체".equals(category)) {
            // 전체 카테고리 BFS Deep Crawling
            crawlAllCategoriesDeep();
        } else {
            // 단일 카테고리 BFS Deep Crawling
            String categoryUrl = NaverNewsSchemas.getCategoryUrls().get(category);
            if (categoryUrl != null) {
                crawlSingleCategoryDeep(category, categoryUrl);
            } else {
                log.error("알 수 없는 카테고리: {}", category);
            }
        }
    }

    /**
     * 전체 카테고리 BFS Deep Crawling
     */
    private void crawlAllCategoriesDeep() {
        log.info("전체 카테고리 BFS Deep Crawling 시작");

        NaverNewsSchemas.getCategoryUrls().forEach((category, url) -> {
            if (!isDeepCrawling.get()) {
                log.info("Deep Crawling 중단 요청으로 작업 종료");
                return;
            }

            try {
                currentCategory.set(category);
                log.info("카테고리 {} BFS Deep Crawling 시작", category);

                crawlSingleCategoryDeep(category, url);

                log.info("카테고리 {} BFS Deep Crawling 완료", category);

                // 카테고리 간 딜레이
                Thread.sleep(3000);

            } catch (Exception e) {
                log.error("카테고리 {} BFS Deep Crawling 중 오류", category, e);
                updateFailureMetrics(category);
            }
        });

        log.info("전체 카테고리 BFS Deep Crawling 완료");
    }

    /**
     * 단일 카테고리 BFS Deep Crawling
     */
    private void crawlSingleCategoryDeep(String category, String categoryUrl) {
        log.info("카테고리 {} BFS Deep Crawling 시작: {}", category, categoryUrl);

        long startTime = System.currentTimeMillis();
        AtomicInteger categoryProcessedCount = new AtomicInteger(0);
        AtomicInteger categorySavedCount = new AtomicInteger(0);

        try {
            // BFS Deep Crawling 실행
            CompletableFuture<List<StreamingCrawlResult>> crawlFuture =
                    crawl4AIClient.crawlNaverNewsBFS(categoryUrl, result -> {
                        // 진행 상황 처리
                        handleStreamingProgress(result, category, categoryProcessedCount, categorySavedCount);
                    });

            // 결과 대기
            List<StreamingCrawlResult> results = crawlFuture.get();

            long crawlTime = System.currentTimeMillis() - startTime;
            int processedPages = categoryProcessedCount.get();
            int savedArticles = categorySavedCount.get();

            // 메트릭 업데이트
            updateDeepCrawlMetrics(category, processedPages, savedArticles, crawlTime);

        } catch (Exception e) {
            log.error("카테고리 {} BFS Deep Crawling 실행 중 오류", category, e);
            updateFailureMetrics(category);
        } finally {
            crawlerMetrics.updateCrawlStatus(category, false);
        }
    }

    /**
     * 스트리밍 진행 상황 처리
     */
    private void handleStreamingProgress(StreamingCrawlResult result, String category,
                                         AtomicInteger categoryProcessedCount,
                                         AtomicInteger categorySavedCount) {
        if (result.isPageSuccessful()) {
            int processed = categoryProcessedCount.incrementAndGet();

            log.debug("카테고리 {} - BFS 페이지 처리 완료 ({}/{}): {}",
                    category,
                    processed,
                    result.getTotalEstimatedPages(),
                    result.getCurrentUrl());

            // 실시간 메트릭 업데이트
            crawlerMetrics.updateCrawlStatus(category, true);
            deepCrawlProcessedCount.incrementAndGet();

            // 기사 저장 시도
            try {
                if (saveStreamingResultToDatabase(result, category)) {
                    int saved = categorySavedCount.incrementAndGet();
                    deepCrawlSavedCount.incrementAndGet();
                    log.debug("카테고리 {} - BFS 기사 저장 완료 ({}번째)", category, saved);
                }
            } catch (Exception e) {
                log.warn("BFS 스트리밍 결과 저장 중 오류: {}", result.getCurrentUrl(), e);
            }
        }
    }

    /**
     * 스트리밍 결과를 데이터베이스에 저장
     */
    @Transactional
    protected boolean saveStreamingResultToDatabase(StreamingCrawlResult streamingResult, String category) {
        try {
            if (!streamingResult.isPageSuccessful() || !streamingResult.hasPageContent()) {
                return false;
            }

            String url = streamingResult.getCurrentUrl();

            // 중복 체크
            if (articleRepository.existsByLink(url)) {
                log.debug("이미 존재하는 BFS 기사 스킵: {}", url);
                return false;
            }

            // 마크다운에서 제목과 내용 추출
            String markdown = streamingResult.getPageResult().getMarkdown();
            if (markdown == null || markdown.trim().isEmpty()) {
                log.debug("BFS 마크다운 내용이 비어있음: {}", url);
                return false;
            }

            // 간단한 제목 추출 (첫 번째 # 헤더 또는 URL에서 추출)
            String title = extractTitleFromMarkdown(markdown, url);
            String content = cleanMarkdownContent(markdown);

            if (content.length() < 100) {
                log.debug("BFS 기사 내용이 너무 짧음: {} ({}자)", url, content.length());
                return false;
            }

            // Article 저장 (기존 saveArticle 메서드 재사용)
            Article article = saveBFSArticle(title, content, url, category, streamingResult);

            log.debug("BFS Deep Crawling 기사 저장 완료: {} - {}", article.getId(), title);
            return true;

        } catch (Exception e) {
            log.error("BFS 스트리밍 결과 저장 중 오류: {}", streamingResult.getCurrentUrl(), e);
            return false;
        }
    }

    /**
     * BFS Deep Crawling 결과로 Article 저장 (기존 메서드와 유사하지만 소스 구분)
     */
    private Article saveBFSArticle(String title, String content, String url, String category,
                                   StreamingCrawlResult streamingResult) {
        String storedDate = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));

        Article.ArticleBuilder builder = Article.builder()
                .title(title)
                .content(content)
                .link(url)
                .category(category)
                .storedDate(storedDate)
                .source("네이버뉴스_BFS") // BFS 크롤링임을 표시
                .publishedAt(LocalDateTime.now())
                .articleTextLength(content.length())
                .viewsCount(0)
                .version(1)
                .isDeleted(false);

        // Deep Crawling 메타데이터 추가
        if (streamingResult.getMetadata() != null) {
            // 스코어를 sentiment로 활용
            if (streamingResult.getMetadata().getScore() != null) {
                builder.sentimentScore(streamingResult.getMetadata().getScore());
            }
        }

        return articleRepository.save(builder.build());
    }

    /**
     * Deep Crawling 메트릭 업데이트
     */
    private void updateDeepCrawlMetrics(String category, int processedPages, int savedArticles, long crawlTime) {
        if (processedPages > 0) {
            crawlerMetrics.incrementCrawlSuccess(category);
            crawlerMetrics.recordCrawlTime(category, crawlTime);
            crawlerMetrics.incrementArticlesProcessed(category, savedArticles);

            log.info("카테고리 {} BFS Deep Crawling 완료 - 처리: {}페이지, 저장: {}기사, 소요시간: {}ms",
                    category, processedPages, savedArticles, crawlTime);
        } else {
            crawlerMetrics.incrementCrawlFailure(category);
            log.warn("카테고리 {} BFS Deep Crawling 실패 - 처리된 페이지 없음", category);
        }

        lastExecutionTimes.put(category, LocalDateTime.now());
    }

    // ===== 유틸리티 메서드들 =====

    /**
     * 마크다운에서 제목 추출
     */
    private String extractTitleFromMarkdown(String markdown, String url) {
        // 첫 번째 # 헤더 찾기
        String[] lines = markdown.split("\n");
        for (String line : lines) {
            line = line.trim();
            if (line.startsWith("# ") && line.length() > 2) {
                return line.substring(2).trim();
            }
        }

        // 헤더가 없으면 첫 번째 의미있는 줄 사용
        for (String line : lines) {
            line = line.trim();
            if (!line.isEmpty() && !line.startsWith("[") && line.length() > 10) {
                return line.length() > 100 ? line.substring(0, 100) + "..." : line;
            }
        }

        // 그것도 없으면 URL에서 추출
        return "뉴스 기사 - " + url.substring(url.lastIndexOf("/") + 1);
    }

    /**
     * 마크다운 내용 정리
     */
    private String cleanMarkdownContent(String markdown) {
        // 기본적인 마크다운 정리
        return markdown
                .replaceAll("\\[([^\\]]+)\\]\\([^)]+\\)", "$1") // 링크 제거
                .replaceAll("#{1,6}\\s*", "")                    // 헤더 마크 제거
                .replaceAll("\\*\\*([^*]+)\\*\\*", "$1")        // 볼드 제거
                .replaceAll("\\*([^*]+)\\*", "$1")              // 이탤릭 제거
                .replaceAll("\\n{3,}", "\n\n")                  // 과도한 줄바꿈 정리
                .trim();
    }

    // ===== 기존 메서드들 (그대로 유지) =====

    private void crawlCategory(String category) throws JsonProcessingException {
        String url = NaverNewsSchemas.getCategoryUrls().get(category);
        Map<String, Object> schema1 = NaverNewsSchemas.getUrlListSchema();
        Crawl4AIRequest getUrlRequest = Crawl4AIRequest.forUrlList(url, schema1);
        Crawl4AIResult urlResult = crawl4AIClient.crawl(getUrlRequest, true);

        String extractedArray = urlResult.getResult().getExtractedContent();
        JsonNode arrayNode = objectMapper.readTree(extractedArray);
        log.info("카테고리 {}: {}개 URL 수집 완료", category, arrayNode.size());
        int savedCount = 0;
        int skippedCount = 0;

        for (int i = 0; i < arrayNode.size(); i++) {
            try {
                JsonNode item = arrayNode.get(i);
                String link = getTextValue(item, "link");
                String title = getTextValue(item, "title");

                // 중복 체크 (이미 구현된 메서드 활용)
                if (articleRepository.existsByLink(link)) {
                    log.debug("이미 존재하는 기사 스킵: {}", link);
                    skippedCount++;
                    continue;
                }

                // 개별 기사 내용 크롤링
                Article savedArticle = crawlAndSaveArticle(link, title, category);
                if (savedArticle != null) {
                    savedCount++;
                    processedCount.incrementAndGet(); // 전체 카운터 증가
                    log.debug("기사 저장 완료: {} - {}", savedArticle.getId(), title);
                }

            } catch (Exception e) {
                log.warn("개별 기사 처리 중 에러 발생 (인덱스: {})", i, e);
                // 개별 기사 실패해도 계속 진행
            }
        }
        log.info("카테고리 {} 처리 완료 - 저장: {}개, 스킵: {}개", category, savedCount, skippedCount);
    }

    private String getTextValue(JsonNode node, String fieldName) {
        if (node.has(fieldName) && !node.get(fieldName).isNull()) {
            String value = node.get(fieldName).asText();
            return value.isEmpty() ? null : value;
        }
        return null;
    }

    @Transactional
    protected Article crawlAndSaveArticle(String link, String title, String category) throws Exception {
        Map<String, Object> schema2 = NaverNewsSchemas.getContentSchema();
        Crawl4AIRequest request2 = Crawl4AIRequest.forArticleContent(link, schema2);
        Crawl4AIResult result2 = crawl4AIClient.crawl(request2, false);

        String extractedArticle = result2.getResult().getExtractedContent();
        JsonNode articleNode = objectMapper.readTree(extractedArticle);

        if (articleNode.size() == 0) {
            log.warn("기사 내용을 추출할 수 없습니다: {}", link);
            return null;
        }

        JsonNode articleItem = articleNode.get(0);
        String content = getTextValue(articleItem, "content");  // schema에 맞게 수정
        String images = getTextValue(articleItem, "image");
        String imageAlts = getTextValue(articleItem, "image_alts");

        // 내용이 없으면 저장하지 않음
        if (content == null || content.trim().isEmpty()) {
            log.warn("기사 내용이 비어있습니다: {}", link);
            return null;
        }

        // Article 저장
        Article article = saveArticle(title, content, link, category);

        // 이미지가 있다면 Media로 저장
        if (images != null && !images.trim().isEmpty()) {
            saveMediaForArticle(article, images, imageAlts);
        }

        return article;
    }

    // saveArticle 메서드 수정
    private Article saveArticle(String title, String content, String link, String category) {
        String storedDate = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));

        Article article = Article.builder()
                .title(title)
                .content(content)
                .link(link)
                .category(category)
                .storedDate(storedDate)
                .source("네이버뉴스")
                .publishedAt(LocalDateTime.now())
                .articleTextLength(content.length())
                .viewsCount(0)
                .version(1)
                .isDeleted(false)
                .build();

        return articleRepository.save(article);
    }

    private void saveMediaForArticle(Article article, String imagesStr, String imageAlts) {
        String storedDate = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));

        // 이미지 URL과 alt 텍스트를 배열로 분리
        String[] imageUrls = imagesStr.split(",");
        String[] altTexts = imageAlts != null ? imageAlts.split(",") : new String[0];

        for (int i = 0; i < imageUrls.length; i++) {
            String trimmedUrl = imageUrls[i].trim();
            if (!trimmedUrl.isEmpty()) {
                // 해당 인덱스에 alt 텍스트가 있으면 사용, 없으면 null
                String caption = i < altTexts.length ? altTexts[i].trim() : null;

                Media media = Media.builder()
                        .article(article)
                        .storedDate(storedDate)
                        .type("image")  // 기존 엔티티의 type 필드 사용
                        .url(trimmedUrl)
                        .caption(caption)  // 기존 엔티티의 caption 필드 사용
                        .build();

                mediaRepository.save(media);
            }
        }
    }


    /**
     * 성공 메트릭 업데이트
     */
    private void updateSuccessMetrics(String category, int articleCount, long crawlTime) {
        crawlerMetrics.incrementCrawlSuccess(category);
        crawlerMetrics.recordCrawlTime(category, crawlTime);
        errorCounts.put(category, 0);
    }

    /**
     * 실패 메트릭 업데이트
     */
    private void updateFailureMetrics(String category) {
        crawlerMetrics.incrementCrawlFailure(category);
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
        isDeepCrawling.set(false);
        currentCategory.set(null);
        crawlStartTime.set(null);
        currentCrawlTask = null;
        visitedUrls.clear(); // 메모리 정리
    }

    /**
     * 성공률 계산
     */
    private double calculateSuccessRate(String category) {
        int errorCount = errorCounts.getOrDefault(category, 0);
        return errorCount == 0 ? 95.0 : Math.max(50.0, 95.0 - (errorCount * 10));
    }
}