import { describe, expect, it } from 'vitest';
import { blankSegments, canonicalMaterialId, materialActionLabel, materialKind, materialTitle, parseChoiceOptions, questionPresentation } from './materialPresentation';

describe('material identity and presentation', () => {
  it('uses original identity and removes revision hashes', () => {
    expect(canonicalMaterialId('Q003~ab123')).toBe('Q003');
    expect(canonicalMaterialId({ id: 'Q003~revision', originalId: 'Q003' })).toBe('Q003');
    expect(materialTitle({ id: 'Q003~revision' })).toBe('练习题 Q003');
  });
  it('honors official prefixes over conflicting metadata', () => {
    expect(materialKind({ id: 'Q003', type: '学习材料' })).toBe('question');
    for (const id of ['L01', 'B01~ab']) expect(materialKind({ id, type: '题目', questionType: '填空题' })).toBe('resource');
    expect(materialActionLabel('L01')).toBe('学习材料 L01');
    expect(materialActionLabel('B01')).toBe('教材 B01');
  });
  it('requires metadata for unknown IDs, without assuming a question', () => {
    expect(materialKind('custom-1')).toBe('resource');
    expect(materialKind({ id: 'custom-1', type: '题目' })).toBe('question');
    expect(materialKind({ id: 'custom-1', questionType: '选择题' })).toBe('question');
    expect(materialKind({ id: 'custom-1' })).toBe('resource');
    expect(questionPresentation('其他')).toBe('general');
    expect(questionPresentation('填空题')).toBe('blank');
    expect(questionPresentation('解答题')).toBe('solution');
  });
});

describe('choice parsing preserves source mathematical text', () => {
  it('splits official newline markers without changing exponents, signs, or relations', () => {
    const result = parseChoiceOptions('A. 3x与3x²\nB. 3x与-2x\nC. x+y与xy\nD. x²与y²');
    expect(result.parsed).toBe(true);
    expect(result.options).toEqual([{ label: 'A', text: '3x与3x²' }, { label: 'B', text: '3x与-2x' }, { label: 'C', text: 'x+y与xy' }, { label: 'D', text: 'x²与y²' }]);
  });
  it('accepts full-width and inline semicolon or whitespace delimiters', () => {
    expect(parseChoiceOptions('Ａ． x < 0；Ｂ． x ≥ 0 C、 x = -2 D） x²').options.map(option => option.text)).toEqual(['x < 0', 'x ≥ 0', 'x = -2', 'x²']);
  });
  it('preserves missing, nonsequential or otherwise ambiguous options as raw text', () => {
    for (const raw of ['', '只有选项内容', 'A. 一 C. 三', 'A. 一 B.', '说明 A. 一 B. 二']) {
      expect(parseChoiceOptions(raw)).toEqual({ options: [], raw, parsed: false });
    }
  });
  it('leaves HTML-like source as literal text for Vue interpolation, not executable markup', () => {
    const raw = 'A. <img src=x onerror=alert(1)>\nB. x < 2';
    expect(parseChoiceOptions(raw).options.map(option => option.text)).toEqual(['<img src=x onerror=alert(1)>', 'x < 2']);
    expect(blankSegments('<script>alert(1)</script>')).toEqual([{ text: '<script>alert(1)</script>', blank: false }]);
  });
  it('marks explicit blanks while preserving every original character and equation', () => {
    const raw = 'x + （  ） = 3，y=____；(x+3)×x';
    const parts = blankSegments(raw);
    expect(parts.map(part => part.text).join('')).toBe(raw);
    expect(parts.filter(part => part.blank).map(part => part.text)).toEqual(['（  ）', '____']);
    expect(blankSegments('每支笔x元，买3支另2元').some(part => part.blank)).toBe(false);
  });
});
