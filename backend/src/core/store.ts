export type Doc = Record<string, any>;
export interface ListOptions {
  limit?: number;
  after?: string;
  from?: number;
  to?: number;
  prefix?: { field: string; value: string };
}
export interface Tx {
  get(path: string): Promise<Doc | undefined>;
  list(
    collection: string,
    where?: Record<string, unknown>,
    options?: ListOptions,
  ): Promise<Doc[]>;
  set(path: string, data: Doc): void;
}
export interface Store {
  run<T>(work: (tx: Tx) => Promise<T>): Promise<T>;
}
