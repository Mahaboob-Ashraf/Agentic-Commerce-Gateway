package dev.agenticcommerce.gateway.agentization.execution;

import dev.agenticcommerce.gateway.agentization.service.AgentizationException;
import java.net.IDN;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class MerchantEndpointSafetyService {

    private static final Pattern HOST_LABEL = Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?");
    private final MerchantDnsResolver dnsResolver;

    public MerchantEndpointSafetyService(MerchantDnsResolver dnsResolver) {
        this.dnsResolver = dnsResolver;
    }

    public ValidatedEndpointResolution validateAndResolve(String candidate) {
        URI uri;
        try {
            uri = new URI(candidate);
        } catch (URISyntaxException | IllegalArgumentException exception) {
            throw reject("ENDPOINT_URL_MALFORMED", "Merchant endpoint URL is malformed");
        }
        if (!uri.isAbsolute() || !"https".equalsIgnoreCase(uri.getScheme())) {
            throw reject("ENDPOINT_HTTPS_REQUIRED", "Arbitrary merchant endpoints require HTTPS");
        }
        if (uri.getRawUserInfo() != null) {
            throw reject("ENDPOINT_USER_INFO_FORBIDDEN", "Endpoint credentials cannot be embedded in a URL");
        }
        if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw reject("ENDPOINT_BASE_URI_INVALID", "Endpoint base URI cannot contain query or fragment data");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw reject("ENDPOINT_HOST_INVALID", "Endpoint hostname is required");
        }
        host = canonicalHost(host);
        int port = uri.getPort() < 0 ? 443 : uri.getPort();
        if (port < 1 || port > 65535) {
            throw reject("ENDPOINT_PORT_INVALID", "Endpoint port is invalid");
        }
        List<InetAddress> addresses;
        try {
            addresses = List.copyOf(dnsResolver.resolve(host));
        } catch (UnknownHostException | RuntimeException exception) {
            throw reject("ENDPOINT_DNS_RESOLUTION_FAILED", "Endpoint DNS resolution failed closed");
        }
        if (addresses.isEmpty()) {
            throw reject("ENDPOINT_DNS_RESOLUTION_FAILED", "Endpoint DNS returned no addresses");
        }
        if (addresses.size() > 32) {
            throw reject("ENDPOINT_DNS_RESULT_LIMIT", "Endpoint DNS returned too many addresses");
        }
        for (InetAddress address : addresses) {
            if (isUnsafe(address)) {
                throw reject("ENDPOINT_ADDRESS_UNSAFE", "Endpoint resolves to an unsafe network address");
            }
        }
        try {
            URI canonical = new URI("https", null, host, port == 443 ? -1 : port,
                    normalizeBasePath(uri.getRawPath()), null, null);
            return new ValidatedEndpointResolution(canonical, host, port, addresses);
        } catch (URISyntaxException exception) {
            throw reject("ENDPOINT_URL_MALFORMED", "Endpoint URL cannot be canonicalized");
        }
    }

    static boolean isUnsafe(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address) {
            int first = Byte.toUnsignedInt(bytes[0]);
            int second = Byte.toUnsignedInt(bytes[1]);
            return first == 0
                    || first == 10
                    || first == 127
                    || (first == 169 && second == 254)
                    || (first == 172 && second >= 16 && second <= 31)
                    || (first == 192 && second == 168)
                    || (first >= 224);
        }
        if (address instanceof Inet6Address) {
            int first = Byte.toUnsignedInt(bytes[0]);
            int second = Byte.toUnsignedInt(bytes[1]);
            return (first & 0xfe) == 0xfc || (first == 0xfe && (second & 0xc0) == 0x80);
        }
        return true;
    }

    private static String canonicalHost(String input) {
        String host;
        try {
            host = IDN.toASCII(input, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException exception) {
            throw reject("ENDPOINT_HOST_INVALID", "Endpoint hostname is invalid");
        }
        if (host.length() > 253 || host.endsWith(".")) {
            throw reject("ENDPOINT_HOST_INVALID", "Endpoint hostname is invalid");
        }
        // URI accepts IP literals separately; DNS names must have valid labels.
        if (!host.contains(":") && !looksLikeIpv4(host)) {
            for (String label : host.split("\\.")) {
                if (!HOST_LABEL.matcher(label).matches()) {
                    throw reject("ENDPOINT_HOST_INVALID", "Endpoint hostname is invalid");
                }
            }
        }
        return host;
    }

    private static boolean looksLikeIpv4(String host) {
        return host.chars().allMatch(character -> Character.isDigit(character) || character == '.');
    }

    private static String normalizeBasePath(String path) {
        if (path == null || path.isBlank() || "/".equals(path)) {
            return null;
        }
        if (path.contains("..") || path.contains("\\")) {
            throw reject("ENDPOINT_BASE_URI_INVALID", "Endpoint base path is unsafe");
        }
        return path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    }

    private static AgentizationException reject(String code, String message) {
        return new AgentizationException(code, HttpStatus.UNPROCESSABLE_ENTITY, message);
    }
}
