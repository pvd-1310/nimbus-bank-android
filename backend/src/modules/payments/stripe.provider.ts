import Stripe from "stripe";
export class StripeGateway {
  constructor(
    readonly stripe: Stripe,
    private webhookSecret: string,
  ) {}
  async create(id: string, amount: number, currency: string) {
    const p = await this.stripe.paymentIntents.create(
      {
        amount,
        currency: currency.toLowerCase(),
        metadata: { paymentId: id },
        automatic_payment_methods: { enabled: true },
      },
      { idempotencyKey: id },
    );
    return { id: p.id, clientSecret: p.client_secret };
  }
  async retrieve(providerId: string) {
    const p = await this.stripe.paymentIntents.retrieve(providerId);
    return { id: p.id, clientSecret: p.client_secret };
  }
  parse(body: Buffer, signature: string) {
    return this.stripe.webhooks.constructEvent(
      body,
      signature,
      this.webhookSecret,
    );
  }
}
