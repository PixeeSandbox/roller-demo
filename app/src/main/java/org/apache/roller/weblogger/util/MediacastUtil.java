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

import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.UnknownHostException;
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
        
        MediacastResource resource = null;
        try {
            URI uri = new URI(url.trim());
            String scheme = uri.getScheme();
            if (scheme == null || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    || uri.getHost() == null) {
                throw new MalformedURLException("Unsupported MediaCast URL");
            }
            for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
                if (isInternalAddress(address)) {
                    throw new MalformedURLException("Unsupported MediaCast URL");
                }
            }

            URL validatedUrl = uri.toURL();
            HttpURLConnection con = (HttpURLConnection) validatedUrl.openConnection();
            con.setInstanceFollowRedirects(false);
            con.setRequestMethod("HEAD");
            int response = con.getResponseCode();
            String message = con.getResponseMessage();
            
            if(response != 200) {
                LOG.debug("Mediacast error " + response + ":" + message + " from url " + url);
                throw new MediacastException(BAD_RESPONSE, "weblogEdit.mediaCastResponseError");
            } else {
                String contentType = con.getContentType();
                long length = con.getContentLength();
                
                if(contentType == null || length == -1) {
                    LOG.debug("Response valid, but contentType or length is invalid");
                    throw new MediacastException(INCOMPLETE, "weblogEdit.mediaCastLacksContentTypeOrLength");
                }
                
                resource = new MediacastResource(url, contentType, length);
                LOG.debug("Valid mediacast resource = " + resource.toString());
                
            }
        } catch (MalformedURLException | URISyntaxException | UnknownHostException ex) {
            LOG.debug("Malformed MediaCast url: " + url);
            throw new MediacastException(BAD_URL, "weblogEdit.mediaCastUrlMalformed", ex);
        } catch (Exception e) {
            LOG.error("ERROR while checking MediaCast URL: " + url + ": " + e.getMessage());
            throw new MediacastException(CHECK_FAILED, "weblogEdit.mediaCastFailedFetchingInfo", e);
        }      
        return resource;
    }
    
    private static boolean isInternalAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }

        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int first = bytes[0] & 0xff;
            int second = bytes[1] & 0xff;
            if (first == 10 || first == 127 || first == 0) {
                return true;
            }
            if (first == 100 && second >= 64 && second <= 127) {
                return true;
            }
            if (first == 172 && second >= 16 && second <= 31) {
                return true;
            }
            return first == 192 && second == 168;
        }

        if (bytes.length == 16) {
            int first = bytes[0] & 0xff;
            int second = bytes[1] & 0xff;
            return (first & 0xfe) == 0xfc || (first == 0xfe && (second & 0xc0) == 0x80);
        }

        return true;
    }
}
