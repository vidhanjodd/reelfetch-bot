package com.reelfetch_bot.service.download;

import com.reelfetch_bot.exception.DownloadException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.nio.file.*;
import java.util.List;
import java.util.Set;
import java.util.UUID;
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


    public Path download(String url) throws DownloadException {
        try {
            URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new DownloadException("Rejected malformed URL: " + url);
        }

        Path outDir = prepareOutputDir();
        String outputTemplate = outDir.resolve("%(id)s.%(ext)s").toString();

        List<String> cmd = buildCommand(url, outputTemplate);
        log.info("Running yt-dlp for URL: {}", url);
        log.debug("Command: {}", cmd);

        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd)
                    .redirectErrorStream(true);

            process = pb.start();
            String output = new String(process.getInputStream().readAllBytes());

            boolean finished = process.waitFor(120, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new DownloadException("yt-dlp timed out after 120 seconds for URL: " + url);
            }

            int exitCode = process.exitValue();
            log.debug("yt-dlp output:\n{}", output);

            if (exitCode != 0) {
                throw new DownloadException("yt-dlp exited with code %d: %s".formatted(exitCode, output));
            }

            return findDownloadedFile(outDir);

        } catch (IOException e) {
            throw new DownloadException("yt-dlp process error: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) process.destroyForcibly();
            throw new DownloadException("Download interrupted", e);
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

    private List<String> buildCommand(String url, String outputTemplate) {
        var cmd = new java.util.ArrayList<>(List.of(
                binaryPath,
                "--no-playlist",
                "--merge-output-format", "mp4",
                "-f", "bestvideo[ext=mp4]+bestaudio[ext=m4a]/best[ext=mp4]/best",
                "-o", outputTemplate,
                "--no-warnings",
                "--quiet",
                "--print", "after_move:filepath"
        ));

        if (cookiesFile != null && !cookiesFile.isBlank()) {
            cmd.add("--cookies");
            cmd.add(cookiesFile);
        }

        cmd.add(url);
        return cmd;
    }

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("mp4", "webm", "mkv", "m4v");

    private Path findDownloadedFile(Path outDir) throws DownloadException {
        try (var stream = Files.list(outDir)) {
            Path candidate = stream
                    .filter(Files::isRegularFile)
                    .findFirst()
                    .orElseThrow(() -> new DownloadException("No file found after yt-dlp download in " + outDir));

            Path resolved = candidate.toRealPath();
            if (!resolved.startsWith(outDir.toRealPath())) {
                throw new DownloadException("Path traversal detected: " + candidate);
            }

            String name = resolved.getFileName().toString();
            String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase() : "";
            if (!ALLOWED_EXTENSIONS.contains(ext)) {
                throw new DownloadException("Rejected file with disallowed extension: " + ext);
            }

            return resolved;

        } catch (IOException e) {
            throw new DownloadException("Error scanning download directory", e);
        }
    }
}