export class Fault extends Error {
  constructor(
    public code: string,
    public status = 400,
  ) {
    super(code);
  }
}
export function check(
  value: unknown,
  code: string,
  status = 400,
): asserts value {
  if (!value) throw new Fault(code, status);
}
export function minor(n: unknown): asserts n is number {
  check(
    typeof n === "number" && Number.isSafeInteger(n) && n > 0,
    "INVALID_AMOUNT",
  );
}
export function key(value: unknown): asserts value is string {
  check(
    typeof value === "string" && /^[A-Za-z0-9_-]{1,100}$/.test(value),
    "INVALID_ID",
  );
}
