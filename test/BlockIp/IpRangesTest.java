package BlockIp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IpRangesTest {
    private static IpRanges of(String... lines) {
        IpRanges.Builder builder = new IpRanges.Builder();
        for (String line : lines) builder.add(line);
        return builder.build();
    }

    @Test
    void parsesOnlyPlainIpv4() {
        assertEquals(0x01020304L, IpRanges.parseIpv4("1.2.3.4"));
        assertEquals(0xFFFFFFFFL, IpRanges.parseIpv4("255.255.255.255"));
        assertEquals(0L, IpRanges.parseIpv4("0.0.0.0"));
        for (String bad : new String[]{null, "", "1.2.3", "1.2.3.4.5", "256.1.1.1", "1..2.3", ".1.2.3", "1.2.3.",
                "::1", "2a0a:51c1::a", "steam:123", "1.2.3.4 ", "a.b.c.d"}) {
            assertEquals(-1L, IpRanges.parseIpv4(bad), bad);
        }
    }

    @Test
    void matchesInsideAndAtTheEdgesOfARange() {
        IpRanges ranges = of("10.0.0.0/8", "192.168.1.0/24", "8.8.8.8");
        assertTrue(ranges.contains("10.0.0.0"));
        assertTrue(ranges.contains("10.255.255.255"));
        assertFalse(ranges.contains("11.0.0.0"));
        assertFalse(ranges.contains("9.255.255.255"));
        assertTrue(ranges.contains("192.168.1.77"));
        assertFalse(ranges.contains("192.168.2.0"));
        assertTrue(ranges.contains("8.8.8.8"));
        assertFalse(ranges.contains("8.8.8.9"));
        assertFalse(ranges.contains("2a0a:51c1::a"));
    }

    @Test
    void handlesTheUpperHalfOfTheAddressSpace() {
        // Addresses above 127.255.255.255 are negative as ints; ordering must still be unsigned
        IpRanges ranges = of("200.0.0.0/8", "100.0.0.0/8", "255.255.255.0/24");
        assertTrue(ranges.contains("200.1.2.3"));
        assertTrue(ranges.contains("100.1.2.3"));
        assertTrue(ranges.contains("255.255.255.255"));
        assertFalse(ranges.contains("150.0.0.1"));
        assertFalse(ranges.contains("201.0.0.0"));
    }

    @Test
    void mergesOverlappingAndTouchingRanges() {
        IpRanges ranges = of("1.0.0.0/24", "1.0.1.0/24", "1.0.0.128/25", "1.0.0.5", "5.0.0.0/8", "5.1.0.0/16");
        assertEquals(2, ranges.size());
        assertTrue(ranges.contains("1.0.1.255"));
        assertFalse(ranges.contains("1.0.2.0"));
    }

    @Test
    void skipsCommentsBlanksIpv6AndJunk() {
        IpRanges.Builder builder = new IpRanges.Builder();
        assertFalse(builder.add(""));
        assertFalse(builder.add("   # header"));
        assertFalse(builder.add("2001:db8::/32"));
        assertFalse(builder.add("1.2.3.4/33"));
        assertFalse(builder.add("1.2.3.4/x"));
        assertTrue(builder.add(" 1.2.3.0/24  # trailing comment"));
        assertTrue(builder.add("0.0.0.0/0"));
        IpRanges ranges = builder.build();
        assertEquals(1, ranges.size());
        assertTrue(ranges.contains("255.255.255.255"));
    }

    @Test
    void emptyContainsNothing() {
        assertFalse(of().contains("1.2.3.4"));
        assertEquals(0, IpRanges.EMPTY.size());
    }
}
