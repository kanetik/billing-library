# Kanetik Billing Library — consumer R8/proguard rules.
# These rules are bundled into the AAR and applied automatically to any
# consumer's release build. Consumers do not need to import or reference
# this file.

# Keep BillingLogger default implementations. Consumers reach the singletons
# via the BillingLogger.Noop / BillingLogger.Android companion properties,
# which is an indirection R8's reachability analysis sometimes drops on
# aggressive consumer configurations. Without these keeps, an opted-in
# logger could silently stop receiving messages in release builds.
-keep class com.kanetik.billing.logging.NoopBillingLogger { *; }
-keep class com.kanetik.billing.logging.AndroidLogLogger { *; }

# PBL's own AAR ships consumer-rules covering Play Billing types, and
# kotlinx.coroutines does the same for Flow / SharedFlow. We don't
# duplicate those keeps here — if Google or JetBrains drops them, we'd
# want to know rather than silently paper over the regression.
