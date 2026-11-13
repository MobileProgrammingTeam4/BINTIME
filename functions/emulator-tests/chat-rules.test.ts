import { readFileSync } from "node:fs";
import { assertFails, assertSucceeds, initializeTestEnvironment, type RulesTestEnvironment } from "@firebase/rules-unit-testing";
import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";

/**
 * BT-021 database.rules.json 검증. 로컬 RTDB 에뮬레이터에서만 실행한다.
 *   firebase emulators:exec --project demo-bintime --only database "npm --prefix functions run test:emulator"
 * 서버(Admin SDK)가 쓰는 members·userChatRooms 는 규칙을 끈 컨텍스트로 시드한다.
 */
const projectId = "demo-bintime";
const MATCH = "a".repeat(64);
const ENDED_MATCH = "b".repeat(64);
const ME = "uid-me";
const OTHER = "uid-other";
const OUTSIDER = "uid-outsider";
const SERVER_TIME = { ".sv": "timestamp" };

const emulatorHost = process.env.FIREBASE_DATABASE_EMULATOR_HOST;
let env: RulesTestEnvironment;

const db = (uid?: string) => (uid ? env.authenticatedContext(uid) : env.unauthenticatedContext()).database();
const message = (senderId: string, content: unknown, createdAt: unknown = SERVER_TIME) => ({ senderId, content, createdAt });

describe.skipIf(!emulatorHost)("BT-021 RTDB 채팅 규칙", () => {
  beforeAll(async () => {
    if (!emulatorHost?.startsWith("127.0.0.1:") && !emulatorHost?.startsWith("localhost:")) {
      throw new Error("local database emulator required");
    }
    const [host, port] = emulatorHost.split(":");
    env = await initializeTestEnvironment({
      projectId,
      database: {
        host,
        port: Number(port),
        rules: readFileSync(new URL("../../database.rules.json", import.meta.url), "utf8"),
      },
    });
  });

  afterAll(async () => {
    await env?.cleanup();
  });

  beforeEach(async () => {
    await env.clearDatabase();
    await env.withSecurityRulesDisabled(async (context) => {
      const room = (status: string) => ({ otherUid: OTHER, status, unreadCount: 2, lastMessage: "hi", lastMessageAt: 1 });
      await context.database().ref().set({
        chatRooms: {
          [MATCH]: {
            members: { [ME]: { joinedAt: 1 }, [OTHER]: { joinedAt: 1 } },
            messages: { "-old": message(OTHER, "hi", 1) },
          },
          [ENDED_MATCH]: {
            members: { [ME]: { joinedAt: 1 }, [OTHER]: { joinedAt: 1 } },
          },
        },
        userChatRooms: {
          [ME]: { [MATCH]: room("ACTIVE"), [ENDED_MATCH]: room("ENDED") },
          [OTHER]: { [MATCH]: { ...room("ACTIVE"), otherUid: ME } },
        },
      });
    });
  });

  describe("읽기", () => {
    it("멤버는 메시지와 멤버 목록을 읽는다", async () => {
      await assertSucceeds(db(ME).ref(`chatRooms/${MATCH}/messages`).get());
      await assertSucceeds(db(ME).ref(`chatRooms/${MATCH}/members`).get());
    });

    it("멤버는 createdAt 정렬 최근 N개 조회를 할 수 있다", async () => {
      const snapshot = await assertSucceeds(
        db(ME).ref(`chatRooms/${MATCH}/messages`).orderByChild("createdAt").limitToLast(100).get());
      expect(snapshot.child("-old/content").val()).toBe("hi");
    });

    it("비멤버·비로그인은 읽을 수 없다", async () => {
      await assertFails(db(OUTSIDER).ref(`chatRooms/${MATCH}/messages`).get());
      await assertFails(db(OUTSIDER).ref(`chatRooms/${MATCH}/members`).get());
      await assertFails(db().ref(`chatRooms/${MATCH}/messages`).get());
    });

    it("멤버도 방 전체 노드나 루트는 읽을 수 없다", async () => {
      await assertFails(db(ME).ref(`chatRooms/${MATCH}`).get());
      await assertFails(db(ME).ref("chatRooms").get());
      await assertFails(db(ME).ref().get());
    });

    it("userChatRooms 는 본인 것만 읽는다", async () => {
      await assertSucceeds(db(ME).ref(`userChatRooms/${ME}`).get());
      await assertFails(db(OUTSIDER).ref(`userChatRooms/${ME}`).get());
      await assertFails(db().ref(`userChatRooms/${ME}`).get());
    });
  });

  describe("메시지 쓰기", () => {
    const newMessage = (uid: string, value: unknown, matchId = MATCH) =>
      db(uid).ref(`chatRooms/${matchId}/messages`).push().set(value);

    it("멤버는 본인 senderId 와 서버 시각으로 보낸다", async () => {
      await assertSucceeds(newMessage(ME, message(ME, "안녕하세요")));
    });

    it("ENDED 방에서도 보낼 수 있다(종료는 차단이 아님)", async () => {
      await assertSucceeds(newMessage(ME, message(ME, "오늘 고마웠어요"), ENDED_MATCH));
    });

    it("다른 사람 senderId 로는 보낼 수 없다", async () => {
      await assertFails(newMessage(ME, message(OTHER, "위조")));
    });

    it("클라이언트 시각은 거부한다", async () => {
      await assertFails(newMessage(ME, message(ME, "x", Date.now())));
    });

    it("필드 누락·추가 필드는 거부한다", async () => {
      await assertFails(newMessage(ME, { senderId: ME, content: "x" }));
      await assertFails(newMessage(ME, { senderId: ME, createdAt: SERVER_TIME }));
      await assertFails(newMessage(ME, { ...message(ME, "x"), type: "SYSTEM" }));
    });

    it("본문은 공백이 아닌 1~1000자 문자열이어야 한다", async () => {
      await assertSucceeds(newMessage(ME, message(ME, "가".repeat(1000))));
      await assertSucceeds(newMessage(ME, message(ME, "  줄바꿈\n포함  ")));
      // 공백 문자 집합이 글자 t·r·n 으로 잘못 해석되지 않는지 확인
      await assertSucceeds(newMessage(ME, message(ME, "rtn")));
      await assertSucceeds(newMessage(ME, message(ME, "\tn")));
      await assertFails(newMessage(ME, message(ME, "가".repeat(1001))));
      await assertFails(newMessage(ME, message(ME, "")));
      await assertFails(newMessage(ME, message(ME, "   ")));
      await assertFails(newMessage(ME, message(ME, " \n\t ")));
      await assertFails(newMessage(ME, message(ME, 123)));
    });

    it("기존 메시지는 수정·삭제할 수 없다", async () => {
      const ref = db(ME).ref(`chatRooms/${MATCH}/messages`).push();
      await assertSucceeds(ref.set(message(ME, "원본")));
      await assertFails(ref.set(message(ME, "수정")));
      await assertFails(ref.child("content").set("수정"));
      await assertFails(ref.remove());
      await assertFails(db(OTHER).ref(`chatRooms/${MATCH}/messages/-old`).remove());
    });

    it("비멤버·비로그인은 보낼 수 없다", async () => {
      await assertFails(newMessage(OUTSIDER, message(OUTSIDER, "x")));
      await assertFails(db().ref(`chatRooms/${MATCH}/messages`).push().set(message(ME, "x")));
    });

    it("messages 노드 전체를 덮어쓸 수 없다", async () => {
      await assertFails(db(ME).ref(`chatRooms/${MATCH}/messages`).set({ "-x": message(ME, "x") }));
    });
  });

  describe("서버 전용 경로", () => {
    it("멤버 추가·삭제는 앱에서 할 수 없다", async () => {
      await assertFails(db(OUTSIDER).ref(`chatRooms/${MATCH}/members/${OUTSIDER}`).set({ joinedAt: 1 }));
      await assertFails(db(ME).ref(`chatRooms/${MATCH}/members/${ME}`).remove());
      await assertFails(db(ME).ref(`chatRooms/new-room/members/${ME}`).set({ joinedAt: 1 }));
    });

    it("userChatRooms 의 상태·숨김·목록 값은 앱에서 바꿀 수 없다", async () => {
      const mine = db(ME).ref(`userChatRooms/${ME}/${MATCH}`);
      await assertFails(mine.child("status").set("ENDED"));
      await assertFails(mine.child("hidden").set(true));
      await assertFails(mine.child("lastMessage").set("x"));
      await assertFails(mine.remove());
      await assertFails(db(ME).ref(`userChatRooms/${ME}/${"c".repeat(64)}`).set({ otherUid: OTHER, status: "ACTIVE" }));
    });
  });

  describe("읽음 처리 (unreadCount)", () => {
    it("본인 항목의 unreadCount 를 0 으로만 바꿀 수 있다", async () => {
      await assertSucceeds(db(ME).ref(`userChatRooms/${ME}/${MATCH}/unreadCount`).set(0));
      await assertFails(db(ME).ref(`userChatRooms/${ME}/${MATCH}/unreadCount`).set(5));
      await assertFails(db(ME).ref(`userChatRooms/${ME}/${MATCH}/unreadCount`).set("0"));
    });

    it("본인 unreadCount 도 삭제할 수 없다 (#90 리뷰 Y2)", async () => {
      await assertFails(db(ME).ref(`userChatRooms/${ME}/${MATCH}/unreadCount`).remove());
      await assertFails(db(ME).ref(`userChatRooms/${ME}/${MATCH}/unreadCount`).set(null));
      await assertFails(db(ME).ref(`userChatRooms/${ME}/${MATCH}`).update({ unreadCount: null }));
      await assertSucceeds(db(ME).ref(`userChatRooms/${ME}/${MATCH}/unreadCount`).set(0));
    });

    it("남의 unreadCount, 없는 방의 unreadCount 는 쓸 수 없다", async () => {
      await assertFails(db(OUTSIDER).ref(`userChatRooms/${ME}/${MATCH}/unreadCount`).set(0));
      await assertFails(db(ME).ref(`userChatRooms/${OTHER}/${MATCH}/unreadCount`).set(0));
      await assertFails(db(ME).ref(`userChatRooms/${ME}/${"d".repeat(64)}/unreadCount`).set(0));
    });
  });
});
