package com.kanetik.billing.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.android.billingclient.api.ProductDetails
import com.kanetik.billing.BillingConnectionResult

class MainActivity : ComponentActivity() {

    private val viewModel: SampleViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Library-managed connection lifecycle — keeps the connection warm
        // while STARTED and tears down on DESTROYED.
        lifecycle.addObserver(viewModel.lifecycleManager)

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SampleScreen(
                        viewModel = viewModel,
                        onBuy = { product -> viewModel.buy(this, product) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SampleScreen(
    viewModel: SampleViewModel,
    onBuy: (ProductDetails) -> Unit,
) {
    val state by viewModel.state.collectAsState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Kanetik Billing Sample", style = MaterialTheme.typography.headlineSmall)
        ConnectionRow(state.connection)
        Button(onClick = viewModel::loadProducts) {
            Text("Query products")
        }
        if (state.loading) {
            CircularProgressIndicator()
        }
        state.products.forEach { product ->
            ProductCard(product = product, onBuy = { onBuy(product) })
        }
        if (state.log.isNotEmpty()) {
            LogCard(state.log)
        }
    }
}

@Composable
private fun ConnectionRow(result: BillingConnectionResult?) {
    val label = when (result) {
        null -> "Connecting…"
        is BillingConnectionResult.Success -> "Connected"
        else -> "Connection error: ${result::class.simpleName}"
    }
    Card(modifier = Modifier.fillMaxSize()) {
        Text(text = label, modifier = Modifier.padding(12.dp))
    }
}

@Composable
private fun ProductCard(product: ProductDetails, onBuy: () -> Unit) {
    Card(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = product.productId, style = MaterialTheme.typography.titleMedium)
            Text(text = product.name)
            Text(
                text = product.oneTimePurchaseOfferDetails?.formattedPrice ?: "(no offer details)",
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = onBuy) { Text("Buy") }
        }
    }
}

@Composable
private fun LogCard(lines: List<String>) {
    Card(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(text = "Log", style = MaterialTheme.typography.titleSmall)
            // Most recent first — the API surface is small enough that the
            // last few lines tell the whole story.
            lines.takeLast(15).reversed().forEach { line ->
                Text(text = line, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
        }
    }
}
