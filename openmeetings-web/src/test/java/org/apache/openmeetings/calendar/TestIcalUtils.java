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
package org.apache.openmeetings.calendar;

import java.net.URI;
import java.util.Date;

import org.apache.openmeetings.AbstractJUnitDefaults;
import org.apache.openmeetings.db.dao.user.UserDao;
import org.apache.openmeetings.db.entity.calendar.Appointment;
import org.apache.openmeetings.db.entity.calendar.OmCalendar;
import org.apache.openmeetings.db.entity.user.User;
import org.apache.openmeetings.service.calendar.caldav.IcalUtils;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;

import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.Component;
import net.fortuna.ical4j.model.DateTime;
import net.fortuna.ical4j.model.TimeZone;
import net.fortuna.ical4j.model.TimeZoneRegistry;
import net.fortuna.ical4j.model.TimeZoneRegistryFactory;
import net.fortuna.ical4j.model.component.VEvent;
import net.fortuna.ical4j.model.parameter.Cn;
import net.fortuna.ical4j.model.parameter.Role;
import net.fortuna.ical4j.model.property.*;
import net.fortuna.ical4j.util.MapTimeZoneCache;

public class TestIcalUtils extends AbstractJUnitDefaults {

	static {
		System.setProperty("net.fortuna.ical4j.timezone.cache.impl", MapTimeZoneCache.class.getName());
		System.setProperty("net.fortuna.ical4j.timezone.update.enabled", "false");
	}

	@Autowired
	UserDao userDao;
	@Autowired
	IcalUtils icalUtils;

	// Test vars
	public OmCalendar omCalendar;
	public Calendar iCal;
	public static final String etag = "'RANDOM-ETAG'";
	public static final String href = "/path/to/ical.ics";
	public static final String title = "TEST EVENT";
	public static final String description = "TEST DESCRIPTION";
	public static final String location = "OFFICE";
	public static final String uid = "1234567890";

	@Before
	public void setUp() {
		if(omCalendar == null) {
			omCalendar = new OmCalendar();
			Long userId = 1L;
			User owner = userDao.get(userId);
			String title = "Calendar Title", href = "http://caldav.example.com/principals/user";

			omCalendar.setOwner(owner);
			omCalendar.setTitle(title);
			omCalendar.setHref(href);
			omCalendar.setSyncType(OmCalendar.SyncType.ETAG);
		}

		if(iCal == null) {
			Date start = new Date();

			java.util.Calendar temp = java.util.Calendar.getInstance();
			temp.setTime(start);
			temp.add(java.util.Calendar.DATE, 1);

			Date end = temp.getTime();
			iCal = generateCalendar(start, end, uid);
		}
	}

	@Test
	public void testiCalToAppt() {
		Appointment a = icalUtils.parseCalendartoAppointment(iCal, href, etag, omCalendar);

		Assert.assertEquals(etag, a.getEtag());
		Assert.assertEquals(href, a.getHref());
		Assert.assertEquals(title, a.getTitle());
		Assert.assertEquals(location, a.getLocation());
	}

	@Test
	public void testApptToIcal() {

		Date start = new Date();

		java.util.Calendar temp = java.util.Calendar.getInstance();
		temp.setTime(start);
		temp.add(java.util.Calendar.DATE, 1);

		Date end = temp.getTime();

		Appointment a = new Appointment();

		a.setOwner(omCalendar.getOwner());
		a.setCalendar(omCalendar);
		a.setTitle(title);
		a.setHref(href);
		a.setDescription(description);
		a.setEtag(etag);
		a.setStart(start);
		a.setEnd(end);
		a.setDeleted(false);
		a.setIsDaily(false);
		a.setIsWeekly(false);
		a.setIsMonthly(false);
		a.setIsYearly(false);
		a.setPasswordProtected(false);
		a.setIcalId(uid);

		a.setConnectedEvent(false);

		if (a.getReminder() == null) {
			a.setReminder(Appointment.Reminder.none);
		}

		Calendar genCal = icalUtils.parseAppointmenttoCalendar(a);

		VEvent genEvent = (VEvent)genCal.getComponent(Component.VEVENT);

		Assert.assertEquals(description, genEvent.getDescription().getValue());
		Assert.assertEquals(title, genEvent.getSummary().getValue());

		Organizer organizer = genEvent.getOrganizer();

		URI uri = URI.create(organizer.getValue());
		String email = uri.getSchemeSpecificPart();
		Assert.assertEquals(a.getOwner().getAddress().getEmail(), email);

		Uid genEventUid = genEvent.getUid();

		Assert.assertEquals(uid, genEventUid.getValue());
	}

	/**
	 * Generates a omCalendar to test.
	 * @return Generated iCalendar
	 */
	private static Calendar generateCalendar(Date start, Date end, String uid) {

		Calendar calendar = new Calendar();

		calendar.getProperties().add(new ProdId(IcalUtils.PROD_ID));
		calendar.getProperties().add(Version.VERSION_2_0);
		calendar.getProperties().add(CalScale.GREGORIAN);

		// Insert TimeZone

		String tzid = timeZone;

		TimeZoneRegistry registry = TimeZoneRegistryFactory.getInstance().createRegistry();
		TimeZone timeZone = registry.getTimeZone(tzid);

		calendar.getComponents().add(timeZone.getVTimeZone());

		// Start and end times. 1 day apart
		DateTime startDt = new DateTime(start);

		DateTime endDt = new DateTime(end);

		// Create the actual Event
		VEvent meeting = new VEvent(startDt, endDt, title);

		// Basic Metadata
		meeting.getProperties().add(new Location(location));
		meeting.getProperties().add(new Description(description));
		meeting.getProperties().add(new Sequence(0));
		meeting.getProperties().add(Transp.OPAQUE);

		// Generate the Uid
		meeting.getProperties().add(new Uid(uid));

		// Create John Does as a Participant
		Attendee attendee = new Attendee(URI.create("mailto:doe@email.com"));
		attendee.getParameters().add(Role.REQ_PARTICIPANT);
		attendee.getParameters().add(new Cn("John Doe"));
		meeting.getProperties().add(attendee);

		// Create Owner John as the Organizer and Chair
		URI orgUri = URI.create(email);
		Attendee orgAtt = new Attendee(orgUri);
		orgAtt.getParameters().add(Role.CHAIR);
		Cn orgCn = new Cn("Owner John");

		orgAtt.getParameters().add(orgCn);
		meeting.getProperties().add(orgAtt);

		Organizer organizer = new Organizer(orgUri);
		organizer.getParameters().add(orgCn);
		meeting.getProperties().add(organizer);

		// Add the meeting to the omCalendar.
		calendar.getComponents().add(meeting);

		return calendar;
	}

}
