package com.luszczuk.makebillingeasy

enum class ResultStatus {
    PENDING,
    SUCCESS,
    CANCELED,
    ERROR
}

enum class RetryType {
    SIMPLE_RETRY,
    EXPONENTIAL_RETRY,
    REQUERY_PURCHASE_RETRY,
    NONE
}