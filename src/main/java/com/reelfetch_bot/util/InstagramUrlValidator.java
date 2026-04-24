package com.reelfetch_bot.util;

import java.util.regex.Pattern;

/**
 * Validates that a URL is a supported Instagram media URL.
 *
 * Supported patterns (MVP):
 *   - Reels  : instagram.com/reel/{id}/
 *   - Posts  : instagram.com/p/{id}/
 *   - Stories: instagram.com/stories/{user}/{id}/
 */
public final class InstagramUrlValidator {

    private static final Pattern INSTAGRAM_PATTERN = Pattern.compile(
            "https?://(www\\.)?instagram\\.com/(reel|p|stories)/[^\\s]+",
            Pattern.CASE_INSENSITIVE
    );

    private InstagramUrlValidator() {}

    public static boolean isValid(String url) {
        if (url == null || url.isBlank()) return false;
        return INSTAGRAM_PATTERN.matcher(url.trim()).find();
    }

    public static String sanitize(String url) {
        // Strip query params that may contain tracking noise
        int q = url.indexOf('?');
        return q > 0 ? url.substring(0, q) : url;
    }
}