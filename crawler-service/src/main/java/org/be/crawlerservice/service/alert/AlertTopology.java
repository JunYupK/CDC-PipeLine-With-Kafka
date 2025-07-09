package org.be.crawlerservice.service.alert;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.kstream.*;
import org.apache.kafka.streams.state.Stores;
import org.be.crawlerservice.config.AlertConfig;
import org.be.crawlerservice.dto.AlertEvent;
import org.be.crawlerservice.dto.CDCEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.support.serializer.JsonSerde;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class AlertTopology {

    private final AlertConfig alertProperties;
    private final AlertAnalyzer alertAnalyzer;

    @Autowired
    public void buildPipeline(StreamsBuilder streamsBuilder) {

        // JSON Serde 설정
        JsonSerde<CDCEvent> cdcEventSerde = new JsonSerde<>(CDCEvent.class);
        JsonSerde<AlertEvent> alertEventSerde = new JsonSerde<>(AlertEvent.class);

        // 입력 스트림
        KStream<String, CDCEvent> cdcStream = streamsBuilder
                .stream(alertProperties.getTopics().getInput(),
                        Consumed.with(Serdes.String(), cdcEventSerde));

        // 1. Breaking News 알림 처리
        processBreakingAlerts(cdcStream, alertEventSerde);

        // 2. Trending 알림 처리
        processTrendingAlerts(cdcStream, alertEventSerde);

        log.info("Kafka Streams 토폴로지 구성 완료");
    }

    private void processBreakingAlerts(KStream<String, CDCEvent> cdcStream,
                                       JsonSerde<AlertEvent> alertEventSerde) {

        cdcStream
                .filter((key, event) -> "INSERT".equals(event.getOperation()))
                .filter((key, event) -> "articles".equals(event.getTable()))
                .mapValues(this::extractArticleData)
                .filter((key, article) -> article != null)
                .groupBy((key, article) -> extractCategory(article))
                .windowedBy(TimeWindows.of(Duration.ofMinutes(
                        alertProperties.getConditions().getBreaking().getTimeWindowMinutes())))
                .aggregate(
                        () -> new ArrayList<Map<String, Object>>(),
                        (key, article, articles) -> {
                            articles.add(article);
                            return articles;
                        },
                        Materialized.with(Serdes.String(), new JsonSerde<>(ArrayList.class))
                )
                .toStream()
                .filter((windowedKey, articles) ->
                        alertAnalyzer.isBreakingNews(articles, alertProperties.getConditions().getBreaking()))
                .selectKey((windowedKey, articles) -> windowedKey.key()) // Windowed key를 String으로 변환
                .mapValues((key, articles) ->
                        alertAnalyzer.createBreakingAlert(articles, key))
                .to(alertProperties.getTopics().getBreakingAlerts(),
                        Produced.with(Serdes.String(), alertEventSerde));
    }

    private void processTrendingAlerts(KStream<String, CDCEvent> cdcStream,
                                       JsonSerde<AlertEvent> alertEventSerde) {

        cdcStream
                .filter((key, event) -> "INSERT".equals(event.getOperation()))
                .filter((key, event) -> "articles".equals(event.getTable()))
                .mapValues(this::extractArticleData)
                .filter((key, article) -> article != null)
                .flatMapValues(this::extractKeywords)
                .groupBy((key, keyword) -> keyword)
                .windowedBy(TimeWindows.of(Duration.ofMinutes(
                        alertProperties.getConditions().getTrending().getTimeWindowMinutes())))
                .count()
                .toStream()
                .filter((windowedKey, count) ->
                        alertAnalyzer.isTrending(windowedKey.key(), count,
                                alertProperties.getConditions().getTrending()))
                .selectKey((windowedKey, count) -> windowedKey.key()) // Windowed key를 String으로 변환
                .mapValues((key, count) ->
                        alertAnalyzer.createTrendingAlert(key, count))
                .to(alertProperties.getTopics().getTrendingAlerts(),
                        Produced.with(Serdes.String(), alertEventSerde));
    }

    private Map<String, Object> extractArticleData(CDCEvent event) {
        if (event.getAfter() == null) return null;
        return event.getAfter();
    }

    private String extractCategory(Map<String, Object> article) {
        return (String) article.getOrDefault("category", "general");
    }

    private List<String> extractKeywords(Map<String, Object> article) {
        // 제목과 내용에서 키워드 추출 (간단한 구현)
        String title = (String) article.get("title");
        String content = (String) article.get("content");

        if (title == null && content == null) return Collections.emptyList();

        // 키워드 추출 로직 (실제로는 더 정교한 NLP 처리 필요)
        String text = (title != null ? title : "") + " " + (content != null ? content : "");
        return Arrays.asList(text.toLowerCase().split("\\s+"));
    }
}