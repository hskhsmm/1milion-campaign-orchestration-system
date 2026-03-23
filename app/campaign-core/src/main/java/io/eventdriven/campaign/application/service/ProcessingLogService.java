package io.eventdriven.campaign.application.service;

import lombok.Getter;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 실시간 모니터링용 처리 로그 수집 서비스
 * 메모리 기반 순환 버퍼로 최근 로그만 유지
 */
@Service
public class ProcessingLogService {

    private static final int MAX_LOGS = 1000; // 최대 1000개 로그 유지
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private final ConcurrentLinkedDeque<LogEntry> logs = new ConcurrentLinkedDeque<>();

    /**
     * 로그 추가
     */
    public void addLog(String level, String message) {
        LogEntry entry = new LogEntry(
                LocalDateTime.now().format(FORMATTER),
                level,
                message
        );

        logs.addLast(entry);

        // 최대 개수 초과 시 오래된 로그 제거
        if (logs.size() > MAX_LOGS) {
            logs.removeFirst();
        }
    }

    /**
     * INFO 레벨 로그
     */
    public void info(String message) {
        addLog("INFO", message);
    }

    /**
     * WARN 레벨 로그
     */
    public void warn(String message) {
        addLog("WARN", message);
    }

    /**
     * ERROR 레벨 로그
     */
    public void error(String message) {
        addLog("ERROR", message);
    }

    /**
     * 최근 N개 로그 조회
     */
    public List<LogEntry> getRecentLogs(int limit) {
        List<LogEntry> result = new ArrayList<>(logs);
        Collections.reverse(result); // 최신 로그가 먼저 오도록
        return result.stream()
                .limit(limit)
                .toList();
    }

    /**
     * 모든 로그 조회
     */
    public List<LogEntry> getAllLogs() {
        List<LogEntry> result = new ArrayList<>(logs);
        Collections.reverse(result); // 최신 로그가 먼저 오도록
        return result;
    }

    /**
     * 로그 초기화
     */
    public void clearLogs() {
        logs.clear();
    }

    @Getter
    public static class LogEntry {
        private final String timestamp;
        private final String level;
        private final String message;

        public LogEntry(String timestamp, String level, String message) {
            this.timestamp = timestamp;
            this.level = level;
            this.message = message;
        }
    }
}
