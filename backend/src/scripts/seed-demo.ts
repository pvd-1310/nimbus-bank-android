import { runtime } from "../infrastructure/runtime.js";
import { check } from "../shared/errors.js";
import { normalizeSearchText } from "../shared/search.js";
import { preserveExisting, preserveSeedAccount, seedClaims } from "./seed-preserve.js";
const r = runtime();
const ns = process.env.DEMO_NAMESPACE ?? "local_demo";
check(/^[a-z0-9_]{3,30}$/.test(ns), "INVALID_NAMESPACE");
const apply = process.argv.includes("--apply");
const permissions = [
  "CUSTOMER_ADMIN",
  "EKYC_REVIEW",
  "RATE_UPDATE",
  "AUDIT_READ",
];
type SeedIdentity = {
  id: string;
  email: string;
  role: "CUSTOMER" | "OFFICER";
  fullName: string;
  phoneMasked: string;
  customerNo?: string;
  address?: string;
  staffCode?: string;
};
const identities: SeedIdentity[] = [
  {
    id: ns + "_customer",
    email: ns + ".customer@example.com",
    role: "CUSTOMER",
    fullName: "Nguyễn Minh Anh",
    phoneMasked: "•••• 4821",
    customerNo: "KH-028451",
    address: "18 Nguyễn Văn Linh, phường Tân Phong, TP. Hồ Chí Minh",
  },
  {
    id: ns + "_recipient",
    email: ns + ".recipient@example.com",
    role: "CUSTOMER",
    fullName: "Trần Quốc Bảo",
    phoneMasked: "•••• 7364",
    customerNo: "KH-028452",
    address: "42 Lê Văn Sỹ, phường 13, TP. Hồ Chí Minh",
  },
  {
    id: ns + "_officer",
    email: ns + ".officer@example.com",
    role: "OFFICER",
    fullName: "Lê Thu Hà",
    phoneMasked: "•••• 1958",
    staffCode: "NV-01427",
  },
];
try {
  console.log(
    JSON.stringify(
      {
        project: r.cfg.projectId,
        mode: r.cfg.mode,
        namespace: ns,
        apply,
        identities: identities.map(({ id, email, role }) => ({
          id,
          email,
          role,
        })),
      },
      null,
      2,
    ),
  );
  if (!apply)
    console.log(
      "Dry run only. Use --apply after checking the project and namespace. Login identities and mutable account/payment state are retained; descriptive fixture data in this namespace may be refreshed.",
    );
  else {
    check(
      r.cfg.mode === "emulator" ||
        process.env.CONFIRM_SEED_PROJECT === r.cfg.projectId,
      "CONFIRM_PROJECT_REQUIRED",
    );
    const password = process.env.DEMO_PASSWORD;
    check(password && password.length >= 12, "DEMO_PASSWORD_REQUIRED");
    for (const u of identities) {
      let authUser;
      try {
        authUser = await r.auth.getUser(u.id);
      } catch (e) {
        check(
          (e as { code?: string }).code === "auth/user-not-found",
          "AUTH_LOOKUP_FAILED",
        );
      }
      if (!authUser) {
        await r.auth.createUser({ uid: u.id, email: u.email, password });
        await r.auth.setCustomUserClaims(u.id, seedClaims(undefined, u.role, u.role === "OFFICER" ? permissions : []));
      } else if (!authUser.customClaims?.role || !Array.isArray(authUser.customClaims.permissions)) {
        await r.auth.setCustomUserClaims(u.id, seedClaims(
          authUser.customClaims,
          u.role,
          u.role === "OFFICER" ? permissions : [],
        ));
      }
      await r.bank.store.run(async (t) => {
        const old = await t.get("users/" + u.id);
        check(!old || old.demoNamespace === ns, "SEED_TARGET_NOT_DEMO");
        t.set("users/" + u.id, preserveExisting(old, {
          uid: u.id,
          role: u.role,
          fullName: u.fullName,
          emailMasked: u.email.replace(/^(.).*(@.*)$/, "$1***$2"),
          phoneMasked: u.phoneMasked,
          status: "ACTIVE",
          createdAt: Date.now(),
          active: true,
          demoNamespace: ns,
        }));
        const collection = u.role === "OFFICER" ? "officers" : "customers";
        const profile = await t.get(collection + "/" + u.id);
        check(!profile || profile.demoNamespace === ns, "SEED_TARGET_NOT_DEMO");
        t.set(collection + "/" + u.id, preserveExisting(profile, {
          uid: u.id,
          fullName: u.fullName,
          phoneMasked: u.phoneMasked,
          active: true,
          permissions: u.role === "OFFICER" ? permissions : [],
          kycStatus: profile?.kycStatus ?? "NOT_SUBMITTED",
          demoNamespace: ns,
          uidRef: u.id,
          ...(u.role === "OFFICER"
            ? {
                staffCode: u.staffCode,
                branchId: ns + "_branch",
              }
            : {
                customerNo: u.customerNo,
                address: u.address,
                createdBy: "TRUSTED_SEED",
              }),
        }));
      });
    }
    await r.bank.store.run(async (t) => {
      const upsertDemo = async (
        path: string,
        data: Record<string, unknown>,
        allowLegacyOwned = false,
      ) => {
        const old = await t.get(path);
        check(
          !old || old.demoNamespace === ns ||
            (allowLegacyOwned && old.demoNamespace === undefined && old.demo !== true),
          "SEED_TARGET_NOT_DEMO",
        );
        t.set(path, preserveExisting(old, {
          ...data,
          demo: true,
          demoNamespace: ns,
        }));
      };
      const refreshBill = async (path: string, data: Record<string, unknown>) => {
        const old = await t.get(path);
        check(!old || old.demoNamespace === ns, "SEED_TARGET_NOT_DEMO");
        t.set(path, preserveExisting(old, {
          ...data,
          status: "UNPAID",
          demo: true,
          demoNamespace: ns,
        }));
      };
      const ownerUid = identities[0].id;
      const seedAccount = async (id: string, data: Record<string, unknown>) => {
        const path = "accounts/" + id;
        const old = await t.get(path);
        check(!old || old.demoNamespace === ns, "SEED_TARGET_NOT_DEMO");
        const owner = data.type === "CHECKING" ? await t.get("users/" + String(data.ownerUid ?? "")) : undefined;
        const ownerNameSearch = data.type === "CHECKING" && owner?.fullName
          ? normalizeSearchText(String(owner.fullName))
          : undefined;
        const account = preserveSeedAccount(old, {
          ...data,
          status: "ACTIVE",
          version: 0,
          demo: true,
          demoNamespace: ns,
        }, ownerNameSearch);
        t.set(path, account);
      };
      for (const [type, balance] of [["CHECKING", 38642500], ["SAVINGS", 62500000], ["MORTGAGE", 742500000]] as const)
        await seedAccount(ns + "_" + type.toLowerCase(), {
          ownerUid,
          type,
          currency: "VND",
          status: "ACTIVE",
          availableMinor: balance,
          bookedMinor: balance,
          balanceMinor: balance,
        });
      await seedAccount(ns + "_recipient_checking", {
        ownerUid: identities[1].id,
        type: "CHECKING",
        currency: "VND",
        status: "ACTIVE",
        availableMinor: 12487500,
        bookedMinor: 12487500,
        balanceMinor: 12487500,
      });
      await upsertDemo("checkingDetails/" + ns + "_checking", {
        accountId: ns + "_checking",
        dailyLimitMinor: 100000000,
        overdraftPolicy: "NONE",
        cardAlias: "Minh Anh",
      });
      await upsertDemo("ratePolicies/" + ns + "_savings_v1", {
        productId: "savings_standard_vnd",
        accountProduct: "savings_standard_vnd",
        annualRateBasisPoints: 465,
        effectiveFrom: Date.now() - 90 * 86400000,
        version: 1,
        updatedBy: "TRUSTED_SEED",
      });
      const savingsAccount = await t.get("accounts/" + ns + "_savings");
      const savingsDetails = await t.get("savingsDetails/" + ns + "_savings");
      await upsertDemo("savingsDetails/" + ns + "_savings", {
        accountId: ns + "_savings",
        principalMinor: Number(savingsAccount?.version ?? 0) > 0
          ? (savingsDetails?.principalMinor ?? 62500000)
          : 62500000,
        productId: "savings_standard_vnd",
        ratePolicyVersion: ns + "_savings_v1",
        openedAt: Date.now() - 90 * 86400000,
        maturityAt: Date.now() + 275 * 86400000,
      });
      const mortgageAccount = await t.get("accounts/" + ns + "_mortgage");
      const mortgageDetails = await t.get("mortgageDetails/" + ns + "_mortgage");
      await upsertDemo("mortgageDetails/" + ns + "_mortgage", {
        accountId: ns + "_mortgage",
        loanPrincipalMinor: 850000000,
        outstandingMinor: Number(mortgageAccount?.version ?? 0) > 0
          ? (mortgageDetails?.outstandingMinor ?? 742500000)
          : 742500000,
        annualRateBasisPoints: 850,
        annualRate: 0.085,
        paymentFrequency: "MONTHLY",
        termMonths: 240,
      });
      for (const [serviceType, name, amountMinor] of [
        ["ELECTRICITY", "Hóa đơn điện sinh hoạt · EVN TP. Hồ Chí Minh", 936400],
        ["WATER", "Hóa đơn nước sinh hoạt · SAWACO", 284600],
        ["TOPUP", "Nạp tiền điện thoại trả trước", 100000],
        ["MOVIE", "Vé xem phim tiêu chuẩn · suất 19:30", 95000],
        ["HOTEL", "Phòng Superior · một đêm tại TP. Đà Lạt", 1250000],
        ["ECOMMERCE", "Bình giữ nhiệt inox 500 ml", 289000],
      ] as const)
        await upsertDemo("catalog/" + ns + "_" + serviceType.toLowerCase(), {
          serviceType, name, amountMinor, currency: "VND", active: true,
        });
      for (const [serviceType, name, amountMinor] of [
        ["MOVIE", "Vé CGV · phòng tiêu chuẩn · suất 19:30", 95000],
        ["HOTEL", "Phòng Superior · khách sạn Đà Lạt · một đêm", 1250000],
        ["ECOMMERCE", "Bình giữ nhiệt inox 500 ml · dung tích 500 ml", 289000],
      ] as const)
        await upsertDemo("catalog/" + ns + "_selection_" + serviceType.toLowerCase(), {
          serviceType, name, amountMinor, currency: "VND", active: true,
        });
      const flights = [
        {
          id: ns + "_flight",
          name: "TP. Hồ Chí Minh – Hà Nội · VN 214",
          airline: "Vietnam Airlines", flightNumber: "VN 214", aircraft: "Airbus A350",
          origin: "SGN", destination: "HAN", originCity: "TP. Hồ Chí Minh", destinationCity: "Hà Nội",
          departureTime: "08:30", arrivalTime: "10:45", duration: "2h 15m", fareClass: "Phổ thông",
          amountMinor: 1890000,
        },
        {
          id: ns + "_selection_flight",
          name: "TP. Hồ Chí Minh – Hà Nội · QH 220",
          airline: "Bamboo Airways", flightNumber: "QH 220", aircraft: "Boeing 787",
          origin: "SGN", destination: "HAN", originCity: "TP. Hồ Chí Minh", destinationCity: "Hà Nội",
          departureTime: "11:15", arrivalTime: "13:30", duration: "2h 15m", fareClass: "Phổ thông",
          amountMinor: 1650000,
        },
        {
          id: ns + "_selection_flight_vietjet",
          name: "TP. Hồ Chí Minh – Hà Nội · VJ 132",
          airline: "Vietjet Air", flightNumber: "VJ 132", aircraft: "Airbus A321",
          origin: "SGN", destination: "HAN", originCity: "TP. Hồ Chí Minh", destinationCity: "Hà Nội",
          departureTime: "14:00", arrivalTime: "16:15", duration: "2h 15m", fareClass: "Phổ thông",
          amountMinor: 1420000,
        },
      ] as const;
      for (const flight of flights) {
        const { id, ...data } = flight;
        await upsertDemo("catalog/" + id, {
          serviceType: "FLIGHT", ...data, currency: "VND", active: true,
        });
      }
      for (const [network, carrierName] of [["A", "Viettel"], ["B", "MobiFone"], ["C", "VinaPhone"]] as const)
        for (const amountMinor of [20000, 50000, 100000, 200000, 500000, 1000000])
          await upsertDemo("catalog/" + ns + "_topup_" + network + "_" + amountMinor, {
            serviceType: "TOPUP", name: carrierName, network,
            amountMinor, currency: "VND", active: true,
          });
      const now = new Date();
      const billingPeriod = "Tháng " + String(now.getMonth() + 1).padStart(2, "0") + "/" + now.getFullYear();
      await refreshBill("bills/" + ns + "_water", {
        ownerUid, serviceType: "WATER", currency: "VND",
        provider: "Tổng công ty Cấp nước Sài Gòn (SAWACO)", billingPeriod,
        invoiceRef: "SAWACO-HCM-" + new Date().getFullYear() + "-" + String(new Date().getMonth() + 1).padStart(2, "0") + "-193846",
        amountMinor: 284600, dueAt: Date.now() + 14 * 86_400_000,
        previousReading: "1.246", currentReading: "1.264", consumption: 18, consumptionUnit: "m³",
      });
      await refreshBill("bills/" + ns + "_electricity", {
        ownerUid, serviceType: "ELECTRICITY", currency: "VND",
        provider: "Tổng công ty Điện lực TP. Hồ Chí Minh (EVNHCMC)", billingPeriod,
        invoiceRef: "EVNHCMC-" + new Date().getFullYear() + "-" + String(new Date().getMonth() + 1).padStart(2, "0") + "-482731",
        amountMinor: 936400,
        previousReading: "4.820", currentReading: "5.145", consumption: 325, consumptionUnit: "kWh",
        dueAt: Date.now() + 4 * 86_400_000,
      });
      const branchPath = "bankBranches/" + ns + "_branch";
      const oldBranch = await t.get(branchPath);
      const isLegacySeedBranch = oldBranch?.branchId === ns + "_branch" &&
        oldBranch.name === "Chi nhánh demo Tân Phong";
      await upsertDemo(branchPath, {
        name: "Ngân hàng Nimbus · Phòng giao dịch Phú Mỹ Hưng",
        address: "Số 12 Nguyễn Lương Bằng, phường Tân Phú, TP. Hồ Chí Minh",
        latitude: 10.7295,
        longitude: 106.7216,
        active: true,
        openingHours: "Thứ Hai–Thứ Sáu, 08:00–17:00",
      }, isLegacySeedBranch);
    });
    console.log(
      "Namespaced seed completed. Sign in with the DEMO_PASSWORD configured locally.",
    );
  }
} finally {
  await r.db.terminate();
}
