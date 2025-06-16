package org.be.crawlerservice.service.docker;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

/**
 * Docker 컨테이너 관리 서비스
 * ProcessBuilder를 사용한 Docker 명령어 실행
 */
@Slf4j
@Service
public class DockerContainerManager {

    private static final String CRAWL4AI_CONTAINER = "crawl4ai-server";
    private static final int DEFAULT_TIMEOUT = 60; // 초

    /**
     * 컨테이너 상태 확인
     */
    public boolean isContainerHealthy(String containerName) {
        try {
            log.debug("컨테이너 상태 확인: {}", containerName);

            // docker ps --filter name=containerName --format "{{.Status}}"
            ProcessBuilder pb = new ProcessBuilder(
                    "docker", "ps",
                    "--filter", "name=" + containerName,
                    "--format", "{{.Status}}"
            );

            Process process = pb.start();
            String output = readProcessOutput(process);

            if (process.waitFor() == 0 && output.contains("Up")) {
                log.debug("컨테이너 {} 상태: {}", containerName, output.trim());
                return true;
            }

            log.warn("컨테이너 {} 비정상 상태: {}", containerName, output.trim());
            return false;

        } catch (Exception e) {
            log.error("컨테이너 상태 확인 실패: {}", containerName, e);
            return false;
        }
    }

    /**
     * 컨테이너 재시작
     */
    public boolean restartContainer(String containerName) {
        try {
            log.info("🔄 컨테이너 재시작 시작: {}", containerName);

            ProcessBuilder pb = new ProcessBuilder("docker", "restart", containerName);
            Process process = pb.start();

            String output = readProcessOutput(process);
            int exitCode = process.waitFor();

            if (exitCode == 0) {
                log.info("✅ 컨테이너 재시작 성공: {}", containerName);
                return true;
            } else {
                log.error("❌ 컨테이너 재시작 실패: {} (exit code: {})", containerName, exitCode);
                return false;
            }

        } catch (Exception e) {
            log.error("컨테이너 재시작 중 예외 발생: {}", containerName, e);
            return false;
        }
    }

    /**
     * 컨테이너 준비 상태까지 대기
     */
    public boolean waitForContainerReady(String containerName, int timeoutSeconds) {
        log.info("⏳ 컨테이너 준비 상태 대기: {} (timeout: {}초)", containerName, timeoutSeconds);

        long startTime = System.currentTimeMillis();
        long timeoutMillis = timeoutSeconds * 1000L;

        while (System.currentTimeMillis() - startTime < timeoutMillis) {
            try {
                // 컨테이너 상태 확인
                if (isContainerHealthy(containerName)) {
                    // 추가로 로그 확인 (선택적)
                    if (isContainerLogsHealthy(containerName)) {
                        long elapsed = (System.currentTimeMillis() - startTime) / 1000;
                        log.info("✅ 컨테이너 준비 완료: {} ({}초 소요)", containerName, elapsed);
                        return true;
                    }
                }

                // 3초 대기 후 재확인
                Thread.sleep(3000);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("컨테이너 준비 대기 중단됨: {}", containerName);
                return false;
            }
        }

        log.error("❌ 컨테이너 준비 타임아웃: {} ({}초)", containerName, timeoutSeconds);
        return false;
    }

    /**
     * 컨테이너 로그 상태 확인 (Crawl4AI 서버 시작 메시지 확인)
     */
    private boolean isContainerLogsHealthy(String containerName) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "docker", "logs", "--tail", "10", containerName
            );

            Process process = pb.start();
            String logs = readProcessOutput(process);

            // Crawl4AI 서버가 정상 시작되었는지 확인
            return logs.contains("Server running") ||
                    logs.contains("Application startup complete") ||
                    logs.contains("11235");

        } catch (Exception e) {
            log.debug("로그 확인 실패 (무시): {}", e.getMessage());
            return true; // 로그 확인 실패는 무시하고 진행
        }
    }

    /**
     * Crawl4AI 컨테이너 전용 메서드들
     */
    public boolean isCrawl4AIHealthy() {
        return isContainerHealthy(CRAWL4AI_CONTAINER);
    }

    public boolean restartCrawl4AI() {
        return restartContainer(CRAWL4AI_CONTAINER);
    }

    public boolean waitForCrawl4AIReady() {
        return waitForContainerReady(CRAWL4AI_CONTAINER, DEFAULT_TIMEOUT);
    }

    /**
     * 전체 복구 프로세스 (재시작 + 대기)
     */
    public boolean recoverCrawl4AI() {
        log.info("🚨 Crawl4AI 컨테이너 복구 시작");

        if (!restartCrawl4AI()) {
            log.error("❌ Crawl4AI 재시작 실패");
            return false;
        }

        if (!waitForCrawl4AIReady()) {
            log.error("❌ Crawl4AI 준비 상태 대기 실패");
            return false;
        }

        log.info("✅ Crawl4AI 컨테이너 복구 완료");
        return true;
    }

    /**
     * 컨테이너 메모리 사용량 확인 (예방적 재시작용)
     */
    public double getContainerMemoryUsage(String containerName) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "docker", "stats", "--no-stream", "--format",
                    "{{.MemPerc}}", containerName
            );

            Process process = pb.start();
            String output = readProcessOutput(process);

            if (process.waitFor() == 0 && !output.isEmpty()) {
                // "45.23%" -> 45.23
                String percentage = output.trim().replace("%", "");
                return Double.parseDouble(percentage);
            }

        } catch (Exception e) {
            log.debug("메모리 사용량 확인 실패: {}", e.getMessage());
        }

        return 0.0;
    }

    /**
     * Process 출력 읽기 헬퍼 메서드
     */
    private String readProcessOutput(Process process) throws IOException {
        StringBuilder output = new StringBuilder();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {

            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
        }

        return output.toString();
    }

    /**
     * Docker 명령어 사용 가능 여부 확인
     */
    public boolean isDockerAvailable() {
        try {
            ProcessBuilder pb = new ProcessBuilder("docker", "--version");
            Process process = pb.start();
            return process.waitFor() == 0;
        } catch (Exception e) {
            log.error("Docker 명령어 사용 불가", e);
            return false;
        }
    }
}