package com.Acrobot.ChestShop.Discord;

import com.Acrobot.Breeze.Utils.InventoryUtil;
import com.Acrobot.Breeze.Utils.MaterialUtil;
import com.Acrobot.ChestShop.Events.PreTransactionEvent;
import com.Acrobot.ChestShop.Events.ShopCreatedEvent;
import com.Acrobot.ChestShop.Events.ShopDestroyedEvent;
import com.Acrobot.ChestShop.Events.ShopEditedEvent;
import com.Acrobot.ChestShop.Events.TransactionEvent;
import com.Acrobot.ChestShop.Signs.ChestShopSign;
import org.bukkit.block.Sign;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

import static com.Acrobot.ChestShop.Events.TransactionEvent.TransactionType.BUY;
import static com.Acrobot.ChestShop.Signs.ChestShopSign.NAME_LINE;

/**
 * Turns ChestShop's own events into webhook messages.
 *
 * Everything is read at MONITOR and only from events that already happened - the point of a shop
 * log is what the server did, not what it was about to do and then cancelled.
 *
 * @author Acrobot
 */
public class DiscordListener implements Listener {
    private final DiscordService discord;

    public DiscordListener(DiscordService discord) {
        this.discord = discord;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTransaction(TransactionEvent event) {
        Sign sign = event.getSign();
        boolean adminShop = ChestShopSign.isAdminShop(sign);

        discord.onTransaction(
                event.getClient(),
                event.getTransactionType() == BUY,
                event.getOwnerAccount() == null ? "—" : event.getOwnerAccount().getName(),
                adminShop,
                describeItem(event.getStock()),
                InventoryUtil.countItems(event.getStock()),
                event.getPrice(),
                sign.getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onShopCreated(ShopCreatedEvent event) {
        discord.onShopCreated(
                event.getPlayer(),
                event.getSignLines(),
                ChestShopSign.isAdminShop(event.getSignLine(NAME_LINE)),
                event.getSign().getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onShopDestroyed(ShopDestroyedEvent event) {
        Sign sign = event.getSign();
        String owner = sign.getLine(NAME_LINE);

        discord.onShopDestroyed(
                event.getDestroyer(),
                sign.getLines(),
                owner,
                ChestShopSign.isAdminShop(owner),
                sign.getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onShopEdited(ShopEditedEvent event) {
        discord.onShopEdited(event.getModifier(), event.getSignLines(), event.getSign().getLocation());
    }

    /**
     * Only the refusals worth looking at: somebody repeatedly trying to use a shop their rank does
     * not allow, or a payment that failed halfway. An out of stock shop is not an incident.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onTransactionDenied(PreTransactionEvent event) {
        if (!event.isCancelled()) {
            return;
        }

        PreTransactionEvent.TransactionOutcome outcome = event.getTransactionOutcome();

        switch (outcome) {
            case CLIENT_DOES_NOT_HAVE_PERMISSION:
            case CLIENT_CANNOT_BUY_IN_PLAYER_SHOP:
            case CLIENT_CANNOT_BUY_IN_ADMIN_SHOP:
            case CLIENT_CANNOT_SELL_IN_PLAYER_SHOP:
            case CLIENT_CANNOT_SELL_IN_ADMIN_SHOP:
            case SHOP_IS_RESTRICTED:
                discord.onTransactionDenied(event.getClient(), outcome.name(),
                        event.getOwnerAccount() == null ? "—" : event.getOwnerAccount().getName(),
                        event.getSign().getLocation());
                break;

            case CLIENT_DEPOSIT_FAILED:
            case SHOP_DEPOSIT_FAILED:
                discord.onEconomyFailure("Transação em " + describeSign(event.getSign()), outcome.name(),
                        event.getClient() == null ? null : event.getClient().getName());
                break;

            default:
                break; //Everyday refusals - no stock, no money, no space
        }
    }

    /**
     * A player logging out ends their buying spree, so their batch goes out now instead of waiting
     * for a timeout on somebody who is no longer there.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        discord.onPlayerQuit(event.getPlayer().getUniqueId());
    }

    private static String describeItem(ItemStack[] stock) {
        ItemStack[] merged = InventoryUtil.mergeSimilarStacks(stock);

        return merged.length == 0 ? "—" : MaterialUtil.getName(merged[0]);
    }

    private static String describeSign(Sign sign) {
        if (sign == null || sign.getLocation().getWorld() == null) {
            return "—";
        }

        return sign.getLocation().getWorld().getName() + " "
                + sign.getLocation().getBlockX() + "," + sign.getLocation().getBlockY() + "," + sign.getLocation().getBlockZ();
    }
}
