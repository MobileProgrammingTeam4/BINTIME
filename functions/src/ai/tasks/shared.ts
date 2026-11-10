import { z } from "zod";
import { AiError } from "../errors";

/** RTDB chatRooms/{matchId} 와 Firestore matches/{matchId} 에 그대로 쓰이는 키 */
export const matchIdSchema = z
  .string()
  .min(1)
  .max(128)
  .regex(/^[A-Za-z0-9_-]+$/);

/** 앞뒤 공백을 지운 짧은 문자열 */
export const text = (min: number, max: number) => z.string().trim().min(min).max(max);

export function requireDistinct(values: string[], label: string): void {
  const normalized = values.map((value) => value.trim().toLowerCase());
  if (new Set(normalized).size !== normalized.length) {
    throw new AiError("AI_INVALID_OUTPUT", `${label} must be distinct`, { transient: true });
  }
}
