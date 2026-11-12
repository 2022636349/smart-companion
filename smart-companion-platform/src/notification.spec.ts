import { afterEach, describe, expect, it } from 'vitest';
import { clearErrors, currentError, dismissError, notifyError } from './notification';
afterEach(clearErrors);
describe('global error notices', () => {
  it('retains an explicitly captured opener even when notification has no active DOM focus', () => {
    const opener = { isConnected: true } as HTMLElement;
    notifyError('失败', '操作未完成', opener);
    expect(currentError.value?.opener).toBe(opener);
    clearErrors();
    notifyError('失败', '操作未完成', null);
    expect(currentError.value?.opener).toBeNull();
  });
  it('reads Error messages and safely handles unknown values', () => {
    notifyError(new Error('读取失败，请重试。'), '读取失败');
    expect(currentError.value?.message).toBe('读取失败，请重试。');
    expect(currentError.value?.title).toBe('读取失败');
    clearErrors(); notifyError({ message: 'untrusted object' });
    expect(currentError.value?.message).toBe('操作未完成，请稍后重试。');
  });
  it('bounds long messages and strips stack frames', () => {
    notifyError('甲'.repeat(5000));
    expect(currentError.value?.message.length).toBe(1201);
    clearErrors(); notifyError(new Error('服务暂时不可用\n    at internal (private.ts:2:3)'));
    expect(currentError.value?.message).toBe('服务暂时不可用');
  });
  it.each(['api_key=private', 'Authorization: Bearer private', 'token=private', 'accessToken: private', 'sk-privatevalue', 'eyJabc.def.ghi', '密码：private'])('does not expose credentials: %s', secret => {
    notifyError(secret); expect(currentError.value?.message).toBe('操作未完成，请稍后重试。');
  });
  it('deduplicates active/queued errors and allows immediate retry after dismissal', () => {
    notifyError('同一失败'); const first = currentError.value!.id;
    for (let index = 0; index < 10; index++) notifyError('同一失败');
    dismissError(first); expect(currentError.value).toBeNull();
    notifyError('同一失败'); expect(currentError.value?.id).not.toBe(first);
  });
  it('caps pending notices at three and clears the entire queue', () => {
    ['一', '二', '三', '四'].forEach(message => notifyError(message));
    dismissError(currentError.value!.id); expect(currentError.value?.message).toBe('二');
    dismissError(currentError.value!.id); expect(currentError.value?.message).toBe('三');
    dismissError(currentError.value!.id); expect(currentError.value).toBeNull();
    notifyError('一'); notifyError('二'); clearErrors(); expect(currentError.value).toBeNull();
    notifyError('新会话'); expect(currentError.value?.message).toBe('新会话');
  });
});
