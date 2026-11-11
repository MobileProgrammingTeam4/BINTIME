import type { Firestore } from "firebase-admin/firestore";
import { AiError } from "./errors";

/** 매칭 참여자 조회. 기준 데이터는 Firestore matches/{matchId}.participantUids (AGENTS.md 5장). */
export interface MatchAccess {
  /** 매칭이 없으면 null */
  participantUids(matchId: string): Promise<string[] | null>;
}

export class FirestoreMatchAccess implements MatchAccess {
  constructor(private readonly db: Firestore) {}

  async participantUids(matchId: string): Promise<string[] | null> {
    const snapshot = await this.db.collection("matches").doc(matchId).get();
    if (!snapshot.exists) return null;
    const uids = snapshot.get("participantUids");
    return Array.isArray(uids) ? uids.filter((uid): uid is string => typeof uid === "string") : [];
  }
}

/** 테스트·Mock 용 */
export class MemoryMatchAccess implements MatchAccess {
  constructor(private readonly matches: Record<string, string[]> = {}) {}

  async participantUids(matchId: string): Promise<string[] | null> {
    return this.matches[matchId] ?? null;
  }
}

/** 매칭이 없을 때도 PERMISSION_DENIED 로 응답해 다른 사람의 matchId 존재 여부를 알려주지 않는다. */
export async function requireParticipant(access: MatchAccess, matchId: string, uid: string): Promise<void> {
  const uids = await access.participantUids(matchId);
  if (!uids || !uids.includes(uid)) {
    throw new AiError("PERMISSION_DENIED", "not a match participant");
  }
}
