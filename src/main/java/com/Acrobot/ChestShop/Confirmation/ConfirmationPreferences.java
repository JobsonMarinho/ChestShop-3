package com.Acrobot.ChestShop.Confirmation;

import com.Acrobot.ChestShop.ChestShop;
import com.Acrobot.ChestShop.Configuration.Properties;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Per player settings of the transaction confirmation menu, persisted in confirmations.yml.
 *
 * A player can decide, separately for Admin Shops and for player shops, whether they want to be
 * asked before a transaction goes through. A player who never touched the setting simply follows
 * the CONFIRMATION_ENABLED_BY_DEFAULT option of the config.
 *
 * @author Acrobot
 */
public class ConfirmationPreferences {
    private static final String FILE_NAME = "confirmations.yml";
    private static final String PLAYERS_SECTION = "players";
    private static final String ADMIN_SHOPS_KEY = "adminShops";
    private static final String PLAYER_SHOPS_KEY = "playerShops";

    /** How long we wait before flushing changes to the disk, in ticks */
    private static final long SAVE_DELAY = 100L;

    private static final Map<UUID, Preference> PREFERENCES = new ConcurrentHashMap<UUID, Preference>();

    private static File file;
    private static int saveTaskId = -1;

    public static void load() {
        file = ChestShop.loadFile(FILE_NAME);
        PREFERENCES.clear();

        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection players = config.getConfigurationSection(PLAYERS_SECTION);
        if (players == null) {
            return;
        }

        for (String key : players.getKeys(false)) {
            UUID uuid;
            try {
                uuid = UUID.fromString(key);
            } catch (IllegalArgumentException invalidUUID) {
                ChestShop.getBukkitLogger().log(Level.WARNING, "Skipping invalid player id in " + FILE_NAME + ": " + key);
                continue;
            }

            ConfigurationSection playerSection = players.getConfigurationSection(key);
            if (playerSection == null) {
                continue;
            }

            Preference preference = new Preference();
            if (playerSection.isBoolean(ADMIN_SHOPS_KEY)) {
                preference.adminShops = playerSection.getBoolean(ADMIN_SHOPS_KEY);
            }
            if (playerSection.isBoolean(PLAYER_SHOPS_KEY)) {
                preference.playerShops = playerSection.getBoolean(PLAYER_SHOPS_KEY);
            }

            if (!preference.isEmpty()) {
                PREFERENCES.put(uuid, preference);
            }
        }
    }

    /**
     * Writes any pending change to the disk and forgets everything. Called when the plugin shuts down.
     */
    public static void unload() {
        if (saveTaskId != -1) {
            Bukkit.getScheduler().cancelTask(saveTaskId);
            saveTaskId = -1;
        }

        write(serialize());
        PREFERENCES.clear();
    }

    /**
     * Does this player want to be asked before a transaction in this kind of shop goes through?
     *
     * @param player    Player to check
     * @param adminShop Is the shop an Admin Shop?
     * @return The player's choice, or the server's default if they never made one
     */
    public static boolean wantsConfirmation(Player player, boolean adminShop) {
        Preference preference = PREFERENCES.get(player.getUniqueId());
        Boolean choice = preference == null ? null : (adminShop ? preference.adminShops : preference.playerShops);

        return choice != null ? choice : Properties.CONFIRMATION_ENABLED_BY_DEFAULT;
    }

    /**
     * Stores a player's choice for one kind of shop
     *
     * @param player    Player whose choice it is
     * @param adminShop Is the choice about Admin Shops?
     * @param enabled   Should the menu be shown?
     */
    public static void setConfirmation(Player player, boolean adminShop, boolean enabled) {
        UUID uuid = player.getUniqueId();

        Preference preference = PREFERENCES.get(uuid);
        if (preference == null) {
            preference = new Preference();
            PREFERENCES.put(uuid, preference);
        }

        if (adminShop) {
            preference.adminShops = enabled;
        } else {
            preference.playerShops = enabled;
        }

        scheduleSave();
    }

    private static void scheduleSave() {
        if (saveTaskId != -1 || ChestShop.getPlugin() == null || !ChestShop.getPlugin().isEnabled()) {
            return;
        }

        saveTaskId = Bukkit.getScheduler().runTaskLater(ChestShop.getPlugin(), new Runnable() {
            public void run() {
                saveTaskId = -1;

                // Serialising has to happen on the main thread, only the file write is offloaded
                final String content = serialize();
                Bukkit.getScheduler().runTaskAsynchronously(ChestShop.getPlugin(), new Runnable() {
                    public void run() {
                        write(content);
                    }
                });
            }
        }, SAVE_DELAY).getTaskId();
    }

    private static String serialize() {
        YamlConfiguration config = new YamlConfiguration();
        ConfigurationSection players = config.createSection(PLAYERS_SECTION);

        for (Map.Entry<UUID, Preference> entry : PREFERENCES.entrySet()) {
            Preference preference = entry.getValue();
            if (preference.isEmpty()) {
                continue;
            }

            ConfigurationSection playerSection = players.createSection(entry.getKey().toString());
            if (preference.adminShops != null) {
                playerSection.set(ADMIN_SHOPS_KEY, preference.adminShops);
            }
            if (preference.playerShops != null) {
                playerSection.set(PLAYER_SHOPS_KEY, preference.playerShops);
            }
        }

        return config.saveToString();
    }

    private static void write(String content) {
        if (file == null) {
            return;
        }

        try {
            Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            ChestShop.getBukkitLogger().log(Level.WARNING, "Could not save " + FILE_NAME, e);
        }
    }

    private static class Preference {
        /** null means "no choice made, follow the config" */
        private Boolean adminShops;
        private Boolean playerShops;

        private boolean isEmpty() {
            return adminShops == null && playerShops == null;
        }
    }
}
