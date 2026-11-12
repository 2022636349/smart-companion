export type MaterialLike = { id?: string; originalId?: string; type?: string; questionType?: string; [key: string]: unknown };
export function canonicalMaterialId(input: string | MaterialLike): string {
  return (typeof input === 'string' ? input : input.originalId || input.id || '').split('~')[0]!.trim();
}
export function materialKind(input: string | MaterialLike): 'question' | 'resource' {
  const id = canonicalMaterialId(input).toUpperCase();
  if (id.startsWith('Q')) return 'question';
  if (id.startsWith('L') || id.startsWith('B')) return 'resource';
  if (typeof input === 'string') return 'resource';
  if (['学习材料', '教材', '学习卡', 'resource'].includes(String(input.type || '').trim().toLowerCase())) return 'resource';
  return ['题目', 'question'].includes(String(input.type || '').trim().toLowerCase()) || Boolean(String(input.questionType || '').trim()) ? 'question' : 'resource';
}
export function materialTitle(input: string | MaterialLike): string {
  const id = canonicalMaterialId(input);
  const prefix = materialKind(input) === 'question' ? '练习题' : id.toUpperCase().startsWith('B') ? '教材' : '学习材料';
  return `${prefix}${id ? ` ${id}` : ''}`;
}
export function materialActionLabel(input: string | MaterialLike): string { return materialTitle(input); }
export function questionPresentation(type: unknown): 'choice' | 'blank' | 'solution' | 'general' {
  const text = String(type || '').toLowerCase();
  if (/选择|单选|多选|choice/.test(text)) return 'choice';
  if (/填空|blank/.test(text)) return 'blank';
  if (/解答|简答|证明|计算|solution|essay/.test(text)) return 'solution';
  return 'general';
}

/** Parse only explicit, sequential markers. Never infer or repair an option's mathematical text. */
export function parseChoiceOptions(raw: unknown): { options: { label: string; text: string }[]; raw: string; parsed: boolean } {
  const text = typeof raw === 'string' ? raw : '';
  const matches = [...text.matchAll(/(?:^|[\r\n;；]|[ \t]+)([A-HＡ-Ｈ])[.．、:：)）]\s*/gu)];
  const fallback = { options: [], raw: text, parsed: false };
  if (matches.length < 2 || text.slice(0, matches[0]!.index).trim()) return fallback;
  const options = matches.map((match, index) => {
    const code = match[1]!.charCodeAt(0);
    return { label: String.fromCharCode(code > 127 ? code - 0xfee0 : code), text: text.slice(match.index! + match[0].length, matches[index + 1]?.index ?? text.length).trim() };
  });
  if (options.some((option, index) => option.label !== String.fromCharCode(65 + index) || !option.text)) return fallback;
  return { options, raw: text, parsed: true };
}

/** Blank styling retains the exact original characters and equation order. */
export function blankSegments(raw: unknown): { text: string; blank: boolean }[] {
  return String(raw || '').split(/(_{2,}|＿{2,}|[（(][ \t\u3000]*[）)])/u).filter(Boolean).map(text => ({ text, blank: /^(_{2,}|＿{2,}|[（(][ \t\u3000]*[）)])$/u.test(text) }));
}
