package com.reelfetch_bot.service;

import com.reelfetch_bot.model.BotUser;
import com.reelfetch_bot.repository.BotUserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.telegram.telegrambots.meta.api.objects.User;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final BotUserRepository repo;

    /**
     * Find or create a {@link BotUser} from an incoming Telegram {@link User}.
     */
    @Transactional
    public BotUser upsert(User telegramUser) {
        return repo.findByTelegramId(telegramUser.getId())
                .map(existing -> {
                    existing.setUsername(telegramUser.getUserName());
                    existing.setFirstName(telegramUser.getFirstName());
                    existing.setLastName(telegramUser.getLastName());
                    return repo.save(existing);
                })
                .orElseGet(() -> {
                    BotUser newUser = BotUser.builder()
                            .telegramId(telegramUser.getId())
                            .username(telegramUser.getUserName())
                            .firstName(telegramUser.getFirstName())
                            .lastName(telegramUser.getLastName())
                            .build();
                    log.info("New user registered: {}", telegramUser.getId());
                    return repo.save(newUser);
                });
    }
}