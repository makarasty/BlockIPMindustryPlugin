package BlockIp;

import java.util.Map;

/**
 * What players read, in their own language: Ukrainian and Russian beside the English defaults,
 * picked by the locale the client sends (the fleet's players are mostly one or the other). Keyed by
 * the English default itself, so a text an operator changed in the config has no translation and is
 * shown as written.
 */
final class Texts {
    private Texts() {}

    /** English default -> {Ukrainian, Russian}. Colour tags and {word} stay where they are. */
    private static final Map<String, String[]> LOCAL =
            Map.of(
                    "Your country is blocked on this server.",
                            new String[] {
                                "Вашу країну заблоковано на цьому сервері.",
                                "Ваша страна заблокирована на этом сервере."
                            },
                    "Quick check", new String[] {"Швидка перевірка", "Быстрая проверка"},
                    "Press the button that says [accent]{word}[] to play.",
                            new String[] {
                                "Натисніть кнопку з написом [accent]{word}[], щоб грати.",
                                "Нажмите кнопку с надписью [accent]{word}[], чтобы играть."
                            },
                    "Wrong button, or no answer in time.",
                            new String[] {
                                "Не та кнопка, або відповіді не було вчасно.",
                                "Не та кнопка, или ответа не было вовремя."
                            },
                    "VPN and hosting connections are not allowed on this server.",
                            new String[] {
                                "VPN і хостинг-підключення на цьому сервері заборонені.",
                                "VPN и хостинг-подключения на этом сервере запрещены."
                            },
                    "Too many players are connected from your address.",
                            new String[] {
                                "З вашої адреси підключено забагато гравців.",
                                "С вашего адреса подключено слишком много игроков."
                            },
                    "[scarlet]You cannot vote yet:[] play a while on this server first.",
                            new String[] {
                                "[scarlet]Голосувати поки не можна:[] спершу трохи пограйте на цьому сервері.",
                                "[scarlet]Голосовать пока нельзя:[] сначала немного поиграйте на этом сервере."
                            },
                    "[scarlet]Anti-bot check:[] walk a few steps with your unit, and a button will appear. Press the one it asks for.",
                            new String[] {
                                "[scarlet]Перевірка на бота:[] пройдіть кілька кроків своїм юнітом, і з'явиться кнопка. Натисніть ту, яку попросять.",
                                "[scarlet]Проверка на бота:[] пройдите несколько шагов своим юнитом, и появится кнопка. Нажмите ту, которую попросят."
                            },
                    "Too many players are joining at once. Try again in a minute.",
                            new String[] {
                                "Забагато гравців заходять одночасно. Спробуйте за хвилину.",
                                "Слишком много игроков заходят одновременно. Попробуйте через минуту."
                            });

    /** {@code text} for a client with {@code locale} ("uk", "ru_RU", ...; null when unknown). */
    static String local(String text, String locale) {
        if (text == null || locale == null) return text;
        String[] t = LOCAL.get(text);
        if (t == null) return text;
        if (locale.startsWith("uk")) return t[0];
        if (locale.startsWith("ru")) return t[1];
        return text;
    }
}
