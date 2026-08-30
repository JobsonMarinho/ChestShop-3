package com.Acrobot.ChestShop.Discord;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rate limit for events that are not transactions.
 *
 * Transactions have their own answer to flooding - see {@link TransactionAggregator}, which keeps
 * every one of them. This is the blunt instrument for the rest: repeated denied clicks, a player
 * breaking a row of shops, and other things where the second identical line adds nothing.
 *
 * Two levels: per event across the server, and per (event, player). CRITICAL and HIGH events skip
 * both, because a database error that gets rate limited is an error nobody hears about.
 *
 * @author Acrobot
 */
public class AntiFlood {
    public enum Decision {
        ALLOW,
        BLOCK_EVENT,
        BLOCK_PLAYER
    }

    private final Map<String, Long> lastByEvent = new ConcurrentHashMap<String, Long>();
    private final Map<String, Long> lastByPlayerEvent = new ConcurrentHashMap<String, Long>();

    /**
     * @param eventKey  Event being dispatched
     * @param playerKey Player it belongs to, or null for an event with no player
     * @param critical  Does this event skip the limits?
     * @param settings  Current anti-flood settings
     * @return Whether it may go out
     */
    public Decision check(String eventKey, String playerKey, boolean critical, DiscordConfig.AntiFloodSettings settings) {
        if (critical && settings.isIgnoreForCritical()) {
            return Decision.ALLOW;
        }

        long now = System.currentTimeMillis();

        if (settings.isPerEventEnabled() && settings.getPerEventCooldownSeconds() > 0) {
            Long last = lastByEvent.get(eventKey);

            if (last != null && now - last < settings.getPerEventCooldownSeconds() * 1000L) {
                return Decision.BLOCK_EVENT;
            }

            lastByEvent.put(eventKey, now);
        }

        if (playerKey != null && settings.isPerPlayerEnabled() && settings.getPerPlayerCooldownSeconds() > 0) {
            String combined = eventKey + "|" + playerKey;
            Long last = lastByPlayerEvent.get(combined);

            if (last != null && now - last < settings.getPerPlayerCooldownSeconds() * 1000L) {
                return Decision.BLOCK_PLAYER;
            }

            lastByPlayerEvent.put(combined, now);
        }

        return Decision.ALLOW;
    }

    public void clear() {
        lastByEvent.clear();
        lastByPlayerEvent.clear();
    }
}
