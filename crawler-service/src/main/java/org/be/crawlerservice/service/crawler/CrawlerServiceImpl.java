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
import org.be.crawlerservice.config.SchedulingConfig;
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
    private final AtomicInteger skippedCount = new AtomicInteger(0);

    // Deep Crawling 전용 상태 관리
    private final AtomicInteger deepCrawlProcessedCount = new AtomicInteger(0);
    private final AtomicInteger deepCrawlSavedCount = new AtomicInteger(0);

    // 하이브리드 크롤링을 위한 추가 필드
    private final ConcurrentHashMap<String, Set<String>> visitedUrls = new ConcurrentHashMap<>();
    private static final int WORKER_COUNT = 3; // Consumer 스레드 수

//    private final SchedulingConfig schedulingConfig;
//
//    // 스케줄 관련 상수
//    private static final String DEEP_CRAWL_SCHEDULE_ID = "deep-crawl-scheduled";
//    private static final String BASIC_CRAWL_SCHEDULE_ID = "basic-crawl-scheduled";

//    /**
//     * Deep Crawling 스케줄 시작 (1시간마다)
//     */
//    public CrawlStatusDto startScheduledDeepCrawling(int intervalHours) {
//        if (schedulingConfig.isTaskScheduled(DEEP_CRAWL_SCHEDULE_ID)) {
//            throw new RuntimeException("Deep Crawling 스케줄이 이미 실행 중입니다");
//        }
//
//        // 즉시 첫 실행
//        CrawlStatusDto initialStatus = startDeepCrawling();
//
//        // 스케줄 등록
//        long intervalMillis = intervalHours * 60 * 60 * 1000L;
//        schedulingConfig.scheduleTask(
//                DEEP_CRAWL_SCHEDULE_ID,
//                () -> {
//                    try {
//                        log.info("스케줄된 Deep Crawling 실행 시작");
//                        startDeepCrawling();
//                    } catch (Exception e) {
//                        log.error("스케줄된 Deep Crawling 실행 중 오류", e);
//                    }
//                },
//                intervalMillis
//        );
//
//        log.info("Deep Crawling 스케줄 시작: {}시간마다 실행", intervalHours);
//
//        return CrawlStatusDto.builder()
//                .status(initialStatus.getStatus())
//                .message(String.format("Deep Crawling이 시작되었고, %d시간마다 자동 실행됩니다", intervalHours))
//                .build();
//    }
//
//    /**
//     * Deep Crawling 스케줄 중지
//     */
//    public CrawlStatusDto stopScheduledDeepCrawling() {
//        if (!schedulingConfig.isTaskScheduled(DEEP_CRAWL_SCHEDULE_ID)) {
//            throw new RuntimeException("실행 중인 Deep Crawling 스케줄이 없습니다");
//        }
//
//        schedulingConfig.cancelTask(DEEP_CRAWL_SCHEDULE_ID);
//
//        return CrawlStatusDto.builder()
//                .status(CrawlerStatus.IDLE)
//                .message("Deep Crawling 스케줄이 중지되었습니다")
//                .build();
//    }
//
//    /**
//     * 스케줄 상태 조회
//     */
//    public Map<String, Object> getScheduleStatus() {
//        Map<String, Object> status = new HashMap<>();
//
//        status.put("deep_crawl_scheduled", schedulingConfig.isTaskScheduled(DEEP_CRAWL_SCHEDULE_ID));
//        status.put("basic_crawl_scheduled", schedulingConfig.isTaskScheduled(BASIC_CRAWL_SCHEDULE_ID));
//        status.put("current_crawling_active", isCrawling.get() || isDeepCrawling.get());
//
//        if (lastExecutionTimes.containsKey("deep_crawl")) {
//            status.put("last_deep_crawl_time", lastExecutionTimes.get("deep_crawl"));
//        }
//
//        return status;
//    }

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
    public CrawlStatusDto startDeepCrawling() {
        if (isDeepCrawling.get()) {
            throw new RuntimeException("딥 크롤링이 이미 진행 중입니다");
        }
        // Deep Crawling 상태 설정
        isDeepCrawling.set(true);
        crawlStartTime.set(LocalDateTime.now());
        deepCrawlProcessedCount.set(0);
        deepCrawlSavedCount.set(0);

        // 🔥 CompletableFuture로 비동기 실행
        CompletableFuture.runAsync(() -> {
            try {
                log.info("비동기 스포츠 분야 BFS Deep Crawling 작업 시작");
                //스포츠 분야 크롤링
                crawlSportCategoriesDeep(2, 100);
                log.info("비동기 BFS 일반 Deep Crawling 작업 시작");
                //일반 유형 기사 크롤링
                crawlBasicCategoriesDeep(2, 100);
                log.info("BFS Deep Crawling 작업 완료 - 처리: {}개, 저장: {}개",
                        deepCrawlProcessedCount.get(), deepCrawlSavedCount.get());
                isDeepCrawling.set(false);
            } catch (Exception e) {
                log.error("BFS Deep Crawling 작업 중 에러 발생", e);
            } finally {
                isDeepCrawling.set(false);
            }
        });

        return CrawlStatusDto.builder()
                .status(CrawlerStatus.RUNNING)
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
    private void crawlSportCategoriesDeep(int maxDepth, int maxPages) {
        NaverNewsSchemas.getSportsCategoryUrls().keySet().forEach(category -> {
            try {
                log.info("카테고리 {} 딥크롤링 시작", category);
                String startUrl = NaverNewsSchemas.getSportsCategoryUrls().get(category);
                Map<String, Object> schema = NaverNewsSchemas.getSportsNewsSchema();
                CompletableFuture<List<Crawl4AIResult>> crawlResults = crawl4AIClient.crawlBFSAsync(startUrl,maxDepth,maxPages, schema);
                //딥 크롤링 결과 파싱 시작
                for(Crawl4AIResult result : crawlResults.get()) {
                    if(result == null) continue;
                    log.info("결과 : " + result.getResult().getUrl());
                    String extracted = result.getResult().getExtractedContent();
                    // null 체크 추가
                    if (extracted == null || extracted.trim().isEmpty()) {
                        log.warn("추출된 콘텐츠가 비어있음");
                        continue; // 다음 결과로 넘어감
                    }
                    log.info("추출 데이터 :"+ extracted);
                    JsonNode extractedJson = objectMapper.readTree(extracted);
                    String link = result.getResult().getUrl();
                    for (JsonNode articleNode : extractedJson) {
                        String title = getTextValue(articleNode, "title");
                        String content = getTextValue(articleNode, "content");
                        String author = getTextValue(articleNode, "author");
                        String publishedDateRaw  = getTextValue(articleNode, "published_date");
                        if(title == null || content == null || author == null ) break;
                        // 중복 체크
                        if (articleRepository.existsByLink(link)) {
                            log.debug("이미 존재하는 기사 스킵: {}", link);
                            skippedCount.incrementAndGet();
                            continue;
                        }
                        //기자 이름 추출
                        author = author.split(" ")[0];
                        // Article 저장
                        Article article = saveArticle(title, content, link, category,author);
                    }
                }
                log.info("카테고리 {} 딥 크롤링 완료", category);
            } catch (Exception e) {
                log.error("카테고리 {} 딥 크롤링 중 에러 발생", category, e);
            }
        });
    }
    /**
     * BFS Deep Crawling 실행
     */
    private void crawlBasicCategoriesDeep(int maxDepth, int maxPages) {
        NaverNewsSchemas.getCategoryUrls().keySet().forEach(category -> {
            try {
                String startUrl = NaverNewsSchemas.getCategoryUrls().get(category);
                log.info("url {} 딥크롤링 시작", startUrl);
                Map<String, Object> schema = NaverNewsSchemas.getBasicNewsSchema();
                CompletableFuture<List<Crawl4AIResult>> crawlResults = crawl4AIClient.crawlBFSAsync(startUrl,maxDepth,maxPages, schema);
                //딥 크롤링 결과 파싱 시작
                for(Crawl4AIResult result : crawlResults.get()) {
                    if(result == null) continue;
                    log.info("결과 : " + result.getResult().getUrl());
                    String extracted = result.getResult().getExtractedContent();
                    // null 체크 추가
                    if (extracted == null || extracted.trim().isEmpty()) {
                        log.warn("추출된 콘텐츠가 비어있음");
                        continue; // 다음 결과로 넘어감
                    }
                    //log.info("추출 데이터 :"+ extracted);
                    JsonNode extractedJson = objectMapper.readTree(extracted);
                    String link = result.getResult().getUrl();
                    for (JsonNode articleNode : extractedJson) {
                        String title = getTextValue(articleNode, "title");
                        String content = getTextValue(articleNode, "content");
                        String author = getTextValue(articleNode, "author");
                        String publishedDateRaw  = getTextValue(articleNode, "published_date");
                        if(title == null || content == null || author == null ) break;
                        // 중복 체크
                        if (articleRepository.existsByLink(link)) {
                            log.debug("이미 존재하는 기사 스킵: {}", link);
                            skippedCount.incrementAndGet();
                            continue;
                        }
                        //기자 이름 추출
                        author = author.split(" ")[0];

                        // Article 저장
                        Article article = saveArticle(title, content, link, category,author);
                    }
                }
                log.info("카테고리 {} 딥 크롤링 완료", category);
            } catch (Exception e) {
                log.error("카테고리 {} 딥 크롤링 중 에러 발생", category, e);
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

        for (int i = 0; i < arrayNode.size(); i++) {
            try {
                JsonNode item = arrayNode.get(i);
                String link = getTextValue(item, "link");
                String title = getTextValue(item, "title");

                // 중복 체크 (이미 구현된 메서드 활용)
                if (articleRepository.existsByLink(link)) {
                    log.debug("이미 존재하는 기사 스킵: {}", link);
                    skippedCount.incrementAndGet();
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
    // saveArticle 메서드 수정
    private Article saveArticle(String title, String content, String link, String category, String author) {
        String storedDate = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));

        Article article = Article.builder()
                .title(title)
                .content(content)
                .link(link)
                .author(author)
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