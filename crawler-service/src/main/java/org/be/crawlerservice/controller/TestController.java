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
import org.be.crawlerservice.dto.request.CrawlRequestDto;
import org.be.crawlerservice.dto.response.CrawlStatusDto;
import org.be.crawlerservice.service.crawler.CrawlerService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/test")
@RequiredArgsConstructor
public class TestController {

    private final Crawl4AIClient crawl4AIClient;
    private final CrawlerProperties crawlerProperties;
    private final ObjectMapper objectMapper;
    private final CrawlerService crawlerService;

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
    /**
     * 다양한 네이버 뉴스 스키마 테스트
     */
    @PostMapping("/check")
    public ResponseEntity<CrawlStatusDto> deepCheck() {


        CrawlRequestDto request = CrawlRequestDto.builder()
                .category("IT과학")
                .maxPages(30)  // Deep Crawling은 더 많은 페이지 처리
                .build();


        CrawlStatusDto status = crawlerService.startDeepCrawling(request);
        try {
            String categoryUrl = "https://news.naver.com/section/100";

            crawl4AIClient.crawlNaverNewsBFS(categoryUrl, result -> {
                // 진행 상황 처리
            });


            return ResponseEntity.ok(status);

        } catch (Exception e) {
            log.error("네이버 뉴스 스키마 테스트 실패", e);
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("error", e.getMessage());
            return ResponseEntity.ok(status);
        }
    }

    private String getTextValue(JsonNode node, String fieldName) {
        if (node.has(fieldName) && !node.get(fieldName).isNull()) {
            String value = node.get(fieldName).asText();
            return value.isEmpty() ? null : value;
        }
        return null;
    }
}