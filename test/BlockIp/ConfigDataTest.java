package BlockIp;

import arc.util.serialization.Json;
import arc.util.serialization.JsonReader;
import arc.util.serialization.JsonWriter.OutputType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigDataTest {
    /** Configured as ConfigManager's own Json is. */
    private static ConfigManager.ConfigData read(String text) {
        Json json = new Json(OutputType.json);
        json.setIgnoreUnknownFields(true);
        json.setUsePrototypes(false);
        ConfigManager.ConfigData data = json.readValue(ConfigManager.ConfigData.class, new JsonReader().parse(text));
        data.repairNulls();
        return data;
    }

    @Test
    void nullsTakeTheDefaults() {
        ConfigManager.ConfigData defaults = new ConfigManager.ConfigData();
        ConfigManager.ConfigData data = read("{\"kickText\":null,\"vpnKickText\":null,\"duplicateKickText\":null,"
                + "\"ipWhiteList\":null,\"uuidWhiteList\":[null,\"u\"],\"blockedCountries\":null,\"vpnLists\":null}");

        assertEquals(defaults.kickText, data.kickText);
        assertEquals(defaults.vpnKickText, data.vpnKickText);
        assertEquals(defaults.duplicateKickText, data.duplicateKickText);
        assertTrue(data.ipWhiteList.isEmpty());
        assertEquals(1, data.uuidWhiteList.size());
        assertTrue(data.blockedCountries.isEmpty());
        assertEquals(defaults.vpnLists, data.vpnLists);
    }

    @Test
    void aFileFromTheOlderBuildKeepsItsValuesAndGetsTheNewDefaults() {
        ConfigManager.ConfigData data = read("{\"blockedCountries\":[\"RU\"],\"ipWhiteList\":[\"1.2.3.4\"],"
                + "\"uuidWhiteList\":[],\"kickText\":\"Nope\",\"somethingElse\":1}");

        assertTrue(data.blockedCountries.contains("RU"));
        assertTrue(data.ipWhiteList.contains("1.2.3.4"));
        assertEquals("Nope", data.kickText);
        assertEquals(VpnGuard.Mode.auto, data.mode());
        assertEquals(2, data.maxPlayersPerIp);
        assertEquals(4, data.maxConnectionsPerIp);
    }
}
