package org.be.crawlerservice.service.alert;
import lombok.extern.slf4j.Slf4j;
import org.be.crawlerservice.config.AlertConfig;
import org.be.crawlerservice.dto.AlertEvent;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
@Slf4j
@Service
public class AlertAnalyzer {

    public boolean isBreakingNews(List<Map<String, Object>> articles,
                                  AlertConfig.Breaking config) {

        if (articles.size() < config.getMinSources()) {
            return false;
        }

        // 키워드 빈도 분석
        Map<String, Integer> keywordCount = new HashMap<>();
        Set<String> sources = new HashSet<>();

        for (Map<String, Object> article : articles) {
            String title = (String) article.get("title");
            String source = (String) article.get("media_name");

            if (title != null) {
                Arrays.stream(title.toLowerCase().split("\\s+"))
                        .forEach(keyword -> keywordCount.merge(keyword, 1, Integer::sum));
            }

            if (source != null) {
                sources.add(source);
            }
        }

        // 임계값 초과 키워드가 있고, 충분한 언론사에서 보도됐는지 확인
        boolean hasHighFrequencyKeyword = keywordCount.values().stream()
                .anyMatch(count -> count >= config.getKeywordThreshold());

        return hasHighFrequencyKeyword && sources.size() >= config.getMinSources();
    }

    public boolean isTrending(String keyword, Long count,
                              AlertConfig.Trending config) {

        return count >= config.getMinMentions();
        // TODO: 이전 기간 대비 증가율 계산 로직 추가
    }

    public AlertEvent createBreakingAlert(List<Map<String, Object>> articles, String category) {
        AlertEvent alert = new AlertEvent();
        alert.setId(UUID.randomUUID().toString());
        alert.setType("breaking");
        alert.setCategory(category);
        alert.setTimestamp(LocalDateTime.now());
        alert.setSeverity(0.8); // 높은 중요도

        // 대표 기사 선택
        Map<String, Object> mainArticle = articles.get(0);
        alert.setTitle("속보: " + mainArticle.get("title"));
        alert.setContent((String) mainArticle.get("content"));

        // 언론사 목록
        List<String> sources = articles.stream()
                .map(article -> (String) article.get("media_name"))
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        alert.setSources(sources);

        // 메타데이터
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("article_count", articles.size());
        metadata.put("source_count", sources.size());
        alert.setMetadata(metadata);

        return alert;
    }

    public AlertEvent createTrendingAlert(String keyword, Long count) {
        AlertEvent alert = new AlertEvent();
        alert.setId(UUID.randomUUID().toString());
        alert.setType("trending");
        alert.setTitle("트렌딩 키워드: " + keyword);
        alert.setContent(keyword + " 키워드가 " + count + "회 언급되었습니다.");
        alert.setTimestamp(LocalDateTime.now());
        alert.setSeverity(0.5); // 중간 중요도
        alert.setKeywords(List.of(keyword));

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("mention_count", count);
        metadata.put("keyword", keyword);
        alert.setMetadata(metadata);

        return alert;
    }
}
