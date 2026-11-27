package coint.poll;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.google.gson.Gson;

public final class PollDefinition {

    public static final int MIN_ANSWERS = 2;
    public static final int MAX_ANSWERS = 6;

    private static final Gson JSON = new Gson();

    public final String id, question, details;
    public final List<String> answers;

    private PollDefinition(String id, String question, String details, List<String> answers) {
        this.id = id;
        this.question = question;
        this.details = details;
        this.answers = Collections.unmodifiableList(answers);
    }

    public static boolean validId(String id) {
        return id != null && id.matches("[a-z0-9][a-z0-9._-]{0,47}");
    }

    public static PollDefinition parse(String json) {
        PollDefinition draft = JSON.fromJson(json, PollDefinition.class);
        if (draft == null || !validId(draft.id)) {
            throw new IllegalArgumentException("Задайте ID опроса латиницей в нижнем регистре, до 48 символов.");
        }
        String question = field(draft.question, 160, "вопрос");
        String details = draft.details == null ? "" : draft.details.trim();
        if (details.length() > 1600) throw new IllegalArgumentException("Сократите описание до 1600 символов.");
        if (draft.answers == null || draft.answers.size() < MIN_ANSWERS || draft.answers.size() > MAX_ANSWERS) {
            throw new IllegalArgumentException("Укажите от двух до шести вариантов ответа.");
        }
        List<String> answers = new ArrayList<>();
        Set<String> unique = new HashSet<>();
        for (String answer : draft.answers) {
            String label = field(answer, 64, "вариант ответа");
            if (!unique.add(label)) throw new IllegalArgumentException("Варианты ответа должны различаться.");
            answers.add(label);
        }
        return new PollDefinition(draft.id, question, details, answers);
    }

    private static String field(String value, int maxLength, String label) {
        String text = value == null ? "" : value.trim();
        if (text.isEmpty() || text.length() > maxLength || text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0) {
            throw new IllegalArgumentException(
                "Проверьте " + label + ". Нужна одна строка до " + maxLength + " символов.");
        }
        return text;
    }

    public String toJson() {
        return JSON.toJson(this);
    }
}
