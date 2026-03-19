package tutorial;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendChatAction;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

public class Bot extends TelegramLongPollingBot {
    private static final int TELEGRAM_TEXT_LIMIT = 4096;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String WEEKDAYS_RESPONSE =
            "Russian: понедельник, вторник, среда, четверг, пятница, суббота, воскресенье.\n"
                    + "French: lundi, mardi, mercredi, jeudi, vendredi, samedi, dimanche.";

    private final ExecutorService responseExecutor = Executors.newCachedThreadPool();
    private final OpenClawConfig openClawConfig = OpenClawConfig.load();
    private final OpenClawClient openClawClient = new OpenClawClient(openClawConfig);

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

        Message message = update.getMessage();
        Long chatId = message.getChatId();

        if (message.isCommand()) {
            handleCommand(chatId, message.getText());
            return;
        }

        if (!message.hasText() || message.getText().isBlank()) {
            sendText(chatId, "Send a text message and I will forward it to OpenClaw.");
            return;
        }

        responseExecutor.submit(() -> handlePrompt(chatId, message.getText()));
    }

    public void sendText(Long chatId, String text) {
        for (String part : splitMessage(text)) {
            SendMessage message = SendMessage.builder()
                    .chatId(chatId.toString())
                    .text(part)
                    .build();
            try {
                execute(message);
            } catch (TelegramApiException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private void handleCommand(Long chatId, String commandText) {
        String command = firstToken(commandText).toLowerCase(Locale.ROOT);
        switch (command) {
            case "/start" -> sendText(chatId,
                    "Connected to OpenClaw.\nModel: " + openClawConfig.defaultModel()
                            + "\nCommands: /help, /model, /weekdays"
                            + "\nSend any text message to chat through your OpenClaw session.");
            case "/help" -> sendText(chatId,
                    "Commands:\n/start\n/help\n/model\n/weekdays\n\nSend any text message to chat with "
                            + openClawConfig.defaultModel() + ".");
            case "/model" -> sendText(chatId, "OpenClaw default model: " + openClawConfig.defaultModel());
            case "/weekdays" -> sendText(chatId, WEEKDAYS_RESPONSE);
            default -> sendText(chatId, "Unknown command. Use /help.");
        }
    }

    private void handlePrompt(Long chatId, String prompt) {
        sendTyping(chatId);
        try {
            String reply = openClawClient.ask(chatId, prompt);
            sendText(chatId, reply);
        } catch (Exception e) {
            sendText(chatId, "OpenClaw request failed: " + e.getMessage());
        }
    }

    private void sendTyping(Long chatId) {
        SendChatAction action = SendChatAction.builder()
                .chatId(chatId.toString())
                .action("typing")
                .build();
        try {
            execute(action);
        } catch (TelegramApiException e) {
            throw new RuntimeException(e);
        }
    }

    private static List<String> splitMessage(String text) {
        List<String> parts = new ArrayList<>();
        String normalized = Objects.requireNonNullElse(text, "").trim();
        if (normalized.isEmpty()) {
            parts.add("(empty response)");
            return parts;
        }

        int start = 0;
        while (start < normalized.length()) {
            int end = Math.min(start + TELEGRAM_TEXT_LIMIT, normalized.length());
            if (end < normalized.length()) {
                int splitAt = normalized.lastIndexOf('\n', end);
                if (splitAt <= start) {
                    splitAt = normalized.lastIndexOf(' ', end);
                }
                if (splitAt > start) {
                    end = splitAt;
                }
            }

            parts.add(normalized.substring(start, end).trim());
            start = end;
            while (start < normalized.length() && Character.isWhitespace(normalized.charAt(start))) {
                start++;
            }
        }
        return parts;
    }

    private static String firstToken(String value) {
        int firstSpace = value.indexOf(' ');
        return firstSpace >= 0 ? value.substring(0, firstSpace) : value;
    }

    private static String requireEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required environment variable: " + name);
        }
        return value;
    }

    static final class OpenClawConfig {
        private final Path configPath;
        private final String defaultModel;
        private final String openClawCommand;
        private final Duration timeout;

        private OpenClawConfig(Path configPath, String defaultModel, String openClawCommand, Duration timeout) {
            this.configPath = configPath;
            this.defaultModel = defaultModel;
            this.openClawCommand = openClawCommand;
            this.timeout = timeout;
        }

        static OpenClawConfig load() {
            Path configPath = resolveConfigPath();
            JsonNode root = readJson(configPath);
            String configuredModel = textAt(root, "/agents/defaults/model/primary");
            String defaultModel = configuredModel == null || configuredModel.isBlank()
                    ? "unknown"
                    : configuredModel;

            String openClawCommand = envOrDefault("OPENCLAW_COMMAND", "openclaw");
            long timeoutSeconds = parseTimeoutSeconds(envOrDefault("OPENCLAW_TIMEOUT_SECONDS", "180"));

            return new OpenClawConfig(configPath, defaultModel, openClawCommand, Duration.ofSeconds(timeoutSeconds));
        }

        Path configPath() {
            return configPath;
        }

        String defaultModel() {
            return defaultModel;
        }

        String openClawCommand() {
            return openClawCommand;
        }

        Duration timeout() {
            return timeout;
        }

        private static Path resolveConfigPath() {
            String configured = System.getenv("OPENCLAW_CONFIG_PATH");
            if (configured != null && !configured.isBlank()) {
                return Path.of(configured);
            }
            return Path.of(System.getProperty("user.home"), ".openclaw", "openclaw.json");
        }

        private static JsonNode readJson(Path path) {
            try {
                return OBJECT_MAPPER.readTree(Files.readString(path));
            } catch (IOException e) {
                throw new IllegalStateException("Unable to read OpenClaw config at " + path, e);
            }
        }

        private static String textAt(JsonNode root, String pointer) {
            JsonNode node = root.at(pointer);
            return node.isMissingNode() || node.isNull() ? null : node.asText();
        }

        private static String envOrDefault(String name, String fallback) {
            String value = System.getenv(name);
            return value == null || value.isBlank() ? fallback : value;
        }

        private static long parseTimeoutSeconds(String rawValue) {
            try {
                return Long.parseLong(rawValue);
            } catch (NumberFormatException e) {
                throw new IllegalStateException("OPENCLAW_TIMEOUT_SECONDS must be a whole number.");
            }
        }
    }

    static final class OpenClawClient {
        private final OpenClawConfig config;

        private OpenClawClient(OpenClawConfig config) {
            this.config = config;
        }

        String ask(Long chatId, String prompt) throws IOException, InterruptedException {
            ProcessBuilder processBuilder = new ProcessBuilder(
                    config.openClawCommand(),
                    "--no-color",
                    "agent",
                    "--json",
                    "--session-id",
                    "telegram-chat-" + chatId,
                    "--channel",
                    "telegram",
                    "--message",
                    prompt
            );
            processBuilder.redirectErrorStream(true);

            Process process = processBuilder.start();
            boolean finished = process.waitFor(config.timeout().toSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new IllegalStateException(
                        "OpenClaw timed out after " + config.timeout().toSeconds() + "s. "
                                + "Make sure the OpenClaw gateway is running.");
            }

            String output = new String(process.getInputStream().readAllBytes());
            if (process.exitValue() != 0) {
                throw new IllegalStateException(compactFailure(output));
            }

            return parseReply(output);
        }

        private String parseReply(String output) throws IOException {
            String jsonText = extractJson(output);
            if (jsonText == null) {
                String trimmed = output.trim();
                if (!trimmed.isBlank()) {
                    return trimmed;
                }
                throw new IllegalStateException(
                        "OpenClaw returned no reply. Config: " + config.configPath());
            }

            JsonNode root = OBJECT_MAPPER.readTree(jsonText);
            JsonNode payloads = root.path("payloads");
            if (payloads.isArray()) {
                StringBuilder combined = new StringBuilder();
                for (JsonNode payload : payloads) {
                    String text = payload.path("text").asText("");
                    if (!text.isBlank()) {
                        if (combined.length() > 0) {
                            combined.append("\n\n");
                        }
                        combined.append(text.trim());
                    }
                }
                if (combined.length() > 0) {
                    return combined.toString();
                }
            }

            String discovered = findText(root);
            if (discovered != null && !discovered.isBlank()) {
                return discovered.trim();
            }

            throw new IllegalStateException("OpenClaw returned JSON, but no text payload was found.");
        }

        private static String findText(JsonNode node) {
            if (node == null || node.isNull() || node.isMissingNode()) {
                return null;
            }
            if (node.isTextual()) {
                String text = node.asText();
                return text.isBlank() ? null : text;
            }
            if (node.isObject()) {
                for (String key : List.of("text", "message", "content", "response", "reply", "error")) {
                    String value = findText(node.get(key));
                    if (value != null) {
                        return value;
                    }
                }
                var fields = node.fields();
                while (fields.hasNext()) {
                    String value = findText(fields.next().getValue());
                    if (value != null) {
                        return value;
                    }
                }
            }
            if (node.isArray()) {
                for (JsonNode child : node) {
                    String value = findText(child);
                    if (value != null) {
                        return value;
                    }
                }
            }
            return null;
        }

        private static String extractJson(String output) {
            int objectStart = output.indexOf('{');
            int arrayStart = output.indexOf('[');
            int start = -1;
            if (objectStart >= 0 && arrayStart >= 0) {
                start = Math.min(objectStart, arrayStart);
            } else if (objectStart >= 0) {
                start = objectStart;
            } else if (arrayStart >= 0) {
                start = arrayStart;
            }
            if (start < 0) {
                return null;
            }

            String trimmed = output.substring(start).trim();
            return trimmed.isEmpty() ? null : trimmed;
        }

        private static String compactFailure(String output) {
            String trimmed = output == null ? "" : output.trim();
            if (trimmed.isBlank()) {
                return "OpenClaw command failed. Make sure `openclaw agent --json` works in this shell.";
            }
            String[] lines = trimmed.split("\\R");
            String lastLine = lines[lines.length - 1].trim();
            return lastLine.isEmpty() ? trimmed : lastLine;
        }
    }
}
