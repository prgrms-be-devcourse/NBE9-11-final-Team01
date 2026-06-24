package com.develop.snaptix.global.exception

class FieldValidationException(
    val fieldErrors: List<ErrorResponse.FieldError>,
) : RuntimeException(ErrorCode.VALIDATION_FAILED.message)
