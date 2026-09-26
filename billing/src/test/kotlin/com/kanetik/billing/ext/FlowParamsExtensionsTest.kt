package com.kanetik.billing.ext

import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.ProductDetails
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Before
import org.junit.Test

class FlowParamsExtensionsTest {

    private lateinit var productDetailsParamsBuilder: BillingFlowParams.ProductDetailsParams.Builder
    private lateinit var flowParamsBuilder: BillingFlowParams.Builder
    private val capturedOfferToken = slot<String>()

    @Before
    fun setUp() {
        mockkStatic(BillingFlowParams.ProductDetailsParams::class)
        mockkStatic(BillingFlowParams::class)

        productDetailsParamsBuilder = mockk(relaxed = true)
        every { productDetailsParamsBuilder.setProductDetails(any()) } returns productDetailsParamsBuilder
        every { productDetailsParamsBuilder.setOfferToken(capture(capturedOfferToken)) } returns productDetailsParamsBuilder
        every { productDetailsParamsBuilder.build() } returns mockk(relaxed = true)
        every { BillingFlowParams.ProductDetailsParams.newBuilder() } returns productDetailsParamsBuilder

        flowParamsBuilder = mockk(relaxed = true)
        every { flowParamsBuilder.setProductDetailsParamsList(any()) } returns flowParamsBuilder
        every { flowParamsBuilder.build() } returns mockk(relaxed = true)
        every { BillingFlowParams.newBuilder() } returns flowParamsBuilder
    }

    @After
    fun tearDown() {
        unmockkStatic(BillingFlowParams.ProductDetailsParams::class)
        unmockkStatic(BillingFlowParams::class)
    }

    @Test
    fun `returns null when oneTimePurchaseOfferDetailsList is null`() {
        val product = productDetails(offers = null)

        assertThat(product.toOneTimeFlowParams()).isNull()
    }

    @Test
    fun `returns null when oneTimePurchaseOfferDetailsList is empty`() {
        val product = productDetails(offers = emptyList())

        assertThat(product.toOneTimeFlowParams()).isNull()
    }

    @Test
    fun `returns null when offerSelector returns null for a multi-offer list`() {
        val product = productDetails(offers = listOf(offer("token-A"), offer("token-B")))

        val result = product.toOneTimeFlowParams(offerSelector = { null })

        assertThat(result).isNull()
    }

    @Test
    fun `default offerSelector picks the first offer among multiple`() {
        val product = productDetails(offers = listOf(offer("token-A"), offer("token-B")))

        val result = product.toOneTimeFlowParams()

        assertThat(result).isNotNull()
        assertThat(capturedOfferToken.captured).isEqualTo("token-A")
    }

    @Test
    fun `custom offerSelector's chosen offer token is set on the flow params`() {
        val offerA = offer("token-A")
        val offerB = offer("token-B")
        val product = productDetails(offers = listOf(offerA, offerB))

        val result = product.toOneTimeFlowParams(offerSelector = { offers -> offers.last() })

        assertThat(result).isNotNull()
        assertThat(capturedOfferToken.captured).isEqualTo("token-B")
    }

    @Test
    fun `custom offerSelector receives the full offer list`() {
        val offerA = offer("token-A")
        val offerB = offer("token-B")
        val offerC = offer("token-C")
        val product = productDetails(offers = listOf(offerA, offerB, offerC))
        var seen: List<ProductDetails.OneTimePurchaseOfferDetails>? = null

        product.toOneTimeFlowParams(offerSelector = { offers -> seen = offers; offers.firstOrNull() })

        assertThat(seen).containsExactly(offerA, offerB, offerC).inOrder()
    }

    private fun offer(token: String): ProductDetails.OneTimePurchaseOfferDetails =
        mockk<ProductDetails.OneTimePurchaseOfferDetails>(relaxed = true).also {
            every { it.offerToken } returns token
        }

    private fun productDetails(offers: List<ProductDetails.OneTimePurchaseOfferDetails>?): ProductDetails =
        mockk<ProductDetails>(relaxed = true).also {
            every { it.oneTimePurchaseOfferDetailsList } returns offers
        }
}
