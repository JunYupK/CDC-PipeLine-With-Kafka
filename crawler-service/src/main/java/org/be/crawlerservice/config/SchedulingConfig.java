package org.be.crawlerservice.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.be.crawlerservice.dto.request.CrawlRequestDto;
import org.be.crawlerservice.service.crawler.CrawlerService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Configuration
@EnableScheduling
@RequiredArgsConstructor
public class SchedulingConfig {

    private final CrawlerService crawlerService;

    // 스케줄된 작업들을 관리하기 위한 맵
    private final ConcurrentHashMap<String, ScheduledFuture<?>> scheduledTasks = new ConcurrentHashMap<>();

    /**
     * TaskScheduler 빈 생성
     */
//    @Bean
//    public TaskScheduler taskScheduler() {
//        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
//        scheduler.setPoolSize(5);
//        scheduler.setThreadNamePrefix("crawler-scheduler-");
//        scheduler.initialize();
//        return scheduler;
//    }

    /**
     * 10초마다 시스템 메트릭 업데이트 (기존 코드)
     */
    @Scheduled(fixedRate = 10000)
    public void updateSystemMetrics() {
        try {
            log.trace("시스템 메트릭 업데이트");
        } catch (Exception e) {
            log.warn("시스템 메트릭 업데이트 실패", e);
        }
    }

//    /**
//     * 스케줄된 작업 추가
//     */
//    public void scheduleTask(String taskId, Runnable task, long intervalMillis) {
//        cancelTask(taskId); // 기존 작업이 있으면 취소
//
//        ScheduledFuture<?> future = taskScheduler().scheduleWithFixedDelay(
//                task,
//                intervalMillis
//        );
//
//        scheduledTasks.put(taskId, future);
//        log.info("스케줄 작업 등록: taskId={}, interval={}ms", taskId, intervalMillis);
//    }

    /**
     * 스케줄된 작업 취소
     */
    public void cancelTask(String taskId) {
        ScheduledFuture<?> future = scheduledTasks.remove(taskId);
        if (future != null && !future.isCancelled()) {
            future.cancel(false);
            log.info("스케줄 작업 취소: taskId={}", taskId);
        }
    }

    /**
     * 모든 스케줄된 작업 취소
     */
    public void cancelAllTasks() {
        scheduledTasks.forEach((taskId, future) -> {
            if (!future.isCancelled()) {
                future.cancel(false);
            }
        });
        scheduledTasks.clear();
        log.info("모든 스케줄 작업 취소됨");
    }

    /**
     * 스케줄된 작업 상태 조회
     */
    public boolean isTaskScheduled(String taskId) {
        ScheduledFuture<?> future = scheduledTasks.get(taskId);
        return future != null && !future.isCancelled() && !future.isDone();
    }
}