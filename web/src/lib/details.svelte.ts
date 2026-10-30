import { api } from './api';
import type { InstanceDetails } from './types';

/**
 * Polls an instance's live details (inventory, pathfinder, tasks) while a panel that shows them is open.
 * Call {@link start} from an $effect and return its result, so polling stops when the panel goes away.
 */
export class DetailsPoller {
	details = $state<InstanceDetails | null>(null);
	error = $state<string | null>(null);
	private timer: ReturnType<typeof setTimeout> | undefined;
	private stopped = false;

	constructor(
		private readonly instanceId: string,
		private readonly intervalMs = 2000
	) {}

	start() {
		this.stopped = false;
		void this.tick();
		return () => {
			this.stopped = true;
			clearTimeout(this.timer);
		};
	}

	/** Fetches now, e.g. right after an action, instead of waiting for the next tick. */
	async refresh() {
		await this.tick();
	}

	// polls overlap when refresh() runs during a scheduled one; only the newest answer counts, and only the
	// newest schedules the next poll, so overlapping calls don't multiply into parallel loops
	private latest = 0;

	private async tick() {
		const request = ++this.latest;
		clearTimeout(this.timer);
		try {
			const details = await api.get<InstanceDetails>(`/instances/${this.instanceId}/details`);
			if (request !== this.latest) return;
			this.details = details;
			this.error = null;
		} catch (e) {
			if (request !== this.latest) return;
			this.error = e instanceof Error ? e.message : String(e);
		}
		// pause while the tab is hidden: no point hammering the bot
		if (!this.stopped) this.timer = setTimeout(() => this.tick(), document.hidden ? this.intervalMs * 5 : this.intervalMs);
	}
}
