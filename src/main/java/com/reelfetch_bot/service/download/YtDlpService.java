package com.reelfetch_bot.service.download;

import com.reelfetch_bot.exception.DownloadException;
import com.reelfetch_bot.util.InstagramUrlValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class YtDlpService {

    @Value("${ytdlp.binary-path:/usr/local/bin/yt-dlp}")
    private String binaryPath;

    @Value("${ytdlp.download-dir:/tmp/reelfetch}")
    private String downloadDir;

    @Value("${ytdlp.cookies-file:}")
    private String cookiesFile;

    private static final Set<String> VIDEO_EXTENSIONS = Set.of("mp4", "webm", "mkv", "m4v");
    private static final Set<String> IMAGE_EXTENSIONS = Set.of("jpg", "jpeg", "png", "webp");
    private static final int TIMEOUT_SECONDS = 120;
    private static final String NO_VIDEO_MARKER = "There is no video in this post";

    public enum MediaKind { VIDEO, IMAGE }

    public record DownloadedMedia(List<Path> files, MediaKind kind) {}

    public DownloadedMedia downloadAll(String url) throws DownloadException {
        validateUrl(url);
        InstagramUrlValidator.ContentType type = InstagramUrlValidator.detectType(url);
        Path outDir = prepareOutputDir();

        try {
            List<Path> videos = tryVideoDownload(url, outDir, type);
            return new DownloadedMedia(videos, MediaKind.VIDEO);
        } catch (DownloadException e) {
            if (e.getMessage() != null && e.getMessage().contains(NO_VIDEO_MARKER)) {
                log.info("No video in post, attempting image download for: {}", url);
                try {
                    List<Path> images = tryImageDownload(url, outDir);
                    return new DownloadedMedia(images, MediaKind.IMAGE);
                } catch (DownloadException ie) {
                    if (ie.getMessage() != null && ie.getMessage().contains(NO_VIDEO_MARKER)) {
                        throw new DownloadException("The post contains no downloadable media (private or unsupported).");
                    }
                    throw ie;
                }
            }
            throw e;
        }
    }



    private List<Path> tryVideoDownload(String url, Path outDir,
                                        InstagramUrlValidator.ContentType type) throws DownloadException {
        String outputTemplate = outDir.resolve("%(playlist_index)s_%(id)s.%(ext)s").toString();
        List<String> cmd = buildVideoCommand(url, outputTemplate, type);
        log.info("Running yt-dlp [video/{}] for: {}", type, url);
        runProcess(cmd, url);
        return findFiles(outDir, VIDEO_EXTENSIONS);
    }

    private List<String> buildVideoCommand(String url, String outputTemplate,
                                           InstagramUrlValidator.ContentType type) {
        var cmd = new java.util.ArrayList<String>();
        cmd.add(binaryPath);
        cmd.add("--merge-output-format"); cmd.add("mp4");
        cmd.add("-f"); cmd.add("bestvideo[ext=mp4]+bestaudio[ext=m4a]/best[ext=mp4]/best");
        cmd.add("-o"); cmd.add(outputTemplate);
        cmd.add("--no-warnings");
        cmd.add("--quiet");

        if (type == InstagramUrlValidator.ContentType.REEL) {
            cmd.add("--no-playlist");
        }

        addCookies(cmd);
        cmd.add(url);
        return cmd;
    }


    private List<Path> tryImageDownload(String url, Path outDir) throws DownloadException {
        List<String> jsonCmd = new java.util.ArrayList<>();
        jsonCmd.add(binaryPath);
        jsonCmd.add("--dump-json");
        jsonCmd.add("--no-warnings");
        jsonCmd.add("--quiet");
        addCookies((ArrayList<String>) jsonCmd);
        jsonCmd.add(url);

        String json = runProcessAndCapture(jsonCmd, url);

        List<String> imageUrls = extractImageUrls(json);
        if (imageUrls.isEmpty()) {
            throw new DownloadException("No images found in post metadata for: " + url);
        }

        log.info("Found {} image(s) to download for: {}", imageUrls.size(), url);

        List<Path> downloaded = new java.util.ArrayList<>();
        for (int i = 0; i < imageUrls.size(); i++) {
            String imageUrl = imageUrls.get(i);
            Path dest = outDir.resolve(i + "_image.jpg");
            downloadHttpFile(imageUrl, dest);
            downloaded.add(dest);
        }

        return downloaded;
    }

    private String runProcessAndCapture(List<String> cmd, String url) throws DownloadException {
        try {
            Process process = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes());

            boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new DownloadException("yt-dlp --dump-json timed out for: " + url);
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                throw new DownloadException("yt-dlp exited with code %d: %s".formatted(exitCode, output));
            }

            return output;
        } catch (IOException e) {
            throw new DownloadException("yt-dlp process error: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DownloadException("yt-dlp interrupted", e);
        }
    }

    private List<String> extractImageUrls(String json) throws DownloadException {
        List<String> urls = new java.util.ArrayList<>();
        try {
            for (String line : json.split("\n")) {
                line = line.strip();
                if (line.isEmpty() || !line.startsWith("{")) continue;

                if (line.contains("\"entries\"")) {
                    urls.addAll(extractFromEntries(line));
                } else {
                    String imageUrl = extractBestImageUrl(line);
                    if (imageUrl != null) urls.add(imageUrl);
                }
            }
        } catch (Exception e) {
            throw new DownloadException("Failed to parse yt-dlp JSON: " + e.getMessage(), e);
        }
        return urls;
    }

    private List<String> extractFromEntries(String json) {
        List<String> urls = new java.util.ArrayList<>();
        int entriesStart = json.indexOf("\"entries\"");
        if (entriesStart < 0) return urls;

        int pos = entriesStart;
        while ((pos = json.indexOf("{", pos + 1)) >= 0) {
            int depth = 0;
            int end = pos;
            for (int i = pos; i < json.length(); i++) {
                if (json.charAt(i) == '{') depth++;
                else if (json.charAt(i) == '}') {
                    depth--;
                    if (depth == 0) { end = i; break; }
                }
            }
            if (end > pos) {
                String entry = json.substring(pos, end + 1);
                String imageUrl = extractBestImageUrl(entry);
                if (imageUrl != null) urls.add(imageUrl);
                pos = end;
            } else {
                break;
            }
        }
        return urls;
    }


    private String extractBestImageUrl(String json) {
        String url = extractJsonString(json, "url");
        if (url != null && (url.startsWith("http") && !url.endsWith(".mp4") && !url.endsWith(".webm"))) {
            return url;
        }
        return extractJsonString(json, "thumbnail");
    }


    private String extractJsonString(String json, String key) {
        String search = "\"" + key + "\":";
        int idx = json.indexOf(search);
        if (idx < 0) return null;

        int valueStart = json.indexOf("\"", idx + search.length());
        if (valueStart < 0) return null;

        StringBuilder sb = new StringBuilder();
        for (int i = valueStart + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                sb.append(json.charAt(i + 1));
                i++;
            } else if (c == '"') {
                break;
            } else {
                sb.append(c);
            }
        }
        return sb.isEmpty() ? null : sb.toString();
    }


    private void downloadHttpFile(String imageUrl, Path dest) throws DownloadException {
        try {
            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                    .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
                    .connectTimeout(java.time.Duration.ofSeconds(15))
                    .build();

            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(URI.create(imageUrl))
                    .timeout(java.time.Duration.ofSeconds(60))
                    .header("User-Agent", "Mozilla/5.0 (compatible; ReelFetchBot/1.0)")
                    .GET()
                    .build();

            java.net.http.HttpResponse<Path> response = client.send(
                    request,
                    java.net.http.HttpResponse.BodyHandlers.ofFile(dest)
            );

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new DownloadException("HTTP " + response.statusCode() + " downloading image: " + imageUrl);
            }

            log.debug("Downloaded image {} → {} ({} bytes)", imageUrl, dest, Files.size(dest));

        } catch (IOException e) {
            throw new DownloadException("Failed to download image: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DownloadException("Image download interrupted", e);
        }
    }


    private void validateUrl(String url) throws DownloadException {
        try {
            URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new DownloadException("Rejected malformed URL: " + url);
        }
    }

    private Path prepareOutputDir() throws DownloadException {
        try {
            Path base = Path.of(downloadDir);
            Files.createDirectories(base);
            Path sessionDir = base.resolve(UUID.randomUUID().toString());
            Files.createDirectories(sessionDir);
            return sessionDir;
        } catch (IOException e) {
            throw new DownloadException("Cannot create download directory", e);
        }
    }

    private void addCookies(java.util.ArrayList<String> cmd) {
        if (cookiesFile != null && !cookiesFile.isBlank()) {
            cmd.add("--cookies");
            cmd.add(cookiesFile);
        }
    }

    private void runProcess(List<String> cmd, String url) throws DownloadException {
        Process process = null;
        try {
            process = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes());

            boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new DownloadException("yt-dlp timed out after " + TIMEOUT_SECONDS + "s for: " + url);
            }

            int exitCode = process.exitValue();
            log.debug("yt-dlp output:\n{}", output);

            if (exitCode != 0) {
                throw new DownloadException("yt-dlp exited with code %d: %s".formatted(exitCode, output));
            }

        } catch (IOException e) {
            throw new DownloadException("yt-dlp process error: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) process.destroyForcibly();
            throw new DownloadException("Download interrupted", e);
        }
    }

    private List<Path> findFiles(Path outDir, Set<String> allowedExtensions) throws DownloadException {
        try (var stream = Files.list(outDir)) {
            List<Path> files = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        String ext = name.contains(".")
                                ? name.substring(name.lastIndexOf('.') + 1).toLowerCase()
                                : "";
                        return allowedExtensions.contains(ext);
                    })
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .map(p -> {
                        try {
                            Path resolved = p.toRealPath();
                            if (!resolved.startsWith(outDir.toRealPath())) {
                                log.warn("Path traversal rejected: {}", p);
                                return null;
                            }
                            return resolved;
                        } catch (IOException e) {
                            return null;
                        }
                    })
                    .filter(p -> p != null)
                    .toList();

            log.info("Found {} file(s) in {}", files.size(), outDir);
            return files;

        } catch (IOException e) {
            throw new DownloadException("Error scanning download directory", e);
        }
    }
}

