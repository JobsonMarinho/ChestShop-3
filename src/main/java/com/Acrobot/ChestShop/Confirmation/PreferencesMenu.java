package com.Acrobot.ChestShop.Confirmation;

import com.Acrobot.Breeze.Configuration.Configuration;
import com.Acrobot.ChestShop.Configuration.Messages;
import com.Acrobot.ChestShop.Configuration.Properties;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

import java.util.List;
import java.util.UUID;

/**
 * The settings the confirmation menu's button opens: whether this player wants to be asked before a
 * transaction, separately for Admin Shops and for shops owned by other players.
 *
 * <pre>
 *   . . . . . . . . .
 *   . . A . . . P . .     A - Admin Shops, P - player shops
 *   . . . . B . . . .     B - back to the offer the player came from
 * </pre>
 *
 * This is the same thing /csconfirm does, reachable without leaving the menu. It always belongs to
 * one pending offer: the player got here from a transaction and goes back to it.
 *
 * @author Acrobot
 */
public class PreferencesMenu implements MenuHolder {
    public static final int MENU_SIZE = 27;

    private static final int ADMIN_SHOPS_SLOT = 11;
    private static final int PLAYER_SHOPS_SLOT = 15;
    private static final int BACK_SLOT = 22;

    /** Minecraft refuses to open an inventory whose title is longer than this */
    private static final int MAX_TITLE_LENGTH = 32;

    private final UUID viewerId;
    private final PendingConfirmation pending;
    private final Inventory inventory;

    public PreferencesMenu(Player viewer, PendingConfirmation pending) {
        this.viewerId = viewer.getUniqueId();
        this.pending = pending;
        this.inventory = Bukkit.createInventory(this, MENU_SIZE, getTitle());

        refresh(viewer);
    }

    /**
     * Redraws the toggles in place. Flipping a setting doesn't reopen the menu - reopening would
     * fire a close event and drop the offer the player still has waiting.
     *
     * @param viewer Player looking at the menu
     */
    public void refresh(Player viewer) {
        inventory.setItem(ADMIN_SHOPS_SLOT, createToggle(viewer, true));
        inventory.setItem(PLAYER_SHOPS_SLOT, createToggle(viewer, false));
        inventory.setItem(BACK_SLOT, MenuButtons.create(Properties.CONFIRMATION_BACK_ITEM, Material.ARROW, (short) 0,
                Messages.CONFIRMATION_BACK_NAME, Messages.CONFIRMATION_BACK_LORE));
    }

    public Inventory getInventory() {
        return inventory;
    }

    public UUID getViewerId() {
        return viewerId;
    }

    public PendingConfirmation getPending() {
        return pending;
    }

    public boolean isAdminShopsSlot(int slot) {
        return slot == ADMIN_SHOPS_SLOT;
    }

    public boolean isPlayerShopsSlot(int slot) {
        return slot == PLAYER_SHOPS_SLOT;
    }

    public boolean isBackSlot(int slot) {
        return slot == BACK_SLOT;
    }

    /**
     * Is this setting the player's to make, or has the server turned confirmations off for that
     * kind of shop entirely?
     *
     * @param adminShop Is this about Admin Shops?
     * @return Can the player flip this toggle?
     */
    public static boolean isChangeable(boolean adminShop) {
        return adminShop ? Properties.CONFIRMATION_FOR_ADMIN_SHOPS : Properties.CONFIRMATION_FOR_PLAYER_SHOPS;
    }

    /**
     * A toggle shows the accept item while it is on and the decline item while it is off, so the
     * two menus speak the same colour language without a second pair of options to configure.
     */
    private static org.bukkit.inventory.ItemStack createToggle(Player viewer, boolean adminShop) {
        boolean changeable = isChangeable(adminShop);
        boolean enabled = changeable && ConfirmationPreferences.wantsConfirmation(viewer, adminShop);

        String label = adminShop ? Messages.CONFIRMATION_SETTINGS_ADMIN_SHOPS : Messages.CONFIRMATION_SETTINGS_PLAYER_SHOPS;
        List<String> lore = !changeable
                ? Messages.CONFIRMATION_SETTINGS_FORCED_OFF_LORE
                : enabled ? Messages.CONFIRMATION_SETTINGS_ON_LORE : Messages.CONFIRMATION_SETTINGS_OFF_LORE;

        return MenuButtons.create(
                enabled ? Properties.CONFIRMATION_ACCEPT_ITEM : Properties.CONFIRMATION_DECLINE_ITEM,
                Material.WOOL, enabled ? (short) 5 : (short) 14,
                (enabled ? "&a" : "&c") + label, lore);
    }

    private static String getTitle() {
        String title = Configuration.getColoured(Messages.CONFIRMATION_SETTINGS_TITLE);

        return title.length() > MAX_TITLE_LENGTH ? title.substring(0, MAX_TITLE_LENGTH) : title;
    }
}
