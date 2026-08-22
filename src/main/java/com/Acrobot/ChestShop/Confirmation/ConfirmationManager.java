package com.Acrobot.ChestShop.Confirmation;

import com.Acrobot.Breeze.Utils.BlockUtil;
import com.Acrobot.Breeze.Utils.InventoryUtil;
import com.Acrobot.Breeze.Utils.MaterialUtil;
import com.Acrobot.Breeze.Utils.StringUtil;
import com.Acrobot.ChestShop.ChestShop;
import com.Acrobot.ChestShop.Configuration.Messages;
import com.Acrobot.ChestShop.Configuration.Properties;
import com.Acrobot.ChestShop.Events.PreTransactionEvent;
import com.Acrobot.ChestShop.Events.TransactionEvent;
import com.Acrobot.ChestShop.Listeners.Player.PlayerInteract;
import com.Acrobot.ChestShop.Permission;
import com.Acrobot.ChestShop.Signs.ChestShopSign;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.Acrobot.ChestShop.Events.PreTransactionEvent.TransactionOutcome.AWAITING_CONFIRMATION;
import static com.Acrobot.ChestShop.Events.TransactionEvent.TransactionType.BUY;
import static org.bukkit.event.block.Action.LEFT_CLICK_BLOCK;
import static org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK;

/**
 * Keeps track of the transactions players have been asked to confirm and runs the accepted ones.
 *
 * <h2>Why this class is so careful</h2>
 *
 * The obvious way of writing a confirmation menu - remember the items and the price when the menu
 * opens and simply hand them to a {@link TransactionEvent} when the player clicks accept - is also
 * an item duplication bug, and it is the exact bug the old ChestShopConfirmation add-on had. The
 * shop is validated once, at click time, but the transaction is executed later, so a player could
 * click sell, drop the items into a chest (or hand them to a friend) while the menu was open,
 * accept, and still get paid: removing items that are not there anymore silently removes nothing,
 * while the money is still transferred. The same trick works the other way around when buying,
 * since the items handed to the buyer are added to their inventory whether the shop chest still
 * holds them or not.
 *
 * So nothing that was measured when the menu opened is ever reused here. On accept the offer is
 * rebuilt from the world - the sign is read from the block again, the chest is looked up again, the
 * client's inventory and balance are checked again - by running the whole {@link PreTransactionEvent}
 * chain a second time, and only then, in the very same tick, the {@link TransactionEvent} is fired.
 * The snapshot taken when the menu opened is used for one thing only: making sure the freshly built
 * offer is still the offer the player agreed to.
 *
 * @author Acrobot
 */
public class ConfirmationManager {
    /** Prices are doubles, so they are compared with a tolerance instead of with == */
    private static final double PRICE_TOLERANCE = 1e-6;

    /** Main thread only - every Bukkit event this class hooks into is fired on the main thread */
    private static final Map<UUID, PendingConfirmation> PENDING = new HashMap<UUID, PendingConfirmation>();

    /**
     * Players who are being moved from one of our menus to another right now.
     *
     * Opening an inventory closes the one before it, and that close event is what normally throws
     * the offer away. While a player is walking between the confirmation menu and its settings the
     * offer has to survive, so the close is ignored for exactly that moment.
     */
    private static final Set<UUID> SWITCHING_MENUS = new HashSet<UUID>();

    /**
     * Players who turned a confirmation off while in the settings and haven't been told how to turn
     * it back on yet. Kept until they leave the menu, so the explanation lands on a chat they can
     * actually read rather than behind an open inventory.
     */
    private static final Set<UUID> TURNED_OFF_IN_MENU = new HashSet<UUID>();

    /**
     * Should this transaction be shown to the client for confirmation first?
     *
     * @param event Transaction that is about to happen
     * @return Do we have to ask?
     */
    public static boolean requiresConfirmation(PreTransactionEvent event) {
        if (!Properties.CONFIRMATION_ENABLED || event.isConfirmationReplay()) {
            return false;
        }

        Player client = event.getClient();
        if (client == null || !client.isOnline()) {
            return false;
        }

        if (event.getTransactionType() == BUY ? !Properties.CONFIRMATION_FOR_BUYING : !Properties.CONFIRMATION_FOR_SELLING) {
            return false;
        }

        boolean adminShop = ChestShopSign.isAdminShop(event.getSign());
        if (!(adminShop ? Properties.CONFIRMATION_FOR_ADMIN_SHOPS : Properties.CONFIRMATION_FOR_PLAYER_SHOPS)) {
            return false;
        }

        if (Permission.has(client, Permission.CONFIRMATION_BYPASS)) {
            return false;
        }

        return ConfirmationPreferences.wantsConfirmation(client, adminShop);
    }

    /**
     * Puts a transaction on hold and shows it to the client
     *
     * @param event Transaction to put on hold
     */
    public static void hold(PreTransactionEvent event) {
        event.setCancelled(AWAITING_CONFIRMATION);
        ask(event);
    }

    /**
     * Takes a snapshot of the offer and shows it to the client
     *
     * @param event Transaction that has been put on hold
     */
    private static void ask(PreTransactionEvent event) {
        Player client = event.getClient();
        Sign sign = event.getSign();

        discard(client); //Only one offer can be pending per player

        boolean adminShop = ChestShopSign.isAdminShop(sign);

        PendingConfirmation pending = new PendingConfirmation(
                client.getUniqueId(),
                sign.getBlock().getLocation(),
                StringUtil.stripColourCodes(sign.getLines()),
                event.getTransactionType(),
                adminShop,
                client.isSneaking(),
                adminShop ? Properties.ADMIN_SHOP_NAME : event.getOwnerAccount().getName(),
                cloneStock(event.getStock()),
                event.getPrice(),
                InventoryUtil.countItems(event.getStock()));

        if (BedrockForms.isBedrockPlayer(client)) {
            // A form has no inventory to open, so the offer is registered first and taken back if
            // Floodgate could not deliver it
            PENDING.put(client.getUniqueId(), pending);
            scheduleTimeout(client, pending);

            if (!BedrockForms.sendConfirmation(client, pending, canChangePreferences(client))) {
                take(client);
            }

            return;
        }

        // The menu is opened before the offer is registered: opening an inventory closes whatever
        // the player had open, and that close event would otherwise throw this offer away again.
        client.openInventory(new ConfirmationMenu(client, pending).getInventory());

        if (!isViewingMenu(client)) {
            return; //Something refused to let the menu open - then there is nothing to accept either
        }

        PENDING.put(client.getUniqueId(), pending);
        scheduleTimeout(client, pending);
    }

    /**
     * Is there anything in the settings this player is allowed to change?
     *
     * ChestShop.confirmation.toggle is declared with {@code default: true} - deciding whether you
     * want to be asked before your own purchases is not a privilege. Asking for the node outright
     * would hide the buttons from every ordinary player on a server whose permission plugin doesn't
     * pass plugin.yml defaults through, and that failure is invisible: no error, just a menu that
     * quietly lost two buttons. So the node is treated as what it is - something everyone holds
     * until an admin explicitly takes it away.
     *
     * @param player Player to check
     * @return Can this player change their own confirmation settings?
     */
    public static boolean canChangePreferences(Player player) {
        return Properties.CONFIRMATION_ALLOW_PLAYER_TOGGLE && !Permission.isDenied(player, Permission.CONFIRMATION_TOGGLE);
    }

    /**
     * @param player Player to check
     * @return The offer this player has waiting, or null
     */
    public static PendingConfirmation getPending(Player player) {
        return PENDING.get(player.getUniqueId());
    }

    /**
     * Notes that this player just turned a confirmation off, so they can be told how to undo it
     * once they leave the settings.
     *
     * @param player Player who turned it off
     */
    public static void rememberTurnedOff(Player player) {
        TURNED_OFF_IN_MENU.add(player.getUniqueId());
    }

    /**
     * Explains how to turn the confirmation back on, if the player turned one off and left it off.
     *
     * Somebody who switches it off has no reason to know the setting still exists, let alone which
     * command reaches it - so the way back is spelled out once, right when they walk away, rather
     * than left for them to discover.
     *
     * @param player Player who is leaving the settings
     */
    public static void sendTurnedOffHint(Player player) {
        if (!TURNED_OFF_IN_MENU.remove(player.getUniqueId()) || !player.isOnline()) {
            return;
        }

        List<String> disabled = new ArrayList<String>();
        if (PreferencesMenu.isChangeable(true) && !ConfirmationPreferences.wantsConfirmation(player, true)) {
            disabled.add(Messages.CONFIRMATION_SETTINGS_ADMIN_SHOPS);
        }
        if (PreferencesMenu.isChangeable(false) && !ConfirmationPreferences.wantsConfirmation(player, false)) {
            disabled.add(Messages.CONFIRMATION_SETTINGS_PLAYER_SHOPS);
        }

        if (disabled.isEmpty()) {
            return; //Turned it off and back on again before leaving
        }

        player.sendMessage(Messages.prefix(Messages.CONFIRMATION_DISABLED_HINT.replace("%shops", String.join(", ", disabled))));
        player.sendMessage(Messages.prefix(Messages.CONFIRMATION_DISABLED_HINT_COMMAND));
    }

    /**
     * The client clicked the accept button: rebuild the transaction from scratch and, if it is
     * still exactly the one they were shown, run it.
     *
     * @param player     Client of the shop
     * @param menuOffer  The offer the clicked menu was built for
     */
    public static void accept(final Player player, PendingConfirmation menuOffer) {
        if (PENDING.get(player.getUniqueId()) != menuOffer) {
            return; //The menu the player clicked is not the offer that is waiting for them anymore
        }

        final PendingConfirmation pending = take(player); //Taking it first makes a doubled click harmless
        if (pending == null) {
            return;
        }

        player.closeInventory();

        // Everything below moves items and money around, which must not happen while we are still
        // inside the inventory click. One tick later is fine - the offer is validated and executed
        // together in that tick, so nothing can slip in between the checks and the transaction.
        Bukkit.getScheduler().runTask(ChestShop.getPlugin(), new Runnable() {
            public void run() {
                process(player, pending);
            }
        });
    }

    /**
     * The client clicked the decline button
     *
     * @param player    Client of the shop
     * @param menuOffer The offer the clicked menu was built for
     */
    public static void decline(Player player, PendingConfirmation menuOffer) {
        if (PENDING.get(player.getUniqueId()) != menuOffer || take(player) == null) {
            return;
        }

        player.closeInventory();
        player.sendMessage(Messages.prefix(Messages.CONFIRMATION_CANCELLED));
    }

    /**
     * The client clicked the settings button: show them their own confirmation settings, keeping
     * the offer alive so the back button can return to it.
     *
     * @param player    Client of the shop
     * @param menuOffer The offer the clicked menu was built for
     */
    public static void openPreferences(Player player, PendingConfirmation menuOffer) {
        // A null offer means the settings were opened on their own, with no transaction behind them
        if (menuOffer != null && PENDING.get(player.getUniqueId()) != menuOffer) {
            return;
        }

        if (BedrockForms.isBedrockPlayer(player)) {
            BedrockForms.sendPreferences(player, menuOffer);
            return;
        }

        switchTo(player, new PreferencesMenu(player, menuOffer).getInventory());
    }

    /**
     * The client clicked the back button in their settings: return to the offer they came from, or
     * tell them it is gone if it ran out while they were in there.
     *
     * @param player    Client of the shop
     * @param menuOffer The offer the settings menu was opened from
     */
    public static void returnToConfirmation(Player player, PendingConfirmation menuOffer) {
        if (PENDING.get(player.getUniqueId()) != menuOffer) {
            player.closeInventory();
            player.sendMessage(Messages.prefix(Messages.CONFIRMATION_EXPIRED));
            return;
        }

        if (BedrockForms.isBedrockPlayer(player)) {
            BedrockForms.sendConfirmation(player, menuOffer, canChangePreferences(player));
            return;
        }

        switchTo(player, new ConfirmationMenu(player, menuOffer).getInventory());
    }

    /**
     * @param player Player to check
     * @return Is this player being moved between two of our menus right now?
     */
    public static boolean isSwitchingMenus(Player player) {
        return SWITCHING_MENUS.contains(player.getUniqueId());
    }

    /**
     * Opens another one of our menus without the close event in between dropping the offer.
     * Everything here happens in one go on the main thread, so the flag can never be left behind.
     */
    private static void switchTo(Player player, Inventory menu) {
        UUID uuid = player.getUniqueId();

        SWITCHING_MENUS.add(uuid);
        try {
            player.openInventory(menu);
        } finally {
            SWITCHING_MENUS.remove(uuid);
        }
    }

    /**
     * Forgets a player's pending offer, telling them about it if they still are around
     *
     * @param player  Client of the shop
     * @param message Message to send, or null to stay quiet
     */
    public static void cancel(Player player, String message) {
        if (take(player) == null) {
            return;
        }

        if (message != null && player.isOnline()) {
            player.sendMessage(Messages.prefix(message));
        }
    }

    /**
     * Forgets a player's pending offer without telling them
     *
     * @param player Client of the shop
     */
    public static void discard(Player player) {
        take(player);

        if (player != null) {
            // Safe to drop here: by the time an offer is discarded the hint has either been sent
            // (the settings were closed) or the player is gone
            TURNED_OFF_IN_MENU.remove(player.getUniqueId());
        }
    }

    /**
     * Closes every open confirmation menu, for example when the configuration is reloaded or the
     * plugin shuts down. A stale menu could otherwise be accepted under settings it was not built with.
     *
     * @param message Message to send to the affected players, or null to stay quiet
     */
    public static void cancelAll(String message) {
        for (UUID uuid : new ArrayList<UUID>(PENDING.keySet())) {
            Player player = Bukkit.getPlayer(uuid);

            if (player == null) {
                remove(uuid);
                continue;
            }

            cancel(player, message);

            if (isViewingMenu(player)) {
                player.closeInventory();
            }
        }

        PENDING.clear();
    }

    /**
     * @param player Player to check
     * @return Is this player looking at a confirmation menu right now?
     */
    public static boolean isViewingMenu(Player player) {
        return player.getOpenInventory() != null && getMenu(player.getOpenInventory().getTopInventory()) != null;
    }

    /**
     * @param inventory Inventory to check
     * @return The confirmation menu backing this inventory, or null if it is not one
     */
    public static ConfirmationMenu getMenu(Inventory inventory) {
        MenuHolder holder = getMenuHolder(inventory);

        return holder instanceof ConfirmationMenu ? (ConfirmationMenu) holder : null;
    }

    /**
     * @param inventory Inventory to check
     * @return Any of ChestShop's own menus backing this inventory, or null if it is not one
     */
    public static MenuHolder getMenuHolder(Inventory inventory) {
        if (inventory == null) {
            return null;
        }

        InventoryHolder holder = inventory.getHolder();

        return holder instanceof MenuHolder ? (MenuHolder) holder : null;
    }

    /**
     * Removes the offer from the queue and stops its timeout. Because every caller goes through
     * here, and because all of this runs on the main thread, an offer can only ever be taken once -
     * which is what keeps a doubled click from running the transaction twice.
     */
    private static PendingConfirmation take(Player player) {
        return player == null ? null : remove(player.getUniqueId());
    }

    private static PendingConfirmation remove(UUID uuid) {
        PendingConfirmation pending = PENDING.remove(uuid);

        if (pending != null && pending.getTimeoutTaskId() != -1) {
            Bukkit.getScheduler().cancelTask(pending.getTimeoutTaskId());
            pending.setTimeoutTaskId(-1);
        }

        return pending;
    }

    private static void scheduleTimeout(final Player player, final PendingConfirmation pending) {
        if (Properties.CONFIRMATION_TIMEOUT <= 0) {
            return;
        }

        int taskId = Bukkit.getScheduler().runTaskLater(ChestShop.getPlugin(), new Runnable() {
            public void run() {
                if (PENDING.get(pending.getClientId()) != pending) {
                    return; //Already accepted, declined or replaced
                }

                cancel(player, Messages.CONFIRMATION_EXPIRED);

                if (player.isOnline() && isViewingMenu(player)) {
                    player.closeInventory();
                }
            }
        }, Properties.CONFIRMATION_TIMEOUT * 20L).getTaskId();

        pending.setTimeoutTaskId(taskId);
    }

    /**
     * Runs an accepted offer. Nothing from the snapshot is trusted here beyond comparing it to the
     * offer that is built again right now.
     */
    private static void process(Player player, PendingConfirmation pending) {
        if (!player.isOnline()) {
            return;
        }

        if (!Properties.CONFIRMATION_ENABLED) {
            // The system was turned off while the menu was open - do not run a transaction whose
            // terms the player can no longer see.
            player.sendMessage(Messages.prefix(Messages.CONFIRMATION_CANCELLED));
            return;
        }

        if (isTooFarAway(player, pending)) {
            player.sendMessage(Messages.prefix(Messages.CONFIRMATION_TOO_FAR));
            return;
        }

        Sign sign = readSign(pending);
        if (sign == null) {
            player.sendMessage(Messages.prefix(Messages.CONFIRMATION_SHOP_GONE));
            return;
        }

        // A shop owner can edit their sign while somebody is staring at the menu
        if (!Arrays.equals(StringUtil.stripColourCodes(sign.getLines()), pending.getSignLines())) {
            player.sendMessage(Messages.prefix(Messages.CONFIRMATION_OFFER_CHANGED));
            return;
        }

        PreTransactionEvent event = PlayerInteract.preparePreTransactionEvent(sign, player, getAction(pending), pending.isSneaking());
        if (event == null) {
            return; //preparePreTransactionEvent already told the player what is wrong
        }

        event.setConfirmationReplay(true);
        ChestShop.callEvent(event);

        if (event.isCancelled()) {
            return; //The usual error message has already been sent by the ErrorMessageSender
        }

        if (!matchesOffer(pending, event)) {
            player.sendMessage(Messages.prefix(Messages.CONFIRMATION_OFFER_CHANGED));
            return;
        }

        ChestShop.callEvent(new TransactionEvent(event, sign));
    }

    private static boolean isTooFarAway(Player player, PendingConfirmation pending) {
        if (Properties.CONFIRMATION_MAX_DISTANCE <= 0) {
            return false;
        }

        Location signLocation = pending.getSignLocation();
        if (signLocation.getWorld() == null || !signLocation.getWorld().equals(player.getWorld())) {
            return true;
        }

        double maxDistance = Properties.CONFIRMATION_MAX_DISTANCE;

        return player.getLocation().distanceSquared(signLocation) > maxDistance * maxDistance;
    }

    /**
     * Reads the shop sign from the world again. The {@link Sign} captured when the menu was opened
     * is a snapshot and would happily keep returning lines that have since been changed.
     */
    private static Sign readSign(PendingConfirmation pending) {
        Location location = pending.getSignLocation();
        if (location.getWorld() == null || !location.getWorld().isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) {
            return null;
        }

        Block block = location.getBlock();
        if (!BlockUtil.isSign(block)) {
            return null;
        }

        Sign sign = (Sign) block.getState();

        return ChestShopSign.isValid(sign) ? sign : null;
    }

    private static Action getAction(PendingConfirmation pending) {
        Action buy = Properties.REVERSE_BUTTONS ? LEFT_CLICK_BLOCK : RIGHT_CLICK_BLOCK;
        Action sell = Properties.REVERSE_BUTTONS ? RIGHT_CLICK_BLOCK : LEFT_CLICK_BLOCK;

        return pending.getTransactionType() == BUY ? buy : sell;
    }

    /**
     * Is the freshly built offer still the one the player accepted?
     *
     * With partial transactions turned on the amount is scaled down to whatever the shop and the
     * player can handle, so a shop running low on stock (or a player who spent their money in the
     * meantime) legitimately produces a smaller offer. That offer is not what the player agreed to,
     * so it is refused instead of being quietly carried out.
     */
    private static boolean matchesOffer(PendingConfirmation pending, PreTransactionEvent event) {
        if (Math.abs(pending.getPrice() - event.getPrice()) > PRICE_TOLERANCE) {
            return false;
        }

        List<ItemStack> shown = Arrays.asList(InventoryUtil.mergeSimilarStacks(pending.getStock()));
        List<ItemStack> current = Arrays.asList(InventoryUtil.mergeSimilarStacks(event.getStock()));

        if (shown.size() != current.size()) {
            return false;
        }

        for (int i = 0; i < shown.size(); i++) {
            ItemStack shownItem = shown.get(i);
            ItemStack currentItem = current.get(i);

            if (shownItem == null || currentItem == null
                    || shownItem.getAmount() != currentItem.getAmount()
                    || !MaterialUtil.equals(shownItem, currentItem)) {
                return false;
            }
        }

        return true;
    }

    private static ItemStack[] cloneStock(ItemStack[] stock) {
        ItemStack[] clone = new ItemStack[stock.length];

        for (int i = 0; i < stock.length; i++) {
            clone[i] = stock[i] == null ? null : stock[i].clone();
        }

        return clone;
    }
}
