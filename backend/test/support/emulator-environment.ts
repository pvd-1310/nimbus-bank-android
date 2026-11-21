export function requireEmulatorEnvironment(env: NodeJS.ProcessEnv): string {
  const projectId = env.GCLOUD_PROJECT ?? "demo-mobile-banking";
  const loopback = (host: string | undefined) => {
    const match = host?.match(
      /^(?:127\.0\.0\.1|localhost|\[::1\]):([0-9]{1,5})$/,
    );
    const port = Number(match?.[1]);
    return !!match && port > 0 && port <= 65535;
  };
  if (
    !/^demo-[a-z0-9-]+$/.test(projectId) ||
    !loopback(env.FIREBASE_AUTH_EMULATOR_HOST) ||
    !loopback(env.FIRESTORE_EMULATOR_HOST)
  )
    throw Error("EMULATOR_TESTS_REQUIRE_DEMO_PROJECT_AND_LOOPBACK_HOSTS");
  return projectId;
}
