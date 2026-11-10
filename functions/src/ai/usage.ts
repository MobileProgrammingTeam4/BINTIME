import { FieldValue, type Firestore } from "firebase-admin/firestore";
import type { TaskName } from "./types";

/**
 * 사용자별 하루 호출 수. Firestore aiUsage/{uid}_{YYYY-MM-DD}(Asia/Seoul) 문서에 작업별로 센다.
 * 서버(Admin SDK)만 쓰고 앱은 읽기·쓰기 모두 금지한다(Rules 작성 시 deny 로 둔다).
 * 토큰 수 등 상세 사용량은 Cloud Logging 의 "ai_call" 로그에 남긴다.
 */
export interface UsageStore {
  /** 한도 안이면 1 늘리고 true, 한도에 닿았으면 그대로 두고 false */
  consume(uid: string, task: TaskName, dailyLimit: number, dateKey: string): Promise<boolean>;
}

export const USAGE_COLLECTION = "aiUsage";

export class FirestoreUsageStore implements UsageStore {
  constructor(private readonly db: Firestore) {}

  async consume(uid: string, task: TaskName, dailyLimit: number, dateKey: string): Promise<boolean> {
    const ref = this.db.collection(USAGE_COLLECTION).doc(`${uid}_${dateKey}`);
    return this.db.runTransaction(async (tx) => {
      const snapshot = await tx.get(ref);
      const counts = (snapshot.get("counts") as Partial<Record<TaskName, number>> | undefined) ?? {};
      const used = counts[task] ?? 0;
      if (used >= dailyLimit) return false;
      tx.set(
        ref,
        {
          uid,
          date: dateKey,
          counts: { [task]: used + 1 },
          total: FieldValue.increment(1),
          updatedAt: FieldValue.serverTimestamp(),
        },
        { merge: true },
      );
      return true;
    });
  }
}

/** 테스트용 */
export class MemoryUsageStore implements UsageStore {
  readonly counts = new Map<string, number>();

  async consume(uid: string, task: TaskName, dailyLimit: number, dateKey: string): Promise<boolean> {
    const key = `${uid}_${dateKey}_${task}`;
    const used = this.counts.get(key) ?? 0;
    if (used >= dailyLimit) return false;
    this.counts.set(key, used + 1);
    return true;
  }
}
