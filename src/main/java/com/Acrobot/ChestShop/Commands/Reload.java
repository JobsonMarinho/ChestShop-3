package com.Acrobot.ChestShop.Commands;

import com.Acrobot.ChestShop.ChestShop;
import com.Acrobot.ChestShop.Configuration.Messages;
import com.Acrobot.ChestShop.Permission;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

/**
 * Reloads config.yml and local.yml without restarting the server.
 *
 * @author Acrobot
 */
public class Reload implements CommandExecutor {

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!Permission.has(sender, Permission.RELOAD) && !Permission.has(sender, Permission.ADMIN)) {
            sender.sendMessage(Messages.prefix(Messages.ACCESS_DENIED));
            return true;
        }

        if (args.length != 0) {
            return false;
        }

        if (ChestShop.reloadConfiguration()) {
            sender.sendMessage(Messages.prefix(Messages.CONFIGURATION_RELOADED));
        } else {
            sender.sendMessage(Messages.prefix(Messages.CONFIGURATION_RELOAD_FAILED));
        }

        return true;
    }
}
