package com.jakober.klarmail.data

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Google-Play-Billing für das Abo „BlockMail Pro“.
 *
 * Ablauf:
 *  1. [init] beim App-Start: Verbindung aufbauen und vorhandene Käufe
 *     abfragen — damit ist Pro nach Neuinstallation sofort wieder aktiv.
 *  2. [purchase] öffnet den Play-Kaufdialog (inklusive der 3-Tage-Testphase,
 *     die als Angebot am Basis-Tarif in der Play Console hinterlegt ist).
 *  3. Jeder Kauf wird bestätigt (acknowledge) — ohne Bestätigung erstattet
 *     Google das Abo nach drei Tagen automatisch zurück.
 *
 * Der Kauf-Token wird in [Prefs.purchaseToken] abgelegt und von
 * [com.jakober.klarmail.ai.ClaudeClient] als Kopfzeile mitgeschickt, damit
 * der BlockMail-Server das Abo serverseitig gegenprüfen kann.
 *
 * Solange [ProAccess.TEST_PHASE_UNLOCK] auf true steht, ist Pro ohnehin für
 * alle frei — dieser Code läuft dann zwar mit, ändert aber nichts.
 */
object BillingManager {

    /** Produkt-ID des Abos in der Play Console. */
    const val PRODUCT_ID = "blockmail_pro"

    private var client: BillingClient? = null
    private var appContext: Context? = null

    /** Zuletzt geladene Angebotsdaten (für Preis-Anzeige und Kauf). */
    private val _productDetails = MutableStateFlow<ProductDetails?>(null)
    val productDetails: StateFlow<ProductDetails?> = _productDetails

    /** Formatierter Preis aus dem Store, z. B. „4,90 €“ — null, wenn unbekannt. */
    val formattedPrice: String?
        get() = _productDetails.value
            ?.subscriptionOfferDetails
            ?.lastOrNull()
            ?.pricingPhases
            ?.pricingPhaseList
            ?.lastOrNull()
            ?.formattedPrice

    fun init(context: Context) {
        if (client != null) return
        appContext = context.applicationContext
        val c = BillingClient.newBuilder(context.applicationContext)
            .setListener { result, purchases ->
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    purchases?.forEach { handlePurchase(it) }
                }
            }
            .enablePendingPurchases(
                PendingPurchasesParams.newBuilder().enableOneTimeProducts().build()
            )
            .build()
        client = c
        connect()
    }

    private fun connect() {
        val c = client ?: return
        runCatching {
            c.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(billingResult: BillingResult) {
                    if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                        queryProduct()
                        refreshPurchases()
                    }
                }

                override fun onBillingServiceDisconnected() {
                    // Beim nächsten Bedarf wird neu verbunden (siehe purchase/refresh)
                }
            })
        }
    }

    /** Angebotsdaten laden (Preis + Angebots-Token für den Kaufdialog). */
    private fun queryProduct() {
        val c = client ?: return
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(PRODUCT_ID)
                        .setProductType(BillingClient.ProductType.SUBS)
                        .build()
                )
            )
            .build()
        runCatching {
            c.queryProductDetailsAsync(params) { _, result ->
                _productDetails.value = result.productDetailsList.firstOrNull()
            }
        }
    }

    /**
     * Vorhandene Abos abfragen und den Pro-Status setzen. Wird beim App-Start
     * und nach jedem Kauf aufgerufen.
     */
    fun refreshPurchases() {
        val c = client ?: return
        if (!c.isReady) {
            connect()
            return
        }
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS)
            .build()
        runCatching {
            c.queryPurchasesAsync(params) { result, purchases ->
                if (result.responseCode != BillingClient.BillingResponseCode.OK) return@queryPurchasesAsync
                val active = purchases.filter {
                    it.purchaseState == Purchase.PurchaseState.PURCHASED
                }
                active.forEach { handlePurchase(it) }
                if (active.isEmpty()) {
                    Prefs.purchaseToken = ""
                    ProAccess.setSubscribed(false)
                }
            }
        }
    }

    /** Kauf bestätigen, Token merken und Pro freischalten. */
    private fun handlePurchase(purchase: Purchase) {
        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) return
        if (purchase.products.none { it == PRODUCT_ID }) return
        Prefs.purchaseToken = purchase.purchaseToken
        ProAccess.setSubscribed(true)
        if (!purchase.isAcknowledged) {
            val c = client ?: return
            val params = AcknowledgePurchaseParams.newBuilder()
                .setPurchaseToken(purchase.purchaseToken)
                .build()
            runCatching { c.acknowledgePurchase(params) { } }
        }
    }

    /**
     * Öffnet den Play-Kaufdialog. Gibt false zurück, wenn das nicht möglich
     * ist (kein Play-Dienst, Angebot noch nicht geladen, kein Activity-
     * Kontext) — der Aufrufer zeigt dann einen Hinweis.
     */
    fun purchase(context: Context): Boolean {
        val c = client ?: return false
        val activity = context.findActivity() ?: return false
        val details = _productDetails.value ?: run {
            queryProduct()
            return false
        }
        // Letztes Angebot nehmen: Play stellt das Angebot mit Testphase
        // voran; das Basis-Angebot ohne Testphase steht am Ende der Liste.
        val offerToken = details.subscriptionOfferDetails
            ?.firstOrNull()?.offerToken ?: return false
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(details)
                        .setOfferToken(offerToken)
                        .build()
                )
            )
            .build()
        return runCatching {
            val r = c.launchBillingFlow(activity, params)
            r.responseCode == BillingClient.BillingResponseCode.OK
        }.getOrDefault(false)
    }

    /** Play-Store-Seite zur Abo-Verwaltung (Kündigen, Zahlungsmittel). */
    fun manageSubscriptionUrl(): String =
        "https://play.google.com/store/account/subscriptions" +
            "?sku=$PRODUCT_ID&package=com.jakober.klarmail"

    private fun Context.findActivity(): Activity? {
        var c: Context? = this
        while (c is ContextWrapper) {
            if (c is Activity) return c
            c = c.baseContext
        }
        return null
    }
}
