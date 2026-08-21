package com.Acrobot.ChestShop.Confirmation;

import com.Acrobot.Breeze.Utils.MaterialUtil;
import com.Acrobot.ChestShop.ChestShop;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.logging.Level;

/**
 * Second line of defence for the confirmation menu.
 *
 * Every item shown in the menu is a real ItemStack. Cancelling the clicks stops it from being taken
 * out, but a cancel can lose the race - a laggy double click, a drag, or another plugin that
 * un-cancels the event at a later priority - and then a wool button, or worse a display copy of a
 * diamond helmet, lands in a real inventory as a free item.
 *
 * So each menu item also gets an invisible NBT mark, and the mark means exactly one thing: <b>this
 * item was born inside a menu and must not exist in a real inventory</b>. The listeners sweep it out
 * of wherever it ended up. Marking without sweeping stops nothing; sweeping without marking would
 * delete legitimate items. Both halves are needed.
 *
 * Note that only the menu's own display copies are ever marked - never the items a transaction
 * actually moves, which would make the sweep eat what the player just bought.
 *
 * @author Acrobot
 */
public class MenuItemGuard {
    /** Own key, so we never collide with (or delete) another plugin's tagged items */
    private static final String MENU_TAG = "ChestShopMenuItem";
    private static final String MENU_TAG_VALUE = "1";

    private static ItemTagger tagger;

    /**
     * Picks how items get tagged on this server. Called once, when the plugin starts.
     */
    public static void initialize() {
        tagger = null;

        try {
            tagger = new NmsItemTagger();
            ChestShop.getBukkitLogger().info("Confirmation menu anti-dupe: tagging items with " + tagger.getName());
        } catch (Throwable couldNotTag) {
            ChestShop.getBukkitLogger().log(Level.WARNING, "Confirmation menu anti-dupe: items can not be tagged on this server ("
                    + couldNotTag.getMessage() + "). The menu still refuses every click, but the inventory sweep is off.");
        }
    }

    /**
     * @return Can menu items be marked and swept on this server?
     */
    public static boolean isActive() {
        return tagger != null;
    }

    /**
     * Marks an item as belonging to a menu.
     *
     * Call this <b>last</b>, after the display name, the lore and everything else is set: applying
     * an ItemMeta rebuilds the item's NBT and would drop the mark again.
     *
     * @param item Item to mark
     * @return The marked item - a different instance, so use the return value
     */
    public static ItemStack mark(ItemStack item) {
        if (tagger == null || MaterialUtil.isEmpty(item)) {
            return item;
        }

        try {
            return tagger.write(item, MENU_TAG, MENU_TAG_VALUE);
        } catch (Throwable couldNotTag) {
            // Better to open an unmarked menu than to swallow the transaction, but say so loudly
            ChestShop.getBukkitLogger().log(Level.SEVERE, "Confirmation menu anti-dupe: tagging failed, turning the sweep off", couldNotTag);
            tagger = null;

            return item;
        }
    }

    /**
     * @param item Item to check
     * @return Did this item come out of a confirmation menu?
     */
    public static boolean isMenuItem(ItemStack item) {
        return tagger != null && !MaterialUtil.isEmpty(item) && tagger.has(item, MENU_TAG);
    }

    /**
     * Removes every menu item from a player's inventory, armour and cursor.
     *
     * Slots are cleared by index rather than with {@link org.bukkit.inventory.Inventory#remove},
     * which would also throw away the player's legitimate stacks of the same item.
     *
     * @param player Player to sweep
     * @param reason Where the sweep came from, for the log
     * @return How many stacks were taken away
     */
    public static int sweep(Player player, String reason) {
        if (tagger == null || player == null || !player.isOnline()) {
            return 0;
        }

        int removed = 0;
        PlayerInventory inventory = player.getInventory();

        ItemStack[] contents = inventory.getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            if (isMenuItem(contents[slot])) {
                inventory.setItem(slot, null);
                removed++;
            }
        }

        // A shop can sell armour, so the display copy in the middle of the menu can be worn
        ItemStack[] armour = inventory.getArmorContents();
        boolean armourChanged = false;
        for (int slot = 0; slot < armour.length; slot++) {
            if (isMenuItem(armour[slot])) {
                armour[slot] = null;
                armourChanged = true;
                removed++;
            }
        }
        if (armourChanged) {
            inventory.setArmorContents(armour);
        }

        if (isMenuItem(player.getItemOnCursor())) {
            player.setItemOnCursor(null);
            removed++;
        }

        if (removed > 0) {
            // A marked item in a real inventory is a sign of an exploit, not just leftover dirt
            ChestShop.getBukkitLogger().warning("Confirmation menu anti-dupe: removed " + removed
                    + " menu item(s) from " + player.getName() + "'s inventory (" + reason + ")");
            player.updateInventory();
        }

        return removed;
    }
}
