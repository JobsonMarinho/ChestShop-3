package com.Acrobot.ChestShop.Confirmation;

import com.Acrobot.ChestShop.ChestShop;
import com.Acrobot.ChestShop.Configuration.Messages;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Drives the confirmation menu and, just as importantly, seals it shut.
 *
 * The menu shows copies of real items. If a player could shift click, drag, hotbar swap or drop one
 * of them, the menu itself would hand out free items - so every single click and drag on this
 * inventory is refused, no matter which slot it lands on.
 *
 * Refusing the click is the first line of defence, not the only one: a cancel can lose the race to
 * lag or to another plugin that un-cancels the event later in the chain. That is what the NBT mark
 * on every menu item is for - see {@link MenuItemGuard}. The sweeps below close the three routes a
 * marked item can take into the real world.
 *
 * @author Acrobot
 */
public class ConfirmationListener implements Listener {
    /** The cursor is only handed back after the close event, so the sweep waits for it to settle */
    private static final long CLOSE_SWEEP_DELAY = 5L;

    /** On join the inventory may not be populated yet */
    private static final long JOIN_SWEEP_DELAY = 10L;

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

        final Player player = (Player) event.getPlayer();

        // Closing the menu in any other way than through the buttons simply drops the offer
        ConfirmationManager.cancel(player, Messages.CONFIRMATION_CANCELLED);

        // A menu item can only enter a real inventory through this menu, so sweeping when this menu
        // closes - rather than on every inventory close on the server - covers the same ground
        sweepLater(player, "closed the confirmation menu", CLOSE_SWEEP_DELAY);
    }

    /**
     * Dropping a marked item makes it an entity in the world, where sweeping an inventory can not
     * reach it any more. The dropped entity is removed instead.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        if (MenuItemGuard.isMenuItem(event.getItemDrop().getItemStack())) {
            event.getItemDrop().remove();
            event.getPlayer().updateInventory();
        }
    }

    /**
     * Catches anything left over from a crash, a restart or an earlier exploit.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        sweepLater(event.getPlayer(), "joined", JOIN_SWEEP_DELAY);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        ConfirmationManager.discard(event.getPlayer());
    }

    private static void sweepLater(final Player player, final String reason, long delay) {
        if (!MenuItemGuard.isActive() || ChestShop.getPlugin() == null || !ChestShop.getPlugin().isEnabled()) {
            return;
        }

        Bukkit.getScheduler().runTaskLater(ChestShop.getPlugin(), new Runnable() {
            public void run() {
                MenuItemGuard.sweep(player, reason);
            }
        }, delay);
    }
}
