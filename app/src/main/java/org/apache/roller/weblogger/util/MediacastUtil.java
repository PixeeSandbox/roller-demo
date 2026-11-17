/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  The ASF licenses this file to You
 * under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.  For additional information regarding
 * copyright in this work, please see the NOTICE file in the top level
 * directory of this distribution.
 */

package org.apache.roller.weblogger.util;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Arrays;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.classic.methods.HttpHead;
import org.apache.hc.core5.util.Timeout;


/**
 * Utility for deailing with mediacast files.
 */
public final class MediacastUtil {
    
    private static final Log LOG = LogFactory.getLog(MediacastUtil.class);
    
    public static final int BAD_URL = 1;
    public static final int CHECK_FAILED = 2;
    public static final int BAD_RESPONSE = 3;
    public static final int INCOMPLETE = 4;
    
    
    // non-instantiable
    private MediacastUtil() {}
    
    
    /**
     * Validate a Mediacast resource.
     */
    public static MediacastResource lookupResource(String url)
            throws MediacastException {
        
        if(url == null || url.isBlank()) {
            return null;
        }
        
        MediacastResource resource = null;
        try {
            URI uri = new URI(url);
            if (!uri.isAbsolute() || uri.getHost() == null || uri.getUserInfo() != null
                    || !("http".equalsIgnoreCase(uri.getScheme())
                    || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getPort() < -1 || uri.getPort() > 65535
                    || (uri.getRawAuthority() != null && uri.getPort() == -1
                    && uri.getRawAuthority().endsWith(":"))) {
                throw new URISyntaxException(url, "Invalid MediaCast URL");
            }
            String uriHost = uri.getHost();
            final String host = uriHost.startsWith("[") && uriHost.endsWith("]")
                    ? uriHost.substring(1, uriHost.length() - 1) : uriHost;
            InetAddress[] addresses = InetAddress.getAllByName(host);
            if (addresses.length == 0 || Arrays.stream(addresses).anyMatch(a -> !isPublicAddress(a))) {
                throw new URISyntaxException(url, "MediaCast destination is not public");
            }
            DnsResolver resolver = new DnsResolver() {
                @Override
                public InetAddress[] resolve(String requestedHost) throws UnknownHostException {
                    if (!host.equalsIgnoreCase(requestedHost)) {
                        throw new UnknownHostException("Unvalidated destination");
                    }
                    return addresses.clone();
                }

                @Override
                public String resolveCanonicalHostname(String requestedHost) throws UnknownHostException {
                    if (!host.equalsIgnoreCase(requestedHost)) {
                        throw new UnknownHostException("Unvalidated destination");
                    }
                    return requestedHost;
                }
            };
            RequestConfig requestConfig = RequestConfig.custom()
                    .setConnectionRequestTimeout(Timeout.ofSeconds(5))
                    .setResponseTimeout(Timeout.ofSeconds(10))
                    .build();
            PoolingHttpClientConnectionManager connectionManager =
                    PoolingHttpClientConnectionManagerBuilder.create()
                    .setDnsResolver(resolver)
                    .setDefaultConnectionConfig(ConnectionConfig.custom()
                            .setConnectTimeout(Timeout.ofSeconds(5)).build())
                    .build();
            try (CloseableHttpClient client = HttpClients.custom()
                    .setConnectionManager(connectionManager)
                    .setDefaultRequestConfig(requestConfig)
                    .disableRedirectHandling()
                    .build()) {
                HttpHead request = new HttpHead(uri);
                try (CloseableHttpResponse response = client.execute(request)) {
                    int status = response.getCode();
                    String message = response.getReasonPhrase();
                    if(status != 200) {
                        LOG.debug("Mediacast error " + status + ":" + message + " from url " + url);
                        throw new MediacastException(BAD_RESPONSE, "weblogEdit.mediaCastResponseError");
                    }
                    String contentType = response.getFirstHeader("Content-Type") == null ? null
                            : response.getFirstHeader("Content-Type").getValue();
                    String contentLength = response.getFirstHeader("Content-Length") == null ? null
                            : response.getFirstHeader("Content-Length").getValue();
                    long length = contentLength == null ? -1 : Long.parseLong(contentLength);
                    if(contentType == null || length == -1) {
                        LOG.debug("Response valid, but contentType or length is invalid");
                        throw new MediacastException(INCOMPLETE, "weblogEdit.mediaCastLacksContentTypeOrLength");
                    }
                    resource = new MediacastResource(url, contentType, length);
                    LOG.debug("Valid mediacast resource = " + resource.toString());
                }
            }
        } catch (MediacastException e) {
            throw e;
        } catch (URISyntaxException | UnknownHostException e) {
            LOG.debug("Malformed or rejected MediaCast url: " + url);
            throw new MediacastException(BAD_URL, "weblogEdit.mediaCastUrlMalformed", e);
        } catch (Exception e) {
            LOG.error("ERROR while checking MediaCast URL: " + url + ": " + e.getMessage());
            throw new MediacastException(CHECK_FAILED, "weblogEdit.mediaCastFailedFetchingInfo", e);
        }
        return resource;
    }

    private static boolean isPublicAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return false;
        }
        if (address instanceof Inet4Address) {
            byte[] bytes = address.getAddress();
            int first = bytes[0] & 0xff;
            int second = bytes[1] & 0xff;
            int third = bytes[2] & 0xff;
            return first != 0 && first != 10 && first != 127 && first < 224
                    && !(first == 100 && second >= 64 && second <= 127)
                    && !(first == 169 && second == 254)
                    && !(first == 172 && second >= 16 && second <= 31)
                    && !(first == 192 && (second == 168
                    || second == 0 && (third == 0 || third == 2)))
                    && !(first == 192 && second == 88 && third == 99)
                    && !(first == 198 && (second == 18 || second == 19 || second == 51 && third == 100))
                    && !(first == 203 && second == 0 && third == 113);
        }
        if (address instanceof Inet6Address) {
            byte[] bytes = address.getAddress();
            int first = bytes[0] & 0xff;
            int second = bytes[1] & 0xff;
            return (first & 0xe0) == 0x20 && (first & 0xfe) != 0xfc
                    && !(first == 0xfe && (second & 0xc0) == 0x80)
                    && !(first == 0x20 && second == 0x01 && (bytes[2] & 0xfe) == 0)
                    && !(first == 0x20 && second == 0x01 && (bytes[2] & 0xff) == 0x0d
                    && (bytes[3] & 0xff) == 0xb8)
                    && !(first == 0x20 && second == 0x02);
        }
        return false;
    }
    
}
