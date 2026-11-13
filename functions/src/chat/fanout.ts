/**
 * E · BT-022 새 메시지 → userChatRooms 갱신(서버 전용). 경로·필드는 docs/BT-021.md.
 *   chatRooms/{matchId}/messages/{messageId}: { senderId, content, createdAt }
 *   userChatRooms/{uid}/{matchId}: { otherUid, lastMessage, lastMessageAt, lastMessageId, unreadCount, status, hidden }
 *
 * 앱은 Rules 때문에 userChatRooms 를 쓸 수 없으므로(본인 unreadCount = 0 제외) 이 트리거가
 * 두 멤버의 마지막 메시지와 상대의 안읽음 수를 갱신한다. Admin SDK 는 Rules 를 우회하므로 여기서 멤버를 직접 확인한다.
 */

/** 메시지 노드. Rules 가 형식을 검사하지만 서버에서도 다시 확인한다. */
export interface ChatMessageValue {
  senderId: string;
  content: string;
  createdAt: number;
}

export type RoomEntry = Record<string, unknown>;

const MAX_CONTENT_LENGTH = 1000;

export function parseMessage(value: unknown): ChatMessageValue | null {
  if (typeof value !== "object" || value === null) return null;
  const { senderId, content, createdAt } = value as Record<string, unknown>;
  if (typeof senderId !== "string" || senderId.length === 0) return null;
  if (typeof content !== "string" || content.trim().length === 0 || content.length > MAX_CONTENT_LENGTH) return null;
  if (typeof createdAt !== "number" || !Number.isFinite(createdAt) || createdAt <= 0) return null;
  return { senderId, content, createdAt };
}

/**
 * 한 멤버의 userChatRooms 항목에 메시지를 반영한 새 값. 바꿀 것이 없으면 undefined(트랜잭션 중단).
 * - 항목이 없으면(null) null 을 돌려 만들지 않는다. 항목 생성은 채팅방 생성 함수(BT-034) 몫이다.
 *   RTDB 트랜잭션은 처음에 로컬 캐시 값(null)으로 호출될 수 있어, 여기서 중단(undefined)하면 서버 값으로 다시 시도하지 않는다.
 * - 같은 메시지를 두 번 받으면(at-least-once) lastMessageId 로 건너뛴다.
 * - 늦게 도착한 오래된 메시지는 마지막 메시지를 덮지 않고 상대 안읽음 수만 올린다.
 * - hidden(나간 방)은 건드리지 않는다. 새 메시지로 다시 보일지는 BT-043 에서 정한다.
 */
export function applyMessage(current: RoomEntry | null, uid: string, messageId: string,
  message: ChatMessageValue): RoomEntry | null | undefined {
  if (current === null || current === undefined) return null;
  if (typeof current !== "object") return undefined;
  if (current.lastMessageId === messageId) return undefined;

  const next: RoomEntry = { ...current };
  const lastAt = typeof current.lastMessageAt === "number" ? current.lastMessageAt : 0;
  const lastId = typeof current.lastMessageId === "string" ? current.lastMessageId : "";
  const newer = message.createdAt > lastAt || (message.createdAt === lastAt && messageId > lastId);
  if (newer) {
    next.lastMessage = message.content;
    next.lastMessageAt = message.createdAt;
    next.lastMessageId = messageId;
  }
  if (uid !== message.senderId) {
    const unread = typeof current.unreadCount === "number" && current.unreadCount >= 0 ? current.unreadCount : 0;
    next.unreadCount = unread + 1;
  }
  if (!newer && uid === message.senderId) return undefined;
  return next;
}

/** RTDB 루트 참조의 최소 인터페이스(firebase-admin Reference). 테스트에서 에뮬레이터 참조를 넘긴다. */
export interface RootRef {
  child(path: string): {
    get(): Promise<{ exists(): boolean; val(): unknown }>;
    transaction(update: (current: RoomEntry | null) => RoomEntry | null | undefined): Promise<unknown>;
  };
}

export type FanoutResult = "applied" | "invalid-message" | "not-member";

/** 트리거 본문. 멤버마다 별도 트랜잭션이라 한쪽 실패가 다른 쪽을 되돌리지 않는다(재전송 시 lastMessageId 로 멱등). */
export async function fanOutMessage(root: RootRef, matchId: string, messageId: string,
  value: unknown): Promise<FanoutResult> {
  const message = parseMessage(value);
  if (!message) return "invalid-message";
  const membersSnapshot = await root.child(`chatRooms/${matchId}/members`).get();
  const members = membersSnapshot.exists() ? Object.keys((membersSnapshot.val() ?? {}) as object) : [];
  if (!members.includes(message.senderId)) return "not-member";
  await Promise.all(members.map((uid) =>
    root.child(`userChatRooms/${uid}/${matchId}`).transaction((current) =>
      applyMessage(current, uid, messageId, message))));
  return "applied";
}
