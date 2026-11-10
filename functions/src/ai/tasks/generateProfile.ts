import { z } from "zod";
import type { AiTask } from "../types";
import { requireDistinct, text } from "./shared";

/**
 * B(BT-029) 자기소개 초안·선호 태그 추천. 결과는 사용자가 수정·저장하는 초안이다.
 * 이름·이메일·UID 는 보내지 않는다. bio 150자는 앱 ProfileValidator.BIO_MAX_LENGTH 와 같다.
 * 태그 허용 목록·# 접두어 여부는 BT-012 #3 확정 후 verify 에 반영한다.
 * live 프롬프트는 BT-029 에서 buildRequest 로 추가한다.
 */
export const BIO_MAX_LENGTH = 150;
export const MAX_SUGGESTED_TAGS = 5;

const input = z
  .object({
    department: text(1, 40),
    grade: z.number().int().min(1).max(6),
    preferredActivities: z.array(text(1, 20)).max(10).default([]),
    courseNames: z.array(text(1, 60)).max(20).default([]),
    draftBio: z.string().trim().max(BIO_MAX_LENGTH).optional(),
  })
  .strict();

const output = z
  .object({
    bio: text(1, BIO_MAX_LENGTH),
    suggestedTags: z.array(text(1, 20)).max(MAX_SUGGESTED_TAGS),
  })
  .strict();

export type GenerateProfileInput = z.infer<typeof input>;
export type GenerateProfileOutput = z.infer<typeof output>;

export const generateProfileTask: AiTask<GenerateProfileInput, GenerateProfileOutput> = {
  name: "generateProfile",
  input,
  output,
  timeoutMs: 10_000,
  dailyLimit: 20,
  verify: (out) => {
    requireDistinct(out.suggestedTags, "suggestedTags");
    return out;
  },
  mock: ({ department, grade }) => ({
    bio: `${department} ${grade}학년이에요. 공강에 같이 공부하거나 카페에서 이야기 나눌 친구를 찾고 있어요!`,
    suggestedTags: ["#스터디", "#카페", "#산책"],
  }),
};
