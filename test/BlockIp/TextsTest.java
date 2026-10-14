package BlockIp;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;

class TextsTest {
    @Test
    void everyDefaultPlayerTextHasUkrainianAndRussian() throws Exception {
        ConfigManager.ConfigData defaults = new ConfigManager.ConfigData();
        for (Field f : ConfigManager.ConfigData.class.getFields()) {
            if (f.getType() != String.class || !(f.getName().endsWith("Text") || f.getName().equals("challengeTitle"))) continue;
            String english = (String) f.get(defaults);
            assertNotEquals(english, Texts.local(english, "uk"), f.getName() + " has no Ukrainian");
            assertNotEquals(english, Texts.local(english, "ru_RU"), f.getName() + " has no Russian");
            assertEquals(english, Texts.local(english, "en"), f.getName());
        }
    }

    @Test
    void aChangedTextAndAnUnknownLocaleAreShownAsWritten() {
        assertEquals("Custom rule", Texts.local("Custom rule", "uk"));
        String english = new ConfigManager.ConfigData().voteRefusedText;
        assertEquals(english, Texts.local(english, null));
        assertEquals(english, Texts.local(english, "de"));
        assertTrue(Texts.local(new ConfigManager.ConfigData().challengeText, "uk").contains("{word}"), "the word slot survives");
    }
}
