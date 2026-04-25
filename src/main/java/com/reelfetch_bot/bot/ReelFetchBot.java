package com.reelfetch_bot.bot;

import com.reelfetch_bot.dto.DownloadResult;
import com.reelfetch_bot.model.BotUser;
import com.reelfetch_bot.service.UserService;
import com.reelfetch_bot.service.download.DownloadManager;
import com.reelfetch_bot.service.download.YtDlpService;
import com.reelfetch_bot.service.cache.UrlCacheService;
import com.reelfetch_bot.util.InstagramUrlValidator;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.GetMe;
import org.telegram.telegrambots.meta.api.methods.send.SendMediaGroup;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.methods.send.SendVideo;
import org.telegram.telegrambots.meta.api.objects.*;
import org.telegram.telegrambots.meta.api.objects.media.InputMediaPhoto;
import org.telegram.telegrambots.meta.generics.BotSession;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;
import org.telegram.telegrambots.meta.TelegramBotsApi;

import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;


@Slf4j
@Component
public class ReelFetchBot extends TelegramLongPollingBot {

    private static final long MAX_TELEGRAM_BYTES = 50L * 1024 * 1024; // 50 MB

    private final String botUsername;
    private final DownloadManager downloadManager;
    private final UserService userService;
    private final UrlCacheService urlCacheService;
    private BotSession botSession;

    public ReelFetchBot(
            @Value("${telegram.bot.token}") String token,
            @Value("${telegram.bot.username}") String botUsername,
            DownloadManager downloadManager,
            UserService userService,
            UrlCacheService urlCacheService
    ) {
        super(token);
        this.botUsername = botUsername;
        this.downloadManager = downloadManager;
        this.userService = userService;
        this.urlCacheService = urlCacheService;
    }

    @PostConstruct
    void init() {
        log.info("Initializing ReelFetch bot: @{}", botUsername);
        try {
            clearWebhook();
            var me = execute(new GetMe());
            log.info("Telegram bot connected successfully: @{} (id={})", me.getUserName(), me.getId());

            TelegramBotsApi telegramBotsApi = new TelegramBotsApi(DefaultBotSession.class);
            botSession = telegramBotsApi.registerBot(this);
            log.info("Long polling session registered for @{}", botUsername);
        } catch (TelegramApiException e) {
            log.error("Telegram bot startup check failed for @{}: {}", botUsername, e.getMessage(), e);
        }
    }

    @Override
    public String getBotUsername() {
        return botUsername;
    }

    @Override
    public void onUpdateReceived(Update update) {
        if (!update.hasMessage()) {
            log.debug("Ignoring update without message: {}", update.getUpdateId());
            return;
        }

        if (!update.getMessage().hasText()) {
            log.info("Ignoring non-text message for chatId {} update {}", update.getMessage().getChatId(), update.getUpdateId());
            return;
        }

        Message msg = update.getMessage();
        long chatId = msg.getChatId();
        String text = msg.getText().trim();
        String command = extractCommand(text);
        log.info("Received update {} from chatId {} user {} text={}", update.getUpdateId(), chatId,
                msg.getFrom() != null ? msg.getFrom().getUserName() : "unknown", text);

        if ("/start".equals(command)) {
            sendMarkdown(chatId, """
                    👋 Welcome to *ReelFetch*!

                    Send me any Instagram Reel, Post, or Story link and I'll fetch the video for you.

                    Example:
                    `https://www.instagram.com/reel/AbCdEfGhIjK/`
                    """);
            return;
        }

        if ("/help".equals(command)) {
            sendMarkdown(chatId, """
                    *How to use ReelFetch:*

                    1. Copy the link from Instagram
                    2. Paste it here
                    3. Wait a moment — I'll send you the video!

                    Supported: Reels, Posts (video), Stories (public)

                    Commands:
                    /start - Show welcome message
                    /help - Show this help
                    /clear - Clear the URL cache
                    """);
            return;
        }

        if ("/clear".equals(command)) {
            urlCacheService.clearAll();
            sendPlainText(chatId, "Cache cleared.");
            return;
        }

        if (!InstagramUrlValidator.isValid(text)) {
            sendPlainText(chatId, "⚠️ That doesn't look like a valid Instagram URL. Please send a Reel, Post, or Story link.");
            return;
        }

        if (msg.getFrom() == null) {
            sendPlainText(chatId, "⚠️ Cannot process anonymous messages.");
            return;
        }

        String cleanUrl = InstagramUrlValidator.sanitize(text);

        BotUser botUser = userService.upsert(msg.getFrom());

        if (Boolean.TRUE.equals(botUser.getIsBlocked())) {
            log.info("Blocked user {} attempted download of {}", botUser.getTelegramId(), cleanUrl);
            sendPlainText(chatId, "⛔ Your account has been restricted.");
            return;
        }

        sendPlainText(chatId, "⏳ Fetching your video, please wait…");

        downloadManager.processAsync(
                cleanUrl,
                botUser,
                result -> onSuccess(chatId, result),
                errorMsg -> sendPlainText(chatId, errorMsg)
        );
    }


    private void onSuccess(long chatId, DownloadResult result) {
        try {
            if (result.fromCache()) {
                String cacheNote = "(returned from cache - use /clear to force a fresh download)";
                sendPlainText(chatId, captionFor(result.contentType(), true) + "\n" + result.primaryPublicUrl() + "\n\n" + cacheNote);
                return;
            }

            List<Path> files = result.localFiles();
            boolean isImage = result.mediaKind() == YtDlpService.MediaKind.IMAGE;

            if (isImage) {
                sendImages(chatId, files, result);
            } else {
                sendVideos(chatId, files, result);
            }

        } finally {
            result.localFiles().forEach(this::deleteSilently);
        }
    }

    private void sendVideos(long chatId, List<Path> files, DownloadResult result) {
        for (int i = 0; i < files.size(); i++) {
            Path file = files.get(i);
            long size;
            try { size = Files.size(file); } catch (Exception e) { size = 0; }

            String label = files.size() > 1 ? "(" + (i + 1) + "/" + files.size() + ") " : "";
            String caption = "✅ " + label + captionFor(result.contentType(), false);

            if (size > 0 && size <= MAX_TELEGRAM_BYTES) {
                sendVideoFile(chatId, file, size, caption);
            } else {
                sendPlainText(chatId, "✅ " + label + "File too large — link:\n" + result.publicUrls().get(i));
            }
        }
    }

    private void sendImages(long chatId, List<Path> files, DownloadResult result) {
        if (files.size() == 1) {
            sendSinglePhoto(chatId, files.get(0), "✅ " + captionFor(result.contentType(), false));
        } else {
            List<List<Path>> batches = partition(files, 10);
            for (int b = 0; b < batches.size(); b++) {
                List<Path> batch = batches.get(b);
                String label = batches.size() > 1 ? " (part " + (b + 1) + "/" + batches.size() + ")" : "";
                sendPhotoAlbum(chatId, batch, "✅ " + captionFor(result.contentType(), false) + label);
            }
        }
    }

    private void sendSinglePhoto(long chatId, Path file, String caption) {
        try {
            SendPhoto sendPhoto = SendPhoto.builder()
                    .chatId(String.valueOf(chatId))
                    .photo(new InputFile(file.toFile(), file.getFileName().toString()))
                    .caption(caption)
                    .build();
            execute(sendPhoto);
            log.info("Sent photo to chatId {}", chatId);
        } catch (Exception e) {
            log.error("Failed to send photo to {}: {}", chatId, e.getMessage(), e);
            sendPlainText(chatId, caption + "\n(Could not send image directly.)");
        }
    }

    private void sendPhotoAlbum(long chatId, List<Path> files, String caption) {
        try {
            List<InputMediaPhoto> media = new ArrayList<>();
            for (int i = 0; i < files.size(); i++) {
                Path f = files.get(i);
                String attachKey = "file" + i;
                InputMediaPhoto photo = InputMediaPhoto.builder()
                        .media("attach://" + attachKey)
                        .mediaName(attachKey + ".jpg")
                        .newMediaFile(f.toFile())
                        .caption(i == 0 ? caption : null)
                        .build();
                media.add(photo);
            }

            SendMediaGroup album = SendMediaGroup.builder()
                    .chatId(String.valueOf(chatId))
                    .medias(new ArrayList<>(media))
                    .build();
            execute(album);
            log.info("Sent photo album ({} images) to chatId {}", files.size(), chatId);

        } catch (Exception e) {
            log.error("Failed to send photo album to {}: {}", chatId, e.getMessage(), e);
            for (int i = 0; i < files.size(); i++) {
                String label = files.size() > 1 ? " (" + (i + 1) + "/" + files.size() + ")" : "";
                sendSinglePhoto(chatId, files.get(i), caption + label);
            }
        }
    }

    private <T> List<List<T>> partition(List<T> list, int size) {
        List<List<T>> result = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            result.add(list.subList(i, Math.min(i + size, list.size())));
        }
        return result;
    }

    private void sendVideoFile(long chatId, Path localFile, long sizeBytes, String caption) {
        try {
            if (!Files.exists(localFile)) {
                throw new IllegalStateException("Local file missing: " + localFile);
            }

            SendVideo sendVideo = SendVideo.builder()
                    .chatId(String.valueOf(chatId))
                    .video(new InputFile(localFile.toFile(), localFile.getFileName().toString()))
                    .caption(caption)
                    .supportsStreaming(true)
                    .build();

            execute(sendVideo);
            log.info("Sent video to chatId {} ({}B)", chatId, sizeBytes);

        } catch (Exception e) {
            log.error("Failed to send video to {}: {}", chatId, e.getMessage(), e);
            sendPlainText(chatId, caption + "\n(Direct send failed — no link available for this item.)");
        }
    }


    private String captionFor(InstagramUrlValidator.ContentType type, boolean fromCache) {
        String suffix = fromCache ? " (cached)" : "";
        return switch (type) {
            case REEL  -> "Here's your Instagram Reel!" + suffix;
            case POST  -> "Here's your Instagram Post!" + suffix;
            case STORY -> "Here's your Instagram Story!" + suffix;
        };
    }


    private String extractCommand(String text) {
        if (!text.startsWith("/")) return null;

        String token = text.split("\\s+", 2)[0];
        int mentionIndex = token.indexOf('@');
        if (mentionIndex >= 0) {
            String mentionedBot = token.substring(mentionIndex + 1);
            if (!mentionedBot.equalsIgnoreCase(botUsername)) {
                return null;
            }
            token = token.substring(0, mentionIndex);
        }
        return token.toLowerCase(Locale.ROOT);
    }

    private void sendMarkdown(long chatId, String text) {
        SendMessage message = SendMessage.builder()
                .chatId(String.valueOf(chatId))
                .text(text)
                .parseMode("Markdown")
                .build();
        executeMessage(chatId, message);
    }

    private void sendPlainText(long chatId, String text) {
        SendMessage message = SendMessage.builder()
                .chatId(String.valueOf(chatId))
                .text(text)
                .build();
        executeMessage(chatId, message);
    }

    private void executeMessage(long chatId, SendMessage message) {
        try {
            execute(message);
            log.info("Sent text message to chatId {}", chatId);
        } catch (TelegramApiException e) {
            log.error("Failed to send message to {}: {}", chatId, e.getMessage(), e);
        }
    }

    private void deleteSilently(Path path) {
        if (path == null) return;

        try {
            Files.deleteIfExists(path);
        } catch (Exception ignored) {
        }

        try {
            Path parent = path.getParent();
            if (parent != null && Files.isDirectory(parent)) {
                try (var stream = Files.list(parent)) {
                    if (stream.findAny().isEmpty()) {
                        Files.deleteIfExists(parent);
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }
}
