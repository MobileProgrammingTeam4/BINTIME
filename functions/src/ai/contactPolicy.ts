/**
 * AI 입력·출력의 연락처 처리(BT-041). 공통 게이트웨이(E) 소유.
 * 쓰는 곳: 채팅 답장 추천(suggestChatReplies, E), 자기소개 초안(generateProfile, B·BT-029),
 * 매칭 평가 입력의 자기소개(matching/score.ts, A·BT-027).
 * 패턴을 바꾸면 세 곳의 입력·출력 검사가 함께 바뀐다(매칭 평가는 입력 해시가 바뀐 사용자만 다시 평가된다).
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
