package coint.cosmetic;

/** Plain text and legacy colors only. Never accepts chat format codes or server/rank placeholders. */
public final class CosmeticSelection {

    public String prefix = "", prefixColor = "", nameColor = "", chatColor = "";

    public void validate() {
        if (prefix == null || prefix.codePointCount(0, prefix.length()) > 8)
            throw new IllegalArgumentException("Префикс: до 8 печатных символов");
        prefix.codePoints()
            .forEach(cp -> {
                if (cp == '§' || Character.isISOControl(cp) || Character.getType(cp) == Character.FORMAT)
                    throw new IllegalArgumentException(
                        "Префикс не может содержать управляющие символы или коды форматирования");
            });
        color(prefixColor);
        color(nameColor);
        color(chatColor);
    }

    private static void color(String value) {
        if (value == null || !value.matches("[0-9a-f]?"))
            throw new IllegalArgumentException("Цвет: 0-9, a-f или reset");
    }
}
