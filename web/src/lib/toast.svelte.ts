import { errorMessage } from './api';

export interface Toast {
	id: number;
	kind: 'error' | 'success' | 'alert';
	title?: string;
	message: string;
}

let nextId = 1;

class Toasts {
	items = $state<Toast[]>([]);

	show(kind: Toast['kind'], message: string, title?: string) {
		const id = nextId++;
		this.items.push({ id, kind, message, title });
		// alerts are something the user asked to hear about: they stay a while
		setTimeout(() => this.dismiss(id), kind === 'alert' ? 20000 : kind === 'error' ? 6000 : 3000);
	}

	error(e: unknown) {
		this.show('error', errorMessage(e));
	}

	success(message: string) {
		this.show('success', message);
	}

	dismiss(id: number) {
		this.items = this.items.filter((t) => t.id !== id);
	}
}

export const toasts = new Toasts();

/** Runs an action, reporting failures as a toast. Returns whether it succeeded. */
export async function attempt(action: () => Promise<unknown>, success?: string): Promise<boolean> {
	try {
		await action();
		if (success) toasts.success(success);
		return true;
	} catch (e) {
		toasts.error(e);
		return false;
	}
}
