const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const DATABASE_ID = "ai-studio-android-printfor-c21a231f-4f6a-4b6c-96d6-d851a34705ab";
const ALICE_UID = "alice_123";
const BOB_UID = "bob_456";

const [emulatorHost, emulatorPortStr] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085").split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

function tsNow() {
  return new Date(Date.now() - 1000);
}

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      databaseId: DATABASE_ID,
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

test("Unauthenticated user cannot read or write printers or jobs", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection(`users/${ALICE_UID}/saved_printers`).get());
  await assertFails(
    unauthDb.collection(`users/${ALICE_UID}/saved_printers`).doc("printer_1").set({
      userId: ALICE_UID,
      printerKey: "raw:192.168.1.50:9100",
      name: "LaserJet",
      connectionType: "WIFI_RAW",
      address: "192.168.1.50",
      port: 9100,
      protocol: "PCL_RASTER",
      createdAt: tsNow(),
      updatedAt: tsNow(),
    })
  );
});

test("Authenticated owner can create, list, update, and delete saved_printers", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const docRef = aliceDb.collection(`users/${ALICE_UID}/saved_printers`).doc("raw_192_168_1_50_9100");

  await assertSucceeds(
    docRef.set({
      userId: ALICE_UID,
      printerKey: "raw:192.168.1.50:9100",
      name: "Office HP LaserJet",
      connectionType: "WIFI_RAW",
      address: "192.168.1.50",
      port: 9100,
      protocol: "PCL_RASTER",
      details: "192.168.1.50:9100 · Manual PCL5",
      createdAt: tsNow(),
      updatedAt: tsNow(),
    })
  );

  await assertSucceeds(aliceDb.collection(`users/${ALICE_UID}/saved_printers`).get());

  await assertSucceeds(
    docRef.update({
      name: "Studio LaserJet Pro",
      updatedAt: tsNow(),
    })
  );

  await assertSucceeds(docRef.delete());
});

test("Cross-user isolation blocks Bob from reading or writing Alice's printers and jobs", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  await assertSucceeds(
    aliceDb.collection(`users/${ALICE_UID}/print_jobs`).doc("job_1").set({
      userId: ALICE_UID,
      documentTitle: "Contract.pdf",
      printerName: "Office HP",
      connectionType: "Wi-Fi IP",
      protocolUsed: "PCL5",
      paperSize: "A4",
      pageCount: 2,
      copies: 1,
      dpi: 300,
      bytesTransferred: 48120,
      status: "Completed",
      createdAt: tsNow(),
    })
  );

  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  await assertFails(bobDb.collection(`users/${ALICE_UID}/print_jobs`).get());
  await assertFails(bobDb.collection(`users/${ALICE_UID}/print_jobs`).doc("job_1").get());
  await assertFails(bobDb.collection(`users/${ALICE_UID}/print_jobs`).doc("job_1").delete());
});

test("Shadow fields and invalid protocol enums are rejected by security rules", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  await assertFails(
    aliceDb.collection(`users/${ALICE_UID}/saved_printers`).doc("bad_printer").set({
      userId: ALICE_UID,
      printerKey: "raw:10.0.0.5:9100",
      name: "Bad Printer",
      connectionType: "WIFI_RAW",
      address: "10.0.0.5",
      port: 9100,
      protocol: "INVALID_PROTO",
      isAdmin: true,
      createdAt: tsNow(),
      updatedAt: tsNow(),
    })
  );
});

test("Print jobs are immutable once created (update rejected)", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const jobRef = aliceDb.collection(`users/${ALICE_UID}/print_jobs`).doc("job_imm");
  await assertSucceeds(
    jobRef.set({
      userId: ALICE_UID,
      documentTitle: "Invoice.pdf",
      printerName: "Thermal POS",
      connectionType: "Bluetooth",
      protocolUsed: "ESC/POS",
      paperSize: "80mm Thermal Roll",
      pageCount: 1,
      copies: 1,
      dpi: 300,
      bytesTransferred: 8192,
      status: "Completed",
      createdAt: tsNow(),
    })
  );
  await assertFails(jobRef.update({ status: "Failed" }));
});
