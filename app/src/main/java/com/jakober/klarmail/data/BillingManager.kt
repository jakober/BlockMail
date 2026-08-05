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
 * Google-Play-Billing für „BlockMail Pro“ mit zwei Stufen.
 *
 * Beide Stufen sind Basis-Tarife EINES Abo-Produkts (so empfiehlt es
 * Google): Damit kann der Nutzer über Play direkt zwischen ihnen wechseln,
 * ohne zu kündigen.
 *
 *  - [BASE_PLAN_PRO]  — 150 KI-Anfragen im Monat
 *  - [BASE_PLAN_PLUS] — 300 KI-Anfragen im Monat
 *
 * Die Preise kommen IMMER aus dem Play Store (siehe [priceFor]) — niemals
 * fest verdrahtet, sonst stimmen Währung und Steuersatz je Land nicht.
 * Wie viele Anfragen tatsächlich übrig sind, weiß der BlockMail-Server
 * (siehe [AiQuota]).
 */
object BillingManager {

    /** Abo-Produkt in der Play Console. */
    const val PRODUCT_ID = "blockmail_pro"

    /** Basis-Tarif „Pro“: 150 KI-Anfragen im Monat. */
    const val BASE_PLAN_PRO = "pro-150"

    /** Basis-Tarif „Pro+“: 300 KI-Anfragen im Monat. */
    const val BASE_PLAN_PLUS = "pro-300"

    /** Enthaltene KI-Anfragen je Tarif (nur zur Anzeige). */
    const val REQUESTS_PRO = 150
    const val REQUESTS_PLUS = 300

    private var client: BillingClient? = null

    /** Tarif, für den gerade der Play-Kaufdialog geöffnet wurde. */
    private var pendingPlan = ""

    private val _productDetails = MutableStateFlow<ProductDetails?>(null)
    val productDetails: StateFlow<ProductDetails?> = _productDetails

    /** Aktiver Basis-Tarif ("" = kein Abo). */
    private val _activePlan = MutableStateFlow("")
    val activePlan: StateFlow<String> = _activePlan

    fun init(context: Context) {
        if (client != null) return
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
                    // Beim nächsten Bedarf wird neu verbunden
                }
            })
        }
    }

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
     * Angebot eines Basis-Tarifs. Gibt es dazu ein Angebot mit
     * Gratis-Testphase, hat dieses mehrere Preisphasen — das nehmen wir,
     * damit der Nutzer die 3 Tage auch wirklich bekommt.
     */
    private fun offerFor(basePlanId: String): ProductDetails.SubscriptionOfferDetails? {
        val offers = _productDetails.value?.subscriptionOfferDetails
            ?.filter { it.basePlanId == basePlanId } ?: return null
        return offers.maxByOrNull { it.pricingPhases.pricingPhaseList.size }
    }

    /**
     * Preis eines Basis-Tarifs, wie ihn Play anzeigt (z. B. „4,90 €“) —
     * null, solange die Angebotsdaten noch nicht geladen sind. Genommen
     * wird die LETZTE Preisphase, also der Dauerpreis nach der Testphase.
     */
    fun priceFor(basePlanId: String): String? =
        offerFor(basePlanId)?.pricingPhases?.pricingPhaseList?.lastOrNull()?.formattedPrice

    /** Enthaltene Anfragen je Basis-Tarif. */
    fun requestsFor(basePlanId: String): Int =
        if (basePlanId == BASE_PLAN_PLUS) REQUESTS_PLUS else REQUESTS_PRO

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
                if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                    return@queryPurchasesAsync
                }
                val active = purchases.filter {
                    it.purchaseState == Purchase.PurchaseState.PURCHASED
                }
                active.forEach { handlePurchase(it) }
                if (active.isEmpty()) {
                    Prefs.purchaseToken = ""
                    Prefs.proPlan = ""
                    _activePlan.value = ""
                    ProAccess.setSubscribed(false)
                    AiQuota.clear()
                } else {
                    _activePlan.value = Prefs.proPlan
                    AiQuota.refreshSoon()
                }
            }
        }
    }

    private fun handlePurchase(purchase: Purchase) {
        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) return
        if (purchase.products.none { it == PRODUCT_ID }) return
        Prefs.purchaseToken = purchase.purchaseToken
        // Welcher Basis-Tarif gekauft wurde, verrät Play erst nach der
        // Server-Prüfung sicher — der Merker dient nur der Anzeige und wird
        // von der Kontingent-Abfrage überschrieben (siehe [planFromServer]).
        val plan = pendingPlan.ifBlank { Prefs.proPlan }.ifBlank { BASE_PLAN_PRO }
        pendingPlan = ""
        Prefs.proPlan = plan
        _activePlan.value = plan
        ProAccess.setSubscribed(true)
        AiQuota.refreshSoon()
        if (!purchase.isAcknowledged) {
            val c = client ?: return
            val params = AcknowledgePurchaseParams.newBuilder()
                .setPurchaseToken(purchase.purchaseToken)
                .build()
            runCatching { c.acknowledgePurchase(params) { } }
        }
    }

    /**
     * Öffnet den Play-Kaufdialog für einen Basis-Tarif. Läuft bereits ein
     * Abo im anderen Tarif, wird gewechselt statt neu gekauft (Play rechnet
     * den Restbetrag an). false = Kauf nicht möglich (Play nicht bereit,
     * Angebot noch nicht geladen).
     */
    fun purchase(context: Context, basePlanId: String): Boolean {
        val c = client ?: return false
        val activity = context.findActivity() ?: return false
        val details = _productDetails.value ?: run {
            queryProduct()
            return false
        }
        val offerToken = offerFor(basePlanId)?.offerToken ?: return false
        val builder = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(details)
                        .setOfferToken(offerToken)
                        .build()
                )
            )
        val oldToken = Prefs.purchaseToken
        if (oldToken.isNotBlank() && Prefs.proPlan.isNotBlank() &&
            Prefs.proPlan != basePlanId
        ) {
            builder.setSubscriptionUpdateParams(
                BillingFlowParams.SubscriptionUpdateParams.newBuilder()
                    .setOldPurchaseToken(oldToken)
                    .setSubscriptionReplacementMode(
                        BillingFlowParams.SubscriptionUpdateParams
                            .ReplacementMode.CHARGE_PRORATED_PRICE
                    )
                    .build()
            )
        }
        pendingPlan = basePlanId
        return runCatching {
            val r = c.launchBillingFlow(activity, builder.build())
            r.responseCode == BillingClient.BillingResponseCode.OK
        }.getOrDefault(false)
    }

    /**
     * Der BlockMail-Server hat den Kauf bei Google geprüft und kennt den
     * echten Tarif — der schlägt jede lokale Vermutung.
     */
    fun planFromServer(basePlanId: String) {
        if (basePlanId.isBlank()) return
        Prefs.proPlan = basePlanId
        _activePlan.value = basePlanId
    }

    /** Play-Store-Seite zur Abo-Verwaltung (Tarif wechseln, kündigen). */
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
