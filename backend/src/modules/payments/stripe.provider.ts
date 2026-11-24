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
