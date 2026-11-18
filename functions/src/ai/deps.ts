import { getApps, initializeApp } from "firebase-admin/app";
import { getFirestore } from "firebase-admin/firestore";
import { getStorage } from "firebase-admin/storage";
import { defineSecret } from "firebase-functions/params";
import { FirestoreMatchAccess } from "./access";
import { resolveMode } from "./config";
import { ALLOWED_EMAIL_SUFFIXES_ENV, parseAllowedEmailSuffixes } from "./emailPolicy";
import { AiError } from "./errors";
import { GenAiModelClient, type ModelClient } from "./gemini";
import type { AiDeps } from "./types";
import { FirestoreUsageStore } from "./usage";

/**
 * Gemini API 키. 앱·Git 에 두지 않는다.
 * 에뮬레이터: functions/.secret.local 의 GEMINI_API_KEY (Git 제외)
 * 배포: firebase functions:secrets:set GEMINI_API_KEY (Blaze 필요)
 */
export const GEMINI_API_KEY = defineSecret("GEMINI_API_KEY");

/**
 * 비밀값 앞뒤의 BOM·공백·줄바꿈(CR/LF)을 지운다. 메모장 파일(--data-file)로 등록하면 끝에 CRLF 가 붙는다.
 * 지운 뒤에도 키에 쓸 수 없는 문자가 남으면 값은 로그에 남기지 않고 AI_UNAVAILABLE 로 처리한다.
 */
export function normalizeApiKey(raw: string | undefined): string {
  const key = (raw ?? "").replace(/^﻿/, "").trim();
  if (key && /[^A-Za-z0-9._-]/.test(key)) {
    throw new AiError("AI_UNAVAILABLE", "GEMINI_API_KEY contains invalid characters", { retryable: false });
  }
  return key;
}

let cached: AiDeps | undefined;
let client: ModelClient | undefined;

/** 함수 인스턴스마다 한 번만 만든다. 테스트는 AiDeps 를 직접 만들어 runAiTask 에 넘긴다. */
export function defaultDeps(): AiDeps {
  if (cached) return cached;
  // 배포 환경에서는 firebase-functions 가 ID 토큰 검증용으로 이름 있는 Admin 앱을 먼저 만든다.
  // 그래서 "앱이 하나라도 있는가"가 아니라 기본 앱([DEFAULT])이 있는지로 판단해야 한다.
  const app = getApps().find((existing) => existing.name === "[DEFAULT]") ?? initializeApp();
  const db = getFirestore(app);
  cached = {
    mode: resolveMode(),
    model: process.env.GEMINI_MODEL?.trim() ?? "",
    modelClient: () => {
      if (client) return client;
      const apiKey = normalizeApiKey(GEMINI_API_KEY.value());
      if (!apiKey) throw new AiError("AI_UNAVAILABLE", "GEMINI_API_KEY is not set");
      client = new GenAiModelClient(apiKey);
      return client;
    },
    usage: new FirestoreUsageStore(db),
    matches: new FirestoreMatchAccess(db),
    now: () => new Date(),
    sleep: (ms) => new Promise((resolve) => setTimeout(resolve, ms)),
    allowedEmailSuffixes: parseAllowedEmailSuffixes(process.env[ALLOWED_EMAIL_SUFFIXES_ENV]),
    readImage: async (path, maxBytes) => {
      const file = getStorage(app).bucket().file(path);
      const [metadata] = await file.getMetadata();
      const size = Number(metadata.size ?? 0);
      const contentType = metadata.contentType ?? undefined;
      if (!Number.isFinite(size) || size > maxBytes) return { contentType, size };
      const [bytes] = await file.download();
      return { contentType, size: bytes.length, bytes };
    },
  };
  return cached;
}
