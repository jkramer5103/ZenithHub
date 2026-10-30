export class ApiError extends Error {
	constructor(
		readonly status: number,
		message: string
	) {
		super(message);
	}
}

let onUnauthorized: () => void = () => {};

/** Called when any request finds the session gone, e.g. after logging out in another tab. */
export function setUnauthorizedHandler(handler: () => void) {
	onUnauthorized = handler;
}

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
	const res = await fetch(`/api${path}`, {
		method,
		headers: body === undefined ? {} : { 'Content-Type': 'application/json' },
		body: body === undefined ? undefined : JSON.stringify(body),
		credentials: 'same-origin'
	});
	return read<T>(res, path);
}

async function read<T>(res: Response, path: string): Promise<T> {
	const text = await res.text();
	let data: unknown = null;
	try {
		data = text ? JSON.parse(text) : null;
	} catch {
		// non-JSON error page from a reverse proxy
	}
	if (!res.ok) {
		if (res.status === 401 && path !== '/login') onUnauthorized();
		const message = (data as { error?: string } | null)?.error ?? `Request failed (${res.status})`;
		throw new ApiError(res.status, message);
	}
	return data as T;
}

declare global {
	// requests app.html starts while the scripts are still downloading
	var zenithPrefetch: Record<string, Promise<Response | null>> | undefined;
}

/** GETs a path, using the response app.html already asked for on the first call. */
async function prefetched<T>(path: string): Promise<T> {
	const early = globalThis.zenithPrefetch?.[path];
	if (globalThis.zenithPrefetch) delete globalThis.zenithPrefetch[path];
	const res = early && (await early.catch(() => null));
	return res ? read<T>(res, path) : request<T>('GET', path);
}

export const api = {
	get: <T>(path: string) => request<T>('GET', path),
	prefetched,
	// mutations always send a JSON body: the manager rejects anything else
	post: <T>(path: string, body: unknown = {}) => request<T>('POST', path, body),
	patch: <T>(path: string, body: unknown) => request<T>('PATCH', path, body),
	put: <T>(path: string, body: unknown) => request<T>('PUT', path, body),
	del: <T>(path: string, body: unknown = {}) => request<T>('DELETE', path, body)
};

export function errorMessage(e: unknown): string {
	return e instanceof Error ? e.message : String(e);
}
