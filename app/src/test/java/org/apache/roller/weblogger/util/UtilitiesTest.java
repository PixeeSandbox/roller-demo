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

import okhttp3.Dns;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.apache.roller.planet.business.fetcher.RomeFeedFetcher;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    public void testPinnedResolverUsesOnlyValidatedAddresses() throws Exception {
        AtomicInteger lookups = new AtomicInteger();
        InetAddress publicAddress = InetAddress.getByAddress(new byte[]{(byte) 93, (byte) 184, (byte) 216, (byte) 34});
        InetAddress privateAddress = InetAddress.getByAddress(new byte[]{127, 0, 0, 1});
        Dns changingResolver = host -> {
            if (lookups.incrementAndGet() == 1) {
                return List.of(publicAddress);
            }
            return List.of(privateAddress);
        };

        OkHttpClient client = RomeFeedFetcher.createPinnedHttpClient("rebinding.example", changingResolver);
        assertEquals(List.of(publicAddress), client.dns().lookup("rebinding.example"));
        assertEquals(List.of(publicAddress), client.dns().lookup("rebinding.example"));
        assertEquals(1, lookups.get());
    }

    @Test
    public void testPinnedResolverRejectsDisallowedAddresses() {
        Dns privateResolver = host -> List.of(InetAddress.getLoopbackAddress());
        assertThrows(UnknownHostException.class,
                () -> RomeFeedFetcher.createPinnedHttpClient("localhost", privateResolver));
    }

    @Test
    public void testFeedClientDoesNotFollowRedirects() throws Exception {
        Dns resolver = host -> List.of(InetAddress.getByAddress(
                new byte[]{(byte) 93, (byte) 184, (byte) 216, (byte) 34}));
        AtomicInteger requests = new AtomicInteger();
        OkHttpClient client = RomeFeedFetcher.createPinnedHttpClient("redirect.example", resolver)
                .newBuilder()
                .addInterceptor(chain -> {
                    requests.incrementAndGet();
                    return new Response.Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(302)
                            .message("Found")
                            .header("Location", "http://target.example/feed")
                            .body(ResponseBody.create(null, ""))
                            .build();
                })
                .build();

        try (Response response = client.newCall(new Request.Builder()
                .url("http://redirect.example/feed").build()).execute()) {
            assertEquals(302, response.code());
        }
        assertEquals(1, requests.get());
        assertFalse(client.followRedirects());
        assertFalse(client.followSslRedirects());
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
