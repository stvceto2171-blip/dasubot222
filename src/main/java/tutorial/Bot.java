package tutorial;

import java.util.List;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.CopyMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageReplyMarkup;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

public class Bot extends TelegramLongPollingBot {
    private boolean screaming = false;
    private InlineKeyboardMarkup keyboardM1;
    private InlineKeyboardMarkup keyboardM2;

    private final String botUsername;
    private final String botToken;

    public Bot() {
        this.botUsername = requireEnv("TELEGRAM_BOT_USERNAME");
        this.botToken = requireEnv("TELEGRAM_BOT_TOKEN");

        var next = InlineKeyboardButton.builder()
                .text("Next").callbackData("next")
                .build();

        var back = InlineKeyboardButton.builder()
                .text("Back").callbackData("back")
                .build();

        var url = InlineKeyboardButton.builder()
                .text("Tutorial")
                .url("https://core.telegram.org/bots/api")
                .build();

        keyboardM1 = InlineKeyboardMarkup.builder()
                .keyboardRow(List.of(next))
                .build();

        keyboardM2 = InlineKeyboardMarkup.builder()
                .keyboardRow(List.of(back))
                .keyboardRow(List.of(url))
                .build();
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
        if (update.hasCallbackQuery()) {
            var callback = update.getCallbackQuery();
            var message = callback.getMessage();
            buttonTap(
                    message.getChatId(),
                    callback.getId(),
                    callback.getData(),
                    message.getMessageId()
            );
            return;
        }

        if (!update.hasMessage()) {
            return;
        }

        Message msg = update.getMessage();
        Long id = msg.getChatId();
        var txt = msg.getText();

        if (msg.isCommand()) {
            if (txt.equals("/scream")) {
                screaming = true;
            } else if (txt.equals("/whisper")) {
                screaming = false;
            } else if (txt.equals("/menu")) {
                sendMenu(id, "<b>Menu 1</b>", keyboardM1);
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

    public void sendMenu(Long who, String txt, InlineKeyboardMarkup kb) {
        SendMessage sm = SendMessage.builder()
                .chatId(who.toString())
                .parseMode("HTML")
                .text(txt)
                .replyMarkup(kb)
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

    private void buttonTap(Long id, String queryId, String data, int msgId) {
        EditMessageText newTxt = EditMessageText.builder()
                .chatId(id.toString())
                .messageId(msgId)
                .text("")
                .build();

        EditMessageReplyMarkup newKb = EditMessageReplyMarkup.builder()
                .chatId(id.toString())
                .messageId(msgId)
                .build();

        if (data.equals("next")) {
            newTxt.setText("MENU 2");
            newKb.setReplyMarkup(keyboardM2);
        } else if (data.equals("back")) {
            newTxt.setText("MENU 1");
            newKb.setReplyMarkup(keyboardM1);
        }

        AnswerCallbackQuery close = AnswerCallbackQuery.builder()
                .callbackQueryId(queryId)
                .build();

        try {
            execute(close);
            execute(newTxt);
            execute(newKb);
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
