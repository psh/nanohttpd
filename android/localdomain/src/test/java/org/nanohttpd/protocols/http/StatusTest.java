package org.nanohttpd.protocols.http;

import java.util.HashMap;
import java.util.Map;

/*
 * #%L
 * NanoHttpd-Core
 * %%
 * Copyright (C) 2012 - 2015 nanohttpd
 * %%
 * Redistribution and use in source and binary forms, with or without modification,
 * are permitted provided that the following conditions are met:
 * 
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 * 
 * 3. Neither the name of the nanohttpd nor the names of its contributors
 *    may be used to endorse or promote products derived from this software without
 *    specific prior written permission.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 * IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT,
 * INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING,
 * BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 * LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
 * OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED
 * OF THE POSSIBILITY OF SUCH DAMAGE.
 * #L%
 */

import org.junit.Assert;
import org.junit.Test;
import org.nanohttpd.protocols.http.response.Status;

import static org.junit.Assert.assertEquals;

public class StatusTest {

    @Test
    public void testMessages() {
        // These are values where the name of the enum does not match the status
        // code description.
        // By default you should not need to add any new values to this map if
        // you
        // make the name of the enum name match the status code description.
        Map<Status, String> overrideValues = new HashMap<>();
        overrideValues.put(Status.INTERNAL_ERROR, "500 Internal Server Error");
        overrideValues.put(Status.SWITCH_PROTOCOL, "101 Switching Protocols");
        overrideValues.put(Status.OK, "200 OK");
        overrideValues.put(Status.MULTI_STATUS, "207 Multi-Status");
        overrideValues.put(Status.REDIRECT, "301 Moved Permanently");
        overrideValues.put(Status.REDIRECT_SEE_OTHER, "303 See Other");
        overrideValues.put(Status.RANGE_NOT_SATISFIABLE, "416 Requested Range Not Satisfiable");
        overrideValues.put(Status.UNSUPPORTED_HTTP_VERSION, "505 HTTP Version Not Supported");

        for (Status status : Status.values()) {
            if (overrideValues.containsKey(status)) {
                assertEquals(overrideValues.get(status), status.getDescription());
            } else {
                assertEquals(getExpectedMessage(status), status.getDescription());
            }
        }
    }

    private String getExpectedMessage(Status status) {
        String name = status.name().toLowerCase();
        String[] words = name.split("_");
        StringBuilder builder = new StringBuilder();
        builder.append(status.getRequestStatus());
        builder.append(' ');

        for (int i = 0; i < words.length; i++) {
            builder.append(Character.toUpperCase(words[i].charAt(0)));
            builder.append(words[i].substring(1));
            builder.append(' ');
        }

        return builder.toString().trim();
    }

    @Test
    public void testLookup() throws Exception {
        assertEquals(Status.SWITCH_PROTOCOL, Status.lookup(101));

        assertEquals(Status.OK, Status.lookup(200));
        assertEquals(Status.CREATED, Status.lookup(201));
        assertEquals(Status.ACCEPTED, Status.lookup(202));
        assertEquals(Status.NO_CONTENT, Status.lookup(204));
        assertEquals(Status.PARTIAL_CONTENT, Status.lookup(206));
        assertEquals(Status.MULTI_STATUS, Status.lookup(207));

        assertEquals(Status.REDIRECT, Status.lookup(301));
        assertEquals(Status.FOUND, Status.lookup(302));
        assertEquals(Status.REDIRECT_SEE_OTHER, Status.lookup(303));
        assertEquals(Status.NOT_MODIFIED, Status.lookup(304));
        assertEquals(Status.TEMPORARY_REDIRECT, Status.lookup(307));

        assertEquals(Status.BAD_REQUEST, Status.lookup(400));
        assertEquals(Status.UNAUTHORIZED, Status.lookup(401));
        assertEquals(Status.FORBIDDEN, Status.lookup(403));
        assertEquals(Status.NOT_FOUND, Status.lookup(404));
        assertEquals(Status.METHOD_NOT_ALLOWED, Status.lookup(405));
        assertEquals(Status.NOT_ACCEPTABLE, Status.lookup(406));
        assertEquals(Status.REQUEST_TIMEOUT, Status.lookup(408));
        assertEquals(Status.CONFLICT, Status.lookup(409));
        assertEquals(Status.GONE, Status.lookup(410));
        assertEquals(Status.LENGTH_REQUIRED, Status.lookup(411));
        assertEquals(Status.PRECONDITION_FAILED, Status.lookup(412));
        assertEquals(Status.PAYLOAD_TOO_LARGE, Status.lookup(413));
        assertEquals(Status.UNSUPPORTED_MEDIA_TYPE, Status.lookup(415));
        assertEquals(Status.RANGE_NOT_SATISFIABLE, Status.lookup(416));
        assertEquals(Status.EXPECTATION_FAILED, Status.lookup(417));
        assertEquals(Status.TOO_MANY_REQUESTS, Status.lookup(429));
        assertEquals(Status.INTERNAL_ERROR, Status.lookup(500));
        assertEquals(Status.NOT_IMPLEMENTED, Status.lookup(501));
        assertEquals(Status.SERVICE_UNAVAILABLE, Status.lookup(503));
        assertEquals(Status.UNSUPPORTED_HTTP_VERSION, Status.lookup(505));
    }
}
