import { z } from "zod";
import { AiError } from "../errors";
import type { AiTask } from "../types";
import { text } from "./shared";

/**
 * C(BT-026) 시간표 이미지 인식. 앱은 이미지 본문이 아니라 본인 Storage 경로만 보낸다(Callable 요청 10MB 제한).
 * 결과는 검수용 초안이며 서버가 시간표에 바로 저장하지 않는다. 중복 정리·검수 UI 는 BT-026.
 * live 프롬프트(Storage 이미지 읽기)는 BT-026 에서 buildRequest 로 추가한다.
 */

/** 사용자별 Storage 경로 규칙(제안). 확정은 C 의 BT-025 와 맞춘다. */
export const timetableImagePrefix = (uid: string) => `timetableImages/${uid}/`;
export const MAX_IMAGES = 5;

const input = z
  .object({
    imagePaths: z.array(z.string().min(1).max(300)).min(1).max(MAX_IMAGES),
  })
  .strict();

/** BT-012 시간 규칙: 자정 기준 분, [startMinute, endMinute), dayOfWeek 1(월)~7(일) */
const course = z
  .object({
    name: text(1, 60),
    dayOfWeek: z.number().int().min(1).max(7),
    startMinute: z.number().int().min(0).max(1439),
    endMinute: z.number().int().min(1).max(1440),
    building: text(1, 40).nullable(),
    room: text(1, 40).nullable(),
    /** 0~1. 낮으면 검수 화면에서 강조한다. */
    confidence: z.number().min(0).max(1),
    /** imagePaths 의 몇 번째 이미지에서 읽었는지 */
    sourceImageIndex: z.number().int().min(0),
  })
  .strict();

const output = z
  .object({
    courses: z.array(course).max(60),
    warnings: z.array(text(1, 200)).max(20),
  })
  .strict();

export type AnalyzeTimetableInput = z.infer<typeof input>;
export type AnalyzeTimetableOutput = z.infer<typeof output>;

export const analyzeTimetableTask: AiTask<AnalyzeTimetableInput, AnalyzeTimetableOutput> = {
  name: "analyzeTimetable",
  input,
  output,
  timeoutMs: 30_000,
  dailyLimit: 10,
  authorize: async ({ imagePaths }, ctx) => {
    const prefix = timetableImagePrefix(ctx.uid);
    const ownsAll = imagePaths.every((path) => path.startsWith(prefix) && !path.includes(".."));
    if (!ownsAll) throw new AiError("PERMISSION_DENIED", "image path outside caller prefix");
  },
  verify: (out, { imagePaths }) => {
    for (const c of out.courses) {
      if (c.startMinute >= c.endMinute || c.sourceImageIndex >= imagePaths.length) {
        throw new AiError("AI_INVALID_OUTPUT", "course time or image index", { transient: true });
      }
    }
    return out;
  },
  // 두 이미지에 같은 수업이 겹쳐 나오는 경우를 넣어 BT-026 중복 정리를 시험할 수 있게 한다.
  mock: ({ imagePaths }) => {
    const last = imagePaths.length - 1;
    return {
      courses: [
        { name: "컴퓨터 구조론", dayOfWeek: 1, startMinute: 600, endMinute: 690, building: "공학관", room: "302호", confidence: 0.94, sourceImageIndex: 0 },
        { name: "자료구조", dayOfWeek: 3, startMinute: 780, endMinute: 870, building: "미래관", room: "407호", confidence: 0.88, sourceImageIndex: 0 },
        { name: "자료구조", dayOfWeek: 3, startMinute: 780, endMinute: 870, building: "미래관", room: "407호", confidence: 0.81, sourceImageIndex: last },
        { name: "확률과 통계", dayOfWeek: 4, startMinute: 540, endMinute: 630, building: null, room: null, confidence: 0.52, sourceImageIndex: last },
      ],
      warnings: last > 0 ? ["여러 이미지에서 같은 수업이 발견되었습니다."] : [],
    };
  },
};
