package com.filmax.core.navigation.di

import com.filmax.core.navigation.AppNavigator
import com.filmax.core.navigation.Navigator
import org.koin.dsl.module

/** Навигатор один на приложение: его получают все экраны, а команды разбирает граф. */
val navigationModule = module {
    single<Navigator> { AppNavigator() }
}
