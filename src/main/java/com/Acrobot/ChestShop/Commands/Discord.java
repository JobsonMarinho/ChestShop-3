package com.Acrobot.ChestShop.Commands;

import com.Acrobot.ChestShop.ChestShop;
import com.Acrobot.ChestShop.Configuration.Messages;
import com.Acrobot.ChestShop.Discord.DiscordConfig;
import com.Acrobot.ChestShop.Discord.DiscordService;
import com.Acrobot.ChestShop.Permission;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Staff side of the webhook system: check it, test it, reload it.
 *
 * A webhook that quietly goes nowhere looks exactly like a quiet server, so being able to prove a
 * destination works - without waiting for someone to buy something - is part of the feature.
 *
 * @author Acrobot
 */
public class Discord implements CommandExecutor, TabCompleter {
    private static final List<String> SUBCOMMANDS = java.util.Arrays.asList("status", "validate", "test", "reload", "clearqueue");

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!Permission.has(sender, Permission.DISCORD) && !Permission.has(sender, Permission.ADMIN)) {
            sender.sendMessage(Messages.prefix(Messages.ACCESS_DENIED));
            return true;
        }

        DiscordService discord = ChestShop.getDiscordService();
        if (discord == null) {
            sender.sendMessage(Messages.prefix("&cO sistema de webhook não está carregado."));
            return true;
        }

        String action = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);

        if (action.equals("status")) {
            status(sender, discord);
        } else if (action.equals("validate")) {
            validate(sender, discord);
        } else if (action.equals("test")) {
            test(sender, discord, args);
        } else if (action.equals("reload")) {
            ChestShop.reloadDiscordConfiguration();
            discord.logReload(sender.getName());
            sender.sendMessage(Messages.prefix("&aConfiguração do Discord recarregada."));
        } else if (action.equals("clearqueue")) {
            sender.sendMessage(Messages.prefix("&aFila esvaziada: &f" + discord.clearQueue(sender.getName()) + "&a mensagem(ns) descartada(s)."));
        } else {
            return false;
        }

        return true;
    }

    private static void status(CommandSender sender, DiscordService discord) {
        DiscordConfig config = discord.getConfig();

        sender.sendMessage(Messages.prefix("&aWebhook do ChestShop"));
        sender.sendMessage("§7Sistema: " + (config.isEnabled() ? "§aligado" : "§cdesligado"));
        sender.sendMessage("§7Fila: §a" + discord.getQueueSize() + "§7/§a" + config.getAntiFlood().getQueueMaxSize());
        sender.sendMessage("§7Enviadas: §a" + discord.getSentToday() + " §7· Falhas: §c" + discord.getFailedToday());
        sender.sendMessage("§7Lotes abertos: §a" + discord.getAggregator().getOpenSessions());

        int enabledEvents = 0;
        for (DiscordConfig.EventSettings event : config.getEvents().values()) {
            if (event.isEnabled()) {
                enabledEvents++;
            }
        }
        sender.sendMessage("§7Eventos ligados: §a" + enabledEvents + "§7/§a" + config.getEvents().size());
    }

    private static void validate(CommandSender sender, DiscordService discord) {
        Map<String, DiscordConfig.Webhook> webhooks = discord.getConfig().getWebhooks();

        if (webhooks.isEmpty()) {
            sender.sendMessage(Messages.prefix("&cNenhum destino configurado no discord.yml."));
            return;
        }

        sender.sendMessage(Messages.prefix("&aDestinos configurados"));

        for (DiscordConfig.Webhook webhook : webhooks.values()) {
            String state;
            if (!webhook.isEnabled()) {
                state = "§8desligado";
            } else if (webhook.getUrl().isEmpty()) {
                state = "§cURL não preenchida";
            } else if (!webhook.hasValidUrl()) {
                state = "§cURL inválida";
            } else {
                state = "§apronto §8" + webhook.getMaskedUrl();
            }

            sender.sendMessage("§7• §f" + webhook.getName() + "§7: " + state);
        }
    }

    private static void test(CommandSender sender, DiscordService discord, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Messages.prefix("&cUse: /csdiscord test <destino>"));
            return;
        }

        String problem = discord.sendTest(args[1], sender.getName());

        if (problem != null) {
            sender.sendMessage(Messages.prefix("&cNão foi possível testar: &f" + problem));
            return;
        }

        sender.sendMessage(Messages.prefix("&aTeste enfileirado. Veja o canal e, se não chegar, o logs/discord.log."));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1) {
            return filter(SUBCOMMANDS, args[0]);
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("test")) {
            DiscordService discord = ChestShop.getDiscordService();

            if (discord != null) {
                return filter(new ArrayList<String>(discord.getConfig().getWebhooks().keySet()), args[1]);
            }
        }

        return Collections.emptyList();
    }

    private static List<String> filter(List<String> options, String start) {
        List<String> matches = new ArrayList<String>();
        String prefix = start.toLowerCase(Locale.ROOT);

        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                matches.add(option);
            }
        }

        return matches;
    }
}
