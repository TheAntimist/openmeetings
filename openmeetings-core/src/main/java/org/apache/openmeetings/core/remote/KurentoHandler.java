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
package org.apache.openmeetings.core.remote;

import static java.util.UUID.randomUUID;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.stream.Collectors;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.apache.directory.api.util.Strings;
import org.apache.openmeetings.core.converter.IRecordingConverter;
import org.apache.openmeetings.core.converter.InterviewConverter;
import org.apache.openmeetings.core.converter.RecordingConverter;
import org.apache.openmeetings.core.util.WebSocketHelper;
import org.apache.openmeetings.db.dao.record.RecordingChunkDao;
import org.apache.openmeetings.db.dao.record.RecordingDao;
import org.apache.openmeetings.db.entity.basic.Client;
import org.apache.openmeetings.db.entity.basic.Client.Activity;
import org.apache.openmeetings.db.entity.basic.Client.StreamDesc;
import org.apache.openmeetings.db.entity.basic.Client.StreamType;
import org.apache.openmeetings.db.entity.basic.IWsClient;
import org.apache.openmeetings.db.entity.record.Recording;
import org.apache.openmeetings.db.entity.room.Room;
import org.apache.openmeetings.db.entity.room.Room.Right;
import org.apache.openmeetings.db.entity.room.Room.RoomElement;
import org.apache.openmeetings.db.manager.IClientManager;
import org.apache.openmeetings.db.util.ws.RoomMessage;
import org.apache.openmeetings.db.util.ws.TextRoomMessage;
import org.kurento.client.Endpoint;
import org.kurento.client.EventListener;
import org.kurento.client.IceCandidate;
import org.kurento.client.KurentoClient;
import org.kurento.client.KurentoConnectionListener;
import org.kurento.client.MediaObject;
import org.kurento.client.MediaPipeline;
import org.kurento.client.ObjectCreatedEvent;
import org.kurento.client.PlayerEndpoint;
import org.kurento.client.RecorderEndpoint;
import org.kurento.client.Tag;
import org.kurento.client.Transaction;
import org.kurento.client.WebRtcEndpoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.task.TaskExecutor;

import com.github.openjson.JSONArray;
import com.github.openjson.JSONObject;

public class KurentoHandler {
	private static final Logger log = LoggerFactory.getLogger(KurentoHandler.class);
	public static final String PARAM_ICE = "iceServers";
	public static final String PARAM_CANDIDATE = "candidate";
	private static final String WARN_NO_KURENTO = "Media Server is not accessible";
	private static final String MODE_TEST = "test";
	private static final String TAG_KUID = "kuid";
	private static final String TAG_MODE = "mode";
	private static final String TAG_ROOM = "roomId";
	private static final String HMAC_SHA1_ALGORITHM = "HmacSHA1";
	private final ScheduledExecutorService recheckScheduler = Executors.newScheduledThreadPool(1);
	public static final String KURENTO_TYPE = "kurento";
	private long checkTimeout = 120000; //ms
	private long objCheckTimeout = 200; //ms
	private int watchThreadCount = 10;
	private String kurentoWsUrl;
	private String turnUrl;
	private String turnUser;
	private String turnSecret;
	private String turnMode;
	private int turnTtl = 60; //minutes
	private KurentoClient client;
	private String kuid;
	private final Map<Long, KRoom> rooms = new ConcurrentHashMap<>();
	final Map<String, KStream> streamsByUid = new ConcurrentHashMap<>();
	final Map<String, KTestStream> testsByUid = new ConcurrentHashMap<>();
	private Runnable check = null;

	@Autowired
	private IClientManager cm;
	@Autowired
	private RecordingDao recDao;
	@Autowired
	private RecordingChunkDao chunkDao;
	@Autowired
	private TaskExecutor taskExecutor;
	@Autowired
	private RecordingConverter recordingConverter;
	@Autowired
	private InterviewConverter interviewConverter;

	public void init() {
		check = () -> {
			try {
				client = KurentoClient.create(kurentoWsUrl, new KConnectionListener());
				kuid = randomUUID().toString();
				client.getServerManager().addObjectCreatedListener(new KWatchDog());
			} catch (Exception e) {
				log.warn("Fail to create Kurento client, will re-try in {} ms", checkTimeout);
				recheckScheduler.schedule(check, checkTimeout, MILLISECONDS);
			}
		};
		recheckScheduler.schedule(check, 50, MILLISECONDS);
	}

	public void destroy() {
		if (client != null) {
			for (Entry<Long, KRoom> e : rooms.entrySet()) {
				e.getValue().close(this);
			}
			rooms.clear();
			for (Entry<String, KTestStream> e : testsByUid.entrySet()) {
				e.getValue().release(this);
			}
			testsByUid.clear();
			streamsByUid.clear();
			client.destroy();
		}
	}

	private static Map<String, String> tagsAsMap(MediaObject pipe) {
		Map<String, String> map = new HashMap<>();
		for (Tag t : pipe.getTags()) {
			map.put(t.getKey(), t.getValue());
		}
		return map;
	}

	Transaction beginTransaction() {
		return client.beginTransaction();
	}

	private MediaPipeline createTestPipeline() {
		Transaction t = beginTransaction();
		MediaPipeline pipe = client.createMediaPipeline(t);
		pipe.addTag(t, TAG_KUID, kuid);
		pipe.addTag(t, TAG_MODE, MODE_TEST);
		pipe.addTag(t, TAG_ROOM, MODE_TEST);
		t.commit();
		return pipe;
	}

	private void onTestMessage(IWsClient _c, final String cmdId, JSONObject msg) {
		KTestStream user = getTestByUid(_c.getUid());
		switch (cmdId) {
			case "wannaRecord":
				WebSocketHelper.sendClient(_c, newTestKurentoMsg()
						.put("id", "canRecord")
						.put(PARAM_ICE, getTurnServers(true))
						);
				break;
			case "record":
				if (user != null) {
					user.release(this);
				}
				user = new KTestStream(_c, msg, createTestPipeline());
				testsByUid.put(_c.getUid(), user);
				break;
			case "iceCandidate":
				JSONObject candidate = msg.getJSONObject(PARAM_CANDIDATE);
				if (user != null) {
					IceCandidate cand = new IceCandidate(candidate.getString(PARAM_CANDIDATE),
							candidate.getString("sdpMid"), candidate.getInt("sdpMLineIndex"));
					user.addCandidate(cand);
				}
				break;
			case "wannaPlay":
				WebSocketHelper.sendClient(_c, newTestKurentoMsg()
						.put("id", "canPlay")
						.put(PARAM_ICE, getTurnServers(true))
						);
				break;
			case "play":
				if (user != null) {
					user.play(_c, msg, createTestPipeline());
				}
				break;
		}
	}

	private void onMessage(Client c, final String cmdId, JSONObject msg) {
		final String uid = msg.optString("uid");
		KStream sender;
		StreamDesc sd;
		log.debug("Incoming message from user with ID '{}': {}", c.getUserId(), msg);
		switch (cmdId) {
			case "devicesAltered":
				if (!msg.getBoolean("audio") && c.hasActivity(Activity.AUDIO)) {
					c.remove(Activity.AUDIO);
				}
				if (!msg.getBoolean("video") && c.hasActivity(Activity.VIDEO)) {
					c.remove(Activity.VIDEO);
				}
				c.getStream(uid).setActivities();
				WebSocketHelper.sendRoom(new TextRoomMessage(c.getRoomId(), cm.update(c), RoomMessage.Type.rightUpdated, c.getUid()));
				break;
			case "toggleActivity":
				toggleActivity(c, Activity.valueOf(msg.getString("activity")));
				break;
			case "broadcastStarted":
				sd = c.getStream(uid);
				sender = getByUid(uid);
				if (sender == null) {
					KRoom room = getRoom(c.getRoomId());
					sender = room.join(sd);
				}
				sender.startBroadcast(this, sd, msg.getString("sdpOffer"));
				if (StreamType.SCREEN == sd.getType() && sd.hasActivity(Activity.RECORD) && !isRecording(c.getRoomId())) {
					startRecording(c);
				}
				break;
			case "onIceCandidate":
				sender = getByUid(uid);
				if (sender != null) {
					JSONObject candidate = msg.getJSONObject(PARAM_CANDIDATE);
					IceCandidate cand = new IceCandidate(
							candidate.getString(PARAM_CANDIDATE)
							, candidate.getString("sdpMid")
							, candidate.getInt("sdpMLineIndex"));
					sender.addCandidate(cand, msg.getString("luid"));
				}
				break;
			case "addListener":
				sender = getByUid(msg.getString("sender"));
				if (sender != null) {
					sender.addListener(this, c.getSid(), c.getUid(), msg.getString("sdpOffer"));
				}
				break;
			case "wannaShare":
				if (screenShareAllowed(c)) {
					startSharing(c, msg, Activity.SCREEN);
				}
				break;
			case "wannaRecord":
				if (recordingAllowed(c)) {
					Room r = c.getRoom();
					if (Room.Type.interview == r.getType()) {
						log.warn("This shouldn't be called for interview room");
						break;
					}
					if (isSharing(r.getId())) {
						startRecording(c);
					} else {
						startSharing(c, msg, Activity.RECORD);
					}
				}
				break;
			case "stopSharing":
				sender = getByUid(uid);
				sd = stopSharing(c.getSid(), uid);
				if (sender != null && sd != null) {
					sender.stopBroadcast(this);
				}
				break;
		}
	}

	public void onMessage(IWsClient _c, JSONObject msg) {
		if (client == null) {
			sendError(_c, "Multimedia server is inaccessible");
			return;
		}
		final String cmdId = msg.getString("id");
		if (MODE_TEST.equals(msg.optString(TAG_MODE))) {
			onTestMessage(_c, cmdId, msg);
		} else {
			final Client c = (Client)_c;

			if (c == null || c.getRoomId() == null) {
				log.warn("Incoming message from invalid user");
				return;
			}
			onMessage(c, cmdId, msg);
		}
	}

	private static boolean isBroadcasting(final Client c) {
		return c.hasAnyActivity(Activity.AUDIO, Activity.VIDEO);
	}

	private void checkStreams(Long roomId) {
		if (client == null) {
			log.warn(WARN_NO_KURENTO);
			return;
		}
		KRoom room = getRoom(roomId);
		if (room.isRecording()) {
			List<StreamDesc> streams = cm.listByRoom(roomId).parallelStream()
					.flatMap(c -> c.getStreams().stream())
					.collect(Collectors.toList());
			if (streams.isEmpty()) {
				log.info("No more streams in the room, stopping recording");
				room.stopRecording(this, null, recDao);
			}
		}
		if (room.isSharing()) {
			List<StreamDesc> streams = cm.listByRoom(roomId).parallelStream()
					.flatMap(c -> c.getStreams().stream())
					.filter(sd -> StreamType.SCREEN == sd.getType()).collect(Collectors.toList());
			if (streams.isEmpty()) {
				log.info("No more screen streams in the room, stopping sharing");
				room.stopSharing();
			}
		}
	}

	public void toggleActivity(Client c, Activity a) {
		log.info("PARTICIPANT {}: trying to toggle activity {}", c, c.getRoomId());

		if (!activityAllowed(c, a, c.getRoom())) {
			if (a == Activity.AUDIO || a == Activity.AUDIO_VIDEO) {
				c.allow(Room.Right.audio);
			}
			if (!c.getRoom().isAudioOnly() && (a == Activity.VIDEO || a == Activity.AUDIO_VIDEO)) {
				c.allow(Room.Right.video);
			}
		}
		if (activityAllowed(c, a, c.getRoom())) {
			boolean wasBroadcasting = isBroadcasting(c);
			if (a == Activity.AUDIO && !c.isMicEnabled()) {
				return;
			}
			if (a == Activity.VIDEO && !c.isCamEnabled()) {
				return;
			}
			if (a == Activity.AUDIO_VIDEO && !c.isMicEnabled() && !c.isCamEnabled()) {
				return;
			}
			c.toggle(a);
			if (!isBroadcasting(c)) {
				//close
				boolean changed = false;
				for (StreamDesc sd : c.getStreams()) {
					KStream s = getByUid(sd.getUid());
					if (StreamType.WEBCAM == sd.getType()) {
						if (s != null) {
							s.stopBroadcast(this);
						}
						c.removeStream(sd.getUid());
						changed = true;
					}
				}
				if (changed) {
					cm.update(c);
					checkStreams(c.getRoomId());
				}
				WebSocketHelper.sendRoom(new TextRoomMessage(c.getRoomId(), c, RoomMessage.Type.rightUpdated, c.getUid()));
				//FIXME TODO update interview buttons
			} else if (!wasBroadcasting) {
				//join
				StreamDesc sd = c.addStream(StreamType.WEBCAM);
				cm.update(c);
				log.debug("User {}: has started broadcast", sd.getUid());
				sendClient(sd.getSid(), newKurentoMsg()
						.put("id", "broadcast")
						.put("stream", sd.toJson())
						.put(PARAM_ICE, getTurnServers(false)));
				//FIXME TODO update interview buttons
			} else {
				//constraints were changed
				for (StreamDesc sd : c.getStreams()) {
					if (StreamType.WEBCAM == sd.getType()) {
						sd.setActivities();
						cm.update(c);
						break;
					}
				}
				WebSocketHelper.sendRoom(new TextRoomMessage(c.getRoomId(), c, RoomMessage.Type.rightUpdated, c.getUid()));
			}
		}
	}

	public boolean recordingAllowed(Client c) {
		if (client == null) {
			log.warn(WARN_NO_KURENTO);
			return false;
		}
		Room r = c.getRoom();
		return r != null && r.isAllowRecording() && c.hasRight(Right.moderator)
				&& !isRecording(r.getId());
	}

	public void startRecording(Client c) {
		if (client == null) {
			log.warn(WARN_NO_KURENTO);
			return;
		}
		getRoom(c.getRoomId()).startRecording(c, recDao);
	}

	public void stopRecording(Client c) {
		if (client == null) {
			log.warn(WARN_NO_KURENTO);
			return;
		}
		getRoom(c.getRoomId()).stopRecording(this, c, recDao);
	}

	void startConvertion(Recording rec) {
		IRecordingConverter conv = rec.isInterview() ? interviewConverter : recordingConverter;
		taskExecutor.execute(() -> conv.startConversion(rec));
	}

	public boolean isRecording(Long roomId) {
		if (client == null) {
			log.warn(WARN_NO_KURENTO);
			return false;
		}
		return getRoom(roomId).isRecording();
	}

	public JSONObject getRecordingUser(Long roomId) {
		if (client == null) {
			log.warn(WARN_NO_KURENTO);
			return new JSONObject();
		}
		return getRoom(roomId).getRecordingUser();
	}

	public boolean screenShareAllowed(Client c) {
		if (client == null) {
			log.warn(WARN_NO_KURENTO);
			return false;
		}
		Room r = c.getRoom();
		return r != null && Room.Type.interview != r.getType()
				&& !r.isHidden(RoomElement.ScreenSharing)
				&& r.isAllowRecording() && c.hasRight(Right.share)
				&& !isSharing(r.getId());
	}

	private void startSharing(Client c, JSONObject msg, Activity...activities) {
		if (client != null && c.getRoomId() != null) {
			getRoom(c.getRoomId()).startSharing(this, cm, c, msg, activities);
		}
	}

	StreamDesc stopSharing(String sid, String uid) {
		StreamDesc sd = null;
		Client c = getBySid(sid);
		if (c.getRoomId() != null) {
			sd = c.getStream(uid);
			if (sd != null && StreamType.SCREEN == sd.getType()) {
				c.removeStream(uid);
				cm.update(c);
				checkStreams(c.getRoomId());
				WebSocketHelper.sendRoom(new TextRoomMessage(c.getRoomId(), c, RoomMessage.Type.rightUpdated, c.getUid()));
			}
		}
		return sd;
	}

	public boolean isSharing(Long roomId) {
		if (client == null) {
			log.warn(WARN_NO_KURENTO);
			return false;
		}
		return getRoom(roomId).isSharing();
	}

	public void leaveRoom(Client c) {
		remove(c);
		WebSocketHelper.sendAll(newKurentoMsg()
				.put("id", "clientLeave")
				.put("uid", c.getUid())
				.toString()
			);
	}

	Client getBySid(String sid) {
		return cm.getBySid(sid);
	}

	public void sendClient(String sid, JSONObject msg) {
		WebSocketHelper.sendClient(cm.getBySid(sid), msg);
	}

	public static void sendError(IWsClient c, String msg) {
		WebSocketHelper.sendClient(c, newKurentoMsg()
				.put("id", "error")
				.put("message", msg));
	}

	public void remove(IWsClient _c) {
		if (client == null ||_c == null) {
			return;
		}
		final String uid = _c.getUid();
		final boolean test = !(_c instanceof Client);
		if (test) {
			IKStream s = getTestByUid(uid);
			if (s != null) {
				s.release(this);
			}
			return;
		}
		Client c = (Client)_c;
		for (StreamDesc sd : c.getStreams()) {
			IKStream s = getByUid(sd.getUid());
			if (s != null) {
				s.release(this);
			}
		}
		if (c.getRoomId() != null) {
			KRoom room = getRoom(c.getRoomId());
			room.leave(this, c);
			checkStreams(c.getRoomId());
		}
	}

	private KRoom getRoom(Long roomId) {
		log.debug("Searching for room {}", roomId);
		KRoom room = rooms.get(roomId);

		if (room == null) {
			log.debug("Room {} does not exist. Will create now!", roomId);
			Transaction t = beginTransaction();
			MediaPipeline pipe = client.createMediaPipeline(t);
			pipe.addTag(t, TAG_KUID, kuid);
			pipe.addTag(t, TAG_ROOM, String.valueOf(roomId));
			t.commit();
			room = new KRoom(roomId, pipe, chunkDao);
			rooms.put(roomId, room);
		}
		log.debug("Room {} found!", roomId);
		return room;
	}

	private KStream getByUid(String uid) {
		return uid == null ? null : streamsByUid.get(uid);
	}

	private KTestStream getTestByUid(String uid) {
		return uid == null ? null : testsByUid.get(uid);
	}

	static JSONObject newKurentoMsg() {
		return new JSONObject().put("type", KURENTO_TYPE);
	}

	static JSONObject newTestKurentoMsg() {
		return newKurentoMsg().put(TAG_MODE, MODE_TEST);
	}

	public static boolean activityAllowed(Client c, Activity a, Room room) {
		boolean r = false;
		switch (a) {
			case AUDIO:
				r = c.hasRight(Right.audio);
				break;
			case VIDEO:
				r = !room.isAudioOnly() && c.hasRight(Right.video);
				break;
			case AUDIO_VIDEO:
				r = !room.isAudioOnly() && c.hasRight(Right.audio) && c.hasRight(Right.video);
				break;
			default:
				break;
		}
		return r;
	}

	public JSONArray getTurnServers() {
		return getTurnServers(false);
	}

	private JSONArray getTurnServers(final boolean test) {
		JSONArray arr = new JSONArray();
		if (!Strings.isEmpty(turnUrl)) {
			try {
				JSONObject turn = new JSONObject();
				if ("rest".equalsIgnoreCase(turnMode)) {
					Mac mac = Mac.getInstance(HMAC_SHA1_ALGORITHM);
					mac.init(new SecretKeySpec(turnSecret.getBytes(), HMAC_SHA1_ALGORITHM));
					StringBuilder user = new StringBuilder()
							.append((test ? 60 : turnTtl * 60) + System.currentTimeMillis() / 1000L);
					if (!Strings.isEmpty(turnUser)) {
						user.append(':').append(turnUser);
					}
					turn.put("username", user)
						.put("credential", Base64.getEncoder().encodeToString(mac.doFinal(user.toString().getBytes())));
				} else {
					turn.put("username", turnUser)
						.put("credential", turnSecret);
				}
				turn.put("url", String.format("turn:%s", turnUrl));
				arr.put(turn);
			} catch (NoSuchAlgorithmException|InvalidKeyException e) {
				log.error("Unexpected error while creating turn", e);
			}
		}
		return arr;
	}

	public void setCheckTimeout(long checkTimeout) {
		this.checkTimeout = checkTimeout;
	}

	public void setObjCheckTimeout(long objCheckTimeout) {
		this.objCheckTimeout = objCheckTimeout;
	}

	public void setWatchThreadCount(int watchThreadCount) {
		this.watchThreadCount = watchThreadCount;
	}

	public void setKurentoWsUrl(String kurentoWsUrl) {
		this.kurentoWsUrl = kurentoWsUrl;
	}

	public void setTurnUrl(String turnUrl) {
		this.turnUrl = turnUrl;
	}

	public void setTurnUser(String turnUser) {
		this.turnUser = turnUser;
	}

	public void setTurnSecret(String turnSecret) {
		this.turnSecret = turnSecret;
	}

	public void setTurnMode(String turnMode) {
		this.turnMode = turnMode;
	}

	public void setTurnTtl(int turnTtl) {
		this.turnTtl = turnTtl;
	}

	private class KConnectionListener implements KurentoConnectionListener {
		@Override
		public void reconnected(boolean sameServer) {
			log.info("Kurento reconnected ? {}", sameServer);
		}

		@Override
		public void disconnected() {
			log.warn("Disconnected, will re-try in {} ms", checkTimeout);
			recheckScheduler.schedule(check, checkTimeout, MILLISECONDS);
		}

		@Override
		public void connectionFailed() {
			log.info("Kurento connectionFailed");
		}

		@Override
		public void connected() {
			log.info("Kurento connected");
		}
	}

	private class KWatchDog implements EventListener<ObjectCreatedEvent> {
		private ScheduledExecutorService scheduler;

		public KWatchDog() {
			scheduler = Executors.newScheduledThreadPool(watchThreadCount);
		}

		@Override
		public void onEvent(ObjectCreatedEvent evt) {
			log.debug("Kurento::ObjectCreated -> {}", evt.getObject());
			if (evt.getObject() instanceof MediaPipeline) {
				// room created
				final String roid = evt.getObject().getId();
				scheduler.schedule(() -> {
					if (client == null) {
						return;
					}
					// still alive
					MediaPipeline pipe = client.getById(roid, MediaPipeline.class);
					Map<String, String> tags = tagsAsMap(pipe);
					if (validTestPipeline(tags)) {
						return;
					}
					if (kuid.equals(tags.get(TAG_KUID))) {
						KRoom r = rooms.get(Long.valueOf(tags.get(TAG_ROOM)));
						if (r.getPipelineId().equals(pipe.getId())) {
							return;
						} else if (r != null) {
							rooms.remove(r.getRoomId());
							r.close(KurentoHandler.this);
						}
					}
					log.warn("Invalid MediaPipeline {} detected, will be dropped, tags: {}", pipe.getId(), tags);
					pipe.release();
				}, objCheckTimeout, MILLISECONDS);
			} else if (evt.getObject() instanceof Endpoint) {
				// endpoint created
				Endpoint _point = (Endpoint)evt.getObject();
				final String eoid = _point.getId();
				Class<? extends Endpoint> _clazz = null;
				if (_point instanceof WebRtcEndpoint) {
					_clazz = WebRtcEndpoint.class;
				} else if (_point instanceof RecorderEndpoint) {
					_clazz = RecorderEndpoint.class;
				} else if (_point instanceof PlayerEndpoint) {
					_clazz = PlayerEndpoint.class;
				}
				final Class<? extends Endpoint> clazz = _clazz;
				scheduler.schedule(() -> {
					if (client == null || clazz == null) {
						return;
					}
					// still alive
					Endpoint point = client.getById(eoid, clazz);
					if (validTestPipeline(point.getMediaPipeline())) {
						return;
					}
					Map<String, String> tags = tagsAsMap(point);
					KStream stream = getByUid(tags.get("outUid"));
					if (stream != null && stream.contains(tags.get("uid"))) {
						return;
					}
					log.warn("Invalid Endpoint {} detected, will be dropped, tags: {}", point.getId(), tags);
					point.release();
				}, objCheckTimeout, MILLISECONDS);
			}
		}

		private boolean validTestPipeline(MediaPipeline pipeline) {
			return validTestPipeline(tagsAsMap(pipeline));
		}

		private boolean validTestPipeline(Map<String, String> tags) {
			return kuid.equals(tags.get(TAG_KUID)) && MODE_TEST.equals(tags.get(TAG_MODE)) && MODE_TEST.equals(tags.get(TAG_ROOM));
		}
	}
}
