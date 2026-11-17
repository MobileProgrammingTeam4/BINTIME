import { FieldValue, type Firestore } from "firebase-admin/firestore";
import type { TaskName } from "./types";

/**
 * 사용자별 하루 호출 수. Firestore aiUsage/{uid}_{YYYY-MM-DD}(Asia/Seoul) 문서에 작업별로 센다.
 * 작업에 최소 호출 간격이 있으면 같은 트랜잭션에서 마지막 호출 시각(lastCalledAtMs.<task>)도 확인·기록한다.
 * 서버(Admin SDK)만 쓰고 앱은 읽기·쓰기 모두 금지한다(Rules 작성 시 deny 로 둔다).
 * 토큰 수 등 상세 사용량은 Cloud Logging 의 "ai_call" 로그에 남긴다.
 */
export interface UsageLimits {
  dailyLimit: number;
  /** 0 또는 없으면 간격 제한 없음 */
  minIntervalMs?: number;
}

/** OK 면 1 차감됨. 나머지는 차감하지 않는다. */
export type ConsumeResult = "OK" | "DAILY_LIMIT" | "TOO_SOON";

export interface UsageStore {
  consume(uid: string, task: TaskName, limits: UsageLimits, dateKey: string, nowMs: number): Promise<ConsumeResult>;
}

export const USAGE_COLLECTION = "aiUsage";

/** 저장된 값으로 이번 호출 허용 여부를 정한다. 시계가 뒤로 간 경우(last > now)도 간격 안으로 본다. */
export function checkLimits(used: number, lastCalledAtMs: number | undefined, limits: UsageLimits, nowMs: number): ConsumeResult {
  if (used >= limits.dailyLimit) return "DAILY_LIMIT";
  const interval = limits.minIntervalMs ?? 0;
  if (interval > 0 && lastCalledAtMs !== undefined && nowMs - lastCalledAtMs < interval) return "TOO_SOON";
  return "OK";
}

export class FirestoreUsageStore implements UsageStore {
  constructor(private readonly db: Firestore) {}

  async consume(uid: string, task: TaskName, limits: UsageLimits, dateKey: string, nowMs: number): Promise<ConsumeResult> {
    const ref = this.db.collection(USAGE_COLLECTION).doc(`${uid}_${dateKey}`);
    return this.db.runTransaction(async (tx) => {
      const snapshot = await tx.get(ref);
      const counts = (snapshot.get("counts") as Partial<Record<TaskName, number>> | undefined) ?? {};
      const lastCalls = (snapshot.get("lastCalledAtMs") as Partial<Record<TaskName, number>> | undefined) ?? {};
      const used = counts[task] ?? 0;
      const result = checkLimits(used, lastCalls[task], limits, nowMs);
      if (result !== "OK") return result;
      tx.set(
        ref,
        {
          uid,
          date: dateKey,
          counts: { [task]: used + 1 },
          lastCalledAtMs: { [task]: nowMs },
          total: FieldValue.increment(1),
          updatedAt: FieldValue.serverTimestamp(),
        },
        { merge: true },
      );
      return "OK";
    });
  }
}

/** 테스트용 */
export class MemoryUsageStore implements UsageStore {
  readonly counts = new Map<string, number>();
  readonly lastCalls = new Map<string, number>();

  async consume(uid: string, task: TaskName, limits: UsageLimits, dateKey: string, nowMs: number): Promise<ConsumeResult> {
    const key = `${uid}_${dateKey}_${task}`;
    const used = this.counts.get(key) ?? 0;
    const result = checkLimits(used, this.lastCalls.get(key), limits, nowMs);
    if (result !== "OK") return result;
    this.counts.set(key, used + 1);
    this.lastCalls.set(key, nowMs);
    return "OK";
  }
}
