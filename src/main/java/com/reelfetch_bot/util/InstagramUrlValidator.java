package com.reelfetch_bot.util;

import java.net.URI;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class InstagramUrlValidator {

    private static final Pattern INSTAGRAM_PATTERN = Pattern.compile(
            "https?://(www\\.)?instagram\\.com/(reel|p|stories)/[A-Za-z0-9_\\-]+/?",
            Pattern.CASE_INSENSITIVE
    );

    private InstagramUrlValidator() {}

    public static boolean isValid(String url) {
        if (url == null || url.isBlank()) return false;
        String trimmed = url.trim();

        try {
            URI uri = URI.create(trimmed);
            String host = uri.getHost();
            if (host == null) return false;
            if (!host.equalsIgnoreCase("instagram.com") &&
                    !host.equalsIgnoreCase("www.instagram.com")) return false;
        } catch (IllegalArgumentException e) {
            return false;
        }

        return INSTAGRAM_PATTERN.matcher(trimmed).matches();
    }

    public static String sanitize(String url) {
        int q = url.indexOf('?');
        String stripped = q > 0 ? url.substring(0, q) : url;
        return stripped.endsWith("/") ? stripped : stripped + "/";
    }
}