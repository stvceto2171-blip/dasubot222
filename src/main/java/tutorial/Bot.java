package tutorial;

import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.CopyMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

public class Bot extends TelegramLongPollingBot {
    private boolean screaming = false;

    private final String botUsername;
    private final String botToken;

    public Bot() {
        this.botUsername = requireEnv("TELEGRAM_BOT_USERNAME");
        this.botToken = requireEnv("TELEGRAM_BOT_TOKEN");
    }

    @Override
    public String getBotUsername() {
        return botUsername;
    }

    @Override
    public String getBotToken() {
        return botToken;
    }

    @Override
    public void onUpdateReceived(Update update) {
        if (!update.hasMessage()) {
            return;
        }

        Message msg = update.getMessage();
        Long id = msg.getChatId();

        if (msg.isCommand()) {
            if (msg.getText().equals("/scream")) {
                screaming = true;
            } else if (msg.getText().equals("/whisper")) {
                screaming = false;
            }
            return;
        }

        if (screaming) {
            scream(id, msg);
        } else {
            copyMessage(id, msg.getMessageId());
        }
    }

    private void scream(Long id, Message msg) {
        if (msg.hasText()) {
            sendText(id, msg.getText().toUpperCase());
        } else {
            copyMessage(id, msg.getMessageId());
        }
    }

    public void sendText(Long who, String what) {
        SendMessage sm = SendMessage.builder()
                .chatId(who.toString())
                .text(what)
                .build();
        try {
            execute(sm);
        } catch (TelegramApiException e) {
            throw new RuntimeException(e);
        }
    }

    public void copyMessage(Long who, Integer messageId) {
        CopyMessage copyMessage = CopyMessage.builder()
                .chatId(who.toString())
                .fromChatId(who.toString())
                .messageId(messageId)
                .build();
        try {
            execute(copyMessage);
        } catch (TelegramApiException e) {
            throw new RuntimeException(e);
        }
    }

    private static String requireEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required environment variable: " + name);
        }
        return value;
    }
}
