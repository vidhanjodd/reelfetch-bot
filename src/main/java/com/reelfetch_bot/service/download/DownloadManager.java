package com.reelfetch_bot.service.download;

import com.reelfetch_bot.dto.DownloadResult;
import com.reelfetch_bot.exception.DownloadException;
import com.reelfetch_bot.model.BotUser;
import com.reelfetch_bot.model.DownloadLog;
import com.reelfetch_bot.repository.DownloadLogRepository;
import com.reelfetch_bot.service.cache.UrlCacheService;
import com.reelfetch_bot.service.storage.StorageService;
import com.reelfetch_bot.util.InstagramUrlValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
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


    private DownloadResult resolveMedia(String url, DownloadLog logEntry) throws Exception {
        InstagramUrlValidator.ContentType contentType = InstagramUrlValidator.detectType(url);

        Optional<String> cached = cacheService.get(url);
        if (cached.isPresent()) {
            String r2Key = cached.get();
            log.info("Cache HIT for URL: {} → r2Key: {}", url, r2Key);
            String publicUrl = storageService.publicUrlFor(r2Key);
            return new DownloadResult(
                    List.of(), List.of(r2Key), List.of(publicUrl),
                    0L, true, contentType, YtDlpService.MediaKind.VIDEO);
        }
        log.info("Cache MISS for URL: {}, proceeding to download", url);

        updateStatus(logEntry, DownloadLog.Status.DOWNLOADING);
        YtDlpService.DownloadedMedia downloaded = ytDlpService.downloadAll(url);

        List<String> r2Keys = new ArrayList<>();
        List<String> publicUrls = new ArrayList<>();
        long totalSize = 0;

        try {
            updateStatus(logEntry, DownloadLog.Status.UPLOADING);
            for (Path file : downloaded.files()) {
                long size = Files.size(file);
                totalSize += size;
                String r2Key = storageService.upload(file);
                r2Keys.add(r2Key);
                publicUrls.add(storageService.publicUrlFor(r2Key));
            }

            if (r2Keys.size() == 1) {
                cacheService.put(url, r2Keys.get(0));
            }

            return new DownloadResult(
                    downloaded.files(), r2Keys, publicUrls,
                    totalSize, false, contentType, downloaded.kind());

        } catch (Exception e) {
            downloaded.files().forEach(this::deleteSessionDir);
            throw e;
        }
    }

    private void finaliseLog(DownloadLog entry, DownloadResult result) {
        entry.setStatus(DownloadLog.Status.COMPLETED);
        entry.setR2Key(String.join(",", result.r2Keys()));
        entry.setFileSizeBytes(result.totalSizeBytes());
        entry.setCompletedAt(OffsetDateTime.now());
        logRepository.save(entry);
    }


    private void deleteSessionDir(Path file) {
        if (file == null) return;
        try {
            Files.deleteIfExists(file);
            Path parent = file.getParent();
            if (parent != null) {
                try (var s = Files.list(parent)) {
                    if (s.findAny().isEmpty()) Files.deleteIfExists(parent);
                }
            }
        } catch (Exception ignored) {
            log.warn("Failed to clean up session dir for {}", file);
        }

    }

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
