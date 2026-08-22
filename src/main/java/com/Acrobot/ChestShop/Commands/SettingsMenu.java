package com.Acrobot.ChestShop.Commands;

import com.Acrobot.ChestShop.Confirmation.ConfirmationManager;
import com.Acrobot.ChestShop.Configuration.Messages;
import com.Acrobot.ChestShop.Configuration.Properties;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Opens the confirmation settings on their own, without having to click a shop sign first.
 *
 * Same settings as /csconfirm and as the button inside the confirmation menu - this is just the
 * door that doesn't require being mid-purchase to walk through. Bedrock players get the form.
 *
 * @author Acrobot
 */
public class SettingsMenu implements CommandExecutor {

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

        if (!ConfirmationManager.canChangePreferences(player)) {
            player.sendMessage(Messages.prefix(Messages.CONFIRMATION_TOGGLE_BLOCKED));
            return true;
        }

        if (args.length != 0) {
            return false;
        }

        ConfirmationManager.openPreferences(player, null);
        return true;
    }
}
