package com.team4.bintime.notification;

import org.junit.Test;
import static org.junit.Assert.*;
import com.team4.bintime.request.MockRequestRepository;

public class MockNotificationRepositoryTest {
    @Test public void fixtures_haveFourTypesAndValidRequestReferences() {
        MockNotificationRepository events=new MockNotificationRepository();
        MockRequestRepository requests=new MockRequestRepository();
        java.util.Set<MockNotificationRepository.Type> types=new java.util.HashSet<>();
        for(MockNotificationRepository.Item item:events.list()) {
            assertNotNull(requests.find(item.requestId)); types.add(item.type);
        }
        assertEquals(4,types.size());
    }
    @Test public void acceptingThroughNotification_usesSameRequestAndDisablesRepeat() {
        MockNotificationRepository events=new MockNotificationRepository();
        MockRequestRepository requests=new MockRequestRepository();
        MockNotificationRepository.Item event=events.list().get(2);
        assertTrue(events.canRespond(event,requests));
        assertTrue(requests.respond(event.requestId,MockRequestRepository.Status.ACCEPTED));
        assertFalse(events.canRespond(event,requests));
        assertEquals(2,requests.list(MockRequestRepository.Tab.COMPLETED).size());
        assertFalse(requests.respond(event.requestId,MockRequestRepository.Status.REJECTED));
    }
    @Test public void rejectionDisablesRepeatButPreservesNotification() {
        MockNotificationRepository events=new MockNotificationRepository();
        MockRequestRepository requests=new MockRequestRepository();
        MockNotificationRepository.Item event=events.list().get(2);
        assertTrue(requests.respond(event.requestId,MockRequestRepository.Status.REJECTED));
        assertFalse(events.canRespond(event,requests));
        assertEquals(4,events.list().size());
    }
    @Test public void popupSuppressionExpiresAtNextSeoulDateAndOffAlwaysWins() {
        assertFalse(PopupPolicy.canShow(true,"2026-11-05","2026-11-05"));
        assertTrue(PopupPolicy.canShow(true,"2026-11-05","2026-11-06"));
        assertTrue(PopupPolicy.canShow(true,"2026-11-30","2026-12-01"));
        assertTrue(PopupPolicy.canShow(true,"","2026-11-05"));
        assertFalse(PopupPolicy.canShow(false,"2026-11-05","2026-11-06"));
    }
}
