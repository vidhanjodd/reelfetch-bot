package com.reelfetch_bot.dto;

import com.reelfetch_bot.service.download.YtDlpService;
import com.reelfetch_bot.util.InstagramUrlValidator;

import java.nio.file.Path;
import java.util.List;

public record DownloadResult(
        List<Path> localFiles,
        List<String> r2Keys,
        List<String> publicUrls,
        long totalSizeBytes,
        boolean fromCache,
        InstagramUrlValidator.ContentType contentType,
        YtDlpService.MediaKind mediaKind
) {
    public String primaryPublicUrl() {
        return publicUrls.isEmpty() ? null : publicUrls.get(0);
    }

    public boolean isMultiFile() {
        return publicUrls.size() > 1;
    }
}