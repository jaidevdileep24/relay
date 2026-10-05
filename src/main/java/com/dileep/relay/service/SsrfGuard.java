package com.dileep.relay.service;


import com.dileep.relay.api.error.ValidationException;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;


/**
 * Rejects endpoint URLs that point inside our own network (SSRF).
 *
 * <p>We call user-supplied URLs from behind our firewall, so a URL the attacker
 * cannot reach, we can. {@code https://169.254.169.254/...} is the cloud metadata
 * service - it would hand out IAM credentials.
 *
 * <p>Checks every IP the host resolves to, not just the first: an attacker can
 * list a public IP first and {@code 127.0.0.1} second.
 *
 * <p>Not covered yet: CGNAT ({@code 100.64/10}), IPv6 {@code fc00::/7}, and DNS
 * changing between this check and the connect. The sender must also refuse
 * redirects, since a public URL can 302 to loopback.
 */
@Component
public class SsrfGuard {

    public void validate(String url) {
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw  new ValidationException("malformed url: " + url);
        }

        if(!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new ValidationException("url must use https scheme: " + url);
        }


        // Credentials in the URL are never sent by HttpClient, and would be
        // echoed back in cleartext on every read of the endpoint.
        if (uri.getRawUserInfo() != null) {
            throw new ValidationException("url must not contain credentials: " + uri.getHost());
        }

        // -1 means "default for the scheme". Anything else outside the TCP range
        // was accepted, then failed every attempt until the retry budget ran out.
        int port = uri.getPort();
        if (port != -1 && (port < 1 || port > 65535)) {
            throw new ValidationException("url port out of range: " + port);
        }

        String host = uri.getHost();
        if(host == null || host.isBlank()) {
            throw new ValidationException("url must have a valid host: " + url);
        }

        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new ValidationException("unable to resolve host: " + host);
        }

        for (InetAddress address : addresses) {
            if (isInternal(address)) {
                throw new ValidationException("url resolves to an internal IP address: " + url);
            }
        }
    }

    private  static boolean isInternal(InetAddress address) {
        return address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isAnyLocalAddress()
                || address.isMulticastAddress();
    }


}
