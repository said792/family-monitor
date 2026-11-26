/** Canonical event vocabulary shared by device ingestion and the dashboard.
 * Prisma keeps Event.type as String for compatibility with existing databases.
 */
export const EVENT_TYPES = [
  "SEARCH", "BROWSER", "CALL", "APP", "WHATSAPP", "MESSENGER", "MEDIA",
] as const;

export type EventType = (typeof EVENT_TYPES)[number];
