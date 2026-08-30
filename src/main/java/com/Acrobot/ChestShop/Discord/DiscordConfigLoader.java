package com.Acrobot.ChestShop.Discord;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Turns discord.yml into a {@link DiscordConfig} snapshot.
 *
 * Everything has a default, so a truncated or hand-mangled file degrades to something sane instead
 * of throwing during startup - a broken webhook config must never keep the shop plugin from loading.
 *
 * @author Acrobot
 */
public class DiscordConfigLoader {
    private static final String ROOT = "discord";

    /**
     * @param file discord.yml
     * @return The snapshot, with the system turned off if the file can't be read
     */
    public static DiscordConfig load(File file) {
        try {
            return read(YamlConfiguration.loadConfiguration(file));
        } catch (Exception malformed) {
            return disabled();
        }
    }

    private static DiscordConfig read(FileConfiguration file) {
        ConfigurationSection root = file.getConfigurationSection(ROOT);
        if (root == null) {
            return disabled();
        }

        return new DiscordConfig(
                root.getBoolean("enabled", false),
                root.getString("username", "ChestShop"),
                root.getDouble("high-value-threshold", 100000),
                readWebhooks(root.getConfigurationSection("webhooks")),
                readEvents(root.getConfigurationSection("events")),
                readAntiFlood(root.getConfigurationSection("anti-flood")),
                readAggregation(root.getConfigurationSection("aggregation")),
                readHttp(root.getConfigurationSection("settings")));
    }

    private static Map<String, DiscordConfig.Webhook> readWebhooks(ConfigurationSection section) {
        Map<String, DiscordConfig.Webhook> webhooks = new HashMap<String, DiscordConfig.Webhook>();
        if (section == null) {
            return webhooks;
        }

        for (String name : section.getKeys(false)) {
            ConfigurationSection entry = section.getConfigurationSection(name);
            if (entry == null) {
                continue;
            }

            webhooks.put(name.toLowerCase(Locale.ROOT), new DiscordConfig.Webhook(
                    name,
                    entry.getBoolean("enabled", false),
                    entry.getString("url", "")));
        }

        return webhooks;
    }

    private static Map<String, DiscordConfig.EventSettings> readEvents(ConfigurationSection section) {
        // Insertion ordered so /csdiscord status lists them the way the file does
        Map<String, DiscordConfig.EventSettings> events = new LinkedHashMap<String, DiscordConfig.EventSettings>();
        if (section == null) {
            return events;
        }

        for (String key : section.getKeys(false)) {
            ConfigurationSection entry = section.getConfigurationSection(key);
            if (entry == null) {
                continue;
            }

            DiscordConfig.Embed embed = new DiscordConfig.Embed(
                    entry.getString("title", ""),
                    entry.getString("description", ""),
                    entry.getInt("color", 3447003),
                    entry.getString("thumbnail", ""),
                    entry.getString("footer", ""));

            events.put(key, new DiscordConfig.EventSettings(
                    key,
                    entry.getBoolean("enabled", false),
                    entry.getString("webhook", "staff"),
                    entry.getString("priority", "MEDIUM"),
                    entry.getString("mention-role-id", ""),
                    embed));
        }

        return events;
    }

    private static DiscordConfig.AntiFloodSettings readAntiFlood(ConfigurationSection section) {
        if (section == null) {
            return new DiscordConfig.AntiFloodSettings(true, 200, 5, true, 0, true, 0, true);
        }

        return new DiscordConfig.AntiFloodSettings(
                section.getBoolean("queue-enabled", true),
                Math.max(1, section.getInt("queue-max-size", 200)),
                Math.max(1, section.getInt("flush-interval-seconds", 5)),
                section.getBoolean("per-event-enabled", true),
                Math.max(0, section.getInt("per-event-cooldown-seconds", 0)),
                section.getBoolean("per-player-enabled", true),
                Math.max(0, section.getInt("per-player-cooldown-seconds", 0)),
                section.getBoolean("ignore-anti-flood-for-critical", true));
    }

    private static DiscordConfig.AggregationSettings readAggregation(ConfigurationSection section) {
        if (section == null) {
            return new DiscordConfig.AggregationSettings(true, 3, 30, 300, 60, 3800,
                    "`{time}` {type} **{amount}x** {item} — {price} — {shop}", "Comprou", "Vendeu");
        }

        return new DiscordConfig.AggregationSettings(
                section.getBoolean("enabled", true),
                Math.max(0, section.getInt("aggregate-after", 3)),
                Math.max(1, section.getInt("idle-seconds", 30)),
                Math.max(1, section.getInt("max-window-seconds", 300)),
                Math.max(1, section.getInt("max-entries", 60)),
                // Discord's own hard limit is 4096; stay under it so the header always fits
                Math.min(3900, Math.max(256, section.getInt("max-description-length", 3800))),
                section.getString("entry-format", "`{time}` {type} **{amount}x** {item} — {price} — {shop}"),
                section.getString("buy-word", "Comprou"),
                section.getString("sell-word", "Vendeu"));
    }

    private static DiscordConfig.HttpSettings readHttp(ConfigurationSection section) {
        if (section == null) {
            return new DiscordConfig.HttpSettings(5000, true, 2);
        }

        return new DiscordConfig.HttpSettings(
                Math.max(500, section.getInt("timeout-ms", 5000)),
                section.getBoolean("retry-on-fail", true),
                Math.max(0, section.getInt("max-retries", 2)));
    }

    private static DiscordConfig disabled() {
        return new DiscordConfig(false, "ChestShop", 100000,
                new HashMap<String, DiscordConfig.Webhook>(),
                new LinkedHashMap<String, DiscordConfig.EventSettings>(),
                readAntiFlood(null), readAggregation(null), readHttp(null));
    }
}
