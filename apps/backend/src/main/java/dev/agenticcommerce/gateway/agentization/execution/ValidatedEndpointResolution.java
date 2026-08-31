package dev.agenticcommerce.gateway.agentization.execution;

import java.net.InetAddress;
import java.net.URI;
import java.util.List;

public record ValidatedEndpointResolution(URI baseUri, String hostname, int port, List<InetAddress> addresses) {
}
