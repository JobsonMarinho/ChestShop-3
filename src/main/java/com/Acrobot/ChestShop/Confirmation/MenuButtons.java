package com.Acrobot.ChestShop.Confirmation;

import com.Acrobot.Breeze.Configuration.Configuration;
import com.Acrobot.Breeze.Utils.MaterialUtil;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the buttons both menus are made of.
 *
 * @author Acrobot
 */
class MenuButtons {

    /**
     * Builds a button out of an item code from the config, falling back to a hardcoded item if the
     * server owner typed something we can't parse.
     *
     * @param itemCode     Item code, written like on a shop sign
     * @param fallback     Material to use when the code can't be read
     * @param fallbackData Data value of that material
     * @param name         Display name, with '&' colour codes
     * @param lore         Lore lines, with '&' colour codes
     * @return The finished, anti-dupe marked button
     */
    static ItemStack create(String itemCode, Material fallback, short fallbackData, String name, List<String> lore) {
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

    static List<String> colour(List<String> lines) {
        List<String> coloured = new ArrayList<String>(lines.size());

        for (String line : lines) {
            coloured.add(Configuration.getColoured(line));
        }

        return coloured;
    }
}
