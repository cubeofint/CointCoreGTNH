package coint.integration.discord;

import java.net.URI;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Removes GIF links from inbound chat without discarding the accompanying message. */
final class DiscordChatMedia {

    private static final Pattern LINKS = Pattern.compile(
        "!?\\[[^\\]\\r\\n]*\\]\\((https?://[^\\s<>]+)\\)|<(https?://[^\\s<>]+)>|(https?://[^\\s<>]+)",
        Pattern.CASE_INSENSITIVE);

    private DiscordChatMedia() {}

    static String stripGifs(String text) {
        if (text == null || text.isEmpty()) return text;
        Matcher matcher = LINKS.matcher(text);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String url = matcher.group(1);
            if (url == null) url = matcher.group(2);
            if (url == null) url = matcher.group(3);
            matcher.appendReplacement(result, Matcher.quoteReplacement(isGif(url) ? "" : matcher.group()));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static boolean isGif(String url) {
        // Punctuation following a plain URL is not part of its filename.
        url = url.replaceAll("[.,!;:)]+$", "");
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            if (host == null) return false;
            host = host.toLowerCase(Locale.ROOT);
            String path = uri.getPath();
            return path != null && path.toLowerCase(Locale.ROOT)
                .endsWith(".gif") || host.equals("tenor.com")
                || host.endsWith(".tenor.com")
                || host.equals("giphy.com")
                || host.endsWith(".giphy.com");
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }
}
