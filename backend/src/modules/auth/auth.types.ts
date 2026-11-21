export interface Actor {
  uid: string;
  role: "CUSTOMER" | "OFFICER";
  permissions: string[];
}
