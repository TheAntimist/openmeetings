/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License") +  you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.openmeetings.service.calendar.caldav.methods;

import java.io.IOException;

import org.apache.http.HttpResponse;
import org.apache.http.StatusLine;
import org.apache.http.client.HttpClient;
import org.apache.http.entity.StringEntity;
import org.apache.jackrabbit.webdav.DavException;
import org.apache.jackrabbit.webdav.MultiStatus;
import org.apache.jackrabbit.webdav.MultiStatusResponse;
import org.apache.jackrabbit.webdav.property.DavPropertyName;
import org.apache.jackrabbit.webdav.property.DavPropertyNameSet;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;

import com.github.caldav4j.CalDAVConstants;
import com.github.caldav4j.util.CalDAVStatus;

public class TestSyncMethod {

	public static final String syncToken = "http://sabre.io/ns/sync/11";
	public static final int numResponses = 1;
	public static final String href = "/dav.php/calendars/testuser/test/1234567890.ics";


	@Test
	public void testInitialSync() throws IOException, DavException {
		HttpClient client = Mockito.mock(HttpClient.class);

		HttpResponse mockedResponse = Mockito.mock(HttpResponse.class);

		StatusLine mockedStatusline = Mockito.mock(StatusLine.class);
		Mockito.when(mockedResponse.getStatusLine()).thenReturn(mockedStatusline);

		Mockito.when(mockedStatusline.getStatusCode()).thenReturn(CalDAVStatus.SC_MULTI_STATUS);

		Mockito.when(mockedResponse.getEntity()).thenReturn(new StringEntity("<?xml version=\"1.0\"?>\n" +
				"<d:multistatus xmlns:d=\"DAV:\" xmlns:s=\"http://sabredav.org/ns\" xmlns:cal=\"urn:ietf:params:xml:ns:caldav\" xmlns:cs=\"http://calendarserver.org/ns/\">\n" +
				" <d:response>\n" +
				"  <d:href>/dav.php/calendars/testuser/test/1234567890.ics</d:href>\n" +
				"  <d:propstat>\n" +
				"   <d:prop>\n" +
				"    <d:getetag>&quot;1234567890&quot;</d:getetag>\n" +
				"<cal:calendar-data>\n" +
				"BEGIN:VCALENDAR\n" +
				"PRODID:-//Events Calendar//Apache Openmeetings//EN\n" +
				"VERSION:2.0\n" +
				"CALSCALE:GREGORIAN\n" +
				"BEGIN:VTIMEZONE\n" +
				"TZID:Asia/Kolkata\n" +
				"TZURL:http://tzurl.org/zoneinfo/Asia/Kolkata\n" +
				"X-LIC-LOCATION:Asia/Kolkata\n" +
				"BEGIN:STANDARD\n" +
				"TZOFFSETFROM:+055328\n" +
				"TZOFFSETTO:+055320\n" +
				"TZNAME:HMT\n" +
				"DTSTART:18540628T000000\n" +
				"RDATE:18540628T000000\n" +
				"END:STANDARD\n" +
				"BEGIN:STANDARD\n" +
				"TZOFFSETFROM:+055320\n" +
				"TZOFFSETTO:+052110\n" +
				"TZNAME:MMT\n" +
				"DTSTART:18700101T000000\n" +
				"RDATE:18700101T000000\n" +
				"END:STANDARD\n" +
				"BEGIN:STANDARD\n" +
				"TZOFFSETFROM:+052110\n" +
				"TZOFFSETTO:+0530\n" +
				"TZNAME:IST\n" +
				"DTSTART:19060101T000850\n" +
				"RDATE:19060101T000850\n" +
				"END:STANDARD\n" +
				"BEGIN:DAYLIGHT\n" +
				"TZOFFSETFROM:+0530\n" +
				"TZOFFSETTO:+0630\n" +
				"TZNAME:+0630\n" +
				"DTSTART:19411001T000000\n" +
				"RDATE:19411001T000000\n" +
				"RDATE:19420901T000000\n" +
				"END:DAYLIGHT\n" +
				"BEGIN:STANDARD\n" +
				"TZOFFSETFROM:+0630\n" +
				"TZOFFSETTO:+0530\n" +
				"TZNAME:IST\n" +
				"DTSTART:19420515T000000\n" +
				"RDATE:19420515T000000\n" +
				"RDATE:19451015T000000\n" +
				"END:STANDARD\n" +
				"END:VTIMEZONE\n" +
				"BEGIN:VEVENT\n" +
				"DTSTAMP:20181108T122155Z\n" +
				"DTSTART:20181108T175155\n" +
				"DTEND:20181109T175155\n" +
				"SUMMARY:TEST EVENT\n" +
				"LOCATION:Office\n" +
				"DESCRIPTION:TEST DESCRIPTION\n" +
				"SEQUENCE:0\n" +
				"TRANSP:OPAQUE\n" +
				"ATTENDEE;ROLE=REQ-PARTICIPANT;CN=John Doe:mailto:doe@email.com\n" +
				"ATTENDEE;ROLE=CHAIR;CN=Owner John:mailto:owner@email.com\n" +
				"ORGANIZER;CN=Owner John:mailto:owner@email.com\n" +
				"DTSTAMP:20181108T122155Z\n" +
				"END:VEVENT\n" +
				"END:VCALENDAR\n" +
				"</cal:calendar-data>\n" +
				"   </d:prop>\n" +
				"   <d:status>HTTP/1.1 200 OK</d:status>\n" +
				"  </d:propstat>\n" +
				" </d:response>\n" +
				" <d:sync-token>http://sabre.io/ns/sync/11</d:sync-token>\n" +
				"</d:multistatus>\n"));

		DavPropertyNameSet properties = new DavPropertyNameSet();
		properties.add(DavPropertyName.GETETAG);
		properties.add(CalDAVConstants.DNAME_CALENDAR_DATA);

		SyncReportInfo reportInfo = new SyncReportInfo(null, properties, SyncReportInfo.SYNC_LEVEL_1);
		SyncMethod method = new SyncMethod("http://path/to/my/resource", reportInfo);

		Mockito.when(client.execute(method)).thenReturn(mockedResponse);

		HttpResponse gotResponse = client.execute(method);
		String token = method.getResponseSynctoken(gotResponse);
		Assert.assertEquals(syncToken, token);

		MultiStatus status = method.getResponseBodyAsMultiStatus(mockedResponse);

		MultiStatusResponse[] multiStatusResponses = status.getResponses();
		Assert.assertEquals(numResponses, multiStatusResponses.length);

		for(MultiStatusResponse resp : multiStatusResponses) {
			Assert.assertEquals(href, resp.getHref());
		}
	}
}
