package com.Acrobot.ChestShop.Discord;

import com.Acrobot.ChestShop.ChestShop;
import com.Acrobot.ChestShop.Economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

/**
 * The webhook system's front door: every audited event goes through one of the onX methods here.
 *
 * <h2>Rules this class exists to keep</h2>
 *
 * <b>The shop always wins.</b> Every entry point is wrapped so that a bad URL, a template typo or a
 * dead network can never cancel a purchase or a shop creation. Auditing is a second class citizen
 * next to the transaction it is auditing.
 *
 * <b>Nothing is announced before it is true.</b> Callers only report events after the world and the
 * economy have already been changed, never in a pre-event that something might still cancel.
 *
 * <b>No HTTP on the server thread.</b> onX runs on the main thread and only builds strings; the
 * payload goes into a queue that an async task drains.
 *
 * <b>The config is a snapshot.</b> It lives in a volatile field, swapped whole on reload, so a
 * /csreload is picked up on the next event with nothing to restart.
 *
 * @author Acrobot
 */
public class DiscordService {
    private static final SimpleDateFormat CLOCK = new SimpleDateFormat("HH:mm:ss");

    /** Every placeholder any template may use, so a missing one never renders as raw {text} */
    private static final String[] PLACEHOLDERS = {
            "player", "uuid", "avatar", "body",
            "item", "amount", "price", "unit_price", "total",
            "shop", "owner", "shop_type", "type",
            "world", "x", "y", "z", "location",
            "reason", "outcome", "context", "admin", "command", "details",
            "buys", "sells", "spent", "earned", "net", "entries", "not_listed",
            "from", "to", "duration", "count", "sign1", "sign2", "sign3", "sign4"
    };

    private final ChestShop plugin;
    private final WebhookSender sender = new WebhookSender();
    private final AntiFlood antiFlood = new AntiFlood();
    private final TransactionAggregator aggregator = new TransactionAggregator();
    private final ConcurrentLinkedQueue<Outgoing> queue = new ConcurrentLinkedQueue<Outgoing>();
    private final List<BukkitTask> tasks = new ArrayList<BukkitTask>();
    private final AtomicInteger sentToday = new AtomicInteger();
    private final AtomicInteger failedToday = new AtomicInteger();

    private volatile DiscordConfig config;
    private volatile DiscordLogger logger;

    public DiscordService(ChestShop plugin, DiscordConfig config) {
        this.plugin = plugin;
        this.config = config;
        this.logger = new DiscordLogger(plugin.getDataFolder(), config.isEnabled());
    }

    public DiscordConfig getConfig() {
        return config;
    }

    public TransactionAggregator getAggregator() {
        return aggregator;
    }

    public int getQueueSize() {
        return queue.size();
    }

    public int getSentToday() {
        return sentToday.get();
    }

    public int getFailedToday() {
        return failedToday.get();
    }

    // ------------------------------------------------------------------ lifecycle

    public void start() {
        long ticks = Math.max(1, config.getAntiFlood().getFlushIntervalSeconds()) * 20L;

        tasks.add(Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, new Runnable() {
            public void run() {
                flushAggregator();
                flushQueue();
            }
        }, ticks, ticks));
    }

    public void stop() {
        for (BukkitTask task : tasks) {
            task.cancel();
        }
        tasks.clear();

        // A batch collected seconds before shutdown is still a batch somebody wanted to see
        dispatchBatches(aggregator.takeAll());
        flushQueue();
    }

    /**
     * Swaps in a freshly read config and restarts the flush task.
     *
     * @param newConfig The new snapshot
     */
    public void reload(DiscordConfig newConfig) {
        stop();

        this.config = newConfig;
        this.logger = new DiscordLogger(plugin.getDataFolder(), newConfig.isEnabled());

        antiFlood.clear();
        start();
    }

    // ------------------------------------------------------------------ transactions

    /**
     * A completed purchase or sale.
     *
     * The first few from a player go out on their own; once they are clearly in a buying spree the
     * rest are folded into one message by {@link TransactionAggregator}.
     */
    public void onTransaction(final Player client, boolean buy, String ownerName, boolean adminShop,
                              String itemName, int amount, double price, Location location) {
        try {
            DiscordConfig current = config;
            if (!current.isEnabled()) {
                return;
            }

            String shopLabel = adminShop ? ownerName : ownerName;
            String locationLabel = describe(location);

            TransactionAggregator.Entry entry = new TransactionAggregator.Entry(
                    buy, amount, itemName, price, money(price), shopLabel, locationLabel);

            TransactionAggregator.Decision decision =
                    aggregator.record(client.getUniqueId(), client.getName(), entry, current.getAggregation());

            if (decision == TransactionAggregator.Decision.AGGREGATED) {
                logger.aggregated(buy ? "transaction-buy" : "transaction-sell", client.getName());
            } else {
                Map<String, String> values = valuesFor(client);
                values.put("item", itemName);
                values.put("amount", String.valueOf(amount));
                values.put("price", money(price));
                values.put("unit_price", money(amount > 0 ? price / amount : price));
                values.put("owner", ownerName);
                values.put("shop", shopLabel);
                values.put("shop_type", adminShop ? "Loja do servidor" : "Loja de jogador");
                values.putAll(locationValues(location));

                dispatch(buy ? "transaction-buy" : "transaction-sell", client, values);
            }

            if (price >= current.getHighValueThreshold()) {
                Map<String, String> values = valuesFor(client);
                values.put("item", itemName);
                values.put("amount", String.valueOf(amount));
                values.put("price", money(price));
                values.put("owner", ownerName);
                values.put("shop_type", adminShop ? "Loja do servidor" : "Loja de jogador");
                values.put("type", buy ? "Compra" : "Venda");
                values.putAll(locationValues(location));

                dispatch("transaction-high-value", client, values);
            }
        } catch (Throwable failure) {
            warn(failure);
        }
    }

    /**
     * Closes a player's batch when they log out, so it isn't held until it times out.
     */
    public void onPlayerQuit(UUID playerId) {
        try {
            TransactionAggregator.Batch batch = aggregator.take(playerId);

            if (batch != null) {
                dispatchBatch(batch);
            }
        } catch (Throwable failure) {
            warn(failure);
        }
    }

    private void flushAggregator() {
        try {
            dispatchBatches(aggregator.collectReady(config.getAggregation()));
        } catch (Throwable failure) {
            warn(failure);
        }
    }

    private void dispatchBatches(List<TransactionAggregator.Batch> batches) {
        for (TransactionAggregator.Batch batch : batches) {
            dispatchBatch(batch);
        }
    }

    private void dispatchBatch(TransactionAggregator.Batch batch) {
        try {
            DiscordConfig.AggregationSettings settings = config.getAggregation();

            Map<String, String> values = masterValues();
            values.put("player", batch.getPlayerName());
            values.put("uuid", batch.getPlayerId().toString());
            values.put("avatar", AvatarResolver.head(batch.getPlayerName()));
            values.put("body", AvatarResolver.body(batch.getPlayerName()));
            values.put("buys", String.valueOf(batch.getBuys()));
            values.put("sells", String.valueOf(batch.getSells()));
            values.put("total", String.valueOf(batch.getBuys() + batch.getSells()));
            values.put("spent", money(batch.getSpent()));
            values.put("earned", money(batch.getEarned()));
            values.put("net", money(batch.getEarned() - batch.getSpent()));
            values.put("from", CLOCK.format(new Date(batch.getFrom())));
            values.put("to", CLOCK.format(new Date(batch.getTo())));
            values.put("duration", describeDuration(batch.getTo() - batch.getFrom()));
            values.put("entries", joinEntries(batch, settings));
            values.put("not_listed", batch.getNotListed() > 0 ? String.valueOf(batch.getNotListed()) : "0");

            logger.batchSent(batch.getPlayerName(), batch.getBuys() + batch.getSells(), batch.getLines().size());

            // The batch already is the anti-flood answer, so it never goes through the rate limiter
            dispatchDirect("transaction-summary", batch.getPlayerName(), values);
        } catch (Throwable failure) {
            warn(failure);
        }
    }

    /**
     * Joins the listed transactions, staying inside what Discord will accept for a description.
     */
    private static String joinEntries(TransactionAggregator.Batch batch, DiscordConfig.AggregationSettings settings) {
        StringBuilder joined = new StringBuilder();
        int listed = 0;

        for (String line : batch.getLines()) {
            if (joined.length() + line.length() + 1 > settings.getMaxDescriptionLength()) {
                break;
            }

            if (joined.length() > 0) {
                joined.append('\n');
            }

            joined.append(line);
            listed++;
        }

        int hidden = batch.getLines().size() - listed + batch.getNotListed();
        if (hidden > 0) {
            joined.append("\n… e mais ").append(hidden).append(" transação(ões)");
        }

        return joined.length() == 0 ? "—" : joined.toString();
    }

    // ------------------------------------------------------------------ shops

    public void onShopCreated(Player creator, String[] signLines, boolean adminShop, Location location) {
        try {
            Map<String, String> values = valuesFor(creator);
            values.putAll(signValues(signLines));
            values.putAll(locationValues(location));
            values.put("shop_type", adminShop ? "Loja do servidor" : "Loja de jogador");

            dispatch(adminShop ? "admin-shop-created" : "shop-created", creator, values);
        } catch (Throwable failure) {
            warn(failure);
        }
    }

    public void onShopDestroyed(Player destroyer, String[] signLines, String ownerName, boolean adminShop, Location location) {
        try {
            Map<String, String> values = valuesFor(destroyer);
            values.putAll(signValues(signLines));
            values.putAll(locationValues(location));
            values.put("owner", ownerName);
            values.put("shop_type", adminShop ? "Loja do servidor" : "Loja de jogador");

            String key;
            if (destroyer == null) {
                // No destroyer means the plugin removed it itself, not a player with a pickaxe
                key = "shop-emptied";
            } else if (adminShop) {
                key = "admin-shop-destroyed";
            } else if (ownerName != null && !ownerName.equalsIgnoreCase(destroyer.getName())) {
                key = "shop-destroyed-by-staff";
            } else {
                key = "shop-destroyed";
            }

            dispatch(key, destroyer, values);
        } catch (Throwable failure) {
            warn(failure);
        }
    }

    public void onShopEdited(Player editor, String[] signLines, Location location) {
        try {
            Map<String, String> values = valuesFor(editor);
            values.putAll(signValues(signLines));
            values.putAll(locationValues(location));

            dispatch("shop-edited", editor, values);
        } catch (Throwable failure) {
            warn(failure);
        }
    }

    public void onShopFee(Player player, double amount, boolean refund) {
        try {
            Map<String, String> values = valuesFor(player);
            values.put("price", money(amount));

            dispatch(refund ? "shop-refund" : "shop-creation-fee", player, values);
        } catch (Throwable failure) {
            warn(failure);
        }
    }

    // ------------------------------------------------------------------ security and technical

    /**
     * A menu item was found in a real inventory. That is not dirt, it is the signature of an
     * exploit attempt, so it is worth waking somebody up for.
     */
    public void onMenuItemRecovered(Player player, int count, String reason) {
        try {
            Map<String, String> values = valuesFor(player);
            values.put("count", String.valueOf(count));
            values.put("reason", reason);

            dispatch("menu-item-recovered", player, values);
        } catch (Throwable failure) {
            warn(failure);
        }
    }

    /**
     * The shop's terms changed between showing the confirmation and the player accepting it.
     */
    public void onOfferChanged(Player player, String[] signLines, Location location) {
        try {
            Map<String, String> values = valuesFor(player);
            values.putAll(signValues(signLines));
            values.putAll(locationValues(location));

            dispatch("confirmation-offer-changed", player, values);
        } catch (Throwable failure) {
            warn(failure);
        }
    }

    public void onTransactionDenied(Player player, String outcome, String ownerName, Location location) {
        try {
            Map<String, String> values = valuesFor(player);
            values.put("outcome", outcome);
            values.put("owner", ownerName);
            values.putAll(locationValues(location));

            dispatch("transaction-denied", player, values);
        } catch (Throwable failure) {
            warn(failure);
        }
    }

    public void onStaffCommand(String admin, String command, String details) {
        try {
            Map<String, String> values = masterValues();
            values.put("admin", admin);
            values.put("player", admin);
            values.put("command", command);
            values.put("details", details);
            values.put("avatar", AvatarResolver.head(admin));

            dispatch("staff-command", null, values);
        } catch (Throwable failure) {
            warn(failure);
        }
    }

    public void onEconomyFailure(String context, String reason, String playerName) {
        try {
            Map<String, String> values = masterValues();
            values.put("context", context);
            values.put("reason", reason);
            values.put("player", playerName == null ? "—" : playerName);

            dispatch("economy-failure", null, values);
        } catch (Throwable failure) {
            warn(failure);
        }
    }

    public void onTechnicalError(String context, String reason) {
        try {
            Map<String, String> values = masterValues();
            values.put("context", context);
            values.put("reason", reason);

            dispatch("technical-error", null, values);
        } catch (Throwable failure) {
            warn(failure);
        }
    }

    // ------------------------------------------------------------------ dispatch

    private void dispatch(String eventKey, OfflinePlayer player, Map<String, String> values) {
        DiscordConfig current = config;
        if (!current.isEnabled()) {
            return;
        }

        DiscordConfig.EventSettings event = current.getEvent(eventKey);
        if (event == null || !event.isEnabled()) {
            return;
        }

        String playerKey = player == null ? null : player.getUniqueId().toString();
        AntiFlood.Decision decision = antiFlood.check(eventKey, playerKey, event.isCritical(), current.getAntiFlood());

        if (decision != AntiFlood.Decision.ALLOW) {
            logger.blocked(eventKey, player == null ? null : player.getName(),
                    decision == AntiFlood.Decision.BLOCK_EVENT ? "per-event" : "per-player");
            return;
        }

        dispatchDirect(eventKey, player == null ? null : player.getName(), values);
    }

    /**
     * Sends without consulting the rate limiter - for messages that are themselves the answer to
     * flooding, like an aggregated batch.
     */
    private void dispatchDirect(String eventKey, String playerName, Map<String, String> values) {
        DiscordConfig current = config;

        DiscordConfig.EventSettings event = current.getEvent(eventKey);
        if (event == null || !event.isEnabled()) {
            return;
        }

        DiscordConfig.Webhook webhook = current.getWebhook(event.getWebhook());
        if (webhook == null || !webhook.isEnabled()) {
            logger.webhookDisabled(eventKey, event.getWebhook());
            return;
        }
        if (webhook.getUrl().isEmpty()) {
            logger.urlMissing(eventKey, event.getWebhook());
            return;
        }
        if (!webhook.hasValidUrl()) {
            logger.urlInvalid(eventKey, event.getWebhook());
            return;
        }

        String json = DiscordJson.buildEmbed(event.getEmbed(), values, current.getUsername(), event.getMentionRoleId());
        enqueue(new Outgoing(eventKey, webhook.getName(), webhook.getUrl(), json, playerName));
    }

    private void enqueue(Outgoing outgoing) {
        DiscordConfig current = config;

        if (!current.getAntiFlood().isQueueEnabled()) {
            sendAsync(outgoing);
            return;
        }

        if (queue.size() >= current.getAntiFlood().getQueueMaxSize()) {
            logger.queueFull(outgoing.event);
            return;
        }

        queue.add(outgoing);
    }

    private void sendAsync(final Outgoing outgoing) {
        if (!plugin.isEnabled()) {
            send(outgoing);
            return;
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, new Runnable() {
            public void run() {
                send(outgoing);
            }
        });
    }

    private void flushQueue() {
        Outgoing outgoing;

        while ((outgoing = queue.poll()) != null) {
            send(outgoing);
        }
    }

    private void send(Outgoing outgoing) {
        WebhookSender.Result result = sender.send(outgoing.url, outgoing.json, config.getHttp());

        if (result.isSuccess()) {
            sentToday.incrementAndGet();
            logger.sent(outgoing.event, outgoing.player, outgoing.webhook);
        } else {
            failedToday.incrementAndGet();
            logger.failed(outgoing.event, outgoing.webhook, result.getReason());
        }
    }

    /**
     * Sends a test embed, for /csdiscord test.
     *
     * @param webhookName Destination to test
     * @param by          Who asked
     * @return Why it could not be sent, or null if it was
     */
    public String sendTest(String webhookName, String by) {
        DiscordConfig current = config;
        DiscordConfig.Webhook webhook = current.getWebhook(webhookName);

        if (webhook == null) {
            return "Webhook desconhecido.";
        }
        if (!webhook.isEnabled()) {
            return "Webhook desligado no discord.yml.";
        }
        if (!webhook.hasValidUrl()) {
            return "URL ausente ou inválida.";
        }

        DiscordConfig.Embed embed = new DiscordConfig.Embed(
                "✅ Teste de webhook",
                "Se você está lendo isto, o destino **" + webhook.getName() + "** está funcionando.\nPedido por **" + by + "**.",
                5763719, "", "ChestShop • Teste");

        logger.testSent(webhook.getName(), by);
        enqueue(new Outgoing("test", webhook.getName(), webhook.getUrl(),
                DiscordJson.buildEmbed(embed, masterValues(), current.getUsername(), null), by));

        return null;
    }

    public int clearQueue(String by) {
        int size = queue.size();

        queue.clear();
        logger.queueCleared(size, by);

        return size;
    }

    public void logReload(String by) {
        logger.reloaded(by);
    }

    // ------------------------------------------------------------------ placeholders

    private static Map<String, String> masterValues() {
        Map<String, String> values = new HashMap<String, String>(64);

        for (String key : PLACEHOLDERS) {
            values.put(key, "—");
        }

        return values;
    }

    private static Map<String, String> valuesFor(Player player) {
        Map<String, String> values = masterValues();

        if (player != null) {
            values.put("player", player.getName());
            values.put("uuid", player.getUniqueId().toString());
            values.put("avatar", AvatarResolver.head(player.getName()));
            values.put("body", AvatarResolver.body(player.getName()));
        }

        return values;
    }

    private static Map<String, String> signValues(String[] lines) {
        Map<String, String> values = new HashMap<String, String>(8);

        for (int i = 0; i < 4; i++) {
            String line = lines != null && lines.length > i && lines[i] != null ? lines[i].trim() : "";
            values.put("sign" + (i + 1), line.isEmpty() ? "—" : line);
        }

        if (lines != null && lines.length > 3) {
            values.put("item", values.get("sign4"));
            values.put("owner", values.get("sign1"));
        }

        return values;
    }

    private static Map<String, String> locationValues(Location location) {
        Map<String, String> values = new HashMap<String, String>(8);

        if (location == null || location.getWorld() == null) {
            return values;
        }

        values.put("world", location.getWorld().getName());
        values.put("x", String.valueOf(location.getBlockX()));
        values.put("y", String.valueOf(location.getBlockY()));
        values.put("z", String.valueOf(location.getBlockZ()));
        values.put("location", describe(location));

        return values;
    }

    private static String describe(Location location) {
        if (location == null || location.getWorld() == null) {
            return "—";
        }

        return location.getWorld().getName() + " " + location.getBlockX() + "," + location.getBlockY() + "," + location.getBlockZ();
    }

    private static String describeDuration(long millis) {
        long seconds = Math.max(0, millis / 1000L);

        if (seconds < 60) {
            return seconds + "s";
        }

        return (seconds / 60) + "min " + (seconds % 60) + "s";
    }

    private static String money(double amount) {
        try {
            return Economy.formatBalance(amount);
        } catch (Throwable noEconomyYet) {
            return String.valueOf(amount);
        }
    }

    private void warn(Throwable failure) {
        ChestShop.getBukkitLogger().log(Level.WARNING, "[Discord] Falha ao montar/disparar webhook: " + failure.getMessage());
    }

    /** One message waiting to be sent */
    private static class Outgoing {
        private final String event;
        private final String webhook;
        private final String url;
        private final String json;
        private final String player;

        private Outgoing(String event, String webhook, String url, String json, String player) {
            this.event = event;
            this.webhook = webhook;
            this.url = url;
            this.json = json;
            this.player = player;
        }
    }
}
