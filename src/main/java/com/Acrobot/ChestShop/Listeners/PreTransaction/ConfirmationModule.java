package com.Acrobot.ChestShop.Listeners.PreTransaction;

import com.Acrobot.ChestShop.Confirmation.ConfirmationManager;
import com.Acrobot.ChestShop.Events.PreTransactionEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * Stops a transaction right before it happens and asks the client to confirm it.
 *
 * This runs at {@link EventPriority#HIGHEST} on purpose: at that point every check has passed and
 * every module that changes the deal (discounts, taxes, partial transactions) has already run, so
 * the price and the amount shown in the menu are the ones the player would really get. Only the
 * MONITOR listeners run after us, and none of them acts on the transaction.
 *
 * The transaction itself is not remembered here - it is cancelled and rebuilt from the world when
 * the player accepts. See {@link ConfirmationManager}.
 *
 * This listener is always registered; whether it does anything is decided at runtime by the
 * configuration, so the whole system can be switched on and off with a reload.
 *
 * @author Acrobot
 */
public class ConfirmationModule implements Listener {

    @EventHandler(priority = EventPriority.HIGHEST)
    public static void onPreTransaction(PreTransactionEvent event) {
        if (event.isCancelled() || !ConfirmationManager.requiresConfirmation(event)) {
            return;
        }

        ConfirmationManager.hold(event);
    }
}
