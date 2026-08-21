package com.Acrobot.ChestShop.Confirmation;

import org.bukkit.inventory.InventoryHolder;

import java.util.UUID;

/**
 * Marks an inventory as one of ChestShop's own menus.
 *
 * Everything the listener has to do to every menu alike - refuse every click and drag, sweep the
 * viewer afterwards - keys off this interface, so a new menu is protected the moment it implements it.
 *
 * @author Acrobot
 */
public interface MenuHolder extends InventoryHolder {
    /**
     * @return The player this menu was built for
     */
    UUID getViewerId();
}
