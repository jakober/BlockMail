package com.jakober.blockpdf

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
 * Play-Billing für „BlockPDF Pro“ — bewusst einfacher als in BlockMail:
 * EIN Abo, EIN Basis-Tarif, kein Tarifwechsel, kein Server. Der Preis
 * kommt immer aus Play (Währung/Steuern je Land), fest steht nur der
 * Ersatztext, bis Play geantwortet hat.
 */
object PdfBilling {

    const val PRODUCT_ID = "blockpdf_pro"
    const val BASE_PLAN = "pdf-199"

    private var client: BillingClient? = null

    @Volatile
    private var connecting = false

    private val _isPro = MutableStateFlow(false)
    val isProFlow: StateFlow<Boolean> = _isPro

    private val _price = MutableStateFlow<String?>(null)
    /** Monatspreis, wie Play ihn anzeigt — null bis zur ersten Antwort. */
    val priceFlow: StateFlow<String?> = _price

    private val _productDetails = MutableStateFlow<ProductDetails?>(null)

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
        if (connecting) return
        connecting = true
        runCatching {
            c.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(billingResult: BillingResult) {
                    connecting = false
                    if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                        queryProduct()
                        refreshPurchases()
                    }
                }

                override fun onBillingServiceDisconnected() {
                    connecting = false
                }
            })
        }.onFailure { connecting = false }
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
                val details = result.productDetailsList.firstOrNull()
                _productDetails.value = details
                _price.value = details?.subscriptionOfferDetails
                    ?.firstOrNull { it.basePlanId == BASE_PLAN }
                    ?.pricingPhases?.pricingPhaseList?.lastOrNull()?.formattedPrice
            }
        }
    }

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
                if (active.isEmpty()) {
                    _isPro.value = false
                } else {
                    active.forEach { handlePurchase(it) }
                }
            }
        }
    }

    private fun handlePurchase(purchase: Purchase) {
        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) return
        if (purchase.products.none { it == PRODUCT_ID }) return
        _isPro.value = true
        if (!purchase.isAcknowledged) {
            val c = client ?: return
            val params = AcknowledgePurchaseParams.newBuilder()
                .setPurchaseToken(purchase.purchaseToken)
                .build()
            runCatching { c.acknowledgePurchase(params) { } }
        }
    }

    /** Öffnet den Play-Kaufdialog. false = gerade nicht möglich. */
    fun purchase(context: Context): Boolean {
        val c = client ?: return false
        val activity = context.findActivity() ?: return false
        val details = _productDetails.value ?: run {
            queryProduct()
            return false
        }
        // Neukauf: das Angebot mit den meisten Preisphasen (falls es je eine
        // Testphase gibt, bekommt der Kunde sie so auch wirklich)
        val offerToken = details.subscriptionOfferDetails
            ?.filter { it.basePlanId == BASE_PLAN }
            ?.maxByOrNull { it.pricingPhases.pricingPhaseList.size }
            ?.offerToken ?: return false
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
            c.launchBillingFlow(activity, params).responseCode ==
                BillingClient.BillingResponseCode.OK
        }.getOrDefault(false)
    }

    private fun Context.findActivity(): Activity? {
        var c: Context? = this
        while (c is ContextWrapper) {
            if (c is Activity) return c
            c = c.baseContext
        }
        return null
    }
}
