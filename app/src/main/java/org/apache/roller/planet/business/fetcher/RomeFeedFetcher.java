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
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;
import okhttp3.Dns;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.apache.commons.text.StringEscapeUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.roller.planet.pojos.SubscriptionEntry;
import org.apache.roller.planet.pojos.Subscription;



/**
 * A FeedFetcher based on Apache ROME and OkHttp.
 */
public class RomeFeedFetcher implements FeedFetcher {
    
    private static final Log log = LogFactory.getLog(RomeFeedFetcher.class);
    
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(3);
    private static final String APPROVED_HOSTS_PROPERTY = "rollerplanet.feed.allowedHosts";

    public RomeFeedFetcher() {
    }

    public static List<InetAddress> resolvePublicAddresses(String host, Dns resolver) throws UnknownHostException {
        List<InetAddress> addresses = resolver.lookup(host);
        if (addresses.isEmpty()) {
            throw new UnknownHostException("No addresses found for host");
        }
        for (InetAddress address : addresses) {
            if (!isPublicAddress(address)) {
                throw new UnknownHostException("Host resolves to a non-public address");
            }
        }
        return List.copyOf(addresses);
    }

    public static OkHttpClient createPinnedHttpClient(String host, Dns resolver) throws UnknownHostException {
        List<InetAddress> addresses = resolvePublicAddresses(host, resolver);
        Dns pinnedResolver = requestedHost -> {
            if (!host.equalsIgnoreCase(requestedHost)) {
                throw new UnknownHostException("Unexpected host");
            }
            return addresses;
        };
        return new OkHttpClient.Builder()
                .dns(pinnedResolver)
                .callTimeout(REQUEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                .connectTimeout(REQUEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                .readTimeout(REQUEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                .writeTimeout(REQUEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .build();
    }

    private static boolean isPublicAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return false;
        }
        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address) {
            int first = bytes[0] & 0xff;
            int second = bytes[1] & 0xff;
            int third = bytes[2] & 0xff;
            return first != 0 && first < 224
                    && !(first == 100 && second >= 64 && second <= 127)
                    && !(first == 192 && (second == 0 || second == 2 || second == 168))
                    && !(first == 192 && second == 88 && third == 99)
                    && !(first == 198 && (second == 18 || second == 19))
                    && !(first == 198 && second == 51 && third == 100)
                    && !(first == 203 && second == 0 && third == 113);
        }
        if (address instanceof Inet6Address) {
            int first = bytes[0] & 0xff;
            int second = bytes[1] & 0xff;
            return (first & 0xe0) == 0x20
                    && !(first == 0x20 && second == 0x01 && (bytes[2] & 0xfe) == 0)
                    && !(first == 0x20 && second == 0x01 && (bytes[2] & 0xff) == 0x0d
                            && (bytes[3] & 0xff) == 0xb8)
                    && !(first == 0x20 && second == 0x02);
        }
        return false;
    }

    public static boolean isApprovedHost(String host) {
        String approvedHosts = System.getProperty(APPROVED_HOSTS_PROPERTY, "");
        for (String approvedHost : approvedHosts.split(",")) {
            if (!approvedHost.trim().isEmpty() && host.equalsIgnoreCase(approvedHost.trim())) {
                return true;
            }
        }
        return false;
    }
    
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
        } catch (FeedException | IOException | InterruptedException ex) {
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
    
    private SyndFeed fetchFeed(String url) throws IOException, InterruptedException, FeedException {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException ex) {
            throw new IOException("Invalid feed URL", ex);
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        String resolverHost = host != null && host.startsWith("[") && host.endsWith("]")
                ? host.substring(1, host.length() - 1) : host;
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                || resolverHost == null || resolverHost.isEmpty() || uri.getRawUserInfo() != null
                || !isValidHost(resolverHost) || !isApprovedHost(resolverHost)) {
            throw new IOException("Feed host is not approved");
        }

        OkHttpClient client = createPinnedHttpClient(resolverHost, Dns.SYSTEM);
        Request request = new Request.Builder()
                .url(uri.toString())
                .header("User-Agent", "RollerPlanetAggregator")
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (response.body() == null) {
                throw new IOException("Feed response has no body");
            }
            try (XmlReader reader = new XmlReader(response.body().byteStream())) {
                return new SyndFeedInput().build(reader);
            }
        }
    }

    private static boolean isValidHost(String host) {
        if (host.startsWith("[") || host.endsWith("]") || host.endsWith(".")) {
            return false;
        }
        if (host.matches("[0-9.]+") || host.contains(":")) {
            try {
                return isPublicAddress(InetAddress.getByName(host));
            } catch (UnknownHostException ex) {
                return false;
            }
        }
        return host.matches("(?i)[a-z0-9](?:[a-z0-9.-]{0,251}[a-z0-9])?")
                && !host.contains("..") && !host.startsWith(".");
    }
    
}
