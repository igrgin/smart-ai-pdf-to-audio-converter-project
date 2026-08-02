export type Availability = "AVAILABLE" | "DEGRADED";

export interface PlatformStatus {
  apiVersion: string;
  build: {
    version: string;
    revision: string;
  };
  availability: {
    core: Availability;
    database: Availability;
  };
}

export async function fetchPlatformStatus(signal: AbortSignal): Promise<PlatformStatus> {
  const response = await fetch("/api/v1/platform/status", {
    headers: { Accept: "application/json" },
    signal
  });

  if (!response.ok) {
    throw new Error(`Platform status returned ${response.status}`);
  }

  return response.json() as Promise<PlatformStatus>;
}
