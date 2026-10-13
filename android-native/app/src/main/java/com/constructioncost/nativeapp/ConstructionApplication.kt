package com.constructioncost.nativeapp

import android.app.Application
import com.constructioncost.nativeapp.data.repository.LedgerRepository

class ConstructionApplication : Application() {
    val repository by lazy { LedgerRepository(this) }
}
