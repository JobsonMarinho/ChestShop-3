package com.Acrobot.ChestShop.Discord;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;
import java.util.TimeZone;

/**
 * Builds the Discord webhook payload by hand.
 *
 * No Gson, no Jackson, no OkHttp: in a Bukkit server every plugin drags its own copy of those, and
 * shading one more is how you get a version clash at load time. Discord's API needs a flat JSON
 * object and correct string escaping, which is a StringBuilder and one switch.
 *
 * @author Acrobot
 */
public class DiscordJson {
    /** Discord rejects an embed whose description is longer than this */
    private static final int MAX_DESCRIPTION = 4096;
    private static final int MAX_TITLE = 256;
    private static final int MAX_FOOTER = 2048;

    /**
     * @param embed    Template, still holding {placeholder} markers
     * @param values   Placeholder values
     * @param username Name the webhook posts under
     * @param roleId   Role to ping, or null/empty for a silent message
     * @return The JSON body to POST
     */
    public static String buildEmbed(DiscordConfig.Embed embed, Map<String, String> values, String username, String roleId) {
        StringBuilder json = new StringBuilder(512);
        json.append('{');

        if (username != null && !username.isEmpty()) {
            json.append("\"username\":\"").append(escape(username)).append("\",");
        }

        if (roleId != null && !roleId.trim().isEmpty()) {
            // allowed_mentions is what keeps a template typo from pinging @everyone
            json.append("\"content\":\"<@&").append(escape(roleId.trim())).append(">\",");
            json.append("\"allowed_mentions\":{\"parse\":[],\"roles\":[\"").append(escape(roleId.trim())).append("\"]},");
        } else {
            json.append("\"allowed_mentions\":{\"parse\":[]},");
        }

        json.append("\"embeds\":[{");
        json.append("\"title\":\"").append(escape(cut(apply(embed.getTitle(), values), MAX_TITLE))).append("\",");
        json.append("\"description\":\"").append(escape(cut(apply(embed.getDescription(), values), MAX_DESCRIPTION))).append("\",");
        json.append("\"color\":").append(embed.getColour()).append(',');

        String thumbnail = apply(embed.getThumbnail(), values);
        if (!thumbnail.isEmpty() && thumbnail.startsWith("http")) {
            json.append("\"thumbnail\":{\"url\":\"").append(escape(thumbnail)).append("\"},");
        }

        json.append("\"footer\":{\"text\":\"").append(escape(cut(apply(embed.getFooter(), values), MAX_FOOTER))).append("\"},");
        json.append("\"timestamp\":\"").append(isoNow()).append('"');
        json.append("}]}");

        return json.toString();
    }

    /**
     * Replaces every {key} with its value. An unknown key becomes a dash rather than being left as
     * a raw {marker} in front of the whole staff team.
     */
    public static String apply(String input, Map<String, String> values) {
        if (input == null || input.indexOf('{') < 0) {
            return input == null ? "" : input;
        }

        StringBuilder out = new StringBuilder(input.length() + 32);

        for (int i = 0; i < input.length(); ) {
            char current = input.charAt(i);

            if (current == '{') {
                int end = input.indexOf('}', i + 1);

                if (end > i) {
                    String value = values.get(input.substring(i + 1, end));
                    out.append(value == null ? "—" : value);
                    i = end + 1;
                    continue;
                }
            }

            out.append(current);
            i++;
        }

        return out.toString();
    }

    private static String cut(String text, int limit) {
        return text.length() <= limit ? text : text.substring(0, limit - 1) + "…";
    }

    private static String escape(String text) {
        StringBuilder out = new StringBuilder(text.length() + 16);

        for (int i = 0; i < text.length(); i++) {
            char current = text.charAt(i);

            switch (current) {
                case '"':
                    out.append("\\\"");
                    break;
                case '\\':
                    out.append("\\\\");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                default:
                    if (current < ' ') {
                        out.append(String.format("\\u%04x", (int) current));
                    } else {
                        out.append(current);
                    }
            }
        }

        return out.toString();
    }

    private static String isoNow() {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
        format.setTimeZone(TimeZone.getTimeZone("UTC"));

        return format.format(new Date());
    }
}
