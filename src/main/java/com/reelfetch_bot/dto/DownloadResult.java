package com.reelfetch_bot.dto;

import java.nio.file.Path;

/**
 * Carries everything needed after a download completes.
 *
 * @param localFile  path on disk (null if served from cache)
 * @param r2Key      object key in R2
 * @param publicUrl  publicly accessible URL
 * @param fileSizeBytes size of the media file in bytes
 * @param fromCache  true when a prior download was reused
 */
public record DownloadResult(
        Path localFile,
        String r2Key,
        String publicUrl,
        long fileSizeBytes,
        boolean fromCache
) {}