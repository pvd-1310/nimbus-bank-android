export const externalBanks = [
  { code: "VCB", name: "Vietcombank" },
  { code: "BIDV", name: "BIDV" },
  { code: "ICB", name: "VietinBank" },
  { code: "TCB", name: "Techcombank" },
  { code: "MB", name: "MB" },
] as const;

export const externalBankCodes = externalBanks.map((bank) => bank.code) as [
  (typeof externalBanks)[number]["code"],
  ...Array<(typeof externalBanks)[number]["code"]>,
];

export function externalBankName(code: string | undefined) {
  return externalBanks.find((bank) => bank.code === code)?.name;
}
