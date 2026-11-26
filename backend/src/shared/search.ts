export function normalizeSearchText(value: string) {
  return value.normalize("NFD").replace(/[\u0300-\u036f]/g, "")
    .replace(/[đĐ]/g, "d").trim().toLocaleLowerCase("vi-VN");
}

export function phoneSearchDigits(value: string) {
  if (!/^[+()\d\s-]+$/.test(value)) return "";
  return value.replace(/\D/g, "");
}

export function phoneSearchTokens(value: string) {
  const digits = phoneSearchDigits(value);
  if (!digits) return [];
  const variants = new Set([digits]);
  if (digits.startsWith("+84")) variants.add("0" + digits.slice(3));
  else if (digits.startsWith("84") && digits.length === 11) variants.add("0" + digits.slice(2));
  else if (digits.startsWith("0") && digits.length === 10) variants.add("84" + digits.slice(1));
  const tokens = new Set<string>();
  for (const variant of variants) {
    for (let start = 0; start < variant.length; start++) {
      for (let end = start + 1; end <= variant.length; end++) tokens.add(variant.slice(start, end));
    }
  }
  return [...tokens];
}
