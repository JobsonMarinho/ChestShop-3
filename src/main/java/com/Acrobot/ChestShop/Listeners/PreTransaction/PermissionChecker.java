package com.Acrobot.ChestShop.Listeners.PreTransaction;

import com.Acrobot.ChestShop.Events.PreTransactionEvent;
import com.Acrobot.ChestShop.Events.TransactionEvent;
import com.Acrobot.ChestShop.Permission;
import com.Acrobot.ChestShop.Signs.ChestShopSign;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;

import static com.Acrobot.ChestShop.Events.PreTransactionEvent.TransactionOutcome.CLIENT_CANNOT_BUY_IN_ADMIN_SHOP;
import static com.Acrobot.ChestShop.Events.PreTransactionEvent.TransactionOutcome.CLIENT_CANNOT_BUY_IN_PLAYER_SHOP;
import static com.Acrobot.ChestShop.Events.PreTransactionEvent.TransactionOutcome.CLIENT_DOES_NOT_HAVE_PERMISSION;
import static com.Acrobot.ChestShop.Events.TransactionEvent.TransactionType.BUY;

/**
 * @author Acrobot
 */
public class PermissionChecker implements Listener {
    @EventHandler
    public static void onPermissionCheck(PreTransactionEvent event) {
        if (event.isCancelled()) {
            return;
        }

        Player client = event.getClient();
        TransactionEvent.TransactionType transactionType = event.getTransactionType();

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

        // Restrict buying based on the shop type: player shops and Admin Shops require their own permission
        if (transactionType == BUY) {
            if (ChestShopSign.isAdminShop(event.getSign())) {
                if (!Permission.has(client, Permission.BUY_ADMIN_SHOP)) {
                    event.setCancelled(CLIENT_CANNOT_BUY_IN_ADMIN_SHOP);
                }
            } else if (!Permission.has(client, Permission.BUY_PLAYER_SHOP)) {
                event.setCancelled(CLIENT_CANNOT_BUY_IN_PLAYER_SHOP);
            }
        }
    }
}
