package com.team4.bintime.chat;

import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.DocumentSnapshot;
import com.team4.bintime.request.MatchAppointment;
import com.team4.bintime.request.MatchAppointmentPolicy;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** BT-033 방 참조로 불변 약속을 읽는다. BT-034/035에서 페이지·서버 시각 보정 확장 시 재사용한다. */
final class FirestoreMatchInfoSource implements MatchInfoSource {
    private final String uid;
    private java.util.function.LongSupplier now = System::currentTimeMillis;
    FirestoreMatchInfoSource(String uid) { this.uid = uid; }
    @Override public void useClock(java.util.function.LongSupplier now) { this.now = now; }
    @Override public ChatRepository.Registration observe(String roomId, ChatRepository.Listener<MatchInfo> listener) {
        boolean[] active = { true };
        com.google.firebase.firestore.ListenerRegistration registration = FirebaseFirestore.getInstance().collection("matches")
                .whereArrayContains("participantUids", uid).whereEqualTo("chatRoomId", roomId).limit(101)
                .addSnapshotListener((snapshot, error) -> {
            if (!active[0]) return;
            if (error != null || snapshot == null || snapshot.size() > 100) { listener.onError(); return; }
            if (snapshot.isEmpty()) { listener.onData(null); return; }
            try {
                List<MatchAppointment> items = new ArrayList<>();
                List<String> participants = null;
                for (DocumentSnapshot doc : snapshot.getDocuments()) {
                    Long start = doc.getLong("startMinute"), end = doc.getLong("endMinute");
                    if (start == null || end == null) throw new IllegalArgumentException();
                    MatchAppointment item = new MatchAppointment(doc.getId(), roomId, doc.getString("meetingDate"), Math.toIntExact(start), Math.toIntExact(end),
                            doc.getString("purpose"), MatchAppointment.Status.valueOf(doc.getString("status")), doc.getBoolean("reviewEligible"), false);
                    items.add(item);
                    Object raw = doc.get("participantUids");
                    if (!(raw instanceof List)) throw new IllegalArgumentException();
                    @SuppressWarnings("unchecked") List<String> uids = (List<String>) raw;
                    if (uids.size() != 2 || !uids.contains(uid)) throw new IllegalArgumentException();
                    if (participants != null && !new java.util.HashSet<>(participants).equals(new java.util.HashSet<>(uids))) throw new IllegalArgumentException();
                    participants = new ArrayList<>(uids);
                }
                MatchAppointment representative = MatchAppointmentPolicy.representative(items, now.getAsLong());
                if (representative == null) representative = items.stream().max(Comparator.comparingLong(MatchAppointment::endAtMillis)).get();
                boolean hasActive = items.stream().anyMatch(a -> a.status == MatchAppointment.Status.ACTIVE);
                boolean hasReadyLink = snapshot.getDocuments().stream().anyMatch(doc -> Boolean.TRUE.equals(doc.getBoolean("chatReady")));
                listener.onData(new MatchInfo(roomId, participants, hasActive ? ChatRoom.Status.ACTIVE : ChatRoom.Status.ENDED,
                        representative.meetingDate, representative.startMinute, representative.endMinute, hasReadyLink, items));
            } catch (RuntimeException invalid) { listener.onError(); }
        });
        return () -> { active[0] = false; registration.remove(); };
    }
}
