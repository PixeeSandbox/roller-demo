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

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.Socket;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;


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
        
        try {
            URI uri = new URL(url).toURI();
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || (!scheme.equalsIgnoreCase("http")
                    && !scheme.equalsIgnoreCase("https")) || host == null
                    || uri.getUserInfo() != null || uri.getFragment() != null) {
                throw new MediacastException(BAD_URL, "weblogEdit.mediaCastUrlMalformed");
            }
            if (host.startsWith("[") && host.endsWith("]")) {
                host = host.substring(1, host.length() - 1);
            }

            int port = uri.getPort();
            if (port == -1) {
                port = scheme.equalsIgnoreCase("https") ? 443 : 80;
            }
            if ((scheme.equalsIgnoreCase("http") && port != 80)
                    || (scheme.equalsIgnoreCase("https") && port != 443)
                    || !isAuthorizedHost(host)) {
                throw new MediacastException(BAD_URL, "weblogEdit.mediaCastUrlMalformed");
            }

            InetAddress[] addresses = InetAddress.getAllByName(host);
            if (addresses.length == 0) {
                throw new MediacastException(BAD_URL, "weblogEdit.mediaCastUrlMalformed");
            }
            for (InetAddress address : addresses) {
                if (isProhibitedAddress(address)) {
                    throw new MediacastException(BAD_URL, "weblogEdit.mediaCastUrlMalformed");
                }
            }

            Socket socket = new Socket();
            try {
                socket.connect(new InetSocketAddress(addresses[0], port), 5000);
                socket.setSoTimeout(5000);
                if (scheme.equalsIgnoreCase("https")) {
                    SSLSocketFactory sslFactory = (SSLSocketFactory) SSLSocketFactory.getDefault();
                    SSLSocket sslSocket = (SSLSocket) sslFactory.createSocket(socket, host, port, true);
                    SSLParameters parameters = sslSocket.getSSLParameters();
                    parameters.setEndpointIdentificationAlgorithm("HTTPS");
                    if (!host.contains(":")) {
                        parameters.setServerNames(java.util.List.of(new SNIHostName(host)));
                    }
                    sslSocket.setSSLParameters(parameters);
                    sslSocket.startHandshake();
                    socket = sslSocket;
                    socket.setSoTimeout(5000);
                }

                String path = uri.getRawPath();
                if (path == null || path.isEmpty()) {
                    path = "/";
                }
                if (uri.getRawQuery() != null) {
                    path += "?" + uri.getRawQuery();
                }
                String requestHost = host.contains(":") ? "[" + host + "]" : host;
                if ((scheme.equalsIgnoreCase("http") && port != 80)
                        || (scheme.equalsIgnoreCase("https") && port != 443)) {
                    requestHost += ":" + port;
                }
                OutputStream output = socket.getOutputStream();
                output.write(("HEAD " + path + " HTTP/1.1\r\nHost: " + requestHost
                        + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                output.flush();

                BufferedReader reader = new BufferedReader(new InputStreamReader(
                        socket.getInputStream(), StandardCharsets.ISO_8859_1));
                String statusLine = reader.readLine();
                if (statusLine == null || !statusLine.matches("HTTP/\\d(?:\\.\\d)? \\d{3}.*")) {
                    throw new java.io.IOException("Invalid HTTP response");
                }
                int response = Integer.parseInt(statusLine.substring(9, 12));
                String message = statusLine.length() > 13 ? statusLine.substring(13) : "";
                Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
                String header;
                while ((header = reader.readLine()) != null && !header.isEmpty()) {
                    int separator = header.indexOf(':');
                    if (separator > 0) {
                        headers.put(header.substring(0, separator).trim(),
                                header.substring(separator + 1).trim());
                    }
                }

                if (response != 200) {
                    LOG.debug("Mediacast error " + response + ":" + message + " from url " + url);
                    throw new MediacastException(BAD_RESPONSE, "weblogEdit.mediaCastResponseError");
                }
                String contentType = headers.get("Content-Type");
                String contentLength = headers.get("Content-Length");
                long length = contentLength == null ? -1 : Long.parseLong(contentLength);
                if (contentType == null || length == -1) {
                    LOG.debug("Response valid, but contentType or length is invalid");
                    throw new MediacastException(INCOMPLETE, "weblogEdit.mediaCastLacksContentTypeOrLength");
                }
                MediacastResource resource = new MediacastResource(url, contentType, length);
                LOG.debug("Valid mediacast resource = " + resource.toString());
                return resource;
            } finally {
                socket.close();
            }
        } catch (MediacastException e) {
            throw e;
        } catch (MalformedURLException | java.net.URISyntaxException e) {
            LOG.debug("Malformed MediaCast url: " + url);
            throw new MediacastException(BAD_URL, "weblogEdit.mediaCastUrlMalformed", e);
        } catch (Exception e) {
            LOG.error("ERROR while checking MediaCast URL: " + url + ": " + e.getMessage());
            throw new MediacastException(CHECK_FAILED, "weblogEdit.mediaCastFailedFetchingInfo", e);
        }
    }

    private static boolean isAuthorizedHost(String host) {
        String allowedHosts = System.getProperty("roller.mediacast.allowedHosts", "");
        for (String allowedHost : allowedHosts.split(",")) {
            String normalizedAllowedHost = allowedHost.trim();
            if (normalizedAllowedHost.startsWith("[") && normalizedAllowedHost.endsWith("]")) {
                normalizedAllowedHost = normalizedAllowedHost.substring(1,
                        normalizedAllowedHost.length() - 1);
            }
            if (host.equalsIgnoreCase(normalizedAllowedHost)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isProhibitedAddress(InetAddress address) {
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int first = bytes[0] & 0xff;
            int second = bytes[1] & 0xff;
            int third = bytes[2] & 0xff;
            return address.isAnyLocalAddress() || address.isLoopbackAddress()
                    || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                    || address.isMulticastAddress() || first == 0 || first >= 224
                    || first == 100 && second >= 64 && second <= 127
                    || first == 192 && (second == 0 || second == 88 || second == 168)
                    || first == 192 && second == 0 && third == 2
                    || first == 192 && second == 88 && third == 99
                    || first == 198 && (second == 18 || second == 19 || second == 51)
                    || first == 203 && second == 0 && third == 113;
        }
        if (bytes.length == 16) {
            return address.isAnyLocalAddress() || address.isLoopbackAddress()
                    || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                    || address.isMulticastAddress() || (bytes[0] & 0xe0) != 0x20
                    || (bytes[0] == 0x20 && bytes[1] == 0x01
                        && ((bytes[2] & 0xff) <= 0x01
                            || ((bytes[2] & 0xff) == 0x0d && (bytes[3] & 0xff) == 0xb8)))
                    || (bytes[0] == 0x20 && bytes[1] == 0x02)
                    || (bytes[0] == 0x3f && (bytes[1] & 0xf0) == 0xf0);
        }
        return true;
    }
    
}
