package com.develop.snaptix.global.alert.service

import com.develop.snaptix.global.alert.model.AlertContext

interface AlertService {
    fun notify(context: AlertContext)
}
