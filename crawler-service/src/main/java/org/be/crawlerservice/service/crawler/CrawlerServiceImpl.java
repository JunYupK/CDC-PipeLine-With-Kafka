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

    // 크롤링 상태 관리
    private final AtomicBoolean isCrawling = new AtomicBoolean(false);
    private final AtomicReference<String> currentCategory = new AtomicReference<>();
    private final AtomicReference<LocalDateTime> crawlStartTime = new AtomicReference<>();
    private final Map<String, Integer> errorCounts = new ConcurrentHashMap<>();
    private final Map<String, LocalDateTime> lastExecutionTimes = new ConcurrentHashMap<>();
    private final MediaRepository mediaRepository;
    private CompletableFuture<Void> currentCrawlTask;
    private final AtomicInteger processedCount = new AtomicInteger(0);
    private final AtomicInteger totalCount = new AtomicInteger(0);

    // 하이브리드 크롤링을 위한 추가 필드
    private final ConcurrentHashMap<String, Set<String>> visitedUrls = new ConcurrentHashMap<>();
    private static final int WORKER_COUNT = 3; // Consumer 스레드 수

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
        processedCount.set(0);
        totalCount.set(0);

        // 🔥 CompletableFuture로 비동기 실행
        CompletableFuture.runAsync(() -> {
            try {
                log.info("비동기 크롤링 작업 시작");
                crawlBasic();
                log.info("크롤링 작업 완료 - 총 처리: {}개", processedCount.get());
            } catch (Exception e) {
                log.error("크롤링 작업 중 에러 발생", e);
            } finally {
                isCrawling.set(false);
            }
        });

        return CrawlStatusDto.builder()
                .status(CrawlerStatus.RUNNING)
                .currentCategory(request.getCategory())
                .startTime(crawlStartTime.get())
                .message("크롤링이 시작되었습니다")
                .build();
    }
    // 🔥 비동기 크롤링 메서드
    @Async("crawlingExecutor")
    public void crawlBasicAsync() {
        try {
            log.info("비동기 크롤링 작업 시작");
            crawlBasic();

            // 크롤링 완료 처리
            isCrawling.set(false);
            log.info("크롤링 작업 완료 - 총 처리: {}개", processedCount.get());

        } catch (Exception e) {
            log.error("크롤링 작업 중 에러 발생", e);
            isCrawling.set(false);
        }
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


    private void crawlBasic() {
        NaverNewsSchemas.getCategoryUrls().keySet().forEach(category -> {
            try {
                log.info("카테고리 {} 크롤링 시작", category);
                crawlCategory(category);
                log.info("카테고리 {} 크롤링 완료", category);
            } catch (Exception e) {
                log.error("카테고리 {} 크롤링 중 에러 발생", category, e);
                // 한 카테고리 실패해도 다른 카테고리는 계속 진행
            }
        });
    }
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
     * 하이브리드 방식 크롤링 작업
     */
    private void crawlJobHybrid(String targetCategory) {
        log.info("하이브리드 크롤링 작업 시작: targetCategory={}", targetCategory);

        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));

        // 대상 URL 필터링
        Map<String, String> categoryUrls = NaverNewsSchemas.getCategoryUrls();
        Map<String, String> urlsToProcess = new HashMap<>();

        if (targetCategory == null) {
            urlsToProcess.putAll(categoryUrls);
        } else if (categoryUrls.containsKey(targetCategory)) {
            urlsToProcess.put(targetCategory, categoryUrls.get(targetCategory));
        } else {
            log.warn("Unknown category: {}", targetCategory);
            return;
        }

        // 각 카테고리별로 하이브리드 크롤링 실행
        List<CompletableFuture<Void>> categoryFutures = new ArrayList<>();

        for (Map.Entry<String, String> entry : urlsToProcess.entrySet()) {
            if (Thread.currentThread().isInterrupted()) {
                log.info("크롤링 작업이 중단되었습니다");
                break;
            }

            String category = entry.getKey();
            String url = entry.getValue();

            CompletableFuture<Void> categoryFuture = crawlCategoryHybrid(category, url, timestamp);
            categoryFutures.add(categoryFuture);
        }

        // 모든 카테고리 크롤링 완료 대기
        CompletableFuture.allOf(categoryFutures.toArray(new CompletableFuture[0])).join();

        log.info("전체 하이브리드 크롤링 작업 완료");
    }

    /**
     * 카테고리별 하이브리드 크롤링
     */
    private CompletableFuture<Void> crawlCategoryHybrid(String category, String baseUrl, String timestamp) {
        log.info("카테고리 하이브리드 크롤링 시작: {} - {}", category, timestamp);

        // 크롤링 상태 업데이트
        crawlerMetrics.updateCrawlStatus(category, true);
        currentCategory.set(category);

        // Producer-Consumer 패턴을 위한 큐와 상태 관리
        BlockingQueue<String> urlQueue = new LinkedBlockingQueue<>();
        AtomicBoolean isProducerDone = new AtomicBoolean(false);
        AtomicInteger processedCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);

        // 중복 방지를 위한 카테고리별 visited URLs 초기화
        visitedUrls.computeIfAbsent(category, k -> ConcurrentHashMap.newKeySet());

        long startTime = System.currentTimeMillis();

        // URL 생산자 (Producer)
        CompletableFuture<Void> producer = CompletableFuture.runAsync(() -> {
            try {
                crawlNewsMetadataProducer(baseUrl, category, timestamp, urlQueue, visitedUrls.get(category));
            } catch (Exception e) {
                log.error("URL 생산자 오류: {}", category, e);
            } finally {
                isProducerDone.set(true);
                log.info("URL 생산자 완료: {}", category);
            }
        });

        // 내용 소비자들 (Consumers)
        List<CompletableFuture<Void>> consumers = new ArrayList<>();

        for (int i = 0; i < WORKER_COUNT; i++) {
            final int workerId = i;
            CompletableFuture<Void> consumer = CompletableFuture.runAsync(() -> {
                log.info("Consumer {} 시작: {}", workerId, category);

                while (!isProducerDone.get() || !urlQueue.isEmpty()) {
                    try {
                        String articleUrl = urlQueue.poll(100, TimeUnit.MILLISECONDS);
                        if (articleUrl != null) {
                            Article article = crawlSingleArticleContent(articleUrl, category, timestamp);

                            if (article != null) {
                                saveArticleToDatabase(article, category);
                                processedCount.incrementAndGet();
                                log.debug("Worker {}: 기사 처리 완료 - {}", workerId, article.getTitle());
                            } else {
                                errorCount.incrementAndGet();
                            }

                            // 서버 부하 방지를 위한 딜레이
                            Thread.sleep(1500);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    } catch (Exception e) {
                        log.error("Consumer {} 오류: {}", workerId, category, e);
                        errorCount.incrementAndGet();
                    }
                }

                log.info("Consumer {} 완료: {} - 처리: {}, 오류: {}",
                        workerId, category, processedCount.get(), errorCount.get());
            });

            consumers.add(consumer);
        }

        // 모든 작업 완료 대기
        return CompletableFuture.allOf(
                Stream.concat(Stream.of(producer), consumers.stream()).toArray(CompletableFuture[]::new)
        ).thenRun(() -> {
            long crawlTime = System.currentTimeMillis() - startTime;

            // 메트릭 업데이트
            if (processedCount.get() > 0) {
                updateSuccessMetrics(category, processedCount.get(), crawlTime);
                log.info("{} 카테고리 크롤링 완료: {}개 기사 처리 (소요시간: {}ms)",
                        category, processedCount.get(), crawlTime);
            } else {
                updateFailureMetrics(category);
                log.warn("{} 카테고리 크롤링 실패: 처리된 기사 없음", category);
            }

            // 크롤링 상태 업데이트
            crawlerMetrics.updateCrawlStatus(category, false);
            lastExecutionTimes.put(category, LocalDateTime.now());

            // 해당 카테고리의 visited URLs 정리 (메모리 절약)
            visitedUrls.get(category).clear();
        });
    }

    /**
     * URL 수집 (Producer) - 메타데이터 크롤링 역할
     */
    private void crawlNewsMetadataProducer(String url, String category, String timestamp,
                                           BlockingQueue<String> urlQueue, Set<String> visited) {
        log.debug("메타데이터 크롤링 시작 (Producer): {}", url);

        try {
            // Crawl4AI 요청 생성
            Map<String, Object> schema = null;
            Crawl4AIRequest request = Crawl4AIRequest.forUrlList(url, schema);

            // 실제 크롤링 실행
            Crawl4AIResult result = crawl4AIClient.crawl(request);

            if (!result.isCrawlSuccessful()) {
                log.error("메타데이터 크롤링 실패: {} - {}", url, result.getError());
                return;
            }

            if (!result.hasExtractedContent()) {
                log.warn("추출된 콘텐츠가 없음: {}", url);
                return;
            }

            // JSON 파싱
            List<Map<String, Object>> extractedItems = objectMapper.readValue(
                    result.getResult().getExtractedContent(),
                    new TypeReference<List<Map<String, Object>>>() {}
            );

            // URL 큐에 추가
            int addedCount = 0;
            for (Map<String, Object> item : extractedItems) {
                String title = (String) item.get("title");
                String link = (String) item.get("link");

                if (!StringUtils.hasText(title) || !StringUtils.hasText(link)) {
                    continue;
                }

                String absoluteLink = convertToAbsoluteUrl(link, url);

                // 중복 체크
                if (visited.add(absoluteLink)) {
                    urlQueue.offer(absoluteLink);
                    addedCount++;
                    log.trace("URL 큐에 추가: {}", absoluteLink);
                }
            }

            log.info("메타데이터 크롤링 완료: {}개 URL 추가됨", addedCount);

        } catch (Exception e) {
            log.error("메타데이터 크롤링 실패: {}", url, e);
        }
    }

    /**
     * 단일 기사 내용 크롤링 (Consumer용)
     */
    private Article crawlSingleArticleContent(String articleUrl, String category, String timestamp) {
        log.trace("기사 내용 크롤링: {}", articleUrl);

        try {

            Crawl4AIResult result = null;

            if (result.isCrawlSuccessful() && result.hasExtractedContent()) {
                List<Map<String, Object>> extractedContent = objectMapper.readValue(
                        result.getResult().getExtractedContent(),
                        new TypeReference<List<Map<String, Object>>>() {}
                );

                if (!extractedContent.isEmpty()) {
                    Map<String, Object> contentData = extractedContent.get(0);
                    String content = (String) contentData.get("content");

                    if (StringUtils.hasText(content)) {
                        Article article = Article.builder()
                                .title((String) contentData.getOrDefault("title", "제목 없음"))
                                .link(articleUrl)
                                .content(content.trim())
                                .category(category)
                                .storedDate(timestamp.substring(0, 8))
                                .source("네이버뉴스")
                                .articleTextLength(content.length())
                                .build();

                        return article;
                    }
                }
            }

            log.warn("기사 내용 크롤링 실패: {}", articleUrl);
            return null;

        } catch (Exception e) {
            log.error("기사 내용 크롤링 오류: {}", articleUrl, e);
            return null;
        }
    }

    /**
     * 단일 기사 저장
     */
    private void saveArticleToDatabase(Article article, String category) {
        try {
            // URL 중복 체크
            if (articleService.existsByUrl(article.getLink())) {
                log.debug("이미 존재하는 기사 스킵: {}", article.getLink());
                return;
            }

            long startTime = System.currentTimeMillis();
            articleService.saveArticle(article);
            long saveTime = System.currentTimeMillis() - startTime;

            crawlerMetrics.recordDbOperationTime(saveTime);
            crawlerMetrics.incrementArticlesProcessed(category, 1);

            log.trace("기사 저장 완료: {} ({}ms)", article.getTitle(), saveTime);

        } catch (Exception e) {
            log.error("기사 저장 실패: {}", article.getTitle(), e);
            throw new RuntimeException("DB 저장 실패", e);
        }
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