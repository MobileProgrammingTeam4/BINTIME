/**
 * 학교 이메일 검사(PR #80 리뷰 1번). 앱(auth/EmailVerificationState.java)의 정규식
 * `^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.(ac\.kr|edu)$`(대소문자 무시)과 같은 판단을 하되,
 * 허용 접미사는 코드에 두지 않고 Functions 환경 설정 ALLOWED_EMAIL_SUFFIXES 에서 읽는다(README 3.1).
 */

export const ALLOWED_EMAIL_SUFFIXES_ENV = "ALLOWED_EMAIL_SUFFIXES";

/** 거절 원인. 로그에만 남기고 앱에는 PERMISSION_DENIED 하나로 보낸다. 이메일 원문은 담지 않는다. */
export type EmailDenyCause = "NO_ALLOWED_SUFFIXES" | "EMAIL_MISSING" | "EMAIL_NOT_VERIFIED" | "EMAIL_DOMAIN_NOT_ALLOWED";

const LOCAL_PART = /^[a-z0-9._%+-]+$/;
const DOMAIN = /^[a-z0-9.-]+$/;
const SUFFIX = /^(\.[a-z0-9-]+)+$/;

/**
 * "ALLOWED_EMAIL_SUFFIXES=.ac.kr,.edu" 를 [".ac.kr", ".edu"] 로 바꾼다.
 * 소문자로 맞추고, 점으로 시작하지 않으면 붙인다("ac.kr" → ".ac.kr"; 점이 없으면 "evilac.kr" 이 통과하기 때문).
 * 형식이 잘못된 항목은 버린다. 결과가 비면 모든 이메일을 거부한다.
 */
export function parseAllowedEmailSuffixes(raw: string | undefined): string[] {
  const suffixes = (raw ?? "")
    .split(",")
    .map((item) => item.trim().toLowerCase())
    .filter((item) => item.length > 0)
    .map((item) => (item.startsWith(".") ? item : `.${item}`))
    .filter((item) => SUFFIX.test(item));
  return [...new Set(suffixes)];
}

/** 앱 정규식과 같은 판단. 도메인 끝이 접미사와 정확히 일치해야 하며, 접미사 앞에 한 글자 이상 있어야 한다. */
export function isAllowedSchoolEmail(email: string, suffixes: readonly string[]): boolean {
  const normalized = email.trim().toLowerCase();
  const at = normalized.indexOf("@");
  if (at <= 0 || at !== normalized.lastIndexOf("@")) return false;
  const local = normalized.slice(0, at);
  const domain = normalized.slice(at + 1);
  if (!LOCAL_PART.test(local) || !DOMAIN.test(domain)) return false;
  return suffixes.some((suffix) => domain.length > suffix.length && domain.endsWith(suffix));
}

/** Auth 토큰의 email·email_verified 로 판단한다. 통과하면 null, 아니면 거절 원인. */
export function checkSchoolEmail(
  token: { email?: unknown; email_verified?: unknown } | undefined,
  suffixes: readonly string[],
): EmailDenyCause | null {
  if (suffixes.length === 0) return "NO_ALLOWED_SUFFIXES";
  const email = token?.email;
  if (typeof email !== "string" || email.length === 0) return "EMAIL_MISSING";
  if (token?.email_verified !== true) return "EMAIL_NOT_VERIFIED";
  if (!isAllowedSchoolEmail(email, suffixes)) return "EMAIL_DOMAIN_NOT_ALLOWED";
  return null;
}
