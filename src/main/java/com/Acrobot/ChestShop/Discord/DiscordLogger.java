package com.Acrobot.ChestShop.Discord;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Local audit trail of what the webhook system did, in logs/discord.log.
 *
 * Without it, a webhook that silently goes nowhere - deleted on Discord's side, URL never filled in -
 * looks exactly like a server where nothing happened.
 *
 * @author Acrobot
 */
public class DiscordLogger {
    private static final SimpleDateFormat STAMP = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    private final File file;
    private final Object lock = new Object();
    private final boolean enabled;

    public DiscordLogger(File dataFolder, boolean enabled) {
        this.enabled = enabled;

        File logs = new File(dataFolder, "logs");
        if (enabled && !logs.exists()) {
            logs.mkdirs();
        }

        this.file = new File(logs, "discord.log");
    }

    public void sent(String event, String player, String webhook) {
        write("SENT | Event=" + event + " | Player=" + safe(player) + " | Webhook=" + webhook);
    }

    public void failed(String event, String webhook, String reason) {
        write("FAILED | Event=" + event + " | Webhook=" + safe(webhook) + " | Error=" + reason);
    }

    public void blocked(String event, String player, String level) {
        write("BLOCKED_ANTIFLOOD | Event=" + event + " | Player=" + safe(player) + " | level=" + level);
    }

    public void aggregated(String event, String player) {
        write("AGGREGATED | Event=" + event + " | Player=" + safe(player));
    }

    public void batchSent(String player, int transactions, int listed) {
        write("BATCH | Player=" + safe(player) + " | Transactions=" + transactions + " | Listed=" + listed);
    }

    public void webhookDisabled(String event, String webhook) {
        write("WEBHOOK_DISABLED | Event=" + event + " | Webhook=" + safe(webhook));
    }

    public void urlMissing(String event, String webhook) {
        write("URL_MISSING | Event=" + event + " | Webhook=" + safe(webhook));
    }

    public void urlInvalid(String event, String webhook) {
        write("URL_INVALID | Event=" + event + " | Webhook=" + safe(webhook));
    }

    public void queueFull(String event) {
        write("QUEUE_FULL | Event=" + event + " | dropped");
    }

    public void testSent(String webhook, String by) {
        write("TEST_SENT | Webhook=" + safe(webhook) + " | By=" + safe(by));
    }

    public void queueCleared(int size, String by) {
        write("QUEUE_CLEARED | Size=" + size + " | By=" + safe(by));
    }

    public void reloaded(String by) {
        write("DISCORD_RELOAD | By=" + safe(by));
    }

    private static String safe(String value) {
        return value == null || value.isEmpty() ? "—" : value;
    }

    private void write(String line) {
        if (!enabled) {
            return;
        }

        synchronized (lock) {
            PrintWriter writer = null;

            try {
                writer = new PrintWriter(new FileWriter(file, true));
                writer.println("[" + STAMP.format(new Date()) + "] " + line);
            } catch (Exception unwritable) {
                // A log that can't be written must not take the plugin down with it
            } finally {
                if (writer != null) {
                    writer.close();
                }
            }
        }
    }
}
