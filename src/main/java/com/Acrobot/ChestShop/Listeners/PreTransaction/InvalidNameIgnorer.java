package com.Acrobot.ChestShop.Listeners.PreTransaction;

import com.Acrobot.ChestShop.ChestShop;
import com.Acrobot.ChestShop.Configuration.Properties;
import com.Acrobot.ChestShop.Events.PreTransactionEvent;
import com.Acrobot.ChestShop.Signs.ChestShopSign;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.logging.Level;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public class InvalidNameIgnorer implements Listener {

    private final static String FALLBACK_PATTERN = "^-?\\w+$";

    private static Pattern usernamePattern;
    private static String usernamePatternSource;

    private static Pattern getUsernamePattern() {
        if (usernamePattern == null || !Properties.VALID_PLAYERNAME_REGEXP.equals(usernamePatternSource)) {
            usernamePatternSource = Properties.VALID_PLAYERNAME_REGEXP;
            try {
                usernamePattern = Pattern.compile(usernamePatternSource);
            } catch (PatternSyntaxException e) {
                ChestShop.getBukkitLogger().log(Level.WARNING, "Invalid VALID_PLAYERNAME_REGEXP \"" + usernamePatternSource + "\", falling back to " + FALLBACK_PATTERN, e);
                usernamePattern = Pattern.compile(FALLBACK_PATTERN);
            }
        }
        return usernamePattern;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public static void onPreTransaction(PreTransactionEvent event) {
        if (event.isCancelled()) {
            return;
        }

        String name = event.getClient().getName();
        if (ChestShopSign.isAdminShop(name) || !getUsernamePattern().matcher(name).matches()) {
            event.setCancelled(PreTransactionEvent.TransactionOutcome.CLIENT_DOES_NOT_HAVE_PERMISSION);
        }
    }
}
