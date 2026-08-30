package com.Acrobot.ChestShop.Discord;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;

/**
 * Posts a payload to a Discord webhook.
 *
 * Retries are deliberately selective: 429 (rate limited) and 5xx are worth another try, while any
 * other 4xx means we sent something Discord will never accept - a deleted webhook, a malformed
 * embed - and hammering it just burns the rate limit budget for the messages that could work.
 *
 * @author Acrobot
 */
public class WebhookSender {

    /**
     * The outcome of a send, kept as a value so the caller can log the reason verbatim.
     */
    public static class Result {
        private final boolean success;
        private final String reason;

        private Result(boolean success, String reason) {
            this.success = success;
            this.reason = reason;
        }

        public static Result ok() {
            return new Result(true, "OK");
        }

        public static Result failed(String reason) {
            return new Result(false, reason);
        }

        public boolean isSuccess() {
            return success;
        }

        public String getReason() {
            return reason;
        }
    }

    /**
     * @param url      Webhook URL
     * @param json     Payload
     * @param settings Timeout and retry policy
     * @return Whether it went through, and why not if it didn't
     */
    public Result send(String url, String json, DiscordConfig.HttpSettings settings) {
        int attempts = settings.isRetryOnFail() ? settings.getMaxRetries() : 0;
        Result last = Result.failed("not sent");

        for (int attempt = 0; attempt <= attempts; attempt++) {
            last = attemptOnce(url, json, settings);

            if (last.isSuccess()) {
                return last;
            }

            if (isClientError(last.getReason())) {
                return last; //Our fault, not a hiccup - trying again would only repeat it
            }
        }

        return last;
    }

    private static boolean isClientError(String reason) {
        return reason.startsWith("HTTP 4") && !reason.startsWith("HTTP 429");
    }

    private Result attemptOnce(String url, String json, DiscordConfig.HttpSettings settings) {
        HttpURLConnection connection = null;

        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(settings.getTimeoutMs());
            connection.setReadTimeout(settings.getTimeoutMs());
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("User-Agent", "ChestShop-Webhook");

            OutputStream output = connection.getOutputStream();
            try {
                output.write(json.getBytes(Charset.forName("UTF-8")));
            } finally {
                output.close();
            }

            int code = connection.getResponseCode();

            if (code >= 200 && code < 300) {
                return Result.ok();
            }

            if (code == 429) {
                return Result.failed("HTTP 429 RateLimited");
            }

            return Result.failed("HTTP " + code + " " + readError(connection));
        } catch (Exception exception) {
            return Result.failed("EXC " + exception.getClass().getSimpleName() + ": " + exception.getMessage());
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static String readError(HttpURLConnection connection) {
        try {
            InputStream stream = connection.getErrorStream();
            if (stream == null) {
                return "";
            }

            BufferedReader reader = new BufferedReader(new InputStreamReader(stream, Charset.forName("UTF-8")));
            try {
                StringBuilder body = new StringBuilder();
                String line;

                while ((line = reader.readLine()) != null && body.length() < 180) {
                    body.append(line);
                }

                return body.length() > 180 ? body.substring(0, 180) : body.toString();
            } finally {
                reader.close();
            }
        } catch (Exception unreadable) {
            return "";
        }
    }
}
