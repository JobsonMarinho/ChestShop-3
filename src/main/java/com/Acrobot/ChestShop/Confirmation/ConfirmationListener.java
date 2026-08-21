package com.Acrobot.ChestShop.Confirmation;

import com.Acrobot.ChestShop.Configuration.Messages;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Drives the confirmation menu and, just as importantly, seals it shut.
 *
 * The menu shows a copy of the item that is being traded. If a player could shift click, drag,
 * hotbar swap or drop that copy, the menu itself would hand out free items - so every single click
 * and drag on this inventory is refused, no matter which slot it lands on. The two buttons are
 * handled after the click has already been cancelled.
 *
 * @author Acrobot
 */
public class ConfirmationListener implements Listener {

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventoryClick(InventoryClickEvent event) {
        ConfirmationMenu menu = ConfirmationManager.getMenu(event.getView().getTopInventory());
        if (menu == null) {
            return;
        }

        // Refuse the click first: whatever happens afterwards, no item leaves this menu
        event.setCancelled(true);
        event.setResult(Event.Result.DENY);

        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }

        Player player = (Player) event.getWhoClicked();
        if (!menu.getPending().getClientId().equals(player.getUniqueId())) {
            return;
        }

        int slot = event.getRawSlot();
        if (slot < 0 || slot >= ConfirmationMenu.MENU_SIZE) {
            return; //The player clicked their own inventory
        }

        if (menu.isAcceptSlot(slot)) {
            ConfirmationManager.accept(player, menu.getPending());
        } else if (menu.isDeclineSlot(slot)) {
            ConfirmationManager.decline(player, menu.getPending());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (ConfirmationManager.getMenu(event.getView().getTopInventory()) == null) {
            return;
        }

        event.setCancelled(true);
        event.setResult(Event.Result.DENY);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (ConfirmationManager.getMenu(event.getInventory()) == null || !(event.getPlayer() instanceof Player)) {
            return;
        }

        // Closing the menu in any other way than through the buttons simply drops the offer
        ConfirmationManager.cancel((Player) event.getPlayer(), Messages.CONFIRMATION_CANCELLED);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        ConfirmationManager.discard(event.getPlayer());
    }
}
