package com.Acrobot.ChestShop.Discord;

/**
 * Builds Minotar avatar URLs for the embeds.
 *
 * Only builds the string - Discord is the one that fetches the image, so the server never makes an
 * HTTP call just to show a head next to a shop log.
 *
 * @author Acrobot
 */
public class AvatarResolver {

    public static String head(String name) {
        return name == null || name.isEmpty() ? "" : "https://minotar.net/helm/" + name + "/100.png";
    }

    public static String body(String name) {
        return name == null || name.isEmpty() ? "" : "https://minotar.net/armor/body/" + name + "/100.png";
    }
}
