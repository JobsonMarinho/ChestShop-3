package com.Acrobot.ChestShop.Commands;

import com.Acrobot.ChestShop.Confirmation.ConfirmationPreferences;
import com.Acrobot.ChestShop.Configuration.Messages;
import com.Acrobot.ChestShop.Configuration.Properties;
import com.Acrobot.ChestShop.Permission;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Lets a player decide whether they want to be asked to confirm their transactions, separately for
 * Admin Shops and for shops owned by other players.
 *
 * @author Acrobot
 */
public class Confirm implements CommandExecutor, TabCompleter {
    private static final List<String> TARGETS = Arrays.asList("admin", "player", "on", "off");
    private static final List<String> STATES = Arrays.asList("on", "off");

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            return false;
        }

        Player player = (Player) sender;

        if (!Properties.CONFIRMATION_ENABLED) {
            player.sendMessage(Messages.prefix(Messages.CONFIRMATION_SYSTEM_DISABLED));
            return true;
        }

        if (!Properties.CONFIRMATION_ALLOW_PLAYER_TOGGLE || !Permission.has(player, Permission.CONFIRMATION_TOGGLE)) {
            player.sendMessage(Messages.prefix(Messages.CONFIRMATION_TOGGLE_BLOCKED));
            return true;
        }

        if (args.length > 2) {
            return false;
        }

        if (args.length == 0) {
            // No argument at all simply flips everything, like /cstoggle does
            boolean enable = !(wants(player, true) && wants(player, false));

            set(player, true, enable);
            set(player, false, enable);
            sendStatus(player);
            return true;
        }

        String target = args[0].toLowerCase(Locale.ROOT);
        Boolean state = args.length > 1 ? parseState(args[1]) : null; //null means "flip it"

        if (args.length > 1 && state == null) {
            return false;
        }

        if (isAdminShopArgument(target)) {
            set(player, true, state == null ? !wants(player, true) : state);
        } else if (isPlayerShopArgument(target)) {
            set(player, false, state == null ? !wants(player, false) : state);
        } else {
            Boolean both = parseState(target);
            if (both == null) {
                return false;
            }

            set(player, true, both);
            set(player, false, both);
        }

        sendStatus(player);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1) {
            return filter(TARGETS, args[0]);
        }

        if (args.length == 2 && (isAdminShopArgument(args[0].toLowerCase(Locale.ROOT)) || isPlayerShopArgument(args[0].toLowerCase(Locale.ROOT)))) {
            return filter(STATES, args[1]);
        }

        return Collections.emptyList();
    }

    private static void set(Player player, boolean adminShop, boolean enabled) {
        ConfirmationPreferences.setConfirmation(player, adminShop, enabled);

        String message;
        if (adminShop) {
            message = enabled ? Messages.CONFIRMATION_ADMIN_SHOPS_ON : Messages.CONFIRMATION_ADMIN_SHOPS_OFF;
        } else {
            message = enabled ? Messages.CONFIRMATION_PLAYER_SHOPS_ON : Messages.CONFIRMATION_PLAYER_SHOPS_OFF;
        }

        player.sendMessage(Messages.prefix(message));
    }

    private static void sendStatus(Player player) {
        player.sendMessage(Messages.prefix(Messages.CONFIRMATION_STATUS
                .replace("%adminshops", describe(player, true))
                .replace("%playershops", describe(player, false))));
    }

    /**
     * A player can have the menu turned on for a kind of shop while the server has it turned off
     * there - saying so avoids the "I turned it on and nothing happens" confusion.
     */
    private static String describe(Player player, boolean adminShop) {
        if (!(adminShop ? Properties.CONFIRMATION_FOR_ADMIN_SHOPS : Properties.CONFIRMATION_FOR_PLAYER_SHOPS)) {
            return Messages.CONFIRMATION_STATUS_FORCED_OFF;
        }

        return wants(player, adminShop) ? Messages.CONFIRMATION_STATUS_ON : Messages.CONFIRMATION_STATUS_OFF;
    }

    private static boolean wants(Player player, boolean adminShop) {
        return ConfirmationPreferences.wantsConfirmation(player, adminShop);
    }

    private static boolean isAdminShopArgument(String argument) {
        return argument.equals("admin") || argument.equals("adminshop") || argument.equals("adminshops") || argument.equals("servidor");
    }

    private static boolean isPlayerShopArgument(String argument) {
        return argument.equals("player") || argument.equals("playershop") || argument.equals("playershops") || argument.equals("jogador");
    }

    private static Boolean parseState(String argument) {
        String state = argument.toLowerCase(Locale.ROOT);

        if (state.equals("on") || state.equals("true") || state.equals("yes") || state.equals("sim")) {
            return true;
        }

        if (state.equals("off") || state.equals("false") || state.equals("no") || state.equals("nao")) {
            return false;
        }

        return null;
    }

    private static List<String> filter(List<String> options, String start) {
        List<String> matches = new ArrayList<String>();
        String prefix = start.toLowerCase(Locale.ROOT);

        for (String option : options) {
            if (option.startsWith(prefix)) {
                matches.add(option);
            }
        }

        return matches;
    }
}
