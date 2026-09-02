package dev.agenticcommerce.gateway.agentization.execution;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.util.Timeout;
import org.springframework.stereotype.Component;

/**
 * Production remote transport. A fresh connection manager pins the approved hostname to the
 * immediately revalidated address set. The request URI retains the hostname, preserving normal TLS
 * SNI and certificate hostname validation. Redirect handling is disabled.
 */
@Component
public class PinnedHttpsMerchantTransport implements MerchantTransport {

    @Override
    public MerchantTransportResponse execute(
            ValidatedEndpointResolution resolution, MerchantTransportRequest request) {
        var connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
                .setDnsResolver(new DnsResolver() {
                    @Override
                    public InetAddress[] resolve(String host) throws UnknownHostException {
                        return pinned(host, resolution);
                    }

                    @Override
                    public String resolveCanonicalHostname(String host) throws UnknownHostException {
                        pinned(host, resolution);
                        return resolution.hostname();
                    }
                })
                .build();
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(Timeout.ofMilliseconds(request.connectTimeoutMs()))
                .setConnectionRequestTimeout(Timeout.ofMilliseconds(request.connectTimeoutMs()))
                .setResponseTimeout(Timeout.ofMilliseconds(request.requestTimeoutMs()))
                .build();
        try (CloseableHttpClient client = HttpClients.custom()
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(requestConfig)
                .disableRedirectHandling()
                .build()) {
            HttpUriRequestBase httpRequest = new HttpUriRequestBase(request.method(), request.uri());
            httpRequest.setHeader("Accept", "application/json");
            request.headers().forEach(httpRequest::setHeader);
            if (request.jsonBody().length > 0) {
                httpRequest.setEntity(new ByteArrayEntity(request.jsonBody(), ContentType.APPLICATION_JSON));
            }
            return client.execute(httpRequest, response -> {
                String contentType = response.getEntity() == null || response.getEntity().getContentType() == null
                        ? "" : response.getEntity().getContentType();
                byte[] body = response.getEntity() == null
                        ? new byte[0]
                        : readBounded(response.getEntity().getContent(), request.maximumResponseBytes());
                return new MerchantTransportResponse(response.getCode(), contentType, body);
            });
        } catch (MerchantExecutionException exception) {
            throw exception;
        } catch (IOException exception) {
            String name = exception.getClass().getSimpleName().toLowerCase(Locale.ROOT);
            String code = name.contains("timeout") ? "MERCHANT_TIMEOUT" : "MERCHANT_TRANSPORT_FAILURE";
            throw new MerchantExecutionException(code, "Approved merchant request failed safely", exception);
        }
    }

    private static InetAddress[] pinned(String host, ValidatedEndpointResolution resolution)
            throws UnknownHostException {
        if (!resolution.hostname().equalsIgnoreCase(host)) {
            throw new UnknownHostException("Transport attempted an unapproved hostname");
        }
        return resolution.addresses().toArray(InetAddress[]::new);
    }

    private static byte[] readBounded(java.io.InputStream input, int maximumBytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maximumBytes, 8192));
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            total += read;
            if (total > maximumBytes) {
                throw new MerchantExecutionException(
                        "MERCHANT_RESPONSE_TOO_LARGE", "Merchant response exceeded the approved limit");
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }
}
