package coint.util;

import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Calendar;
import java.util.concurrent.TimeUnit;

import net.minecraft.command.WrongUsageException;

public class TimeUtil {

    private static final String PARSE_ERROR = "Неверный формат времени. Примеры: 30s, 10m (минуты), 2h, 7d, 1mo (календарные месяцы), perm (навсегда)";

    public static String formatDuration(long ms) {
        if (ms < -1) {
            return "навсегда";
        }

        long seconds = ms / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        long days = hours / 24;

        if (days > 0) {
            return days + "д " + (hours % 24) + "ч";
        } else if (hours > 0) {
            return hours + "ч " + (minutes % 60) + "м";
        } else if (minutes > 0) {
            return minutes + "м " + (seconds % 60) + "с";
        } else {
            return seconds + "с";
        }
    }

    public static ZoneId getZone() {
        return ZoneId.ofOffset("GMT", ZoneOffset.of("+3"));
    }

    public static long simpleParse(String time) {
        try {
            String str = time.toLowerCase();
            char unit = str.charAt(str.length() - 1);
            long value = Long.parseLong(str.substring(0, str.length() - 1));

            return switch (unit) {
                case 's' -> TimeUnit.SECONDS.toMillis(value);
                case 'm' -> TimeUnit.MINUTES.toMillis(value);
                case 'h' -> TimeUnit.HOURS.toMillis(value);
                case 'd' -> TimeUnit.DAYS.toMillis(value);
                default -> throw new IllegalArgumentException();
            };
        } catch (Exception e) {
            throw new WrongUsageException("Неверный формат времени. Используйте: 10s, 5m, 2h, 1d");
        }
    }

    /**
     * Parses duration strings: {@code 30s}, {@code 10m} (minutes), {@code 2h}, {@code 7d}, {@code 1mo}…{@code 12mo}
     * (calendar months).
     *
     * @return milliseconds, or {@code -1} for "perm"
     */
    public static long parseDuration(String raw) throws WrongUsageException {
        String s = raw.toLowerCase()
            .trim();
        if (s.equals("perm") || s.equals("permanent") || s.equals("навсегда")) {
            return -1;
        }
        if (s.length() < 2) {
            throw new WrongUsageException(PARSE_ERROR);
        }
        try {
            if (s.endsWith("mo")) {
                if (s.length() < 3) {
                    throw new WrongUsageException("Укажите число перед mo, например: 1mo, 3mo, 12mo");
                }
                long months = Long.parseLong(s.substring(0, s.length() - 2));
                return millisForCalendarMonths(months);
            }
            long value = Long.parseLong(s.substring(0, s.length() - 1));
            return switch (s.charAt(s.length() - 1)) {
                case 's' -> value * 1_000L;
                case 'm' -> value * 60_000L;
                case 'h' -> value * 3_600_000L;
                case 'd' -> value * 86_400_000L;
                default -> throw new WrongUsageException(PARSE_ERROR);
            };
        } catch (NumberFormatException e) {
            throw new WrongUsageException(PARSE_ERROR);
        }
    }

    private static long millisForCalendarMonths(long months) throws WrongUsageException {
        if (months <= 0) {
            throw new WrongUsageException("Число месяцев должно быть положительным");
        }
        if (months > Integer.MAX_VALUE) {
            throw new WrongUsageException("Слишком большое число месяцев");
        }
        Calendar cal = Calendar.getInstance();
        long start = cal.getTimeInMillis();
        cal.add(Calendar.MONTH, (int) months);
        return cal.getTimeInMillis() - start;
    }
}
