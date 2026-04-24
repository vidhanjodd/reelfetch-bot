package com.reelfetch_bot.service.download;

import com.reelfetch_bot.exception.DownloadException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import java.util.UUID;

/**
 * Wraps yt-dlp as a subprocess.
 * yt-dlp must be installed on the host (or in the Docker image).
 *
 * Instagram MVP: yt-dlp handles Reels, Posts (video), and Stories
 * without cookies for public accounts. For private/auth-required content
 * supply a cookies.txt via {@code ytdlp.cookies-file} property.
 */
@Slf4j
@Service
public class YtDlpService {

    @Value("${ytdlp.binary-path:/usr/local/bin/yt-dlp}")
    private String binaryPath;

    @Value("${ytdlp.download-dir:/tmp/reelfetch}")
    private String downloadDir;

    @Value("${ytdlp.cookies-file:}")
    private String cookiesFile;

    /**
     * Download media from {@code url} to a temp directory.
     *
     * @return Path to the downloaded file
     * @throws DownloadException if yt-dlp exits non-zero or times out
     */
    public Path download(String url) throws DownloadException {
        Path outDir = prepareOutputDir();
        String outputTemplate = outDir.resolve("%(id)s.%(ext)s").toString();

        List<String> cmd = buildCommand(url, outputTemplate);
        log.info("Running yt-dlp for URL: {}", url);
        log.debug("Command: {}", cmd);

        try {
            ProcessBuilder pb = new ProcessBuilder(cmd)
                    .redirectErrorStream(true);

            Process process = pb.start();
            String output = new String(process.getInputStream().readAllBytes());
            int exitCode = process.waitFor();

            log.debug("yt-dlp output:\n{}", output);

            if (exitCode != 0) {
                throw new DownloadException("yt-dlp exited with code %d: %s".formatted(exitCode, output));
            }

            return findDownloadedFile(outDir);

        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DownloadException("yt-dlp process error: " + e.getMessage(), e);
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
                "--no-playlist",           // single video only
                "--merge-output-format", "mp4",
                "-f", "bestvideo[ext=mp4]+bestaudio[ext=m4a]/best[ext=mp4]/best",
                "-o", outputTemplate,
                "--no-warnings",
                "--quiet",
                "--print", "after_move:filepath" // print final path to stdout
        ));

        if (cookiesFile != null && !cookiesFile.isBlank()) {
            cmd.add("--cookies");
            cmd.add(cookiesFile);
        }

        cmd.add(url);
        return cmd;
    }

    private Path findDownloadedFile(Path outDir) throws DownloadException {
        try (var stream = Files.list(outDir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .findFirst()
                    .orElseThrow(() -> new DownloadException("No file found after yt-dlp download in " + outDir));
        } catch (IOException e) {
            throw new DownloadException("Error scanning download directory", e);
        }
    }
}