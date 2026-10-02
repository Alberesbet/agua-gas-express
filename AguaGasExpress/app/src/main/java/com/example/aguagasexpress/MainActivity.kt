package com.example.aguagasexpress

import android.content.Context
import android.content.DialogInterface
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.aguagasexpress.ui.theme.AguaGasExpressTheme
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import com.google.firebase.messaging.FirebaseMessaging
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay

private val Navy = Color(0xFF0B1730)
private val Blue = Color(0xFF1688E8)
private val LightBlue = Color(0xFF54B8FF)
private val Orange = Color(0xFFFF8A32)
private val Ink = Color(0xFF18253A)
private val Green = Color(0xFF16834A)

// Código inicial de autorização. É local/offline; não substitui autenticação online.
private const val DEFAULT_ADMIN_AUTH_CODE = "160829"
private const val DEFAULT_DELIVERY_PASSWORD = "160829"

private data class Product(val name: String, val price: Double, val isGas: Boolean = name.equals("Gás", ignoreCase = true))

private data class Order(
    val id: Long,
    val customer: String,
    val phone: String,
    val address: String,
    val items: String,
    val total: Double,
    val payment: String,
    val cashGiven: Double,
    val status: String,
    val paid: Boolean,
    val waterQty: Int = 0,
    val gasQty: Int = 0,
    val demo: Boolean = false,
    val pixReported: Boolean = false,
    val orderNumber: Int = 0,
    val customerUid: String = "",
    val customerFcmToken: String = ""
)

private fun registerAdminPushToken(
    prefs: android.content.SharedPreferences,
    firestore: FirebaseFirestore
) {
    if (prefs.getString("admin_password_hash", null).isNullOrBlank()) return
    FirebaseMessaging.getInstance().token
        .addOnSuccessListener { token ->
            if (token.isNullOrBlank()) return@addOnSuccessListener
            prefs.edit().putString("admin_fcm_token", token).apply()
            // Merge preserves prices, Pix settings and other fields in appConfig/main.
            firestore.collection("appConfig").document("main")
                .set(mapOf("adminFcmToken" to token), SetOptions.merge())
        }
}

private enum class Page { HOME, LOGIN, DELIVERY_LOGIN, CUSTOMER, ADMIN, DELIVERY, PRODUCTS, PIX_SETTINGS, ADMIN_CODE_SETTINGS, DELIVERY_PASSWORD_SETTINGS, NEW_ORDER, ORDERS, RECOVERY }

class MainActivity : ComponentActivity() {
    override fun onResume() {
        super.onResume()
        isAppVisible = true
    }

    override fun onPause() {
        isAppVisible = false
        super.onPause()
    }

    companion object {
        @Volatile var isAppVisible: Boolean = false
            private set
        @Volatile var isOnDeliveryScreen: Boolean = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("agua_gas_preferences", Context.MODE_PRIVATE)
        try {
            if (FirebaseApp.getApps(this).isEmpty()) {
                val firebaseOptions = FirebaseOptions.Builder()
                    .setApplicationId("1:1071136912572:android:477d843eb2dad93ef38cb9")
                    .setApiKey("AIzaSyBYqd8kTRq_cV5suBSWK-qTjldaOJm7h-8")
                    .setProjectId("agua-e-gas-express")
                    .setGcmSenderId("1071136912572")
                    .setStorageBucket("agua-e-gas-express.firebasestorage.app")
                    .build()
                FirebaseApp.initializeApp(this, firebaseOptions)
            }
        } catch (_: Exception) { }
        if (prefs.getString("admin_authorization_code", null) == "AGX-583941") {
            prefs.edit().putString("admin_authorization_code", "160829").apply()
        }
        val oldDeliveryHash = hashPassword("ENT-583941")
        if (prefs.getString("delivery_password_hash", null).isNullOrBlank() ||
            prefs.getString("delivery_password_hash", null) == oldDeliveryHash) {
            prefs.edit().putString("delivery_password_hash", hashPassword(DEFAULT_DELIVERY_PASSWORD)).apply()
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 2601)
        }
        setContent {
            AguaGasExpressTheme {
                Surface(Modifier.fillMaxSize(), color = Navy) { AguaGasApp(prefs) }
            }
        }
    }
}

private fun announceCustomerArrival(context: Context) {
    try {
        lateinit var speech: android.speech.tts.TextToSpeech
        speech = android.speech.tts.TextToSpeech(context.applicationContext) { status ->
            if (status == android.speech.tts.TextToSpeech.SUCCESS) {
                val languageStatus = speech.setLanguage(java.util.Locale("pt", "BR"))
                if (languageStatus != android.speech.tts.TextToSpeech.LANG_MISSING_DATA &&
                    languageStatus != android.speech.tts.TextToSpeech.LANG_NOT_SUPPORTED) {
                    speech.speak("Seu pedido chegou! O entregador está no endereço.", android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "aguagas_cliente_chegada")
                } else playOrderAlertSound(context)
            } else playOrderAlertSound(context)
        }
    } catch (_: Exception) { playOrderAlertSound(context) }
}

private fun hashPassword(value: String): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    return Base64.encodeToString(bytes, Base64.NO_WRAP)
}

private fun money(value: Double): String =
    "R$ " + String.format(Locale.forLanguageTag("pt-BR"), "%.2f", value)

private fun playOrderAlertSound(context: Context) {
    try {
        val ringtone = android.media.RingtoneManager.getRingtone(
            context,
            android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)
        )
        ringtone?.play()
    } catch (_: Exception) { }
}

private fun announceNewOrder(context: Context) {
    try {
        lateinit var speech: android.speech.tts.TextToSpeech
        speech = android.speech.tts.TextToSpeech(context.applicationContext) { status ->
            if (status == android.speech.tts.TextToSpeech.SUCCESS) {
                try {
                    val languageStatus = speech.setLanguage(java.util.Locale("pt", "BR"))
                    if (languageStatus == android.speech.tts.TextToSpeech.LANG_MISSING_DATA ||
                        languageStatus == android.speech.tts.TextToSpeech.LANG_NOT_SUPPORTED) {
                        playOrderAlertSound(context)
                    } else {
                        val result = speech.speak(
                            "Chegou mais um pedido!",
                            android.speech.tts.TextToSpeech.QUEUE_FLUSH,
                            null,
                            "aguagas_novo_pedido"
                        )
                        if (result == android.speech.tts.TextToSpeech.ERROR) playOrderAlertSound(context)
                    }
                } catch (_: Exception) { playOrderAlertSound(context) }
            } else {
                playOrderAlertSound(context)
            }
        }
    } catch (_: Exception) {
        playOrderAlertSound(context)
    }
}

private const val COMBO_WATER_NAME = "Estrela • Safira • Diamantina"
private const val LEGACY_COMBO_WATER_NAME = "Água (Estrela, Safira e Diamantina)"

private fun defaultProducts(gasPrice: Double = 95.0, comboWaterPrice: Double = 5.50): List<Product> = listOf(
    Product("Estrela", comboWaterPrice),
    Product("Safira", comboWaterPrice),
    Product("Diamantina", comboWaterPrice),
    Product("Cristalina", 8.00),
    Product("Santa Maria", 6.50),
    Product("Santa Joana", 12.00),
    Product("Gás", gasPrice, true)
)

private fun loadProducts(prefs: android.content.SharedPreferences): List<Product> {
    val saved = prefs.getString("products_json", null) ?: return defaultProducts()
    return try {
        val array = JSONArray(saved)
        val loaded = (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val name = item.optString("name", "").trim()
            val price = item.optDouble("price", -1.0)
            if (name.isBlank() || price < 0.0) null
            else Product(name, price, item.optBoolean("isGas", name.equals("Gás", ignoreCase = true)))
        }.distinctBy { it.name.lowercase(Locale.ROOT) }
        // Preserve the exact saved catalog, including an intentionally empty list or deleted brands.
        if (loaded.isNotEmpty() || array.length() == 0) loaded else defaultProducts()
    } catch (_: Exception) {
        defaultProducts()
    }
}

private fun saveProducts(prefs: android.content.SharedPreferences, products: List<Product>) {
    val array = JSONArray()
    products.forEach { array.put(JSONObject().put("name", it.name).put("price", it.price).put("isGas", it.isGas)) }
    prefs.edit().putString("products_json", array.toString()).apply()
}

private fun loadOrders(prefs: android.content.SharedPreferences): List<Order> {
    val saved = prefs.getString("orders_json", null) ?: return emptyList()
    return try {
        val array = JSONArray(saved)
        val loaded = (0 until array.length()).map {
            val item = array.getJSONObject(it)
            Order(
                id = item.getLong("id"),
                customer = item.getString("customer"),
                phone = item.getString("phone"),
                address = item.getString("address"),
                items = item.getString("items"),
                total = item.getDouble("total"),
                payment = item.getString("payment"),
                cashGiven = item.optDouble("cashGiven", 0.0),
                status = item.getString("status"),
                paid = item.optBoolean("paid", false),
                waterQty = item.optInt("waterQty", 0),
                gasQty = item.optInt("gasQty", 0),
                demo = item.optBoolean("demo", false),
                pixReported = item.optBoolean("pixReported", false),
                orderNumber = item.optInt("orderNumber", 0),
                customerUid = item.optString("customerUid", ""),
                customerFcmToken = item.optString("customerFcmToken", "")
            )
        }.sortedBy { it.id }
        var nextLegacyNumber = 0
        loaded.map { order ->
            if (order.demo) order else {
                nextLegacyNumber = maxOf(nextLegacyNumber + 1, order.orderNumber)
                if (order.orderNumber <= 0) order.copy(orderNumber = nextLegacyNumber) else order
            }
        }
    } catch (_: Exception) { emptyList() }
}

private fun saveOrders(prefs: android.content.SharedPreferences, orders: List<Order>) {
    val array = JSONArray()
    orders.forEach {
        array.put(
            JSONObject()
                .put("id", it.id).put("customer", it.customer).put("phone", it.phone)
                .put("address", it.address).put("items", it.items).put("total", it.total)
                .put("payment", it.payment).put("cashGiven", it.cashGiven)
                .put("status", it.status).put("paid", it.paid)
                .put("waterQty", it.waterQty).put("gasQty", it.gasQty).put("demo", it.demo)
                .put("pixReported", it.pixReported).put("orderNumber", it.orderNumber)
                .put("customerUid", it.customerUid)
                .put("customerFcmToken", it.customerFcmToken)
        )
    }
    prefs.edit().putString("orders_json", array.toString()).apply()
}

private fun orderToFirestoreMap(order: Order): Map<String, Any> = mapOf(
    "id" to order.id,
    "customer" to order.customer,
    "phone" to order.phone,
    "address" to order.address,
    "items" to order.items,
    "total" to order.total,
    "payment" to order.payment,
    "cashGiven" to order.cashGiven,
    "status" to order.status,
    "paid" to order.paid,
    "waterQty" to order.waterQty,
    "gasQty" to order.gasQty,
    "demo" to order.demo,
    "pixReported" to order.pixReported,
    "orderNumber" to order.orderNumber,
    "customerUid" to order.customerUid,
    "customerFcmToken" to order.customerFcmToken
)

private fun firestoreDocumentToOrder(documentId: String, data: Map<String, Any>): Order? = try {
    Order(
        id = (data["id"] as? Number)?.toLong() ?: documentId.toLong(),
        customer = data["customer"] as? String ?: "",
        phone = data["phone"] as? String ?: "",
        address = data["address"] as? String ?: "",
        items = data["items"] as? String ?: "",
        total = (data["total"] as? Number)?.toDouble() ?: 0.0,
        payment = data["payment"] as? String ?: "Dinheiro",
        cashGiven = (data["cashGiven"] as? Number)?.toDouble() ?: 0.0,
        status = data["status"] as? String ?: "Pendente",
        paid = data["paid"] as? Boolean ?: false,
        waterQty = (data["waterQty"] as? Number)?.toInt() ?: 0,
        gasQty = (data["gasQty"] as? Number)?.toInt() ?: 0,
        demo = data["demo"] as? Boolean ?: false,
        pixReported = data["pixReported"] as? Boolean ?: false,
        orderNumber = (data["orderNumber"] as? Number)?.toInt() ?: 0,
        customerUid = data["customerUid"] as? String ?: "",
        customerFcmToken = data["customerFcmToken"] as? String ?: ""
    )
} catch (_: Exception) { null }

private fun productsJson(products: List<Product>): String {
    val array = JSONArray()
    products.forEach { array.put(JSONObject().put("name", it.name).put("price", it.price).put("isGas", it.isGas)) }
    return array.toString()
}

private fun authenticateBiometric(
    context: Context,
    title: String,
    onSuccess: () -> Unit,
    onError: (String) -> Unit
) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
        onError("A entrada por digital exige Android 9 ou superior neste aplicativo. Use a senha.")
        return
    }
    val activity = context as? ComponentActivity
    if (activity == null) {
        onError("Não foi possível abrir a autenticação biométrica. Use a senha.")
        return
    }
    try {
        val prompt = BiometricPrompt.Builder(activity)
            .setTitle(title)
            .setSubtitle("Confirme sua identidade com a biometria cadastrada neste aparelho")
            .setNegativeButton("Cancelar", activity.mainExecutor, DialogInterface.OnClickListener { _, _ -> })
            .build()
        prompt.authenticate(
            CancellationSignal(),
            activity.mainExecutor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                    onError(errString?.toString() ?: "Autenticação cancelada. Use a senha se preferir.")
                }
            }
        )
    } catch (_: Exception) {
        onError("Não foi possível iniciar a digital. Confira se há biometria cadastrada no aparelho.")
    }
}

@Composable
private fun AguaGasApp(prefs: android.content.SharedPreferences) {
    val context = LocalContext.current
    var page by remember { mutableStateOf(Page.HOME) }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var showConfirmPassword by remember { mutableStateOf(false) }
    var showDeliveryPassword by remember { mutableStateOf(false) }
    var showDeliveryDraft by remember { mutableStateOf(false) }
    var showDeliveryConfirmDraft by remember { mutableStateOf(false) }
    var adminAuthorizationInput by remember { mutableStateOf("") }
    var adminAuthorizationDraft by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var firstSetup by remember { mutableStateOf(false) }
    var adminBiometricOptIn by remember { mutableStateOf(prefs.getBoolean("admin_biometric_enabled", false)) }
    var deliveryBiometricOptIn by remember { mutableStateOf(prefs.getBoolean("delivery_biometric_enabled", false)) }
    var products by remember { mutableStateOf(loadProducts(prefs)) }
    var newProductName by remember { mutableStateOf("") }
    var newProductPrice by remember { mutableStateOf("") }
    var newProductIsGas by remember { mutableStateOf(false) }
    var productToDelete by remember { mutableStateOf<Product?>(null) }
    var orders by remember { mutableStateOf(loadOrders(prefs)) }
    var firebaseStatus by remember { mutableStateOf("Conectando ao Firebase…") }
    val selectedOrders = remember { mutableStateListOf<Long>() }
    var customer by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var waterQty by remember { mutableStateOf("0") }
    var gasQty by remember { mutableStateOf("0") }
    var payment by remember { mutableStateOf("Dinheiro") }
    var cashGiven by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var pixKey by remember { mutableStateOf(prefs.getString("pix_key", "") ?: "") }
    var pixKeyDraft by remember { mutableStateOf(pixKey) }
    var pixKeyType by remember { mutableStateOf(prefs.getString("pix_key_type", "CPF") ?: "CPF") }
    var pixKeyTypeDraft by remember { mutableStateOf(pixKeyType) }
    var pixKeyTypeMenuExpanded by remember { mutableStateOf(false) }
    var pixRecipientName by remember { mutableStateOf(prefs.getString("pix_recipient_name", "") ?: "") }
    var pixRecipientNameDraft by remember { mutableStateOf(pixRecipientName) }
    var deliveryPassword by remember { mutableStateOf("") }
    var deliveryConfirmPassword by remember { mutableStateOf("") }
    var deliveryFirstSetup by remember { mutableStateOf(false) }
    var deliveryPasswordDraft by remember { mutableStateOf("") }
    var deliveryPasswordConfirmDraft by remember { mutableStateOf("") }
    var clientHouseNumber by remember { mutableStateOf(prefs.getString("client_house_number", "") ?: "") }
    var cepSearching by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    var salesDailyWater by remember { mutableStateOf(0) }
    var salesDailyGas by remember { mutableStateOf(0) }
    var salesMonthlyWater by remember { mutableStateOf(0) }
    var salesMonthlyGas by remember { mutableStateOf(0) }
    var clientName by remember { mutableStateOf(prefs.getString("client_name", "") ?: "") }
    var clientPhone by remember { mutableStateOf(prefs.getString("client_phone", "") ?: "") }
    var clientCep by remember { mutableStateOf(prefs.getString("client_cep", "") ?: "") }
    var clientAddress by remember { mutableStateOf(prefs.getString("client_street_address", prefs.getString("client_address", "")) ?: "") }
    var clientProfileSaved by remember { mutableStateOf(prefs.getBoolean("client_profile_saved", false)) }
    var clientLastOrderId by remember { mutableStateOf<Long?>(null) }
    var orderSubmitting by remember { mutableStateOf(false) }

    val currentOrders by rememberUpdatedState(orders)
    val currentPage by rememberUpdatedState(page)
    SideEffect { MainActivity.isOnDeliveryScreen = page == Page.DELIVERY }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            val currentDay = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(java.util.Date())
            val currentMonth = java.text.SimpleDateFormat("yyyy-MM", Locale.US).format(java.util.Date())
            if (prefs.getString("salesDailyDate", "") != currentDay) {
                salesDailyWater = 0
                salesDailyGas = 0
            }
            if (prefs.getString("salesMonthlyKey", "") != currentMonth) {
                salesMonthlyWater = 0
                salesMonthlyGas = 0
            }
        }
    }
    val firestore = remember { FirebaseFirestore.getInstance() }
    val canSeeAllOrders = !prefs.getString("admin_password_hash", null).isNullOrBlank() || page == Page.DELIVERY

    DisposableEffect(firestore, canSeeAllOrders) {
        var ordersRegistration: ListenerRegistration? = null
        var configRegistration: ListenerRegistration? = null
        var disposed = false
        val auth = FirebaseAuth.getInstance()
        FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
            if (!token.isNullOrBlank()) prefs.edit().putString("customer_fcm_token", token).apply()
        }

        fun attachListeners(uid: String) {
            if (disposed) return
            if (canSeeAllOrders) registerAdminPushToken(prefs, firestore)
            val orderQuery = if (canSeeAllOrders) {
                firestore.collection("orders")
            } else {
                firestore.collection("orders").whereEqualTo("customerUid", uid)
            }
            ordersRegistration?.remove()
            ordersRegistration = orderQuery.addSnapshotListener { snapshot, error ->
                if (disposed) return@addSnapshotListener
                if (error != null) {
                    firebaseStatus = "Firebase: erro ao sincronizar pedidos (${error.localizedMessage ?: "verifique as regras"})"
                    return@addSnapshotListener
                }
                if (snapshot == null) return@addSnapshotListener
                val remoteOrders = snapshot.documents.mapNotNull { doc ->
                    doc.data?.let { firestoreDocumentToOrder(doc.id, it) }
                }.filterNot { it.demo }

                // Alerta somente para pedido em dinheiro recém-criado ou quando o cliente
                // informa o Pix no botão verde. Criar pedido Pix, por si só, não toca aviso.
                val alertKeys = remoteOrders.filter { it.status == "Pendente" }.mapNotNull { order ->
                    when {
                        order.payment.equals("PIX", ignoreCase = true) && order.pixReported -> "${order.id}:pix"
                        !order.payment.equals("PIX", ignoreCase = true) -> "${order.id}:cash"
                        else -> null
                    }
                }.toSet()
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    if (!disposed && currentPage != Page.DELIVERY &&
                        !prefs.getString("admin_password_hash", null).isNullOrBlank()) {
                        val previousRaw = prefs.getString("admin_seen_order_alert_keys", null)
                        if (previousRaw == null) {
                            // Inicialização silenciosa: não anunciar pedidos antigos ao abrir o app.
                            prefs.edit().putString("admin_seen_order_alert_keys", alertKeys.joinToString(",")).apply()
                        } else {
                            val previousSet = previousRaw.split(",").filter { it.isNotBlank() }.toSet()
                            val newKeys = alertKeys - previousSet
                            if (MainActivity.isAppVisible && newKeys.isNotEmpty()) announceNewOrder(context)
                            prefs.edit().putString("admin_seen_order_alert_keys", alertKeys.joinToString(",")).apply()
                        }
                    }
                }

                // A nuvem é a fonte da verdade. Não restaurar do armazenamento local pedidos
                // removidos após cancelamento ou conclusão.
                val merged = remoteOrders.filter { it.status != "Concluído" && it.status != "Cancelado" }
                    .sortedBy { it.id }
                orders = merged
                saveOrders(prefs, merged)
                // Remover documentos de teste antigos e registros já encerrados, sem tocar em pedidos ativos.
                snapshot.documents.filter { doc ->
                    doc.getBoolean("demo") == true || doc.getString("status") == "Concluído" || doc.getString("status") == "Cancelado"
                }.forEach { doc -> doc.reference.delete() }
                firebaseStatus = "Sincronização ativa · ${remoteOrders.size} pedido(s)"
            }

            configRegistration?.remove()
            configRegistration = firestore.collection("appConfig").document("main")
                .addSnapshotListener { snapshot, error ->
                    if (disposed) return@addSnapshotListener
                    if (error != null) {
                        firebaseStatus = "Firebase conectado parcialmente; confira as regras de configuração."
                        return@addSnapshotListener
                    }
                    if (snapshot != null && snapshot.exists()) {
                        val remoteProductsJson = snapshot.getString("productsJson")
                        if (!remoteProductsJson.isNullOrBlank() && remoteProductsJson != "[]") {
                            prefs.edit().putString("products_json", remoteProductsJson).apply()
                            products = loadProducts(prefs)
                        } else if (canSeeAllOrders && products.isNotEmpty()) {
                            // Se a configuração remota antiga estiver sem catálogo, republicar os produtos locais da Administração.
                            firestore.collection("appConfig").document("main")
                                .set(mapOf("productsJson" to productsJson(products)), SetOptions.merge())
                        }
                        snapshot.getString("pixKey")?.let {
                            pixKey = it
                            pixKeyDraft = it
                            prefs.edit().putString("pix_key", it).apply()
                        }
                        snapshot.getString("pixKeyType")?.let {
                            pixKeyType = it
                            pixKeyTypeDraft = it
                            prefs.edit().putString("pix_key_type", it).apply()
                        }
                        snapshot.getString("pixRecipientName")?.let {
                            pixRecipientName = it
                            pixRecipientNameDraft = it
                            prefs.edit().putString("pix_recipient_name", it).apply()
                        }
                        snapshot.getString("deliveryPasswordHash")?.takeIf { it.isNotBlank() }?.let { sharedHash ->
                            val safeHash = if (sharedHash == hashPassword("ENT-583941")) hashPassword(DEFAULT_DELIVERY_PASSWORD) else sharedHash
                            prefs.edit().putString("delivery_password_hash", safeHash).apply()
                            if (safeHash != sharedHash && canSeeAllOrders) {
                                firestore.collection("appConfig").document("main").set(mapOf("deliveryPasswordHash" to safeHash), SetOptions.merge())
                            }
                        }
                        val currentDay = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(java.util.Date())
                        val currentMonth = java.text.SimpleDateFormat("yyyy-MM", Locale.US).format(java.util.Date())
                        prefs.edit().putString("salesDailyDate", snapshot.getString("salesDailyDate") ?: "")
                            .putString("salesMonthlyKey", snapshot.getString("salesMonthlyKey") ?: "").apply()
                        salesDailyWater = if (snapshot.getString("salesDailyDate") == currentDay) (snapshot.getLong("salesDailyWater") ?: 0L).toInt() else 0
                        salesDailyGas = if (snapshot.getString("salesDailyDate") == currentDay) (snapshot.getLong("salesDailyGas") ?: 0L).toInt() else 0
                        salesMonthlyWater = if (snapshot.getString("salesMonthlyKey") == currentMonth) (snapshot.getLong("salesMonthlyWater") ?: 0L).toInt() else 0
                        salesMonthlyGas = if (snapshot.getString("salesMonthlyKey") == currentMonth) (snapshot.getLong("salesMonthlyGas") ?: 0L).toInt() else 0
                    } else if (canSeeAllOrders) {
                        val initialConfig = mapOf(
                            "productsJson" to productsJson(products),
                            "pixKey" to pixKey,
                            "pixKeyType" to pixKeyType,
                            "pixRecipientName" to pixRecipientName,
                            "deliveryPasswordHash" to (prefs.getString("delivery_password_hash", hashPassword(DEFAULT_DELIVERY_PASSWORD)) ?: hashPassword(DEFAULT_DELIVERY_PASSWORD)),
                            "adminFcmToken" to (prefs.getString("admin_fcm_token", "") ?: "")
                        )
                        firestore.collection("appConfig").document("main").set(initialConfig, SetOptions.merge())
                    }
                }
        }

        if (auth.currentUser != null) {
            attachListeners(auth.currentUser!!.uid)
        } else {
            auth.signInAnonymously()
                .addOnSuccessListener { result ->
                    result.user?.uid?.let { attachListeners(it) }
                }
                .addOnFailureListener { e ->
                    if (!disposed) firebaseStatus = "Firebase: não conectou (${e.localizedMessage ?: "verifique a internet"})"
                }
        }
        onDispose {
            disposed = true
            ordersRegistration?.remove()
            configRegistration?.remove()
        }
    }

    fun persistOrders(updated: List<Order>) {
        // Salva localmente de imediato e garante autenticação anônima antes de enviar à nuvem.
        val submittedFromCustomer = page == Page.CUSTOMER
        orders = updated
        saveOrders(prefs, updated)

        fun uploadForUid(uid: String) {
            val prepared = updated.map { order ->
                if (!order.demo && order.customerUid.isBlank() && submittedFromCustomer) order.copy(customerUid = uid) else order
            }
            orders = prepared
            saveOrders(prefs, prepared)
            val toUpload = prepared.filter { order -> !order.demo && (canSeeAllOrders || order.customerUid == uid) }
            if (toUpload.isEmpty()) return
            toUpload.forEach { order ->
                firestore.collection("orders").document(order.id.toString()).set(orderToFirestoreMap(order))
                    .addOnSuccessListener { firebaseStatus = "Pedido sincronizado com o Firebase." }
                    .addOnFailureListener { e -> firebaseStatus = "Falha ao enviar pedido: ${e.localizedMessage ?: "verifique as regras do Firestore"}" }
            }
        }

        val auth = FirebaseAuth.getInstance()
        val currentUid = auth.currentUser?.uid
        if (!currentUid.isNullOrBlank()) {
            uploadForUid(currentUid)
        } else {
            auth.signInAnonymously()
                .addOnSuccessListener { result ->
                    val uid = result.user?.uid
                    if (!uid.isNullOrBlank()) uploadForUid(uid)
                    else firebaseStatus = "Firebase: autenticação anônima não retornou usuário."
                }
                .addOnFailureListener { e -> firebaseStatus = "Pedido salvo neste aparelho, mas não sincronizado: ${e.localizedMessage ?: "falha de autenticação"}" }
        }
    }
    fun publishSharedConfig() {
        val payload = mapOf(
            "productsJson" to productsJson(products),
            "pixKey" to pixKey,
            "pixKeyType" to pixKeyType,
            "pixRecipientName" to pixRecipientName
        )
        val auth = FirebaseAuth.getInstance()
        if (auth.currentUser != null) {
            firestore.collection("appConfig").document("main").set(payload, SetOptions.merge())
                .addOnSuccessListener { firebaseStatus = "Configuração compartilhada atualizada." }
                .addOnFailureListener { e -> firebaseStatus = "Não foi possível sincronizar a configuração: ${e.localizedMessage ?: "erro"}" }
        } else {
            auth.signInAnonymously().addOnSuccessListener {
                firestore.collection("appConfig").document("main").set(payload, SetOptions.merge())
                    .addOnFailureListener { e -> firebaseStatus = "Não foi possível sincronizar a configuração: ${e.localizedMessage ?: "erro"}" }
            }.addOnFailureListener { e -> firebaseStatus = "Firebase: não conectou (${e.localizedMessage ?: "erro"})" }
        }
    }
    fun updateOrder(updated: Order) {
        persistOrders(orders.map { if (it.id == updated.id) updated else it })
    }
    fun removeOrder(order: Order, countSales: Boolean, onDone: (() -> Unit)? = null) {
        val orderRef = firestore.collection("orders").document(order.id.toString())
        orderRef.delete().addOnSuccessListener {
            firestore.collection("orders").document(order.id.toString()).collection("messages").get()
                .addOnSuccessListener { snap ->
                    val batch = firestore.batch()
                    snap.documents.forEach { batch.delete(it.reference) }
                    batch.commit()
                }
            val remaining = orders.filterNot { it.id == order.id }
            orders = remaining
            saveOrders(prefs, remaining)
            message = if (countSales) "Entrega concluída. Totais atualizados." else "Pedido cancelado e removido."
            onDone?.invoke()
        }.addOnFailureListener { e -> message = "Não foi possível remover o pedido da nuvem: ${e.localizedMessage ?: "verifique a conexão e as regras do Firebase"}" }
    }
    fun completeOrderAndCount(order: Order, onDone: () -> Unit) {
        val orderRef = firestore.collection("orders").document(order.id.toString())
        val configRef = firestore.collection("appConfig").document("main")
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(java.util.Date())
        val month = java.text.SimpleDateFormat("yyyy-MM", Locale.US).format(java.util.Date())
        firestore.runTransaction { transaction ->
            val remoteOrder = transaction.get(orderRef)
            val config = transaction.get(configRef)
            if (!remoteOrder.exists()) return@runTransaction false
            val oldDay = config.getString("salesDailyDate").orEmpty()
            val oldMonth = config.getString("salesMonthlyKey").orEmpty()
            val dayWater = if (oldDay == today) (config.getLong("salesDailyWater") ?: 0L).toInt() else 0
            val dayGas = if (oldDay == today) (config.getLong("salesDailyGas") ?: 0L).toInt() else 0
            val monthWater = if (oldMonth == month) (config.getLong("salesMonthlyWater") ?: 0L).toInt() else 0
            val monthGas = if (oldMonth == month) (config.getLong("salesMonthlyGas") ?: 0L).toInt() else 0
            transaction.set(configRef, mapOf(
                "salesDailyDate" to today,
                "salesDailyWater" to dayWater + order.waterQty,
                "salesDailyGas" to dayGas + order.gasQty,
                "salesMonthlyKey" to month,
                "salesMonthlyWater" to monthWater + order.waterQty,
                "salesMonthlyGas" to monthGas + order.gasQty
            ), SetOptions.merge())
            transaction.delete(orderRef)
            true
        }.addOnSuccessListener { counted ->
            if (counted) {
                val savedDay = prefs.getString("salesDailyDate", "").orEmpty()
                val savedMonth = prefs.getString("salesMonthlyKey", "").orEmpty()
                salesDailyWater = if (savedDay == today) salesDailyWater + order.waterQty else order.waterQty
                salesDailyGas = if (savedDay == today) salesDailyGas + order.gasQty else order.gasQty
                salesMonthlyWater = if (savedMonth == month) salesMonthlyWater + order.waterQty else order.waterQty
                salesMonthlyGas = if (savedMonth == month) salesMonthlyGas + order.gasQty else order.gasQty
                prefs.edit().putString("salesDailyDate", today).putString("salesMonthlyKey", month).apply()
                firestore.collection("orders").document(order.id.toString()).collection("messages").get().addOnSuccessListener { snap ->
                    val batch = firestore.batch(); snap.documents.forEach { batch.delete(it.reference) }; batch.commit()
                }
                val remaining = orders.filterNot { it.id == order.id }
                orders = remaining; saveOrders(prefs, remaining)
                onDone()
            } else {
                message = "Este pedido não está mais na nuvem. Atualize a lista antes de concluir."
            }
        }.addOnFailureListener { e -> message = "Não foi possível registrar a venda: ${e.localizedMessage ?: "verifique a conexão e as regras do Firebase"}" }
    }
    fun nextOrderNumber(): Int = (orders.filterNot { it.demo }.maxOfOrNull { it.orderNumber } ?: 0) + 1
    fun parseQty(value: String): Int = value.toIntOrNull()?.coerceIn(0, 999) ?: 0
    fun openMap(order: Order) {
        // Abre uma rota de carro até o endereço usando a localização atual como origem.
        val routeUrl = "https://www.google.com/maps/dir/?api=1&destination=${Uri.encode(order.address)}&travelmode=driving"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(routeUrl))
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
            message = "Não foi possível abrir o Google Maps. Confira se há navegador ou Maps instalado."
        }
    }

    if (productToDelete != null) {
        val deleting = productToDelete!!
        AlertDialog(
            onDismissRequest = { productToDelete = null },
            title = { Text("Apagar produto?") },
            text = { Text("${deleting.name} será removido do catálogo para novos pedidos. Pedidos ativos não serão alterados.") },
            confirmButton = {
                TextButton(onClick = {
                    val updated = products.filterNot { it.name == deleting.name }
                    products = updated
                    saveProducts(prefs, updated)
                    publishSharedConfig()
                    productToDelete = null
                    message = "Produto ${deleting.name} removido e enviado para sincronização."
                }) { Text("APAGAR", color = Color(0xFFB71C1C)) }
            },
            dismissButton = { TextButton(onClick = { productToDelete = null }) { Text("CANCELAR") } }
        )
    }

    Column(
        modifier = Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF10264A), Navy, Color(0xFF071020))))
            .windowInsetsPadding(WindowInsets.navigationBars)
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        when (page) {
            Page.HOME -> {
                Spacer(Modifier.height(25.dp))
                Text("💧 🔥", fontSize = 48.sp)
                Text("Água & Gás", color = Color.White, fontSize = 31.sp, fontWeight = FontWeight.ExtraBold)
                Text("EXPRESS", color = Orange, fontSize = 19.sp, fontWeight = FontWeight.Bold, letterSpacing = 5.sp)
                Spacer(Modifier.height(32.dp))
                Text("Escolha como deseja entrar", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(20.dp))
                ModeCard("CLIENTE", "Fazer pedido de água e gás", "🛍️", Green) {
                    message = ""; error = ""
                    if (clientProfileSaved) page = Page.CUSTOMER else page = Page.CUSTOMER
                }
                Spacer(Modifier.height(14.dp))
                ModeCard("ADMINISTRAÇÃO", "Pedidos, pagamentos e produtos", "⚙️", Orange) {
                    firstSetup = prefs.getString("admin_password_hash", null).isNullOrBlank()
                    password = ""; confirmPassword = ""; adminAuthorizationInput = ""; error = ""; page = Page.LOGIN
                }
                Spacer(Modifier.height(14.dp))
                ModeCard("ENTREGADOR", "Consultar e atualizar entregas", "🚚", Blue) {
                    deliveryFirstSetup = false
                    deliveryPassword = ""; deliveryConfirmPassword = ""; error = ""; page = Page.DELIVERY_LOGIN
                }
                Spacer(Modifier.height(20.dp))
                Text("Pediu, chegou!", color = LightBlue, fontWeight = FontWeight.Bold)
            }

            Page.LOGIN -> {
                Header("Acesso administrativo", "🔐")
                Text(if (firstSetup) "Informe o código de autorização e crie a senha inicial (mínimo 6 caracteres)." else "Digite sua senha.",
                    color = Color.White, textAlign = TextAlign.Center)
                Spacer(Modifier.height(18.dp))
                if (firstSetup) {
                    OutlinedTextField(
                        value = adminAuthorizationInput,
                        onValueChange = { adminAuthorizationInput = it.filter(Char::isDigit).take(6); error = "" },
                        label = { Text("Código de autorização (6 números)", color = Color.White) }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        textStyle = androidx.compose.ui.text.TextStyle(color = Color.White), modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(10.dp))
                }
                OutlinedTextField(
                    value = password, onValueChange = { password = it; error = "" },
                    label = { Text("Senha administrativa", color = Color.White) }, singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(color = Color.White),
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = { TextButton(onClick = { showPassword = !showPassword }) { Text(if (showPassword) "OCULTAR" else "MOSTRAR", color = LightBlue) } },
                    modifier = Modifier.fillMaxWidth()
                )
                if (firstSetup) {
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = confirmPassword, onValueChange = { confirmPassword = it; error = "" },
                        label = { Text("Confirmar senha", color = Color.White) }, singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(color = Color.White),
                        visualTransformation = if (showConfirmPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = { TextButton(onClick = { showConfirmPassword = !showConfirmPassword }) { Text(if (showConfirmPassword) "OCULTAR" else "MOSTRAR", color = LightBlue) } },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Checkbox(checked = adminBiometricOptIn, onCheckedChange = { adminBiometricOptIn = it })
                    Text("Ativar acesso por digital neste aparelho", color = Color.White, fontSize = 13.sp)
                }
                ErrorText(error)
                Spacer(Modifier.height(16.dp))
                MainButton(if (firstSetup) "CRIAR SENHA" else "ENTRAR") {
                    if (firstSetup) {
                        val requiredCode = prefs.getString("admin_authorization_code", DEFAULT_ADMIN_AUTH_CODE) ?: DEFAULT_ADMIN_AUTH_CODE
                        when {
                            adminAuthorizationInput != requiredCode -> error = "Código de autorização incorreto."
                            password.length < 6 -> error = "Use pelo menos 6 caracteres."
                            password != confirmPassword -> error = "As senhas não coincidem."
                            else -> {
                                prefs.edit().putString("admin_password_hash", hashPassword(password))
                                    .putBoolean("admin_biometric_enabled", adminBiometricOptIn).apply()
                                password = ""; confirmPassword = ""; adminAuthorizationInput = ""; error = ""; page = Page.ADMIN
                            }
                        }
                    } else {
                        val saved = prefs.getString("admin_password_hash", null)
                        if (saved != null && saved == hashPassword(password)) {
                            prefs.edit().putBoolean("admin_biometric_enabled", adminBiometricOptIn).apply()
                            password = ""; error = ""; page = Page.ADMIN
                        } else error = "Senha incorreta."
                    }
                }
                if (!firstSetup && prefs.getBoolean("admin_biometric_enabled", false)) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            authenticateBiometric(context, "Acesso administrativo",
                                onSuccess = { error = ""; page = Page.ADMIN },
                                onError = { error = it })
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("ENTRAR COM DIGITAL", color = Color.White) }
                }
                if (!firstSetup) TextButton(onClick = { page = Page.RECOVERY }) {
                    Text("Esqueci minha senha", color = LightBlue)
                }
                BackButton { page = Page.HOME }
            }

            Page.DELIVERY_LOGIN -> {
                Header("Acesso do entregador", "🚚")
                Text("Digite a senha do entregador fornecida pela administração.", color = Color.White, textAlign = TextAlign.Center)
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = deliveryPassword,
                    onValueChange = { deliveryPassword = it; error = "" },
                    label = { Text("Senha do entregador", color = Color.White) },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(color = Color.White),
                    visualTransformation = if (showDeliveryPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = { TextButton(onClick = { showDeliveryPassword = !showDeliveryPassword }) { Text(if (showDeliveryPassword) "OCULTAR" else "MOSTRAR", color = LightBlue) } },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Checkbox(checked = deliveryBiometricOptIn, onCheckedChange = { deliveryBiometricOptIn = it })
                    Text("Ativar acesso por digital neste aparelho", color = Color.White, fontSize = 13.sp)
                }
                ErrorText(error)
                Spacer(Modifier.height(12.dp))
                MainButton("ENTRAR") {
                    if (prefs.getString("delivery_password_hash", null) == hashPassword(deliveryPassword) || prefs.getString("delivery_password_hash", null) == hashPassword(deliveryPassword.replace(" ", ""))) {
                        prefs.edit().putBoolean("delivery_biometric_enabled", deliveryBiometricOptIn).apply()
                        deliveryPassword = ""; error = ""; selectedOrders.clear(); page = Page.DELIVERY
                    } else error = "Senha incorreta. Peça a senha à administração."
                }
                if (!deliveryFirstSetup && prefs.getBoolean("delivery_biometric_enabled", false)) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            authenticateBiometric(context, "Acesso do entregador",
                                onSuccess = { error = ""; selectedOrders.clear(); page = Page.DELIVERY },
                                onError = { error = it })
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("ENTRAR COM DIGITAL", color = Color.White) }
                }
                BackButton { error = ""; page = Page.HOME }
            }

            Page.CUSTOMER -> {
                Header("Faça seu pedido", "🛍️")
                if (!clientProfileSaved) {
                    Text("Primeiro, preencha seus dados para entrega.", color = Color.White, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(12.dp))
                    InputField("Nome completo", clientName) { clientName = it }
                    InputField("Telefone", clientPhone) { clientPhone = it }
                    InputField("CEP", clientCep) { clientCep = it.filter { ch -> ch.isDigit() }.take(8) }
                    OutlinedButton(
                        onClick = {
                            val digits = clientCep.filter(Char::isDigit)
                            if (digits.length != 8) { message = "Digite um CEP com 8 números." }
                            else {
                                cepSearching = true
                                message = "Consultando CEP…"
                                coroutineScope.launch {
                                    val result = withContext(Dispatchers.IO) {
                                        try {
                                            val connection = (URL("https://viacep.com.br/ws/$digits/json/").openConnection() as HttpURLConnection)
                                            connection.connectTimeout = 8000
                                            connection.readTimeout = 8000
                                            connection.requestMethod = "GET"
                                            connection.inputStream.bufferedReader().use { it.readText() }.let { org.json.JSONObject(it) }
                                        } catch (_: Exception) { null }
                                    }
                                    cepSearching = false
                                    if (result == null || result.optBoolean("erro", false)) {
                                        message = "Não foi possível localizar o CEP. Você pode preencher o endereço manualmente."
                                    } else {
                                        val parts = listOf(result.optString("logradouro"), result.optString("bairro"), listOf(result.optString("localidade"), result.optString("uf")).filter { it.isNotBlank() }.joinToString(" - "))
                                            .filter { it.isNotBlank() && it != "null" }
                                        clientAddress = parts.joinToString(", ")
                                        message = if (clientAddress.isBlank()) "CEP localizado. Preencha o endereço manualmente." else "Endereço localizado. Informe o número da casa abaixo."
                                    }
                                }
                            }
                        }, enabled = !cepSearching, modifier = Modifier.fillMaxWidth()
                    ) { Text(if (cepSearching) "PESQUISANDO CEP…" else "PESQUISAR CEP") }
                    InputField("Rua, bairro e cidade", clientAddress) { clientAddress = it }
                    InputField("Número da casa", clientHouseNumber) { clientHouseNumber = it }
                    ErrorText(message)
                    Spacer(Modifier.height(10.dp))
                    MainButton("SALVAR CADASTRO E CONTINUAR") {
                        when {
                            clientName.isBlank() -> message = "Informe seu nome."
                            clientPhone.isBlank() -> message = "Informe seu telefone."
                            clientAddress.isBlank() -> message = "Informe a rua, bairro e cidade."
                            clientHouseNumber.isBlank() -> message = "Informe o número da casa."
                            else -> {
                                clientProfileSaved = true
                                prefs.edit().putString("client_name", clientName.trim())
                                    .putString("client_phone", clientPhone.trim())
                                    .putString("client_cep", clientCep.trim())
                                    .putString("client_street_address", clientAddress.trim())
                                    .putString("client_house_number", clientHouseNumber.trim())
                                    .putString("client_address", listOf(clientAddress.trim(), clientHouseNumber.trim()).filter { it.isNotBlank() }.joinToString(", "))
                                    .putBoolean("client_profile_saved", true).apply()
                                message = ""
                            }
                        }
                    }
                } else {
                    Text("Olá, $clientName!", color = LightBlue, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text("Telefone: $clientPhone", color = Color.White, textAlign = TextAlign.Center, fontSize = 12.sp, lineHeight = 14.sp)
                    Text("Endereço: ${listOf(clientAddress, clientHouseNumber).filter { it.isNotBlank() }.joinToString(", ")}", color = Color.White, textAlign = TextAlign.Center, fontSize = 12.sp, lineHeight = 14.sp)
                    TextButton(onClick = {
                        clientProfileSaved = false
                        message = ""
                    }) { Text("Alterar endereço / dados", color = LightBlue) }
                    Spacer(Modifier.height(2.dp))
                    var clientQuantities by remember { mutableStateOf(products.associate { it.name to 0 }) }
                    LaunchedEffect(products.map { it.name to it.isGas }) {
                        clientQuantities = products.associate { it.name to (clientQuantities[it.name] ?: 0) }
                    }
                    var pixCopied by remember { mutableStateOf(false) }
                    val lastClientOrder = orders.firstOrNull { it.id == clientLastOrderId && it.status !in listOf("Concluído", "Cancelado") } ?: orders
                        .filter { !it.demo && it.phone.filter(Char::isDigit) == clientPhone.filter(Char::isDigit) && it.status in listOf("Pendente", "Autorizado", "Em entrega", "Chegou ao endereço") }
                        .maxByOrNull { it.id }
                    LaunchedEffect(lastClientOrder?.id, lastClientOrder?.status) {
                        val arrivalOrder = lastClientOrder
                        if (arrivalOrder != null && arrivalOrder.status == "Chegou ao endereço" &&
                            prefs.getLong("arrival_announced_order_id", -1L) != arrivalOrder.id) {
                            prefs.edit().putLong("arrival_announced_order_id", arrivalOrder.id).apply()
                            announceCustomerArrival(context)
                        }
                    }
                    if (lastClientOrder != null) {
                        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(14.dp)) {
                            Column(Modifier.padding(12.dp)) {
                                Text("ACOMPANHAMENTO DO PEDIDO #${lastClientOrder.orderNumber.toString().padStart(2, '0')}", color = Ink, fontWeight = FontWeight.ExtraBold)
                                Text("Status: ${when (lastClientOrder.status) { "Pendente" -> "Aguardando confirmação"; "Autorizado" -> "Pedido confirmado"; "Em entrega" -> "Saiu para entrega"; "Chegou ao endereço" -> "O entregador chegou"; else -> lastClientOrder.status }}", color = Blue, fontWeight = FontWeight.Bold)
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    Text("ESCOLHA SEUS PRODUTOS", color = LightBlue, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
                    if (products.isEmpty()) {
                        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF17375F)), shape = RoundedCornerShape(12.dp)) {
                            Text("Os produtos ainda estão sincronizando. Volte a esta tela em alguns instantes ou peça à administração para conferir o catálogo.", modifier = Modifier.padding(14.dp), color = Color.White, textAlign = TextAlign.Center)
                        }
                    }
                    products.forEach { product ->
                        val quantity = clientQuantities[product.name] ?: 0
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF10284A)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (product.isGas) Text("🔥", fontSize = 24.sp)
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                                    Text(product.name, color = Color.White, fontWeight = FontWeight.Bold,
                                        fontSize = if (product.name == COMBO_WATER_NAME) 12.sp else 14.sp, maxLines = 2)
                                    Text(money(product.price), color = LightBlue, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                }
                                OutlinedButton(
                                    onClick = { if (quantity > 0) clientQuantities = clientQuantities + (product.name to quantity - 1) },
                                    contentPadding = PaddingValues(horizontal = 11.dp, vertical = 0.dp),
                                    modifier = Modifier.height(36.dp)
                                ) { Text("−", fontSize = 18.sp) }
                                Text("$quantity", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                Button(
                                    onClick = { if (quantity < 99) clientQuantities = clientQuantities + (product.name to quantity + 1) },
                                    contentPadding = PaddingValues(horizontal = 11.dp, vertical = 0.dp),
                                    modifier = Modifier.height(36.dp)
                                ) { Text("+", fontSize = 18.sp) }
                            }
                        }
                    }
                    val clientWaterQty = products.filterNot { it.isGas }.sumOf { clientQuantities[it.name] ?: 0 }
                    val clientGasQty = products.filter { it.isGas }.sumOf { clientQuantities[it.name] ?: 0 }
                    val clientTotal = products.sumOf { product -> (clientQuantities[product.name] ?: 0) * product.price }
                    Text("TOTAL: ${money(clientTotal)}", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
                    if (payment == "PIX" && pixCopied && lastClientOrder != null && lastClientOrder.payment == "PIX" && lastClientOrder.status == "Pendente" && !lastClientOrder.pixReported) {
                        Button(
                            onClick = {
                                updateOrder(lastClientOrder.copy(pixReported = true))
                                message = "Você informou que fez o Pix. A administração ainda precisa conferir o recebimento."
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Green),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) { Text("JÁ FIZ O PAGAMENTO PIX", fontWeight = FontWeight.Bold) }
                    } else if (lastClientOrder != null && lastClientOrder.payment == "PIX" && lastClientOrder.pixReported && lastClientOrder.status == "Pendente") {
                        Text("Pix informado — aguardando conferência da administração", color = Color(0xFF8BE9A5), fontSize = 12.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        RadioButton(selected = payment == "Dinheiro", onClick = { payment = "Dinheiro"; message = "" })
                        Text("Dinheiro", color = Color.White)
                        Spacer(Modifier.width(6.dp))
                        RadioButton(selected = payment == "PIX", onClick = { payment = "PIX"; message = "" })
                        Text("Pix", color = Color.White)
                        Spacer(Modifier.width(6.dp))
                        RadioButton(selected = payment == "Fiado", onClick = { payment = "Fiado"; message = "No momento não estamos com essa opção. Aguarde mais alguns instantes." })
                        Text("Fiado", color = Color.White)
                    }
                    if (payment == "Dinheiro") {
                        InputField("Quanto você vai entregar? (ex.: 50)", cashGiven, true) { cashGiven = it; message = "" }
                        val cashValue = cashGiven.replace(",", ".").toDoubleOrNull() ?: 0.0
                        if (cashGiven.isNotBlank() && cashValue >= clientTotal) {
                            Text("Troco: ${money(cashValue - clientTotal)}", color = Color(0xFF8BE9A5), fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
                        } else if (cashGiven.isNotBlank()) {
                            Text("Faltam: ${money(clientTotal - cashValue)}", color = Color(0xFFFFD0A8), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        } else {
                            Text("Informe o valor que vai entregar para calcular o troco.", color = Color.White, fontSize = 12.sp)
                        }
                    }
                    if (payment == "Fiado") {
                        Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(14.dp)) {
                            Text(
                                "No momento não estamos com essa opção. Aguarde mais alguns instantes.",
                                modifier = Modifier.padding(14.dp), color = Ink, textAlign = TextAlign.Center, fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    if (payment == "PIX") {
                        Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(14.dp)) {
                            Column(Modifier.padding(14.dp)) {
                                Text("CONFIRME O DESTINATÁRIO DO PIX", color = Ink, fontWeight = FontWeight.Bold)
                                Text(if (pixRecipientName.isBlank()) "Nome do recebedor não cadastrado" else "Pix para $pixRecipientName", color = Ink, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.height(10.dp))
                                Text("CHAVE PIX $pixKeyType", color = Blue, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
                                Text(
                                    if (pixKey.isBlank()) "A chave Pix ainda não foi cadastrada pela administração." else pixKey,
                                    modifier = Modifier.padding(start = 10.dp, top = 5.dp, bottom = 8.dp),
                                    color = Ink,
                                    fontSize = 17.sp
                                )
                                if (pixKey.isNotBlank()) {
                                    MainButton("COPIAR CHAVE PIX") {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Chave Pix", pixKey))
                                        pixCopied = true
                                        message = "Chave Pix copiada."
                                    }
                                }
                            }
                        }
                    }
                    ErrorText(message)
                    Spacer(Modifier.height(10.dp))
                    MainButton(if (orderSubmitting) "ENVIANDO PEDIDO…" else "FAZER PEDIDO") {
                        if (orderSubmitting) return@MainButton
                        when {
                            clientWaterQty + clientGasQty == 0 -> message = "Escolha ao menos um produto."
                            products.any { (clientQuantities[it.name] ?: 0) > 0 && it.price <= 0.0 } -> message = "Os preços ainda não foram configurados pela administração."
                            payment == "PIX" && pixKey.isBlank() -> message = "A administração precisa cadastrar a chave Pix."
                            payment == "Fiado" -> message = "No momento não estamos com essa opção. Aguarde mais alguns instantes."
                            payment == "Dinheiro" && (cashGiven.replace(",", ".").toDoubleOrNull() ?: 0.0) < clientTotal -> message = "Informe um valor igual ou maior que o total para calcular o troco."
                            else -> {
                                val items = products.mapNotNull { product ->
                                    val quantity = clientQuantities[product.name] ?: 0
                                    if (quantity > 0) "$quantity ${product.name}" else null
                                }.joinToString(", ")
                                val order = Order(
                                    id = System.currentTimeMillis(),
                                    customer = clientName.trim(),
                                    phone = clientPhone.trim(),
                                    address = listOf(clientCep.trim(), listOf(clientAddress.trim(), clientHouseNumber.trim()).filter { it.isNotBlank() }.joinToString(", ")).filter { it.isNotBlank() }.joinToString(" — "),
                                    items = items,
                                    total = clientTotal,
                                    payment = payment,
                                    cashGiven = if (payment == "Dinheiro") (cashGiven.replace(",", ".").toDoubleOrNull() ?: 0.0) else 0.0,
                                    status = "Pendente",
                                    paid = false,
                                    waterQty = clientWaterQty,
                                    gasQty = clientGasQty,
                                    demo = false,
                                    orderNumber = nextOrderNumber(),
                                    customerUid = FirebaseAuth.getInstance().currentUser?.uid.orEmpty(),
                                    customerFcmToken = prefs.getString("customer_fcm_token", "").orEmpty()
                                )
                                if (orderSubmitting) return@MainButton
                                orderSubmitting = true
                                val auth = FirebaseAuth.getInstance()
                                fun uploadOrderForCustomer(uid: String) {
                                    val orderForCloud = order.copy(customerUid = uid)
                                    firestore.collection("orders").document(orderForCloud.id.toString())
                                        .set(orderToFirestoreMap(orderForCloud))
                                        .addOnSuccessListener {
                                            val updatedOrders = (orders.filterNot { it.id == orderForCloud.id } + orderForCloud).sortedBy { it.id }
                                            orders = updatedOrders
                                            saveOrders(prefs, updatedOrders)
                                            clientLastOrderId = orderForCloud.id
                                            clientQuantities = products.associate { it.name to 0 }
                                            cashGiven = ""
                                            orderSubmitting = false
                                            message = if (orderForCloud.payment.equals("PIX", ignoreCase = true)) {
                                                "Pedido #${orderForCloud.orderNumber.toString().padStart(2, '0')} enviado para a administração. O Pix ainda está pendente."
                                            } else {
                                                "Pedido #${orderForCloud.orderNumber.toString().padStart(2, '0')} enviado para a administração."
                                            }
                                        }
                                        .addOnFailureListener { e ->
                                            orderSubmitting = false
                                            message = "Não foi possível enviar o pedido. Verifique a conexão e as regras do Firebase. ${e.localizedMessage ?: "Tente novamente."}"
                                        }
                                }
                                if (auth.currentUser != null) {
                                    uploadOrderForCustomer(auth.currentUser!!.uid)
                                } else {
                                    auth.signInAnonymously()
                                        .addOnSuccessListener { result ->
                                            val uid = result.user?.uid
                                            if (uid.isNullOrBlank()) {
                                                orderSubmitting = false
                                                message = "Não foi possível autenticar este aparelho no Firebase."
                                            } else uploadOrderForCustomer(uid)
                                        }
                                        .addOnFailureListener { e ->
                                            orderSubmitting = false
                                            message = "Não foi possível conectar ao Firebase. ${e.localizedMessage ?: "Confira a internet e tente novamente."}"
                                        }
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                MainButton("VOLTAR AO INÍCIO") { message = ""; page = Page.HOME }
            }

            Page.ADMIN -> {
                Header("Administração", "⚙️")
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF17375F)), shape = RoundedCornerShape(14.dp)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("VENDAS DE HOJE", color = LightBlue, fontWeight = FontWeight.ExtraBold)
                        Text("Água: $salesDailyWater  •  Gás: $salesDailyGas", color = Color.White, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(5.dp))
                        Text("ACUMULADO DO MÊS", color = LightBlue, fontWeight = FontWeight.ExtraBold)
                        Text("Água: $salesMonthlyWater  •  Gás: $salesMonthlyGas", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.height(18.dp))
                MenuButton("💧  Produtos e preços") { page = Page.PRODUCTS }
                MenuButton("🔑  Configurar chave Pix") {
                    pixKeyDraft = pixKey
                    pixKeyTypeDraft = pixKeyType
                    pixRecipientNameDraft = pixRecipientName
                    message = ""
                    page = Page.PIX_SETTINGS
                }
                MenuButton("🛡️  Código de autorização") {
                    adminAuthorizationDraft = prefs.getString("admin_authorization_code", DEFAULT_ADMIN_AUTH_CODE) ?: DEFAULT_ADMIN_AUTH_CODE
                    message = ""
                    page = Page.ADMIN_CODE_SETTINGS
                }
                MenuButton("🔐  Senha fixa do entregador") {
                    deliveryPasswordDraft = ""
                    deliveryPasswordConfirmDraft = ""
                    message = ""
                    page = Page.DELIVERY_PASSWORD_SETTINGS
                }
                MenuButton("📥  Pedidos recebidos (${orders.count { it.status == "Pendente" }})") { page = Page.ORDERS }
                MenuButton("🚚  Entregas (${orders.count { it.status == "Autorizado" || it.status == "Em entrega" || it.status == "Chegou ao endereço" }})") { page = Page.DELIVERY }
                Spacer(Modifier.height(10.dp))
                MainButton("SAIR DA ADMINISTRAÇÃO") { page = Page.HOME }
                Spacer(Modifier.height(12.dp))

            }

            Page.PRODUCTS -> {
                Header("Produtos e preços", "💧")
                Text("Cadastre, altere ou retire produtos. O catálogo será compartilhado com os clientes pelo Firebase.", color = Color.White, textAlign = TextAlign.Center)
                Spacer(Modifier.height(14.dp))
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF17375F)), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Text("ACRESCENTAR PRODUTO", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
                        OutlinedTextField(
                            value = newProductName,
                            onValueChange = { newProductName = it; message = "" },
                            label = { Text("Nome do produto / marca", color = Color.White) },
                            singleLine = true,
                            textStyle = androidx.compose.ui.text.TextStyle(color = Color.White),
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = newProductPrice,
                            onValueChange = { newProductPrice = it },
                            label = { Text("Preço em reais", color = Color.White) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            textStyle = androidx.compose.ui.text.TextStyle(color = Color.White),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = !newProductIsGas, onClick = { newProductIsGas = false })
                            Text("Água / marca", color = Color.White)
                            Spacer(Modifier.width(10.dp))
                            RadioButton(selected = newProductIsGas, onClick = { newProductIsGas = true })
                            Text("Gás", color = Color.White)
                        }
                        Button(
                            onClick = {
                                val name = newProductName.trim()
                                val price = newProductPrice.replace(",", ".").toDoubleOrNull()
                                when {
                                    name.isBlank() -> message = "Digite o nome do produto."
                                    price == null || price <= 0.0 -> message = "Digite um preço maior que zero."
                                    products.any { it.name.equals(name, ignoreCase = true) } -> message = "Já existe um produto com esse nome."
                                    else -> {
                                        val updated = products + Product(name, price, newProductIsGas)
                                        products = updated
                                        saveProducts(prefs, updated)
                                        publishSharedConfig()
                                        newProductName = ""
                                        newProductPrice = ""
                                        message = "Produto adicionado. A lista do Cliente será atualizada quando o Firebase sincronizar."
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Blue)
                        ) { Text("ACRESCENTAR PRODUTO") }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text("PRODUTOS CADASTRADOS (${products.size})", color = Color.White, fontWeight = FontWeight.ExtraBold)
                if (products.isEmpty()) {
                    Text("Nenhum produto cadastrado. Use o formulário acima para acrescentar um.", color = Color(0xFFFFD0A8), textAlign = TextAlign.Center)
                }
                products.forEachIndexed { index, product ->
                    var priceText by remember(product.name, product.price) {
                        mutableStateOf(if (product.price == 0.0) "" else product.price.toString().replace(".", ","))
                    }
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(16.dp)) {
                        Column(Modifier.padding(14.dp)) {
                            Text(if (product.isGas) "🔥 ${product.name}" else product.name, color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            OutlinedTextField(value = priceText, onValueChange = { priceText = it },
                                label = { Text("Preço em reais") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Ink, unfocusedTextColor = Ink,
                                    focusedContainerColor = Color.White, unfocusedContainerColor = Color.White,
                                    cursorColor = Blue, focusedLabelColor = Blue, unfocusedLabelColor = Ink,
                                    focusedBorderColor = Blue, unfocusedBorderColor = Ink
                                ), modifier = Modifier.fillMaxWidth())
                            Text("Preço atual: ${money(product.price)}", color = Ink)
                            Button(onClick = {
                                val parsed = priceText.replace(",", ".").toDoubleOrNull()
                                if (parsed == null || parsed <= 0.0) message = "Digite um preço maior que zero."
                                else {
                                    val updated = products.toMutableList().also { it[index] = product.copy(price = parsed) }
                                    products = updated
                                    saveProducts(prefs, updated)
                                    publishSharedConfig()
                                    message = "Preço de ${product.name} salvo e enviado para sincronização."
                                }
                            }, modifier = Modifier.fillMaxWidth()) { Text("SALVAR PREÇO") }
                            OutlinedButton(
                                onClick = { productToDelete = product },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFB71C1C))
                            ) { Text("APAGAR PRODUTO") }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
                ErrorText(message)
                Text(firebaseStatus, color = if (firebaseStatus.startsWith("Conectado") || firebaseStatus.startsWith("Configuração compartilhada")) Color(0xFF86EFAC) else Color(0xFFFFD0A8), fontSize = 11.sp, textAlign = TextAlign.Center)
                MainButton("VOLTAR") { message = ""; page = Page.ADMIN }
            }

            Page.ADMIN_CODE_SETTINGS -> {
                Header("Código de autorização", "🛡️")
                Text(
                    "Defina o código que será exigido antes do cadastro inicial de uma senha administrativa NESTE aparelho. Guarde-o com cuidado.",
                    color = Color.White, textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = adminAuthorizationDraft,
                    onValueChange = { adminAuthorizationDraft = it.filter(Char::isDigit).take(6); message = "" },
                    label = { Text("Novo código de autorização (6 números)", color = Color.White) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 18.sp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                        focusedLabelColor = Color.White, unfocusedLabelColor = Color.White,
                        cursorColor = LightBlue, focusedBorderColor = LightBlue, unfocusedBorderColor = LightBlue
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                ErrorText(message)
                Spacer(Modifier.height(12.dp))
                MainButton("SALVAR CÓDIGO") {
                    if (!adminAuthorizationDraft.matches(Regex("\\d{6}"))) {
                        message = "O código deve ter exatamente 6 números."
                    } else {
                        prefs.edit().putString("admin_authorization_code", adminAuthorizationDraft.trim()).apply()
                        message = "Código salvo neste aparelho."
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "Atenção: este código é local neste aparelho. O código inicial desta versão é 160829; alterações feitas aqui não são enviadas para outros celulares.",
                    color = Color(0xFFFFD0A8), fontSize = 12.sp, textAlign = TextAlign.Center
                )
                BackButton { message = ""; page = Page.ADMIN }
            }

            Page.DELIVERY_PASSWORD_SETTINGS -> {
                Header("Senha do entregador", "🔐")
                Text("A senha inicial é 16 08 29. Você pode alterar a senha aqui; a nova senha será compartilhada com os outros aparelhos conectados ao Firebase.", color = Color.White, textAlign = TextAlign.Center)
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = deliveryPasswordDraft,
                    onValueChange = { deliveryPasswordDraft = it; message = "" },
                    label = { Text("Nova senha do entregador", color = Color.White) },
                    singleLine = true, textStyle = androidx.compose.ui.text.TextStyle(color = Color.White),
                    visualTransformation = if (showDeliveryDraft) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = { TextButton(onClick = { showDeliveryDraft = !showDeliveryDraft }) { Text(if (showDeliveryDraft) "OCULTAR" else "MOSTRAR", color = LightBlue) } },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = deliveryPasswordConfirmDraft,
                    onValueChange = { deliveryPasswordConfirmDraft = it; message = "" },
                    label = { Text("Confirmar nova senha", color = Color.White) },
                    singleLine = true, textStyle = androidx.compose.ui.text.TextStyle(color = Color.White),
                    visualTransformation = if (showDeliveryConfirmDraft) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = { TextButton(onClick = { showDeliveryConfirmDraft = !showDeliveryConfirmDraft }) { Text(if (showDeliveryConfirmDraft) "OCULTAR" else "MOSTRAR", color = LightBlue) } },
                    modifier = Modifier.fillMaxWidth()
                )
                ErrorText(message)
                Spacer(Modifier.height(12.dp))
                MainButton("SALVAR SENHA DO ENTREGADOR") {
                    when {
                        deliveryPasswordDraft.length < 6 -> message = "Use pelo menos 6 caracteres."
                        deliveryPasswordDraft != deliveryPasswordConfirmDraft -> message = "As senhas não coincidem."
                        else -> {
                            val newDeliveryHash = hashPassword(deliveryPasswordDraft)
                            prefs.edit().putString("delivery_password_hash", newDeliveryHash).apply()
                            firestore.collection("appConfig").document("main").set(mapOf("deliveryPasswordHash" to newDeliveryHash), SetOptions.merge())
                                .addOnSuccessListener { message = "Senha do entregador atualizada e sincronizada." }
                                .addOnFailureListener { e -> message = "Senha salva neste aparelho, mas não sincronizou: ${e.localizedMessage ?: "erro do Firebase"}" }
                            deliveryPasswordDraft = ""
                            deliveryPasswordConfirmDraft = ""
                        }
                    }
                }
                BackButton { message = ""; page = Page.ADMIN }
            }

            Page.PIX_SETTINGS -> {
                Header("Configurar Pix", "🔑")
                Text(
                    "Cadastre o nome de quem vai receber e a chave Pix. O cliente verá o nome para conferir antes de pagar. Estes dados ficam salvos neste aparelho.",
                    color = Color.White,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = pixRecipientNameDraft,
                    onValueChange = { pixRecipientNameDraft = it; message = "" },
                    label = { Text("Nome completo de quem recebe o Pix", color = Color.White) },
                    textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 18.sp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                        focusedLabelColor = Color.White, unfocusedLabelColor = Color.White,
                        cursorColor = LightBlue, focusedBorderColor = LightBlue, unfocusedBorderColor = LightBlue
                    ),
                    modifier = Modifier.fillMaxWidth(), singleLine = true
                )
                Spacer(Modifier.height(12.dp))
                Text("Tipo da chave Pix", color = Color.White, fontWeight = FontWeight.Bold)
                Box(modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = { pixKeyTypeMenuExpanded = true },
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Blue)
                    ) {
                        Text("CHAVE PIX $pixKeyTypeDraft  ▾", fontWeight = FontWeight.Bold)
                    }
                    DropdownMenu(
                        expanded = pixKeyTypeMenuExpanded,
                        onDismissRequest = { pixKeyTypeMenuExpanded = false }
                    ) {
                        listOf("CPF", "CNPJ", "Celular", "E-mail", "Aleatória").forEach { type ->
                            DropdownMenuItem(
                                text = { Text(type) },
                                onClick = {
                                    pixKeyTypeDraft = type
                                    pixKeyTypeMenuExpanded = false
                                    message = ""
                                }
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = pixKeyDraft,
                    onValueChange = { pixKeyDraft = it; message = "" },
                    label = { Text("Digite o número ou valor da chave Pix", color = Color.White) },
                    textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 18.sp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                        focusedLabelColor = Color.White, unfocusedLabelColor = Color.White,
                        cursorColor = LightBlue, focusedBorderColor = LightBlue, unfocusedBorderColor = LightBlue
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = false,
                    minLines = 2
                )
                Spacer(Modifier.height(12.dp))
                if (pixKey.isNotBlank() || pixRecipientName.isNotBlank()) {
                    Card(colors = CardDefaults.cardColors(containerColor = Color.White),
                        shape = RoundedCornerShape(14.dp)) {
                        Column(Modifier.padding(14.dp)) {
                            Text("Dados do Pix salvos", color = Green, fontWeight = FontWeight.Bold)
                            Text("Recebedor: ${if (pixRecipientName.isBlank()) "não informado" else pixRecipientName}", color = Ink)
                            Text("Chave Pix $pixKeyType: $pixKey", color = Ink)
                        }
                    }
                } else {
                    Text("Nenhuma chave Pix cadastrada ainda.", color = Color(0xFFFFD0A8))
                }
                ErrorText(message)
                Spacer(Modifier.height(12.dp))
                MainButton("SALVAR DADOS DO PIX") {
                    if (pixRecipientNameDraft.isBlank()) {
                        message = "Digite o nome de quem vai receber o Pix."
                    } else if (pixKeyDraft.isBlank()) {
                        message = "Digite a chave Pix antes de salvar."
                    } else {
                        pixKey = pixKeyDraft.trim()
                        pixKeyType = pixKeyTypeDraft
                        pixRecipientName = pixRecipientNameDraft.trim()
                        prefs.edit().putString("pix_key", pixKey)
                            .putString("pix_key_type", pixKeyType)
                            .putString("pix_recipient_name", pixRecipientName).apply()
                        publishSharedConfig()
                        message = "Nome do recebedor e chave Pix salvos e enviados para sincronização."
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "A chave Pix e os preços serão compartilhados com o aplicativo do cliente quando o Firebase estiver conectado.",
                    color = Color(0xFFFFD0A8), fontSize = 12.sp, textAlign = TextAlign.Center
                )
                Text(firebaseStatus, color = if (firebaseStatus.startsWith("Conectado") || firebaseStatus.startsWith("Configuração compartilhada")) Color(0xFF86EFAC) else Color(0xFFFFD0A8), fontSize = 11.sp, textAlign = TextAlign.Center)
                BackButton { message = ""; page = Page.ADMIN }
            }

            Page.NEW_ORDER -> {
                Header("Registrar pedido", "🧾")
                Text("Preencha os dados do cliente e as quantidades.", color = Color.White, textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                InputField("Nome do cliente", customer) { customer = it }
                InputField("Telefone", phone) { phone = it }
                InputField("Endereço completo", address) { address = it }
                Text("Água — ${money(products.firstOrNull { !it.isGas }?.price ?: 0.0)}", color = Color.White, fontWeight = FontWeight.Bold)
                InputField("Quantidade de água", waterQty, true) { waterQty = it }
                Text("Gás — ${money(products.firstOrNull { it.isGas }?.price ?: 0.0)}", color = Color.White, fontWeight = FontWeight.Bold)
                InputField("Quantidade de gás", gasQty, true) { gasQty = it }
                val w = parseQty(waterQty); val g = parseQty(gasQty)
                val waterPrice = products.firstOrNull { !it.isGas }?.price ?: 0.0
                val gasPrice = products.firstOrNull { it.isGas }?.price ?: 0.0
                val total = w * waterPrice + g * gasPrice
                Spacer(Modifier.height(8.dp))
                Text("TOTAL: ${money(total)}", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.ExtraBold)
                Spacer(Modifier.height(12.dp))
                Text("Forma de pagamento", color = Color.White, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = payment == "Dinheiro", onClick = { payment = "Dinheiro" })
                    Text("Dinheiro", color = Color.White)
                    Spacer(Modifier.width(10.dp))
                    RadioButton(selected = payment == "PIX", onClick = { payment = "PIX" })
                    Text("PIX", color = Color.White)
                }
                if (payment == "Dinheiro") {
                    InputField("Valor entregue pelo cliente (ex.: 50)", cashGiven, true) { cashGiven = it }
                    val cash = cashGiven.replace(",", ".").toDoubleOrNull() ?: 0.0
                    Text("Troco: ${money((cash - total).coerceAtLeast(0.0))}", color = LightBlue, fontWeight = FontWeight.Bold)
                }
                ErrorText(message)
                Spacer(Modifier.height(12.dp))
                MainButton("SALVAR PEDIDO") {
                    when {
                        customer.isBlank() -> message = "Informe o nome do cliente."
                        phone.isBlank() -> message = "Informe o telefone."
                        address.isBlank() -> message = "Informe o endereço."
                        w + g == 0 -> message = "Informe uma quantidade maior que zero."
                        total <= 0 -> message = "Cadastre os preços antes de registrar o pedido."
                        payment == "Dinheiro" && (cashGiven.replace(",", ".").toDoubleOrNull() ?: 0.0) < total ->
                            message = "O valor recebido não cobre o total."
                        else -> {
                            val items = buildList {
                                if (w > 0) add("$w Água")
                                if (g > 0) add("$g Gás")
                            }.joinToString(", ")
                            val cash = cashGiven.replace(",", ".").toDoubleOrNull() ?: 0.0
                            val order = Order(System.currentTimeMillis(), customer.trim(), phone.trim(), address.trim(),
                                items, total, payment, if (payment == "Dinheiro") cash else 0.0, "Pendente", false,
                                waterQty = w, gasQty = g, orderNumber = nextOrderNumber())
                            persistOrders((orders + order).sortedBy { it.id })
                            message = ""; page = Page.ORDERS
                        }
                    }
                }
                BackButton { message = ""; page = Page.ADMIN }
            }

            Page.ORDERS, Page.DELIVERY -> {
                val deliveryMode = page == Page.DELIVERY
                val visibleOrders = if (deliveryMode) {
                    orders.filter { !it.demo && it.status in listOf("Autorizado", "Em entrega", "Chegou ao endereço") }
                } else orders.filter { !it.demo && it.status == "Pendente" }
                Header(if (deliveryMode) "Entregas" else "Pedidos recebidos", if (deliveryMode) "🚚" else "📥")
                if (deliveryMode && (message.startsWith("Entrega concluída") || message.startsWith("Chegada registrada"))) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFD9F8E4)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            message,
                            modifier = Modifier.padding(14.dp),
                            color = Color(0xFF126B36),
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                    }
                }
                if (deliveryMode) {
                    val onRouteCount = orders.count { it.status == "Em entrega" }
                    val arrivedCount = orders.count { it.status == "Chegou ao endereço" }
                    val waitingCount = orders.count { it.status == "Autorizado" }
                    Text("A caminho: $onRouteCount  •  Chegou: $arrivedCount  •  Aguardando saída: $waitingCount", color = Color.White, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                }
                if (deliveryMode) {
                    Text("Marque os pedidos autorizados e envie todos de uma vez para a rota.", color = Color.White, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(8.dp))
                    val readyCount = visibleOrders.count { it.status == "Autorizado" }
                    if (readyCount > 0) MainButton("ENVIAR SELECIONADOS (${selectedOrders.size}) PARA ENTREGA") {
                        val ids = selectedOrders.toSet()
                        if (ids.isNotEmpty()) {
                            persistOrders(orders.map {
                                if (it.id in ids && it.status == "Autorizado") it.copy(status = "Em entrega") else it
                            })
                            selectedOrders.clear()
                        }
                    }
                } else {
                    Text("Confira o pagamento primeiro. Depois autorize o pedido para a tela de entregas.", color = Color.White, textAlign = TextAlign.Center)
                }
                Spacer(Modifier.height(12.dp))
                if (visibleOrders.isEmpty()) {
                    Text(if (deliveryMode) "Nenhuma entrega aguardando ou em andamento." else "Nenhum pedido pendente.", color = Color.White, textAlign = TextAlign.Center)
                } else {
                    visibleOrders.sortedBy { if (deliveryMode) it.id else -it.id }.forEach { order ->
                        val cardColor = when {
                            !deliveryMode && order.status == "Cancelado" -> Color(0xFFFFE0E0)
                            deliveryMode && order.status == "Chegou ao endereço" -> Color(0xFFD9F8E4)
                            deliveryMode && order.status == "Em entrega" -> Color(0xFFFFE0E0)
                            deliveryMode && order.status == "Autorizado" -> Color(0xFFFFF0D5)
                            else -> Color.White
                        }
                        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cardColor),
                            shape = RoundedCornerShape(16.dp)) {
                            Column(Modifier.padding(14.dp)) {
                                if (deliveryMode && order.status == "Autorizado") {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(checked = order.id in selectedOrders, onCheckedChange = { checked ->
                                            if (checked) { if (order.id !in selectedOrders) selectedOrders.add(order.id) }
                                            else selectedOrders.remove(order.id)
                                        })
                                        Text("Selecionar para esta rota", color = Ink, fontWeight = FontWeight.Bold)
                                    }
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        "PEDIDO #${order.orderNumber.toString().padStart(2, '0')}",
                                        color = Blue, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp
                                    )
                                    if (!deliveryMode && order.status == "Pendente") {
                                        Button(
                                            onClick = { removeOrder(order, false) },
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB71C1C)),
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                            modifier = Modifier.heightIn(min = 34.dp)
                                        ) { Text("CANCELAR PEDIDO", fontSize = 10.sp, fontWeight = FontWeight.Bold) }
                                    }
                                }
                                Text(order.customer, color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                Text("Telefone: ${order.phone}", color = Ink)
                                Text("Endereço: ${order.address}", color = Ink)
                                val orderItemLabels = order.items.split(",").map { it.trim() }.filter { it.isNotBlank() }
                                if (orderItemLabels.isNotEmpty()) {
                                    Text("ITENS DO PEDIDO", color = Ink, fontWeight = FontWeight.ExtraBold, fontSize = 11.sp)
                                    orderItemLabels.chunked(2).forEach { itemRow ->
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            itemRow.forEach { itemLabel ->
                                                val isGas = itemLabel.contains("gás", ignoreCase = true) || itemLabel.contains("gas", ignoreCase = true)
                                                val tileColor = if (isGas) Color(0xFFDBEAFE) else if (itemRow.indexOf(itemLabel) % 2 == 0) Color(0xFFDCFCE7) else Color(0xFFDBEAFE)
                                                val tileText = if (isGas || tileColor == Color(0xFFDBEAFE)) Color(0xFF1D4ED8) else Color(0xFF166534)
                                                Surface(Modifier.weight(1f), color = tileColor, shape = RoundedCornerShape(8.dp)) {
                                                    Text(itemLabel, Modifier.padding(horizontal = 8.dp, vertical = 7.dp), color = tileText, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                                }
                                            }
                                            if (itemRow.size == 1) Spacer(Modifier.weight(1f))
                                        }
                                        Spacer(Modifier.height(4.dp))
                                    }
                                }
                                Text("Total: ${money(order.total)}", color = Ink, fontWeight = FontWeight.Bold)
                                Text("Pagamento: ${order.payment}", color = Ink)
                                if (order.payment == "PIX") {
                                    Text(
                                        "Pix para: ${if (pixRecipientName.isBlank()) "Nome não cadastrado" else pixRecipientName} • Chave: ${if (pixKey.isBlank()) "Nenhuma chave cadastrada" else pixKey}",
                                        color = Ink
                                    )
                                }
                                if (order.payment == "Dinheiro") {
                                    Text("Valor informado: ${money(order.cashGiven)}", color = Ink)
                                    Text("Troco previsto: ${money((order.cashGiven - order.total).coerceAtLeast(0.0))}", color = Ink)
                                }
                                Text(
                                    when {
                                        order.paid -> "Pagamento confirmado pela administração"
                                        order.pixReported && order.payment == "PIX" -> "Cliente informou que pagou Pix — conferir no banco"
                                        else -> "Pagamento aguardando conferência"
                                    },
                                    color = if (order.paid) Green else Orange, fontWeight = FontWeight.Bold
                                )
                                Text(
                                    if (deliveryMode && order.status == "Em entrega") "🔴 A CAMINHO — AINDA NÃO ENTREGUE"
                                    else if (deliveryMode && order.status == "Chegou ao endereço") "🟢 CHEGOU AO ENDEREÇO — AVISO REGISTRADO"
                                    else if (deliveryMode && order.status == "Autorizado") "🟠 AGUARDANDO SAÍDA"
                                    else "Status: ${order.status}",
                                    color = when {
                                        deliveryMode && order.status == "Chegou ao endereço" -> Green
                                        deliveryMode && order.status == "Em entrega" -> Color(0xFFB00020)
                                        deliveryMode && order.status == "Autorizado" -> Color(0xFF9A5700)
                                        order.status == "Concluído" -> Green
                                        order.status == "Cancelado" -> Color(0xFFB71C1C)
                                        else -> Blue
                                    },
                                    fontWeight = FontWeight.ExtraBold
                                )
                                if (order.status in listOf("Pendente", "Autorizado", "Em entrega", "Chegou ao endereço")) {
                                }
                                Spacer(Modifier.height(8.dp))
                                if (order.status == "Cancelado") {
                                    Text("PEDIDO CANCELADO — não será enviado para entregas nem para a rota.", color = Color(0xFFB71C1C), fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
                                } else if (!deliveryMode) {
                                    if (!order.paid) {
                                        Button(onClick = { updateOrder(order.copy(paid = true)) },
                                            modifier = Modifier.fillMaxWidth(),
                                            colors = ButtonDefaults.buttonColors(containerColor = Green)) {
                                            Text(if (order.payment == "PIX") "CONFIRMAR PIX RECEBIDO" else "CONFIRMAR DINHEIRO RECEBIDO")
                                        }
                                    } else {
                                        Button(onClick = { updateOrder(order.copy(status = "Autorizado")) },
                                            modifier = Modifier.fillMaxWidth(),
                                            colors = ButtonDefaults.buttonColors(containerColor = Orange)) {
                                            Text("AUTORIZAR E ENVIAR PARA ENTREGAS")
                                        }
                                    }
                                } else {
                                    Button(onClick = { openMap(order) }, modifier = Modifier.fillMaxWidth()) {
                                        Text("ABRIR ROTA NO GOOGLE MAPS")
                                    }
                                    if (order.status == "Em entrega") {
                                        Button(
                                            onClick = {
                                                updateOrder(order.copy(status = "Chegou ao endereço"))
                                                message = "Chegada registrada neste aparelho. O aviso no celular do cliente depende da conexão online entre os aparelhos."
                                            },
                                            modifier = Modifier.fillMaxWidth(),
                                            colors = ButtonDefaults.buttonColors(containerColor = Orange)
                                        ) {
                                            Text("🔔 AVISAR CLIENTE QUE CHEGUEI")
                                        }
                                    }
                                    if (order.status == "Autorizado" || order.status == "Em entrega" || order.status == "Chegou ao endereço") {
                                        Button(
                                            onClick = {
                                                val nextOrder = orders.filter { it.status == "Em entrega" && it.id != order.id }.minByOrNull { it.id }
                                                completeOrderAndCount(order) {
                                                    selectedOrders.remove(order.id)
                                                    if (nextOrder != null) {
                                                        message = "Entrega concluída! Totais atualizados. Abrindo a próxima rota: ${nextOrder.customer}."
                                                        openMap(nextOrder)
                                                    } else {
                                                        message = "Entrega concluída! Totais atualizados; pedido removido."
                                                    }
                                                }
                                            },
                                            modifier = Modifier.fillMaxWidth(),
                                            colors = ButtonDefaults.buttonColors(containerColor = Green)
                                        ) {
                                            Text("FINALIZAR ENTREGA")
                                        }
                                    }
                                    if (order.status == "Autorizado" || order.status == "Em entrega" || order.status == "Chegou ao endereço") {
                                        Spacer(Modifier.height(6.dp))
                                        Button(
                                            onClick = {
                                                removeOrder(order, false)
                                                selectedOrders.remove(order.id)
                                            },
                                            modifier = Modifier.fillMaxWidth(),
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB71C1C))
                                        ) {
                                            Text("CANCELAR ENTREGA")
                                        }
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                }
                MainButton("VOLTAR") {
                    selectedOrders.clear()
                    message = ""
                    page = if (deliveryMode) Page.HOME else Page.ADMIN
                }
            }

            Page.RECOVERY -> {
                Header("Recuperar senha", "📱")
                Text("A recuperação por telefone e SMS ainda não está configurada. Esta tela não redefine a senha.",
                    color = Color.White, textAlign = TextAlign.Center)
                Spacer(Modifier.height(20.dp))
                MainButton("VOLTAR") { page = Page.LOGIN }
            }
        }
    }
}

@Composable
private fun Header(title: String, emoji: String) {
    Text(emoji, fontSize = 40.sp)
    Spacer(Modifier.height(8.dp))
    Text(title, color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
    Spacer(Modifier.height(18.dp))
}

@Composable
private fun ModeCard(title: String, subtitle: String, emoji: String, accent: Color, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable { onClick() }, shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(emoji, fontSize = 32.sp)
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(title, color = Ink, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp)
                Text(subtitle, color = Color(0xFF5E6E82), fontSize = 12.sp)
            }
            Text("›", color = accent, fontSize = 32.sp)
        }
    }
}

@Composable
private fun MainButton(text: String, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth().height(50.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Blue)) {
        Text(text, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun MenuButton(text: String, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(16.dp)) {
        Text(text, Modifier.fillMaxWidth().padding(20.dp), color = Ink, fontSize = 17.sp, fontWeight = FontWeight.Bold)
    }
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun InputField(label: String, value: String, numeric: Boolean = false, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, color = Color.White) },
        textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 17.sp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Color.White, unfocusedTextColor = Color.White,
            focusedLabelColor = Color.White, unfocusedLabelColor = Color.White,
            cursorColor = LightBlue, focusedBorderColor = LightBlue, unfocusedBorderColor = LightBlue
        ),
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Decimal else KeyboardType.Text)
    )
}

@Composable
private fun ErrorText(value: String) {
    if (value.isNotBlank()) {
        Spacer(Modifier.height(8.dp))
        Text(value, color = Color(0xFFFFB4A8), textAlign = TextAlign.Center)
    }
}

@Composable
private fun BackButton(onClick: () -> Unit) {
    TextButton(onClick = onClick) { Text("Voltar", color = LightBlue) }
}
