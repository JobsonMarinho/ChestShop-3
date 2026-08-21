package com.Acrobot.ChestShop.Confirmation;

import com.Acrobot.Breeze.Configuration.Configuration;
import com.Acrobot.Breeze.Utils.InventoryUtil;
import com.Acrobot.Breeze.Utils.MaterialUtil;
import com.Acrobot.ChestShop.ChestShop;
import com.Acrobot.ChestShop.Configuration.Messages;
import com.Acrobot.ChestShop.Configuration.Properties;
import com.Acrobot.ChestShop.Economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

import static com.Acrobot.ChestShop.Events.TransactionEvent.TransactionType.BUY;

/**
 * The 27 slot menu a player has to go through before a transaction is made.
 *
 * <pre>
 *   . . . . . . . . .
 *   . . A . I . D . .     A - accept, D - decline, I - the item being traded
 *   . . . . . . . . .
 * </pre>
 *
 * The three slots are configurable; everything else stays empty on purpose.
 *
 * The menu holds nothing but display copies: every click on it is cancelled by
 * {@link ConfirmationListener}, so no item can ever be dragged out of here.
 *
 * @author Acrobot
 */
public class ConfirmationMenu implements InventoryHolder {
    public static final int MENU_SIZE = 27;

    private static final int DEFAULT_ACCEPT_SLOT = 11;
    private static final int DEFAULT_DECLINE_SLOT = 15;
    private static final int DEFAULT_ITEM_SLOT = 13;

    /** Minecraft refuses to open an inventory whose title is longer than this */
    private static final int MAX_TITLE_LENGTH = 32;

    private final PendingConfirmation pending;
    private final Inventory inventory;

    // Resolved once per menu, so a reload can't move the buttons under an open menu
    private final int acceptSlot;
    private final int declineSlot;

    public ConfirmationMenu(PendingConfirmation pending) {
        this.pending = pending;
        this.inventory = Bukkit.createInventory(this, MENU_SIZE, getTitle(pending));

        int accept = resolveSlot(Properties.CONFIRMATION_ACCEPT_SLOT, DEFAULT_ACCEPT_SLOT);
        int decline = resolveSlot(Properties.CONFIRMATION_DECLINE_SLOT, DEFAULT_DECLINE_SLOT);
        int item = resolveSlot(Properties.CONFIRMATION_ITEM_SLOT, DEFAULT_ITEM_SLOT);

        if (accept == decline || accept == item || decline == item) {
            ChestShop.getBukkitLogger().warning("The confirmation menu has two things configured for the same slot ("
                    + accept + "/" + decline + "/" + item + "), falling back to the default layout");

            accept = DEFAULT_ACCEPT_SLOT;
            decline = DEFAULT_DECLINE_SLOT;
            item = DEFAULT_ITEM_SLOT;
        }

        this.acceptSlot = accept;
        this.declineSlot = decline;

        inventory.setItem(accept, createButton(Properties.CONFIRMATION_ACCEPT_ITEM, Material.WOOL, (short) 5,
                Messages.CONFIRMATION_ACCEPT_NAME, Messages.CONFIRMATION_ACCEPT_LORE));
        inventory.setItem(decline, createButton(Properties.CONFIRMATION_DECLINE_ITEM, Material.WOOL, (short) 14,
                Messages.CONFIRMATION_DECLINE_NAME, Messages.CONFIRMATION_DECLINE_LORE));
        inventory.setItem(item, createOfferItem(pending));
    }

    public Inventory getInventory() {
        return inventory;
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

    private static int resolveSlot(int configured, int fallback) {
        return configured >= 0 && configured < MENU_SIZE ? configured : fallback;
    }

    private static String getTitle(PendingConfirmation pending) {
        String title = Configuration.getColoured(pending.getTransactionType() == BUY
                ? Messages.CONFIRMATION_TITLE_BUY
                : Messages.CONFIRMATION_TITLE_SELL);

        return title.length() > MAX_TITLE_LENGTH ? title.substring(0, MAX_TITLE_LENGTH) : title;
    }

    /**
     * Builds one of the two buttons out of an item code from the config, falling back to a
     * hardcoded item if the server owner typed something we can't parse.
     */
    private static ItemStack createButton(String itemCode, Material fallback, short fallbackData, String name, List<String> lore) {
        ItemStack item = null;

        if (itemCode != null && !itemCode.trim().isEmpty()) {
            item = MaterialUtil.getItem(itemCode);
        }

        if (item == null || item.getType() == Material.AIR) {
            item = new ItemStack(fallback, 1, fallbackData);
        }

        item.setAmount(1);

        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(Configuration.getColoured(name));
            meta.setLore(colour(lore));
            item.setItemMeta(meta);
        }

        // Marked last: applying an ItemMeta rebuilds the item's NBT and would drop the mark
        return MenuItemGuard.mark(item);
    }

    /**
     * The item in the middle: a display copy of what is being traded, with the whole offer written
     * into its lore so the player can see exactly what they are about to agree to.
     */
    private static ItemStack createOfferItem(PendingConfirmation pending) {
        ItemStack[] merged = InventoryUtil.mergeSimilarStacks(pending.getStock());

        ItemStack display = merged.length > 0 ? merged[0].clone() : new ItemStack(Material.BARRIER);
        display.setAmount(Math.max(1, Math.min(pending.getItemAmount(), display.getMaxStackSize())));

        int amount = pending.getItemAmount();
        double unitPrice = amount > 0 ? pending.getPrice() / amount : pending.getPrice();

        List<String> lore = new ArrayList<String>();
        for (String line : Messages.CONFIRMATION_ITEM_LORE) {
            lore.add(Configuration.getColoured(line
                    .replace("%type", pending.getTransactionType() == BUY ? Messages.CONFIRMATION_TYPE_BUY : Messages.CONFIRMATION_TYPE_SELL)
                    .replace("%item", getItemName(display))
                    .replace("%amount", String.valueOf(amount))
                    .replace("%unitprice", Economy.formatBalance(unitPrice))
                    .replace("%price", Economy.formatBalance(pending.getPrice()))
                    .replace("%owner", pending.getOwnerName())));
        }

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

    private static List<String> colour(List<String> lines) {
        List<String> coloured = new ArrayList<String>(lines.size());

        for (String line : lines) {
            coloured.add(Configuration.getColoured(line));
        }

        return coloured;
    }
}
