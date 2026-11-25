import { readFileSync, readdirSync } from "node:fs";
import { describe, expect, it } from "vitest";
import { isAllowedSchoolEmail, parseAllowedEmailSuffixes } from "../src/ai/emailPolicy";

/**
 * #135: Rules(isSchoolUser)는 Functions 환경 설정을 읽을 수 없어 학교 도메인을 직접 적는다.
 * firestore.rules·storage.rules·functions/.env.*·앱 정규식이 서로 어긋나지 않는지 확인한다.
 */
const root = new URL("../../", import.meta.url);
const read = (path: string) => readFileSync(new URL(path, root), "utf8");

/** Rules 파일의 isSchoolUser() 안 matches('...') 패턴. */
function rulesPattern(file: string): string {
  const body = read(file).match(/function isSchoolUser\(\)\s*\{([\s\S]*?)\n\s*\}/);
  expect(body, `${file}: isSchoolUser() 없음`).not.toBeNull();
  const pattern = body![1].match(/\.matches\('([^']+)'\)/);
  expect(pattern, `${file}: matches() 없음`).not.toBeNull();
  return pattern![1];
}

/** "(ac[.]kr|edu)" → [".ac.kr", ".edu"] */
function suffixesOf(pattern: string): string[] {
  const group = pattern.match(/\[\.\]\(([^)]+)\)\$$/);
  expect(group, `접미사 그룹을 찾지 못함: ${pattern}`).not.toBeNull();
  return group![1].split("|").map((s) => `.${s.replace(/\[\.\]/g, ".")}`).sort();
}

const firestorePattern = rulesPattern("firestore.rules");
const storagePattern = rulesPattern("storage.rules");
const ruleSuffixes = suffixesOf(firestorePattern);

describe("#135 학교 이메일 Rules 조건 일치", () => {
  it("firestore.rules 와 storage.rules 의 조건이 같다", () => {
    expect(storagePattern).toBe(firestorePattern);
  });

  it("functions/.env.* 의 ALLOWED_EMAIL_SUFFIXES 와 같은 도메인이다", () => {
    const envFiles = readdirSync(new URL("functions/", root)).filter((f) => f.startsWith(".env."));
    expect(envFiles.length).toBeGreaterThan(0);
    for (const file of envFiles) {
      const line = read(`functions/${file}`).match(/^ALLOWED_EMAIL_SUFFIXES=(.*)$/m);
      expect(line, `${file}: ALLOWED_EMAIL_SUFFIXES 없음`).not.toBeNull();
      expect(parseAllowedEmailSuffixes(line![1]).sort(), file).toEqual(ruleSuffixes);
    }
  });

  it("앱 정규식(EmailVerificationState.java)과 같은 도메인이다", () => {
    const java = read("app/src/main/java/com/team4/bintime/auth/EmailVerificationState.java");
    const group = java.match(/\\\\\.\(([^)]+)\)\$"/);
    expect(group).not.toBeNull();
    const appSuffixes = group![1].split("|").map((s) => `.${s.replace(/\\\\\./g, ".")}`).sort();
    expect(appSuffixes).toEqual(ruleSuffixes);
  });

  it("Rules 정규식은 서버 isAllowedSchoolEmail 과 같은 판단을 한다", () => {
    const rule = new RegExp(firestorePattern);
    const samples = [
      "student@school.ac.kr", "Student@School.AC.KR", "a.b+c@cs.uni.ac.kr", "x@mit.edu",
      "outside@example.net", "x@ac.kr", "x@.ac.kr", "x@edu", "x@school.ac.kr.evil.com",
      "x@schoolac.kr", "x@school.ac.krx", "x@school.edu.com", "a@b@school.ac.kr", "@school.ac.kr",
      "한글@school.ac.kr", "x@학교.ac.kr", "x!@school.ac.kr",
    ];
    for (const email of samples) {
      expect(rule.test(email.toLowerCase()), email).toBe(isAllowedSchoolEmail(email, ruleSuffixes));
    }
  });
});
