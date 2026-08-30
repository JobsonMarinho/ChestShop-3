package com.Acrobot.ChestShop.Discord;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Folds a burst of transactions from one player into a single, detailed message.
 *
 * <h2>Why not just rate limit</h2>
 *
 * The usual anti-flood drops whatever exceeds the limit, which on a shop plugin throws away exactly
 * the thing you wanted to audit: the player who bought forty times in two minutes. Here nothing is
 * dropped - the burst is collected and sent as one embed listing every transaction, so the channel
 * stays readable and the log stays complete.
 *
 * <h2>How a batch behaves</h2>
 *
 * The first few transactions go out one by one, so a player buying a single item still shows up in
 * real time. Once someone crosses that count they are treated as being in a burst, and everything
 * from there is collected. A batch is sent when the player goes quiet ({@code idle-seconds}), when
 * it grows past {@code max-entries}, or when it hits the hard ceiling of {@code max-window-seconds}
 * - whichever comes first, so a batch is never held indefinitely by someone who never stops buying.
 *
 * Going quiet ends the session, so coming back later starts fresh with immediate messages again.
 * Hitting a size or time ceiling does not: the player is demonstrably still in a burst, and resetting
 * there would let them alternate between ceilings and never stop producing single messages.
 *
 * <h2>Threading</h2>
 *
 * {@link #record} runs on the server thread (from the transaction event) and {@link #collectReady}
 * on the async flush task, so the map is concurrent and every session is guarded by its own lock.
 *
 * @author Acrobot
 */
public class TransactionAggregator {
    private static final SimpleDateFormat TIME = new SimpleDateFormat("HH:mm:ss");

    /** What should happen to a transaction that was just recorded */
    public enum Decision {
        /** Send it now, on its own */
        IMMEDIATE,
        /** Collected into the player's batch */
        AGGREGATED
    }

    /** One transaction, as it will be listed inside a batch */
    public static class Entry {
        private final boolean buy;
        private final int amount;
        private final String item;
        private final double price;
        private final String formattedPrice;
        private final String shop;
        private final String location;
        private final long time;

        public Entry(boolean buy, int amount, String item, double price, String formattedPrice, String shop, String location) {
            this.buy = buy;
            this.amount = amount;
            this.item = item;
            this.price = price;
            this.formattedPrice = formattedPrice;
            this.shop = shop;
            this.location = location;
            this.time = System.currentTimeMillis();
        }

        private String format(DiscordConfig.AggregationSettings settings) {
            Map<String, String> values = new java.util.HashMap<String, String>();
            values.put("time", TIME.format(new Date(time)));
            values.put("type", buy ? settings.getBuyWord() : settings.getSellWord());
            values.put("amount", String.valueOf(amount));
            values.put("item", item);
            values.put("price", formattedPrice);
            values.put("shop", shop);
            values.put("location", location);

            return DiscordJson.apply(settings.getEntryFormat(), values);
        }
    }

    /** A finished batch, detached from its session so the embed can be built without holding a lock */
    public static class Batch {
        private final UUID playerId;
        private final String playerName;
        private final long from;
        private final long to;
        private final int buys;
        private final int sells;
        private final double spent;
        private final double earned;
        private final List<String> lines;
        private final int notListed;

        private Batch(UUID playerId, String playerName, long from, long to, int buys, int sells,
                      double spent, double earned, List<String> lines, int notListed) {
            this.playerId = playerId;
            this.playerName = playerName;
            this.from = from;
            this.to = to;
            this.buys = buys;
            this.sells = sells;
            this.spent = spent;
            this.earned = earned;
            this.lines = lines;
            this.notListed = notListed;
        }

        public UUID getPlayerId() {
            return playerId;
        }

        public String getPlayerName() {
            return playerName;
        }

        public long getFrom() {
            return from;
        }

        public long getTo() {
            return to;
        }

        public int getBuys() {
            return buys;
        }

        public int getSells() {
            return sells;
        }

        public double getSpent() {
            return spent;
        }

        public double getEarned() {
            return earned;
        }

        public List<String> getLines() {
            return lines;
        }

        /** Transactions that happened but did not fit in the listing */
        public int getNotListed() {
            return notListed;
        }
    }

    private static class Session {
        private final UUID playerId;
        private String playerName;

        private long firstAt;
        private long lastAt;

        private int immediateCount;

        private int buys;
        private int sells;
        private double spent;
        private double earned;
        private int notListed;

        private final List<String> lines = new ArrayList<String>();

        private Session(UUID playerId, String playerName) {
            this.playerId = playerId;
            this.playerName = playerName;
            this.firstAt = System.currentTimeMillis();
            this.lastAt = this.firstAt;
        }

        private boolean hasBatch() {
            return buys + sells > 0;
        }

        private Batch drain() {
            Batch batch = new Batch(playerId, playerName, firstAt, lastAt, buys, sells, spent, earned,
                    new ArrayList<String>(lines), notListed);

            lines.clear();
            buys = 0;
            sells = 0;
            spent = 0;
            earned = 0;
            notListed = 0;
            firstAt = System.currentTimeMillis();

            return batch;
        }
    }

    private final Map<UUID, Session> sessions = new ConcurrentHashMap<UUID, Session>();

    /**
     * Records a transaction and says what to do with it.
     *
     * @param playerId   Client of the shop
     * @param playerName Their name, for the batch header
     * @param entry      The transaction
     * @param settings   Current aggregation settings
     * @return Whether this one goes out on its own or was collected
     */
    public Decision record(UUID playerId, String playerName, Entry entry, DiscordConfig.AggregationSettings settings) {
        if (!settings.isEnabled()) {
            return Decision.IMMEDIATE;
        }

        Session session = sessions.get(playerId);
        if (session == null) {
            session = new Session(playerId, playerName);
            Session raced = sessions.putIfAbsent(playerId, session);
            session = raced != null ? raced : session;
        }

        synchronized (session) {
            session.playerName = playerName;
            session.lastAt = System.currentTimeMillis();

            if (session.immediateCount < settings.getAggregateAfter()) {
                session.immediateCount++;
                return Decision.IMMEDIATE;
            }

            if (entry.buy) {
                session.buys++;
                session.spent += entry.price;
            } else {
                session.sells++;
                session.earned += entry.price;
            }

            if (session.lines.size() < settings.getMaxEntries()) {
                session.lines.add(entry.format(settings));
            } else {
                session.notListed++;
            }

            return Decision.AGGREGATED;
        }
    }

    /**
     * Collects every batch that is ready to be sent.
     *
     * @param settings Current aggregation settings
     * @return Batches to turn into messages, possibly empty
     */
    public List<Batch> collectReady(DiscordConfig.AggregationSettings settings) {
        List<Batch> ready = new ArrayList<Batch>();
        long now = System.currentTimeMillis();

        long idleMillis = settings.getIdleSeconds() * 1000L;
        long windowMillis = settings.getMaxWindowSeconds() * 1000L;

        for (Session session : sessions.values()) {
            synchronized (session) {
                boolean idle = now - session.lastAt >= idleMillis;
                boolean full = session.lines.size() >= settings.getMaxEntries();
                boolean expired = now - session.firstAt >= windowMillis;

                if (idle) {
                    // The player stopped: close the session so a later visit starts over
                    sessions.remove(session.playerId);

                    if (session.hasBatch()) {
                        ready.add(session.drain());
                    }
                } else if (full || expired) {
                    // Still going: send what we have but keep the session, so we don't fall back
                    // into sending one message per transaction
                    if (session.hasBatch()) {
                        ready.add(session.drain());
                    }
                }
            }
        }

        return ready;
    }

    /**
     * Closes a player's session, for when they log out.
     *
     * @param playerId Player who left
     * @return Their pending batch, or null if they had none
     */
    public Batch take(UUID playerId) {
        Session session = sessions.remove(playerId);
        if (session == null) {
            return null;
        }

        synchronized (session) {
            return session.hasBatch() ? session.drain() : null;
        }
    }

    /**
     * Closes every session, for shutdown - a batch must not be lost because the server stopped.
     *
     * @return Every pending batch
     */
    public List<Batch> takeAll() {
        List<Batch> ready = new ArrayList<Batch>();

        for (UUID playerId : new ArrayList<UUID>(sessions.keySet())) {
            Batch batch = take(playerId);

            if (batch != null) {
                ready.add(batch);
            }
        }

        return ready;
    }

    public void clear() {
        sessions.clear();
    }

    public int getOpenSessions() {
        return sessions.size();
    }
}
