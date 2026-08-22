package com.Acrobot.ChestShop.Confirmation;

import com.Acrobot.Breeze.Configuration.Configuration;
import com.Acrobot.Breeze.Utils.StringUtil;
import com.Acrobot.ChestShop.ChestShop;
import com.Acrobot.ChestShop.Configuration.Messages;
import com.Acrobot.ChestShop.Configuration.Properties;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Level;

import static com.Acrobot.ChestShop.Events.TransactionEvent.TransactionType.BUY;

/**
 * Shows the confirmation and its settings to Bedrock players as native forms.
 *
 * A Bedrock client reaches a chest menu through Geyser's translation, which works but is miserable
 * to use on a phone: tiny slots, no lore on tap. Floodgate can hand the client a real Bedrock form
 * instead - a title, the offer written out, and buttons - so the same decision is made in the
 * interface that platform actually has.
 *
 * Floodgate is a soft dependency and is reached purely through reflection: no compile time
 * dependency, no repository to resolve, nothing to break the build, and a server without Floodgate
 * simply keeps the Java menus. The Cumulus builder takes plain java.util.function callbacks, so no
 * dynamic proxies are needed either.
 *
 * <b>Threading:</b> a form's answer arrives on Floodgate's own thread, not the server thread.
 * Everything a button does is therefore bounced back onto the main thread before it touches a shop.
 *
 * @author Acrobot
 */
public class BedrockForms {
    private static boolean available = false;

    private static Method getInstance;
    private static Method isFloodgatePlayer;
    private static Method sendForm;

    private static Method formBuilder;
    private static Method builderTitle;
    private static Method builderContent;
    private static Method builderButton;
    private static Method builderValidHandler;
    private static Method builderClosedHandler;
    private static Method builderBuild;

    private static Method clickedButtonId;

    /**
     * Looks for Floodgate and wires up the API. Called once, when the plugin starts.
     */
    public static void initialize() {
        available = false;

        if (Bukkit.getPluginManager().getPlugin("floodgate") == null) {
            return; //No Floodgate, no Bedrock players - nothing to say about it
        }

        try {
            Class<?> api = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            Class<?> form = Class.forName("org.geysermc.cumulus.form.Form");
            Class<?> simpleForm = Class.forName("org.geysermc.cumulus.form.SimpleForm");
            Class<?> builder = Class.forName("org.geysermc.cumulus.form.SimpleForm$Builder");
            Class<?> response = Class.forName("org.geysermc.cumulus.response.SimpleFormResponse");

            getInstance = api.getMethod("getInstance");
            isFloodgatePlayer = api.getMethod("isFloodgatePlayer", UUID.class);
            sendForm = api.getMethod("sendForm", UUID.class, form);

            formBuilder = simpleForm.getMethod("builder");
            builderTitle = builder.getMethod("title", String.class);
            builderContent = builder.getMethod("content", String.class);
            builderButton = builder.getMethod("button", String.class);
            builderValidHandler = builder.getMethod("validResultHandler", Consumer.class);
            builderClosedHandler = builder.getMethod("closedOrInvalidResultHandler", Runnable.class);
            builderBuild = builder.getMethod("build");

            clickedButtonId = response.getMethod("clickedButtonId");

            available = true;
            ChestShop.getBukkitLogger().info("Found Floodgate - Bedrock players get the confirmation as a form");
        } catch (Throwable unusableApi) {
            ChestShop.getBukkitLogger().log(Level.WARNING, "Floodgate is installed but its form API could not be reached ("
                    + unusableApi + "). Bedrock players will get the Java menus through Geyser instead.");
        }
    }

    /**
     * @param player Player to check
     * @return Should this player be shown a Bedrock form rather than a chest menu?
     */
    public static boolean isBedrockPlayer(Player player) {
        if (!available || !Properties.CONFIRMATION_BEDROCK_FORMS) {
            return false;
        }

        try {
            return (Boolean) isFloodgatePlayer.invoke(getInstance.invoke(null), player.getUniqueId());
        } catch (Throwable exception) {
            return false;
        }
    }

    /** What a button of the confirmation form does, in the order the buttons were added */
    private enum FormAction {
        ACCEPT, DECLINE, SETTINGS
    }

    /**
     * Shows the offer, with the same terms the Java menu spells out.
     *
     * @param player     Client of the shop
     * @param pending    Offer to show
     * @param withExtras Should the optional buttons be offered?
     * @return Was the form sent?
     */
    public static boolean sendConfirmation(final Player player, final PendingConfirmation pending, boolean withExtras) {
        if (!available) {
            return false;
        }

        try {
            // The buttons are tracked as they are added, so a hidden one can't shift what the
            // others mean - a form only tells us which index was pressed
            final List<FormAction> actions = new ArrayList<FormAction>();

            Object builder = formBuilder.invoke(null);

            builder = builderTitle.invoke(builder, plain(pending.getTransactionType() == BUY
                    ? Messages.CONFIRMATION_TITLE_BUY
                    : Messages.CONFIRMATION_TITLE_SELL));
            builder = builderContent.invoke(builder, join(ConfirmationMenu.describeOffer(pending)));

            builder = builderButton.invoke(builder, plain(Messages.CONFIRMATION_ACCEPT_NAME));
            actions.add(FormAction.ACCEPT);

            builder = builderButton.invoke(builder, plain(Messages.CONFIRMATION_DECLINE_NAME));
            actions.add(FormAction.DECLINE);

            if (withExtras) {
                builder = builderButton.invoke(builder, plain(Messages.CONFIRMATION_SETTINGS_NAME));
                actions.add(FormAction.SETTINGS);
            }

            builder = builderValidHandler.invoke(builder, new Consumer<Object>() {
                public void accept(Object response) {
                    final int button = readButton(response);

                    runOnMainThread(new Runnable() {
                        public void run() {
                            if (button < 0 || button >= actions.size() || !stillWaiting(player, pending)) {
                                return;
                            }

                            switch (actions.get(button)) {
                                case ACCEPT:
                                    ConfirmationManager.accept(player, pending);
                                    break;
                                case DECLINE:
                                    ConfirmationManager.decline(player, pending);
                                    break;
                                case SETTINGS:
                                    ConfirmationManager.openPreferences(player, pending);
                                    break;
                            }
                        }
                    });
                }
            });

            builder = builderClosedHandler.invoke(builder, new Runnable() {
                public void run() {
                    runOnMainThread(new Runnable() {
                        public void run() {
                            // Closing the form is the Bedrock way of walking away from the offer
                            ConfirmationManager.cancel(player, Messages.CONFIRMATION_CANCELLED);
                        }
                    });
                }
            });

            return send(player, builderBuild.invoke(builder));
        } catch (Throwable exception) {
            ChestShop.getBukkitLogger().log(Level.WARNING, "Could not send the confirmation form", exception);
            return false;
        }
    }

    /**
     * The Bedrock version of the settings menu: one button per setting, showing its current state,
     * plus a way back to the offer.
     *
     * @param player  Player to show it to
     * @param pending Offer they came from
     * @return Was the form sent?
     */
    public static boolean sendPreferences(final Player player, final PendingConfirmation pending) {
        if (!available) {
            return false;
        }

        try {
            Object builder = formBuilder.invoke(null);

            builder = builderTitle.invoke(builder, plain(Messages.CONFIRMATION_SETTINGS_TITLE));
            builder = builderContent.invoke(builder, firstLine(Messages.CONFIRMATION_SETTINGS_LORE));
            builder = builderButton.invoke(builder, toggleLabel(player, true));
            builder = builderButton.invoke(builder, toggleLabel(player, false));
            builder = builderButton.invoke(builder, plain(pending != null
                    ? Messages.CONFIRMATION_BACK_NAME
                    : Messages.CONFIRMATION_CLOSE_NAME));

            builder = builderValidHandler.invoke(builder, new Consumer<Object>() {
                public void accept(Object response) {
                    final int button = readButton(response);

                    runOnMainThread(new Runnable() {
                        public void run() {
                            if (!stillWaiting(player, pending)) {
                                return;
                            }

                            if (button == 2) {
                                ConfirmationManager.sendTurnedOffHint(player);

                                // Answering a form already closed it, so with no offer behind us
                                // there is nothing left to do
                                if (pending != null) {
                                    ConfirmationManager.returnToConfirmation(player, pending);
                                }
                                return;
                            }

                            boolean adminShop = button == 0;
                            if (PreferencesMenu.isChangeable(adminShop)) {
                                boolean enabled = !ConfirmationPreferences.wantsConfirmation(player, adminShop);

                                ConfirmationPreferences.setConfirmation(player, adminShop, enabled);

                                if (!enabled) {
                                    ConfirmationManager.rememberTurnedOff(player);
                                }
                            }

                            // Redrawing a form means sending it again
                            ConfirmationManager.openPreferences(player, pending);
                        }
                    });
                }
            });

            builder = builderClosedHandler.invoke(builder, new Runnable() {
                public void run() {
                    runOnMainThread(new Runnable() {
                        public void run() {
                            ConfirmationManager.sendTurnedOffHint(player);
                            ConfirmationManager.cancel(player, Messages.CONFIRMATION_CANCELLED);
                        }
                    });
                }
            });

            return send(player, builderBuild.invoke(builder));
        } catch (Throwable exception) {
            ChestShop.getBukkitLogger().log(Level.WARNING, "Could not send the settings form", exception);
            return false;
        }
    }

    private static boolean send(Player player, Object form) throws Exception {
        return (Boolean) sendForm.invoke(getInstance.invoke(null), player.getUniqueId(), form);
    }

    /**
     * The offer can run out of time while the form is on screen, and a form the server cannot close
     * stays there - so a button press that arrives too late says so instead of doing nothing.
     */
    private static boolean stillWaiting(Player player, PendingConfirmation pending) {
        if (!player.isOnline()) {
            return false;
        }

        if (pending == null) {
            return true; //Settings opened on their own - there is no offer that could run out
        }

        if (ConfirmationManager.getPending(player) != pending) {
            player.sendMessage(Messages.prefix(Messages.CONFIRMATION_EXPIRED));
            return false;
        }

        return true;
    }

    private static String toggleLabel(Player player, boolean adminShop) {
        String label = adminShop ? Messages.CONFIRMATION_SETTINGS_ADMIN_SHOPS : Messages.CONFIRMATION_SETTINGS_PLAYER_SHOPS;

        String state;
        if (!PreferencesMenu.isChangeable(adminShop)) {
            state = Messages.CONFIRMATION_STATUS_FORCED_OFF;
        } else {
            state = ConfirmationPreferences.wantsConfirmation(player, adminShop)
                    ? Messages.CONFIRMATION_STATUS_ON
                    : Messages.CONFIRMATION_STATUS_OFF;
        }

        return plain(label) + "\n" + plain(state);
    }

    private static int readButton(Object response) {
        try {
            return (Integer) clickedButtonId.invoke(response);
        } catch (Throwable exception) {
            return -1;
        }
    }

    /**
     * Bedrock forms have no colour codes, so they are translated and then stripped rather than
     * shown to the player as raw section signs.
     */
    private static String plain(String text) {
        return StringUtil.stripColourCodes(Configuration.getColoured(text));
    }

    /**
     * The lore of a menu button ends with a "click to..." line that means nothing on a form, so
     * only its first, descriptive line is used as the form's text.
     */
    private static String firstLine(List<String> lines) {
        return lines.isEmpty() ? "" : plain(lines.get(0));
    }

    private static String join(List<String> lines) {
        StringBuilder joined = new StringBuilder();

        for (String line : lines) {
            if (joined.length() > 0) {
                joined.append('\n');
            }
            joined.append(StringUtil.stripColourCodes(line));
        }

        return joined.toString();
    }

    private static void runOnMainThread(Runnable task) {
        if (ChestShop.getPlugin() == null || !ChestShop.getPlugin().isEnabled()) {
            return;
        }

        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else {
            Bukkit.getScheduler().runTask(ChestShop.getPlugin(), task);
        }
    }
}
