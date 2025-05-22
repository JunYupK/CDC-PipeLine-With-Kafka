package org.be.crawlerservice.service.crawler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.be.crawlerservice.dto.request.CrawlRequestDto;
import org.be.crawlerservice.dto.response.CrawlStatusDto;
import org.be.crawlerservice.dto.response.StatsResponseDto;
import org.be.crawlerservice.enums.CrawlerStatus;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class CrawlerServiceImpl implements CrawlerService {

    @Override
    public CrawlStatusDto startCrawling(CrawlRequestDto request) {
        log.info("Starting crawling for category: {}", request.getCategory());

        // TODO: 실제 크롤링 로직 구현
        return CrawlStatusDto.builder()
                .status(CrawlerStatus.RUNNING)
                .currentCategory(request.getCategory())
                .startTime(LocalDateTime.now())
                .message("크롤링이 시작되었습니다")
                .build();
    }

    @Override
    public CrawlStatusDto stopCrawling() {
        log.info("Stopping crawling");

        // TODO: 실제 크롤링 중지 로직 구현
        return CrawlStatusDto.builder()
                .status(CrawlerStatus.IDLE)
                .message("크롤링이 중지되었습니다")
                .build();
    }

    @Override
    public CrawlStatusDto getCurrentStatus() {
        // TODO: 실제 상태 조회 로직 구현
        return CrawlStatusDto.builder()
                .status(CrawlerStatus.IDLE)
                .message("크롤링 대기 중")
                .build();
    }

    @Override
    public StatsResponseDto getCrawlingStats() {
        // TODO: 실제 통계 조회 로직 구현
        StatsResponseDto.OverallStats overallStats = StatsResponseDto.OverallStats.builder()
                .totalArticles(0L)
                .totalCategories(0)
                .totalDates(0)
                .averageSuccessRate(0.0)
                .build();

        return StatsResponseDto.builder()
                .overall(overallStats)
                .byCategory(new HashMap<>())
                .recentDaily(new HashMap<>())
                .build();
    }

    @Override
    public Map<String, String> getLastExecutionTimes() {
        // TODO: 실제 마지막 실행 시간 조회 로직 구현
        return new HashMap<>();
    }

    @Override
    public Map<String, Double> getSuccessRates() {
        // TODO: 실제 성공률 조회 로직 구현
        return new HashMap<>();
    }
}