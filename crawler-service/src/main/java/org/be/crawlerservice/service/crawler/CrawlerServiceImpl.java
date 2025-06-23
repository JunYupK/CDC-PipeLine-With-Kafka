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
    private final AtomicBoolean isContinuousDeepCrawling = new AtomicBoolean(false);
    private final AtomicInteger cycleCount = new AtomicInteger(0);

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
    public CrawlStatusDto startDeepCrawling() {
        if (isDeepCrawling.get() || isContinuousDeepCrawling.get()) {
            throw new RuntimeException("딥 크롤링이 이미 진행 중입니다");
        }

        // Deep Crawling 상태 설정
        isDeepCrawling.set(true);
        isContinuousDeepCrawling.set(true);
        crawlStartTime.set(LocalDateTime.now());
        cycleCount.set(0);

        // 🔥 무한 반복 비동기 실행
        CompletableFuture.runAsync(() -> {
            try {
                log.info("🔄 연속 BFS Deep Crawling 시작");

                while (isContinuousDeepCrawling.get()) {
                    long cycleStartTime = System.currentTimeMillis(); // 📊 추가
                    int currentCycle = cycleCount.incrementAndGet();
                    crawlerMetrics.updateCurrentCycle(currentCycle);
                    log.info("📈 크롤링 사이클 {} 시작", currentCycle);

                    try {
                        // 1. 스포츠 카테고리 크롤링
                        if (isContinuousDeepCrawling.get()) {
                            long sportStartTime = System.currentTimeMillis(); // 📊 추가
                            crawlSportCategoriesDeep(2, 300);
                            long sportDuration = System.currentTimeMillis() - sportStartTime; // 📊 추가
                            crawlerMetrics.recordCycleCrawlTime(currentCycle, "sports", sportDuration);
                        }

                        // 일반 카테고리 크롤링
                        if (isContinuousDeepCrawling.get()) {
                            long basicStartTime = System.currentTimeMillis(); // 📊 추가
                            crawlBasicCategoriesDeep(2, 300);
                            long basicDuration = System.currentTimeMillis() - basicStartTime; // 📊 추가
                            crawlerMetrics.recordCycleCrawlTime(currentCycle, "basic", basicDuration); // 📊 추가
                        }


                        // 3. 사이클 간 대기 시간
                        if (isContinuousDeepCrawling.get()) {
                            log.info("⏰ 다음 사이클까지 30분 대기...");
                            Thread.sleep(30 * 60);
                        }
                        // 전체 사이클 시간도 기록
                        long totalCycleDuration = System.currentTimeMillis() - cycleStartTime; // 📊 추가
                        crawlerMetrics.recordCycleCrawlTime(currentCycle, "total", totalCycleDuration); // 📊 추가
                    } catch (InterruptedException e) {
                        log.info("🛑 연속 크롤링이 중단되었습니다 (사이클 {})", currentCycle);
                        Thread.currentThread().interrupt();
                        break;
                    } catch (Exception e) {
                        log.error("❌ 사이클 {} 중 오류 발생", currentCycle, e);

                        // 오류 발생 시 5분 대기 후 다음 사이클 진행
                        try {
                            Thread.sleep(5 * 60 * 1000);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }

                log.info("🏁 연속 BFS Deep Crawling 종료 - 총 사이클: {}", cycleCount.get());

            } catch (Exception e) {
                log.error("❌ 연속 BFS Deep Crawling 작업 중 심각한 오류", e);
            } finally {
                isDeepCrawling.set(false);
                isContinuousDeepCrawling.set(false);
            }
        });

        return CrawlStatusDto.builder()
                .status(CrawlerStatus.RUNNING)
                .startTime(crawlStartTime.get())
                .message("연속 BFS Deep Crawling이 시작되었습니다 (무한 반복)")
                .build();
    }

    @Override
    public CrawlStatusDto stopCrawling() {
        boolean wasCrawling = isCrawling.get() || isDeepCrawling.get() || isContinuousDeepCrawling.get();

        if (!wasCrawling) {
            throw new RuntimeException("실행 중인 크롤링 작업이 없습니다");
        }

        log.info("🛑 크롤링 중지 요청");

        // 연속 크롤링 중지
        if (isContinuousDeepCrawling.get()) {
            log.info("🔄 연속 Deep Crawling 중지 중...");
            isContinuousDeepCrawling.set(false);
        }

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
        } else if (isDeepCrawling.get() || isContinuousDeepCrawling.get()) {
            String message = isContinuousDeepCrawling.get() ?
                    String.format("연속 BFS Deep Crawling 진행 중 (사이클 %d)", cycleCount.get()) :
                    "BFS Deep Crawling이 진행 중입니다";

            return CrawlStatusDto.builder()
                    .status(CrawlerStatus.RUNNING)
                    .currentCategory(currentCategory.get())
                    .startTime(crawlStartTime.get())
                    .processedArticles(deepCrawlProcessedCount.get())
                    .errorCounts(new HashMap<>(errorCounts))
                    .lastExecutionTimes(new HashMap<>(lastExecutionTimes))
                    .message(message)
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
            int savedCount = 0;
            int duplicateCount = 0;
            int nullContentCount = 0;
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
                        nullContentCount++; // 📊 추가
                        crawlerMetrics.incrementDailyNullContentCount(category);
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
                        if(title == null || content == null || author == null ) {
                            nullContentCount++; // 📊 추가
                            crawlerMetrics.incrementDailyNullContentCount(category); // 📊 추가
                            break;
                        }
                        // 중복 체크
                        if (articleRepository.existsByLink(link)) {
                            duplicateCount++; // 📊 추가
                            crawlerMetrics.incrementDailyDuplicateCount(category); // 📊 추가
                            continue;
                        }
                        //기자 이름 추출
                        author = author.split(" ")[0];
                        // Article 저장
                        Article article = saveArticle(title, content, link, category,author);
                        savedCount++; // 📊 추가
                        crawlerMetrics.incrementDailyArticlesSaved(category); // 📊 추가
                        crawlerMetrics.incrementDailyCrawlSuccess(category); // 📊 추가
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
            int savedCount = 0;
            int duplicateCount = 0;
            int nullContentCount = 0;
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
                        nullContentCount++; // 📊 추가
                        crawlerMetrics.incrementDailyNullContentCount(category); // 📊 추가
                        continue; // 다음 결과로 넘어감
                    }
                    JsonNode extractedJson = objectMapper.readTree(extracted);
                    String link = result.getResult().getUrl();
                    for (JsonNode articleNode : extractedJson) {
                        String title = getTextValue(articleNode, "title");
                        String content = getTextValue(articleNode, "content");
                        String author = getTextValue(articleNode, "author");
                        String publishedDateRaw  = getTextValue(articleNode, "published_date");
                        if(title == null || content == null || author == null ) {
                            nullContentCount++; // 📊 추가
                            crawlerMetrics.incrementDailyNullContentCount(category); // 📊 추가
                            break;
                        }
                        // 중복 체크
                        if (articleRepository.existsByLink(link)) {
                            duplicateCount++; // 📊 추가
                            crawlerMetrics.incrementDailyDuplicateCount(category); // 📊 추가
                            continue;
                        }
                        //기자 이름 추출
                        author = author.split(" ")[0];

                        // Article 저장
                        Article article = saveArticle(title, content, link, category,author);
                        savedCount++; // 📊 추가
                        crawlerMetrics.incrementDailyArticlesSaved(category); // 📊 추가
                        crawlerMetrics.incrementDailyCrawlSuccess(category); // 📊 추가
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
     * 실패 메트릭 업데이트
     */
    private void updateFailureMetrics(String category) {
        crawlerMetrics.incrementCrawlFailure();
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
        isContinuousDeepCrawling.set(false);
        currentCategory.set(null);
        crawlStartTime.set(null);
        currentCrawlTask = null;
        visitedUrls.clear();
    }

    /**
     * 성공률 계산
     */
    private double calculateSuccessRate(String category) {
        int errorCount = errorCounts.getOrDefault(category, 0);
        return errorCount == 0 ? 95.0 : Math.max(50.0, 95.0 - (errorCount * 10));
    }
}