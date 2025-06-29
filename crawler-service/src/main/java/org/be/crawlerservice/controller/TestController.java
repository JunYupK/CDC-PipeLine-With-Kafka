package org.be.crawlerservice.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.be.crawlerservice.client.Crawl4AIClient;
import org.be.crawlerservice.client.schema.NaverNewsSchemas;
import org.be.crawlerservice.config.CrawlerProperties;
import org.be.crawlerservice.dto.crawl4ai.Crawl4AIRequest;
import org.be.crawlerservice.dto.crawl4ai.Crawl4AIResult;
import org.be.crawlerservice.dto.crawl4ai.DeepCrawlingStrategy;
import org.be.crawlerservice.dto.request.CrawlRequestDto;
import org.be.crawlerservice.service.crawler.CrawlerService;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@RestController
@RequestMapping("/api/v1/test")
@RequiredArgsConstructor
public class TestController {

    private final Crawl4AIClient crawl4AIClient;
    private final CrawlerProperties crawlerProperties;
    private final ObjectMapper objectMapper;
    private final CrawlerService crawlerService;
    private final RestTemplate restTemplate;
    private final Semaphore crawlingSemaphore = new Semaphore(4); // 최대 4개 동시 실행
    private final ExecutorService parallelExecutor = Executors.newFixedThreadPool(6); // 카테고리별 처리용
    private final ScheduledExecutorService resourceMonitor = Executors.newSingleThreadScheduledExecutor();



    @PostMapping("/schema-extraction-test")
    public void testSchemaExtraction() {
        try{
            String []startUrls = {"https://m.sports.naver.com/basketball/index","https://m.sports.naver.com/basketball/index"};
            long categoryStartTime = System.currentTimeMillis();
            for(int i=0;i<2;i++){
                String startUrl = startUrls[i];
                CompletableFuture<Void> task = CompletableFuture.runAsync(() -> {
                    crawlingSemaphore.tryAcquire();
                    try {
                        log.info("🏃‍♂️ [{}] 병렬 딥크롤링 시작");
                        CompletableFuture<List<Crawl4AIResult>> crawlResults =
                                crawl4AIClient.crawlBFSAsync(startUrl, 1, 10,  NaverNewsSchemas.getSportsNewsSchema());
                        List<Crawl4AIResult> results = crawlResults.get(20, TimeUnit.MINUTES);
                        System.out.println(results.size());
                        for (Crawl4AIResult result : results) {
                            if (result == null) continue;
                            String extracted = result.getResult().getExtractedContent();
                            if (extracted == null || extracted.trim().isEmpty()) {
                                continue;
                            }

                            JsonNode extractedJson = objectMapper.readTree(extracted);
                            String link = result.getResult().getUrl();

                            for (JsonNode articleNode : extractedJson) {
                                try {
                                    String title = getTextValue(articleNode, "title");
                                    String content = getTextValue(articleNode, "content");
                                    String author = getTextValue(articleNode, "author");
                                    System.out.println(link);
                                    System.out.println(title);

                                    if (title == null || content == null) {
                                        continue;
                                    }



                                    // 기자 이름 처리
                                    if (author != null && author.contains(" ")) {
                                        author = author.split(" ")[0];
                                    }


                                } catch (Exception e) {
                                    log.warn("❌ [{}] 개별 기사 처리 중 오류: {}",e.getMessage());
                                }
                            }
                        }
                        long categoryDuration = System.currentTimeMillis() - categoryStartTime;
                        log.info("✅ [{}] 병렬 딥크롤링 완료 ({}ms)", categoryDuration);
                    }catch (Exception e){
                        log.error("❌ [{}] 병렬 딥크롤링 중 오류", e);
                    }finally {
                        crawlingSemaphore.release();
                    }
                },parallelExecutor);
            }

            long totalCycleDuration = System.currentTimeMillis() - categoryStartTime;
            System.out.println(totalCycleDuration);
            System.out.println("으아아악");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
    @PostMapping("/schema-extraction-test-single")
    public void testSchemaSingleExtraction() {
        try{
            String []startUrls = {"https://m.sports.naver.com/basketball/index","https://m.sports.naver.com/basketball/index"};
            long cycleStartTime = System.currentTimeMillis();
            for(int i=0;i<2;i++){
                String startUrl = startUrls[i];
                CompletableFuture<List<Crawl4AIResult>> crawlResults =
                        crawl4AIClient.crawlBFSAsync(startUrl, 1, 10,  NaverNewsSchemas.getSportsNewsSchema());
                List<Crawl4AIResult> results = crawlResults.get(20, TimeUnit.MINUTES);
                System.out.println(results.size());
                for (Crawl4AIResult result : results) {
                    if (result == null) continue;
                    String extracted = result.getResult().getExtractedContent();
                    if (extracted == null || extracted.trim().isEmpty()) {
                        continue;
                    }

                    JsonNode extractedJson = objectMapper.readTree(extracted);
                    String link = result.getResult().getUrl();

                    for (JsonNode articleNode : extractedJson) {
                        try {
                            String title = getTextValue(articleNode, "title");
                            String content = getTextValue(articleNode, "content");
                            String author = getTextValue(articleNode, "author");
                            System.out.println(link);
                            System.out.println(title);

                            if (title == null || content == null) {
                                continue;
                            }



                            // 기자 이름 처리
                            if (author != null && author.contains(" ")) {
                                author = author.split(" ")[0];
                            }


                        } catch (Exception e) {
                            log.warn("❌ [{}] 개별 기사 처리 중 오류: {}",e.getMessage());
                        }
                    }
                }
            }

            long totalCycleDuration = System.currentTimeMillis() - cycleStartTime;

            System.out.println(totalCycleDuration);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
    private boolean testSchema(String url, Map<String, Object> schema, String schemaName) {
        try {
            Crawl4AIRequest request = Crawl4AIRequest.forArticleContent(url, schema);
            Crawl4AIResult result = crawl4AIClient.crawl(request, false);

            if (result != null && result.getResult() != null) {
                String extracted = result.getResult().getExtractedContent();

                if (extracted != null && !extracted.trim().isEmpty() && !extracted.equals("[]")) {
                    JsonNode node = objectMapper.readTree(extracted);
                    if (node.isArray() && node.size() > 0) {
                        JsonNode article = node.get(0);
                        String title = getTextValue(article, "title");
                        String content = getTextValue(article, "content");

                        boolean hasValidContent = title != null && content != null &&
                                !title.trim().isEmpty() && !content.trim().isEmpty();

                        log.info("✅ [{}] 성공: title={}, content={}",
                                schemaName, title != null ? "O" : "X", content != null ? "O" : "X");
                        return hasValidContent;
                    }
                }
            }

            log.warn("❌ [{}] 실패: 추출 데이터 없음", schemaName);
            return false;

        } catch (Exception e) {
            log.warn("❌ [{}] 오류: {}", schemaName, e.getMessage());
            return false;
        }
    }

    // 개선된 범용 스키마
    private Map<String, Object> getImprovedUniversalSchema() {
        return Map.of(
                "name", "ImprovedUniversal",
                "baseSelector", "body",
                "fields", List.of(
                        Map.of(
                                "name", "title",
                                "selector", "#title_area span, .ArticleHead_article_head_title__YUNFf h2, .media_end_head_headline, h1, [class*='title']",
                                "type", "text"
                        ),
                        Map.of(
                                "name", "content",
                                "selector", "#dic_area, .ArticleContent_comp_article_content__luOFM, .media_end_body_content, #newsct_article, [class*='content']",
                                "type", "text"
                        ),
                        Map.of(
                                "name", "author",
                                "selector", ".ArticleHead_journalist_wrap__nE8S_ em, .media_end_head_journalist em, [class*='author']",
                                "type", "text"
                        )
                )
        );
    }

    /**
     * 다양한 네이버 뉴스 스키마 테스트
     */
    @PostMapping("/naver-news-schemas")
    public ResponseEntity<Map<String, Object>> testNaverNewsSchemas() {
        try {
            String url = "https://news.naver.com/section/100";
            Map<String, Object> results = new HashMap<>();
            // 1. 개선된 스키마 테스트
            log.info("스키마 1: 개선된 네이버 뉴스 스키마 테스트");
            Map<String, Object> schema1 = NaverNewsSchemas.getUrlListSchema();
            Crawl4AIRequest request1 = Crawl4AIRequest.forUrlList(url, schema1);
            Crawl4AIResult result1 = crawl4AIClient.crawl(request1, true);

            String extractedArray = result1.getResult().getExtractedContent();
            JsonNode arrayNode = objectMapper.readTree(extractedArray);

            for(int i =0; i<arrayNode.size(); i++){
                Map<String, Object> schema2 = NaverNewsSchemas.getContentSchema();

                JsonNode item = arrayNode.get(i);
                String link = getTextValue(item, "link");
                String title = getTextValue(item, "title");


                System.out.println((i + 1) + ". " + title);
                System.out.println("  링크: " + link);
                System.out.println();

                Crawl4AIRequest request2 = Crawl4AIRequest.forArticleContent(link, schema2);
                Crawl4AIResult result2 = crawl4AIClient.crawl(request2,false);
                System.out.println(result2.getResult().getExtractedContent());
                break;
            }

            results.put("schema1_improved", Map.of(
                    "success", result1.isCrawlSuccessful(),
                    "has_content", result1.hasExtractedContent(),
                    "content_length", result1.hasExtractedContent() ? result1.getResult().getExtractedContent().length() : 0,
                    "extracted_content", result1.getResult().getExtractedContent()
            ));

            return ResponseEntity.ok(results);

        } catch (Exception e) {
            log.error("네이버 뉴스 스키마 테스트 실패", e);
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("error", e.getMessage());
            return ResponseEntity.status(500).body(errorResponse);
        }
    }


    private String getTextValue(JsonNode node, String fieldName) {
        if (node.has(fieldName) && !node.get(fieldName).isNull()) {
            String value = node.get(fieldName).asText();
            return value.isEmpty() ? null : value;
        }
        return null;
    }
    private HttpHeaders createHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (crawlerProperties.getApiToken() != null && !crawlerProperties.getApiToken().isEmpty()) {
            headers.setBearerAuth(crawlerProperties.getApiToken());
        }
        return headers;
    }
}