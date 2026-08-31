package dev.agenticcommerce.gateway.agentization.execution;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class SystemMerchantDnsResolver implements MerchantDnsResolver {
    @Override
    public List<InetAddress> resolve(String hostname) throws UnknownHostException {
        return List.of(InetAddress.getAllByName(hostname));
    }
}
