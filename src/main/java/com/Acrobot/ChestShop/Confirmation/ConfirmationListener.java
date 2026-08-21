package com.Acrobot.ChestShop.Confirmation;

import com.Acrobot.ChestShop.ChestShop;
import com.Acrobot.ChestShop.Configuration.Messages;
import com.Acrobot.ChestShop.Configuration.Properties;
import com.Acrobot.ChestShop.Permission;
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
 * Drives ChestShop's menus and, just as importantly, seals them shut.
 *
 * The menus show copies of real items. If a player could shift click, drag, hotbar swap or drop one
 * of them, a menu would hand out free items - so every single click and drag on them is refused, no
 * matter which slot it lands on, before any button is even looked at.
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
        MenuHolder menu = ConfirmationManager.getMenuHolder(event.getView().getTopInventory());
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
        if (!menu.getViewerId().equals(player.getUniqueId())) {
            return;
        }

        int slot = event.getRawSlot();
        if (slot < 0 || slot >= event.getView().getTopInventory().getSize()) {
            return; //The player clicked their own inventory
        }

        if (menu instanceof ConfirmationMenu) {
            onConfirmationClick(player, (ConfirmationMenu) menu, slot);
        } else if (menu instanceof PreferencesMenu) {
            onPreferencesClick(player, (PreferencesMenu) menu, slot);
        }
    }

    private static void onConfirmationClick(Player player, ConfirmationMenu menu, int slot) {
        if (menu.isAcceptSlot(slot)) {
            ConfirmationManager.accept(player, menu.getPending());
        } else if (menu.isDeclineSlot(slot)) {
            ConfirmationManager.decline(player, menu.getPending());
        } else if (menu.isDismissSlot(slot)) {
            ConfirmationManager.acceptAndStopAsking(player, menu.getPending());
        } else if (menu.isSettingsSlot(slot)) {
            ConfirmationManager.openPreferences(player, menu.getPending());
        }
    }

    private static void onPreferencesClick(Player player, PreferencesMenu menu, int slot) {
        if (menu.isBackSlot(slot)) {
            ConfirmationManager.returnToConfirmation(player, menu.getPending());
            return;
        }

        boolean adminShop = menu.isAdminShopsSlot(slot);
        if (!adminShop && !menu.isPlayerShopsSlot(slot)) {
            return;
        }

        // The menu is only built for players who may change this, but the config can be reloaded
        // while it is open, so the permission is checked again on the click that acts on it
        if (!Properties.CONFIRMATION_ALLOW_PLAYER_TOGGLE || !Permission.has(player, Permission.CONFIRMATION_TOGGLE)) {
            player.sendMessage(Messages.prefix(Messages.CONFIRMATION_TOGGLE_BLOCKED));
            return;
        }

        if (!PreferencesMenu.isChangeable(adminShop)) {
            return; //The server turned confirmations off there - the lore already says so
        }

        boolean enabled = !ConfirmationPreferences.wantsConfirmation(player, adminShop);
        ConfirmationPreferences.setConfirmation(player, adminShop, enabled);

        // Redrawn in place: reopening would fire a close event and drop the offer still waiting
        menu.refresh(player);
        player.updateInventory();
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (ConfirmationManager.getMenuHolder(event.getView().getTopInventory()) == null) {
            return;
        }

        event.setCancelled(true);
        event.setResult(Event.Result.DENY);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        MenuHolder menu = ConfirmationManager.getMenuHolder(event.getInventory());
        if (menu == null || !(event.getPlayer() instanceof Player)) {
            return;
        }

        final Player player = (Player) event.getPlayer();

        // Walking from the confirmation menu into its settings and back closes one to open the
        // other; only a close that really ends the visit drops the offer
        if (!ConfirmationManager.isSwitchingMenus(player)) {
            ConfirmationManager.cancel(player, Messages.CONFIRMATION_CANCELLED);
        }

        // A menu item can only enter a real inventory through one of these menus, so sweeping when
        // one closes - rather than on every inventory close on the server - covers the same ground
        sweepLater(player, "closed a ChestShop menu", CLOSE_SWEEP_DELAY);
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
