package vn.ttcs.recruitment.application;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import vn.ttcs.recruitment.common.ApiException;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

@Component
public class ApplicationClientIpResolver {
    private final List<Network> trusted;

    public ApplicationClientIpResolver(@Value("${app.application-intake.trusted-proxies:}") String proxies,
            @Value("${server.forward-headers-strategy:NONE}") String forwardStrategy) {
        // Container/framework rewriting would erase the actual peer before this trust check.
        if (!"NONE".equalsIgnoreCase(forwardStrategy)) {
            throw new IllegalArgumentException("Application IP quota requires server.forward-headers-strategy=NONE");
        }
        if (proxies.length() > 4096) throw new IllegalArgumentException("Too many trusted proxies");
        var networks = new ArrayList<Network>();
        if (!proxies.isBlank()) {
            for (String item : proxies.split(",", -1)) networks.add(Network.parse(item.trim()));
        }
        if (networks.size() > 64) throw new IllegalArgumentException("Too many trusted proxies");
        trusted = List.copyOf(networks);
    }

    public String resolve(HttpServletRequest request) {
        try {
            InetAddress peer = literal(request.getRemoteAddr());
            if (!isTrusted(peer)) return peer.getHostAddress();
            var values = request.getHeaders("X-Forwarded-For");
            var chain = new ArrayList<InetAddress>();
            int bytes = 0;
            while (values != null && values.hasMoreElements()) {
                String value = values.nextElement();
                bytes += value.length();
                if (bytes > 2048) throw new IllegalArgumentException();
                for (String token : value.split(",", -1)) {
                    if (chain.size() >= 16) throw new IllegalArgumentException();
                    chain.add(literal(token.trim()));
                }
            }
            if (chain.isEmpty()) throw new IllegalArgumentException();
            // Walk from the real peer towards the client; never choose an attacker-controlled leftmost value.
            for (int i = chain.size() - 1; i >= 0; i--) {
                InetAddress address = chain.get(i);
                if (!isTrusted(address) || i == 0) return address.getHostAddress();
            }
            throw new IllegalArgumentException();
        } catch (IllegalArgumentException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "APPLICATION_CLIENT_IP_INVALID",
                    "Không thể xác định địa chỉ gửi hồ sơ. Vui lòng thử lại sau.");
        }
    }

    private boolean isTrusted(InetAddress address) {
        for (Network network : trusted) if (network.contains(address.getAddress())) return true;
        return false;
    }

    private static InetAddress literal(String value) {
        if (value == null || value.isEmpty() || value.length() > 45) throw new IllegalArgumentException();
        if (value.indexOf(':') >= 0) {
            // Only numeric IPv6 syntax reaches getByName: no hostname, scope, brackets, port or DNS lookup.
            if (!value.matches("[0-9a-fA-F:.]+")) throw new IllegalArgumentException();
            if (value.contains(".")) ipv4(value.substring(value.lastIndexOf(':') + 1));
        } else ipv4(value);
        try { return InetAddress.getByName(value); }
        catch (UnknownHostException exception) { throw new IllegalArgumentException(); }
    }

    private static void ipv4(String value) {
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4) throw new IllegalArgumentException();
        for (String part : parts) {
            if (!part.matches("0|[1-9][0-9]{0,2}") || Integer.parseInt(part) > 255) throw new IllegalArgumentException();
        }
    }

    private record Network(byte[] address, int bits) {
        static Network parse(String value) {
            String[] parts = value.split("/", -1);
            if (parts.length > 2) throw new IllegalArgumentException("Invalid trusted proxy network");
            byte[] address = literal(parts[0]).getAddress();
            int bits = address.length * 8;
            if (parts.length == 2) {
                if (!parts[1].matches("[0-9]{1,3}")) throw new IllegalArgumentException("Invalid trusted proxy prefix");
                bits = Integer.parseInt(parts[1]);
            }
            if (bits < 1 || bits > address.length * 8) throw new IllegalArgumentException("Invalid trusted proxy prefix");
            return new Network(address, bits);
        }
        boolean contains(byte[] candidate) {
            if (candidate.length != address.length) return false;
            for (int i = 0; i < bits; i++) {
                int mask = 1 << (7 - i % 8);
                if ((candidate[i / 8] & mask) != (address[i / 8] & mask)) return false;
            }
            return true;
        }
    }
}
