import Stripe from "stripe";
import { check } from "../../shared/errors.js";
export class StripeGateway {
  constructor(
    readonly stripe: Stripe,
    private webhookSecret: string,
  ) {}
  async createCustomer(requestId: string) {
    const customer = await this.stripe.customers.create(
      {},
      { idempotencyKey: "nimbus-customer-" + requestId },
    );
    check(customer.livemode === false, "LIVE_MODE_FORBIDDEN");
    return customer.id;
  }

  async createCustomerSession(customerId: string) {
    const session = await this.stripe.customerSessions.create({
      customer: customerId,
      components: {
        mobile_payment_element: {
          enabled: true,
          features: {
            payment_method_save: "enabled",
            payment_method_redisplay: "enabled",
            payment_method_remove: "enabled",
          },
        },
      },
    });
    check(session.livemode === false, "LIVE_MODE_FORBIDDEN");
    check(session.customer === customerId, "PROVIDER_MISMATCH");
    return session.client_secret;
  }

  async listCardPaymentMethods(customerId: string) {
    const list = await this.stripe.customers.listPaymentMethods(customerId, {
      type: "card",
      limit: 10,
    });
    return list.data.map((pm) => ({
      id: pm.id,
      brand: (pm.card?.brand ?? "visa").toUpperCase(),
      last4: pm.card?.last4 ?? "",
      expMonth: pm.card?.exp_month ?? null,
      expYear: pm.card?.exp_year ?? null,
    }));
  }

  async createSetupIntent(customerId: string, requestId: string) {
    const setup = await this.stripe.setupIntents.create(
      {
        customer: customerId,
        payment_method_types: ["card"],
        usage: "off_session",
      },
      { idempotencyKey: "nimbus-setup-" + requestId },
    );
    check(setup.livemode === false, "LIVE_MODE_FORBIDDEN");
    return setup.client_secret;
  }

  async detachPaymentMethod(paymentMethodId: string, expectedCustomerId: string) {
    const pm = await this.stripe.paymentMethods.retrieve(paymentMethodId);
    check(pm.livemode === false, "LIVE_MODE_FORBIDDEN");
    const owner = typeof pm.customer === "string" ? pm.customer : pm.customer?.id;
    check(owner === expectedCustomerId, "FORBIDDEN", 403);
    await this.stripe.paymentMethods.detach(paymentMethodId);
  }

  async create(id: string, amount: number, currency: string, customerId?: string, purpose = "BILL_PAYMENT") {
    const p = await this.stripe.paymentIntents.create(
      {
        amount,
        currency: currency.toLowerCase(),
        metadata: { paymentId: id, purpose },
        automatic_payment_methods: { enabled: true },
        ...(customerId && { customer: customerId }),
      },
      { idempotencyKey: id },
    );
    check(p.livemode === false, "LIVE_MODE_FORBIDDEN");
    return p;
  }
  async retrieve(providerId: string) {
    const p = await this.stripe.paymentIntents.retrieve(providerId);
    check(p.livemode === false, "LIVE_MODE_FORBIDDEN");
    return p;
  }
  parse(body: Buffer, signature: string) {
    return this.stripe.webhooks.constructEvent(
      body,
      signature,
      this.webhookSecret,
    );
  }
}
