import { describe, expect, it } from "vitest";
import { checkSchoolEmail, isAllowedSchoolEmail, parseAllowedEmailSuffixes } from "../src/ai/emailPolicy";

const SUFFIXES = parseAllowedEmailSuffixes(".ac.kr,.edu");

describe("parseAllowedEmailSuffixes", () => {
  it("쉼표로 나누고 공백·대소문자를 정리한다", () => {
    expect(parseAllowedEmailSuffixes(" .AC.KR , .Edu ,, ")).toEqual([".ac.kr", ".edu"]);
  });

  it("점이 없으면 붙여 'evilac.kr' 같은 도메인이 통과하지 않게 한다", () => {
    const suffixes = parseAllowedEmailSuffixes("ac.kr");
    expect(suffixes).toEqual([".ac.kr"]);
    expect(isAllowedSchoolEmail("a@evilac.kr", suffixes)).toBe(false);
  });

  it("비어 있거나 형식이 잘못되면 빈 목록", () => {
    expect(parseAllowedEmailSuffixes(undefined)).toEqual([]);
    expect(parseAllowedEmailSuffixes("")).toEqual([]);
    expect(parseAllowedEmailSuffixes(" , ")).toEqual([]);
    expect(parseAllowedEmailSuffixes(".,..,.a..b,@x.com")).toEqual([]);
  });
});

describe("isAllowedSchoolEmail (앱 정규식과 같은 판단)", () => {
  it.each([
    "student@kookmin.ac.kr",
    "Student@Kookmin.AC.KR",
    "a.b+tag_1%x-y@mail.kookmin.ac.kr",
    "someone@mit.edu",
    "SOMEONE@CS.MIT.EDU",
  ])("허용: %s", (email) => {
    expect(isAllowedSchoolEmail(email, SUFFIXES)).toBe(true);
  });

  it.each([
    // 접미사 속임수
    "student@kookmin.ac.kr.evil.com",
    "student@kookmin.edu.evil.com",
    "kookmin.ac.kr@evil.com",
    "student@evil.com?.ac.kr",
    "student@evil.com#.ac.kr",
    "student@evilac.kr",
    "student@evil.ac.kr.",
    // 접미사 자체만 있는 도메인
    "student@ac.kr",
    "student@.ac.kr",
    "student@edu",
    // 형식 오류
    "@kookmin.ac.kr",
    "student",
    "a@b@kookmin.ac.kr",
    "stu dent@kookmin.ac.kr",
    "student@kookmin_x.ac.kr",
    "student@gmail.com",
    "",
  ])("거부: %s", (email) => {
    expect(isAllowedSchoolEmail(email, SUFFIXES)).toBe(false);
  });

  it("허용 목록이 비면 모두 거부", () => {
    expect(isAllowedSchoolEmail("student@kookmin.ac.kr", [])).toBe(false);
  });
});

describe("checkSchoolEmail", () => {
  it("인증된 학교 이메일이면 통과", () => {
    expect(checkSchoolEmail({ email: "s@kookmin.ac.kr", email_verified: true }, SUFFIXES)).toBeNull();
  });

  it("설정이 비면 NO_ALLOWED_SUFFIXES", () => {
    expect(checkSchoolEmail({ email: "s@kookmin.ac.kr", email_verified: true }, [])).toBe("NO_ALLOWED_SUFFIXES");
  });

  it("이메일 없음 / 미인증 / 허용 밖 도메인", () => {
    expect(checkSchoolEmail({}, SUFFIXES)).toBe("EMAIL_MISSING");
    expect(checkSchoolEmail(undefined, SUFFIXES)).toBe("EMAIL_MISSING");
    expect(checkSchoolEmail({ email: "s@kookmin.ac.kr" }, SUFFIXES)).toBe("EMAIL_NOT_VERIFIED");
    expect(checkSchoolEmail({ email: "s@kookmin.ac.kr", email_verified: false }, SUFFIXES)).toBe("EMAIL_NOT_VERIFIED");
    // 문자열 "true" 는 인증으로 보지 않는다
    expect(checkSchoolEmail({ email: "s@kookmin.ac.kr", email_verified: "true" }, SUFFIXES)).toBe("EMAIL_NOT_VERIFIED");
    expect(checkSchoolEmail({ email: "s@kookmin.ac.kr.evil.com", email_verified: true }, SUFFIXES)).toBe(
      "EMAIL_DOMAIN_NOT_ALLOWED",
    );
  });
});
