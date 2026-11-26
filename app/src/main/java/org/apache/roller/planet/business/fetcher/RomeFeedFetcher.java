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

package org.apache.roller.planet.business.fetcher;

import com.rometools.rome.feed.module.DCModule;
import com.rometools.rome.feed.synd.SyndCategory;
import com.rometools.rome.feed.synd.SyndContent;
import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.FeedException;
import com.rometools.rome.io.SyndFeedInput;
import com.rometools.rome.io.XmlReader;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import org.apache.commons.httpclient.ConnectTimeoutException;
import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.HttpMethod;
import org.apache.commons.httpclient.methods.GetMethod;
import org.apache.commons.httpclient.params.HttpConnectionParams;
import org.apache.commons.httpclient.protocol.Protocol;
import org.apache.commons.httpclient.protocol.ProtocolSocketFactory;
import org.apache.commons.httpclient.protocol.SecureProtocolSocketFactory;
import org.apache.commons.text.StringEscapeUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.roller.planet.pojos.SubscriptionEntry;
import org.apache.roller.planet.pojos.Subscription;


/**
 * A FeedFetcher based on Apache ROME and Apache Commons HttpClient.
 */
public class RomeFeedFetcher implements FeedFetcher {
    
    private static final Log log = LogFactory.getLog(RomeFeedFetcher.class);
    
    /**
     * Set the JVM system property roller.feed.allowedHosts to a comma-separated list of exact hostnames.
     * It defaults to no authorized hosts; deployments must set it to their approved feed hosts.
     */
    private static final String ALLOWED_FEED_HOSTS_PROPERTY = "roller.feed.allowedHosts";
    private static final int TIMEOUT_MILLIS = 3000;
    
    /**
     * @inheritDoc
     */
    @Override
    public Subscription fetchSubscription(String feedURL) throws FetcherException {
        return fetchSubscription(feedURL, null);
    }
    
    
    /**
     * @inheritDoc
     */
    @Override
    public Subscription fetchSubscription(String feedURL, Date lastModified) throws FetcherException {

        if(feedURL == null) {
            throw new IllegalArgumentException("feed url cannot be null");
        }
        
        // fetch the feed
        log.debug("Fetching feed: "+feedURL);
        SyndFeed feed;
        try {
            feed = fetchFeed(feedURL);
        } catch (FeedException | IOException ex) {
            throw new FetcherException("Error fetching subscription - "+feedURL, ex);
        }
        
        log.debug("Feed pulled, extracting data into Subscription");
        
        // build planet subscription from fetched feed
        Subscription newSub = new Subscription();
        newSub.setFeedURL(feedURL);
        newSub.setSiteURL(feed.getLink());
        newSub.setTitle(feed.getTitle());
        newSub.setAuthor(feed.getAuthor());
        newSub.setLastUpdated(feed.getPublishedDate());
        
        
        // normalize any data that couldn't be properly extracted
        if(newSub.getSiteURL() == null) {
            // set the site url to the feed url then
            newSub.setSiteURL(newSub.getFeedURL());
        }
        if(newSub.getAuthor() == null) {
            // set the author to the title
            newSub.setAuthor(newSub.getTitle());
        }
        
        // check if feed is unchanged and bail now if so
        if(lastModified != null && newSub.getLastUpdated() != null &&
                !newSub.getLastUpdated().after(lastModified)) {
            return null;
        }
        
        if(log.isDebugEnabled()) {
            log.debug("Subscription is: " + newSub.toString());
        }
        
        
        // some kludge to deal with feeds w/ no entry dates
        // we assign arbitrary dates chronologically by entry starting either
        // from the current time or the last update time of the subscription
        Calendar cal = Calendar.getInstance();
        if (newSub.getLastUpdated() != null) {
            cal.setTime(newSub.getLastUpdated());
        } else {
            cal.setTime(new Date());
            cal.add(Calendar.DATE, -1);
        }
        
        // add entries
        List<SyndEntry> feedEntries = feed.getEntries();
        for (SyndEntry feedEntry : feedEntries) {
            SubscriptionEntry newEntry = buildEntry(feedEntry);
            
            // some kludge to handle feeds with no entry dates
            if (newEntry.getPubTime() == null) {
                log.debug("No published date, assigning fake date for "+feedURL);
                newEntry.setPubTime(new Timestamp(cal.getTimeInMillis()));
                cal.add(Calendar.DATE, -1);
            }
            
            newSub.addEntry(newEntry);
        }
        
        log.debug(feedEntries.size()+" entries included");
        
        return newSub;
    }
    
    
    // build a SubscriptionEntry from Rome SyndEntry and SyndFeed
    private SubscriptionEntry buildEntry(SyndEntry romeEntry) {
        
        // if we don't have a permalink then we can't continue
        if(romeEntry.getLink() == null) {
            return null;
        }
        
        SubscriptionEntry newEntry = new SubscriptionEntry();
        
        newEntry.setTitle(romeEntry.getTitle());
        newEntry.setPermalink(romeEntry.getLink());
        
        // Play some games to get the author
        DCModule entrydc = (DCModule)romeEntry.getModule(DCModule.URI);
        if (romeEntry.getAuthor() != null) {
            newEntry.setAuthor(romeEntry.getAuthor());
        } else {
            // use <dc:creator>
            newEntry.setAuthor(entrydc.getCreator());
        }
        
        // Play some games to get the updated date
        if (romeEntry.getUpdatedDate() != null) {
            newEntry.setUpdateTime(new Timestamp(romeEntry.getUpdatedDate().getTime()));
        }
        // TODO: should we set a default update time here?
        
        // And more games getting publish date
        if (romeEntry.getPublishedDate() != null) {
            // use <pubDate>
            newEntry.setPubTime(new Timestamp(romeEntry.getPublishedDate().getTime()));
        } else if (entrydc != null && entrydc.getDate() != null) {
            // use <dc:date>
            newEntry.setPubTime(new Timestamp(entrydc.getDate().getTime()));
        } else {
            newEntry.setPubTime(newEntry.getUpdateTime());
        }
        
        // get content and unescape if it is 'text/plain'
        if (!romeEntry.getContents().isEmpty()) {
            SyndContent content= romeEntry.getContents().get(0);
            if (content != null && content.getType().equals("text/plain")) {
                newEntry.setText(StringEscapeUtils.unescapeHtml4(content.getValue()));
            } else if (content != null) {
                newEntry.setText(content.getValue());
            }
        }
        
        // no content, try summary
        if (StringUtils.isBlank(newEntry.getText()) && romeEntry.getDescription() != null)  {
            newEntry.setText(romeEntry.getDescription().getValue());
        }
        
        // copy categories
        if (!romeEntry.getCategories().isEmpty()) {
            List<String> list = new ArrayList<>();
            for (SyndCategory cat : romeEntry.getCategories()) {
                list.add(cat.getName());
            }
            newEntry.setCategoriesString(list);
        }
        
        return newEntry;
    }
    
    private SyndFeed fetchFeed(String url) throws IOException, FeedException {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException ex) {
            throw new IOException("Invalid feed URL", ex);
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        int port = uri.getPort();
        if (scheme == null || host == null || uri.getRawUserInfo() != null || uri.getRawAuthority() == null
                || uri.getRawAuthority().endsWith(":")
                || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                || (port != -1 && (port < 1 || port > 65535))) {
            throw new IOException("Invalid feed URL");
        }
        scheme = scheme.toLowerCase(Locale.ROOT);
        if (port == -1) {
            port = "https".equals(scheme) ? 443 : 80;
        }
        String allowedHosts = System.getProperty(ALLOWED_FEED_HOSTS_PROPERTY, "");
        boolean allowed = false;
        for (String allowedHost : allowedHosts.split(",")) {
            if (host.equalsIgnoreCase(allowedHost.trim())) {
                allowed = true;
                break;
            }
        }
        if (!allowed) {
            throw new IOException("Feed host is not authorized");
        }

        InetAddress[] addresses = InetAddress.getAllByName(host);
        if (addresses.length == 0) {
            throw new IOException("Feed host did not resolve");
        }
        for (InetAddress address : addresses) {
            if (!isPublicAddress(address)) {
                throw new IOException("Feed host resolves to a non-public address");
            }
        }

        HttpClient client = new HttpClient();
        ProtocolSocketFactory socketFactory = "https".equals(scheme)
                ? new PinnedSSLSocketFactory(host, addresses[0])
                : new PinnedSocketFactory(addresses[0]);
        Protocol protocol = new Protocol(scheme, socketFactory, port);
        HttpMethod method = new GetMethod(uri.toASCIIString());
        method.setFollowRedirects(false);
        method.setRequestHeader("User-Agent", "RollerPlanetAggregator");
        HttpConnectionParams params = client.getHttpConnectionManager().getParams();
        params.setConnectionTimeout(TIMEOUT_MILLIS);
        params.setSoTimeout(TIMEOUT_MILLIS);
        try {
            org.apache.commons.httpclient.HostConfiguration hostConfiguration =
                    new org.apache.commons.httpclient.HostConfiguration();
            hostConfiguration.setHost(host, port, protocol);
            client.executeMethod(hostConfiguration, method);
            try (XmlReader reader = new XmlReader(method.getResponseBodyAsStream())) {
                return new SyndFeedInput().build(reader);
            }
        } finally {
            method.releaseConnection();
        }
    }

    private static boolean isPublicAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return false;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int first = bytes[0] & 0xff;
            int second = bytes[1] & 0xff;
            int third = bytes[2] & 0xff;
            return first != 0 && first < 224
                    && !(first == 100 && second >= 64 && second <= 127)
                    && !(first == 192 && second == 0 && (third == 0 || third == 2))
                    && !(first == 192 && second == 88 && third == 99)
                    && !(first == 192 && second == 168)
                    && !(first == 198 && (second == 18 || second == 19))
                    && !(first == 198 && second == 51 && third == 100)
                    && !(first == 203 && second == 0 && third == 113);
        }
        return (bytes[0] & 0xe0) == 0x20
                && !(bytes[0] == 0x20 && bytes[1] == 0x01 && ((bytes[2] & 0xff) <= 1
                        || (bytes[2] == 0x0d && bytes[3] == (byte) 0xb8)))
                && !(bytes[0] == 0x20 && bytes[1] == 0x02)
                && !(bytes[0] == 0x3f && bytes[1] == (byte) 0xff && (bytes[2] & 0xf0) == 0);
    }

    private static class PinnedSocketFactory implements ProtocolSocketFactory {
        private final InetAddress address;

        PinnedSocketFactory(InetAddress address) {
            this.address = address;
        }

        @Override
        public Socket createSocket(String host, int port) throws IOException {
            return connect(port, TIMEOUT_MILLIS);
        }

        @Override
        public Socket createSocket(String host, int port, InetAddress localAddress, int localPort) throws IOException {
            return connect(port, TIMEOUT_MILLIS);
        }

        @Override
        public Socket createSocket(String host, int port, InetAddress localAddress, int localPort,
                HttpConnectionParams params) throws IOException, ConnectTimeoutException {
            return connect(port, params.getConnectionTimeout());
        }

        private Socket connect(int port, int timeout) throws IOException {
            Socket socket = new Socket();
            socket.connect(new InetSocketAddress(address, port), timeout);
            return socket;
        }
    }

    private static class PinnedSSLSocketFactory extends PinnedSocketFactory implements SecureProtocolSocketFactory {
        private final String hostname;
        private final InetAddress address;
        private final SSLSocketFactory sslFactory = (SSLSocketFactory) SSLSocketFactory.getDefault();

        PinnedSSLSocketFactory(String hostname, InetAddress address) {
            super(address);
            this.hostname = hostname;
            this.address = address;
        }

        @Override
        public Socket createSocket(Socket socket, String host, int port, boolean autoClose) throws IOException {
            return secure(socket);
        }

        @Override
        public Socket createSocket(String host, int port) throws IOException {
            return secure(connectPinned(port, TIMEOUT_MILLIS));
        }

        @Override
        public Socket createSocket(String host, int port, InetAddress localAddress, int localPort) throws IOException {
            return secure(connectPinned(port, TIMEOUT_MILLIS));
        }

        @Override
        public Socket createSocket(String host, int port, InetAddress localAddress, int localPort,
                HttpConnectionParams params) throws IOException, ConnectTimeoutException {
            return secure(connectPinned(port, params.getConnectionTimeout()));
        }

        private Socket connectPinned(int port, int timeout) throws IOException {
            Socket socket = new Socket();
            socket.connect(new InetSocketAddress(address, port), timeout);
            return socket;
        }

        private Socket secure(Socket socket) throws IOException {
            socket.setSoTimeout(TIMEOUT_MILLIS);
            try {
                SSLSocket sslSocket = (SSLSocket) sslFactory.createSocket(socket, hostname, socket.getPort(), true);
                SSLParameters sslParameters = sslSocket.getSSLParameters();
                sslParameters.setEndpointIdentificationAlgorithm("HTTPS");
                if (!hostname.contains(":") && !hostname.matches("[0-9.]+")) {
                    sslParameters.setServerNames(java.util.Collections.singletonList(new SNIHostName(hostname)));
                }
                sslSocket.setSSLParameters(sslParameters);
                sslSocket.startHandshake();
                return sslSocket;
            } catch (IOException ex) {
                socket.close();
                throw ex;
            }
        }
    }
    
}
