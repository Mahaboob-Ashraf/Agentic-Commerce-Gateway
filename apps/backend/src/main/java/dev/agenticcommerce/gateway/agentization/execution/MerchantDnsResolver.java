package dev.agenticcommerce.gateway.agentization.execution;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

public interface MerchantDnsResolver {
    List<InetAddress> resolve(String hostname) throws UnknownHostException;
}
