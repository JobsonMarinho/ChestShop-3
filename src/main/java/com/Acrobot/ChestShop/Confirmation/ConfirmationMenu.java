package com.Acrobot.ChestShop.Confirmation;

import com.Acrobot.Breeze.Configuration.Configuration;
import com.Acrobot.Breeze.Utils.InventoryUtil;
import com.Acrobot.Breeze.Utils.MaterialUtil;
import com.Acrobot.ChestShop.ChestShop;
import com.Acrobot.ChestShop.Configuration.Messages;
import com.Acrobot.ChestShop.Configuration.Properties;
import com.Acrobot.ChestShop.Economy.Economy;
import com.Acrobot.ChestShop.Permission;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.Acrobot.ChestShop.Events.TransactionEvent.TransactionType.BUY;

/**
 * The 27 slot menu a player has to go through before a transaction is made.
 *
 * <pre>
 *   . . . . . . . . .
 *   . . A . I . D . .     A - accept, D - decline, I - the item being traded
 *   . . . . . . . . S     S - opens the player's own confirmation settings
 * </pre>
 *
 * The slots are configurable; everything else stays empty on purpose.
 *
 * The menu holds nothing but display copies: every click on it is cancelled by
 * {@link ConfirmationListener}, so no item can ever be dragged out of here.
 *
 * @author Acrobot
 */
public class ConfirmationMenu implements MenuHolder {
    public static final int MENU_SIZE = 27;

    private static final int DEFAULT_ACCEPT_SLOT = 11;
    private static final int DEFAULT_DECLINE_SLOT = 15;
    private static final int DEFAULT_ITEM_SLOT = 13;

    /** Minecraft refuses to open an inventory whose title is longer than this */
    private static final int MAX_TITLE_LENGTH = 32;

    /** Keeps a misconfigured layout from filling the console, one line per menu opened */
    private static boolean layoutWarned = false;

    private final UUID viewerId;
    private final PendingConfirmation pending;
    private final Inventory inventory;

    // Resolved once per menu, so a reload can't move the buttons under a menu that is already open
    private final int acceptSlot;
    private final int declineSlot;
    private final int settingsSlot;

    public ConfirmationMenu(Player viewer, PendingConfirmation pending) {
        this.viewerId = viewer.getUniqueId();
        this.pending = pending;
        this.inventory = Bukkit.createInventory(this, MENU_SIZE, getTitle(pending));

        int accept = resolveSlot(Properties.CONFIRMATION_ACCEPT_SLOT, DEFAULT_ACCEPT_SLOT);
        int decline = resolveSlot(Properties.CONFIRMATION_DECLINE_SLOT, DEFAULT_DECLINE_SLOT);
        int item = resolveSlot(Properties.CONFIRMATION_ITEM_SLOT, DEFAULT_ITEM_SLOT);

        if (accept == decline || accept == item || decline == item) {
            warnAboutLayout("The confirmation menu has two things configured for the same slot ("
                    + accept + "/" + decline + "/" + item + "), falling back to the default layout");

            accept = DEFAULT_ACCEPT_SLOT;
            decline = DEFAULT_DECLINE_SLOT;
            item = DEFAULT_ITEM_SLOT;
        }

        this.acceptSlot = accept;
        this.declineSlot = decline;
        this.settingsSlot = resolveSettingsSlot(viewer, accept, decline, item);

        inventory.setItem(accept, MenuButtons.create(Properties.CONFIRMATION_ACCEPT_ITEM, Material.WOOL, (short) 5,
                Messages.CONFIRMATION_ACCEPT_NAME, Messages.CONFIRMATION_ACCEPT_LORE));
        inventory.setItem(decline, MenuButtons.create(Properties.CONFIRMATION_DECLINE_ITEM, Material.WOOL, (short) 14,
                Messages.CONFIRMATION_DECLINE_NAME, Messages.CONFIRMATION_DECLINE_LORE));
        inventory.setItem(item, createOfferItem(pending));

        if (settingsSlot != -1) {
            inventory.setItem(settingsSlot, MenuButtons.create(Properties.CONFIRMATION_SETTINGS_ITEM,
                    Material.REDSTONE_COMPARATOR, (short) 0,
                    Messages.CONFIRMATION_SETTINGS_NAME, Messages.CONFIRMATION_SETTINGS_LORE));
        }
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

    public boolean isAcceptSlot(int slot) {
        return slot == acceptSlot;
    }

    public boolean isDeclineSlot(int slot) {
        return slot == declineSlot;
    }

    public boolean isSettingsSlot(int slot) {
        return settingsSlot != -1 && slot == settingsSlot;
    }

    /**
     * Lets a misconfigured layout be seen once instead of on every single menu.
     */
    public static void resetLayoutWarning() {
        layoutWarned = false;
    }

    private static void warnAboutLayout(String message) {
        if (!layoutWarned) {
            layoutWarned = true;
            ChestShop.getBukkitLogger().warning(message);
        }
    }

    private static int resolveSlot(int configured, int fallback) {
        return configured >= 0 && configured < MENU_SIZE ? configured : fallback;
    }

    /**
     * @return Where the settings button goes, or -1 if it shouldn't be shown at all
     */
    private static int resolveSettingsSlot(Player viewer, int accept, int decline, int item) {
        if (!ConfirmationManager.canChangePreferences(viewer)) {
            return -1; //Nothing for this player to change in there
        }

        int slot = Properties.CONFIRMATION_SETTINGS_SLOT;
        if (slot < 0 || slot >= MENU_SIZE) {
            return -1; //Deliberately hidden
        }

        if (slot == accept || slot == decline || slot == item) {
            warnAboutLayout("The confirmation menu's settings button is configured for slot " + slot
                    + ", which is already taken - hiding the button");
            return -1;
        }

        return slot;
    }

    private static String getTitle(PendingConfirmation pending) {
        String title = Configuration.getColoured(pending.getTransactionType() == BUY
                ? Messages.CONFIRMATION_TITLE_BUY
                : Messages.CONFIRMATION_TITLE_SELL);

        return title.length() > MAX_TITLE_LENGTH ? title.substring(0, MAX_TITLE_LENGTH) : title;
    }

    /**
     * Spells the offer out, one line per entry of CONFIRMATION_ITEM_LORE.
     *
     * Shared with the Bedrock form, so a player on either platform reads exactly the same terms.
     *
     * @param pending Offer to describe
     * @return The coloured lines
     */
    public static List<String> describeOffer(PendingConfirmation pending) {
        int amount = pending.getItemAmount();
        double unitPrice = amount > 0 ? pending.getPrice() / amount : pending.getPrice();

        List<String> lines = new ArrayList<String>();
        for (String line : Messages.CONFIRMATION_ITEM_LORE) {
            lines.add(Configuration.getColoured(line
                    .replace("%type", pending.getTransactionType() == BUY ? Messages.CONFIRMATION_TYPE_BUY : Messages.CONFIRMATION_TYPE_SELL)
                    .replace("%item", getItemName(getDisplayItem(pending)))
                    .replace("%amount", String.valueOf(amount))
                    .replace("%unitprice", Economy.formatBalance(unitPrice))
                    .replace("%price", Economy.formatBalance(pending.getPrice()))
                    .replace("%owner", pending.getOwnerName())));
        }

        return lines;
    }

    private static ItemStack getDisplayItem(PendingConfirmation pending) {
        ItemStack[] merged = InventoryUtil.mergeSimilarStacks(pending.getStock());

        return merged.length > 0 ? merged[0].clone() : new ItemStack(Material.BARRIER);
    }

    /**
     * The item in the middle: a display copy of what is being traded, with the whole offer written
     * into its lore so the player can see exactly what they are about to agree to.
     */
    private static ItemStack createOfferItem(PendingConfirmation pending) {
        ItemStack display = getDisplayItem(pending);
        display.setAmount(Math.max(1, Math.min(pending.getItemAmount(), display.getMaxStackSize())));

        List<String> lore = describeOffer(pending);

        ItemMeta meta = display.getItemMeta();
        if (meta != null) {
            if (meta.hasLore()) {
                lore.add("");
                lore.addAll(meta.getLore());
            }

            meta.setLore(lore);
            display.setItemMeta(meta);
        }

        // This is only ever a display copy - the items a transaction really moves are never marked
        return MenuItemGuard.mark(display);
    }

    private static String getItemName(ItemStack item) {
        ItemMeta meta = item.getItemMeta();

        if (meta != null && meta.hasDisplayName()) {
            return meta.getDisplayName();
        }

        return MaterialUtil.getName(item);
    }
}
