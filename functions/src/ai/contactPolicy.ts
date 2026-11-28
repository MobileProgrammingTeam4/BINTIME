/**
 * AI 입력·출력의 연락처 처리(BT-041). 공통 게이트웨이(E) 소유.
 * 기본 패턴은 generateProfile(B, BT-029)의 CONTACT_SOURCE 와 같다. 채팅에서 흔한 점 구분 전화번호(010.1234.5678)와
 * http·www 없는 메신저·SNS 링크를 더 막는다.
 * generateProfile 을 이 파일로 옮기는 것은 B 와 합의한 뒤 별도로 한다(그 전까지 두 곳에 같은 기본 패턴이 있다).
 */
const CONTACT_SOURCE = [
  // 이메일
  String.raw`[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}`,
  // 전화번호처럼 숫자가 9개 이상 이어짐(공백·하이픈·점 허용). 시각(14:00)·학번 8자리는 걸리지 않는다.
  String.raw`(?:\d[\s.-]?){9,}`,
  // 링크
  String.raw`https?:\/\/\S*`,
  String.raw`www\.\S*`,
  // http·www 없이 쓰는 메신저·SNS 링크(오픈채팅 등). 앞뒤 경계로 chat.me·at.meeting 처럼 단어 중간이 걸리지 않게 한다.
  // 앞은 점이 아닌 [\w-]로만 막아 m.instagram.com 같은 서브도메인 링크는 계속 지운다(PR #127 리뷰).
  String.raw`(?<![\w-])(?:open\.kakao\.com|qr\.kakao\.com|pf\.kakao\.com|instagram\.com|instagr\.am|t\.me|discord\.gg|discord\.com\/invite)(?![\w-])(?:\/\S*)?`,
].join("|");

/** 연락처처럼 보이는 부분을 지우고 공백을 정리한다. 남는 글자가 없으면 빈 문자열. */
export function stripContacts(text: string): string {
  return text.replace(new RegExp(CONTACT_SOURCE, "gi"), " ").replace(/\s+/g, " ").trim();
}

/** 연락처처럼 보이는 부분이 있는지 */
export function containsContact(text: string): boolean {
  return new RegExp(CONTACT_SOURCE, "i").test(text);
}
