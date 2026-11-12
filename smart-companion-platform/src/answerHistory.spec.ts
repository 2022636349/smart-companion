import { describe, expect, it } from 'vitest';
import { choiceAnswerText, evidenceText, recordScore, submissionTime } from './answerHistory';
import { requestGate } from './experienceDisplay';
describe('作答历史原始证据', () => {
  it('区分待评分、未作答和真实零分', () => {
    expect(recordScore({status:'SCORED',score:0,maxScore:3})).toBe('已有评分 0/3');
    expect(recordScore({status:'PENDING',score:null,maxScore:3})).toBe('待评分');
    expect(recordScore({status:'UNANSWERED',score:null,maxScore:3})).toContain('未作答');
  });
  it('保留原文与缺失语义', () => { expect(evidenceText('  x=5\n过程 ')).toBe('  x=5\n过程 '); expect(evidenceText(null)).toBe('未提供'); expect(evidenceText('')).toBe('未提供'); expect(evidenceText(0)).toBe('0'); });
  it('时间缩写保留记录墙上时间，缺失不编造', () => { expect(submissionTime('2026-01-20T09:11:32+08:00')).toBe('2026-01-20 09:11'); expect(submissionTime(null)).toBe('未提供'); expect(submissionTime('未知时间')).toBe('未知时间'); });
  it('选项文本只作辅助，不重写或猜测答案', () => {
    const answer=' Ｂ ', options='A. x=1\nB. x=2';
    expect(choiceAnswerText(answer,options)).toBe('B：x=2'); expect(answer).toBe(' Ｂ ');
    expect(choiceAnswerText('x=2',options)).toBe(''); expect(choiceAnswerText('D',options)).toBe('');
    expect(choiceAnswerText('A','任意文本')).toBe('');
  });
  it('快切选择与关闭会使旧请求失效', () => { const gate=requestGate(); const first=gate.next(),next=gate.next();expect(gate.current(first)).toBe(false);expect(gate.current(next)).toBe(true);gate.next();expect(gate.current(next)).toBe(false); });
});
