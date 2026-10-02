const { onDocumentCreated, onDocumentUpdated } = require("firebase-functions/v2/firestore");
const logger = require("firebase-functions/logger");
const { initializeApp } = require("firebase-admin/app");
const { getFirestore } = require("firebase-admin/firestore");
const { getMessaging } = require("firebase-admin/messaging");

initializeApp();
const db = getFirestore();
const REGION = "southamerica-east1";

async function sendPush(token, data, logLabel) {
  if (!token) {
    logger.warn(`${logLabel}: token FCM ausente; nenhum push enviado.`);
    return;
  }
  await getMessaging().send({
    token,
    data,
    android: { priority: "high", ttl: 60 * 60 * 1000 }
  });
}

// Dinheiro: avisa quando o pedido é criado. Pix: espera o botão verde do cliente.
exports.notifyNewOrder = onDocumentCreated(
  { document: "orders/{orderId}", region: REGION },
  async (event) => {
    const order = event.data && event.data.data();
    if (!order || order.demo === true || order.status !== "Pendente" || order.cancelled === true) return;
    if (String(order.payment || "").toUpperCase() === "PIX") return;

    const config = await db.collection("appConfig").doc("main").get();
    try {
      await sendPush(config.get("adminFcmToken"), {
        type: "new_order",
        title: "Novo pedido",
        body: "Chegou mais um pedido!",
        orderId: String(event.params.orderId || "")
      }, "Novo pedido em dinheiro");
      logger.info("Push de pedido em dinheiro enviado à administração.", { orderId: event.params.orderId });
    } catch (error) {
      logger.error("Falha ao enviar push de pedido em dinheiro.", error);
      throw error;
    }
  }
);

// Pix: o aviso só sai quando pixReported muda de false/ausente para true.
exports.notifyPixReported = onDocumentUpdated(
  { document: "orders/{orderId}", region: REGION },
  async (event) => {
    const before = event.data.before.data();
    const after = event.data.after.data();
    if (!after || after.demo === true || after.status !== "Pendente") return;
    if (String(after.payment || "").toUpperCase() !== "PIX") return;
    if (before.pixReported === true || after.pixReported !== true) return;

    const config = await db.collection("appConfig").doc("main").get();
    try {
      await sendPush(config.get("adminFcmToken"), {
        type: "pix_reported",
        title: "Pix informado",
        body: "Cliente informou que fez o Pix. Confira o recebimento no banco.",
        orderId: String(event.params.orderId || "")
      }, "Pix informado");
      logger.info("Push de Pix informado enviado à administração.", { orderId: event.params.orderId });
    } catch (error) {
      logger.error("Falha ao enviar push de Pix informado.", error);
      throw error;
    }
  }
);

// Cliente: avisa quando o entregador registra que chegou ao endereço.
exports.notifyCustomerArrival = onDocumentUpdated(
  { document: "orders/{orderId}", region: REGION },
  async (event) => {
    const before = event.data.before.data();
    const after = event.data.after.data();
    if (!after || after.demo === true) return;
    if (before.status === "Chegou ao endereço" || after.status !== "Chegou ao endereço") return;

    try {
      await sendPush(after.customerFcmToken, {
        type: "customer_arrived",
        title: "Pedido chegou",
        body: "Seu pedido chegou",
        orderId: String(event.params.orderId || "")
      }, "Aviso de chegada ao cliente");
      logger.info("Push de chegada enviado ao cliente.", { orderId: event.params.orderId });
    } catch (error) {
      logger.error("Falha ao enviar push de chegada ao cliente.", error);
      throw error;
    }
  }
);
