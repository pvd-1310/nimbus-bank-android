import { runtime } from "../infrastructure/runtime.js";
import { check } from "../shared/errors.js";
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
const identities = [
  {
    id: ns + "_customer",
    email: ns + ".customer@example.com",
    role: "CUSTOMER",
    fullName: "Khách hàng demo",
  },
  {
    id: ns + "_recipient",
    email: ns + ".recipient@example.com",
    role: "CUSTOMER",
    fullName: "Người nhận demo",
  },
  {
    id: ns + "_officer",
    email: ns + ".officer@example.com",
    role: "OFFICER",
    fullName: "Nhân viên demo",
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
      "Dry run only. Use --apply after checking the project and namespace. Existing records are preserved.",
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
      let exists = true;
      try {
        await r.auth.getUser(u.id);
      } catch (e) {
        check(
          (e as { code?: string }).code === "auth/user-not-found",
          "AUTH_LOOKUP_FAILED",
        );
        exists = false;
      }
      if (!exists)
        await r.auth.createUser({ uid: u.id, email: u.email, password });
      await r.auth.setCustomUserClaims(u.id, {
        role: u.role,
        permissions: u.role === "OFFICER" ? permissions : [],
      });
      await r.bank.store.run(async (t) => {
        const old = await t.get("users/" + u.id);
        check(!old || old.demoNamespace === ns, "SEED_TARGET_NOT_DEMO");
        t.set("users/" + u.id, {
          ...old,
          uid: u.id,
          role: u.role,
          fullName: u.fullName,
          emailMasked: u.email.replace(/^(.).*(@.*)$/, "$1***$2"),
          phoneMasked: null,
          status: old?.status ?? "ACTIVE",
          createdAt: old?.createdAt ?? Date.now(),
          active: true,
          demoNamespace: ns,
        });
        const collection = u.role === "OFFICER" ? "officers" : "customers";
        const profile = await t.get(collection + "/" + u.id);
        t.set(collection + "/" + u.id, {
          ...profile,
          uid: u.id,
          active: true,
          permissions: u.role === "OFFICER" ? permissions : [],
          kycStatus: profile?.kycStatus ?? "NOT_SUBMITTED",
          demoNamespace: ns,
          uidRef: u.id,
          ...(u.role === "OFFICER"
            ? {
                staffCode: profile?.staffCode ?? "DEMO_" + ns,
                branchId: profile?.branchId ?? ns + "_branch",
              }
            : {
                customerNo: profile?.customerNo ?? "DEMO_" + u.id,
                address: profile?.address ?? "Demo fixture",
                createdBy: profile?.createdBy ?? "TRUSTED_SEED",
              }),
        });
      });
    }
    await r.bank.store.run(async (t) => {
      const create = async (path: string, data: Record<string, unknown>) => {
        if (!(await t.get(path)))
          t.set(path, { ...data, demo: true, demoNamespace: ns });
      };
      const ownerUid = identities[0].id;
      for (const type of ["CHECKING", "SAVINGS", "MORTGAGE"])
        await create("accounts/" + ns + "_" + type.toLowerCase(), {
          ownerUid,
          type,
          currency: "VND",
          status: "ACTIVE",
          availableMinor: 25000000,
          bookedMinor: 25000000,
          balanceMinor: 25000000,
          version: 0,
        });
      await create("accounts/" + ns + "_recipient_checking", {
        ownerUid: identities[1].id,
        type: "CHECKING",
        currency: "VND",
        status: "ACTIVE",
        availableMinor: 1000000,
        bookedMinor: 1000000,
        balanceMinor: 1000000,
        version: 0,
      });
      await create("checkingDetails/" + ns + "_checking", {
        accountId: ns + "_checking",
        dailyLimitMinor: 50000000,
        overdraftPolicy: "NONE",
        cardAlias: "DEMO",
      });
      await create("ratePolicies/" + ns + "_savings_v1", {
        productId: "demo_savings",
        annualRateBasisPoints: 500,
        effectiveFrom: 0,
        version: 1,
        updatedBy: "TRUSTED_SEED",
      });
      await create("savingsDetails/" + ns + "_savings", {
        accountId: ns + "_savings",
        principalMinor: 25000000,
        productId: "demo_savings",
        ratePolicyVersion: ns + "_savings_v1",
        openedAt: Date.now(),
        maturityAt: Date.now() + 365 * 86400000,
      });
      await create("mortgageDetails/" + ns + "_mortgage", {
        accountId: ns + "_mortgage",
        loanPrincipalMinor: 100000000,
        outstandingMinor: 100000000,
        annualRateBasisPoints: 800,
        paymentFrequency: "BIWEEKLY",
        termMonths: 24,
      });
      for (const serviceType of [
        "ELECTRICITY",
        "WATER",
        "TOPUP",
        "FLIGHT",
        "MOVIE",
        "HOTEL",
        "ECOMMERCE",
      ])
        await create("catalog/" + ns + "_" + serviceType.toLowerCase(), {
          serviceType,
          name: serviceType + " demo",
          amountMinor: 20000,
          currency: "VND",
          active: true,
        });
      await create("bills/" + ns + "_electricity", {
        ownerUid,
        provider: "DEMO",
        invoiceRef: ns + "_electricity",
        amountMinor: 20000,
        currency: "VND",
        status: "UNPAID",
        dueAt: null,
      });
      await create("bankBranches/" + ns + "_branch", {
        name: "Chi nhánh demo Tân Phong",
        address: "Tân Phong, TP Hồ Chí Minh",
        latitude: 10.7326,
        longitude: 106.6998,
        active: true,
        openingHours: "08:00–17:00",
      });
    });
    console.log(
      "Namespaced seed completed. Sign in with the DEMO_PASSWORD configured locally.",
    );
  }
} finally {
  await r.db.terminate();
}
