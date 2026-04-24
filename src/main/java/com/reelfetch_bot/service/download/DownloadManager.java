package com.reelfetch_bot.service.download;

import com.reelfetch_bot.dto.DownloadResult;
import com.reelfetch_bot.exception.DownloadException;
import com.reelfetch_bot.model.BotUser;
import com.reelfetch_bot.model.DownloadLog;
import com.reelfetch_bot.repository.DownloadLogRepository;
import com.reelfetch_bot.service.cache.UrlCacheService;
import com.reelfetch_bot.service.storage.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Orchestrates: cache check → yt-dlp download → R2 upload → cache store.
 * The {@link #processAsync} method is fire-and-forget; results are delivered
 * via a callback (the Telegram sender lambda).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DownloadManager {

    private final YtDlpService ytDlpService;
    private final StorageService storageService;
    private final UrlCacheService cacheService;
    private final DownloadLogRepository logRepository;

    /**
     * Kick off an async download pipeline.
     *
     * @param url        the Instagram URL
     * @param user       the requesting BotUser (for logging)
     * @param onSuccess  callback receiving {@link DownloadResult}
     * @param onFailure  callback receiving an error message string
     */
    @Async("downloadExecutor")
    public CompletableFuture<Void> processAsync(
            String url,
            BotUser user,
            Consumer<DownloadResult> onSuccess,
            Consumer<String> onFailure
    ) {
        DownloadLog logEntry = createLog(user, url);

        try {
            DownloadResult result = resolveMedia(url, logEntry);
            finaliseLog(logEntry, result);
            onSuccess.accept(result);

        } catch (Exception ex) {
            log.error("Download pipeline failed for URL {}: {}", url, ex.getMessage(), ex);
            failLog(logEntry, ex.getMessage());
            onFailure.accept("❌ Sorry, I couldn't download that. " + humanReadable(ex));
        }

        return CompletableFuture.completedFuture(null);
    }

    // ── pipeline ─────────────────────────────────────────────────────────────

    private DownloadResult resolveMedia(String url, DownloadLog logEntry) throws Exception {

        // 1. Cache hit?
        Optional<String> cached = cacheService.get(url);
        if (cached.isPresent()) {
            String r2Key = cached.get();
            log.info("Cache hit for URL: {}", url);
            String publicUrl = storageService.publicUrlFor(r2Key);
            return new DownloadResult(null, r2Key, publicUrl, 0L, true);
        }

        // 2. Download
        updateStatus(logEntry, DownloadLog.Status.DOWNLOADING);
        Path localFile = ytDlpService.download(url);
        long size = Files.size(localFile);

        // 3. Upload to R2
        updateStatus(logEntry, DownloadLog.Status.UPLOADING);
        String r2Key = storageService.upload(localFile);
        String publicUrl = storageService.publicUrlFor(r2Key);

        // 4. Cache the mapping
        cacheService.put(url, r2Key);

        return new DownloadResult(localFile, r2Key, publicUrl, size, false);
    }

    // ── log helpers ──────────────────────────────────────────────────────────

    private DownloadLog createLog(BotUser user, String url) {
        DownloadLog log = DownloadLog.builder()
                .user(user)
                .originalUrl(url)
                .status(DownloadLog.Status.PENDING)
                .build();
        return logRepository.save(log);
    }

    private void updateStatus(DownloadLog entry, DownloadLog.Status status) {
        entry.setStatus(status);
        logRepository.save(entry);
    }

    private void finaliseLog(DownloadLog entry, DownloadResult result) {
        entry.setStatus(DownloadLog.Status.COMPLETED);
        entry.setR2Key(result.r2Key());
        entry.setFileSizeBytes(result.fileSizeBytes());
        entry.setCompletedAt(OffsetDateTime.now());
        logRepository.save(entry);
    }

    private void failLog(DownloadLog entry, String message) {
        entry.setStatus(DownloadLog.Status.FAILED);
        entry.setErrorMessage(message);
        entry.setCompletedAt(OffsetDateTime.now());
        logRepository.save(entry);
    }

    private String humanReadable(Exception ex) {
        if (ex instanceof DownloadException) return "The URL may be private or unsupported.";
        return "An unexpected error occurred.";
    }
}
