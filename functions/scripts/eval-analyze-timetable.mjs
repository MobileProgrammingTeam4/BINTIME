#!/usr/bin/env node
// BT-048 시간표 인식 평가 스크립트(수동 실행, CI 제외).
//
// 앱과 같은 요청(analyzeTimetableTask.buildRequest, PROMPT, typesOnly 스키마)을 이미지 한 장씩 Gemini 에 직접 보내고,
// 응답을 서버와 같은 zod output 스키마와 verify 에 통과시킨 뒤 정답 fixture 와 비교한다.
// 서버 게이트웨이(gemini.ts·gateway.ts)는 쓰지 않으므로 하루 횟수 차감·재시도는 없다(실패는 그대로 기록).
//
// 사전 준비: cd functions && npm run build   (lib/ 의 컴파일 결과를 불러온다)
// API 키: 환경 변수 GEMINI_API_KEY 에서만 읽는다. 출력·저장하지 않는다.
// 사용법: docs/BT-048.md "평가 스크립트" 절.

import { createHash } from "node:crypto";
import { existsSync, mkdirSync, readFileSync, readdirSync, statSync, writeFileSync } from "node:fs";
import { createRequire } from "node:module";
import { dirname, join, relative, resolve, isAbsolute } from "node:path";
import { fileURLToPath } from "node:url";
import { parseArgs } from "node:util";
import { aggregate, formatSummary, imageKey, scoreImage } from "./eval-scoring.mjs";

const here = dirname(fileURLToPath(import.meta.url));
const functionsDir = resolve(here, "..");
const repoRoot = resolve(functionsDir, "..");

const USAGE = `사용법:
  node scripts/eval-analyze-timetable.mjs --images <이미지 폴더(repo 밖)> --out <결과 폴더(repo 밖)> --model <모델 ID> [옵션]

필수
  --images      IMG_xxxx.jpeg 파일이 있는 폴더(repo 밖). 파일명으로 fixture 항목과 연결한다.
  --out         결과 JSON 을 저장할 폴더(repo 밖). 없으면 만든다.
  --model       Gemini 모델 ID (예: gemini-3.5-flash-lite)

옵션
  --truth       정답 fixture (기본 functions/test/fixtures/timetable-truth.json)
  --thinking    default | off | minimal | low | medium | high | <thinkingBudget 정수>  (기본 default: 설정 안 보냄)
  --resolution  default | LOW | MEDIUM | HIGH | ULTRA  (기본 default: 설정 안 보냄. ULTRA 는 이미지 part 단위로 보냄)
  --repeat      같은 이미지를 몇 번 보낼지 (기본 1)
  --only        쉼표로 구분한 이미지 키만 (예: IMG_3464,IMG_3465)
  --yes         외부 API 전송에 동의. 없으면 대상만 보여 주고 끝낸다.

환경 변수
  GEMINI_API_KEY  Gemini API 키(필수). 출력·저장하지 않는다.`;

function fail(message, code = 1) {
  console.error(message);
  process.exit(code);
}

function insideRepo(path) {
  const rel = relative(repoRoot, path);
  return rel === "" || (!rel.startsWith("..") && !isAbsolute(rel));
}

/** --thinking 값 → thinkingConfig(없으면 undefined). */
export function thinkingConfig(value) {
  const v = String(value ?? "default").trim().toLowerCase();
  if (v === "default") return undefined;
  if (v === "off") return { thinkingBudget: 0 };
  if (["minimal", "low", "medium", "high"].includes(v)) return { thinkingLevel: v.toUpperCase() };
  if (/^-?\d+$/.test(v)) return { thinkingBudget: Number(v) };
  throw new Error(`--thinking 값이 올바르지 않습니다: ${value}`);
}

/** --resolution 값 → { config?: MediaResolution, part?: PartMediaResolutionLevel }. */
export function resolutionOption(value) {
  const v = String(value ?? "default").trim().toUpperCase();
  if (v === "DEFAULT") return {};
  if (["LOW", "MEDIUM", "HIGH"].includes(v)) return { config: `MEDIA_RESOLUTION_${v}` };
  if (v === "ULTRA") return { part: "MEDIA_RESOLUTION_ULTRA_HIGH" };
  throw new Error(`--resolution 값이 올바르지 않습니다: ${value}`);
}

function parse() {
  let parsed;
  try {
    parsed = parseArgs({
      options: {
        images: { type: "string" },
        truth: { type: "string", default: join(functionsDir, "test", "fixtures", "timetable-truth.json") },
        out: { type: "string" },
        model: { type: "string" },
        thinking: { type: "string", default: "default" },
        resolution: { type: "string", default: "default" },
        repeat: { type: "string", default: "1" },
        only: { type: "string" },
        yes: { type: "boolean", default: false },
        help: { type: "boolean", default: false },
      },
      strict: true,
    }).values;
  } catch (error) {
    fail(`${error.message}\n\n${USAGE}`);
  }
  if (parsed.help) fail(USAGE, 0);
  if (!parsed.images || !parsed.out || !parsed.model) fail(`--images, --out, --model 은 필수입니다.\n\n${USAGE}`);
  const repeat = Number(parsed.repeat);
  if (!Number.isInteger(repeat) || repeat < 1 || repeat > 20) fail("--repeat 는 1~20 정수입니다.");
  let thinking;
  let resolution;
  try {
    thinking = thinkingConfig(parsed.thinking);
    resolution = resolutionOption(parsed.resolution);
  } catch (error) {
    fail(error.message);
  }
  const images = resolve(parsed.images);
  const out = resolve(parsed.out);
  if (!existsSync(images) || !statSync(images).isDirectory()) fail(`이미지 폴더가 없습니다: ${images}`);
  if (insideRepo(images)) fail("이미지 폴더는 repo 밖이어야 합니다(이미지를 repo 에 두지 않는다).");
  if (insideRepo(out)) fail("결과 폴더는 repo 밖이어야 합니다(결과에 실제 과목명이 들어갈 수 있다).");
  return { ...parsed, images, out, repeat, thinkingConfig: thinking, resolutionOption: resolution };
}

/** fixture 와 폴더의 파일을 파일명(IMG_xxxx)으로 연결한다. fixture 순서를 따른다. */
function pairImages(truth, folder, only) {
  const files = new Map();
  for (const name of readdirSync(folder)) {
    const key = imageKey(name);
    if (key) files.set(key, join(folder, name));
  }
  const wanted = only ? new Set(only.split(",").map((s) => s.trim().toUpperCase()).filter(Boolean)) : null;
  const pairs = [];
  const missingFiles = [];
  for (const entry of truth.images) {
    if (wanted && !wanted.has(entry.image)) continue;
    const file = files.get(entry.image);
    if (file) pairs.push({ ...entry, file });
    else missingFiles.push(entry.image);
  }
  const unknownFiles = [...files.keys()].filter((key) => !truth.images.some((e) => e.image === key));
  return { pairs, missingFiles, unknownFiles };
}

const EVAL_UID = "eval";
const evalPath = `timetableImages/${EVAL_UID}/eval-batch/0.jpg`;

/** 서버 buildRequest 를 그대로 쓰기 위한 최소 deps. 로컬 파일을 Storage 처럼 돌려준다. */
function localDeps(bytes) {
  return {
    mode: "live",
    readImage: async (_path, maxBytes) => {
      const isJpeg = bytes.length >= 3 && bytes[0] === 0xff && bytes[1] === 0xd8 && bytes[2] === 0xff;
      return { contentType: isJpeg ? "image/jpeg" : "application/octet-stream", size: bytes.length,
        bytes: bytes.length <= maxBytes ? bytes : undefined };
    },
  };
}

function sanitize(message, secret) {
  const text = String(message ?? "");
  return secret ? text.split(secret).join("[KEY]") : text;
}

export async function callOnce({ ai, task, toGeminiSchema, options, bytes, secret }) {
  const input = { imagePaths: [evalPath] };
  const deps = localDeps(bytes);
  const request = await task.buildRequest(input, { uid: EVAL_UID, requestId: `eval-${Date.now()}` }, deps);
  const contents = structuredClone(request.contents);
  if (options.resolutionOption.part) {
    for (const content of contents) {
      for (const part of content.parts) if (part.inlineData) part.mediaResolution = { level: options.resolutionOption.part };
    }
  }
  const config = {
    systemInstruction: request.systemInstruction,
    temperature: request.temperature,
    maxOutputTokens: request.maxOutputTokens,
    responseMimeType: "application/json",
    responseJsonSchema: toGeminiSchema(task.output, task.geminiSchema),
    httpOptions: { timeout: task.timeoutMs },
  };
  if (options.thinkingConfig) config.thinkingConfig = options.thinkingConfig;
  if (options.resolutionOption.config) config.mediaResolution = options.resolutionOption.config;

  const started = performance.now();
  const record = {};
  try {
    const response = await ai.models.generateContent({ model: options.model, contents, config });
    record.seconds = Math.round((performance.now() - started) / 100) / 10;
    record.modelVersion = response.modelVersion;
    record.promptTokens = response.usageMetadata?.promptTokenCount;
    record.outputTokens = response.usageMetadata?.candidatesTokenCount;
    record.thoughtsTokens = response.usageMetadata?.thoughtsTokenCount;
    record.finishReason = response.candidates?.[0]?.finishReason;
    let json;
    try {
      json = JSON.parse(response.text ?? "");
    } catch {
      record.error = { kind: "INVALID_JSON", message: "not json" };
      return record;
    }
    const parsed = task.output.safeParse(json);
    if (!parsed.success) {
      record.error = { kind: "SCHEMA", message: parsed.error.issues.slice(0, 3).map((i) => `${i.path.join(".")}: ${i.message}`).join("; ") };
      record.raw = json;
      return record;
    }
    try {
      record.output = task.verify ? task.verify(parsed.data, input, deps) : parsed.data;
    } catch (error) {
      record.error = { kind: "VERIFY", message: sanitize(error?.message, secret) };
      record.raw = parsed.data;
    }
  } catch (error) {
    record.seconds = Math.round((performance.now() - started) / 100) / 10;
    record.error = { kind: "API", status: error?.status, message: sanitize(error?.message, secret).slice(0, 300) };
  }
  return record;
}

async function main() {
  const options = parse();
  const truth = JSON.parse(readFileSync(resolve(options.truth), "utf8"));
  const { pairs, missingFiles, unknownFiles } = pairImages(truth, options.images, options.only);
  if (missingFiles.length) console.log(`폴더에 없는 정답 항목(건너뜀): ${missingFiles.join(", ")}`);
  if (unknownFiles.length) console.log(`정답표에 없는 이미지(건너뜀): ${unknownFiles.join(", ")}`);
  if (pairs.length === 0) fail("평가할 이미지가 없습니다.");

  console.log(`대상: ${pairs.map((p) => p.image).join(", ")}`);
  console.log(`설정: 모델 ${options.model}, thinking ${options.thinking}, resolution ${options.resolution}, 반복 ${options.repeat}회`);
  console.log(`이미지 ${pairs.length}장을 외부 API(Google Gemini)로 보냅니다. 호출은 총 ${pairs.length * options.repeat}회입니다.`);
  if (!options.yes) {
    console.log("진행하려면 --yes 를 붙여 다시 실행하세요. 아무것도 보내지 않았습니다.");
    return;
  }
  const secret = process.env.GEMINI_API_KEY?.trim();
  if (!secret) fail("GEMINI_API_KEY 환경 변수가 없습니다. 키를 파일에 쓰지 말고 현재 셸에만 설정한 뒤 다시 실행하세요.");

  const require = createRequire(import.meta.url);
  const libTask = join(functionsDir, "lib", "ai", "tasks", "analyzeTimetable.js");
  if (!existsSync(libTask)) fail("lib/ 가 없습니다. 먼저 cd functions && npm run build 를 실행하세요.");
  const { analyzeTimetableTask: task, PROMPT } = require(libTask);
  const { toGeminiSchema } = require(join(functionsDir, "lib", "ai", "gateway.js"));
  const { GoogleGenAI } = await import("@google/genai");
  const ai = new GoogleGenAI({ apiKey: secret, httpOptions: { retryOptions: { attempts: 1 } } });

  const images = pairs.map((p) => ({ image: p.image, file: p.file, runs: [] }));
  for (let repeat = 1; repeat <= options.repeat; repeat++) {
    for (const [i, pair] of pairs.entries()) {
      const bytes = readFileSync(pair.file);
      process.stdout.write(`${pair.image} (반복 ${repeat}) 호출 중… `);
      const record = await callOnce({ ai, task, toGeminiSchema, options, bytes, secret });
      const run = { repeat, ...record };
      if (record.output) run.score = scoreImage(pair.courses, record.output.courses);
      images[i].runs.push(run);
      console.log(run.score ? `${record.seconds}초, 일치 ${run.score.matchedCount}/${run.score.truthCount}` : `실패 ${record.error?.kind}`);
    }
  }

  const total = aggregate(images.flatMap((image) => image.runs));
  const config = { model: options.model, thinking: options.thinking, resolution: options.resolution, repeat: options.repeat,
    truth: relative(repoRoot, resolve(options.truth)),
    promptSha256: createHash("sha256").update(PROMPT).digest("hex").slice(0, 16) };
  const report = { createdAt: new Date().toISOString(), config, images, total };
  console.log("");
  console.log(formatSummary(report));

  mkdirSync(options.out, { recursive: true });
  const stamp = report.createdAt.replace(/[:.]/g, "-");
  const outFile = join(options.out, `eval-analyze-timetable-${stamp}.json`);
  writeFileSync(outFile, JSON.stringify(report, null, 2), "utf8");
  writeFileSync(outFile.replace(/\.json$/, ".txt"), formatSummary(report) + "\n", "utf8");
  console.log(`\n결과 저장: ${outFile}`);
}

const invokedDirectly = process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url);
if (invokedDirectly) {
  main().catch((error) => fail(`평가 중 오류: ${sanitize(error?.message, process.env.GEMINI_API_KEY?.trim())}`));
}
