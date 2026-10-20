package vn.ttcs.recruitment.application;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;

// Task 339: the quota bucket of a public tracking request. One bucket per IPv4 address and one per IPv6 /64, because
// one IPv6 subscriber usually holds a whole /64 and could otherwise use a fresh address for every attempt. The /64 is
// stored as its first address (low 64 bits zeroed), so the key is still a host value and V31's CHECK holds. IPv4-mapped
// IPv6 literals already resolve to IPv4 (ApplicationClientIpResolver), so they share the IPv4 bucket.
final class TrackingBuckets {
    private TrackingBuckets() { }

    // resolvedIp is ApplicationClientIpResolver's numeric literal; the character check keeps getByName from ever
    // looking up a host name.
    static String of(String resolvedIp) {
        if (resolvedIp == null || resolvedIp.isEmpty() || resolvedIp.length() > 45
                || !resolvedIp.matches("[0-9a-fA-F:.]+")) {
            throw new IllegalArgumentException("Not a numeric IP literal");
        }
        try {
            InetAddress address = InetAddress.getByName(resolvedIp);
            if (!(address instanceof Inet6Address)) return address.getHostAddress();
            byte[] bytes = address.getAddress();
            Arrays.fill(bytes, 8, 16, (byte) 0);
            return InetAddress.getByAddress(bytes).getHostAddress();
        } catch (UnknownHostException exception) {
            throw new IllegalArgumentException("Not a numeric IP literal");
        }
    }
}
