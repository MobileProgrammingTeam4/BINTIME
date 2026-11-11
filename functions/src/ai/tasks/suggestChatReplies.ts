import { z } from "zod";
import { requireParticipant } from "../access";
import type { AiTask } from "../types";
import { matchIdSchema, requireDistinct, text } from "./shared";

/**
 * E(BT-041) 채팅 답장 3개 추천. 앱은 matchId 만 보낸다(메시지 본문을 앱에서 받지 않아 조작·과다 전송을 막는다).
 * 서버가 RTDB chatRooms/{matchId}/messages 최근 메시지를 읽어 이름 없이 "나/상대"로만 바꿔 보낸다(BT-041).
 * 결과는 제안일 뿐이며 서버·앱 모두 자동 전송하지 않는다. 앱은 칩 선택 시 입력창에만 넣는다.
 * ENDED 매칭도 채팅이 유지되므로 참여자이면 상태와 관계없이 허용한다.
 */
export const REPLY_COUNT = 3;
export const REPLY_MAX_LENGTH = 50;

const input = z.object({ matchId: matchIdSchema }).strict();

const output = z
  .object({
    replies: z.array(text(1, REPLY_MAX_LENGTH)).length(REPLY_COUNT),
  })
  .strict();

export type SuggestChatRepliesInput = z.infer<typeof input>;
export type SuggestChatRepliesOutput = z.infer<typeof output>;

export const suggestChatRepliesTask: AiTask<SuggestChatRepliesInput, SuggestChatRepliesOutput> = {
  name: "suggestChatReplies",
  input,
  output,
  timeoutMs: 10_000,
  dailyLimit: 50,
  authorize: ({ matchId }, ctx, deps) => requireParticipant(deps.matches, matchId, ctx.uid),
  verify: (out) => {
    requireDistinct(out.replies, "replies");
    return out;
  },
  mock: () => ({ replies: ["좋아요! 그때 봐요 :)", "혹시 시간 조금 늦춰도 될까요?", "어디서 만나면 좋을까요?"] }),
};
