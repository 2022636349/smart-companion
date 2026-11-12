import type { ImportDetail, Report, Summary } from './types';

interface Session { accessToken: string; name: string }
const key = 'wisdom-companion.session';
export const workspaceId = String(import.meta.env.VITE_COMPANION_WORKSPACE || '3');
const base = '/courses/' + encodeURIComponent(workspaceId) + '/companion/imports';

export function session(): Session | null {
  try { return JSON.parse(sessionStorage.getItem(key) || 'null'); }
  catch { return null; }
}
export function logout() { sessionStorage.removeItem(key); }

export async function request<T>(path: string, method = 'GET', body?: FormData | object): Promise<T> {
  const headers = new Headers();
  const current = session();
  if (current) headers.set('Authorization', 'Bearer ' + current.accessToken);
  if (method === 'POST') headers.set('Idempotency-Key', crypto.randomUUID());
  if (body && !(body instanceof FormData)) headers.set('Content-Type', 'application/json');
  const response = await fetch('/api' + path, {
    method, headers, body: body instanceof FormData ? body : body ? JSON.stringify(body) : undefined
  });
  const payload = await response.json();
  if (response.status === 401) logout();
  if (!response.ok) throw Object.assign(new Error(payload.message || '请求失败，请重试。'), { status: response.status });
  return payload.data as T;
}

function verify(detail: ImportDetail) {
  if (String(detail.summary.courseId) !== workspaceId ||
      detail.report && String(detail.report.courseId) !== workspaceId) {
    throw new Error('分析空间不一致，请重新读取数据集。');
  }
  return detail;
}

export const api = {
  async login(username: string, password: string) {
    const data = await request<{ user: { displayName: string; role: string }; tokens: { accessToken: string } }>(
      '/auth/login', 'POST', { username, password }
    );
    if (!['TEACHER', 'ADMIN'].includes(data.user.role.toUpperCase())) {
      throw new Error('此分析空间需要教师或管理员账号。');
    }
    const result = { accessToken: data.tokens.accessToken, name: data.user.displayName };
    sessionStorage.setItem(key, JSON.stringify(result));
    return result;
  },
  async list() {
    const data = await request<{ items: Summary[] }>(base);
    if (data.items.some(item => String(item.courseId) !== workspaceId)) throw new Error('数据集空间不一致。');
    return data.items;
  },
  async get(id: string) { return verify(await request<ImportDetail>(base + '/' + encodeURIComponent(id))); },
  async upload(file: File) {
    if (!/\.(xlsx|zip)$/i.test(file.name) || file.size > 100 * 1024 * 1024) {
      throw new Error('请选择不超过 100 MiB 的 XLSX 或 ZIP。');
    }
    const form = new FormData();
    form.append('file', file);
    return verify(await request<ImportDetail>(base, 'POST', form));
  },
  async confirm(detail: ImportDetail, sourceLabel: string, acknowledgeWarnings: boolean) {
    return verify(await request<ImportDetail>(base + '/' + detail.summary.id + '/confirm', 'POST', {
      sourceLabel, acknowledgeWarnings, sha256: detail.summary.sha256
    }));
  },
  async analyze(id: string) {
    return verify(await request<ImportDetail>(base + '/' + encodeURIComponent(id) + '/analyze', 'POST', {}));
  },
  async download(id: string) {
    const response = await fetch('/api' + base + '/' + encodeURIComponent(id) + '/report', {
      headers: { Authorization: 'Bearer ' + session()?.accessToken }
    });
    if (!response.ok) {
      const data = await response.json();
      throw new Error(data.message || '报告下载失败。');
    }
    const report = await response.json() as Report;
    if (String(report.courseId) !== workspaceId) throw new Error('报告空间不一致。');
    const url = URL.createObjectURL(new Blob([JSON.stringify(report, null, 2)], { type: 'application/json' }));
    const link = document.createElement('a');
    link.href = url;
    link.download = '智学引擎-学情报告-' + id + '.json';
    link.click();
    URL.revokeObjectURL(url);
  }
};
