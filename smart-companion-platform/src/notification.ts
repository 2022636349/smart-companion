import { computed, shallowRef } from 'vue';

const fallback = '操作未完成，请稍后重试。';
export interface ErrorNotice { id: number; title: string; message: string; opener: HTMLElement | null }
const queue = shallowRef<ErrorNotice[]>([]);
let sequence = 0;
/** Host-only reactive state. Callers must check their request/session gate before notifying. */
export const currentError = computed(() => queue.value[0] ?? null);

function readable(value: string, limit: number): string {
  // Technical payloads and credentials are never useful in the user-facing dialog.
  if (/(?:bearer\s+\S+|\bsk-[\w-]+|\beyJ[\w-]+\.[\w-]+\.[\w-]+|(?:token|api[ _-]?key|access[ _-]?token|refresh[ _-]?token|authorization|password|secret|密码|密钥|令牌)\s*["']?\s*[:=：]|Traceback\s*\(|<html|<!doctype)/i.test(value)) return fallback;
  const message = value.split(/\r?\n\s*(?:at\s+|File\s+")/)[0]!.replace(/[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]/g, '').trim();
  if (!message) return fallback;
  return message.length > limit ? message.slice(0, limit) + '…' : message;
}

export function notifyError(error: unknown, title = '操作未完成', opener?: HTMLElement | null): void {
  const message = readable(typeof error === 'string' ? error : error instanceof Error ? error.message : '', 1200);
  const safeTitle = readable(title, 80);
  if (queue.value.some(item => item.title === safeTitle && item.message === message)) return;
  if (queue.value.length >= 3) return;
  queue.value = [...queue.value, { id: ++sequence, title: safeTitle, message,
    opener: opener !== undefined ? opener : typeof document !== 'undefined' && document.activeElement instanceof HTMLElement ? document.activeElement : null }];
}

export function dismissError(id: number): void {
  if (queue.value[0]?.id === id) queue.value = queue.value.slice(1);
}

export function clearErrors(): void { queue.value = []; }

