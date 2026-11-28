import type { Database } from "firebase-admin/database";
import type { Firestore } from "firebase-admin/firestore";
import { AiError } from "./errors";

/**
 * BT-041 답장 추천이 읽는 채팅 맥락. 앱은 matchId 만 보내고 서버가 직접 읽는다.
 * BT-034: matchId(개별 약속)와 실제 RTDB 방 키 chatRoomId 는 다를 수 있으므로 matches/{matchId}.chatRoomId 로 방을 찾는다.
 */
export interface ChatRoomContext {
  chatRoomId: string;
  participantUids: string[];
  /** 매칭 목적(스냅샷). "전체"·없음이면 null */
  purpose: string | null;
}

export interface ChatLine {
  senderId: string;
  content: string;
  createdAt: number;
}

export interface ChatContextAccess {
  /**
   * 호출자가 지금 이 방에서 답장을 보낼 수 있는지 확인하고 방 정보를 돌려준다.
   * - 매칭 없음·비참여자·방에서 나감(members 없음)·보관/전송 불가 방: PERMISSION_DENIED(존재 여부를 알리지 않음)
   * - 방이 아직 준비 전(chatRoomId 없음·chatReady=false): AI_UNAVAILABLE(retryable)
   * - ENDED 매칭은 채팅이 유지되므로 허용한다.
   */
  room(matchId: string, uid: string): Promise<ChatRoomContext>;
  /** createdAt 오름차순 최근 limit 개. 형식이 잘못된 메시지는 버린다. */
  recentMessages(chatRoomId: string, limit: number): Promise<ChatLine[]>;
}

/** RTDB 경로에 넣어도 되는 키(BT-024 matchIdSchema 와 같은 규칙) */
const ROOM_KEY = /^[A-Za-z0-9_-]{1,128}$/;
const PURPOSE_ALL = "전체";

const denied = () => new AiError("PERMISSION_DENIED", "not a current chat member");
const notReady = () => new AiError("AI_UNAVAILABLE", "chat room is not ready", { retryable: true });

export function parsePurpose(value: unknown): string | null {
  if (typeof value !== "string") return null;
  const purpose = value.trim();
  return purpose && purpose !== PURPOSE_ALL && purpose.length <= 20 ? purpose : null;
}

export function parseLines(raw: unknown): ChatLine[] {
  if (!raw || typeof raw !== "object") return [];
  const lines: ChatLine[] = [];
  for (const value of Object.values(raw as Record<string, unknown>)) {
    if (!value || typeof value !== "object") continue;
    const { senderId, content, createdAt } = value as Record<string, unknown>;
    if (typeof senderId !== "string" || typeof content !== "string" || typeof createdAt !== "number") continue;
    lines.push({ senderId, content, createdAt });
  }
  return lines.sort((a, b) => a.createdAt - b.createdAt);
}

/** Firestore 매칭 스냅샷 + RTDB 방. Admin SDK 라 Rules 를 거치지 않으므로 참여·멤버 여부를 여기서 직접 확인한다. */
export class FirebaseChatContextAccess implements ChatContextAccess {
  constructor(private readonly db: Firestore, private readonly rtdb: () => Database) {}

  async room(matchId: string, uid: string): Promise<ChatRoomContext> {
    const match = await this.read(() => this.db.collection("matches").doc(matchId).get());
    const participants = match.exists ? match.get("participantUids") : null;
    if (!Array.isArray(participants) || !participants.includes(uid)) throw denied();
    const chatRoomId = match.get("chatRoomId");
    if (typeof chatRoomId !== "string" || !ROOM_KEY.test(chatRoomId)) throw notReady();

    const database = this.rtdb();
    const [member, control] = await Promise.all([
      this.read(() => database.ref(`chatRooms/${chatRoomId}/members/${uid}`).get()),
      this.read(() => database.ref(`chatRooms/${chatRoomId}/control`).get()),
    ]);
    if (!member.exists()) throw denied();
    const state = control.val() as { chatReady?: unknown; writable?: unknown; archived?: unknown } | null;
    // control 이 없는 이전 시연 방은 전송을 허용하는 Rules(BT-034 §4)와 맞춘다.
    if (state?.archived === true || state?.writable === false) throw denied();
    if (state?.chatReady === false) throw notReady();
    return {
      chatRoomId,
      participantUids: participants.filter((value): value is string => typeof value === "string"),
      purpose: parsePurpose(match.get("purpose")),
    };
  }

  async recentMessages(chatRoomId: string, limit: number): Promise<ChatLine[]> {
    if (!ROOM_KEY.test(chatRoomId)) throw notReady();
    const snapshot = await this.read(() =>
      this.rtdb().ref(`chatRooms/${chatRoomId}/messages`).orderByChild("createdAt").limitToLast(limit).get(),
    );
    return parseLines(snapshot.val());
  }

  /** 읽기 실패(네트워크 등)는 원인 없이 일시적 오류로 바꾼다. */
  private async read<T>(op: () => Promise<T>): Promise<T> {
    try {
      return await op();
    } catch (error) {
      if (error instanceof AiError) throw error;
      throw new AiError("AI_UNAVAILABLE", "chat context read failed", { retryable: true });
    }
  }
}

/** 테스트용 방 */
export interface MemoryChatRoom {
  chatRoomId: string;
  participantUids: string[];
  /** 현재 RTDB members. 생략하면 participantUids 와 같다. */
  members?: string[];
  purpose?: string;
  chatReady?: boolean;
  writable?: boolean;
  archived?: boolean;
  messages?: ChatLine[];
}

/** 테스트용. matches 키는 matchId, 값의 chatRoomId 는 다를 수 있다. */
export class MemoryChatContextAccess implements ChatContextAccess {
  readonly reads: string[] = [];

  constructor(private readonly matches: Record<string, MemoryChatRoom | null> = {}) {}

  async room(matchId: string, uid: string): Promise<ChatRoomContext> {
    const room = this.matches[matchId];
    if (!room || !room.participantUids.includes(uid)) throw denied();
    if (!(room.members ?? room.participantUids).includes(uid)) throw denied();
    if (room.archived === true || room.writable === false) throw denied();
    if (room.chatReady === false) throw notReady();
    return { chatRoomId: room.chatRoomId, participantUids: room.participantUids, purpose: parsePurpose(room.purpose) };
  }

  async recentMessages(chatRoomId: string, limit: number): Promise<ChatLine[]> {
    this.reads.push(chatRoomId);
    const room = Object.values(this.matches).find((value) => value?.chatRoomId === chatRoomId);
    return [...(room?.messages ?? [])].sort((a, b) => a.createdAt - b.createdAt).slice(-limit);
  }
}
