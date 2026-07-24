package com.Acrobot.ChestShop.Listeners.PreTransaction;

import com.Acrobot.ChestShop.Events.PreTransactionEvent;
import com.Acrobot.ChestShop.Events.TransactionEvent;
import com.Acrobot.ChestShop.Permission;
import com.Acrobot.ChestShop.Signs.ChestShopSign;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;

import static com.Acrobot.ChestShop.Events.PreTransactionEvent.TransactionOutcome.CLIENT_CANNOT_BUY_IN_ADMIN_SHOP;
import static com.Acrobot.ChestShop.Events.PreTransactionEvent.TransactionOutcome.CLIENT_CANNOT_BUY_IN_PLAYER_SHOP;
import static com.Acrobot.ChestShop.Events.PreTransactionEvent.TransactionOutcome.CLIENT_CANNOT_SELL_IN_ADMIN_SHOP;
import static com.Acrobot.ChestShop.Events.PreTransactionEvent.TransactionOutcome.CLIENT_CANNOT_SELL_IN_PLAYER_SHOP;
import static com.Acrobot.ChestShop.Events.PreTransactionEvent.TransactionOutcome.CLIENT_DOES_NOT_HAVE_PERMISSION;
import static com.Acrobot.ChestShop.Events.TransactionEvent.TransactionType.BUY;

/**
 * @author Acrobot
 */
public class PermissionChecker implements Listener {
    @EventHandler(priority = EventPriority.LOWEST)
    public static void onPermissionCheck(PreTransactionEvent event) {
        if (event.isCancelled()) {
            return;
        }

        Player client = event.getClient();
        TransactionEvent.TransactionType transactionType = event.getTransactionType();
        boolean adminShop = ChestShopSign.isAdminShop(event.getSign());

        // Rank gate first: buying/selling in player shops and Admin Shops each require their own permission
        if (transactionType == BUY) {
            if (!Permission.has(client, adminShop ? Permission.BUY_ADMIN_SHOP : Permission.BUY_PLAYER_SHOP)) {
                event.setCancelled(adminShop ? CLIENT_CANNOT_BUY_IN_ADMIN_SHOP : CLIENT_CANNOT_BUY_IN_PLAYER_SHOP);
                return;
            }
        } else {
            if (!Permission.has(client, adminShop ? Permission.SELL_ADMIN_SHOP : Permission.SELL_PLAYER_SHOP)) {
                event.setCancelled(adminShop ? CLIENT_CANNOT_SELL_IN_ADMIN_SHOP : CLIENT_CANNOT_SELL_IN_PLAYER_SHOP);
                return;
            }
        }

        // Generic per-item buy/sell permission
        for (ItemStack stock : event.getStock()) {
            String matID = stock.getType().toString().toLowerCase();

            boolean hasPerm;

            if (transactionType == BUY) {
                hasPerm = Permission.has(client, Permission.BUY) || Permission.has(client, Permission.BUY_ID + matID);
            } else {
                hasPerm = Permission.has(client, Permission.SELL) || Permission.has(client, Permission.SELL_ID + matID);
            }

            if (!hasPerm) {
                event.setCancelled(CLIENT_DOES_NOT_HAVE_PERMISSION);
                return;
            }
        }
    }
}
