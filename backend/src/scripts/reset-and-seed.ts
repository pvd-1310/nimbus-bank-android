import { runtime } from "../infrastructure/runtime.js";
import { check } from "../shared/errors.js";
import { digest } from "../shared/crypto.js";
import { normalizeSearchText } from "../shared/search.js";
import { seedClaims } from "./seed-preserve.js";

const r = runtime();
const ns = process.env.DEMO_NAMESPACE ?? "demo";
check(/^[a-z0-9_]{3,30}$/.test(ns), "INVALID_NAMESPACE");
const apply = process.argv.includes("--apply");

const permissions = [
  "CUSTOMER_ADMIN",
  "EKYC_REVIEW",
  "RATE_UPDATE",
  "AUDIT_READ",
];

const COLLECTIONS_TO_WIPE = [
  "users",
  "customers",
  "officers",
  "accounts",
  "checkingDetails",
  "savingsDetails",
  "mortgageDetails",
  "transactions",
  "transactionIntents",
  "ledgerEntries",
  "otpChallenges",
  "ekycRequests",
  "ekycUploadTickets",
  "faceTemplates",
  "bills",
  "billPaymentLocks",
  "payments",
  "orders",
  "outbox",
  "ratePolicies",
  "bankBranches",
  "catalog",
  "notifications",
  "chatHistory",
  "auditLogs",
  "contactPhoneReservations",
  "dailyUsage",
  "rateLimits",
];

type SeedIdentity = {
  id: string;
  email: string;
  role: "CUSTOMER" | "OFFICER";
  fullName: string;
  phone: string;
  phoneMasked: string;
  customerNo?: string;
  tier?: string;
  address?: string;
  nationalIdNumber?: string;
  staffCode?: string;
};

const identities: SeedIdentity[] = [
  {
    id: ns + "_customer",
    email: ns + ".customer@example.com",
    role: "CUSTOMER",
    fullName: "Nguyễn Minh Anh",
    phone: "0901482100",
    phoneMasked: "•••• 4821",
    customerNo: "NB-028451",
    tier: "PLATINUM",
    address: "18 Nguyễn Văn Linh, phường Tân Phong, Quận 7, TP. Hồ Chí Minh",
    nationalIdNumber: "079198004821",
  },
  {
    id: ns + "_recipient",
    email: ns + ".recipient@example.com",
    role: "CUSTOMER",
    fullName: "Trần Quốc Bảo",
    phone: "0907364000",
    phoneMasked: "•••• 7364",
    customerNo: "NB-028452",
    tier: "GOLD",
    address: "42 Lê Văn Sỹ, phường 13, Quận 3, TP. Hồ Chí Minh",
    nationalIdNumber: "079096007364",
  },
  {
    id: ns + "_peer",
    email: ns + ".peer@example.com",
    role: "CUSTOMER",
    fullName: "Phạm Thu Hà",
    phone: "0912345678",
    phoneMasked: "•••• 5678",
    customerNo: "NB-028453",
    tier: "STANDARD",
    address: "105 Hai Bà Trưng, phường Bến Nghé, Quận 1, TP. Hồ Chí Minh",
    nationalIdNumber: "079199005678",
  },
  {
    id: ns + "_officer",
    email: ns + ".officer@example.com",
    role: "OFFICER",
    fullName: "Lê Thu Hà",
    phone: "0909195800",
    phoneMasked: "•••• 1958",
    staffCode: "NV-01427",
  },
];

async function wipeCollection(name: string): Promise<number> {
  let deleted = 0;
  while (true) {
    const snap = await r.db.collection(name).limit(200).get();
    if (snap.empty) break;
    const batch = r.db.batch();
    for (const doc of snap.docs) {
      batch.delete(doc.ref);
      deleted++;
    }
    await batch.commit();
  }
  return deleted;
}

async function run() {
  console.log(
    JSON.stringify(
      {
        project: r.cfg.projectId,
        mode: r.cfg.mode,
        namespace: ns,
        apply,
        collectionsToWipe: COLLECTIONS_TO_WIPE.length,
        identities: identities.map(({ id, email, role }) => ({ id, email, role })),
      },
      null,
      2,
    ),
  );

  if (!apply) {
    console.log("Dry run only. Pass --apply to wipe Firestore and seed fresh realistic data.");
    return;
  }

  check(
    r.cfg.mode === "emulator" || process.env.CONFIRM_SEED_PROJECT === r.cfg.projectId,
    "CONFIRM_PROJECT_REQUIRED",
  );
  const password = process.env.DEMO_PASSWORD;
  check(password && password.length >= 12, "DEMO_PASSWORD_REQUIRED");

  const wipedCounts: Record<string, number> = {};
  for (const col of COLLECTIONS_TO_WIPE) {
    const count = await wipeCollection(col);
    if (count > 0) wipedCounts[col] = count;
  }
  console.log("Wiped Firestore collections:", wipedCounts);

  const now = Date.now();
  const dayMs = 86_400_000;

  // 1. Auth + Users + Customers/Officers + Phone Reservations
  for (const u of identities) {
    let existing = null;
    try {
      existing = await r.auth.getUser(u.id);
    } catch (e) {
      if ((e as { code?: string }).code !== "auth/user-not-found") throw e;
    }
    if (!existing) {
      // Also check by email in case a previous seed created a random UID
      try {
        const byEmail = await r.auth.getUserByEmail(u.email);
        await r.auth.deleteUser(byEmail.uid);
      } catch (e) {
        if ((e as { code?: string }).code !== "auth/user-not-found") throw e;
      }
      await r.auth.createUser({
        uid: u.id,
        email: u.email,
        password,
        displayName: u.fullName,
      });
    } else {
      await r.auth.updateUser(u.id, {
        email: u.email,
        password,
        displayName: u.fullName,
        disabled: false,
      });
    }
    await r.auth.setCustomUserClaims(
      u.id,
      seedClaims(undefined, u.role, u.role === "OFFICER" ? permissions : []),
    );

    const canonicalPhone = "+84" + u.phone.slice(1);
    await r.bank.store.run(async (t) => {
      t.set("users/" + u.id, {
        uid: u.id,
        role: u.role,
        fullName: u.fullName,
        emailMasked: u.email.replace(/^(.).*(@.*)$/, "$1***$2"),
        contactEmail: u.email,
        contactPhone: canonicalPhone,
        phoneMasked: u.phoneMasked,
        status: "ACTIVE",
        active: true,
        preferences: { language: "vi", notificationsEnabled: true },
        createdAt: now - 120 * dayMs,
        demoNamespace: ns,
      });
      t.set("contactPhoneReservations/" + digest(["PHONE", canonicalPhone]), {
        uid: u.id,
        contactPhone: canonicalPhone,
        updatedAt: now,
      });
      if (u.role === "OFFICER") {
        t.set("officers/" + u.id, {
          officerId: u.id,
          uid: u.id,
          uidRef: u.id,
          fullName: u.fullName,
          staffCode: u.staffCode,
          permissions,
          active: true,
          branchId: ns + "_branch_pmh",
          demoNamespace: ns,
        });
      } else {
        t.set("customers/" + u.id, {
          customerId: u.id,
          uid: u.id,
          uidRef: u.id,
          fullName: u.fullName,
          phoneMasked: u.phoneMasked,
          customerNo: u.customerNo,
          tier: u.tier ?? "STANDARD",
          nationalIdNumber: u.nationalIdNumber,
          kycStatus: "NOT_SUBMITTED",
          address: u.address,
          active: true,
          createdBy: "TRUSTED_SEED",
          demoNamespace: ns,
        });
      }
    });
  }

  const mainUid = identities[0].id;
  const recipientUid = identities[1].id;
  const peerUid = identities[2].id;

  // 2. Accounts, Checking/Savings/Mortgage Details, Rate Policies
  await r.bank.store.run(async (t) => {
    const mainCheckingId = ns + "_checking";
    const mainSavingsId = ns + "_savings";
    const mainMortgageId = ns + "_mortgage";
    const recipientCheckingId = ns + "_recipient_checking";
    const peerCheckingId = ns + "_peer_checking";

    const putAccount = (
      id: string,
      ownerUid: string,
      fullName: string,
      type: "CHECKING" | "SAVINGS" | "MORTGAGE",
      balanceMinor: number,
    ) => {
      t.set("accounts/" + id, {
        accountId: id,
        ownerUid,
        ...(type === "CHECKING" ? { ownerNameSearch: normalizeSearchText(fullName) } : {}),
        type,
        currency: "VND",
        status: "ACTIVE",
        availableMinor: balanceMinor,
        bookedMinor: balanceMinor,
        balanceMinor,
        version: 1,
        demo: true,
        demoNamespace: ns,
      });
    };

    putAccount(mainCheckingId, mainUid, "Nguyễn Minh Anh", "CHECKING", 38_642_500);
    putAccount(mainSavingsId, mainUid, "Nguyễn Minh Anh", "SAVINGS", 62_500_000);
    putAccount(mainMortgageId, mainUid, "Nguyễn Minh Anh", "MORTGAGE", 742_500_000);
    putAccount(recipientCheckingId, recipientUid, "Trần Quốc Bảo", "CHECKING", 14_987_500);
    putAccount(peerCheckingId, peerUid, "Phạm Thu Hà", "CHECKING", 21_400_000);

    t.set("checkingDetails/" + mainCheckingId, {
      accountId: mainCheckingId,
      displayName: "Tài khoản thanh toán Platinum",
      branchId: ns + "_branch_pmh",
      branchName: "PGD Phú Mỹ Hưng · Quận 7",
      dailyLimitMinor: 50_000_000,
      cardDailyLimitMinor: 20_000_000,
      overdraftPolicy: "NONE",
      cardAlias: "NGUYEN MINH ANH",
      cardTier: "infinite",
      nfcEnabled: false,
      cardFrozen: false,
      demo: true,
      demoNamespace: ns,
    });
    t.set("checkingDetails/" + recipientCheckingId, {
      accountId: recipientCheckingId,
      displayName: "Tài khoản thanh toán Gold",
      branchId: ns + "_branch_sg",
      branchName: "Chi nhánh Sài Gòn · Quận 1",
      dailyLimitMinor: 30_000_000,
      cardDailyLimitMinor: 15_000_000,
      overdraftPolicy: "NONE",
      cardAlias: "TRAN QUOC BAO",
      cardTier: "gold",
      nfcEnabled: true,
      cardFrozen: false,
      demo: true,
      demoNamespace: ns,
    });
    t.set("checkingDetails/" + peerCheckingId, {
      accountId: peerCheckingId,
      displayName: "Tài khoản thanh toán Chuẩn",
      branchId: ns + "_branch_sg",
      branchName: "Chi nhánh Sài Gòn · Quận 1",
      dailyLimitMinor: 20_000_000,
      cardDailyLimitMinor: 10_000_000,
      overdraftPolicy: "NONE",
      cardAlias: "PHAM THU HA",
      cardTier: "classic",
      nfcEnabled: false,
      cardFrozen: false,
      demo: true,
      demoNamespace: ns,
    });

    t.set("ratePolicies/" + ns + "_savings_v1", {
      policyId: ns + "_savings_v1",
      productId: "savings_standard_vnd",
      accountProduct: "savings_standard_vnd",
      rateType: "SAVINGS",
      annualRateBasisPoints: 465,
      effectiveFrom: now - 90 * dayMs,
      version: 1,
      updatedBy: "TRUSTED_SEED",
      demo: true,
      demoNamespace: ns,
    });
    t.set("ratePolicies/" + ns + "_loan_v1", {
      policyId: ns + "_loan_v1",
      productId: "mortgage_home_vnd",
      accountProduct: "mortgage_home_vnd",
      rateType: "LOAN",
      annualRateBasisPoints: 850,
      effectiveFrom: now - 90 * dayMs,
      version: 1,
      updatedBy: "TRUSTED_SEED",
      demo: true,
      demoNamespace: ns,
    });

    t.set("savingsDetails/" + mainSavingsId, {
      accountId: mainSavingsId,
      principalMinor: 62_500_000,
      productId: "savings_standard_vnd",
      ratePolicyVersion: ns + "_savings_v1",
      termMonths: 12,
      openedAt: now - 90 * dayMs,
      maturityAt: now + 275 * dayMs,
      demo: true,
      demoNamespace: ns,
    });

    t.set("mortgageDetails/" + mainMortgageId, {
      accountId: mainMortgageId,
      contractCode: "MC-88291",
      loanPrincipalMinor: 850_000_000,
      outstandingMinor: 742_500_000,
      annualRateBasisPoints: 850,
      annualRate: 0.085,
      paymentFrequency: "MONTHLY",
      termMonths: 240,
      remainingTermMonths: 225,
      paidInstallments: 15,
      nextDueAt: now + 5 * dayMs,
      demo: true,
      demoNamespace: ns,
    });

    // 3. Realistic Transactions over the last 6 days
    const seedTx = [
      {
        id: ns + "_tx_salary",
        ownerUid: mainUid,
        sourceAccountId: mainCheckingId,
        type: "TRANSFER_RECEIVED",
        amountMinor: 18_500_000,
        balanceAfterMinor: 41_993_900,
        counterpartyName: "Công ty Công nghệ Nimbus",
        committedAt: now - 5 * dayMs + 3_600_000,
      },
      {
        id: ns + "_tx_bao",
        ownerUid: mainUid,
        sourceAccountId: mainCheckingId,
        destinationAccountId: recipientCheckingId,
        type: "INTERNAL_TRANSFER",
        amountMinor: 1_500_000,
        balanceAfterMinor: 40_493_900,
        counterpartyName: "Trần Quốc Bảo",
        destinationPhone: "+84907364000",
        committedAt: now - 3 * dayMs + 14_400_000,
      },
      {
        id: ns + "_tx_ha",
        ownerUid: mainUid,
        sourceAccountId: mainCheckingId,
        destinationAccountId: peerCheckingId,
        type: "INTERNAL_TRANSFER",
        amountMinor: 850_000,
        balanceAfterMinor: 39_643_900,
        counterpartyName: "Phạm Thu Hà",
        destinationPhone: "+84912345678",
        committedAt: now - 2 * dayMs + 7_200_000,
      },
      {
        id: ns + "_tx_evn",
        ownerUid: mainUid,
        sourceAccountId: mainCheckingId,
        type: "BILL_PAYMENT",
        amountMinor: 936_400,
        balanceAfterMinor: 38_707_500,
        counterpartyName: "EVN TP. Hồ Chí Minh",
        committedAt: now - 1 * dayMs + 10_800_000,
      },
      {
        id: ns + "_tx_coffee",
        ownerUid: mainUid,
        sourceAccountId: mainCheckingId,
        type: "UTILITY_ORDER",
        amountMinor: 65_000,
        balanceAfterMinor: 38_642_500,
        counterpartyName: "The Coffee House",
        committedAt: now - 3_600_000,
      },
      {
        id: ns + "_tx_bao_credit",
        ownerUid: recipientUid,
        sourceAccountId: recipientCheckingId,
        type: "TRANSFER_RECEIVED",
        amountMinor: 1_500_000,
        balanceAfterMinor: 14_987_500,
        counterpartyName: "Nguyễn Minh Anh",
        committedAt: now - 3 * dayMs + 14_400_000,
      },
    ];
    for (const item of seedTx) {
      t.set("transactions/" + item.id, {
        transactionId: item.id,
        requestId: item.id,
        actorUid: item.ownerUid,
        currency: "VND",
        status: "SETTLED",
        createdAt: new Date(item.committedAt),
        demo: true,
        demoNamespace: ns,
        ...item,
      });
    }

    // 4. Bills with subscriberName & serviceAddress
    const dt = new Date(now);
    const billingPeriod = "Tháng " + String(dt.getMonth() + 1).padStart(2, "0") + "/" + dt.getFullYear();
    t.set("bills/" + ns + "_water", {
      billId: ns + "_water",
      ownerUid: mainUid,
      serviceType: "WATER",
      currency: "VND",
      status: "UNPAID",
      provider: "Tổng công ty Cấp nước Sài Gòn (SAWACO)",
      billingPeriod,
      invoiceRef: "SAWACO-HCM-" + dt.getFullYear() + "-" + String(dt.getMonth() + 1).padStart(2, "0") + "-193846",
      subscriberName: "NGUYỄN MINH ANH",
      serviceAddress: "18 Nguyễn Văn Linh, P. Tân Phong, Quận 7, TP.HCM",
      amountMinor: 284_600,
      dueAt: now + 14 * dayMs,
      previousReading: "1.246",
      currentReading: "1.264",
      consumption: 18,
      consumptionUnit: "m³",
      demo: true,
      demoNamespace: ns,
    });
    t.set("bills/" + ns + "_electricity", {
      billId: ns + "_electricity",
      ownerUid: mainUid,
      serviceType: "ELECTRICITY",
      currency: "VND",
      status: "UNPAID",
      provider: "Tổng công ty Điện lực TP. Hồ Chí Minh (EVNHCMC)",
      billingPeriod,
      invoiceRef: "EVNHCMC-" + dt.getFullYear() + "-" + String(dt.getMonth() + 1).padStart(2, "0") + "-482731",
      subscriberName: "NGUYỄN MINH ANH",
      serviceAddress: "18 Nguyễn Văn Linh, P. Tân Phong, Quận 7, TP.HCM",
      amountMinor: 936_400,
      dueAt: now + 4 * dayMs,
      previousReading: "4.820",
      currentReading: "5.145",
      consumption: 325,
      consumptionUnit: "kWh",
      demo: true,
      demoNamespace: ns,
    });

    // 5. Catalog
    for (const [serviceType, name, amountMinor] of [
      ["ELECTRICITY", "Hóa đơn điện sinh hoạt · EVN TP. Hồ Chí Minh", 936_400],
      ["WATER", "Hóa đơn nước sinh hoạt · SAWACO", 284_600],
      ["TOPUP", "Nạp tiền điện thoại trả trước", 100_000],
      ["MOVIE", "Vé xem phim tiêu chuẩn · suất 19:30", 95_000],
      ["HOTEL", "Phòng Superior · một đêm tại TP. Đà Lạt", 1_250_000],
      ["ECOMMERCE", "Bình giữ nhiệt inox 500 ml", 289_000],
    ] as const) {
      t.set("catalog/" + ns + "_" + serviceType.toLowerCase(), {
        serviceType,
        name,
        amountMinor,
        currency: "VND",
        active: true,
        demo: true,
        demoNamespace: ns,
      });
    }
    const flights = [
      {
        id: ns + "_flight",
        name: "TP. Hồ Chí Minh – Hà Nội · VN 214",
        airline: "Vietnam Airlines",
        flightNumber: "VN 214",
        aircraft: "Airbus A350",
        origin: "SGN",
        destination: "HAN",
        originCity: "TP. Hồ Chí Minh",
        destinationCity: "Hà Nội",
        departureTime: "08:30",
        arrivalTime: "10:45",
        duration: "2h 15m",
        fareClass: "Phổ thông",
        amountMinor: 1_890_000,
      },
      {
        id: ns + "_selection_flight",
        name: "TP. Hồ Chí Minh – Hà Nội · QH 220",
        airline: "Bamboo Airways",
        flightNumber: "QH 220",
        aircraft: "Boeing 787",
        origin: "SGN",
        destination: "HAN",
        originCity: "TP. Hồ Chí Minh",
        destinationCity: "Hà Nội",
        departureTime: "11:15",
        arrivalTime: "13:30",
        duration: "2h 15m",
        fareClass: "Phổ thông",
        amountMinor: 1_650_000,
      },
      {
        id: ns + "_selection_flight_vietjet",
        name: "TP. Hồ Chí Minh – Hà Nội · VJ 132",
        airline: "Vietjet Air",
        flightNumber: "VJ 132",
        aircraft: "Airbus A321",
        origin: "SGN",
        destination: "HAN",
        originCity: "TP. Hồ Chí Minh",
        destinationCity: "Hà Nội",
        departureTime: "14:00",
        arrivalTime: "16:15",
        duration: "2h 15m",
        fareClass: "Phổ thông",
        amountMinor: 1_420_000,
      },
    ] as const;
    for (const flight of flights) {
      const { id, ...data } = flight;
      t.set("catalog/" + id, {
        serviceType: "FLIGHT",
        ...data,
        currency: "VND",
        active: true,
        demo: true,
        demoNamespace: ns,
      });
    }
    for (const [network, carrierName] of [["A", "Viettel"], ["B", "MobiFone"], ["C", "VinaPhone"]] as const) {
      for (const amountMinor of [20_000, 50_000, 100_000, 200_000, 500_000, 1_000_000]) {
        t.set("catalog/" + ns + "_topup_" + network + "_" + amountMinor, {
          serviceType: "TOPUP",
          name: carrierName,
          network,
          amountMinor,
          currency: "VND",
          active: true,
          demo: true,
          demoNamespace: ns,
        });
      }
    }

    // 6. Branches & ATMs
    const branches = [
      {
        id: ns + "_branch_pmh",
        name: "Ngân hàng Nimbus · PGD Phú Mỹ Hưng",
        address: "Số 12 Nguyễn Lương Bằng, phường Tân Phú, Quận 7, TP. Hồ Chí Minh",
        latitude: 10.7295,
        longitude: 106.7216,
        type: "BRANCH",
        openingHours: "Thứ Hai–Thứ Sáu, 08:00–17:00",
        phone: "028 5412 8899",
        services: ["Quầy giao dịch", "Tư vấn VIP", "ATM 24/7"],
      },
      {
        id: ns + "_branch_sg",
        name: "Ngân hàng Nimbus · Chi nhánh Sài Gòn",
        address: "Số 124 Nguyễn Huệ, phường Bến Nghé, Quận 1, TP. Hồ Chí Minh",
        latitude: 10.7745,
        longitude: 106.7032,
        type: "BRANCH",
        openingHours: "Thứ Hai–Thứ Bảy, 08:00–17:00",
        phone: "028 3822 6688",
        services: ["Quầy giao dịch", "Mở thẻ nhanh", "Tư vấn vay"],
      },
      {
        id: ns + "_atm_vincom",
        name: "Nimbus ATM 24/7 · Vincom Đồng Khởi",
        address: "72 Lê Thánh Tôn, phường Bến Nghé, Quận 1, TP. Hồ Chí Minh",
        latitude: 10.7781,
        longitude: 106.7019,
        type: "ATM",
        openingHours: "Mở cửa 24/7",
        phone: "1900 5588 66",
        services: ["Rút tiền ATM 24/7", "Nộp tiền CDM"],
      },
    ];
    for (const branch of branches) {
      const { id, ...data } = branch;
      t.set("bankBranches/" + id, {
        branchId: id,
        active: true,
        demo: true,
        demoNamespace: ns,
        ...data,
      });
    }

    // 7. Welcome Notification
    t.set("notifications/" + ns + "_welcome", {
      notificationId: ns + "_welcome",
      ownerUid: mainUid,
      category: "GENERAL",
      title: "Chào mừng trở lại Nimbus Bank",
      message: "Tài khoản thanh toán Platinum và các tiện ích số đã sẵn sàng.",
      body: "Tài khoản thanh toán Platinum và các tiện ích số đã sẵn sàng.",
      read: false,
      readAt: null,
      createdAt: now - 1800_000,
    });
  });

  console.log("Clean reset & realistic seed completed for namespace:", ns);
}

run()
  .catch((err) => {
    console.error("Reset & seed failed:", err);
    process.exitCode = 1;
  })
  .finally(() => r.db.terminate());
