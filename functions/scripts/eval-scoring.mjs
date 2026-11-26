// BT-048 시간표 인식 평가: 채점·매칭·집계(순수 함수). eval-analyze-timetable.mjs 와 단위 테스트가 같이 쓴다.
// 외부 API·파일 접근 없음.

/** 앱 RecognitionReview.LOW_CONFIDENCE 와 같은 값. */
export const LOW_CONFIDENCE = 0.7;
/** endApprox 정답의 종료 시각 허용 오차(분). */
export const APPROX_END_TOLERANCE = 30;

/**
 * @typedef {{ name?: string, dayOfWeek: number, startMinute: number, endMinute: number, endApprox?: boolean }} TruthCourse
 * @typedef {{ name?: string, dayOfWeek: number, startMinute: number, endMinute: number, confidence?: number }} RecognizedCourse
 */

/** 파일명 → fixture 키. "IMG_3466.jpeg" → "IMG_3466". 대소문자 무시, IMG_숫자 형식이 아니면 null. */
export function imageKey(fileName) {
  const match = /^(img_\d+)\.(jpe?g)$/i.exec(String(fileName));
  return match ? match[1].toUpperCase() : null;
}

/** 정답 하나와 인식 하나가 일치하는지. 요일·시작·종료가 같아야 하고 endApprox 는 종료 ±30분 허용. */
export function isMatch(truth, recognized) {
  if (truth.dayOfWeek !== recognized.dayOfWeek || truth.startMinute !== recognized.startMinute) return false;
  if (truth.endApprox) return Math.abs(recognized.endMinute - truth.endMinute) <= APPROX_END_TOLERANCE;
  return truth.endMinute === recognized.endMinute;
}

const byTime = (a, b) => a.item.startMinute - b.item.startMinute || a.item.endMinute - b.item.endMinute || a.index - b.index;

/**
 * 한 이미지의 정답과 인식 결과를 대응시킨다. 수업명은 가명이라 쓰지 않는다.
 * 1) 일치(isMatch)하는 쌍을 먼저 묶는다(정답 순서대로, 아직 안 쓴 인식 중 첫 번째).
 * 2) 남은 것끼리 요일별로 시작 시각 순서대로 짝지어 어긋남(시작·종료 분 차이 = 인식 - 정답)을 잰다.
 * 3) 짝이 없는 정답은 누락(missing), 인식은 초과(extra).
 * @param {TruthCourse[]} truth
 * @param {RecognizedCourse[]} recognized
 */
export function matchCourses(truth, recognized) {
  const usedTruth = new Set();
  const usedRec = new Set();
  const matched = [];
  truth.forEach((t, ti) => {
    const ri = recognized.findIndex((r, i) => !usedRec.has(i) && isMatch(t, r));
    if (ri < 0) return;
    usedTruth.add(ti);
    usedRec.add(ri);
    matched.push({ truth: t, recognized: recognized[ri] });
  });

  const mismatched = [];
  const missing = [];
  const extra = [];
  const days = new Set([...truth.map((t) => t.dayOfWeek), ...recognized.map((r) => r.dayOfWeek)]);
  for (const day of [...days].sort((a, b) => a - b)) {
    const restTruth = truth.map((item, index) => ({ item, index })).filter((x) => !usedTruth.has(x.index) && x.item.dayOfWeek === day).sort(byTime);
    const restRec = recognized.map((item, index) => ({ item, index })).filter((x) => !usedRec.has(x.index) && x.item.dayOfWeek === day).sort(byTime);
    const pairs = Math.min(restTruth.length, restRec.length);
    for (let i = 0; i < pairs; i++) {
      const t = restTruth[i].item;
      const r = restRec[i].item;
      mismatched.push({
        truth: t,
        recognized: r,
        startDelta: r.startMinute - t.startMinute,
        endDelta: r.endMinute - t.endMinute,
      });
    }
    missing.push(...restTruth.slice(pairs).map((x) => x.item));
    extra.push(...restRec.slice(pairs).map((x) => x.item));
  }
  return { matched, mismatched, missing, extra };
}

/** 값별 개수(0 은 세지 않는다). 키는 문자열 "+30", "-60" 형식. */
export function deltaHistogram(values) {
  const histogram = {};
  for (const value of values) {
    if (value === 0) continue;
    const key = value > 0 ? `+${value}` : String(value);
    histogram[key] = (histogram[key] ?? 0) + 1;
  }
  return Object.fromEntries(Object.entries(histogram).sort((a, b) => Number(a[0]) - Number(b[0])));
}

const ratio = (part, whole) => (whole === 0 ? null : part / whole);
const isLow = (c) => typeof c?.confidence === "number" && c.confidence < LOW_CONFIDENCE;

/** 5분 단위가 아닌 시각 값(시작·종료 각각) 개수. */
export function countOffFiveMinute(recognized) {
  let count = 0;
  for (const r of recognized) {
    if (r.startMinute % 5 !== 0) count++;
    if (r.endMinute % 5 !== 0) count++;
  }
  return count;
}

/**
 * 한 이미지 채점.
 * - 틀린 수업 = 어긋난 인식(mismatched) + 초과 인식(extra).
 * - lowConfidenceAmongMatched: 맞은 수업 중 confidence < 0.7 비율(쓸데없는 '확인 필요').
 * - highConfidenceAmongWrong: 틀린 수업 중 confidence >= 0.7 비율('확인 필요'가 안 붙은 오답).
 */
export function scoreImage(truth, recognized) {
  const result = matchCourses(truth, recognized);
  const wrong = [...result.mismatched.map((m) => m.recognized), ...result.extra];
  const lowMatched = result.matched.filter((m) => isLow(m.recognized)).length;
  const highWrong = wrong.filter((r) => typeof r.confidence === "number" && !isLow(r)).length;
  return {
    truthCount: truth.length,
    recognizedCount: recognized.length,
    matchedCount: result.matched.length,
    mismatchedCount: result.mismatched.length,
    missingCount: result.missing.length,
    extraCount: result.extra.length,
    startDeltas: deltaHistogram(result.mismatched.map((m) => m.startDelta)),
    endDeltas: deltaHistogram(result.mismatched.map((m) => m.endDelta)),
    lowConfidenceAmongMatched: ratio(lowMatched, result.matched.length),
    lowConfidenceMatchedCount: lowMatched,
    highConfidenceAmongWrong: ratio(highWrong, wrong.length),
    highConfidenceWrongCount: highWrong,
    wrongCount: wrong.length,
    offFiveMinuteValues: countOffFiveMinute(recognized),
    mismatched: result.mismatched,
    missing: result.missing,
    extra: result.extra,
  };
}

function mergeHistogram(target, source) {
  for (const [key, count] of Object.entries(source)) target[key] = (target[key] ?? 0) + count;
}

/**
 * 여러 이미지(또는 반복) 결과 합계. 실패한 호출(score 없음)은 failedCalls 로만 센다.
 * @param {Array<{ score?: ReturnType<typeof scoreImage>, seconds?: number, promptTokens?: number, outputTokens?: number }>} runs
 */
export function aggregate(runs) {
  const total = {
    calls: runs.length,
    failedCalls: 0,
    truthCount: 0,
    recognizedCount: 0,
    matchedCount: 0,
    mismatchedCount: 0,
    missingCount: 0,
    extraCount: 0,
    wrongCount: 0,
    lowConfidenceMatchedCount: 0,
    highConfidenceWrongCount: 0,
    offFiveMinuteValues: 0,
    startDeltas: {},
    endDeltas: {},
    seconds: { total: 0, max: 0, mean: null },
    promptTokens: 0,
    outputTokens: 0,
  };
  let timed = 0;
  for (const run of runs) {
    if (typeof run.seconds === "number") {
      timed++;
      total.seconds.total += run.seconds;
      total.seconds.max = Math.max(total.seconds.max, run.seconds);
    }
    total.promptTokens += run.promptTokens ?? 0;
    total.outputTokens += run.outputTokens ?? 0;
    if (!run.score) {
      total.failedCalls++;
      continue;
    }
    const s = run.score;
    for (const key of ["truthCount", "recognizedCount", "matchedCount", "mismatchedCount", "missingCount", "extraCount",
      "wrongCount", "lowConfidenceMatchedCount", "highConfidenceWrongCount", "offFiveMinuteValues"]) {
      total[key] += s[key];
    }
    mergeHistogram(total.startDeltas, s.startDeltas);
    mergeHistogram(total.endDeltas, s.endDeltas);
  }
  total.startDeltas = deltaHistogram(Object.entries(total.startDeltas).flatMap(([k, n]) => Array(n).fill(Number(k))));
  total.endDeltas = deltaHistogram(Object.entries(total.endDeltas).flatMap(([k, n]) => Array(n).fill(Number(k))));
  total.seconds.total = Math.round(total.seconds.total * 10) / 10;
  total.seconds.mean = timed === 0 ? null : Math.round((total.seconds.total / timed) * 10) / 10;
  total.lowConfidenceAmongMatched = ratio(total.lowConfidenceMatchedCount, total.matchedCount);
  total.highConfidenceAmongWrong = ratio(total.highConfidenceWrongCount, total.wrongCount);
  return total;
}

const pct = (value) => (value === null ? "-" : `${Math.round(value * 1000) / 10}%`);
const hist = (h) => (Object.keys(h).length === 0 ? "없음" : Object.entries(h).map(([k, n]) => `${k}분 ${n}건`).join(", "));

/** 콘솔용 일반 텍스트 요약(표 문자 없음). */
export function formatSummary({ config, images, total }) {
  const lines = [];
  lines.push(`모델: ${config.model} / thinking: ${config.thinking} / resolution: ${config.resolution} / 반복: ${config.repeat}회`);
  lines.push("");
  for (const image of images) {
    for (const run of image.runs) {
      const head = `${image.image} (반복 ${run.repeat})`;
      if (!run.score) {
        lines.push(`${head}: 실패 ${run.error?.kind ?? "unknown"} ${run.error?.message ?? ""} / ${run.seconds ?? "-"}초`);
        continue;
      }
      const s = run.score;
      lines.push(`${head}: 정답/인식/일치 ${s.truthCount}/${s.recognizedCount}/${s.matchedCount}, ${run.seconds}초, 토큰 입력 ${run.promptTokens ?? "-"} 출력 ${run.outputTokens ?? "-"}`);
      lines.push(`  시작 어긋남: ${hist(s.startDeltas)} / 종료 어긋남: ${hist(s.endDeltas)} / 누락 ${s.missingCount} 초과 ${s.extraCount}`);
      lines.push(`  맞은 수업 중 confidence<0.7: ${pct(s.lowConfidenceAmongMatched)} / 틀린 수업 중 confidence>=0.7: ${pct(s.highConfidenceAmongWrong)} / 5분 단위 아닌 값: ${s.offFiveMinuteValues}`);
    }
  }
  lines.push("");
  lines.push(`합계: 정답 ${total.truthCount}, 인식 ${total.recognizedCount}, 일치 ${total.matchedCount}, 어긋남 ${total.mismatchedCount}, 누락 ${total.missingCount}, 초과 ${total.extraCount}, 실패한 호출 ${total.failedCalls}/${total.calls}`);
  lines.push(`시작 어긋남 분포: ${hist(total.startDeltas)}`);
  lines.push(`종료 어긋남 분포: ${hist(total.endDeltas)}`);
  lines.push(`호출 시간: 평균 ${total.seconds.mean ?? "-"}초, 최대 ${total.seconds.max}초 / 토큰: 입력 ${total.promptTokens}, 출력 ${total.outputTokens}`);
  lines.push(`맞은 수업 중 confidence<0.7: ${pct(total.lowConfidenceAmongMatched)} / 틀린 수업 중 confidence>=0.7: ${pct(total.highConfidenceAmongWrong)} / 5분 단위 아닌 값: ${total.offFiveMinuteValues}`);
  return lines.join("\n");
}
