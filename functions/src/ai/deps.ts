import { getApps, initializeApp } from "firebase-admin/app";
import { getDatabase, type Database } from "firebase-admin/database";
import { getFirestore } from "firebase-admin/firestore";
import { getStorage } from "firebase-admin/storage";
import { defineSecret } from "firebase-functions/params";
import { FirestoreMatchAccess } from "./access";
import { FirebaseChatContextAccess } from "./chatContext";
import { resolveMode } from "./config";
import { ALLOWED_EMAIL_SUFFIXES_ENV, parseAllowedEmailSuffixes } from "./emailPolicy";
import { AiError } from "./errors";
import { GenAiModelClient, type ModelClient } from "./gemini";
import { FirestorePlaceStore } from "./placeStore";
import type { AiDeps, StoredImage } from "./types";
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

/** readStorageImage 가 쓰는 Storage 파일의 최소 모양(테스트에서 가짜로 바꾼다). */
export interface StorageImageFile {
  getMetadata(): Promise<[{ size?: string | number | null; contentType?: string | null }, ...unknown[]]>;
  download(): Promise<[Buffer, ...unknown[]]>;
}

/**
 * AiDeps.readImage 실제 구현. 메타데이터 크기가 maxBytes 를 넘거나 크기를 모르면 내려받지 않는다.
 * 에뮬레이터인데 Storage 에뮬레이터 주소가 없으면 Admin SDK 가 운영 버킷을 읽게 되므로 파일을 열기 전에 거부한다.
 */
export async function readStorageImage(
  openFile: () => StorageImageFile,
  maxBytes: number,
  env: NodeJS.ProcessEnv = process.env,
): Promise<StoredImage> {
  if (env.FUNCTIONS_EMULATOR === "true" && !env.FIREBASE_STORAGE_EMULATOR_HOST) {
    throw new AiError("AI_UNAVAILABLE", "storage emulator required", { retryable: false });
  }
  const file = openFile();
  const [metadata] = await file.getMetadata();
  const rawSize = metadata.size;
  // 크기를 모르면 받지 않는다(0 으로 두면 상한 검사를 지나 내려받게 된다).
  const size = rawSize === undefined || rawSize === null || rawSize === "" ? Number.NaN : Number(rawSize);
  const contentType = metadata.contentType ?? undefined;
  if (!Number.isFinite(size) || size > maxBytes) return { contentType, size };
  const [bytes] = await file.download();
  return { contentType, size: bytes.length, bytes };
}

/**
 * RTDB 를 처음 쓸 때 연결한다(답장 추천만 쓰므로 다른 AI 함수는 RTDB 에 연결하지 않는다).
 * 에뮬레이터인데 RTDB 에뮬레이터 주소가 없으면 Admin SDK 가 운영 RTDB 를 읽게 되므로 연결 전에 거부한다.
 */
export function lazyDatabase(open: () => Database, env: NodeJS.ProcessEnv = process.env): () => Database {
  let database: Database | undefined;
  return () => {
    if (env.FUNCTIONS_EMULATOR === "true" && !env.FIREBASE_DATABASE_EMULATOR_HOST) {
      throw new AiError("AI_UNAVAILABLE", "database emulator required", { retryable: false });
    }
    database ??= open();
    return database;
  };
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
    taskModel: (name) => process.env[name],
    modelClient: () => {
      if (client) return client;
      const apiKey = normalizeApiKey(GEMINI_API_KEY.value());
      if (!apiKey) throw new AiError("AI_UNAVAILABLE", "GEMINI_API_KEY is not set");
      client = new GenAiModelClient(apiKey);
      return client;
    },
    usage: new FirestoreUsageStore(db),
    matches: new FirestoreMatchAccess(db),
    chatContext: new FirebaseChatContextAccess(db, lazyDatabase(() => getDatabase(app))),
    now: () => new Date(),
    sleep: (ms) => new Promise((resolve) => setTimeout(resolve, ms)),
    allowedEmailSuffixes: parseAllowedEmailSuffixes(process.env[ALLOWED_EMAIL_SUFFIXES_ENV]),
    readImage: (path, maxBytes) => readStorageImage(() => getStorage(app).bucket().file(path), maxBytes),
    places: new FirestorePlaceStore(db),
  };
  return cached;
}
