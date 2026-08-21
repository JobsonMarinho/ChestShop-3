package com.Acrobot.ChestShop.Confirmation;

import org.bukkit.inventory.ItemStack;

/**
 * Writes and reads an invisible NBT string on an item.
 *
 * Kept behind an interface so the way the tag is stored can be swapped without touching the code
 * that marks menu items or sweeps inventories.
 *
 * @author Acrobot
 */
public interface ItemTagger {
    /**
     * @return Short name of this implementation, for the startup log
     */
    String getName();

    /**
     * Writes a tag onto an item.
     *
     * The item is <b>not</b> modified in place - the NMS route works on copies, so the returned
     * stack is the one carrying the tag. Always use the return value.
     *
     * @param stack Item to tag
     * @param key   Tag name
     * @param value Tag value
     * @return The tagged item
     */
    ItemStack write(ItemStack stack, String key, String value);

    /**
     * @param stack Item to read
     * @param key   Tag name
     * @return The tag's value, or null if the item doesn't carry it
     */
    String read(ItemStack stack, String key);

    /**
     * @param stack Item to check
     * @param key   Tag name
     * @return Does the item carry this tag?
     */
    boolean has(ItemStack stack, String key);
}
