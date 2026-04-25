package com.reelfetch_bot.service.download;

import com.reelfetch_bot.exception.DownloadException;
import com.reelfetch_bot.util.InstagramUrlValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class YtDlpService {

    @Value("${ytdlp.binary-path:/usr/local/bin/yt-dlp}")
    private String binaryPath;

    @Value("${ytdlp.download-dir:/tmp/reelfetch}")
    private String downloadDir;

    @Value("${ytdlp.cookies-file:}")
    private String cookiesFile;

    @Value("${instaloader.python-path:python3}")
    private String pythonPath;

    private static final Set<String> VIDEO_EXTENSIONS = Set.of("mp4", "webm", "mkv", "m4v");
    private static final Set<String> IMAGE_EXTENSIONS = Set.of("jpg", "jpeg", "png", "webp");
    private static final int TIMEOUT_SECONDS = 120;

    private static final List<String> NO_VIDEO_SIGNALS = List.of(
            "No video formats found",
            "no video",
            "Downloading 0 items"
    );

    public enum MediaKind { VIDEO, IMAGE }

    public record DownloadedMedia(List<Path> files, MediaKind kind) {}


    public DownloadedMedia downloadAll(String url) throws DownloadException {
        validateUrl(url);
        InstagramUrlValidator.ContentType type = InstagramUrlValidator.detectType(url);
        Path outDir = prepareOutputDir();

        VideoResult videoResult = tryVideoDownload(url, outDir, type);

        if (!videoResult.files().isEmpty()) {
            log.info("yt-dlp downloaded {} video(s) for: {}", videoResult.files().size(), url);
            return new DownloadedMedia(videoResult.files(), MediaKind.VIDEO);
        }

        if (videoResult.looksLikeImagePost()) {
            log.info("yt-dlp signals image-only post, falling back to instaloader for: {}", url);
        } else {
            log.info("No video downloaded, trying instaloader as fallback for: {}", url);
        }

        List<Path> images = tryInstalaoderDownload(url, outDir);
        if (!images.isEmpty()) {
            log.info("instaloader downloaded {} image(s) for: {}", images.size(), url);
            return new DownloadedMedia(images, MediaKind.IMAGE);
        }

        throw new DownloadException("No media found. The post may be private or require login.");
    }


    private record VideoResult(List<Path> files, boolean looksLikeImagePost) {}

    private VideoResult tryVideoDownload(String url, Path outDir,
                                         InstagramUrlValidator.ContentType type) {
        try {
            String outputTemplate = outDir.resolve("%(playlist_index)s_%(id)s.%(ext)s").toString();
            List<String> cmd = buildVideoCommand(url, outputTemplate, type);
            log.info("Running yt-dlp [{}] for: {}", type, url);

            String output = runProcess(cmd, url);
            log.debug("yt-dlp output: {}", output);

            List<Path> files = findFiles(outDir, VIDEO_EXTENSIONS);
            log.info("Found {} video file(s) in {}", files.size(), outDir);

            boolean imageSignal = NO_VIDEO_SIGNALS.stream()
                    .anyMatch(sig -> output.toLowerCase().contains(sig.toLowerCase()));

            return new VideoResult(files, imageSignal);

        } catch (DownloadException e) {
            log.warn("yt-dlp video download failed: {}", e.getMessage());
            boolean imageSignal = NO_VIDEO_SIGNALS.stream()
                    .anyMatch(sig -> e.getMessage() != null &&
                            e.getMessage().toLowerCase().contains(sig.toLowerCase()));
            return new VideoResult(List.of(), imageSignal);
        }
    }

    private List<String> buildVideoCommand(String url, String outputTemplate,
                                           InstagramUrlValidator.ContentType type) {
        List<String> cmd = new ArrayList<>(List.of(
                binaryPath,
                "--merge-output-format", "mp4",
                "-f", "bestvideo[ext=mp4]+bestaudio[ext=m4a]/best[ext=mp4]/best",
                "-o", outputTemplate,
                "--ignore-errors",
                "--no-warnings",
                "--quiet"
        ));

        if (type == InstagramUrlValidator.ContentType.REEL) {
            cmd.add("--no-playlist");
        }

        addCookies(cmd);
        cmd.add(url);
        return cmd;
    }


    private List<Path> tryInstalaoderDownload(String url, Path outDir) throws DownloadException {
        String shortcode = extractShortcode(url);
        if (shortcode == null) {
            throw new DownloadException("Could not extract Instagram shortcode from URL: " + url);
        }

        String postArg = "-" + shortcode;

        List<String> cmd = new ArrayList<>(List.of(
                pythonPath, "-m", "instaloader",
                "--no-videos",
                "--no-video-thumbnails",
                "--no-metadata-json",
                "--no-captions",
                "--filename-pattern", "{date_utc:%Y%m%d_%H%M%S}_{shortcode}_{mediacount}",
                "--", postArg
        ));

        log.info("Running instaloader for shortcode: {}", shortcode);
        log.debug("Command: {}", String.join(" ", cmd));

        try {
            ProcessBuilder pb = new ProcessBuilder(cmd)
                    .directory(outDir.toFile())
                    .redirectErrorStream(true);

            Process process = pb.start();
            String output = new String(process.getInputStream().readAllBytes());
            boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            if (!finished) {
                process.destroyForcibly();
                throw new DownloadException("instaloader timed out for: " + url);
            }

            log.debug("instaloader output:\n{}", output);

            List<Path> images = findFilesRecursive(outDir, IMAGE_EXTENSIONS);
            log.info("instaloader found {} image(s) in {}", images.size(), outDir);
            return images;

        } catch (IOException e) {
            throw new DownloadException("instaloader process error: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DownloadException("instaloader interrupted", e);
        }
    }

    private String extractShortcode(String url) {
        Matcher m = Pattern.compile("/(?:p|reel|stories/[^/]+)/([A-Za-z0-9_\\-]+)/?")
                .matcher(url);
        return m.find() ? m.group(1) : null;
    }


    private String runProcess(List<String> cmd, String url) throws DownloadException {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
            Process process = pb.start();
            String output = new String(process.getInputStream().readAllBytes());

            boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new DownloadException("yt-dlp timed out after " + TIMEOUT_SECONDS + "s for: " + url);
            }

            int exitCode = process.exitValue();
            if (exitCode != 0 && exitCode != 1) {
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


    private List<Path> findFiles(Path dir, Set<String> extensions) throws DownloadException {
        try (var stream = Files.list(dir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> matchesExtension(p, extensions))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .map(p -> safeRealPath(p, dir))
                    .filter(Objects::nonNull)
                    .toList();
        } catch (IOException e) {
            throw new DownloadException("Error scanning directory: " + e.getMessage(), e);
        }
    }

    private List<Path> findFilesRecursive(Path dir, Set<String> extensions) throws DownloadException {
        try (var stream = Files.walk(dir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> matchesExtension(p, extensions))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .map(p -> safeRealPath(p, dir))
                    .filter(Objects::nonNull)
                    .toList();
        } catch (IOException e) {
            throw new DownloadException("Error scanning directory recursively: " + e.getMessage(), e);
        }
    }

    private boolean matchesExtension(Path p, Set<String> extensions) {
        String name = p.getFileName().toString().toLowerCase();
        int dot = name.lastIndexOf('.');
        return dot >= 0 && extensions.contains(name.substring(dot + 1));
    }

    private Path safeRealPath(Path p, Path rootDir) {
        try {
            Path resolved = p.toRealPath();
            if (!resolved.startsWith(rootDir.toRealPath())) {
                log.warn("Path traversal rejected: {}", p);
                return null;
            }
            return resolved;
        } catch (IOException e) {
            return null;
        }
    }


    private void validateUrl(String url) throws DownloadException {
        if (url == null || url.isBlank()) {
            throw new DownloadException("URL must not be blank");
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
            throw new DownloadException("Cannot create download directory: " + e.getMessage(), e);
        }
    }

    private void addCookies(List<String> cmd) {
        if (cookiesFile != null && !cookiesFile.isBlank()) {
            cmd.add("--cookies");
            cmd.add(cookiesFile);
        } else {
            cmd.add("--cookies-from-browser");
            cmd.add("brave");
        }
    }
}