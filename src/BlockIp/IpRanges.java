package BlockIp;

import java.util.Arrays;

/**
 * A set of IPv4 ranges, sorted and merged into two int arrays and searched by bisection.
 *
 * A list of ~70,000 hosting and VPN networks costs about 8 bytes a range here and a lookup is
 * ~17 comparisons with no allocation, which is what a check that runs on every join can afford.
 * Addresses are stored with the sign bit flipped, so signed int order is unsigned address order.
 */
final class IpRanges {
    static final IpRanges EMPTY = new IpRanges(new int[0], new int[0]);

    private final int[] starts;
    private final int[] ends;

    private IpRanges(int[] starts, int[] ends) {
        this.starts = starts;
        this.ends = ends;
    }

    int size() {
        return starts.length;
    }

    /** {@code ip} is the address's 32 bits, as {@link #parseIpv4} returns them. */
    boolean contains(int ip) {
        int key = ip ^ Integer.MIN_VALUE;
        int lo = 0, hi = starts.length - 1, candidate = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (starts[mid] <= key) {
                candidate = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return candidate >= 0 && key <= ends[candidate];
    }

    boolean contains(String address) {
        long ip = parseIpv4(address);
        return ip >= 0 && contains((int) ip);
    }

    /** The 32 bits of a dotted IPv4 literal, or -1 for anything else (IPv6, a name, junk). */
    static long parseIpv4(String text) {
        if (text == null) return -1;
        int n = text.length();
        long value = 0;
        int octet = -1, dots = 0;
        for (int i = 0; i < n; i++) {
            char c = text.charAt(i);
            if (c >= '0' && c <= '9') {
                octet = (octet < 0 ? 0 : octet * 10) + (c - '0');
                if (octet > 255) return -1;
            } else if (c == '.' && octet >= 0 && dots < 3) {
                value = (value << 8) | octet;
                octet = -1;
                dots++;
            } else {
                return -1;
            }
        }
        if (dots != 3 || octet < 0) return -1;
        return (value << 8) | octet;
    }

    /** Collects ranges line by line, then sorts and merges them once. */
    static final class Builder {
        private long[] packed = new long[1024];
        private int count;

        /**
         * Adds "a.b.c.d/nn" or "a.b.c.d"; anything after '#' is a comment. Returns false for a line
         * that holds no IPv4 range (blank, comment, IPv6, junk), which the caller may count or ignore.
         */
        boolean add(String line) {
            int hash = line.indexOf('#');
            String entry = (hash >= 0 ? line.substring(0, hash) : line).trim();
            if (entry.isEmpty()) return false;

            int slash = entry.indexOf('/');
            long ip = parseIpv4(slash >= 0 ? entry.substring(0, slash) : entry);
            if (ip < 0) return false;

            int prefix = 32;
            if (slash >= 0) {
                try {
                    prefix = Integer.parseInt(entry.substring(slash + 1).trim());
                } catch (NumberFormatException e) {
                    return false;
                }
                if (prefix < 0 || prefix > 32) return false;
            }

            int mask = prefix == 0 ? 0 : -1 << (32 - prefix);
            int start = (int) ip & mask;
            int end = start | ~mask;
            if (count == packed.length) packed = Arrays.copyOf(packed, count * 2);
            packed[count++] = ((long) (start ^ Integer.MIN_VALUE) << 32) | ((end ^ Integer.MIN_VALUE) & 0xFFFFFFFFL);
            return true;
        }

        IpRanges build() {
            if (count == 0) return EMPTY;
            // The high half is the flipped start, so sorting the longs sorts by start
            Arrays.sort(packed, 0, count);

            int[] starts = new int[count];
            int[] ends = new int[count];
            int merged = 0;
            for (int i = 0; i < count; i++) {
                int start = (int) (packed[i] >> 32);
                int end = (int) packed[i];
                // Overlapping or touching the previous range: extend it instead of adding one
                if (merged > 0 && (long) start <= (long) ends[merged - 1] + 1) {
                    if (end > ends[merged - 1]) ends[merged - 1] = end;
                } else {
                    starts[merged] = start;
                    ends[merged] = end;
                    merged++;
                }
            }
            packed = null;
            return new IpRanges(Arrays.copyOf(starts, merged), Arrays.copyOf(ends, merged));
        }
    }
}
