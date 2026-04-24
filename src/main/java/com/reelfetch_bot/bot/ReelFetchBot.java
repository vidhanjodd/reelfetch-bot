package com.reelfetch_bot.bot;

import com.reelfetch_bot.dto.DownloadResult;
import com.reelfetch_bot.model.BotUser;
import com.reelfetch_bot.service.UserService;
import com.reelfetch_bot.service.download.DownloadManager;
import com.reelfetch_bot.util.InstagramUrlValidator;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.GetMe;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendVideo;
import org.telegram.telegrambots.meta.api.objects.*;
import org.telegram.telegrambots.meta.generics.BotSession;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;
import org.telegram.telegrambots.meta.TelegramBotsApi;

import java.nio.file.*;
import java.util.Locale;


@Slf4j
@Component
public class ReelFetchBot extends TelegramLongPollingBot {

    private static final long MAX_TELEGRAM_BYTES = 50L * 1024 * 1024; // 50 MB

    private final String botUsername;
    private final DownloadManager downloadManager;
    private final UserService userService;
    private BotSession botSession;

    public ReelFetchBot(
            @Value("${telegram.bot.token}") String token,
            @Value("${telegram.bot.username}") String botUsername,
            DownloadManager downloadManager,
            UserService userService
    ) {
        super(token);
        this.botUsername = botUsername;
        this.downloadManager = downloadManager;
        this.userService = userService;
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
                    """);
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
            boolean sendDirect = result.localFile() != null
                    && result.fileSizeBytes() > 0
                    && result.fileSizeBytes() <= MAX_TELEGRAM_BYTES;

            if (sendDirect) {
                sendVideoFile(chatId, result);
            } else {
                String caption = result.fromCache()
                        ? "✅ Here's your video (cached):"
                        : "✅ File is large — here's your direct link:";
                sendPlainText(chatId, caption + "\n" + result.publicUrl());
            }
        } finally {
            deleteSilently(result.localFile());
        }
    }


    private void sendVideoFile(long chatId, DownloadResult result) {
        try {
            Path localFile = result.localFile();
            if (localFile == null || !Files.exists(localFile)) {
                throw new IllegalStateException("Local file is unavailable for Telegram upload");
            }

            SendVideo sendVideo = SendVideo.builder()
                    .chatId(String.valueOf(chatId))
                    .video(new InputFile(localFile.toFile(), localFile.getFileName().toString()))
                    .caption("✅ Here's your Instagram video!")
                    .supportsStreaming(true)
                    .build();

            execute(sendVideo);
            log.info("Sent video to chatId {} ({}B)", chatId, result.fileSizeBytes());
        } catch (Exception e) {
            log.error("Failed to send video to {}: {}", chatId, e.getMessage(), e);
            sendPlainText(chatId, "✅ Video ready! Download here:\n" + result.publicUrl());
        }
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
