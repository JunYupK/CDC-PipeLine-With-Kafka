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

    /**
     * Crawl4AI 서버 헬스 체크
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> testHealth() {
        try {
            boolean healthy = crawl4AIClient.isHealthy();

            Map<String, Object> response = new HashMap<>();
            response.put("crawl4ai_healthy", healthy);
            response.put("message", healthy ? "Crawl4AI 서버 정상" : "Crawl4AI 서버 오류");

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("헬스체크 실패", e);
            Map<String, Object> response = new HashMap<>();
            response.put("crawl4ai_healthy", false);
            response.put("error", e.getMessage());
            return ResponseEntity.status(503).body(response);
        }
    }
    @PostMapping("/stream-deep-crawl")
    public ResponseEntity<Map<String, Object>> testStreamDeepCrawl() {
        try {
            String startUrl = "https://news.naver.com/section/100";
            Map<String, Object> results = new HashMap<>();
            AtomicInteger processedCount = new AtomicInteger(0);
            AtomicInteger validArticleCount = new AtomicInteger(0);

            log.info("🚀 Stream BFS Deep Crawling 시작: {}", startUrl);

            // Stream 요청 생성
            Map<String, Object> schema = NaverNewsSchemas.getBasicNewsSchema();


            Crawl4AIRequest request = Crawl4AIRequest.forBestFirstNaverNews(startUrl, 1, 10, schema);
            String crawlUrl = crawlerProperties.getCrawl4aiUrl() + "/crawl";
            HttpHeaders headers = createHeaders();
            String requestJson = objectMapper.writeValueAsString(request);
            HttpEntity<String> requestEntity = new HttpEntity<>(requestJson, headers);

            ResponseEntity<String> response = restTemplate.postForEntity(crawlUrl, requestEntity, String.class);

//            // WebClient로 스트림 연결
//            WebClient webClient = WebClient.builder()
//                    .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
//                    .build();
//
//            log.info("📡 스트림 연결 시작...");
//
//            Flux<String> streamFlux = webClient.post()
//                    .uri(crawlUrl)
//                    .header("Content-Type", "application/json")
//                    .header("Authorization", "Bearer " + crawlerProperties.getApiToken())
//                    .bodyValue(requestJson)
//                    .retrieve()
//                    .bodyToFlux(String.class);
//
//            // 실시간 스트림 처리
//            streamFlux
//                    .timeout(Duration.ofSeconds(120))
//                    .doOnSubscribe(subscription -> log.info("🔗 스트림 구독 시작"))
//                    .doOnNext(chunk -> {
//                        int count = processedCount.incrementAndGet();
//
//                        // 🔥 실제 청크 내용 로깅 추가
//                        log.info("📦 청크 {}: 크기={}bytes", count, chunk.length());
//                        log.info("📄 청크 내용: {}", chunk);
//
//                        try {
//                            // JSON 파싱 시도
//                            if (chunk.trim().startsWith("{") || chunk.trim().startsWith("[")) {
//                                JsonNode chunkNode = objectMapper.readTree(chunk);
//
//                                // 🔥 JSON 구조 로깅 추가
//                                log.info("🔍 JSON 구조: {}", chunkNode.toPrettyString());
//
//                                // 배열이면 각 요소 처리
//                                if (chunkNode.isArray()) {
//                                    for (JsonNode item : chunkNode) {
//                                        processArticleNode(item, validArticleCount);
//                                    }
//                                } else {
//                                    processArticleNode(chunkNode, validArticleCount);
//                                }
//                            } else {
//                                log.warn("⚠️ JSON이 아닌 청크: {}", chunk.substring(0, Math.min(chunk.length(), 200)));
//                            }
//
//                        } catch (Exception e) {
//                            log.warn("⚠️ 청크 파싱 실패: {} - 청크: {}", e.getMessage(),
//                                    chunk.substring(0, Math.min(chunk.length(), 200)));
//                        }
//                    })
//                    .doOnComplete(() -> {
//                        log.info("✅ 스트림 완료 - 총 청크: {}, 유효 기사: {}",
//                                processedCount.get(), validArticleCount.get());
//                    })
//                    .doOnError(error -> log.error("❌ 스트림 오류", error))
//                    .blockLast();
//
//            results.put("stream_completed", true);
//            results.put("total_chunks", processedCount.get());
//            results.put("valid_articles", validArticleCount.get());

            return ResponseEntity.ok(results);

        } catch (Exception e) {
            log.error("❌ Stream Deep Crawling 실패", e);
            return ResponseEntity.status(500).body(Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));
        }
    }

    private void processArticleNode(JsonNode node, AtomicInteger validCount) {
        try {
            // 🔥 더 많은 필드 체크
            String title = getTextValue(node, "title");
            String link = getTextValue(node, "link");
            String url = getTextValue(node, "url");
            String content = getTextValue(node, "content");

            log.debug("🔍 노드 분석 - title: {}, link: {}, url: {}, content: {}",
                    title != null ? "있음" : "없음",
                    link != null ? "있음" : "없음",
                    url != null ? "있음" : "없음",
                    content != null ? "있음" : "없음");

            // title과 link가 모두 있는 경우만 출력
            if (title != null && !title.trim().isEmpty() &&
                    (link != null || url != null)) {

                int count = validCount.incrementAndGet();
                String finalLink = link != null ? link : url;

                log.info("📰 기사 {}: {}", count, title);
                log.info("🔗 링크: {}", finalLink);
                log.info("---");

                if (content != null && !content.trim().isEmpty()) {
                    log.debug("📄 내용 미리보기: {}...",
                            content.substring(0, Math.min(content.length(), 100)));
                }
            }

        } catch (Exception e) {
            log.warn("노드 처리 중 오류: {}", e.getMessage());
        }
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