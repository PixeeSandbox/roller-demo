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

import org.apache.roller.planet.business.fetcher.FetcherException;
import org.apache.roller.planet.business.fetcher.RomeFeedFetcher;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Test utilities.
 */
public class UtilitiesTest  {

    @Test
    public void testExtractHTML() {
        String test = "<a>keep me</a>";
        String expect = "<a></a>";
        String result = Utilities.extractHTML(test);
        assertEquals(expect, result);
    }

    @Test
    public void testRemoveHTML() {
        String test = "<br><br><p>a <b>bold</b> sentence with a <a href=\"http://example.com\">link</a></p>";
        String expect = "a bold sentence with a link";
        String result = Utilities.removeHTML(test, false);
        assertEquals(expect, result);
    }

    @Test
    public void testTruncateNicely1() {
        String test = "blah blah blah blah blah";
        String expect = "blah blah blah";
        String result = Utilities.truncateNicely(test, 11, 15, "");
        assertEquals(expect, result);
    }

    @Test
    public void testTruncateNicely2() {
        String test = "<p><b>blah1 blah2</b> <i>blah3 blah4 blah5</i></p>";
        String expect = "<p><b>blah1 blah2</b> <i>blah3</i></p>";
        String result = Utilities.truncateNicely(test, 15, 20, "");
        //System.out.println(result);
        assertEquals(expect, result);
    }
    
    @Test
    public void testFeedFetcherRejectsUnconfiguredAndDisallowedHosts() {
        assertThrows(IllegalArgumentException.class,
                () -> RomeFeedFetcher.validateFeedUri("https://feeds.example.org/rss", ""));
        assertThrows(IllegalArgumentException.class,
                () -> RomeFeedFetcher.validateFeedUri("https://other.example.org/rss", "feeds.example.org"));
    }

    @Test
    public void testFeedFetcherRejectsMalformedAndUnsupportedDestinations() {
        assertThrows(IllegalArgumentException.class,
                () -> RomeFeedFetcher.validateFeedUri("file:///etc/passwd", "feeds.example.org"));
        assertThrows(IllegalArgumentException.class,
                () -> RomeFeedFetcher.validateFeedUri("http://user@feeds.example.org/rss", "feeds.example.org"));
        assertThrows(IllegalArgumentException.class,
                () -> RomeFeedFetcher.validateFeedUri("http://feeds.example.org:8080/rss", "feeds.example.org"));
        assertThrows(IllegalArgumentException.class,
                () -> RomeFeedFetcher.validateFeedUri("http://feeds.example.org:abc/rss", "feeds.example.org"));
        assertThrows(IllegalArgumentException.class,
                () -> RomeFeedFetcher.validateFeedUri("http://127.0.0.1/rss", "127.0.0.1"));
    }

    @Test
    public void testFeedFetcherDoesNotFetchWithoutConfiguredProxy() {
        RomeFeedFetcher fetcher = new RomeFeedFetcher("", "feeds.example.org");
        assertThrows(FetcherException.class,
                () -> fetcher.fetchSubscription("http://feeds.example.org/rss"));
    }

    @Test
    public void testFeedFetcherDoesNotBypassUnavailableProxy() throws IOException {
        int unavailablePort;
        try (ServerSocket socket = new ServerSocket(0)) {
            unavailablePort = socket.getLocalPort();
        }
        RomeFeedFetcher fetcher = new RomeFeedFetcher("http://127.0.0.1:" + unavailablePort,
                "feeds.example.org");
        assertThrows(FetcherException.class,
                () -> fetcher.fetchSubscription("http://feeds.example.org/rss"));
    }

    /* broken because it uses UtilitiesModel which is part of .ui.* package
    @Test
    public void testAddNoFollow() {
        String test1 = "<p>this some text with a <a href=\"http://example.com\">link</a>";
        String expect1 = "<p>this some text with a <a href=\"http://example.com\" rel=\"nofollow\">link</a>";
        String result1 = UtilitiesModel.addNofollow(test1);
        assertEquals(expect1, result1);
     
        String test2 = "<p>this some text with a <A href=\"http://example.com\">link</a>";
        String expect2 = "<p>this some text with a <A href=\"http://example.com\" rel=\"nofollow\">link</a>";
        String result2 = UtilitiesModel.addNofollow(test2);
        assertEquals(expect2, result2);
     
    }
     */

}
