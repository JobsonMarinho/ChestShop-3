package com.Acrobot.ChestShop.Discord;

import java.util.Collections;
import java.util.Locale;
import java.util.Map;

/**
 * An immutable snapshot of discord.yml.
 *
 * The config is never read from disk on the hot path: it is turned into this object once, when the
 * plugin enables or reloads, and the service holds it in a volatile field. A reload swaps the whole
 * object at once, so nothing has to be restarted and no half-applied config is ever visible.
 *
 * @author Acrobot
 */
public class DiscordConfig {
    private final boolean enabled;
    private final String username;
    private final double highValueThreshold;

    private final Map<String, Webhook> webhooks;
    private final Map<String, EventSettings> events;

    private final AntiFloodSettings antiFlood;
    private final AggregationSettings aggregation;
    private final HttpSettings http;

    public DiscordConfig(boolean enabled, String username, double highValueThreshold,
                         Map<String, Webhook> webhooks, Map<String, EventSettings> events,
                         AntiFloodSettings antiFlood, AggregationSettings aggregation, HttpSettings http) {
        this.enabled = enabled;
        this.username = username;
        this.highValueThreshold = highValueThreshold;
        this.webhooks = Collections.unmodifiableMap(webhooks);
        this.events = Collections.unmodifiableMap(events);
        this.antiFlood = antiFlood;
        this.aggregation = aggregation;
        this.http = http;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getUsername() {
        return username;
    }

    public double getHighValueThreshold() {
        return highValueThreshold;
    }

    public Webhook getWebhook(String name) {
        return name == null ? null : webhooks.get(name.toLowerCase(Locale.ROOT));
    }

    public Map<String, Webhook> getWebhooks() {
        return webhooks;
    }

    public EventSettings getEvent(String key) {
        return events.get(key);
    }

    public Map<String, EventSettings> getEvents() {
        return events;
    }

    public AntiFloodSettings getAntiFlood() {
        return antiFlood;
    }

    public AggregationSettings getAggregation() {
        return aggregation;
    }

    public HttpSettings getHttp() {
        return http;
    }

    /**
     * One Discord webhook URL - a destination such as economy, shops, staff, security or technical.
     */
    public static class Webhook {
        private final String name;
        private final boolean enabled;
        private final String url;

        public Webhook(String name, boolean enabled, String url) {
            this.name = name;
            this.enabled = enabled;
            this.url = url == null ? "" : url.trim();
        }

        public String getName() {
            return name;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public String getUrl() {
            return url;
        }

        /**
         * Rejects an untouched placeholder as firmly as a typo: posting a shop log to whatever
         * happens to answer at a stray URL is worse than not posting at all.
         *
         * @return Does this look like a real Discord webhook URL?
         */
        public boolean hasValidUrl() {
            String lower = url.toLowerCase(Locale.ROOT);

            return !lower.isEmpty()
                    && lower.startsWith("https://")
                    && !lower.startsWith("coloque")
                    && lower.contains("discord")
                    && lower.contains("/webhooks/");
        }

        /**
         * @return The URL with its token hidden, safe to show to staff in game
         */
        public String getMaskedUrl() {
            String marker = "/webhooks/";
            int index = url.indexOf(marker);

            if (index < 0) {
                return "********";
            }

            String rest = url.substring(index + marker.length());
            int slash = rest.indexOf('/');

            return url.substring(0, index + marker.length()) + (slash < 0 ? rest : rest.substring(0, slash)) + "/********";
        }
    }

    /**
     * One audited event: whether it fires, where it goes, how loud it is and what the embed says.
     */
    public static class EventSettings {
        private final String key;
        private final boolean enabled;
        private final String webhook;
        private final String priority;
        private final String mentionRoleId;
        private final Embed embed;

        public EventSettings(String key, boolean enabled, String webhook, String priority, String mentionRoleId, Embed embed) {
            this.key = key;
            this.enabled = enabled;
            this.webhook = webhook;
            this.priority = priority == null ? "MEDIUM" : priority;
            this.mentionRoleId = mentionRoleId;
            this.embed = embed;
        }

        public String getKey() {
            return key;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public String getWebhook() {
            return webhook;
        }

        public String getPriority() {
            return priority;
        }

        public String getMentionRoleId() {
            return mentionRoleId;
        }

        public Embed getEmbed() {
            return embed;
        }

        /**
         * @return Should this event skip the anti-flood? A database error has to arrive every time.
         */
        public boolean isCritical() {
            return priority.equalsIgnoreCase("CRITICAL") || priority.equalsIgnoreCase("HIGH");
        }
    }

    /**
     * The embed template, with {placeholder} markers still in it.
     */
    public static class Embed {
        private final String title;
        private final String description;
        private final int colour;
        private final String thumbnail;
        private final String footer;

        public Embed(String title, String description, int colour, String thumbnail, String footer) {
            this.title = title == null ? "" : title;
            this.description = description == null ? "" : description;
            this.colour = colour;
            this.thumbnail = thumbnail == null ? "" : thumbnail;
            this.footer = footer == null ? "" : footer;
        }

        public String getTitle() {
            return title;
        }

        public String getDescription() {
            return description;
        }

        public int getColour() {
            return colour;
        }

        public String getThumbnail() {
            return thumbnail;
        }

        public String getFooter() {
            return footer;
        }
    }

    public static class AntiFloodSettings {
        private final boolean queueEnabled;
        private final int queueMaxSize;
        private final int flushIntervalSeconds;
        private final boolean perEventEnabled;
        private final int perEventCooldownSeconds;
        private final boolean perPlayerEnabled;
        private final int perPlayerCooldownSeconds;
        private final boolean ignoreForCritical;

        public AntiFloodSettings(boolean queueEnabled, int queueMaxSize, int flushIntervalSeconds,
                                 boolean perEventEnabled, int perEventCooldownSeconds,
                                 boolean perPlayerEnabled, int perPlayerCooldownSeconds, boolean ignoreForCritical) {
            this.queueEnabled = queueEnabled;
            this.queueMaxSize = queueMaxSize;
            this.flushIntervalSeconds = flushIntervalSeconds;
            this.perEventEnabled = perEventEnabled;
            this.perEventCooldownSeconds = perEventCooldownSeconds;
            this.perPlayerEnabled = perPlayerEnabled;
            this.perPlayerCooldownSeconds = perPlayerCooldownSeconds;
            this.ignoreForCritical = ignoreForCritical;
        }

        public boolean isQueueEnabled() {
            return queueEnabled;
        }

        public int getQueueMaxSize() {
            return queueMaxSize;
        }

        public int getFlushIntervalSeconds() {
            return flushIntervalSeconds;
        }

        public boolean isPerEventEnabled() {
            return perEventEnabled;
        }

        public int getPerEventCooldownSeconds() {
            return perEventCooldownSeconds;
        }

        public boolean isPerPlayerEnabled() {
            return perPlayerEnabled;
        }

        public int getPerPlayerCooldownSeconds() {
            return perPlayerCooldownSeconds;
        }

        public boolean isIgnoreForCritical() {
            return ignoreForCritical;
        }
    }

    /**
     * How a burst of transactions from one player is folded into a single message.
     *
     * See {@link TransactionAggregator} for what each number actually does.
     */
    public static class AggregationSettings {
        private final boolean enabled;
        private final int aggregateAfter;
        private final int idleSeconds;
        private final int maxWindowSeconds;
        private final int maxEntries;
        private final int maxDescriptionLength;
        private final String entryFormat;
        private final String buyWord;
        private final String sellWord;

        public AggregationSettings(boolean enabled, int aggregateAfter, int idleSeconds, int maxWindowSeconds,
                                   int maxEntries, int maxDescriptionLength, String entryFormat, String buyWord, String sellWord) {
            this.enabled = enabled;
            this.aggregateAfter = aggregateAfter;
            this.idleSeconds = idleSeconds;
            this.maxWindowSeconds = maxWindowSeconds;
            this.maxEntries = maxEntries;
            this.maxDescriptionLength = maxDescriptionLength;
            this.entryFormat = entryFormat;
            this.buyWord = buyWord;
            this.sellWord = sellWord;
        }

        public boolean isEnabled() {
            return enabled;
        }

        /** How many transactions go out one by one before the player is considered to be in a burst */
        public int getAggregateAfter() {
            return aggregateAfter;
        }

        /** Seconds without a transaction that close the batch */
        public int getIdleSeconds() {
            return idleSeconds;
        }

        /** Hard ceiling: a batch is never held longer than this, however busy the player is */
        public int getMaxWindowSeconds() {
            return maxWindowSeconds;
        }

        /** Lines kept in one batch; the rest are counted but not listed */
        public int getMaxEntries() {
            return maxEntries;
        }

        /** Discord refuses an embed description longer than 4096 characters */
        public int getMaxDescriptionLength() {
            return maxDescriptionLength;
        }

        public String getEntryFormat() {
            return entryFormat;
        }

        public String getBuyWord() {
            return buyWord;
        }

        public String getSellWord() {
            return sellWord;
        }
    }

    public static class HttpSettings {
        private final int timeoutMs;
        private final boolean retryOnFail;
        private final int maxRetries;

        public HttpSettings(int timeoutMs, boolean retryOnFail, int maxRetries) {
            this.timeoutMs = timeoutMs;
            this.retryOnFail = retryOnFail;
            this.maxRetries = maxRetries;
        }

        public int getTimeoutMs() {
            return timeoutMs;
        }

        public boolean isRetryOnFail() {
            return retryOnFail;
        }

        public int getMaxRetries() {
            return maxRetries;
        }
    }
}
