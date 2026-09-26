package BlockIp;

import arc.files.Fi;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VpnListTest {
    private static void await() throws InterruptedException {
        for (int i = 0; i < 200 && VpnList.isLoading(); i++) Thread.sleep(25);
        assertFalse(VpnList.isLoading(), "the load did not finish");
    }

    @Test
    void downloadsEverySourceIntoTheCacheAndLoadsIt(@TempDir Path dir) throws Exception {
        Path dc = Files.writeString(dir.resolve("dc.txt"), "1.0.0.0/24\n# comment\n2001:db8::/32");
        Path vpn = Files.writeString(dir.resolve("vpn.txt"), "9.9.9.9");
        Fi cache = new Fi(dir.resolve("vpn-ipv4.txt").toFile());

        VpnList.refresh(List.of(dc.toUri().toString(), vpn.toUri().toString()), cache, 3_600_000L, false);
        await();

        assertTrue(VpnList.contains("1.0.0.200"));
        assertTrue(VpnList.contains("9.9.9.9"));
        assertFalse(VpnList.contains("1.0.1.0"));
        assertEquals(2, VpnList.size());
        assertTrue(cache.exists());
    }

    @Test
    void aFailedDownloadKeepsTheLastGoodCache(@TempDir Path dir) throws Exception {
        Fi cache = new Fi(dir.resolve("vpn-ipv4.txt").toFile());
        cache.writeString("5.5.5.0/24\n");
        String missing = dir.resolve("nope.txt").toUri().toString();

        VpnList.refresh(List.of(missing), cache, 0L, true);
        await();

        assertTrue(VpnList.contains("5.5.5.5"));
        assertEquals("5.5.5.0/24\n", cache.readString());
        assertFalse(cache.sibling("vpn-ipv4.txt.part").exists());
    }

    @Test
    void aFreshCacheIsReadWithoutDownloading(@TempDir Path dir) throws Exception {
        Fi cache = new Fi(dir.resolve("vpn-ipv4.txt").toFile());
        cache.writeString("7.7.7.7\n");

        VpnList.refresh(List.of(dir.resolve("nope.txt").toUri().toString()), cache, 3_600_000L, false);
        await();

        assertTrue(VpnList.contains("7.7.7.7"));
    }
}
