export function humanize(value: string): string {
  return value.replaceAll("_", " ").toLowerCase().replace(/(^|\s)\p{L}/gu, (letter) => letter.toUpperCase());
}

export function formatTime(value: string): string {
  return new Intl.DateTimeFormat(undefined, {
    dateStyle: "medium",
    timeStyle: "short",
    timeZone: "UTC"
  }).format(new Date(value));
}
