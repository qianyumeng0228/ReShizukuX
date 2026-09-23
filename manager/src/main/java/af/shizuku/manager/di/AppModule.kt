package af.shizuku.manager.di

import af.shizuku.manager.database.ActivityLogManager
import af.shizuku.manager.database.AppContextManager
import org.koin.dsl.module

val appModule = module {
    single { ActivityLogManager }
    single { AppContextManager }
}
