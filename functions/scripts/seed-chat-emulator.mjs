// BT-021 로컬 RTDB 에뮬레이터 전용 채팅 시드. 실제 채팅방은 BT-034 에서 서버가 매칭 수락 후 만든다.
// 운영 DB 에 쓰지 않도록 에뮬레이터 호스트가 127.0.0.1/localhost 가 아니면 실행을 거부한다.
//
// 사용법 (docs/BT-021.md):
//   npm --prefix functions run seed:chat -- --me <내 UID> [--other <상대 UID>] [--ns <네임스페이스>] [--host 127.0.0.1:9000]
//   --ns 기본값: demo-bintime-default-rtdb. 앱을 붙일 때는 앱의 RTDB 주소 이름(예: bintime-mobile-default-rtdb)을 준다.
//
// 만드는 데이터: 진행 중(ACTIVE) 방 1개 + 종료(ENDED) 방 1개, 각 방 members·messages, 양쪽 userChatRooms.
// 같은 인자로 다시 실행하면 같은 matchId 를 덮어쓴다(멱등). 다른 경로는 건드리지 않는다.
import { createHash } from "node:crypto";
import { parseArgs } from "node:util";
import { deleteApp, initializeApp } from "firebase-admin/app";
import { getDatabase } from "firebase-admin/database";

const { values } = parseArgs({
  options: {
    me: { type: "string" },
    other: { type: "string", default: "seed-partner" },
    ns: { type: "string", default: "demo-bintime-default-rtdb" },
    host: { type: "string", default: process.env.FIREBASE_DATABASE_EMULATOR_HOST ?? "127.0.0.1:9000" },
  },
});

const fail = (message) => {
  console.error(`seed-chat: ${message}`);
  process.exit(1);
};

const KEY = /^[^.$#\[\]\/]{1,128}$/;
if (!values.me || !KEY.test(values.me)) fail("--me <UID> 가 필요합니다.");
if (!KEY.test(values.other) || values.other === values.me) fail("--other 는 --me 와 다른 UID 여야 합니다.");
if (!/^[a-z0-9-]+$/.test(values.ns)) fail("--ns 형식이 올바르지 않습니다.");
if (!/^(127\.0\.0\.1|localhost):\d+$/.test(values.host)) fail("로컬 에뮬레이터 호스트(127.0.0.1:포트)만 허용합니다.");

process.env.FIREBASE_DATABASE_EMULATOR_HOST = values.host;
const app = initializeApp({ projectId: "demo-bintime", databaseURL: `http://${values.host}?ns=${values.ns}` }, "seed-chat");

const me = values.me;
const other = values.other;
// 실제 matchId(= requestId, sha256 64자리 hex)와 같은 형식
const matchId = (label) => createHash("sha256").update(JSON.stringify(["seed-chat", me, other, label])).digest("hex");
const activeId = matchId("active");
const endedId = matchId("ended");

const now = Date.now();
const minute = 60_000;
const conversation = (base, lines) => {
  const messages = {};
  lines.forEach(([sender, content], i) => {
    // push 키와 비슷하게 시간순 정렬되는 키
    messages[`-seed${String(base + i).padStart(4, "0")}`] = { senderId: sender, content, createdAt: now - (lines.length - i) * minute };
  });
  return messages;
};

const activeMessages = conversation(0, [
  [other, "안녕하세요! 공강 매칭으로 연결됐네요 :)"],
  [me, "안녕하세요! 오늘 스터디 같이 하실래요?"],
  [other, "좋아요. 북악관 근처 어떠세요?"],
]);
const endedMessages = conversation(100, [
  [me, "오늘 덕분에 과제 잘 끝냈어요"],
  [other, "저도요! 다음에 또 봐요"],
]);
const last = (messages) => {
  const list = Object.values(messages);
  return list[list.length - 1];
};

const room = (otherUid, status, messages, unreadCount) => ({
  otherUid,
  status,
  lastMessage: last(messages).content,
  lastMessageAt: last(messages).createdAt,
  lastMessageId: Object.keys(messages).at(-1),
  unreadCount,
  hidden: false,
});

const updates = {
  [`chatRooms/${activeId}`]: { members: { [me]: { joinedAt: now }, [other]: { joinedAt: now } }, messages: activeMessages },
  [`chatRooms/${endedId}`]: { members: { [me]: { joinedAt: now }, [other]: { joinedAt: now } }, messages: endedMessages },
  [`userChatRooms/${me}/${activeId}`]: room(other, "ACTIVE", activeMessages, 1),
  [`userChatRooms/${me}/${endedId}`]: room(other, "ENDED", endedMessages, 0),
  [`userChatRooms/${other}/${activeId}`]: room(me, "ACTIVE", activeMessages, 0),
  [`userChatRooms/${other}/${endedId}`]: room(me, "ENDED", endedMessages, 1),
};

try {
  await getDatabase(app).ref().update(updates);
  console.log(`seed-chat: ${values.host} ns=${values.ns}`);
  console.log(`  ACTIVE ${activeId}`);
  console.log(`  ENDED  ${endedId}`);
} catch (error) {
  fail(error instanceof Error ? error.message : String(error));
} finally {
  await deleteApp(app);
}
